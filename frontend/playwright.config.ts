import { defineConfig, devices } from '@playwright/test'

/**
 * 两套 e2e:
 *   - smoke        : 关键写路径冒烟, 串行, retries 0, 快。行为与重组前一致。
 *   - regression-*: 视觉/响应式/交互/失败态回归, 多 viewport, retries 1, trace/video 可回放。
 */
export default defineConfig({
  testDir: './e2e',
  fullyParallel: false,
  retries: 0,
  workers: 1,
  reporter: [['list']],
  use: {
    baseURL: 'http://localhost:9203',
    trace: 'off',
    screenshot: 'only-on-failure',
    video: 'off',
    actionTimeout: 10_000,
    navigationTimeout: 20_000,
  },
  timeout: 60_000,
  expect: { timeout: 8_000 },

  projects: [
    // 冒烟: 关键写路径, 行为零改动 (retries 0 / trace off 保持原样)
    {
      name: 'smoke',
      testDir: './e2e/smoke',
      testMatch: /.*\.spec\.ts/,
      retries: 0,
      use: {
        ...devices['Desktop Chrome'],
        viewport: { width: 1440, height: 900 },
        trace: 'off',
        video: 'off',
      },
    },

    // 回归: 桌面 viewport (跑全部回归 spec: 视觉/响应式/交互/失败态)
    {
      name: 'regression-desktop',
      testDir: './e2e/regression',
      testMatch: /.*\.spec\.ts/,
      retries: 1,
      use: {
        ...devices['Desktop Chrome'],
        viewport: { width: 1440, height: 900 },
        trace: 'on-first-retry',
        video: 'retain-on-failure',
      },
    },

    // 回归: 平板 viewport (仅响应式, 其它用例桌面跑一次即可)
    {
      name: 'regression-tablet',
      testDir: './e2e/regression',
      testMatch: /responsive\.spec\.ts/,
      retries: 1,
      use: {
        ...devices['Desktop Chrome'],
        viewport: { width: 768, height: 1024 },
        trace: 'on-first-retry',
        video: 'retain-on-failure',
      },
    },

    // 回归: 移动 viewport (仅响应式, 顶栏 ≤640px 隐藏不适合跑交互)
    {
      name: 'regression-mobile',
      testDir: './e2e/regression',
      testMatch: /responsive\.spec\.ts/,
      retries: 1,
      use: {
        ...devices['Desktop Chrome'],
        viewport: { width: 375, height: 812 },
        trace: 'on-first-retry',
        video: 'retain-on-failure',
      },
    },
  ],
})
