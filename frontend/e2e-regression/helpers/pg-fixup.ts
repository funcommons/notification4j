import crypto from 'node:crypto'
import net from 'node:net'

/**
 * 极简 PostgreSQL 客户端（零依赖，SCRAM-SHA-256）——仅用于「缺陷绕行脚手架」：
 * ND-L5-01 已修复，保留备查（当前 e2e 用例已全部改走真实 API，不再引用本文件）。
 *
 * 【缺陷 ND-L5-01（P1）】渠道 ENABLED 无 API 通路：EMAIL verify 恒 10604（提示「首次投递时校验」），
 * patch ENABLED 恒 10610（要求 last_verify_at 非空），而投递计划/引擎均只认 ENABLED →
 * 纯 API 形态下外发引擎线端到端不可达。既有 IT（NfySmokeTest/NfyDeliveryPlanTest 等）对同一缺口
 * 采用「mapper 置 ENABLED 离线手法」同口径。本 helper 仅做最小绕行：
 *   UPDATE nfya_channel SET status='ENABLED', last_verify_at=now()
 *   WHERE target=<本线自建 uniq target> AND tenant_id=(SELECT id FROM nfya_tenant WHERE name=<本线自建 uniq 租户名>)
 * 严格限定本线自建租户数据，不触碰其他数据；缺陷本身在 l5-engine.spec.ts L5-00 场景独立取证登记。
 */

const PG = {
  host: '127.0.0.1',
  port: 25432,
  user: 'admin',
  database: 'notification4j',
  password: 'test@2026',
}

interface PgMsg {
  type: string
  body: Buffer
}

