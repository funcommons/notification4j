import { defineConfig } from 'vitest/config'
import { resolve } from 'path'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      '@': resolve(__dirname, 'src'),
    },
  },
  test: {
    environment: 'jsdom',
    globals: true,
    // 裁剪时丢失的 SDK 测试全局装配（i18n/pinia/ElementPlus/matchMedia/ResizeObserver/localStorage）
    setupFiles: ['src/components/sdk/__tests__/setup.ts'],
    // e2e/ 是 Playwright 用例(test:e2e 跑),vitest 误扫必挂 79 个 —— 显式排除
    // V1.0 收官: 旧用例已随迁 legacy/e2e/ (同属 Playwright, 同样排除, 防误扫)
    exclude: ['e2e/**', 'legacy/e2e/**', 'node_modules/**'],
  },
})