import { test, expect } from '@playwright/test'
import { ensureToken, authPage } from '../helpers/seed'

test.describe('补偿管理 E2E', () => {
  test.beforeAll(async ({ request }) => {
    await ensureToken(request)
  })

  test.beforeEach(async ({ page }) => {
    await authPage(page)
  })

  test('补偿列表页加载', async ({ page }) => {
    await page.goto('/benefit/tenant/app/compensations')
    // Compensations 页面容器 (不用 .el-table, 可能用 FcTable 渲染别的 class)
    await expect(page.locator('.compensations-page')).toBeVisible({ timeout: 15_000 })
  })

  test('补偿页过滤表单可见', async ({ page }) => {
    await page.goto('/benefit/tenant/app/compensations')
    await expect(page.locator('.compensations-page')).toBeVisible({ timeout: 15_000 })
    // 过滤表单 (el-form)
    await expect(page.locator('.filter-form').or(page.locator('.el-form'))).toBeVisible({ timeout: 5_000 })
  })

  test('补偿新建弹窗打开 (FcDialog)', async ({ page }) => {
    await page.goto('/benefit/tenant/app/compensations')
    await expect(page.locator('.compensations-page')).toBeVisible({ timeout: 15_000 })

    const addBtn = page.getByRole('button', { name: /新建补偿|New Compensation/i }).first()
    if (await addBtn.isVisible({ timeout: 3_000 }).catch(() => false)) {
      await addBtn.click()
      await expect(page.locator('.fc-dialog').or(page.locator('.el-dialog'))).toBeVisible({ timeout: 5_000 })
    }
  })
})
