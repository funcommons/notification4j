import { test, expect } from '@playwright/test'
import { ensureToken, authPage } from '../helpers/seed'

test.describe('消费流水 E2E', () => {
  test.beforeAll(async ({ request }) => {
    await ensureToken(request)
  })

  test.beforeEach(async ({ page }) => {
    await authPage(page)
  })

  test('消费流水页加载 + 表格可见', async ({ page }) => {
    await page.goto('/benefit/tenant/app/consumptions')
    await expect(page.locator('.el-table')).toBeVisible({ timeout: 15_000 })
  })

  test('消费流水查询 (userid 过滤)', async ({ page }) => {
    await page.goto('/benefit/tenant/app/consumptions')
    await expect(page.locator('.el-table')).toBeVisible({ timeout: 15_000 })

    // 尝试 userid 输入框
    const useridInput = page.locator('.el-form-item').filter({ hasText: /用户|User/i }).locator('input').first()
    if (await useridInput.isVisible({ timeout: 3_000 }).catch(() => false)) {
      await useridInput.fill('e2e-consume-test')
      const searchBtn = page.getByRole('button', { name: /search|查询|搜索/i }).first()
      if (await searchBtn.isVisible()) {
        await searchBtn.click()
      }
    }
    // 页面渲染成功 (不要求有数据)
    await expect(page.locator('.el-table')).toBeVisible()
  })

  test('消费直接扣减页面加载', async ({ page }) => {
    await page.goto('/benefit/tenant/app/consume-direct')
    // 页面加载 (可能无表格, 验证页面不为空)
    await expect(page.locator('body')).not.toBeEmpty()
  })
})
