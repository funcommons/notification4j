<template>
  <section id="home-docs" class="home-section home-section--alt">
    <div class="home-section__inner">
      <FcSectionHeader
        :title="t('home.docs-title')"
        :subtitle="t('home.docs-subtitle')"
      />

      <div class="docs-grid">
        <FcSectionCard
          v-for="doc in docs"
          :key="doc.id"
          class="doc-card"
          padding="md"
          hover
        >
          <div class="doc-icon"><i :class="doc.icon" /></div>
          <h3 class="doc-title">{{ t(`home.docs.${doc.id}.title`) }}</h3>
          <p class="doc-desc">{{ t(`home.docs.${doc.id}.desc`) }}</p>
          <div class="doc-foot">
            <span class="doc-auth">
              <i class="ri-lock-2-line" />
              {{ doc.requireAuth ? t('home.docs-need-auth') : t('home.docs-public') }}
            </span>
            <FcButton
              variant="primary"
              size="small"
              link
              @click="goDoc(doc)"
            >
              {{ t('home.docs-open') }}
              <i class="ri-arrow-right-s-line" />
            </FcButton>
          </div>
        </FcSectionCard>
      </div>
    </div>
  </section>
</template>

<script setup lang="ts">
/**
 * HomeDocs - 平台首页「文档」版块。
 *
 * 4 张卡片: 嵌入接入 (链到现有 EmbedDocs) / 模板复用 / 订阅管理 / 计量扣减.
 * 大多数需要登录后访问, 卡片底部小字 + 跳转按钮提醒。
 *
 * 嵌入接入是「推荐入口」, 配 special 样式 (★ 标记), 跟 EmbedDocs 内部一致。
 */
import { useI18n } from 'vue-i18n'
import { useRouter } from 'vue-router'
import { FcSectionHeader, FcSectionCard, FcButton } from '@/components/sdk'

defineOptions({ name: 'HomeDocs' })

const { t } = useI18n()
const router = useRouter()

const docs = [
  { id: 'embed',     icon: 'ri-window-2-line',         requireAuth: true,  path: '/benefit/platform/app/dev/embed-docs', star: true },
  { id: 'template',  icon: 'ri-stack-line',            requireAuth: true,  path: '/benefit/platform/app/templates/set' },
  { id: 'subscribe', icon: 'ri-vip-crown-2-line',      requireAuth: true,  path: '/benefit/platform/app/subscriptions' },
  { id: 'metering',  icon: 'ri-flashlight-line',       requireAuth: true,  path: '/benefit/platform/app/consumptions' },
]

function goDoc(doc: typeof docs[number]) {
  router.push(doc.path)
}
</script>

<style scoped lang="scss">
.home-section {
  padding: 80px 24px;
  background: var(--app-bg-page, var(--el-bg-color));
}
.home-section--alt {
  background: var(--app-bg-muted, var(--el-fill-color-blank));
}
.home-section__inner {
  max-width: 1200px;
  margin: 0 auto;
}

.docs-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(280px, 1fr));
  gap: 16px;
  margin-top: 32px;
}

.doc-card {
  display: flex;
  flex-direction: column;
  gap: 10px;
  position: relative;
}

.doc-icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 40px;
  height: 40px;
  border-radius: 10px;
  background: color-mix(in srgb, var(--app-primary, #6366f1) 12%, transparent);
  color: var(--app-primary);
  font-size: 20px;
}

.doc-title {
  margin: 0;
  font-size: 15px;
  font-weight: 600;
  color: var(--app-text, var(--el-text-color-primary));
}

.doc-desc {
  margin: 0;
  font-size: 13px;
  line-height: 1.7;
  color: var(--app-text-secondary);
  flex: 1;
}

.doc-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-top: 8px;
  padding-top: 12px;
  border-top: 1px solid var(--app-separator, var(--el-border-color-extra-light));
}

.doc-auth {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-size: 11px;
  color: var(--app-text-tertiary);
  i { font-size: 12px; }
}

@media (max-width: 640px) {
  .home-section { padding: 56px 16px; }
  .docs-grid { grid-template-columns: 1fr; }
}
</style>
