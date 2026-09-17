/**
 * useUnreadCount - 铃铛未读数轮询（PRD F-EMB / 接口文档 §5.3）。
 *
 * 契约：
 * - 立即首拉 + 按 intervalMs（默认 30s）轮询 GET /runtime/messages/unread-count；
 * - 页面不可见（document.hidden）暂停，恢复可见时立即补拉；
 * - total 变化且后端标记 has_urgent 时，postMessage('NFY_URGENT') 通知父页（V1.0 轮询口径）；
 * - fetch 抛错静默保留旧值，不打断轮询（嵌入 iframe 内不打断父页）。
 */
import { ref, onUnmounted, getCurrentInstance, type Ref } from 'vue'

export interface UnreadSnapshot {
  total: number
  unread_count?: number
  unconfirmed_count?: number
  has_urgent?: boolean
}

export interface UnreadCountOptions {
  fetch: () => Promise<UnreadSnapshot>
  intervalMs?: number
  /** 可注入（默认 window.parent.postMessage；测试桩用） */
  postToParent?: (msg: unknown) => void
  /** 仅供测试注入的定时器句柄集合 */
  autoStart?: boolean
}

export function useUnreadCount(options: UnreadCountOptions): {
  total: Ref<number>
  snapshot: Ref<UnreadSnapshot | null>
  refresh: () => Promise<void>
  stop: () => void
} {
  const intervalMs = options.intervalMs ?? 30000
  const total = ref(0)
  const snapshot: Ref<UnreadSnapshot | null> = ref(null)
  let timer: ReturnType<typeof setInterval> | null = null
  let stopped = false

  const postToParent =
    options.postToParent ??
    ((msg: unknown) => {
      try {
        if (window.parent && window.parent !== window) {
          window.parent.postMessage(msg, '*')
        }
      } catch {
        // 非 iframe 或跨域拒绝：静默
      }
    })

  const tick = async () => {
    if (stopped || document.hidden) return
    try {
      const next = await options.fetch()
      const wasUrgent = snapshot.value?.has_urgent ?? false
      snapshot.value = next
      total.value = next.total
      if (next.has_urgent && !wasUrgent) {
        postToParent({ type: 'NFY_URGENT', total: next.total })
      }
    } catch {
      // 静默：保留旧值
    }
  }

  const onVisibility = () => {
    if (!stopped && !document.hidden) {
      void tick()
    }
  }

  document.addEventListener('visibilitychange', onVisibility)
  timer = setInterval(() => void tick(), intervalMs)
  void tick()

  const stop = () => {
    stopped = true
    if (timer) clearInterval(timer)
    document.removeEventListener('visibilitychange', onVisibility)
  }

  if (getCurrentInstance()) {
    onUnmounted(stop)
  }

  return { total, snapshot, refresh: tick, stop }
}
