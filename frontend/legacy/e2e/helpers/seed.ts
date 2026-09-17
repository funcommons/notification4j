/**
 * E2E 通用 helper: 鉴权注入 + 数据 seed
 *
 * 关键约束 (来自 framework4j-accesstoken 单会话策略):
 *   - 同一 (app_id, token_type) 下每个新 token 会覆盖 Redis nonce
 *   - 因此 UI 登录产生的 token 会被 API token 顶替, 反之亦然
 *   - 解决: 一次 token 复用, 不在 spec 内部并发再申请
 *
 * 用法:
 *   test.beforeAll(async ({ request }) => { await ensureToken(request) })
 *   test.beforeEach(async ({ page }) => { await authPage(page) })
 *   const { subscribeId, itemId, subsItemId } = await seedSubscription(request, 'bucket-123')
 */

import type { APIRequestContext, Page } from '@playwright/test'

export const TENANT_ID = 'paYSmFC67GbW'
export const TENANT_SECRET = 'df15b6301adc4e3ea7baf90933991dba'
export const BASE_BE = 'http://localhost:9200'

// Module-level 单例: beforeAll 申请一次, 全 spec 复用
let SHARED_TOKEN: string | null = null
let SHARED_EXPIRES_AT: number | null = null

export interface SharedToken {
  token: string
  expiresAt: number
}

/** 申请 tenant access token. 已有就复用, 不会产生新 token (避免覆盖 Redis nonce) */
export async function ensureToken(request: APIRequestContext): Promise<SharedToken> {
  if (SHARED_TOKEN && SHARED_EXPIRES_AT && Date.now() < SHARED_EXPIRES_AT - 60_000) {
    return { token: SHARED_TOKEN, expiresAt: SHARED_EXPIRES_AT }
  }
  const res = await request.post(`${BASE_BE}/benefit/api/v1/auth/token`, {
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    form: { grant_type: 'client_credentials', client_id: TENANT_ID, client_secret: TENANT_SECRET },
  })
  const body = await res.json()
  if (body.code !== 0) throw new Error(`token request failed: ${body.message}`)
  SHARED_TOKEN = body.data.access_token
  SHARED_EXPIRES_AT = Date.now() + body.data.expires_in * 1000
  return { token: SHARED_TOKEN, expiresAt: SHARED_EXPIRES_AT }
}

/** 把 ensureToken 拿到的 token 注入到页面 localStorage, 让前端 store 直接读到 */
export async function authPage(page: Page): Promise<void> {
  if (!SHARED_TOKEN || !SHARED_EXPIRES_AT) {
    throw new Error('authPage called before ensureToken; use beforeAll to initialize.')
  }
  await page.goto('/benefit/tenant/app/dashboard')
  await page.waitForLoadState('domcontentloaded')
  await page.evaluate(
    ({ token, expiresAt, appId, appSecret }) => {
      localStorage.setItem('benefit4j:access_token', token)
      localStorage.setItem('benefit4j:expires_at', String(expiresAt))
      localStorage.setItem('benefit4j:app_id', appId)
      localStorage.setItem('benefit4j:app_secret', appSecret)
    },
    { token: SHARED_TOKEN, expiresAt: SHARED_EXPIRES_AT, appId: TENANT_ID, appSecret: TENANT_SECRET },
  )
  // 重新加载让 Pinia 从 localStorage 读到 token
  await page.goto('/benefit/tenant/app/dashboard')
  await page.waitForURL(/\/benefit\/tenant\/app\/dashboard/, { timeout: 10_000 })
}

export interface SeedResult {
  subscribeId: string
  itemId: string
  subsItemId: string
}

/** 通过 tenant API 创建一个完整的 item / set / subscription, 拿到原始桶 ID */
export async function seedSubscription(
  request: APIRequestContext,
  tag: string,
): Promise<SeedResult> {
  const { token } = await ensureToken(request)
  const headers = { Authorization: `Bearer ${token}` }

  const itemRes = await request.post(`${BASE_BE}/benefit/api/v1/tenant/benefit-items`, {
    headers,
    data: { name: `e2e-item-${tag}` },
  })
  const itemBody = await itemRes.json()
  if (itemBody.code !== 0) throw new Error(`item create failed: ${JSON.stringify(itemBody)}`)
  const itemId: string = itemBody.data.item_id

  const setRes = await request.post(`${BASE_BE}/benefit/api/v1/tenant/benefit-sets`, {
    headers,
    data: {
      name: `e2e-set-${tag}`,
      duration: 1,
      duration_unit: 'month',
      quota: 100,
      priority: 10,
      items: [{ item_id: itemId, quota: 100, refresh_cycle: 1, refresh_cycle_unit: 'day' }],
    },
  })
  const setBody = await setRes.json()
  if (setBody.code !== 0) throw new Error(`set create failed: ${JSON.stringify(setBody)}`)
  const setId: string = setBody.data.set_id

  const subRes = await request.post(`${BASE_BE}/benefit/api/v1/tenant/subscriptions`, {
    headers,
    data: { userid: `e2e-user-${tag}`, set_id: setId, external_order_id: `e2e-order-${tag}` },
  })
  const subBody = await subRes.json()
  if (subBody.code !== 0) throw new Error(`subscribe failed: ${JSON.stringify(subBody)}`)
  const subscribeId: string = subBody.data.subscribe_id
  if (!subscribeId) throw new Error('subscribe_id empty')

  // 列出桶拿 subsItemId
  const itemsRes = await request.get(
    `${BASE_BE}/benefit/api/v1/tenant/subscriptions/${subscribeId}/items`,
    { headers },
  )
  const itemsBody = await itemsRes.json()
  if (itemsBody.code !== 0) throw new Error(`items failed: ${JSON.stringify(itemsBody)}`)
  const subsItemId: string = itemsBody.data.list[0]?.id
  if (!subsItemId) throw new Error('subsItemId empty')

  return { subscribeId, itemId, subsItemId }
}