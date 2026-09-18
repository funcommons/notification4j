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

  // ==== V1.3 运行时白名单（issue #1）：构建时名单外 origin 持 token 拉 oem.hosts 核验 ====

  function setupWithFetcher(
    allowed: string[],
    fetcher: (token: string) => Promise<string[] | null>,
  ) {
    const postSpy = vi.fn()
    const machine = createEmbedHandshake({
      allowedOrigins: allowed,
      fetchExtraOrigins: fetcher,
      postToParent: postSpy,
      isEmbedded: () => true,
      resendIntervalMs: 500,
    })
    machine.start()
    return { machine, postSpy }
  }

  /** 冲刷 acceptViaRuntimeList 的微任务链（不能用 runAllTimersAsync——READY 重发 interval 无限） */
  const flushAsync = async () => {
    for (let i = 0; i < 20; i++) await Promise.resolve()
  }

  it('运行时白名单命中：oem.hosts 含父页 origin → 接受，且同 origin 后续消息走快速路径', async () => {
    const fetcher = vi.fn(async () => ['https://partner.example.com'])
    const { machine } = setupWithFetcher(['https://app.example.com'], fetcher)
    window.dispatchEvent(tokenMessage('https://partner.example.com', 'jwt-oem', 'u_9'))
    await flushAsync()
    expect(machine.status.value).toBe('connected')
    expect(machine.token.value).toBe('jwt-oem')
    expect(machine.userId.value).toBe('u_9')
    expect(fetcher).toHaveBeenCalledTimes(1)
    expect(fetcher).toHaveBeenCalledWith('jwt-oem')

    // 重握手（过期后同 origin）→ runtimeOrigins 快速路径，不再触发拉取
    machine.notifyExpired()
    window.dispatchEvent(tokenMessage('https://partner.example.com', 'jwt-oem-2'))
    await flushAsync()
    expect(machine.status.value).toBe('connected')
    expect(machine.token.value).toBe('jwt-oem-2')
    expect(fetcher).toHaveBeenCalledTimes(1)
    machine.stop()
  })

  it('运行时白名单未命中：oem.hosts 不含父页 origin → 忽略，仍 waiting 重发', async () => {
    const fetcher = vi.fn(async () => ['https://other.example.com'])
    const { machine, postSpy } = setupWithFetcher(['https://app.example.com'], fetcher)
    window.dispatchEvent(tokenMessage('https://evil.com'))
    await flushAsync()
    expect(machine.status.value).not.toBe('connected')
    expect(machine.token.value).toBe('')
    const readyBefore = sendReadyCalls(postSpy)
    vi.advanceTimersByTime(1000)
    expect(sendReadyCalls(postSpy)).toBeGreaterThan(readyBefore)
    machine.stop()
  })

  it('运行时白名单拉取失败（null）→ fail-closed 忽略', async () => {
    const fetcher = vi.fn(async () => null)
    const { machine } = setupWithFetcher(['https://app.example.com'], fetcher)
    window.dispatchEvent(tokenMessage('https://flaky.example.com'))
    await flushAsync()
    expect(machine.status.value).not.toBe('connected')
    machine.stop()
  })

  it('同 token 并发消息只触发一次 oem.hosts 拉取（在途去重）', async () => {
    let resolveFetch!: (v: string[] | null) => void
    const fetcher = vi.fn(
      () => new Promise<string[] | null>((resolve) => (resolveFetch = resolve)),
    )
    const { machine } = setupWithFetcher(['https://app.example.com'], fetcher)
    window.dispatchEvent(tokenMessage('https://partner.example.com', 'jwt-dup'))
    window.dispatchEvent(tokenMessage('https://partner.example.com', 'jwt-dup'))
    window.dispatchEvent(tokenMessage('https://partner.example.com', 'jwt-dup'))
    await Promise.resolve()
    expect(fetcher).toHaveBeenCalledTimes(1)
    resolveFetch(['https://partner.example.com'])
    await flushAsync()
    expect(machine.status.value).toBe('connected')
    machine.stop()
  })
})
