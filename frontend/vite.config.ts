import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { resolve } from 'path'
import AutoImport from 'unplugin-auto-import/vite'
import pkg from './package.json'

// https://vite.dev/config/
export default defineConfig({
  // 部署 base = /nfy/：应用路由空间本就是 /nfy/tenant/**（router/index.ts），宿主域同源反代
  // （mmagix /nfy 段 / nginx /nfy/ → 本服务）路径原样透传不 rewrite——产物资产引用必须带
  // /nfy 前缀，否则嵌入宿主域时 index.html 的绝对 /assets/* 解析到宿主源 404（iframe 白屏）。
  // 服务端承接见 NfyStaticPageConfig（/nfy/assets/** 等映射与 /assets/** 双轨并存）。
  base: '/nfy/',
  plugins: [
    vue({
      template: {
        compilerOptions: {
          // ALTCHA 是 web component (npm altcha), Vue 别把它当 Vue 组件
          isCustomElement: (tag) => tag === 'altcha-widget',
        },
      },
    }),
    AutoImport({
      imports: ['vue', 'pinia', 'vue-i18n'],
      dts: 'auto-imports.d.ts',
      eslintrc: { enabled: false },
    }),
  ],
  define: {
    __APP_VERSION__: JSON.stringify(pkg.version || '0.0.0'),
    __BUILD_TIME__: JSON.stringify(new Date().toISOString()),
  },
  resolve: {
    alias: {
      '@': resolve(__dirname, 'src')
    }
  },
  css: {
    preprocessorOptions: {
      scss: {
        additionalData: `@use "@/styles/variables.scss" as *;`
      }
    }
  },
  server: {
    port: 9203,
    open: true,
    // 允许生产域名 Host 头穿透 (OEM 多租户识别 + Nginx 反代模拟)
    allowedHosts: [
      'localhost',
      '127.0.0.1',
      'aigc-beta.lhs11.com',
      'aidoit-beta.lhs11.com',
      '.lhs11.com'
    ],
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true
      },
      // 图片代理 (后端 ImageProxyController): 解决跨域 canvas 污染
      '/image_proxy': {
        target: 'http://localhost:8080',
        changeOrigin: true
      },
      // Benefit4j 后端 API (只代理 /benefit/api/，不代理前端路由 /benefit/platform/app/login 等)
      '/benefit/api/': {
        target: 'http://localhost:9200',
        changeOrigin: true
      }
    }
  },
  build: {
    // 拆分大依赖到独立 chunk, 避免 500kB 警告
    rollupOptions: {
      output: {
        manualChunks: {
          // Vue 运行时 + 路由 + 状态管理
          'vue-vendor': ['vue', 'vue-router', 'pinia', 'vue-i18n'],
          // UI 库
          'element-plus': ['element-plus', '@element-plus/icons-vue'],
          // fabric 画布 (体积大, 单独拆)
          'fabric': ['fabric'],
          // three.js (导演台用,体积大单独拆)
          'three': ['three'],
        },
      },
    },
    // 单 chunk 报警阈值 (kB); element-plus 完整包约 1MB, 业务依赖都在此阈值之下
    chunkSizeWarningLimit: 1100,
  }
})
