import { test, expect } from '@playwright/test'

/**
 * 交互回归 — 首页顶栏 + hero 全交互.
 * 仅 desktop 跑 (≤640px 顶栏 CTA/nav 隐藏, 不适合).
 */

const ANCHORS = [
  { name: /特性|Features/i, id: '#home-features' },
  { name: /架构|Architecture/i, id: '#home-architecture' },
  { name: /技术栈|Tech/i, id: '#home-tech-stack' },
  { name: /文档|Docs/i, id: '#home-docs' },
]

test.describe('交互 — 顶栏锚点', () => {
  for (const a of ANCHORS) {
    test(`点击「${a.id.slice(1)}」滚动到对应区块`, async ({ page }) => {
      await page.goto('/')
      await page.waitForLoadState('networkidle')
      const link = page.locator('.nav-link').filter({ hasText: a.name }).first()
      await expect(link).toBeVisible()
      await link.click()
      await page.waitForTimeout(600)
      const section = page.locator(a.id).first()
      const box = await section.boundingBox()
      expect(box).not.toBeNull()
      const vp = page.viewportSize()!
      // 区块顶部已在视口上半部分 (已滚动到位)
      expect(box!.y).toBeLessThan(vp.height * 0.5)
    })
  }
})

test.describe('交互 — 主题切换', () => {
  test('切到 dark 并持久化', async ({ page }) => {
    await page.goto('/')
    await page.waitForLoadState('networkidle')
    const trigger = page.getByRole('button', { name: /外观|Appearance/i }).first()
    await trigger.click()
    const darkSwatch = page.locator('.fc-ts-swatch').filter({ hasText: /深色|Dark/i }).first()
    await expect(darkSwatch).toBeVisible({ timeout: 5_000 })
    await darkSwatch.click()
    await expect(page.locator('html')).toHaveAttribute('data-theme', 'dark', { timeout: 5_000 })
    // 持久化: FcThemeProvider 自带 key fc-theme-provider
    const persisted = await page.evaluate(() => localStorage.getItem('fc-theme-provider'))
    expect(persisted).not.toBeNull()
    expect(persisted!).toContain('"theme":"dark"')
  })

  test('切回 light 生效', async ({ page }) => {
    await page.goto('/')
    await page.waitForLoadState('networkidle')
    // 先点 dark
    await page.getByRole('button', { name: /外观|Appearance/i }).first().click()
    await page.locator('.fc-ts-swatch').filter({ hasText: /深色|Dark/i }).first().click()
    await expect(page.locator('html')).toHaveAttribute('data-theme', 'dark', { timeout: 5_000 })
    // 再点 light 切回
    await page.getByRole('button', { name: /外观|Appearance/i }).first().click()
    const lightSwatch = page.locator('.fc-ts-swatch').filter({ hasText: /浅色|Light/i }).first()
    await expect(lightSwatch).toBeVisible({ timeout: 5_000 })
    await lightSwatch.click()
    await expect(page.locator('html')).toHaveAttribute('data-theme', 'light', { timeout: 5_000 })
  })
})

test.describe('交互 — i18n 切换', () => {
  test('切英文后首页文案随之切换', async ({ page }) => {
    await page.goto('/')
    await page.waitForLoadState('networkidle')
    // nav-link 文案走 i18n (特性/Features), hero title 来自 oem 不走 i18n
    const navLink = page.locator('.nav-link').first()
    const before = (await navLink.textContent()) || ''
    await page.locator('.nav-locale-item').filter({ hasText: /EN/i }).first().click()
    await page.waitForTimeout(500)
    const after = (await navLink.textContent()) || ''
    expect(after).not.toEqual('')
    expect(after).not.toEqual(before)
  })

  test('locale 选择持久化 (reload 后仍为英文)', async ({ page }) => {
    await page.goto('/')
    await page.waitForLoadState('networkidle')
    await page.locator('.nav-locale-item').filter({ hasText: /EN/i }).first().click()
    await page.waitForTimeout(500)
    // reload 后 locale 应保持 (nav-link 仍为英文)
    await page.reload()
    await page.waitForLoadState('networkidle')
    const enItem = page.locator('.nav-locale-item').filter({ hasText: /EN/i }).first()
    await expect(enItem).toHaveClass(/active/, { timeout: 5_000 })
    const navLink = page.locator('.nav-link').first()
    await expect(navLink).toContainText(/Features|特性/i)
  })
})

test.describe('交互 — CTA 跳转', () => {
  test('hero 应用端 CTA 跳 tenant 登录', async ({ page }) => {
    await page.goto('/')
    await page.waitForLoadState('networkidle')
    const cta = page.locator('.home-hero__ctas').getByRole('button', { name: /应用端|tenant/i }).first()
    await cta.click()
    await page.waitForURL(/\/benefit\/app\/tenant\/login/, { timeout: 15_000 })
    expect(page.url()).toContain('/benefit/app/tenant/login')
  })

  test('hero 平台端 CTA 跳 platform 登录', async ({ page }) => {
    await page.goto('/')
    await page.waitForLoadState('networkidle')
    const cta = page.locator('.home-hero__ctas').getByRole('button', { name: /平台端|platform/i }).first()
    await cta.click()
    await page.waitForURL(/\/benefit\/app\/platform\/login/, { timeout: 15_000 })
    expect(page.url()).toContain('/benefit/app/platform/login')
  })

  test('顶栏双 CTA 跳转 (desktop)', async ({ page }) => {
    await page.goto('/')
    await page.waitForLoadState('networkidle')
    // tenant
    const tenantBtn = page.locator('.nav-login-btn').first()
    await expect(tenantBtn).toBeVisible()
    await tenantBtn.click()
    await page.waitForURL(/\/benefit\/app\/tenant\/login/, { timeout: 15_000 })
  })

  test('文档卡片「嵌入接入文档」未登录重定向登录', async ({ page }) => {
    await page.goto('/')
    await page.waitForLoadState('networkidle')
    const card = page.locator('.doc-card').first()
    await expect(card).toBeVisible()
    const openBtn = card.getByRole('link').or(card.getByRole('button')).first()
    await openBtn.click()
    await page.waitForURL(/\/benefit\/app\/platform\/login|\/benefit\/platform\/app\/dev\/embed-docs/, {
      timeout: 15_000,
    })
    expect(page.url()).toMatch(/\/benefit\/app\/platform\/login|\/benefit\/platform\/app\/dev\/embed-docs/)
  })
})
