<template>
  <footer class="home-footer">
    <div class="home-footer__inner">
      <div class="home-footer__col home-footer__col--brand">
        <div class="footer-brand">
          <img class="footer-brand-logo" :src="oemLogo" :alt="oemTitle" />
          <span class="footer-brand-text">{{ oemTitle }}</span>
        </div>
        <p class="footer-tagline">{{ oemSubtitle || t('home.footer-tagline') }}</p>
      </div>

      <div class="home-footer__col">
        <h4 class="footer-col-title">{{ t('home.footer-col-product') }}</h4>
        <ul class="footer-links">
          <li><a href="#" @click.prevent="scrollTo('features')">{{ t('home.nav.features') }}</a></li>
          <li><a href="#" @click.prevent="scrollTo('architecture')">{{ t('home.nav.architecture') }}</a></li>
          <li><a href="#" @click.prevent="scrollTo('tech-stack')">{{ t('home.nav.tech-stack') }}</a></li>
          <li><a href="#" @click.prevent="scrollTo('docs')">{{ t('home.nav.docs') }}</a></li>
        </ul>
      </div>

      <div class="home-footer__col">
        <h4 class="footer-col-title">{{ t('home.footer-col-login') }}</h4>
        <ul class="footer-links">
          <li><a href="#" @click.prevent="goLogin('tenant')">{{ t('home.nav.login-tenant') }}</a></li>
          <li><a href="#" @click.prevent="goLogin('platform')">{{ t('home.nav.login-platform') }}</a></li>
        </ul>
      </div>

      <div class="home-footer__col">
        <h4 class="footer-col-title">{{ t('home.footer-col-community') }}</h4>
        <ul class="footer-links">
          <li>
            <a :href="githubUrl" target="_blank" rel="noopener">
              <i class="ri-github-line" />
              {{ t('home.footer-github') }}
            </a>
          </li>
          <li>
            <a :href="supportUrl" target="_blank" rel="noopener">
              <i class="ri-customer-service-2-line" />
              {{ t('home.footer-support') }}
            </a>
          </li>
        </ul>
      </div>
    </div>

    <div class="home-footer__bottom">
      <span class="footer-copy">© {{ year }} {{ oemTitle }} · {{ oemFooterText }}</span>
    </div>
  </footer>
</template>

<script setup lang="ts">
/**
 * HomeFooter - 平台首页页脚。
 *
 * 4 列布局: 品牌 / 产品 / 登录 / 社区. 底部一行版权.
 * 整页颜色走 --app-* token, 跟随主题。
 */
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRouter } from 'vue-router'
import { useOemStore } from '@/store/oem'

defineOptions({ name: 'HomeFooter' })

const { t } = useI18n()
const router = useRouter()
const oem = useOemStore()

const oemLogo = computed(() => oem.config.logoUrl || '/logo.svg')
const oemTitle = computed(() => oem.config.title || oem.config.companyName || 'Benefit4j')
const oemSubtitle = computed(() => oem.config.subtitle || '')
const oemFooterText = computed(() => oem.config.footerText || t('home.footer-default'))

const githubUrl = 'https://github.com/funcommons/benefit4j'
const supportUrl = computed(() => oem.config.supportUrl || '#')
const year = new Date().getFullYear()

function scrollTo(id: string) {
  const el = document.getElementById(`home-${id}`)
  if (el) el.scrollIntoView({ behavior: 'smooth', block: 'start' })
}

function goLogin(kind: 'tenant' | 'platform') {
  router.push(kind === 'tenant' ? '/benefit/app/tenant/login' : '/benefit/app/platform/login')
}
</script>

<style scoped lang="scss">
.home-footer {
  background: var(--app-bg-card, var(--el-bg-color));
  border-top: 1px solid var(--app-separator, var(--el-border-color-extra-light));
  padding: 56px 24px 24px;
}
.home-footer__inner {
  max-width: 1200px;
  margin: 0 auto;
  display: grid;
  grid-template-columns: 1.4fr 1fr 1fr 1fr;
  gap: 32px;
}

.home-footer__col {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.footer-brand {
  display: inline-flex;
  align-items: center;
  gap: 10px;
}
.footer-brand-logo {
  width: 28px;
  height: 28px;
  object-fit: contain;
  border-radius: 6px;
}
.footer-brand-text {
  font-size: 16px;
  font-weight: 700;
  color: var(--app-text, var(--el-text-color-primary));
}

.footer-tagline {
  margin: 0;
  font-size: 12px;
  line-height: 1.6;
  color: var(--app-text-secondary);
  max-width: 280px;
}

.footer-col-title {
  margin: 0;
  font-size: 13px;
  font-weight: 600;
  color: var(--app-text, var(--el-text-color-primary));
  letter-spacing: 0.4px;
}

.footer-links {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 8px;

  a {
    display: inline-flex;
    align-items: center;
    gap: 4px;
    font-size: 12px;
    color: var(--app-text-secondary);
    text-decoration: none;
    transition: color 0.15s;
    &:hover { color: var(--app-primary); }
    i { font-size: 14px; }
  }
}

.home-footer__bottom {
  max-width: 1200px;
  margin: 32px auto 0;
  padding-top: 24px;
  border-top: 1px solid var(--app-separator, var(--el-border-color-extra-light));
  text-align: center;
}
.footer-copy {
  font-size: 11px;
  color: var(--app-text-tertiary);
}

@media (max-width: 768px) {
  .home-footer__inner { grid-template-columns: 1fr 1fr; gap: 24px; }
  .home-footer__col--brand { grid-column: 1 / -1; }
}
@media (max-width: 480px) {
  .home-footer__inner { grid-template-columns: 1fr; }
}
</style>
