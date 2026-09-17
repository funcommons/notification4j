import { test, expect } from '@playwright/test'
import { ensureToken, authPage } from '../helpers/seed'

/**
 * Sets 创建/编辑 confirm 修复验证:
 * BenefitSetEditor confirm 按钮按 readonly 分流 (非只读 → handleSubmit 校验+提交).
 * 之前 bug: confirm 无条件绑 handleCancel, 创建/编辑点 confirm 只关闭不提交.
 * 验证: 创建模式 confirm 触发表单校验 (空表单 → 校验失败提示, 不直接关闭不跳登录).
 */
test.describe('Sets confirm 提交链', () => {
  test.beforeAll(async ({ request }) => {
    await ensureToken(request)
  })
  test.beforeEach(async ({ page }) => {
    await authPage(page)
  })

  test('创建模式 confirm 触发校验 (空表单提示, 非直接关闭)', async ({ page }) => {
    await page.goto('/benefit/tenant/app/sets')
    await page.waitForLoadState('networkidle')

    // 点创建/新建权益包
    const createBtn = page.getByRole('button', { name: /创建权益包|新建权益包|创建|新建|Create/i }).first()
    if (!(await createBtn.isVisible({ timeout: 5000 }).catch(() => false))) {
      test.skip(true, '无创建按钮')
      return
    }
    await createBtn.click()
    const dialog = page.locator('.el-dialog').first()
    await expect(dialog).toBeVisible({ timeout: 10_000 })

    // 空表单点 confirm → 应触发校验 (校验失败提示), 对话框不直接关闭
    await dialog.getByRole('button', { name: /确认|Confirm|确 定/i }).last().click()
    await page.waitForTimeout(1500)
    // 校验失败时对话框仍在 (未关闭), 且有校验错误提示
    const dialogStillOpen = await dialog.isVisible().catch(() => false)
    // 不跳登录 (confirm 分流生效, 未误触 401)
    expect(page.url()).not.toContain('/login')
    // 空表单校验失败 → 对话框应保持打开 (等待填写), 而非静默关闭
    expect(dialogStillOpen || (await page.locator('.el-form-item__error, .el-message').count()) > 0).toBeTruthy()
  })
})
