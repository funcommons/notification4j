import net from 'node:net'

/**
 * 极简 SMTP 收包黑洞（L5 外发引擎线专用）：
 * - 220 问候 → EHLO/HELO 250（不宣告任何扩展 → Jakarta Mail starttls.enable 不升 TLS、auth 关闭直连）
 * - MAIL/RCPT 250 → DATA：前 failFirst 次回 454 临时失败，其后 354 收数据 → 250 收尾
 * - QUIT 221；records() 记录每次成功 DATA 的信封与正文（断言「收到且含标题」用）
 */
export interface SinkRecord {
  from: string
  to: string
  data: string
  at: number
}

export class SmtpSink {
  private server: net.Server | null = null
  private readonly sockets = new Set<net.Socket>()
  private readonly recs: SinkRecord[] = []
  /** 前 N 次 DATA 回 454 临时失败；Infinity=持续失败；0=全部成功 */
  failFirst = 0

  start(port: number): Promise<void> {
    return new Promise((resolve, reject) => {
      const s = net.createServer((sock) => this.serve(sock))
      s.on('error', reject)
      s.listen(port, '127.0.0.1', () => resolve())
      this.server = s
    })
  }

  async stop(): Promise<void> {
    for (const sock of this.sockets) {
      sock.destroy()
    }
    this.sockets.clear()
    if (!this.server) return
    const s = this.server
    this.server = null
    await new Promise<void>((resolve) => s.close(() => resolve()))
  }

  records(): readonly SinkRecord[] {
    return this.recs
  }

  private reply(sock: net.Socket, line: string): void {
    sock.write(line + '\r\n')
  }

  private serve(sock: net.Socket): void {
    this.sockets.add(sock)
    let buf = ''
    let inData = false
    let from = ''
    let to = ''
    this.reply(sock, '220 smtp-sink ESMTP ready')
    sock.on('data', (chunk: Buffer) => {
      buf += chunk.toString('utf8')
      for (;;) {
        if (inData) {
          const end = buf.indexOf('\r\n.\r\n')
          if (end < 0) return
          this.recs.push({ from, to, data: buf.slice(0, end), at: Date.now() })
          buf = buf.slice(end + 5)
          inData = false
          this.reply(sock, '250 OK queued')
          continue
        }
        const nl = buf.indexOf('\r\n')
        if (nl < 0) return
        const line = buf.slice(0, nl)
        buf = buf.slice(nl + 2)
        const cmd = line.trim().toUpperCase()
        if (cmd.startsWith('EHLO') || cmd.startsWith('HELO')) {
          this.reply(sock, '250 smtp-sink')
        } else if (cmd.startsWith('MAIL FROM:')) {
          from = line.slice(line.indexOf(':') + 1).trim()
          this.reply(sock, '250 OK')
        } else if (cmd.startsWith('RCPT TO:')) {
          to = line.slice(line.indexOf(':') + 1).trim()
          this.reply(sock, '250 OK')
        } else if (cmd === 'DATA') {
          // 剩余额度口径：每次置 failFirst=N 即「接下来 N 次 DATA 回 454」（Infinity=持续失败）
          if (this.failFirst > 0) {
            this.failFirst--
            this.reply(sock, '454 4.3.0 temporary failure (sink fault injection)')
          } else {
            inData = true
            this.reply(sock, '354 End data with <CR><LF>.<CR><LF>')
          }
        } else if (cmd.startsWith('QUIT')) {
          this.reply(sock, '221 Bye')
          sock.end()
        } else if (cmd.startsWith('NOOP') || cmd.startsWith('RSET')) {
          this.reply(sock, '250 OK')
        } else if (line.trim().length > 0) {
          this.reply(sock, '500 5.5.2 unknown command')
        }
      }
    })
    sock.on('error', () => { /* 引擎侧断连属正常（454 后 QUIT 竞态） */ })
    sock.on('close', () => this.sockets.delete(sock))
  }
}
