import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { useUnreadCount } from './useUnreadCount'

// VECTOR: TAG=step7-unread-poll
// 契约（PRD F-EMB 铃铛）：未读数 30s 轮询（页面不可见时暂停，可见恢复并立即首拉）；
// URGENT 到达经 postMessage 通知父页（V1.0）；stop 后不再轮询。
// 时序口径：构造后立即首拉（微任务刷新后可见），interval 周期后再拉。

const flush = async () => {
  await Promise.resolve()
  await Promise.resolve()
}

const snap = (total: number, hasUrgent = false) => ({
  total,
  unread_count: total,
  unconfirmed_count: 0,
  has_urgent: hasUrgent,
})

describe('useUnreadCount', () => {
  beforeEach(() => {
    vi.useFakeTimers()
    Object.defineProperty(document, 'hidden', { value: false, configurable: true })
  })
  afterEach(() => {
    vi.useRealTimers()
    vi.restoreAllMocks()
  })

  it('立即首拉并按 interval 轮询', async () => {
    const fetch = vi.fn().mockResolvedValue(snap(2))
    const { total, stop } = useUnreadCount({ fetch, intervalMs: 30000 })
    await flush()
    expect(fetch).toHaveBeenCalledTimes(1)
    expect(total.value).toBe(2)
    await vi.advanceTimersByTimeAsync(30000)
    expect(fetch).toHaveBeenCalledTimes(2)
    stop()
  })

  it('页面不可见暂停轮询，可见时恢复并立即拉取', async () => {
    const fetch = vi.fn().mockResolvedValue(snap(0))
    const { stop } = useUnreadCount({ fetch, intervalMs: 30000 })
    await flush()
    expect(fetch).toHaveBeenCalledTimes(1)

    Object.defineProperty(document, 'hidden', { value: true, configurable: true })
    document.dispatchEvent(new Event('visibilitychange'))
    await vi.advanceTimersByTimeAsync(90000)
    expect(fetch).toHaveBeenCalledTimes(1) // 暂停

    Object.defineProperty(document, 'hidden', { value: false, configurable: true })
    document.dispatchEvent(new Event('visibilitychange'))
    await flush()
    expect(fetch).toHaveBeenCalledTimes(2) // 可见立即拉
    stop()
  })

  it('fetch 抛错不中断轮询', async () => {
    const fetch = vi.fn().mockRejectedValueOnce(new Error('network')).mockResolvedValue(snap(5))
    const { total, stop } = useUnreadCount({ fetch, intervalMs: 1000 })
    await flush()
    expect(total.value).toBe(0) // 首拉失败保留旧值
    await vi.advanceTimersByTimeAsync(1000)
    expect(total.value).toBe(5) // 下个周期恢复
    stop()
  })

  it('URGENT 首次出现时 postMessage 通知父页', async () => {
    const postSpy = vi.fn()
    const queue = [snap(0, false), snap(3, true)]
    const fetch = vi.fn().mockImplementation(() => Promise.resolve(queue.shift() ?? snap(3, true)))
    const { stop } = useUnreadCount({ fetch, intervalMs: 1000, postToParent: postSpy })
    await flush()
    expect(postSpy.mock.calls.length).toBe(0) // 无 urgent 不发
    await vi.advanceTimersByTimeAsync(1000)
    await flush()
    const urgentMsg = postSpy.mock.calls
      .map((c) => c[0] as { type?: string })
      .find((m) => m.type === 'NFY_URGENT')
    expect(urgentMsg).toBeTruthy()
    await vi.advanceTimersByTimeAsync(1000)
    await flush()
    const count = postSpy.mock.calls.filter((c) => (c[0] as { type?: string }).type === 'NFY_URGENT').length
    expect(count).toBe(1) // urgent 持续时不重复发
    stop()
  })
})
