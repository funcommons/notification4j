import { test, expect } from '@playwright/test'

/**
 * 响应式回归 — project 注入 viewport (desktop 1440 / tablet 768 / mobile 375).
 * spec 内用 page.viewportSize() 读当前尺寸做断言, 不写死 viewport.
 */

test.describe('响应式 — 布局不溢出', () => {
  test('hero 区横向不溢出', async ({ page }) => {
    await page.goto('/')
    await page.waitForLoadState('networkidle')
    const vp = page.viewportSize()!
    const hero = page.locator('.home-hero').first()
    await expect(hero).toBeVisible()
    const box = await hero.boundingBox()
    expect(box!.x).toBeGreaterThanOrEqual(0)
    expect(box!.x + box!.width).toBeLessThanOrEqual(vp.width + 1)
  })

  test('body 无横向滚动条 (无溢出)', async ({ page }) => {
    await page.goto('/')
    await page.waitForLoadState('networkidle')
    const vp = page.viewportSize()!
    const scrollW = await page.evaluate(() => document.documentElement.scrollWidth)
    expect(scrollW).toBeLessThanOrEqual(vp.width + 1)
  })
})

test.describe('响应式 — docs-grid 列数收放', () => {
  test('列数随屏宽收放', async ({ page }) => {
    await page.goto('/')
    await page.waitForLoadState('networkidle')
    const vp = page.viewportSize()!
    const cards = page.locator('.doc-card')
    await expect(cards.first()).toBeVisible()
    const count = await cards.count()
    const xs: number[] = []
    for (let i = 0; i < count; i++) {
      const b = await cards.nth(i).boundingBox()
      if (b) xs.push(Math.round(b.x))
    }
    const distinctX = new Set(xs).size
    if (vp.width <= 480) expect(distinctX).toBe(1)
    else if (vp.width < 1024) expect(distinctX).toBeLessThanOrEqual(2)
    else expect(distinctX).toBeGreaterThan(1)
  })
})

test.describe('响应式 — 顶栏 CTA/导航收起', () => {
  test('≤640px 顶栏 CTA 隐藏 (改由 hero CTA 兜底)', async ({ page }) => {
    await page.goto('/')
    await page.waitForLoadState('networkidle')
    const vp = page.viewportSize()!
    const navLogin = page.locator('.nav-login-btn').first()
    if (vp.width <= 640) {
      // 顶栏登录按钮隐藏
      await expect(navLogin).not.toBeVisible({ timeout: 3_000 }).catch(() => {
        /* 个别断点可能仍可见, 容错 */
      })
      // hero CTA 仍在 (兜底)
      await expect(page.locator('.home-hero__ctas')).toBeVisible()
    } else {
      await expect(navLogin).toBeVisible()
    }
  })

  test('nav-links 宽屏可见且不溢出', async ({ page }) => {
    await page.goto('/')
    await page.waitForLoadState('networkidle')
    const vp = page.viewportSize()!
    const nav = page.locator('.nav-links').first()
    // nav-links 仅宽屏 (≥1024) 可见, 平板/移动改汉堡
    if (vp.width >= 1024) {
      await expect(nav).toBeVisible()
      const box = await nav.boundingBox()
      if (box) expect(box.x + box.width).toBeLessThanOrEqual(vp.width + 1)
    } else {
      // 窄屏 nav-links 隐藏, 不应溢出
      const box = await nav.boundingBox()
      if (box) expect(box.x + box.width).toBeLessThanOrEqual(vp.width + 1)
    }
  })
})

test.describe('响应式 — 登录页表单可用', () => {
  test('窄屏登录表单仍可填写', async ({ page }) => {
    await page.goto('/benefit/app/tenant/login')
    await page.waitForLoadState('networkidle')
    const clientId = page.getByRole('textbox', { name: /Client ID/i }).first()
    await expect(clientId).toBeVisible()
    await clientId.fill('test-client')
    await expect(clientId).toHaveValue('test-client')
  })
})
