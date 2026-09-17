import { test, expect } from '@playwright/test'
import { ensureToken, authPage } from '../helpers/seed'

/**
 * 失败态回归 — benefitClient 拦截器分流 + 全局兜底.
 *   - 401 (10201 过期/10205 踢人/10208 吊销) → handleAuthFailure 跳登录
 *   - 429 → toast「操作过于频繁」
 *   - 409 → toast「请勿重复提交」
 *   - 5xx → toast 兜底 (error.response?.data?.message || 'API Error')
 *   - 网络中断 → toast 兜底
 *   - 业务 code != 0 → reject (业务层 toast)
 * mock 用 page.route, 不依赖后端真实错误态. 仅 desktop.
 */

test.describe('失败态 — 鉴权类 (跳登录)', () => {
  test.beforeAll(async ({ request }) => {
    await ensureToken(request)
  })
  test.beforeEach(async ({ page }) => {
    await authPage(page)
  })

  for (const { code, label } of [
    { code: 10201, label: 'token 过期' },
    { code: 10205, label: '被他端踢出' },
    { code: 10208, label: 'token 被吊销' },
  ]) {
    test(`401 ${label} (${code}) → 跳 tenant 登录页`, async ({ page }) => {
      await page.route('**/benefit/api/v1/**', (route) =>
        route.fulfill({
          status: 401,
          contentType: 'application/json',
          body: JSON.stringify({ code, message: 'auth failure' }),
        }),
      )
      await page.goto('/benefit/tenant/app/consumptions')
      await page.waitForURL(/\/benefit\/app\/tenant\/login/, { timeout: 15_000 })
      expect(page.url()).toContain('/benefit/app/tenant/login')
      // token 已清
      const token = await page.evaluate(() => localStorage.getItem('benefit4j:access_token'))
      expect(token).toBeNull()
    })
  }
})

test.describe('失败态 — 限流/重复 (toast)', () => {
  test.beforeAll(async ({ request }) => {
    await ensureToken(request)
  })
  test.beforeEach(async ({ page }) => {
    await authPage(page)
  })

  test('429 → toast「操作过于频繁」', async ({ page }) => {
    await page.route('**/benefit/api/v1/**', (route) =>
      route.fulfill({ status: 429, contentType: 'application/json', body: JSON.stringify({ code: -1, message: 'rl' }) }),
    )
    await page.goto('/benefit/tenant/app/consumptions')
    const msg = page.locator('.el-message').first()
    await expect(msg).toBeVisible({ timeout: 10_000 })
    await expect(msg).toContainText(/操作过于频繁/)
  })

  test('409 → toast「请勿重复提交」', async ({ page }) => {
    await page.route('**/benefit/api/v1/**', (route) =>
      route.fulfill({ status: 409, contentType: 'application/json', body: JSON.stringify({ code: 409, message: 'dup' }) }),
    )
    await page.goto('/benefit/tenant/app/consumptions')
    const msg = page.locator('.el-message').first()
    await expect(msg).toBeVisible({ timeout: 10_000 })
    await expect(msg).toContainText(/请勿重复提交/)
  })
})

test.describe('失败态 — 服务端/网络兜底', () => {
  test.beforeAll(async ({ request }) => {
    await ensureToken(request)
  })
  test.beforeEach(async ({ page }) => {
    await authPage(page)
  })

  test('500 → toast 显示后端 message', async ({ page }) => {
    await page.route('**/benefit/api/v1/**', (route) =>
      route.fulfill({
        status: 500,
        contentType: 'application/json',
        body: JSON.stringify({ code: -1, message: '服务内部错误-X' }),
      }),
    )
    await page.goto('/benefit/tenant/app/consumptions')
    const msg = page.locator('.el-message').first()
    await expect(msg).toBeVisible({ timeout: 10_000 })
    await expect(msg).toContainText(/服务内部错误-X|API Error/)
  })

  test('网络中断 → toast 兜底', async ({ page }) => {
    await page.route('**/benefit/api/v1/**', (route) => route.abort('failed'))
    await page.goto('/benefit/tenant/app/consumptions')
    const msg = page.locator('.el-message').first()
    await expect(msg).toBeVisible({ timeout: 10_000 })
    // 兜底文案
    await expect(msg).toContainText(/API Error|失败|network|Network/i)
  })

  test('业务 code != 0 → reject 由业务层 toast', async ({ page }) => {
    await page.route('**/benefit/api/v1/**', (route) =>
      route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ code: 10400, message: 'INSUFFICIENT_BALANCE-余额不足' }),
      }),
    )
    await page.goto('/benefit/tenant/app/consumptions')
    const msg = page.locator('.el-message').first()
    await expect(msg).toBeVisible({ timeout: 10_000 })
    await expect(msg).toContainText(/INSUFFICIENT_BALANCE|余额不足|操作失败/)
  })
})
