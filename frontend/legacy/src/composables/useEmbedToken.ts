/**
 * useEmbedToken - 嵌入模式认证 (postMessage 握手 + URL token 兼容)。
 *
 * 安全等级:
 *  - 推荐级 (postMessage): token 不进 URL, 不写 storage, 全程内存。
 *  - 基础级 (URL token):   URL 带 ?access_token=..., 写 sessionStorage 供 SPA 跳转复用(关 tab 即清)。
 *
 * 用法 (PageLayout.vue):
 *   const { status } = useEmbedToken()
 *
 * 父页集成 (三方前端):
 *   iframe 加载后收到 { type: 'READY' } → 拿 token → postMessage({ type: 'TOKEN', ... })
 *   详见 documents/embedded-integration.md §8.2
 */
import { ref, onMounted, onUnmounted, type Ref } from 'vue'
import { useBenefitAuthStore } from '@/store/benefitAuth'

export type EmbedTokenStatus =
  | 'idle'       // 非嵌入环境 (正常用户)
  | 'basic'      // 基础级: URL 带 access_token
  | 'waiting'    // 推荐级: 等待父页 postMessage
  | 'connected'  // 推荐级: 握手完成, token 可用
  | 'expired'    // 推荐级: token 过期, 等待续签

/**
 * 允许嵌入的父页 origin 白名单。
 * 生产环境通过 configureEmbedParentOrigins() 在 main.ts 中配置。
 * 开发环境默认允许 localhost。
 */
let ALLOWED_PARENT_ORIGINS: string[] = [
  'http://localhost:5173',
  'http://localhost:3000',
  'http://localhost:8080',
  'http://localhost:9203',
]

/** 在 main.ts 中调用, 设置生产环境白名单 */
export function configureEmbedParentOrigins(origins: string[]) {
  ALLOWED_PARENT_ORIGINS = origins
}

/** 读取当前白名单 (调试 / 测试用) */
export function getEmbedParentOrigins(): readonly string[] {
  return ALLOWED_PARENT_ORIGINS
}

const isInIframe = (): boolean => {
  try {
    return window.self !== window.top
  } catch {
    // 跨域访问 window.top 会抛异常 → 说明在 iframe 里
    return true
  }
}

export function useEmbedToken(): { status: Ref<EmbedTokenStatus> } {
  const status = ref<EmbedTokenStatus>('idle')
  const auth = useBenefitAuthStore()

  let renewTimer: ReturnType<typeof setInterval> | null = null
  let expiresAt = 0

  const handleMessage = (e: MessageEvent) => {
    // ① Origin 校验 — 浏览器内核设置, JS 不可伪造
    if (!ALLOWED_PARENT_ORIGINS.includes(e.origin)) return

    // ② 消息结构校验
    const data = e.data
    if (!data || typeof data !== 'object') return

    if (data.type === 'TOKEN' && typeof data.access_token === 'string' && data.access_token.length > 0) {
      const expiresIn = typeof data.expires_in === 'number' ? data.expires_in : 3600

      // 内存写入, 不 persist (persist=false)
      auth.setToken(data.access_token, expiresIn, false)

      expiresAt = Date.now() + expiresIn * 1000
      status.value = 'connected'

      startRenewTimer()
    }
  }

  const startRenewTimer = () => {
    if (renewTimer) clearInterval(renewTimer)
    renewTimer = setInterval(() => {
      // 到期前 10 分钟请求续签
      if (Date.now() > expiresAt - 10 * 60 * 1000) {
        status.value = 'expired'
        requestRenew()
      }
    }, 60_000)
  }

  const requestRenew = () => {
    // 向所有白名单 origin 发 RENEW (父页只响应匹配自己 origin 的那条)
    for (const origin of ALLOWED_PARENT_ORIGINS) {
      try {
        window.parent.postMessage({ type: 'RENEW' }, origin)
      } catch {
        // 跨域 postMessage 失败 — 忽略
      }
    }
  }

  onMounted(() => {
    // 基础级: URL 已有 access_token → 路由守卫已处理, 标记状态即可
    const urlToken = new URLSearchParams(window.location.search).get('access_token')
    if (urlToken) {
      status.value = 'basic'
      return
    }

    // 非 iframe 环境 → 正常用户, 不启动握手
    if (!isInIframe()) {
      status.value = 'idle'
      return
    }

    // 推荐级: 启动 postMessage 握手
    status.value = 'waiting'
    window.addEventListener('message', handleMessage)

    // 告诉父页: iframe 已就绪, 请发送 token
    for (const origin of ALLOWED_PARENT_ORIGINS) {
      try {
        window.parent.postMessage({ type: 'READY' }, origin)
      } catch {
        // 跨域 postMessage 失败 — 忽略
      }
    }
  })

  onUnmounted(() => {
    window.removeEventListener('message', handleMessage)
    if (renewTimer) {
      clearInterval(renewTimer)
      renewTimer = null
    }
  })

  return { status }
}
