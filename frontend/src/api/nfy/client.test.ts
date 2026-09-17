import { describe, it, expect, vi } from 'vitest'
import type { AxiosRequestConfig, AxiosResponse, InternalAxiosRequestConfig } from 'axios'
import { createNfyClient, NfyApiError } from './client'
import { createDeliveryApi } from './index'

// VECTOR: TAG=step7-nfy-client
// 契约（接口设计文档 §2.4/§2.5）：Bearer token + X-User-Id 头注入；
// 信封 6 字段——code===0 解包 data，非 0 抛 NfyApiError(code,message)；
// HTTP 非 2xx（认证 401/429 例外）→ NfyApiError(http 码)。
// 测试经 axios adapter 注入桩（零 mock 依赖）。

/** 桩 adapter：记录请求并按注册表应答 */
function stubAdapter() {
  const requests: InternalAxiosRequestConfig[] = []
  const responder = vi.fn((config: InternalAxiosRequestConfig): Partial<AxiosResponse> => {
    throw new Error('no route: ' + String(config.url))
  })
  const adapter = vi.fn(async (config: InternalAxiosRequestConfig): Promise<AxiosResponse> => {
    requests.push(config)
    const resp = await responder(config)
    return {
      data: resp.data,
      status: resp.status ?? 200,
      statusText: 'OK',
      headers: {},
      config,
    }
  })
  return { adapter, requests, responder }
}

describe('nfy client', () => {
  it('GET 注入 Bearer 与 X-User-Id 头并解包 data', async () => {
    const { adapter, requests, responder } = stubAdapter()
    responder.mockResolvedValue({ data: { code: 0, message: 'ok', data: { unread_count: 3 } } })
    const client = createNfyClient({
      baseUrl: '/nfy/api/v1',
      getToken: () => 'jwt-a',
      getUserId: () => 'u_1',
      adapter,
    })
    const data = await client.get<{ unread_count: number }>('/runtime/messages/unread-count')
    expect(data.unread_count).toBe(3)
    expect(requests[0]?.headers?.Authorization).toBe('Bearer jwt-a')
    expect(requests[0]?.headers?.['X-User-Id']).toBe('u_1')
    expect(requests[0]?.url).toContain('/runtime/messages/unread-count')
  })

  it('token 动态变化生效（嵌入握手续签）', async () => {
    const { adapter, requests, responder } = stubAdapter()
    responder.mockResolvedValue({ data: { code: 0, data: { list: [] } } })
    let token = 'jwt-a'
    const client = createNfyClient({
      baseUrl: '/nfy/api/v1',
      getToken: () => token,
      getUserId: () => 'u_1',
      adapter,
    })
    await client.get('/runtime/messages')
    token = 'jwt-b'
    await client.get('/runtime/messages')
    expect(requests[1]?.headers?.Authorization).toBe('Bearer jwt-b')
  })

  it('信封 code!=0 抛 NfyApiError(10100) 并带字段错误', async () => {
    const { adapter, responder } = stubAdapter()
    responder.mockResolvedValue({
      data: {
        code: 10100, message: '请求参数错误', data: null,
        error: [{ field: 'target', code: 'FORMAT_INVALID', message: 'Webhook 地址非法' }],
      },
    })
    const client = createNfyClient({
      baseUrl: '/nfy/api/v1', getToken: () => 't', getUserId: () => 'u', adapter,
    })
    const err = await client.post('/runtime/channels', {}).catch((e) => e)
    expect(err).toBeInstanceOf(NfyApiError)
    expect((err as NfyApiError).code).toBe(10100)
    expect((err as NfyApiError).fields?.[0]?.field).toBe('target')
  })

  it('HTTP 非 2xx（如认证 401）→ NfyApiError(http 码)', async () => {
    const { adapter, responder } = stubAdapter()
    responder.mockResolvedValue({ data: { code: 401 }, status: 401 })
    const client = createNfyClient({
      baseUrl: '/nfy/api/v1', getToken: () => 't', getUserId: () => 'u', adapter,
    })
    const err = await client.get('/runtime/messages').catch((e) => e)
    expect(err).toBeInstanceOf(NfyApiError)
    expect((err as NfyApiError).code).toBe(401)
  })

  it('PUT 以 JSON 体提交', async () => {
    const { adapter, requests, responder } = stubAdapter()
    responder.mockResolvedValue({ data: { code: 0, data: { saved_count: 1 } } })
    const client = createNfyClient({
      baseUrl: '/nfy/api/v1', getToken: () => 't', getUserId: () => 'u', adapter,
    })
    await client.put('/runtime/subscriptions', { items: [] })
    expect(requests[0]?.data).toBe(JSON.stringify({ items: [] }))
  })
})

