import { test, expect } from '@playwright/test'
import { ensureToken, authPage, seedSubscription } from '../helpers/seed'

test.describe('多源额度桶 (V1.2.0) E2E', () => {
  test.beforeAll(async ({ request }) => {
    await ensureToken(request)
  })

  test.beforeEach(async ({ page }) => {
    await authPage(page)
  })

  test('订阅明细展开: 展示多源桶 (source_type / bucket_priority / expires_at)', async ({ page, request }) => {
    const tag = `bucket-${Date.now()}`
    const { subscribeId } = await seedSubscription(request, tag)

    await page.goto('/benefit/tenant/app/subscriptions')
    await expect(page.locator('.subs-table')).toBeVisible({ timeout: 15_000 })

    // 通过 label 锁定 userid 输入框
    const useridInput = page.locator('.el-form-item').filter({ hasText: /用户ID|User ID/ }).locator('input').first()
    await useridInput.fill(`e2e-user-${tag}`)
    await page.getByRole('button', { name: /search|查询|搜索/i }).first().click()
    await expect(page.locator('.el-table__row').first()).toBeVisible({ timeout: 10_000 })

    // 展开第一行
    await page.locator('.el-table__expand-icon').first().click()
    await expect(page.locator('.bucket-detail').first()).toBeVisible({ timeout: 10_000 })

    const detail = page.locator('.bucket-detail').first()
    await expect(detail).toContainText('SUBSCRIPTION')
    await expect(detail.locator('.cell-id').first()).toBeVisible()
  })

  test('补偿页: 提交 ADD 创建一个新 TOPUP 桶并展示结果', async ({ page, request }) => {
    const tag = `comp-${Date.now()}`
    const { subscribeId, itemId, subsItemId } = await seedSubscription(request, tag)

    await page.goto('/benefit/tenant/app/compensations')
    await expect(page.locator('.compensations-page')).toBeVisible({ timeout: 15_000 })

    await page.getByRole('button', { name: /新建补偿/ }).first().click()
    await expect(page.locator('.el-dialog').first()).toBeVisible({ timeout: 10_000 })

    const dialog = page.locator('.el-dialog').first()
    // 排除 el-input-number 内部的 type=number input, 只锁文本框
    const textInputs = dialog.locator('input:not([type=number])')
    await textInputs.nth(0).fill(subscribeId)
    await textInputs.nth(1).fill(subsItemId)
    await textInputs.nth(2).fill(itemId)
    await dialog.locator('.el-input-number input').first().fill('5000')
    // ADD 分支: 用 placeholder 锁定 source_type 输入框
    await dialog.locator('input[placeholder*="TOPUP"]').fill('TOPUP')

    await page.getByRole('button', { name: /确认/ }).last().click()

    await expect(page.locator('.result-block').first()).toBeVisible({ timeout: 15_000 })
    await expect(page.locator('.result-block').first()).toContainText('TOPUP')
  })

  test('直接扣减页: 部分允许 (partialAllowed) 扣到能扣为止', async ({ page, request }) => {
    const tag = `partial-${Date.now()}`
    const { itemId } = await seedSubscription(request, tag)

    await page.goto('/benefit/tenant/app/consume-direct')
    await expect(page.locator('.consume-direct-page')).toBeVisible({ timeout: 15_000 })

    await page.getByRole('button', { name: /新建直接扣减/ }).first().click()
    await expect(page.locator('.el-dialog').first()).toBeVisible({ timeout: 10_000 })

    const dialog = page.locator('.el-dialog').first()
    const textInputs = dialog.locator('input:not([type=number])')
    await textInputs.nth(0).fill(`e2e-user-${tag}`)
    await textInputs.nth(1).fill(itemId)
    await dialog.locator('.el-input-number input').first().fill('999999')
    await textInputs.nth(2).fill(`e2e-consume-${tag}`)

    // FcSwitch 自实现, class 是 .fc-switch
    await dialog.locator('.fc-switch').first().click()

    await page.getByRole('button', { name: /确认/ }).last().click()

    await expect(page.locator('.el-alert--warning').first()).toBeVisible({ timeout: 15_000 })
    await expect(page.locator('.result-block').first()).toContainText('100')
  })

  test('直接扣减页: 严格模式 (默认) 总额不足整笔拒绝', async ({ page, request }) => {
    const tag = `strict-${Date.now()}`
    const { itemId } = await seedSubscription(request, tag)

    await page.goto('/benefit/tenant/app/consume-direct')
    await expect(page.locator('.consume-direct-page')).toBeVisible({ timeout: 15_000 })

    await page.getByRole('button', { name: /新建直接扣减/ }).first().click()
    await expect(page.locator('.el-dialog').first()).toBeVisible({ timeout: 10_000 })

    const dialog = page.locator('.el-dialog').first()
    const textInputs = dialog.locator('input:not([type=number])')
    await textInputs.nth(0).fill(`e2e-user-${tag}`)
    await textInputs.nth(1).fill(itemId)
    await dialog.locator('.el-input-number input').first().fill('999999')
    await textInputs.nth(2).fill(`e2e-strict-${tag}`)

    // 不开 partial_allowed
    await page.getByRole('button', { name: /确认/ }).last().click()

    await expect(page.locator('.el-result').first()).toBeVisible({ timeout: 15_000 })
    await expect(page.locator('.el-result__subtitle').first()).toContainText(/INSUFFICIENT_BALANCE|不足/)
  })
})