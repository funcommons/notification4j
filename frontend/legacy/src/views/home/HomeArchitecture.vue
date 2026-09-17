<template>
  <section id="home-architecture" class="home-section home-section--alt">
    <div class="home-section__inner">
      <FcSectionHeader
        :title="t('home.arch-title')"
        :subtitle="t('home.arch-subtitle')"
      />

      <!-- 4 层架构 -->
      <div class="arch-stack">
        <div
          v-for="(layer, idx) in layers"
          :key="layer.id"
          class="arch-layer"
          :class="`arch-layer--${layer.tone}`"
        >
          <div class="arch-layer__index">
            <span class="arch-layer__num">L{{ idx + 1 }}</span>
            <span class="arch-layer__name">{{ t(`home.arch.${layer.id}-title`) }}</span>
          </div>
          <div class="arch-layer__desc">{{ t(`home.arch.${layer.id}-desc`) }}</div>
          <div class="arch-layer__chips">
            <FcTag
              v-for="item in layer.items"
              :key="item"
              size="sm"
              :color="toTagColor(layer.tone)"
            >{{ item }}</FcTag>
          </div>
        </div>
      </div>

      <!-- 数据模型表 -->
      <h3 class="arch-block-title">{{ t('home.arch.tables-title') }}</h3>
      <div class="arch-tables">
        <div v-for="table in tables" :key="table.name" class="arch-table">
          <div class="arch-table__name">
            <i class="ri-table-2" />
            <code>{{ table.name }}</code>
          </div>
          <div class="arch-table__desc">{{ t(`home.arch.tables.${table.id}`) }}</div>
        </div>
      </div>
    </div>
  </section>
</template>

<script setup lang="ts">
/**
 * HomeArchitecture - 平台首页「架构 / 数据模型」版块。
 *
 * 上半: 4 层架构 (接入 / 业务 / 数据 / 基础设施), 走 CSS 阶梯式卡片
 *       (无外部 SVG, 纯 CSS 排版, 跟 mock 数据中真实表层一致).
 *
 * 下半: 11 张数据表 (来自 mock 真实 sql), 走 CSS grid 列表。
 *       表名用 <code> 渲染, 紧跟一句中文说明。
 */
import { useI18n } from 'vue-i18n'
import { FcSectionHeader, FcTag } from '@/components/sdk'

defineOptions({ name: 'HomeArchitecture' })

const { t } = useI18n()

// FcTag color 无 'info' (TagColor 联合), 但 CSS class 需保留 info 样式 → 映射 info→gray
type TagColor = 'primary' | 'gray' | 'success' | 'warning' | 'danger' | 'brand'
const toTagColor = (tone: string): TagColor => (tone === 'info' ? 'gray' : tone) as TagColor

const layers = [
  { id: 'access', tone: 'primary' as const, items: ['iframe 嵌入', 'REST API', 'Web SDK', 'postMessage'] },
  { id: 'biz',    tone: 'success' as const, items: ['租户', '权益项', '权益包', '订阅', '扣减', '退减', '补偿', '迁移'] },
  { id: 'data',   tone: 'warning' as const, items: ['ubma_*', 'ubmp_*', 'ubme_*', '审计日志', 'JSONB / GIN'] },
  { id: 'infra',  tone: 'info' as const,    items: ['Snowflake', 'OpenID', '多级缓存', 'Lua 限流', 'AES 加密', 'HMAC 签名'] },
]

const tables = [
  { id: 'application', name: 'ubma_application' },
  { id: 'item',        name: 'ubma_benefit_item' },
  { id: 'set',         name: 'ubma_benefit_set' },
  { id: 'ref',         name: 'ubma_benefit_ref' },
  { id: 'subscribe',   name: 'ubma_subscribe' },
  { id: 'subs-item',   name: 'ubma_subscribe_item' },
  { id: 'consume',     name: 'ubma_consume' },
  { id: 'refund',      name: 'ubma_refund' },
  { id: 'compensation',name: 'ubma_compensation' },
  { id: 'unsubscribe', name: 'ubma_unsubscribe' },
  { id: 'migration',   name: 'ubma_migration' },
]
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

.arch-stack {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 12px;
  margin-top: 32px;
}
.arch-layer {
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 20px;
  border-radius: var(--app-radius-lg, 16px);
  border: 1px solid var(--app-separator, var(--el-border-color-light));
  background: var(--app-bg-card, var(--el-bg-color));
  position: relative;
  overflow: hidden;

  &::before {
    content: '';
    position: absolute;
    top: 0;
    left: 0;
    width: 100%;
    height: 3px;
  }
  &--primary::before { background: var(--el-color-primary); }
  &--success::before { background: var(--el-color-success); }
  &--warning::before { background: var(--el-color-warning); }
  &--info::before    { background: var(--el-color-info); }
}

.arch-layer__index {
  display: flex;
  align-items: center;
  gap: 8px;
}
.arch-layer__num {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  border-radius: 8px;
  background: var(--app-bg-muted, var(--el-fill-color-light));
  font-size: 11px;
  font-weight: 700;
  color: var(--app-text-secondary);
  letter-spacing: 0.5px;
}
.arch-layer__name {
  font-size: 15px;
  font-weight: 600;
  color: var(--app-text, var(--el-text-color-primary));
}

.arch-layer__desc {
  font-size: 12px;
  line-height: 1.6;
  color: var(--app-text-secondary, var(--el-text-color-regular));
}

.arch-layer__chips {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
}

.arch-block-title {
  margin: 56px 0 16px;
  font-size: 18px;
  font-weight: 600;
  color: var(--app-text, var(--el-text-color-primary));
}

.arch-tables {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(280px, 1fr));
  gap: 10px;
}
.arch-table {
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding: 12px 16px;
  border-radius: var(--app-radius-md, 10px);
  background: var(--app-bg-card, var(--el-bg-color));
  border: 1px solid var(--app-separator, var(--el-border-color-extra-light));
  transition: border-color 0.15s;
  &:hover { border-color: var(--app-primary); }
}
.arch-table__name {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;
  font-weight: 600;
  color: var(--app-primary);
  code {
    font-family: var(--el-font-family-mono, ui-monospace, SFMono-Regular, Menlo, monospace);
    font-size: 13px;
  }
  i { color: var(--app-primary); }
}
.arch-table__desc {
  font-size: 12px;
  color: var(--app-text-secondary);
  line-height: 1.5;
}

@media (max-width: 960px) {
  .arch-stack { grid-template-columns: repeat(2, 1fr); }
}
@media (max-width: 640px) {
  .home-section { padding: 56px 16px; }
  .arch-stack { grid-template-columns: 1fr; }
  .arch-tables { grid-template-columns: 1fr; }
}
</style>
