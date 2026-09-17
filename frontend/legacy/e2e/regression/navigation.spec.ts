import { test, expect } from '@playwright/test'

/**
 * 路由守卫回归 — 未登录访问受保护路由应重定向到对应登录页.
 * 仅 desktop.
 */

test.describe('路由守卫 — 未登录重定向', () => {
  test('tenant 受保护路由 → tenant 登录页', async ({ page }) => {
    await page.goto('/benefit/tenant/app/dashboard')
    await page.waitForURL(/\/benefit\/app\/tenant\/login/, { timeout: 15_000 })
    expect(page.url()).toContain('/benefit/app/tenant/login')
  })

  test('platform 受保护路由 → platform 登录页', async ({ page }) => {
    await page.goto('/benefit/platform/app/dashboard')
    await page.waitForURL(/\/benefit\/app\/platform\/login/, { timeout: 15_000 })
    expect(page.url()).toContain('/benefit/app/platform/login')
  })

  test('嵌套 tenant 路由 → tenant 登录页', async ({ page }) => {
    await page.goto('/benefit/tenant/app/subscriptions')
    await page.waitForURL(/\/benefit\/app\/tenant\/login/, { timeout: 15_000 })
    expect(page.url()).toContain('/benefit/app/tenant/login')
  })
})

test.describe('登录页 — 表单可用', () => {
  test('tenant 登录页表单元素', async ({ page }) => {
    await page.goto('/benefit/app/tenant/login')
    await page.waitForLoadState('networkidle')
    await expect(page.getByRole('textbox', { name: /Client ID/i }).first()).toBeVisible()
    await expect(page.getByRole('textbox', { name: /Client Secret|app_secret/i }).first()).toBeVisible()
    await expect(page.getByRole('button', { name: /登|Login|登录/i }).first()).toBeVisible()
  })

  test('platform 登录页表单元素', async ({ page }) => {
    await page.goto('/benefit/app/platform/login')
    await page.waitForLoadState('networkidle')
    await expect(page.getByRole('textbox', { name: /Client ID/i }).first()).toBeVisible()
    await expect(page.getByRole('button', { name: /登|Login|登录/i }).first()).toBeVisible()
  })

  test('tenant 登录页可填写并触发校验', async ({ page }) => {
    await page.goto('/benefit/app/tenant/login')
    await page.waitForLoadState('networkidle')
    const cid = page.getByRole('textbox', { name: /Client ID/i }).first()
    const csec = page.getByRole('textbox', { name: /Client Secret|app_secret/i }).first()
    await cid.fill('e2e-invalid-id')
    await csec.fill('e2e-invalid-secret')
    await page.getByRole('button', { name: /登|Login|登录/i }).first().click()
    // 无效凭证 → 应停留在登录页 (并给出错误提示, 不跳转 dashboard)
    await page.waitForTimeout(1500)
    expect(page.url()).toContain('/benefit/app/tenant/login')
  })
})

test.describe('首页 — 公开可访问', () => {
  test('首页 / 无需登录可访问', async ({ page }) => {
    await page.goto('/')
    await page.waitForLoadState('networkidle')
    await expect(page.locator('.home-hero')).toBeVisible()
  })

  test('首页各区块均渲染', async ({ page }) => {
    await page.goto('/')
    await page.waitForLoadState('networkidle')
    for (const id of ['#home-features', '#home-architecture', '#home-tech-stack', '#home-docs']) {
      await expect(page.locator(id).first()).toBeVisible()
    }
  })
})
