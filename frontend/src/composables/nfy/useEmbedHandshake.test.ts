import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { nextTick } from 'vue'
import { createEmbedHandshake } from './useEmbedHandshake'

// VECTOR: TAG=step7-embed-handshake
// 契约（PRD F-EMB-001 / 接口文档嵌入安全）：
// - iframe 内周期重发 NFY_READY（默认 500ms，防父页监听未就绪死锁），收到 NFY_TOKEN 停发；
// - 仅接受白名单 origin 的消息；token 只进内存；
// - token 过期（expired 事件/手动 notifyExpired）后重新进入 waiting 并重发 READY；
// - 非 iframe 环境直接 idle。

const sendReadyCalls = (spy: ReturnType<typeof vi.fn>) =>
  spy.mock.calls.map((c) => (c[0] as { type?: string }).type).filter((t) => t === 'NFY_READY').length

describe('useEmbedHandshake', () => {
  beforeEach(() => {
    vi.useFakeTimers()
  })
  afterEach(() => {
    vi.useRealTimers()
    vi.restoreAllMocks()
  })

  function setup(iframe: boolean, allowed = ['https://app.example.com']) {
    const postSpy = vi.fn()
    const machine = createEmbedHandshake({
      allowedOrigins: allowed,
      postToParent: postSpy,
      isEmbedded: () => iframe,
      resendIntervalMs: 500,
    })
    machine.start()
    return { machine, postSpy }
  }

  const tokenMessage = (origin: string, token = 'jwt-1', userid = 'u_1') =>
    new MessageEvent('message', {
      origin,
      source: null,
      data: { type: 'NFY_TOKEN', token, user_id: userid },
    })

  it('非 iframe 环境 → idle 且不发 READY', () => {
    const { machine, postSpy } = setup(false)
    expect(machine.status.value).toBe('idle')
    vi.advanceTimersByTime(2000)
    expect(sendReadyCalls(postSpy)).toBe(0)
    machine.stop()
  })

  it('iframe 内周期重发 READY 直至握手成功', async () => {
    const { machine, postSpy } = setup(true)
    vi.advanceTimersByTime(1600)
    const before = sendReadyCalls(postSpy)
    expect(before).toBeGreaterThanOrEqual(3)

    window.dispatchEvent(tokenMessage('https://app.example.com'))
    await nextTick()
    expect(machine.status.value).toBe('connected')
    expect(machine.token.value).toBe('jwt-1')
    expect(machine.userId.value).toBe('u_1')
    const after = sendReadyCalls(postSpy)
    vi.advanceTimersByTime(2000)
    expect(sendReadyCalls(postSpy)).toBe(after) // 握手后停发
    machine.stop()
  })

  it('白名单外 origin 的 NFY_TOKEN 被忽略', async () => {
    const { machine, postSpy } = setup(true, ['https://app.example.com'])
    window.dispatchEvent(tokenMessage('https://evil.com'))
    await nextTick()
    expect(machine.status.value).not.toBe('connected')
    vi.advanceTimersByTime(1000)
    expect(sendReadyCalls(postSpy)).toBeGreaterThan(0) // 仍在重发
    machine.stop()
  })

  it('token 过期后回到 waiting 并重新握手', async () => {
    const { machine, postSpy } = setup(true)
    window.dispatchEvent(tokenMessage('https://app.example.com', 'jwt-old'))
    await nextTick()
    expect(machine.status.value).toBe('connected')

    machine.notifyExpired()
    await nextTick()
    expect(machine.status.value).toBe('waiting')
    expect(machine.token.value).toBe('')
    const readyAtExpired = sendReadyCalls(postSpy)
    vi.advanceTimersByTime(600)
    expect(sendReadyCalls(postSpy)).toBeGreaterThan(readyAtExpired) // 重新握手

    window.dispatchEvent(tokenMessage('https://app.example.com', 'jwt-new'))
    await nextTick()
    expect(machine.status.value).toBe('connected')
    expect(machine.token.value).toBe('jwt-new')
    machine.stop()
  })
})
