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
  /**
   * 鉴权类失败回调（F-2）：HTTP 401 或信封 code∈fwk ApiCode 102xx「认证与账号类」段
   * （10200 未认证/10201 过期/10202 无效/10205 踢出/10207 格式错误/10208 注销…，
   * 服务端契约 §2.2：认证失败统一 HTTP 200 信封）。业务类失败（403 域闸、10100
   * 参数、103xx 权限、104xx 业务规则）不触发。可省略（向后兼容，维持原行为）。
   */
  onAuthExpired?: (e: NfyApiError) => void
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

/**
 * 鉴权类失败判定（F-2）：HTTP 401（网关/代理层）或 fwk ApiCode 102xx 认证与账号类段。
 * 刻意排除业务 403（域闸 SecurityException→HTTP 403）与 103xx 权限类——
 * 那些不是 token 失效，不应触发重握手。
 */
export function isAuthExpiredCode(code: number): boolean {
  return code === 401 || (code >= 10200 && code < 10300)
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

  function toNfyError(e: unknown): NfyApiError {
    if (e instanceof NfyApiError) return e
    if (axios.isAxiosError(e) && e.response) {
      return new NfyApiError(e.response.status, `HTTP ${e.response.status}`)
    }
    return new NfyApiError(-1, (e as Error).message)
  }

  async function unwrap<T>(p: Promise<{ data: NfyEnvelope<T> }>): Promise<T> {
    try {
      const resp = await p
      const envelope = resp.data
      if (envelope.code !== 0) {
        throw new NfyApiError(envelope.code, envelope.message, envelope.error ?? undefined)
      }
      return envelope.data
    } catch (e) {
      const err = toNfyError(e)
      if (options.onAuthExpired && isAuthExpiredCode(err.code)) options.onAuthExpired(err)
      throw err
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
