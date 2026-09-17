/**
 * nfy 轻量 API 客户端（消息中心专用，与 benefit4j request.ts 解耦）。
 *
 * 契约（接口设计文档 §2.4/§2.5）：
 * - 每请求注入 `Authorization: Bearer <token>`（嵌入握手的短期 token，≤1h）
 *   与 `X-User-Id`（终端用户透传标识，服务端与 token claim 绑定校验）。
 * - 响应信封 6 字段：code===0 → 解包返回 data；code!==0 → 抛 NfyApiError(code,message,fields)。
 * - HTTP 非 2xx（认证域 401/429、DomainGuard 403）→ NfyApiError(http 状态码)。
 */
import axios, { type AxiosInstance, type AxiosRequestConfig } from 'axios'

export interface NfyFieldError {
  field: string
  code: string
  message: string
}

export class NfyApiError extends Error {
  readonly code: number
  readonly fields?: NfyFieldError[]

  constructor(code: number, message: string, fields?: NfyFieldError[]) {
    super(message)
    this.name = 'NfyApiError'
    this.code = code
    this.fields = fields
  }
}

export interface NfyClientOptions {
  baseUrl: string
  getToken: () => string
  getUserId: () => string
  /** 测试注入口（axios adapter） */
  adapter?: AxiosRequestConfig['adapter']
  timeoutMs?: number
}

export interface NfyEnvelope<T> {
  code: number
  message: string
  data: T
  error: NfyFieldError[] | null
}

export function createNfyClient(options: NfyClientOptions) {
  const http: AxiosInstance = axios.create({
    baseURL: options.baseUrl,
    timeout: options.timeoutMs ?? 30000,
    adapter: options.adapter,
  })

  http.interceptors.request.use((config) => {
    const token = options.getToken()
    if (token) config.headers.Authorization = `Bearer ${token}`
    const userid = options.getUserId()
    if (userid) config.headers['X-User-Id'] = userid
    return config
  })

  async function unwrap<T>(p: Promise<{ data: NfyEnvelope<T> }>): Promise<T> {
    try {
      const resp = await p
      const envelope = resp.data
      if (envelope.code !== 0) {
        throw new NfyApiError(envelope.code, envelope.message, envelope.error ?? undefined)
      }
      return envelope.data
    } catch (e) {
      if (e instanceof NfyApiError) throw e
      if (axios.isAxiosError(e) && e.response) {
        throw new NfyApiError(e.response.status, `HTTP ${e.response.status}`)
      }
      throw new NfyApiError(-1, (e as Error).message)
    }
  }

  return {
    get: <T>(url: string, params?: Record<string, unknown>) =>
      unwrap<T>(http.get<T, { data: NfyEnvelope<T> }>(url, { params })),
    post: <T>(url: string, body?: unknown) =>
      unwrap<T>(http.post<T, { data: NfyEnvelope<T> }>(url, body)),
    put: <T>(url: string, body?: unknown) =>
      unwrap<T>(http.put<T, { data: NfyEnvelope<T> }>(url, body)),
    patch: <T>(url: string, body?: unknown) =>
      unwrap<T>(http.patch<T, { data: NfyEnvelope<T> }>(url, body)),
    del: <T>(url: string) =>
      unwrap<T>(http.delete<T, { data: NfyEnvelope<T> }>(url)),
  }
}

export type NfyClient = ReturnType<typeof createNfyClient>
