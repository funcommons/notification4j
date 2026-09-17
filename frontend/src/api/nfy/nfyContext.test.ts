import { describe, it, expect, vi, afterEach } from 'vitest'
import { nextTick } from 'vue'
import type { AxiosResponse, InternalAxiosRequestConfig } from 'axios'
import { createNfyRuntime } from './nfyContext'

// VECTOR: TAG=f2-auth-expired-wiring
// F-2 接线：client 鉴权类失败（HTTP 401 / 信封 code∈102xx）→
// authError 置位 + handshake.notifyExpired()（嵌入态回 waiting 重发 NFY_READY，
// 宿主重新投 NFY_TOKEN）；新 token 到达 → connected 且 authError 清除。
// 业务类失败（403 域闸）不得当作会话失效。
// axios 层经 adapter 桩注入（同 client.test.ts 套路，零 mock 依赖）。

function stubAdapter() {
  const responder = vi.fn((config: InternalAxiosRequestConfig): Partial<AxiosResponse> => {
    throw new Error('no route: ' + String(config.url))
  })
  const adapter = vi.fn(async (config: InternalAxiosRequestConfig): Promise<AxiosResponse> => {
    const resp = await responder(config)
    return { data: resp.data, status: resp.status ?? 200, statusText: 'OK', headers: {}, config }
  })
  return { adapter, responder }
}

const tokenMessage = (origin: string, token: string, userid = 'u_1') =>
  new MessageEvent('message', {
    origin,
    source: null,
    data: { type: 'NFY_TOKEN', token, user_id: userid },
  })

function setup() {
  const { adapter, responder } = stubAdapter()
  const rt = createNfyRuntime({
    baseUrl: '/nfy/api/v1',
    allowedOrigins: ['https://app.example.com'],
    adapter,
    isEmbedded: () => true,
  })
  return { rt, responder }
}

describe('nfyContext 鉴权失效接线', () => {
  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('信封 10202 → authError 置位 + 回 waiting；新 token → connected + authError 清除', async () => {
    const { rt, responder } = setup()
    responder
      .mockResolvedValueOnce({ data: { code: 10202, message: '登录凭证无效', data: null } })
      .mockResolvedValueOnce({ data: { code: 0, message: 'ok', data: { list: [], next_cursor: null, has_more: false } } })

    // 嵌入握手完成（bad token 非空即 connected）
    window.dispatchEvent(tokenMessage('https://app.example.com', 'bad-token'))
    await nextTick()
    expect(rt.handshake.status.value).toBe('connected')

    // 数据请求鉴权失败 → authError + 重新握手（waiting）
    await expect(rt.apis.messages.list({ limit: 5 })).rejects.toMatchObject({ code: 10202 })
    expect(rt.authError.value?.code).toBe(10202)
    expect(rt.handshake.status.value).toBe('waiting')

    // 宿主重发 NFY_TOKEN → 恢复 connected 且清除失效标记
    window.dispatchEvent(tokenMessage('https://app.example.com', 'good-token'))
    await nextTick()
    expect(rt.handshake.status.value).toBe('connected')
    expect(rt.authError.value).toBeNull()

    // 新 token 下请求成功，不再触发失效
    await expect(rt.apis.messages.list({ limit: 5 })).resolves.toMatchObject({ has_more: false })
    expect(rt.handshake.status.value).toBe('connected')
    expect(rt.authError.value).toBeNull()
    rt.handshake.stop()
  })

  it('HTTP 401 同样触发接线（authError=401 + 回 waiting）', async () => {
    const { rt, responder } = setup()
    responder.mockResolvedValue({ data: { code: 401, message: 'unauthorized' }, status: 401 })

    window.dispatchEvent(tokenMessage('https://app.example.com', 'jwt-a'))
    await nextTick()
    expect(rt.handshake.status.value).toBe('connected')

    await expect(rt.apis.messages.list({ limit: 5 })).rejects.toMatchObject({ code: 401 })
    expect(rt.authError.value?.code).toBe(401)
    expect(rt.handshake.status.value).toBe('waiting')
    rt.handshake.stop()
  })

  it('业务 403（域闸）不当作会话失效：authError 不置位、保持 connected', async () => {
    const { rt, responder } = setup()
    responder.mockResolvedValue({ data: { code: 403, message: '权限不足' }, status: 403 })

    window.dispatchEvent(tokenMessage('https://app.example.com', 'jwt-a'))
    await nextTick()

    await expect(rt.apis.messages.list({ limit: 5 })).rejects.toMatchObject({ code: 403 })
    expect(rt.authError.value).toBeNull()
    expect(rt.handshake.status.value).toBe('connected')
    rt.handshake.stop()
  })
})
