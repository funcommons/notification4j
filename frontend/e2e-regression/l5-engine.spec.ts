import { test, expect, type Page } from '@playwright/test'
import fs from 'node:fs'
import path from 'node:path'
import {
  BASE, platformToken, createTenant, nfy, expectCode0, evidence, get, post, put, patch, uniq, until,
  type NfyResp, type Tenant,
} from './helpers/nfy'
import { SmtpSink } from './helpers/smtp-sink'

/**
 * L5 外发引擎线 · Playwright 端到端回归
 * 契约（技术方案 §4.3）：send→投递计划（PENDING）→引擎领取（SKIP LOCKED→SENDING）→SPI 投递；
 * 失败退避（测试实例 2/5/10s）重试≤3 → DEAD（retry_count=4）；渠道连续失败≥5 熔断 DISABLED+属主站内信；
 * quiet_hours 计划期「推迟发送非丢弃」（next_retry_at=窗结束）；DLV-001 查询（target 脱敏）/DLV-002 仅 DEAD 可重投。
 * 测试实例提速参数（bin/start-app.sh）：scan 500ms / backoff 2,5,10 / SMTP=localhost:3925（本 spec 的 sink）。
 * 注意：INAPP 站内信发送时已直接落库、不走引擎；引擎只外发站外渠道（DINGTALK/WECOM/FEISHU/EMAIL）。
 *
 * 【缺陷 ND-L5-01（P1）已修复】原状：EMAIL verify 恒 10604、patch ENABLED 恒 10610 → 渠道 ENABLED
 * 无 API 通路，外发链纯 API 形态断裂（曾以 helpers/pg-fixup 直改库绕行）。修复后契约：
 * ① patch ENABLED 对 EMAIL 豁免已验证前置（verify 10604「首次投递时校验」文案契约不变）——PENDING 直启放行；
 * ② EMAIL 熔断态（fail_count≥5）显式重新启用即视为重新验证（ENABLED 同时 fail_count 归 0）；
 * ③ 投递成功回写 last_verify_at（投递成功即渠道可用性证据）。IM（DINGTALK/WECOM/FEISHU）口径不变：
 * 仍须真实 verify 且熔断后须先重新验证（10610）。本线全链（注册→直启→计划→引擎→SMTP→重试→DEAD→重投→
 * 熔断→重启用→免打扰）均为真实 API 端到端，零绕行。
 *
 * 【第 2 轮证据改版】投递页是引擎线的天然 UI 证据面：外发成功链→投递页 SUCCESS 行、失败退避→FAILED 行
 * （退避窗内截图）、DEAD→DEAD 行、DLV-002 重投→UI「重投」按钮动作+行转 SUCCESS（前后图）、quiet_hours→
 * PENDING 推迟行、熔断→渠道页 DISABLED 徽标+消息页属主站内信。均为宿主握手页驱动真实 SPA 截图
 * （NFY_EVIDENCE_DIR 参数化）；next_retry_at 等 UI 暂无列的字段以 API 面证据补位（P3 改进注记）。
 */

const SINK_PORT = 3925

interface DeliveryVO {
  delivery_id: string
  status: string
  retry_count: string | number
  next_retry_at: string | number | null
  error_message: string
  sent_at: string | number | null
  created_at: string | number | null
  title: string
  target: string
  channel_type: string
  userid: string
}
interface DeliveryListResp { list: DeliveryVO[]; total: number }

const sigOf = (t: Tenant) => ({ key: t.open_id, secret: t.tenant_secret })
const opt = (t: Tenant, userid?: string) => ({ token: t.token, userId: userid, sig: sigOf(t) })

// ---------- 第 2 轮证据规约：UI 面用例以真实 SPA 界面截图为关键证据（投递页=引擎线天然 UI 面） ----------
const SHOTS = process.env.NFY_EVIDENCE_DIR
  ? path.resolve(process.env.NFY_EVIDENCE_DIR)
  : path.resolve(process.cwd(), '../docs/test/report/local-run/screenshots')
const HOST = 'http://localhost:3000/nfy-host.html'
const fl = (p: Page) => p.frameLocator('#nfy')

