/**
 * notification4j 消息中心运行时上下文：握手 + API 客户端一次性装配。
 * Shell 提供（provide 'nfy'），四页 inject 使用；token 过期自动回握手。
 */
import { provide, inject, computed } from 'vue'
import { createNfyClient, NfyApiError } from '@/api/nfy/client'
import {
  createMessageApi,
  createAnnouncementApi,
  createChannelApi,
  createSubscriptionApi,
  createDeliveryApi,
} from '@/api/nfy/index'
import { createEmbedHandshake } from '@/composables/nfy/useEmbedHandshake'

const NFY_KEY = Symbol('nfy-context')

export interface NfyContext {
  handshake: ReturnType<typeof createEmbedHandshake>
  apis: ReturnType<typeof createNfyApis>
  ready: import('vue').ComputedRef<boolean>
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

export function provideNfy(options: { baseUrl: string; allowedOrigins: string[] }): NfyContext {
  const handshake = createEmbedHandshake({ allowedOrigins: options.allowedOrigins })
  handshake.start()

  const client = createNfyClient({
    baseUrl: options.baseUrl,
    getToken: () => handshake.token.value,
    getUserId: () => handshake.userId.value,
  })

  // 401（token 失效）→ 重新握手；其它错误页面自行呈现
  const apis = createNfyApis(client)
  const ctx: NfyContext = {
    handshake,
    apis,
    ready: computed(() => handshake.status.value === 'connected'),
  }
  provide(NFY_KEY, ctx)
  return ctx
}

export function useNfy(): NfyContext {
  const ctx = inject<NfyContext>(NFY_KEY)
  if (!ctx) throw new Error('nfy context 未提供：请在 NfyShell 内使用')
  return ctx
}

export function isTokenExpiredError(e: unknown): e is NfyApiError {
  return e instanceof NfyApiError && (e.code === 401 || e.code === 403)
}
