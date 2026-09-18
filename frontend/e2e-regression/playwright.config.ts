import { defineConfig } from '@playwright/test'

/**
 * notification4j 业务线验收回归（L1~L8）Playwright 配置。
 * 前置：bin/start-app.sh 已启动 fat jar(9200)，容器 nfy4j-e2e-pg/redis 已启动。
 * 证据：NFY_EVIDENCE_DIR 指定目录（默认 docs/test/report/local-run/screenshots）。
 */
export default defineConfig({
  testDir: '.',
  testMatch: /l\d-.*\.spec\.ts/,
  globalSetup: './global-setup.ts',
  workers: 1,                 // 共享测试实例，串行防数据串扰
  retries: 0,
  timeout: 60_000,
  reporter: [['list'], ['json', { outputFile: '.runs/e2e-results.json' }]],
  use: {
    baseURL: 'http://localhost:9200',
    headless: true,
    screenshot: 'off',
    trace: 'off',
    actionTimeout: 15_000,
    navigationTimeout: 20_000,
  },
})
