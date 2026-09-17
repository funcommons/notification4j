import { test, expect, type Page } from '@playwright/test'
import {
  BASE, PLATFORM_SECRET, mintToken, platformToken, createTenant, nfy, expectCode0,
  evidence, get, post, uniq,
} from './helpers/nfy'

/**
 * L1 认证与开放接入线 · Playwright 端到端回归
 * 契约：AUTH-001（client_credentials 换 token / 防爆破 5 次 15min）、
 *       OPEN-001（注册码自助注册，PRK-001 签发配合）
 * 对应 IT 深回归：NfyAuthFlowTest / NfyRegistrationKeyFlowTest / NfyReviewGapTest(并发扣减)
 */
test.describe('L1 认证与开放接入线', () => {
  let page: Page
  test.beforeAll(async ({ browser }) => { page = await browser.newPage() })
  test.afterAll(async () => { await page.close() })

  const l1Keys: string[] = []

  test('L1-01 AUTH-001 平台 client_credentials 换 token 成功', async () => {
    const r = await mintToken('PLATFORM', PLATFORM_SECRET)
    expectCode0(r)
    expect(r.data.access_token, '签发 JWT').toBeTruthy()
    await evidence(page, 'L1-01-平台token换取成功', {
      请求: 'POST /nfy/api/v1/auth/token (grant_type=client_credentials, client_id=PLATFORM)',
      响应: { code: r.code, message: r.message, access_token: r.data.access_token.slice(0, 32) + '...(截断)', trace_id: r.trace_id },
    })
  })

  test('L1-02 AUTH-001 新建租户凭据换 token 端到端', async () => {
    const plat = await platformToken()
    const t = await createTenant(plat, uniq('认证租户'))
    expect(t.token).toBeTruthy()
    await evidence(page, 'L1-02-租户凭据换token端到端', {
      流程: 'PTE-001 建租户 → 返回 open_id/tenant_secret(仅一次) → AUTH-001 换 token',
      open_id: t.open_id,
      tenant_secret: t.tenant_secret.slice(0, 8) + '...(截断)',
      token签发: '成功(JWT ' + t.token.slice(0, 20) + '...)',
    })
  })

  test('L1-03 AUTH-001 错误 secret 拒绝', async () => {
    const plat = await platformToken()
    const t = await createTenant(plat, uniq('错误密钥租户'))
    const r = await mintToken(t.open_id, 'wrong-secret-' + uniq('x'))
    expect(r.code, '非 0 错误码').not.toBe(0)
    expect(r.data ?? null, '不得签发 token').toBeNull()
    await evidence(page, 'L1-03-错误secret拒绝', { 请求: 'POST /auth/token (正确 client_id + 错误 secret)', 响应: { code: r.code, message: r.message } })
  })

  test('L1-04 AUTH-001 防爆破：连错 5 次后锁定，正确凭据也被拒', async () => {
    const plat = await platformToken()
    const t = await createTenant(plat, uniq('防爆破租���'))
    for (let i = 1; i <= 5; i++) {
      const bad = await mintToken(t.open_id, 'brute-' + i)
      expect(bad.code).not.toBe(0)
    }
    const locked = await mintToken(t.open_id, t.tenant_secret)
    expect(locked.code, '锁定后正确凭据亦被拒').not.toBe(0)
    await evidence(page, 'L1-04-防爆破锁定', {
      步骤: '同一 client_id 连续 5 次错误 secret（max-fail=5，lock-minutes=15）',
      第6次正确凭据响应: { code: locked.code, message: locked.message },
      结论: '锁定生效，正确凭据亦被拒（15min 窗口内）',
    })
  })

  test('L1-05 PRK-001 注册码签发 + 列表脱敏', async () => {
    const plat = await platformToken()
    const r = expectCode0(await nfy<{ registration_key?: string }>(await post(`${BASE}/nfy/platform/api/v1/registration-keys`,
      { max_uses: 1, expire_hours: 24 }, { token: plat })))
    const key = r.data.registration_key ?? ''
    expect(key, '完整注册码仅此一次返回').toBeTruthy()
    const list = expectCode0(await nfy(await get(`${BASE}/nfy/platform/api/v1/registration-keys`, { token: plat })))
    await evidence(page, 'L1-05-注册码签发与列表脱���', {
      签发: { code: r.code, max_uses: 1, expire_hours: 24, key: key.slice(0, 6) + '...(完整码仅返回一次)' },
      列表脱敏: JSON.stringify(list.data).includes(key) ? '【失败】完整码出现在列表' : '完整码未出现在列表（脱敏生效）',
    })
    expect(JSON.stringify(list.data), '列表不得含完整注册码').not.toContain(key)
    l1Keys.push(key)
  })

  test('L1-06 OPEN-001 注册码自助注册成功 → 新租户可认证', async () => {
    const key = l1Keys.pop()!
    const name = uniq('自助注册租户')
    const r = expectCode0(await nfy<Record<string, string>>(await post(`${BASE}/nfy/open/api/v1/tenants/register`,
      { registration_key: key, name })))
    expect(r.data.open_id, '注册返回 open_id').toBeTruthy()
    expect(r.data.tenant_secret, '注册返回 tenant_secret(仅一次)').toBeTruthy()
    const auth = await mintToken(r.data.open_id, r.data.tenant_secret)
    expectCode0(auth)
    await evidence(page, 'L1-06-自助注册端到端', {
      流程: `OPEN-001 注册(码 ${key.slice(0, 6)}...) → open_id/tenant_secret → AUTH-001 换 token`,
      新租户: r.data.open_id,
      认证: '成功（新租户立即具备接入能力）',
    })
  })

  test('L1-07 OPEN-001 max_uses=1 注册码消费后二次注册拒绝（原子扣减）', async () => {
    const plat = await platformToken()
    const issued = expectCode0(await nfy<{ registration_key?: string }>(await post(`${BASE}/nfy/platform/api/v1/registration-keys`,
      { max_uses: 1, expire_hours: 24 }, { token: plat })))
    const key = issued.data.registration_key ?? ''
    expectCode0(await nfy(await post(`${BASE}/nfy/open/api/v1/tenants/register`, { registration_key: key, name: uniq('一次性码租户A') })))
    const second = await nfy(await post(`${BASE}/nfy/open/api/v1/tenants/register`, { registration_key: key, name: uniq('一次性码租户B') }))
    expect(second.code, '同码第二次必须失败').not.toBe(0)
    await evidence(page, 'L1-07-注册码原子扣减', {
      注册码: { max_uses: 1 },
      第一次注册: '成功（扣减至 0）',
      第二次注册响应: { code: second.code, message: second.message },
      结论: '额度原子扣减，超额拒绝',
    })
  })

  test('L1-08 OPEN-001 max_uses=2 注册码恰好消费两次', async () => {
    const plat = await platformToken()
    const issued = expectCode0(await nfy<{ registration_key?: string }>(await post(`${BASE}/nfy/platform/api/v1/registration-keys`,
      { max_uses: 2, expire_hours: 24 }, { token: plat })))
    const key = issued.data.registration_key ?? ''
    expectCode0(await nfy(await post(`${BASE}/nfy/open/api/v1/tenants/register`, { registration_key: key, name: uniq('两次码租户A') })))
    expectCode0(await nfy(await post(`${BASE}/nfy/open/api/v1/tenants/register`, { registration_key: key, name: uniq('两次码租户B') })))
    const third = await nfy(await post(`${BASE}/nfy/open/api/v1/tenants/register`, { registration_key: key, name: uniq('两次码租户C') }))
    expect(third.code, '第三次必须失败').not.toBe(0)
    await evidence(page, 'L1-08-注册码两次额度边界', {
      注册码: { max_uses: 2 },
      消费: '第1次成功 / 第2次成功 / 第3次拒绝',
      第三次响应: { code: third.code, message: third.message },
    })
  })
})