/** 真实界面截图（fullPage） */
async function shot(p: Page, name: string): Promise<string> {
  fs.mkdirSync(SHOTS, { recursive: true })
  const file = path.join(SHOTS, `${name}.png`)
  await p.screenshot({ path: file, fullPage: true })
  return file
}

test.describe('L5 外发引擎线', () => {
  let page: Page
  let plat: string
  let t: Tenant
  let tenantName = ''
  const sink = new SmtpSink()

  test.beforeAll(async ({ browser }) => {
    page = await browser.newPage()
    plat = await platformToken()
    tenantName = uniq('L5引擎线')
    t = await createTenant(plat, tenantName)
    await sink.start(SINK_PORT)
  })
  test.afterAll(async () => {
    await sink.stop()
    await page.close()
  })

  const createType = async (code: string, channels: string[]) =>
    expectCode0(await nfy<{ type_id: string }>(await post(`${BASE}/nfy/api/v1/admin/types`,
      { type_code: code, name: '类型-' + code, default_channels: channels }, { token: t.token })))
  const regEmail = async (userid: string, target: string) =>
    nfy<{ channel_id: string; status: string }>(await post(`${BASE}/nfy/api/v1/runtime/channels`,
      { channel_type: 'EMAIL', name: '邮箱渠道-' + userid, target }, opt(t, userid)))
  /** 注册 EMAIL（PENDING）→ PATCH ENABLED（ND-L5-01 已修复：EMAIL 直启为正式契约），返回 channel_id */
  const regEmailEnabled = async (userid: string): Promise<{ chId: string; target: string }> => {
    const target = `${uniq(userid.toLowerCase())}@e2e.test`
    const chId = expectCode0(await regEmail(userid, target)).data.channel_id
    const p = expectCode0(await nfy<{ status: string }>(await patch(
      `${BASE}/nfy/api/v1/runtime/channels/${chId}`, { status: 'ENABLED' }, opt(t, userid))))
    expect(p.data.status, 'PATCH ENABLED 生效').toBe('ENABLED')
    return { chId, target }
  }
  const sendMsg = async (userid: string, typeCode: string, bizNo: string, title: string) =>
    nfy<{ message_id: string; delivery_planned: number; inapp_saved: boolean }>(await post(`${BASE}/nfy/api/v1/runtime/messages`,
        { type_code: typeCode, user_ids: [userid], title, content: 'L5 引擎线内容', biz_no: bizNo }, opt(t, userid)))
  const deliveries = async (bizNo: string) =>
    nfy<DeliveryListResp>(await get(`${BASE}/nfy/api/v1/admin/deliveries?biz_no=${bizNo}&limit=10`, { token: t.token }))
  const putSubs = async (userid: string, items: Array<Record<string, unknown>>) =>
    nfy<{ saved_count: number }>(await put(`${BASE}/nfy/api/v1/runtime/subscriptions`, { items }, opt(t, userid)))
  const firstDelivery = async (bizNo: string): Promise<DeliveryVO | null> =>
    (await deliveries(bizNo)).data.list[0] ?? null
  const retryApi = async (deliveryId: string) =>
    nfy<{ delivery_id: string; status: string }>(await post(`${BASE}/nfy/api/v1/admin/deliveries/${deliveryId}/retry`, undefined, { token: t.token }))
  const channelRow = async (userid: string, chId: string) => {
    const list = expectCode0(await nfy<{ list: Array<Record<string, unknown>> }>(
      await get(`${BASE}/nfy/api/v1/runtime/channels`, opt(t, userid))))
    return list.data.list.find((c) => c.channel_id === chId)!
  }

  /** 打开宿主握手页驱动真实 SPA（iframe#nfy + NFY_TOKEN 握手） */
  const openUI = async (pageName: string, userid: string) => {
    await page.goto(`${HOST}?page=${pageName}&token=${t.token}&user_id=${userid}`)
    return fl(page)
  }
  /** 打开投递页并按 biz_no 过滤查询（管理面 SPA；成功后表格恰 1 行） */
  const openDeliveries = async (userid: string, bizNo: string) => {
    const f = await openUI('deliveries', userid)
    await expect(f.getByRole('heading', { name: '投递记录' })).toBeVisible()
    await f.getByPlaceholder('biz_no').fill(bizNo)
    await f.getByRole('button', { name: '查询' }).click()
    const row = f.locator('.el-table__body-wrapper .el-table__row')
    await expect(row, 'biz_no 过滤后恰 1 行').toHaveCount(1)
    return { f, row }
  }

  // 跨用例接力状态（workers=1 串行，声明序执行）
  let l502DeliveryId = ''
  let l505DeliveryId = ''
  let l505ChannelId = ''

  test('L5-00 修复回归 ND-L5-01：EMAIL 渠道纯 API 通路 ENABLED 可达（原 P1 缺陷）', async () => {
    const code = 'DEF' + uniq('').toUpperCase()
    await createType(code, ['EMAIL'])
    const reg = expectCode0(await regEmail('u0', `${uniq('u0')}@e2e.test`))
    const chId = reg.data.channel_id
    expect(reg.data.status, '注册即 PENDING').toBe('PENDING')
    // ① EMAIL verify 契约不变：主动验证仍恒 10604（「首次投递时校验」文案契约保留）
    const v = await nfy<{ status: string }>(await post(`${BASE}/nfy/api/v1/runtime/channels/${chId}/verify`, undefined, opt(t, 'u0')))
    expect(v.code, 'EMAIL verify 契约不变仍 10604').toBe(10604)
    expect(v.message, '「首次投递时校验」文案契约保留').toContain('首次投递时校验')
    // ② 修复点：patch ENABLED 对 EMAIL 豁免已验证前置 → code 0（原恒 10610 死锁）
    const p = expectCode0(await nfy<{ status: string }>(await patch(
      `${BASE}/nfy/api/v1/runtime/channels/${chId}`, { status: 'ENABLED' }, opt(t, 'u0'))))
    expect(p.data.status, 'PENDING 直启生效').toBe('ENABLED')
    // ③ 列表复核：状态真落库 ENABLED（投递计划按 ENABLED 过滤 → 外发链闭合，L5-01 实证）
    const row = await channelRow('u0', chId)
    expect(row.status, '列表复核 ENABLED').toBe('ENABLED')
    await evidence(page, 'L5-00-修复回归ND-L5-01', {
      缺陷: 'ND-L5-01（P1）已修复：EMAIL 渠道纯 API 通路 ENABLED 可达（外发链闭合，本线 L5-01 起全走真实 API 零绕行）',
      修复后契约: {
        '① verify(EMAIL)': { code: v.code, message: v.message, 语义: '主动验证仍 10604（首次投递时校验），契约不变' },
        '② patch{ENABLED}': { code: p.code, status: p.data.status, 语义: 'EMAIL 豁免已验证前置，PENDING 直启；熔断态重启用=重新验证（fail_count 归 0，L5-06 实证）' },
        '③ 渠道列表': { status: row.status },
      },
      达成: '注册 PENDING → patch ENABLED code 0 → 列表 ENABLED（修复意见「patch ENABLED 对 EMAIL 放宽 last_verify_at 前置」落地）',
    })
  })

  test('L5-01 外发成功链：类型默认 EMAIL → 计划 → 引擎 → SMTP 收包', async () => {
    const code = 'OK' + uniq('').toUpperCase()
    await createType(code, ['EMAIL'])
    const { chId, target } = await regEmailEnabled('u1')
    const title = 'L5-ok-' + uniq('')
    const bizNo = 'l5-ok-' + uniq('')
    const s = expectCode0(await sendMsg('u1', code, bizNo, title))
    expect(s.data.delivery_planned, '计划 1 行（EMAIL 实例）').toBe(1)
    expect(s.data.inapp_saved, 'INAPP 站内信落库不走引擎').toBe(true)
    // 轮询 admin 投递列表至终态 SUCCESS（引擎回写 SUCCESS；消息主档 SENT 语义一致）
    const d = await until(() => firstDelivery(bizNo), (r) => r !== null && r.status === 'SUCCESS', 25000, 400)
    expect(Number(d!.retry_count), '一次投递成功无重试').toBe(0)
    expect(d!.sent_at, 'sent_at 回填').toBeTruthy()
    expect(d!.target, '投递快照脱敏（u***@）').toContain('***@')
    // sink 收包断言
    const recs = sink.records()
    expect(recs.length, 'SMTP sink 收到 1 封').toBe(1)
    expect(recs[0]!.data, '正文含标题').toContain(title)
    expect(recs[0]!.data, 'Subject 头含标题').toContain(`Subject: ${title}`)
    expect(recs[0]!.to, '收件人=渠道 target').toContain(target)
    expect(recs[0]!.from, '发件人=实例 mail-from').toContain('nfy-e2e@test.local')
    // 第 2 轮证据规约（UI 面关键图）：投递页真实 SPA 出现 SUCCESS 行（biz_no 过滤恰 1 行）
    const { row } = await openDeliveries('u1', bizNo)
    await expect(row.locator('.fc-tag', { hasText: 'SUCCESS' })).toBeVisible()
    await expect(row).toContainText('EMAIL')
    await expect(row).toContainText('u1')
    await shot(page, 'L5-01-投递页UI-SUCCESS行')
    await evidence(page, 'L5-01-外发成功链', {
      链路: '类型 default_channels=[EMAIL] → send 计划 PENDING → 引擎领取(500ms 扫描) → SMTP → SUCCESS',
      投递记录: { delivery_id: d!.delivery_id, status: d!.status, retry_count: d!.retry_count, target: d!.target, sent_at: d!.sent_at },
      SMTP收包: { from: recs[0]!.from, to: recs[0]!.to, subject: `Subject: ${title}` },
      信道: `localhost:${SINK_PORT}（helpers/smtp-sink）`,
      UI复核: '投递页（biz_no 过滤）出现 1 行 SUCCESS 徽标（EMAIL/u1/重试次数 0/发送时间回显，错误信息列空）',
    })
  })

  test('L5-02 失败重试退避：454×2 → FAILED(1,2) 递进 → 恢复后 SENT', async () => {
    const code = 'RT' + uniq('').toUpperCase()
    await createType(code, ['EMAIL'])
    await regEmailEnabled('u2')
    sink.failFirst = 2 // 前 2 次 DATA 回 454 临时失败
    const sinkBefore = sink.records().length
    const title = 'L5-retry-' + uniq('')
    const bizNo = 'l5-rt-' + uniq('')
    expectCode0(await sendMsg('u2', code, bizNo, title))
    const seen: Array<{ status: string; retry_count: string; next_retry_at: number; created_at: number }> = []
    const observe = async () => {
      const r = await firstDelivery(bizNo)
      if (r) {
        seen.push({ status: r.status, retry_count: String(r.retry_count), next_retry_at: Number(r.next_retry_at), created_at: Number(r.created_at) })
      }
      return r
    }
    // API 面观测 FAILED(1) → FAILED(2) 递进（仍处 5s 退避窗内，引擎尚未第 3 次领取）
    const f2row = await until(observe, (r) => r !== null && r.status === 'FAILED' && String(r.retry_count) === '2', 25000, 300)
    // 第 2 轮证据规约（UI 面关键图）：FAILED 退避窗内投递页真实 SPA 行 —— FAILED 徽标 + 重试次数 + 454 错误留痕
    const { row } = await openDeliveries('u2', bizNo)
    await expect(row.locator('.fc-tag', { hasText: 'FAILED' })).toBeVisible()
    await expect(row).toContainText('SMTP')
    await shot(page, 'L5-02-投递页UI-FAILED行退避中')
    // 继续 API 面等到终态 SUCCESS（第 3 次尝试，sink 恢复）
    const d = await until(observe, (r) => r !== null && r.status === 'SUCCESS', 35000, 400)
    expect(Number(d!.retry_count), '第 3 次尝试成功，重试计数=2').toBe(2)
    expect(Number(f2row!.next_retry_at) - Number(f2row!.created_at) > 0, 'FAILED(2) 携带 next_retry_at（退避窗，UI 暂无该列）').toBe(true)
    const f1 = [...seen].reverse().find((x) => x.status === 'FAILED' && x.retry_count === '1')
    const f2 = [...seen].reverse().find((x) => x.status === 'FAILED' && x.retry_count === '2')
    expect(f1, '观测到 FAILED(1)').toBeTruthy()
    expect(f2, '观测到 FAILED(2)').toBeTruthy()
    const gap1 = f1!.next_retry_at - f1!.created_at
    const gap2 = f2!.next_retry_at - f1!.next_retry_at
    expect(gap1, `首次失败退避≈2s（实测 ${gap1}ms）`).toBeGreaterThanOrEqual(1500)
    expect(gap1).toBeLessThanOrEqual(4500)
    expect(gap2, `二次失败退避≈5s 递进（实测 ${gap2}ms）`).toBeGreaterThanOrEqual(4000)
    expect(gap2).toBeLessThanOrEqual(7000)
    expect(sink.records().length, '第 3 次尝试 sink 收包（增量 1）').toBe(sinkBefore + 1)
    l502DeliveryId = d!.delivery_id
    await evidence(page, 'L5-02-失败重试退避', {
      注入: 'sink.failFirst=2（前 2 次 DATA 回 454）',
      退避配置: '测试实例 backoff=2,5,10s（契约生产 1/5/15min，实例提速）',
      观测: `FAILED(1) 退避 ${gap1}ms；FAILED(2) 退避 ${gap2}ms；第 3 次尝试 → SUCCESS`,
      投递终态: { delivery_id: d!.delivery_id, status: d!.status, retry_count: d!.retry_count },
      FAILED窗: { status: f2row!.status, retry_count: f2row!.retry_count, next_retry_at: f2row!.next_retry_at },
      UI复核: 'FAILED 退避窗内投递页出现 FAILED 徽标行（重试次数 2 + SMTP 454 错误留痕）',
      改进注记: 'P3：投递页 UI 未展示 next_retry_at 列（下次重试时间用户不可见），本用例以 API 面证据补位',
    })
  })

  test('L5-03 持续失败 → DEAD（retry_count=4）→ DLV-002 人工重投成功', async () => {
    const code = 'DD' + uniq('').toUpperCase()
    await createType(code, ['EMAIL'])
    await regEmailEnabled('u3')
    sink.failFirst = Infinity // 持续失败
    const bizNo = 'l5-dd-' + uniq('')
    expectCode0(await sendMsg('u3', code, bizNo, 'L5-dead-' + uniq('')))
    // 3 次退避（2/5/10s）后 DEAD，初始+3 次重试=retry_count 4
    const dead = await until(() => firstDelivery(bizNo), (r) => r !== null && r.status === 'DEAD', 45000, 500)
    expect(Number(dead!.retry_count), '初始+3 次重试').toBe(4)
    expect(dead!.error_message, '错误摘要留痕').toContain('SMTP')
    // 非 DEAD 不可重投 → 10402（用 L5-02 的 SUCCESS 行）
    expect((await retryApi(l502DeliveryId)).code, 'SUCCESS 行重投 10402').toBe(10402)
    expect((await retryApi('999999999')).code, '不存在投递 10400').toBe(10400)
    // 第 2 轮证据规约（UI 前后图①）：投递页真实 SPA 出现 DEAD 行（红色徽标 + 重投按钮仅 DEAD 行有）
    const { f, row } = await openDeliveries('u3', bizNo)
    await expect(row.locator('.fc-tag', { hasText: 'DEAD' })).toBeVisible()
    await expect(row).toContainText('4')
    const retryBtn = row.getByRole('button', { name: '重投' })
    await expect(retryBtn).toBeVisible()
    await shot(page, 'L5-03-投递页UI-DEAD行重投前')
    // sink 恢复 → UI 操作 DLV-002 重投（POST /admin/deliveries/{id}/retry → PENDING 立即可扫）
    sink.failFirst = 0
    await retryBtn.click()
    await expect(f.getByText('已重新入队'), '重投成功 toast').toBeVisible()
    const done = await until(() => firstDelivery(bizNo), (x) => x !== null && x.status === 'SUCCESS', 25000, 400)
    expect(sink.records().length, '恢复后重投 sink 收包').toBeGreaterThanOrEqual(1)
    // UI 前后图②：同一 biz_no 行转 SUCCESS
    await f.getByRole('button', { name: '查询' }).click()
    await expect(row.locator('.fc-tag', { hasText: 'SUCCESS' })).toBeVisible()
    await shot(page, 'L5-03-投递页UI-重投后转SUCCESS')
    await evidence(page, 'L5-03-DEAD与人工重投', {
      死信: { status: dead!.status, retry_count: dead!.retry_count, error: dead!.error_message },
      DLV002校验: { '非 DEAD 重投': 10402, '不存在 id': 10400 },
      重投: { 入口: '投递页 DEAD 行「重投」按钮（真实 UI 动作）', 契约: 'POST /admin/deliveries/{id}/retry → {status:"PENDING"} 立即可扫', 恢复后终态: done!.status },
      时序: '失败退避 2/5/10s 三次 → DEAD；重投 next_retry_at=now',
      UI复核: '重投前 DEAD 徽标+重投按钮 → 点击重投 toast「已重新入队」→ 重查后同行转 SUCCESS 徽标（前后图）',
    })
  })

  test('L5-04 quiet_hours：静默窗内投递推迟（PENDING + next_retry_at=窗结束）', async () => {
    const code = 'QH' + uniq('').toUpperCase()
    await createType(code, ['EMAIL'])
    const { chId } = await regEmailEnabled('u4')
    // 构造覆盖当前时刻的静默窗：start=now-1h、end=now+1h（跨午夜自然覆盖，不 sleep 不 mock）
    const fmt = (d: Date) => `${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`
    const now = new Date()
    const window = { start: fmt(new Date(now.getTime() - 3600_000)), end: fmt(new Date(now.getTime() + 3600_000)) }
    expectCode0(await putSubs('u4', [{ type_code: code, channel_ids: ['INAPP', chId], quiet_hours: window }]))
    const sinkBefore = sink.records().length
    const bizNo = 'l5-qh-' + uniq('')
    expectCode0(await sendMsg('u4', code, bizNo, 'L5-quiet-' + uniq('')))
    const d = await until(() => firstDelivery(bizNo), (r) => r !== null, 15000, 400)
    expect(d!.status, '投递已生成但被推迟').toBe('PENDING')
    const deferMs = Number(d!.next_retry_at) - Date.now()
    expect(deferMs, `next_retry_at 推迟到窗结束（≈1h，实测 ${Math.round(deferMs / 1000)}s）`).toBeGreaterThan(25 * 60_000)
    expect(deferMs).toBeLessThan(130 * 60_000)
    expect(sink.records().length, '静默窗内不真发（不真等窗结束）').toBe(sinkBefore)
    // 第 2 轮证据规约（UI 关键图）：投递页真实 SPA 呈现被推迟的 PENDING 行（quiet_hours 计划期推迟）
    const { row } = await openDeliveries('u4', bizNo)
    await expect(row.locator('.fc-tag', { hasText: 'PENDING' })).toBeVisible()
    await expect(row).toContainText('0')
    await shot(page, 'L5-04-投递页UI-PENDING推迟行')
    await evidence(page, 'L5-04-quiet_hours推迟', {
      静默窗: window,
      投递: { status: d!.status, next_retry_at: d!.next_retry_at, 推迟: `${Math.round(deferMs / 1000)}s` },
      语义: '推迟发送非丢弃（Courier 口径）：next_retry_at=窗结束，引擎按 next_retry_at<=now 自然发出',
      UI复核: '投递页出现 PENDING 徽标行（重试次数 0，静默窗内不发送）；next_retry_at 由 API 面断言（UI 暂无该列）',
      豁免注记: 'URGENT 全量渠道路径不经订阅矩阵、INAPP 落库即达——均不受免打扰影响（NfyQuietHoursTest 深覆盖）',
    })
  })

  test('L5-05 渠道熔断前夜：4 次失败（未达阈值不熔断）→ DEAD', async () => {
    const code = 'BR' + uniq('').toUpperCase()
    await createType(code, ['EMAIL'])
    const { chId } = await regEmailEnabled('u5')
    sink.failFirst = Infinity
    const bizNo = 'l5-br-' + uniq('')
    expectCode0(await sendMsg('u5', code, bizNo, 'L5-breaker-' + uniq('')))
    const dead = await until(() => firstDelivery(bizNo), (r) => r !== null && r.status === 'DEAD', 45000, 500)
    expect(Number(dead!.retry_count)).toBe(4)
    const ch = await channelRow('u5', chId)
    expect(String(ch.fail_count), '渠道失败计数=4（阈值 5 未熔断）').toBe('4')
    expect(ch.status, '渠道仍 ENABLED').toBe('ENABLED')
    l505DeliveryId = dead!.delivery_id
    l505ChannelId = chId
    await evidence(page, 'L5-05-熔断阈值前不熔断', {
      投递: { status: dead!.status, retry_count: dead!.retry_count },
      渠道: { channel_id: chId, fail_count: ch.fail_count, status: ch.status },
      契约: 'fail_count 与 retry_count 两类计数器分离；阈值 5 未达不熔断',
    })
  })

  test('L5-06 渠道熔断：第 5 次失败 → DISABLED + 属主站内信兜底 + EMAIL 熔断重启用=重新验证', async () => {
    // 接 L5-05：DLV-002 重投该 DEAD 行 → 引擎再失败一次 → fail_count=5 → 熔断
    expectCode0(await retryApi(l505DeliveryId))
    // 以渠道状态为准等待熔断完成（第 5 次失败：退避 2/5/10s 时序后 DEAD + DISABLED）
    const broken = await until(() => channelRow('u5', l505ChannelId), (c) => c.status === 'DISABLED', 45000, 500)
    expect(String(broken.fail_count), '第 5 次失败达到阈值').toBe('5')
    expect(broken.status, '熔断 DISABLED').toBe('DISABLED')
    // 第 2 轮证据规约（UI 关键图①）：渠道页真实 SPA 出现熔断渠道 DISABLED 徽标（红色）
    const fch = await openUI('channels', 'u5')
    const card = fch.locator('.card', { hasText: '邮箱渠道-u5' })
    await expect(card.locator('.fc-tag', { hasText: 'DISABLED' })).toBeVisible()
    await shot(page, 'L5-06-渠道页UI-熔断DISABLED徽标')
    // ND-L5-01 修复：EMAIL 熔断态显式重新启用即视为重新验证 → code 0 且 fail_count 归 0（原 10610 死锁不可恢复）
    const p = expectCode0(await nfy<{ status: string }>(await patch(
      `${BASE}/nfy/api/v1/runtime/channels/${l505ChannelId}`, { status: 'ENABLED' }, opt(t, 'u5'))))
    expect(p.data.status, '熔断态重启用生效').toBe('ENABLED')
    const revived = await channelRow('u5', l505ChannelId)
    expect(revived.status, '渠道恢复 ENABLED').toBe('ENABLED')
    expect(String(revived.fail_count), '重启用即重新验证：fail_count 归 0').toBe('0')
    // 属主站内信兜底（CHANNEL_ALERT，仅 USER scope 渠道）
    const inbox = expectCode0(await nfy<{ list: Array<{ title: string; type_code: string }> }>(
      await get(`${BASE}/nfy/api/v1/runtime/messages?type_code=CHANNEL_ALERT`, opt(t, 'u5'))))
    expect(inbox.data.list.length, '属主收到熔断站内信').toBeGreaterThanOrEqual(1)
    expect(inbox.data.list[0]!.title).toContain('渠道连续失败已自动停用')
    // 第 2 轮证据规约（UI 关键图②）：消息页真实 SPA 呈现属主熔断站内信（引擎兜底直达 INAPP）
    const fmsg = await openUI('messages', 'u5')
    await expect(fmsg.getByText('渠道连续失败已自动停用').first()).toBeVisible()
    await shot(page, 'L5-06-消息页UI-属主熔断站内信')
    sink.failFirst = 0
    await evidence(page, 'L5-06-熔断与属主站内信', {
      流转: 'DLV-002 重投 DEAD 行 → 第 5 次失败 → fail_count=5 → CAS 熔断 DISABLED',
      渠道: { fail_count: broken.fail_count, status: broken.status },
      熔断恢复: {
        patch_code: p.code, status: p.data.status, fail_count: revived.fail_count,
        语义: 'EMAIL 显式重启用=重新验证（ND-L5-01 修复）；IM 熔断仍须真实 verify（10610，IT 钉死）',
      },
      属主站内信: { type_code: 'CHANNEL_ALERT', title: inbox.data.list[0]!.title },
      UI复核: '渠道页「邮箱渠道-u5」卡片 DISABLED 红色徽标（熔断态 UI 可见）；消息页出现「渠道连续失败已自动停用」站内信',
      限速注记: '每渠道限速（实例放宽 600/min）由引擎内存窗实现，API 面不可直接观测（NfyDeliveryEngineTest 深覆盖）',
    })
  })
})
