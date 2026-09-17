import axios from 'axios'
import type { AxiosInstance, InternalAxiosRequestConfig, AxiosError, AxiosResponse } from 'axios'
import { v4 as uuidv4 } from 'uuid'
import { getOrCreateTraceId } from '@/utils/trace'
import { authBus } from '@/utils/authBus'
import { buildSignatureHeaders, isRuntimeUrl } from '@/utils/signature'

export type { ApiResponse } from './types'
import type { ApiResponse } from './types'

/**
 * Token 提供者 — 默认从 sessionStorage 读 (基础级 / 登录页模式)。
 * 嵌入推荐级 (postMessage) 模式下 token 只在内存, 需通过
 * setBenefitTokenProvider(() => useBenefitAuthStore().token) 注入 store 读取。
 *
 * 与 request.ts 的 setUserStoreGetter 同一模式, 避免 benefitClient ↔ store 循环依赖。
 */
let tokenProvider: () => string | null = () => sessionStorage.getItem('benefit4j:access_token')

export function setBenefitTokenProvider(provider: () => string | null) {
  tokenProvider = provider
}

// 鉴权业务码 (来自 framework4j-accesstoken 拦截器):
//   10201 — 令牌已过期或不存在
//   10205 — 账号已在别处登录 (单会话策略)
//   10208 — 令牌已注销
const BIZ_TOKEN_EXPIRED = 10201
const BIZ_TOKEN_KICKED = 10205
const BIZ_TOKEN_REVOKED = 10208

export const benefitClient: AxiosInstance = axios.create({
  baseURL: '/',
  timeout: 10000,
})

benefitClient.interceptors.request.use((config: InternalAxiosRequestConfig) => {
  const token = tokenProvider()
  if (token) {
    config.headers.set('Authorization', `Bearer ${token}`)
  }

  // 链路追踪: 与主站 request.ts 共享同一 session trace-id
  config.headers.set('X-Trace-Id', getOrCreateTraceId())

  if ((config.method === 'post' || config.method === 'put') && !config.headers.has('Idempotency-Key')) {
    config.headers.set('Idempotency-Key', uuidv4())
  }

  // runtime 域强制 HMAC-SHA256 签名 (与后端 @RequiresSignature 对齐)
  const url = config.url || ''
  if (isRuntimeUrl(url)) {
    const secret = sessionStorage.getItem('benefit4j:app_secret')
    const accessKey = sessionStorage.getItem('benefit4j:tenant_id')
    if (secret && accessKey) {
      const sig = buildSignatureHeaders(config.method || 'get', url, secret, accessKey, config.data)
      config.headers.set('X-Access-Key', sig['X-Access-Key'])
      config.headers.set('X-Timestamp', sig['X-Timestamp'])
      config.headers.set('X-Nonce', sig['X-Nonce'])
      config.headers.set('X-Signature', sig['X-Signature'])
    }
  }

  return config
})

benefitClient.interceptors.response.use(
  (response: AxiosResponse<ApiResponse>) => {
    const res = response.data
    if (res.code !== 0) {
      return Promise.reject(new Error(res.message || 'API Error'))
    }
    return res as any
  },
  async (error: AxiosError<{ code?: number; message?: string }>) => {
    const status = error.response?.status
    const code = error.response?.data?.code

    // 401 / token 过期/踢人/吊销 → 跳登录页 (已有逻辑)
    if (status === 401 || code === BIZ_TOKEN_EXPIRED || code === BIZ_TOKEN_KICKED || code === BIZ_TOKEN_REVOKED) {
      handleAuthFailure(code ?? BIZ_TOKEN_EXPIRED)
    }

    // 429 限流 → 提示稍后重试 (不打扰跳页)
    if (status === 429) {
      return Promise.reject(new Error('操作过于频繁, 请稍后重试'))
    }

    // 409 重复提交 → 提示勿重复
    if (status === 409 || code === 409) {
      return Promise.reject(new Error('请勿重复提交, 已为您返回首次结果'))
    }

    const message = error.response?.data?.message || error.message || 'API Error'
    return Promise.reject(new Error(message))
  },
)

function handleAuthFailure(code: number): void {
  // 通过 BroadcastChannel 让其他标签页也跟着跳走 (A 页被踢 → B 页同步)
  authBus.emit({
    type: 'auth-expired',
    code,
    timestamp: Date.now(),
  })

  // 清登录态
  sessionStorage.removeItem('benefit4j:access_token')
  sessionStorage.removeItem('benefit4j:expires_at')
  const tenantId = sessionStorage.getItem('benefit4j:tenant_id')
  sessionStorage.removeItem('benefit4j:tenant_id')

  // 被踢/注销: 弹窗友好提示; 过期: 静默踢 (业务上用户操作时触发, 提示反而打断)
  if (code === BIZ_TOKEN_KICKED) {
    // ElMessage 异步弹; window.location.href 是同步跳, 提示走 store / 全局 toast 队列
    console.warn('[benefitClient] token kicked by another session (10205)')
  } else if (code === BIZ_TOKEN_REVOKED) {
    console.warn('[benefitClient] token revoked (10208)')
  }

  const loginPath = tenantId === 'PLATFORM' ? '/benefit/app/platform/login' : '/benefit/app/tenant/login'
  window.location.href = loginPath
}