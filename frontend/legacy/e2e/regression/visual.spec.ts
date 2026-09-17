import { test, expect } from '@playwright/test'

/**
 * 视觉回归 — 首页 + 登录页, light/dark + 代码块固定深色.
 * 首次生成基准: npx playwright test visual.spec.ts --update-snapshots=missing
 * maxDiffPixelRatio 容差抗字体/渲染差异. 仅 desktop.
 *
 * 主题应用: goto 后 evaluate 直接设 html[data-theme], CSS 变量立即切换.
 * (addInitScript 注 localStorage 无效 — store rehydrate 不调 applyToRoot.)
 */

async function applyTheme(page: import('@playwright/test').Page, theme: 'light' | 'dark') {
  await page.evaluate((t) => {
    document.documentElement.setAttribute('data-theme', t)
  }, theme)
  // 等 CSS 变量应用
  await page.waitForTimeout(300)
}

test.describe('视觉回归 — 首页', () => {
  test('light 模式全页', async ({ page }) => {
    await page.goto('/')
    await page.waitForLoadState('networkidle')
    await applyTheme(page, 'light')
    await expect(page).toHaveScreenshot('portal-light.png', { maxDiffPixelRatio: 0.05 })
  })

  test('dark 模式全页', async ({ page }) => {
    await page.goto('/')
    await page.waitForLoadState('networkidle')
    await applyTheme(page, 'dark')
    await expect(page.locator('html')).toHaveAttribute('data-theme', 'dark')
    await expect(page).toHaveScreenshot('portal-dark.png', { maxDiffPixelRatio: 0.05 })
  })

  test('hero 区 light', async ({ page }) => {
    await page.goto('/')
    await page.waitForLoadState('networkidle')
    await applyTheme(page, 'light')
    await expect(page.locator('.home-hero').first()).toHaveScreenshot('hero-light.png', { maxDiffPixelRatio: 0.05 })
  })

  test('hero 区 dark', async ({ page }) => {
    await page.goto('/')
    await page.waitForLoadState('networkidle')
    await applyTheme(page, 'dark')
    await expect(page.locator('html')).toHaveAttribute('data-theme', 'dark')
    await expect(page.locator('.home-hero').first()).toHaveScreenshot('hero-dark.png', { maxDiffPixelRatio: 0.05 })
  })
})

test.describe('视觉回归 — 代码块 (固定深色, 不随主题翻转)', () => {
  for (const theme of ['light', 'dark'] as const) {
    test(`${theme} 下代码块仍为深色背景`, async ({ page }) => {
      await page.goto('/')
      await page.waitForLoadState('networkidle')
      await applyTheme(page, theme)
      if (theme === 'dark') {
        await expect(page.locator('html')).toHaveAttribute('data-theme', 'dark')
      }
      const codePre = page.locator('.code-pre').first()
      await expect(codePre).toBeVisible()
      const bg = await codePre.evaluate((el) => getComputedStyle(el).backgroundColor)
      // #1f2430 = rgb(31,36,48)
      expect(bg).toMatch(/rgb\(31,\s*36,\s*48\)/i)
      await expect(codePre).toHaveScreenshot(`code-block-${theme}.png`, { maxDiffPixelRatio: 0.02 })
    })
  }
})

test.describe('视觉回归 — 登录页', () => {
  test('tenant 登录页 light', async ({ page }) => {
    await page.goto('/benefit/app/tenant/login')
    await page.waitForLoadState('networkidle')
    await applyTheme(page, 'light')
    await expect(page).toHaveScreenshot('tenant-login-light.png', { maxDiffPixelRatio: 0.05 })
  })

  test('tenant 登录页 dark', async ({ page }) => {
    await page.goto('/benefit/app/tenant/login')
    await page.waitForLoadState('networkidle')
    await applyTheme(page, 'dark')
    await expect(page.locator('html')).toHaveAttribute('data-theme', 'dark')
    await expect(page).toHaveScreenshot('tenant-login-dark.png', { maxDiffPixelRatio: 0.05 })
  })

  test('platform 登录页 light', async ({ page }) => {
    await page.goto('/benefit/app/platform/login')
    await page.waitForLoadState('networkidle')
    await applyTheme(page, 'light')
    await expect(page).toHaveScreenshot('platform-login-light.png', { maxDiffPixelRatio: 0.05 })
  })
})
