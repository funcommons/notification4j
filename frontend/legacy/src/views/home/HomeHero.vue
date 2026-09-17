<template>
  <section class="home-hero">
    <div class="home-hero__bg">
      <div class="home-hero__glow home-hero__glow--a" />
      <div class="home-hero__glow home-hero__glow--b" />
    </div>

    <div class="home-hero__content">
      <div class="home-hero__badge">
        <i class="ri-rocket-2-line" />
        <span>{{ t('home.hero-badge') }}</span>
      </div>

      <h1 class="home-hero__title">
        <span class="home-hero__title-main">{{ oemTitle }}</span>
        <span class="home-hero__title-tag">{{ t('home.hero-tagline') }}</span>
      </h1>

      <p class="home-hero__subtitle">{{ heroSubtitle }}</p>

      <div class="home-hero__ctas">
        <FcButton variant="primary" size="large" @click="goLogin('tenant')">
          <i class="ri-apps-2-line" />
          {{ t('home.hero-cta-tenant') }}
        </FcButton>
        <FcButton variant="secondary" size="large" @click="goLogin('platform')">
          <i class="ri-building-2-line" />
          {{ t('home.hero-cta-platform') }}
        </FcButton>
      </div>

      <div class="home-hero__meta">
        <span class="meta-item"><i class="ri-shield-check-line" /> {{ t('home.hero-meta-1') }}</span>
        <span class="meta-sep">·</span>
        <span class="meta-item"><i class="ri-code-s-slash-line" /> {{ t('home.hero-meta-2') }}</span>
        <span class="meta-sep">·</span>
        <span class="meta-item"><i class="ri-plug-line" /> {{ t('home.hero-meta-3') }}</span>
      </div>
    </div>
  </section>
</template>

<script setup lang="ts">
/**
 * HomeHero - 平台首页首屏。
 *
 * 视觉: 渐变背景 + 两个柔光斑 + 居中内容 (徽标 / 标题 / 标语 /
 *      双 CTA / 安全卖点), 跟 ProfileDemo 的 hero pattern 思路一致,
 *      但不带 cover 图, 改成纯 CSS 渐变 + 装饰圆。
 *
 * 文案: 标题 = OEM config.title, 副标题 = OEM config.subtitle (有) 否则走 i18n hero-subtitle。
 */
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRouter } from 'vue-router'
import { useOemStore } from '@/store/oem'
import { FcButton } from '@/components/sdk'

defineOptions({ name: 'HomeHero' })

const { t } = useI18n()
const router = useRouter()
const oem = useOemStore()

const oemTitle = computed(() => oem.config.title || oem.config.companyName || 'Benefit4j')
const heroSubtitle = computed(() => oem.config.subtitle || t('home.hero-subtitle'))

function goLogin(kind: 'tenant' | 'platform') {
  router.push(kind === 'tenant' ? '/benefit/app/tenant/login' : '/benefit/app/platform/login')
}
</script>

<style scoped lang="scss">
.home-hero {
  position: relative;
  overflow: hidden;
  padding: 96px 24px 80px;
  background:
    linear-gradient(135deg,
      color-mix(in srgb, var(--app-primary, #6366f1) 8%, var(--app-bg-page, #ffffff)) 0%,
      color-mix(in srgb, var(--app-primary, #6366f1) 2%, var(--app-bg-page, #ffffff)) 100%);
  border-bottom: 1px solid var(--app-separator, var(--el-border-color-extra-light));
}

.home-hero__bg {
  position: absolute;
  inset: 0;
  pointer-events: none;
  overflow: hidden;
}

.home-hero__glow {
  position: absolute;
  border-radius: 50%;
  filter: blur(80px);
  opacity: 0.45;
}
.home-hero__glow--a {
  top: -120px;
  right: -80px;
  width: 480px;
  height: 480px;
  background: color-mix(in srgb, var(--app-primary, #6366f1) 35%, transparent);
}
.home-hero__glow--b {
  bottom: -160px;
  left: -120px;
  width: 420px;
  height: 420px;
  background: color-mix(in srgb, var(--el-color-success, #67c23a) 30%, transparent);
}

.home-hero__content {
  position: relative;
  max-width: 880px;
  margin: 0 auto;
  text-align: center;
}

.home-hero__badge {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 6px 14px;
  margin-bottom: 24px;
  background: var(--app-bg-card, var(--el-bg-color));
  border: 1px solid var(--app-separator, var(--el-border-color-light));
  border-radius: 999px;
  font-size: 12px;
  font-weight: 500;
  color: var(--app-text-secondary);
  box-shadow: var(--app-shadow-sm, 0 1px 2px rgba(0,0,0,0.04));
  i { color: var(--app-primary); }
}

.home-hero__title {
  margin: 0 0 16px;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
  line-height: 1.2;
}
.home-hero__title-main {
  font-size: 48px;
  font-weight: 800;
  color: var(--app-text, var(--el-text-color-primary));
  letter-spacing: -0.5px;
  background: linear-gradient(135deg,
    var(--app-primary, #6366f1) 0%,
    color-mix(in srgb, var(--app-primary, #6366f1) 60%, var(--el-color-success, #67c23a)) 100%);
  -webkit-background-clip: text;
  background-clip: text;
  -webkit-text-fill-color: transparent;
  color: transparent;
}
.home-hero__title-tag {
  font-size: 22px;
  font-weight: 500;
  color: var(--app-text-secondary, var(--el-text-color-regular));
}

.home-hero__subtitle {
  margin: 0 auto 32px;
  max-width: 640px;
  font-size: 15px;
  line-height: 1.7;
  color: var(--app-text-secondary, var(--el-text-color-regular));
}

.home-hero__ctas {
  display: inline-flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 36px;
  flex-wrap: wrap;
  justify-content: center;
}

.home-hero__meta {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  font-size: 12px;
  color: var(--app-text-tertiary);
  flex-wrap: wrap;
  justify-content: center;
}
.meta-item {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  i { color: var(--app-primary); }
}
.meta-sep { opacity: 0.5; }

@media (max-width: 640px) {
  .home-hero { padding: 64px 16px 48px; }
  .home-hero__title-main { font-size: 32px; }
  .home-hero__title-tag { font-size: 16px; }
  .home-hero__subtitle { font-size: 14px; }
}
</style>
