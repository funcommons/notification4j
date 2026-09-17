<template>
  <FcHeader>
    <template #brand>
      <a class="nav-brand" href="#" @click.prevent="scrollToTop">
        <img class="nav-brand-logo" :src="oemLogo" :alt="oemTitle" />
        <span class="nav-brand-text">{{ oemTitle }}</span>
      </a>
    </template>

    <template #actions>
      <nav class="nav-links">
        <a
          v-for="item in navItems"
          :key="item.id"
          class="nav-link"
          href="#"
          @click.prevent="scrollTo(item.id)"
        >
          {{ t(`home.nav.${item.id}`) }}
        </a>
      </nav>
    </template>

    <template #user>
      <FcThemeSwitcher variant="popover" />
      <div class="nav-divider" />
      <div class="nav-locale">
        <a
          v-for="lang in langs"
          :key="lang"
          class="nav-locale-item"
          :class="{ active: currentLocale === lang }"
          href="#"
          @click.prevent="switchLocale(lang)"
        >{{ t(`langSwitch.${lang === 'zh-CN' ? 'zh' : 'en'}`) }}</a>
      </div>
      <div class="nav-divider" />
      <FcButton class="nav-login-btn" variant="primary" size="small" @click="goLogin('tenant')">
        <i class="ri-apps-2-line" /> {{ t('home.nav.login-tenant') }}
      </FcButton>
      <FcButton class="nav-login-btn" variant="secondary" size="small" @click="goLogin('platform')">
        <i class="ri-building-2-line" /> {{ t('home.nav.login-platform') }}
      </FcButton>
    </template>
  </FcHeader>
</template>

<script setup lang="ts">
/**
 * HomeTopNav - 平台首页吸顶导航。
 *
 * 复用 FcHeader 的 4 slot:
 *   - brand: OEM Logo + 项目名 (点击回到顶)
 *   - actions: 锚点链接 (特性 / 架构 / 文档 / 接入)
 *   - user: 主题切换 (复用 FcThemeSwitcher popover) + 语言切换 + 两个登录 CTA
 *
 * 锚点跳转用原生 scrollIntoView, 不依赖路由, 适合单页滚动。
 */
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRouter } from 'vue-router'
import { useOemStore } from '@/store/oem'
import { usePreferenceStore } from '@/store/preference'
import { FcHeader, FcButton, FcThemeSwitcher } from '@/components/sdk'
import i18n from '@/locales'

defineOptions({ name: 'HomeTopNav' })

const { t } = useI18n()
const router = useRouter()
const oem = useOemStore()
const preference = usePreferenceStore()

const oemLogo = computed(() => oem.config.logoUrl || '/logo.svg')
const oemTitle = computed(() => oem.config.title || oem.config.companyName || 'Benefit4j')

const navItems = [
  { id: 'features' },
  { id: 'architecture' },
  { id: 'tech-stack' },
  { id: 'docs' },
]

const langs: Array<'zh-CN' | 'en-US'> = ['zh-CN', 'en-US']
const currentLocale = computed(() => preference.locale)

function switchLocale(next: 'zh-CN' | 'en-US') {
  preference.setLocale(next)
  i18n.global.locale.value = next
}

function scrollToTop() {
  window.scrollTo({ top: 0, behavior: 'smooth' })
}

function scrollTo(id: string) {
  const el = document.getElementById(`home-${id}`)
  if (el) el.scrollIntoView({ behavior: 'smooth', block: 'start' })
}

function goLogin(kind: 'tenant' | 'platform') {
  router.push(kind === 'tenant' ? '/benefit/app/tenant/login' : '/benefit/app/platform/login')
}
</script>

<style scoped lang="scss">
.nav-brand {
  display: inline-flex;
  align-items: center;
  gap: 10px;
  text-decoration: none;
  color: inherit;
  cursor: pointer;
}
.nav-brand-logo {
  width: 28px;
  height: 28px;
  object-fit: contain;
  border-radius: 6px;
}
.nav-brand-text {
  font-size: 16px;
  font-weight: 700;
  color: var(--app-text, var(--el-text-color-primary));
  letter-spacing: 0.4px;
}

.nav-links {
  display: inline-flex;
  align-items: center;
  gap: 4px;
}
.nav-link {
  padding: 8px 14px;
  font-size: 13px;
  font-weight: 500;
  color: var(--app-text-secondary, var(--el-text-color-regular));
  border-radius: 8px;
  text-decoration: none;
  transition: background 0.15s, color 0.15s;
  &:hover {
    background: var(--app-bg-muted, var(--el-fill-color-light));
    color: var(--app-primary, var(--el-color-primary));
  }
}

.nav-divider {
  width: 1px;
  height: 20px;
  background: var(--app-separator, var(--el-border-color-light));
}

.nav-locale {
  display: inline-flex;
  align-items: center;
  gap: 2px;
  padding: 2px;
  border: 1px solid var(--app-separator, var(--el-border-color-light));
  border-radius: 8px;
}
.nav-locale-item {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-width: 28px;
  height: 24px;
  padding: 0 8px;
  font-size: 12px;
  font-weight: 600;
  color: var(--app-text-secondary);
  border-radius: 6px;
  text-decoration: none;
  cursor: pointer;
  transition: background 0.15s, color 0.15s;
  &:hover { color: var(--app-primary); }
  &.active {
    background: var(--app-primary, var(--el-color-primary));
    color: var(--app-on-primary, #fff);
  }
}

@media (max-width: 960px) {
  .nav-links { display: none; }
}
@media (max-width: 640px) {
  .nav-divider, .nav-locale { display: none; }
  // 顶栏右侧两个登录按钮在 640px 以下隐藏, 引导用户用 hero 区 CTA (避免顶栏溢出).
  .nav-login-btn { display: none; }
}
@media (max-width: 480px) {
  // 更窄屏: 顶栏所有登录相关元素完全隐藏, hero 双 CTA 兜底.
  .nav-login-btn { display: none; }
  .nav-divider { display: none; }
}
</style>
