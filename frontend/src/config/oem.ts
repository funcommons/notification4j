/**
 * OEM 白标配置 — 前端静态配置文件。
 *
 * 历史上 OEM 配置从后端 `/api/v1/oem/config` 拉取 (按域名多租户匹配)。
 * 现改为前端配置文件直接内置默认值, 不再发起网络请求:
 *  - 启动零延迟、离线可用、无首屏闪烁
 *  - 如需按环境差异化, 可在此读取 import.meta.env 或构建期注入
 *
 * 使用方式:
 *   import { OEM_CONFIG, type OemConfig } from '@/config/oem'
 */

/** OEM 配置类型 (原 api/oem.ts 的 OemConfig, 接口取消后迁移至此) */
export interface OemConfig {
  logoUrl?: string
  logoDarkUrl?: string
  companyName?: string
  title?: string
  subtitle?: string
  brand?: 'ldx2' | 'mchuan' | 'apple' | 'google' | 'manyun' | 'acme' | string
  theme?: 'light' | 'dark'
  primaryColor?: string
  successColor?: string
  warningColor?: string
  dangerColor?: string
  locale?: 'zh-CN' | 'en-US' | string
  faviconUrl?: string
  footerText?: string
  supportUrl?: string
  loginBgUrl?: string
}

/** 默认 OEM 配置 (前端内置, 不依赖后端) */
export const OEM_CONFIG: OemConfig = {
  // logo 走构建 base 前缀拼路径 (P2-9 修复): vite base='/nfy/' 嵌入形态 → '/nfy/logo.svg'
  // (服务端 NfyStaticPageConfig /nfy/logo.svg 转发承接); dev base='/' → '/logo.svg' 根映射。
  // 原绝对 '/logo.svg' 在 /nfy/ base 下解析到宿主源根路径 → 404。
  logoUrl: `${import.meta.env.BASE_URL}logo.svg`,
  companyName: '权益中台',
  title: '权益中台',
  subtitle: '公共权益配置计量中台',
  brand: 'acme',
  theme: 'light',
  locale: 'zh-CN',
  footerText: '',
}
