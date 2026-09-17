/**
 * useEmbedHandshake - 嵌入消息中心 postMessage 握手（PRD F-EMB-001 推荐级）。
 *
 * 协议：
 *   iframe → 父页  { type: 'NFY_READY' }            周期重发（防父页监听未就绪死锁）
 *   父页  → iframe  { type: 'NFY_TOKEN', token, user_id }   仅白名单 origin 接受
 *
 * 安全：
 * - token/user_id 只进内存（不写 URL/storage），暴露面 7→1；
 * - origin 白名单外消息一律忽略；
 * - token 过期（notifyExpired，由 NfyApiError(401) 触发）→ 回到 waiting 重新握手；
 * - 非 iframe 环境 → idle（独立部署/正常登录态使用）。
 */
import { ref, onUnmounted, type Ref } from 'vue'

export type EmbedStatus = 'idle' | 'waiting' | 'connected'

export interface EmbedHandshakeOptions {
  allowedOrigins: string[]
  /** 可注入：默认 window.parent.postMessage(msg, '*')（origin 由父页自行校验） */
  postToParent?: (msg: unknown) => void
  /** 可注入：默认 window.self !== window.top */
  isEmbedded?: () => boolean
  resendIntervalMs?: number
}

export function createEmbedHandshake(options: EmbedHandshakeOptions) {
  const status: Ref<EmbedStatus> = ref('idle')
  const token: Ref<string> = ref('')
  const userId: Ref<string> = ref('')
  const resendIntervalMs = options.resendIntervalMs ?? 500
  const embedded = options.isEmbedded ?? (() => window.self !== window.top)
  const postToParent =
    options.postToParent ??
    ((msg: unknown) => {
      try {
        if (window.parent && window.parent !== window) window.parent.postMessage(msg, '*')
      } catch {
        /* 静默 */
      }
    })

  let timer: ReturnType<typeof setInterval> | null = null

  const sendReady = () => postToParent({ type: 'NFY_READY' })
  const startResend = () => {
    if (timer) clearInterval(timer)
    sendReady()
    timer = setInterval(sendReady, resendIntervalMs)
  }
  const stopResend = () => {
    if (timer) clearInterval(timer)
    timer = null
  }

  const onMessage = (event: MessageEvent) => {
    if (!options.allowedOrigins.includes(event.origin)) return
    const data = event.data as { type?: string; token?: string; user_id?: string } | null
    if (!data || data.type !== 'NFY_TOKEN') return
    token.value = data.token ?? ''
    userId.value = data.user_id ?? ''
    status.value = token.value ? 'connected' : 'waiting'
    if (status.value === 'connected') stopResend()
  }

  /** token 失效（NfyApiError 401）→ 重新进入握手 */
  const notifyExpired = () => {
    if (status.value !== 'connected') return
    token.value = ''
    userId.value = ''
    if (embedded()) {
      status.value = 'waiting'
      startResend()
    } else {
      status.value = 'idle'
    }
  }

  const start = () => {
    window.addEventListener('message', onMessage)
    if (!embedded()) {
      status.value = 'idle'
      return
    }
    status.value = 'waiting'
    startResend()
  }

  const stop = () => {
    window.removeEventListener('message', onMessage)
    stopResend()
  }

  const reset = () => {
    token.value = ''
    userId.value = ''
  }

  onUnmounted(stop)

  return { status, token, userId, start, stop, notifyExpired, reset }
}
