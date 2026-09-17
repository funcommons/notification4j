import { test, expect } from '@playwright/test'

/**
 * dev 页可用性回归 — /dev/* 是给开发者用的功能, 必须可访问可渲染.
 * 登录走 dev mock (dev-interceptor: 任意 phone/password 返回 ops 用户).
 * 仅 desktop.
 */

test.describe('dev 页可用性', () => {
  test('登录 (mock ops) → 访问 /dev 渲染', async ({ page }) => {
    await page.goto('/login')
    await page.waitForLoadState('networkidle')

    // 开发模式: 任意账号密码即可登录 (mock 返回 ops)
    const phoneInput = page.locator('input:not([type=password])').first()
    await phoneInput.fill('13800000000')
    await page.locator('input[type=password]').first().fill('dev-any-password')
    await page.getByRole('button', { name: /登录|Login|登 录/i }).first().click()

    // 等登录完成 (mock 返回 token + ops user)
    await page.waitForTimeout(2000)

    // 访问 /dev 索引页
    await page.goto('/dev')
    await page.waitForLoadState('networkidle')

    // 守卫应通过 (ops), 不停在 /login
    expect(page.url()).not.toContain('/login')
    // dev 页渲染: 有内容 (AppLayout 侧栏 或 dev 内容容器)
    await expect(page.locator('body')).not.toBeEmpty()
  })

  test('/dev 子页可访问 (InspirationDemo)', async ({ page }) => {
    // 先登录
    await page.goto('/login')
    await page.waitForLoadState('networkidle')
    await page.locator('input:not([type=password])').first().fill('13800000000')
    await page.locator('input[type=password]').first().fill('dev-any-password')
    await page.getByRole('button', { name: /登录|Login|登 录/i }).first().click()
    await page.waitForTimeout(2000)

    // 访问 dev 子页 (InspirationDemo 用 types.ts WorkDetailVO — 验证清理没破坏)
    await page.goto('/dev/inspiration')
    await page.waitForLoadState('networkidle')
    expect(page.url()).not.toContain('/login')
    await expect(page.locator('body')).not.toBeEmpty()
  })
})