function esc(s: string): string {
  return s.replace(/'/g, "''")
}

async function pgQuery(sql: string): Promise<string[][]> {
  return new Promise<string[][]>((resolve, reject) => {
    const sock = net.connect(PG.port, PG.host)
    let buf = Buffer.alloc(0)
    const queue: PgMsg[] = []
    const waiters: Array<{ resolve: (m: PgMsg) => void; reject: (e: Error) => void }> = []
    const fail = (e: Error) => {
      sock.destroy()
      while (waiters.length) waiters.shift()!.reject(e)
      reject(e)
    }
    const feed = () => {
      for (;;) {
        if (buf.length < 5) return
        const type = String.fromCharCode(buf[0])
        const len = buf.readInt32BE(1)
        if (buf.length < len + 1) return
        const msg: PgMsg = { type, body: buf.slice(5, len + 1) }
        buf = buf.slice(len + 1)
        if (type === 'E') {
          fail(new Error('PG ErrorResponse: ' + errText(msg.body)))
          return
        }
        const w = waiters.shift()
        if (w) w.resolve(msg)
        else queue.push(msg)
      }
    }
    const next = (): Promise<PgMsg> =>
      queue.length ? Promise.resolve(queue.shift()!) : new Promise((res, rej) => waiters.push({ resolve: res, reject: rej }))
    const send = (type: string, body: Buffer) => {
      const head = Buffer.alloc(5)
      head.write(type, 0)
      head.writeInt32BE(body.length + 4, 1)
      sock.write(Buffer.concat([head, body]))
    }

    sock.on('error', fail)
    sock.on('connect', () => {
      const params = Buffer.from(`user\0${PG.user}\0database\0${PG.database}\0\0`, 'utf8')
      const start = Buffer.alloc(params.length + 8)
      start.writeInt32BE(params.length + 8, 0)
      start.writeInt32BE(196608, 4) // protocol 3.0
      params.copy(start, 8)
      sock.write(start)
    })

    void (async () => {
      try {
        // ---- 认证循环（SCRAM-SHA-256）----
        let authed = false
        let clientFirstBare = ''
        let serverFirst = ''
        for (;;) {
          const m = await next()
          if (m.type !== 'R') {
            if (m.type === 'Z') break
            continue
          }
          const kind = m.body.readInt32BE(0)
          if (kind === 0) {
            authed = true
            continue
          }
          if (kind === 10) {
            // AuthenticationSASL：机制名列表，取 SCRAM-SHA-256
            const mechs = m.body.slice(4).toString('utf8').split('\0').filter(Boolean)
            if (!mechs.includes('SCRAM-SHA-256')) throw new Error('PG 无 SCRAM-SHA-256: ' + mechs.join(','))
            const nonce = crypto.randomBytes(18).toString('base64')
            clientFirstBare = `n=${PG.user},r=${nonce}`
            const init = `n,,${clientFirstBare}`
            const p = Buffer.alloc('SCRAM-SHA-256'.length + 1 + 4 + init.length)
            let o = p.write('SCRAM-SHA-256\0', 0)
            p.writeInt32BE(init.length, o)
            o += 4
            p.write(init, o)
            send('p', p)
            continue
          }
          if (kind === 11) {
            serverFirst = m.body.slice(4).toString('utf8')
            const parts = Object.fromEntries(serverFirst.split(',').map((kv) => [kv[0], kv.slice(2)]))
            const salted = crypto.pbkdf2Sync(PG.password, Buffer.from(parts.s, 'base64'), parseInt(parts.i, 10), 32, 'sha256')
            const clientKey = crypto.createHmac('sha256', salted).update('Client Key').digest()
            const storedKey = crypto.createHash('sha256').update(clientKey).digest()
            const cFinalWoProof = `c=biws,r=${parts.r}`
            const authMessage = `${clientFirstBare},${serverFirst},${cFinalWoProof}`
            const clientSig = crypto.createHmac('sha256', storedKey).update(authMessage).digest()
            const proof = Buffer.alloc(clientKey.length)
            for (let i = 0; i < proof.length; i++) proof[i] = clientKey[i] ^ clientSig[i]
            send('p', Buffer.from(`${cFinalWoProof},p=${proof.toString('base64')}`, 'utf8'))
            continue
          }
          if (kind === 12) continue // SASLFinal（v=服务端签名，此处信任 TLS 外的本地链路不校验）
          if (kind === 3) {
            send('p', Buffer.from(`${PG.password}\0`, 'utf8'))
            continue
          }
          throw new Error('不支持的 PG 认证方式: ' + kind)
        }
        if (!authed) throw new Error('PG 认证未完成')

        // ---- 简单查询 ----
        send('Q', Buffer.from(sql + '\0', 'utf8'))
        const rows: string[][] = []
        for (;;) {
          const m = await next()
          if (m.type === 'D') {
            const n = m.body.readInt16BE(0)
            const row: string[] = []
            let o = 2
            for (let i = 0; i < n; i++) {
              const l = m.body.readInt32BE(o)
              o += 4
              if (l === -1) row.push('')
              else {
                row.push(m.body.slice(o, o + l).toString('utf8'))
                o += l
              }
            }
            rows.push(row)
          } else if (m.type === 'Z') {
            break
          }
        }
        send('X', Buffer.alloc(0))
        sock.end()
        resolve(rows)
      } catch (e) {
        fail(e instanceof Error ? e : new Error(String(e)))
      }
    })()

    sock.on('data', (d: Buffer) => {
      buf = Buffer.concat([buf, d])
      feed()
    })
  })
}

function errText(body: Buffer): string {
  const parts: string[] = []
  let o = 0
  for (;;) {
    if (o >= body.length || body[o] === 0) break
    const code = String.fromCharCode(body[o])
    o++
    const end = body.indexOf(0, o)
    if (end < 0) break
    parts.push(code + '=' + body.slice(o, end).toString('utf8'))
    o = end + 1
  }
  return parts.join(' ')
}

/**
 * 缺陷绕行（见文件头）：按 uniq target + 本线自建租户名精确置 ENABLED，返回 channel_id。
 * 影响行数为 0 视为失败（ loud fail，防静默绿）。
 */
export async function enableChannelOffroad(target: string, tenantName: string): Promise<string> {
  const sql = `UPDATE nfya_channel c SET status = 'ENABLED', last_verify_at = now() ` +
    `WHERE c.target = '${esc(target)}' ` +
    `AND c.tenant_id = (SELECT id FROM nfya_tenant WHERE name = '${esc(tenantName)}') ` +
    `RETURNING id::text`
  const rows = await pgQuery(sql)
  if (rows.length === 0) {
    throw new Error(`enableChannelOffroad 未命中（target=${target}, tenant=${tenantName}）——检查注册是否成功`)
  }
  return rows[0][0]
}
