/**
 * notification4j 消息中心运行时上下文：握手 + API 客户端一次性装配。
 * Shell 提供（provide 'nfy'），四页 inject 使用；token 过期自动回握手。
 *
 * F-2：client 鉴权类失败（HTTP 401 / 信封 102xx）→ authError 置位 +
 * handshake.notifyExpired()（嵌入态回 waiting 重发 NFY_READY，宿主重新投
 * NFY_TOKEN；非嵌入态回 idle）；新 token 到达即清除 authError。页面据
 * authError 区分「空数据」与「会话失效」，不再呈现误导性空态。
 */
import { provide, inject, computed, ref, watch, type Ref } from 'vue'
import { createNfyClient, NfyApiError, isAuthExpiredCode } from '@/api/nfy/client'
import {
  createMessageApi,
  createAnnouncementApi,
  createChannelApi,
  createSubscriptionApi,
  createDeliveryApi,
} from '@/api/nfy/index'
import { createEmbedHandshake } from '@/composables/nfy/useEmbedHandshake'

export const NFY_KEY = Symbol('nfy-context')

export interface NfyContext {
  handshake: ReturnType<typeof createEmbedHandshake>
  apis: ReturnType<typeof createNfyApis>
  ready: import('vue').ComputedRef<boolean>
  /** 最近一次鉴权类失败（会话失效标记）；新 token 到达后清除 */
  authError: Ref<NfyApiError | null>
}

function createNfyApis(client: ReturnType<typeof createNfyClient>) {
  return {
    messages: createMessageApi(client),
    announcements: createAnnouncementApi(client),
    channels: createChannelApi(client),
    subscriptions: createSubscriptionApi(client),
    deliveries: createDeliveryApi(client),
  }
}

export interface NfyRuntimeOptions {
  baseUrl: string
  allowedOrigins: string[]
  /** 测试注入口（axios adapter，同 client 约定） */
  adapter?: import('axios').AxiosRequestConfig['adapter']
  /** 测试注入口：默认 window.self !== window.top */
  isEmbedded?: () => boolean
}

/**
 * 组装握手 + 客户端 + 鉴权失效接线（不依赖组件实例，可单测）。
 * provideNfy = createNfyRuntime + provide。
 */
export function createNfyRuntime(options: NfyRuntimeOptions) {
  const handshake = createEmbedHandshake({
    allowedOrigins: options.allowedOrigins,
    isEmbedded: options.isEmbedded,
  })
  handshake.start()

  /** 会话失效标记：页面据此显示「会话已失效」而非「暂无消息」空态 */
  const authError = ref<NfyApiError | null>(null)

  const client = createNfyClient({
    baseUrl: options.baseUrl,
    getToken: () => handshake.token.value,
    getUserId: () => handshake.userId.value,
    adapter: options.adapter,
    onAuthExpired: (e) => {
      authError.value = e
      // 嵌入态回 waiting 重发 NFY_READY（宿主重新发 NFY_TOKEN）；非嵌入态回 idle
      handshake.notifyExpired()
    },
  })

  // 新 token 到达（重新握手成功）→ 会话恢复，清除失效标记
  watch(
    () => handshake.status.value,
    (s) => {
      if (s === 'connected') authError.value = null
    },
  )

  const apis = createNfyApis(client)
  return {
    handshake,
    apis,
    authError,
    ready: computed(() => handshake.status.value === 'connected'),
  }
}

export function provideNfy(options: { baseUrl: string; allowedOrigins: string[] }): NfyContext {
  const ctx = createNfyRuntime(options)
  provide(NFY_KEY, ctx)
  return ctx
}

export function useNfy(): NfyContext {
  const ctx = inject<NfyContext>(NFY_KEY)
  if (!ctx) throw new Error('nfy context 未提供：请在 NfyShell 内使用')
  return ctx
}

/**
 * 鉴权类失败（token 失效）：HTTP 401 或信封 code∈fwk ApiCode 102xx 认证段。
 * 业务 403（域闸）/103xx 权限类不是 token 失效，不算。
 */
export function isTokenExpiredError(e: unknown): e is NfyApiError {
  return e instanceof NfyApiError && isAuthExpiredCode(e.code)
}
