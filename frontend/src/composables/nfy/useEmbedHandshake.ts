/**
 * useEmbedHandshake - 嵌入消息中心 postMessage 握手（PRD F-EMB-001 推荐级）。
 *
 * 协议：
 *   iframe → 父页  { type: 'NFY_READY' }            周期重发（防父页监听未就绪死锁）
 *   父页  → iframe  { type: 'NFY_TOKEN', token, user_id }   仅白名单 origin 接受
 *
 * 安全：
 * - token/user_id 只进内存（不写 URL/storage），暴露面 7→1；
 * - origin 白名单 = 构建时 allowedOrigins ∪ 运行时 oem.hosts（V1.3，issue #1）：
 *   构建时名单外的 NFY_TOKEN，持该 token 调 fetchExtraOrigins 拉 oem.hosts 核验，
 *   命中才接受（once accepted 该 origin 本会话内走快速路径）；未命中/拉取失败
 *   一律忽略（fail-closed，防恶意父页注入）；
 * - token 过期（notifyExpired，由 NfyApiError(401) 触发）→ 回到 waiting 重新握手；
 * - 非 iframe 环境 → idle（独立部署/正常登录态使用）。
 */
import { ref, onUnmounted, type Ref } from 'vue'

export type EmbedStatus = 'idle' | 'waiting' | 'connected'

export interface EmbedHandshakeOptions {
  allowedOrigins: string[]
  /**
   * 运行时白名单补全（V1.3）：以候选 token 调后端 oem.hosts 下发端点。
   * 契约：失败/非 0 信封 resolve null（**不得 reject**）；同 token 并发去重。
   */
  fetchExtraOrigins?: (token: string) => Promise<string[] | null>
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

  /** 运行时白名单（oem.hosts 核验通过的 origin，本会话有效）与同 token 在途去重 */
  const runtimeOrigins = new Set<string>()
  const inflight = new Map<string, Promise<string[] | null>>()

  const extraOrigins = (token: string): Promise<string[] | null> => {
    if (!options.fetchExtraOrigins) return Promise.resolve(null)
    let p = inflight.get(token)
    if (!p) {
      p = options.fetchExtraOrigins(token).catch(() => null).finally(() => inflight.delete(token))
      inflight.set(token, p)
    }
    return p
  }

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

  const accept = (t: string, uid: string) => {
    token.value = t
    userId.value = uid
    status.value = t ? 'connected' : 'waiting'
    if (status.value === 'connected') stopResend()
  }

  /** 构建时名单外：持候选 token 拉运行时 oem.hosts 核验，命中才接受 */
  const acceptViaRuntimeList = async (origin: string, t: string, uid: string) => {
    if (!t) return
    const extra = await extraOrigins(t)
    if (!extra || !extra.includes(origin)) return
    if (status.value === 'connected') return // 已有会话不抢占（等待中的重发消息作废）
    runtimeOrigins.add(origin)
    accept(t, uid)
  }

  const onMessage = (event: MessageEvent) => {
    const data = event.data as { type?: string; token?: string; user_id?: string } | null
    if (!data || data.type !== 'NFY_TOKEN') return
    if (options.allowedOrigins.includes(event.origin) || runtimeOrigins.has(event.origin)) {
      accept(data.token ?? '', data.user_id ?? '')
      return
    }
    void acceptViaRuntimeList(event.origin, data.token ?? '', data.user_id ?? '')
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