// VECTOR: TAG=v1.2-dlv-ui
// API-DLV-001/002（§5.9.2 管理面）：筛选+Offset 分页查询、仅 DEAD 人工重投。
describe('nfy deliveries api', () => {
  function makeApis(adapter: AxiosRequestConfig['adapter']) {
    const client = createNfyClient({
      baseUrl: '/nfy/api/v1', getToken: () => 't', getUserId: () => 'u', adapter,
    })
    return createDeliveryApi(client)
  }

  it('list GET /admin/deliveries：筛选与 Offset 分页参数透传并解包 {list,total}', async () => {
    const { adapter, requests, responder } = stubAdapter()
    responder.mockResolvedValue({
      data: {
        code: 0, message: 'ok',
        data: {
          list: [{ delivery_id: '9', userid: 'u_1', channel_type: 'EMAIL', status: 'DEAD', retry_count: 3, error_message: 'x', sent_at: null, created_at: 1700000000000 }],
          total: 42,
        },
      },
    })
    const api = makeApis(adapter)
    const data = await api.list({
      biz_no: 'B202', userid: 'u_1', channel_type: 'EMAIL', status: 'DEAD',
      created_after: 1700000000000, created_before: 1790000000000, offset: 20, limit: 10,
    })
    expect(data.total).toBe(42)
    expect(data.list[0]?.delivery_id).toBe('9')
    expect(data.list[0]?.status).toBe('DEAD')
    expect(requests[0]?.method).toBe('get')
    expect(requests[0]?.url).toContain('/admin/deliveries')
    expect(requests[0]?.params).toEqual({
      biz_no: 'B202', userid: 'u_1', channel_type: 'EMAIL', status: 'DEAD',
      created_after: 1700000000000, created_before: 1790000000000, offset: 20, limit: 10,
    })
  })

  it('list 可只传部分筛选（缺省项不带 undefined 噪声）', async () => {
    const { adapter, requests, responder } = stubAdapter()
    responder.mockResolvedValue({ data: { code: 0, data: { list: [], total: 0 } } })
    const api = makeApis(adapter)
    await api.list({ userid: 'u_9', offset: 0, limit: 20 })
    expect(requests[0]?.params).toEqual({ userid: 'u_9', offset: 0, limit: 20 })
  })

  it('retry POST /admin/deliveries/{id}/retry 并解包 {status:"PENDING"}', async () => {
    const { adapter, requests, responder } = stubAdapter()
    responder.mockResolvedValue({ data: { code: 0, data: { delivery_id: '9', status: 'PENDING' } } })
    const api = makeApis(adapter)
    const data = await api.retry('9')
    expect(data.status).toBe('PENDING')
    expect(requests[0]?.method).toBe('post')
    expect(requests[0]?.url).toContain('/admin/deliveries/9/retry')
  })

  it('retry 非 DEAD：信封 code=10402 → 抛 NfyApiError(10402)', async () => {
    const { adapter, responder } = stubAdapter()
    responder.mockResolvedValue({ data: { code: 10402, message: '非 DEAD 状态不可重投', data: null } })
    const api = makeApis(adapter)
    const err = await api.retry('8').catch((e: unknown) => e)
    expect(err).toBeInstanceOf(NfyApiError)
    expect((err as NfyApiError).code).toBe(10402)
    expect((err as NfyApiError).message).toBe('非 DEAD 状态不可重投')
  })
})

// VECTOR: TAG=f2-auth-expired-callback
// F-2：鉴权类失败 → onAuthExpired 回调（供 nfyContext 接线 notifyExpired/会话失效提示）。
// 鉴权类 = HTTP 401 或信封 code∈fwk ApiCode 102xx「认证与账号类」段
// （10200 未认证/10201 过期/10202 无效/10205 踢出/10207 格式错误/10208 注销…，
//   实测 9200：无效签名→10202，乱串→10207，空 token→10200，均 HTTP 200 信封）。
// 业务类失败（403 域闸 / 10100 参数 / 10402 业务规则 / 103xx 权限）不得触发。
describe('nfy client onAuthExpired', () => {
  function makeClient(adapter: AxiosRequestConfig['adapter'], onAuthExpired?: (e: NfyApiError) => void) {
    return createNfyClient({ baseUrl: '/nfy/api/v1', getToken: () => 't', getUserId: () => 'u', adapter, onAuthExpired })
  }

  it.each([
    ['信封 code=10202 令牌无效', { data: { code: 10202, message: '登录凭证无效', data: null } }, 10202],
    ['信封 code=10201 凭证过期', { data: { code: 10201, message: '登录凭证已过期', data: null } }, 10201],
    ['信封 code=10207 令牌格式错误', { data: { code: 10207, message: '令牌格式错误', data: null } }, 10207],
    ['HTTP 401', { data: { code: 401, message: 'unauthorized' }, status: 401 }, 401],
  ])('%s → 抛 NfyApiError 且触发 onAuthExpired', async (_name, resp, code) => {
    const { adapter, responder } = stubAdapter()
    responder.mockResolvedValue(resp)
    const onAuthExpired = vi.fn()
    const client = makeClient(adapter, onAuthExpired)
    const err = await client.get('/runtime/messages').catch((e: unknown) => e)
    expect(err).toBeInstanceOf(NfyApiError)
    expect((err as NfyApiError).code).toBe(code)
    expect(onAuthExpired).toHaveBeenCalledTimes(1)
    expect(onAuthExpired.mock.calls[0]?.[0]).toBe(err)
  })

  it.each([
    ['业务 403（域闸，HTTP 403）', { data: { code: 403, message: '权限不足' }, status: 403 }],
    ['权限 10300', { data: { code: 10300, message: '无权限访问', data: null } }],
    ['参数 10100', { data: { code: 10100, message: '请求参数错误', data: null } }],
    ['业务规则 10402', { data: { code: 10402, message: '非 DEAD 状态不可重投', data: null } }],
  ])('%s → 不触发 onAuthExpired', async (_name, resp) => {
    const { adapter, responder } = stubAdapter()
    responder.mockResolvedValue(resp)
    const onAuthExpired = vi.fn()
    const client = makeClient(adapter, onAuthExpired)
    await expect(client.get('/runtime/messages')).rejects.toBeInstanceOf(NfyApiError)
    expect(onAuthExpired).not.toHaveBeenCalled()
  })

  it('未提供 onAuthExpired 时行为不变（向后兼容）', async () => {
    const { adapter, responder } = stubAdapter()
    responder.mockResolvedValue({ data: { code: 10202, message: '登录凭证无效', data: null } })
    const client = makeClient(adapter)
    await expect(client.get('/runtime/messages')).rejects.toMatchObject({ code: 10202 })
  })
})
