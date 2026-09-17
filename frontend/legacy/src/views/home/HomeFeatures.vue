<template>
  <section id="home-features" class="home-section">
    <div class="home-section__inner">
      <FcSectionHeader
        :title="t('home.features-title')"
        :subtitle="t('home.features-subtitle')"
      />

      <div class="feature-grid">
        <FcSectionCard
          v-for="feat in features"
          :key="feat.id"
          class="feature-card"
          padding="md"
          hover
        >
          <div class="feature-icon" :class="`feature-icon--${feat.tone}`">
            <i :class="feat.icon" />
          </div>
          <h3 class="feature-title">{{ t(`home.features.${feat.id}.title`) }}</h3>
          <p class="feature-desc">{{ t(`home.features.${feat.id}.desc`) }}</p>
          <div class="feature-tags">
            <FcTag
              v-for="tag in feat.tags"
              :key="tag"
              size="sm"
              :color="toTagColor(feat.tone)"
            >{{ tag }}</FcTag>
          </div>
        </FcSectionCard>
      </div>
    </div>
  </section>
</template>

<script setup lang="ts">
/**
 * HomeFeatures - 平台首页「特性」版块。
 *
 * 6 张卡片, 走 dev/index.vue 的 dev-grid 模式 (auto-fill / minmax 280px).
 * 每张卡片: 彩色图标 + 标题 + 描述 + 标签. 颜色 tone 走 primary/success/
 *      warning/danger/info 之一, 与 feature 的语义相关.
 */
import { useI18n } from 'vue-i18n'
import { FcSectionHeader, FcSectionCard, FcTag } from '@/components/sdk'

defineOptions({ name: 'HomeFeatures' })

const { t } = useI18n()

// FcTag color 无 'info' (TagColor 联合), CSS class 需保留 info 样式 → 映射 info→gray
type TagColor = 'primary' | 'gray' | 'success' | 'warning' | 'danger' | 'brand'
const toTagColor = (tone: string): TagColor => (tone === 'info' ? 'gray' : tone) as TagColor

const features = [
  { id: 'subscribe',  icon: 'ri-vip-crown-2-line', tone: 'primary',  tags: ['订阅', '自动续费', '到期'] },
  { id: 'metering',   icon: 'ri-flashlight-line',  tone: 'warning',  tags: ['预扣', '退减', '补偿'] },
  { id: 'template',   icon: 'ri-stack-line',      tone: 'success',  tags: ['克隆', '复用', '审批'] },
  { id: 'embed',      icon: 'ri-window-2-line',   tone: 'info',     tags: ['iframe', 'postMessage', 'OAuth'] },
  { id: 'multi-tenant', icon: 'ri-group-2-line',  tone: 'primary',  tags: ['多租户', '隔离', '审计'] },
  { id: 'audit',      icon: 'ri-file-shield-2-line', tone: 'danger',  tags: ['审计', '操作追溯', '合规'] },
] as const
</script>

<style scoped lang="scss">
.home-section {
  padding: 80px 24px;
  background: var(--app-bg-page, var(--el-bg-color));
}
.home-section__inner {
  max-width: 1200px;
  margin: 0 auto;
}

.feature-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(300px, 1fr));
  gap: 16px;
  margin-top: 32px;
}

.feature-card {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.feature-icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 44px;
  height: 44px;
  border-radius: 12px;
  font-size: 22px;
  flex-shrink: 0;
}
.feature-icon--primary { background: color-mix(in srgb, var(--el-color-primary, #6366f1) 12%, transparent); color: var(--el-color-primary); }
.feature-icon--success { background: color-mix(in srgb, var(--el-color-success, #67c23a) 12%, transparent); color: var(--el-color-success); }
.feature-icon--warning { background: color-mix(in srgb, var(--el-color-warning, #e6a23c) 12%, transparent); color: var(--el-color-warning); }
.feature-icon--danger  { background: color-mix(in srgb, var(--el-color-danger, #f56c6c) 12%, transparent);  color: var(--el-color-danger); }
.feature-icon--info    { background: color-mix(in srgb, var(--el-color-info, #909399) 12%, transparent);    color: var(--el-color-info); }

.feature-title {
  margin: 0;
  font-size: 16px;
  font-weight: 600;
  color: var(--app-text, var(--el-text-color-primary));
}

.feature-desc {
  margin: 0;
  font-size: 13px;
  line-height: 1.7;
  color: var(--app-text-secondary, var(--el-text-color-regular));
}

.feature-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: 4px;
}

@media (max-width: 640px) {
  .home-section { padding: 56px 16px; }
  .feature-grid { grid-template-columns: 1fr; }
}
</style>
