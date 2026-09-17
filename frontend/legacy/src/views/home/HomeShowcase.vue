<template>
  <section id="home-showcase" class="home-section">
    <div class="home-section__inner">
      <FcSectionHeader
        :title="t('home.showcase-title')"
        :subtitle="t('home.showcase-subtitle')"
      />

      <div class="showcase-grid">
        <FcSectionCard
          v-for="item in showcases"
          :key="item.id"
          class="showcase-card"
          padding="none"
          hover
        >
          <div class="showcase-card__bar" :style="{ background: item.color }" />
          <div class="showcase-card__body">
            <div class="showcase-card__head">
              <div class="showcase-card__logo" :style="{ background: item.color }">
                {{ item.logoText }}
              </div>
              <div class="showcase-card__head-text">
                <h3 class="showcase-card__name">{{ item.name }}</h3>
                <p class="showcase-card__industry">{{ t(`home.showcase.industries.${item.industry}`) }}</p>
              </div>
            </div>

            <div class="showcase-card__plan">
              <span class="showcase-card__plan-name">{{ item.planName }}</span>
              <span class="showcase-card__plan-price">{{ item.priceLabel }}</span>
            </div>

            <ul class="showcase-card__benefits">
              <li v-for="(_b, idx) in item.benefits" :key="idx" class="showcase-card__benefit">
                <i class="ri-check-line" />
                <span>{{ t(`home.showcase.${item.id}.b${idx + 1}`) }}</span>
              </li>
            </ul>

            <div class="showcase-card__scale">
              <i class="ri-group-line" />
              <span>{{ item.scaleLabel }}</span>
            </div>
          </div>
        </FcSectionCard>
      </div>

      <p class="showcase-footnote">
        <i class="ri-information-line" />
        {{ t('home.showcase-footnote') }}
      </p>
    </div>
  </section>
</template>

<script setup lang="ts">
/**
 * HomeShowcase - 平台首页「真实大厂权益案例」版块。
 *
 * 6 张卡片, 覆盖 6 个不同垂直行业 (视频 / 电商 / 音乐 / 云存储 / 知识 / 企业 SaaS),
 * 让用户秒懂 benefit4j 能实现什么样的权益中台能力。
 *
 * 数据基于 2025 年公开数据 (会员数 / 付费用户), 仅供参考。
 * 卡片内容通过 i18n 渲染, 中英双语切换无遗漏。
 * Logo 用品牌色 + 首字母字标, 避免版权风险。
 */
import { useI18n } from 'vue-i18n'
import { FcSectionHeader, FcSectionCard } from '@/components/sdk'

defineOptions({ name: 'HomeShowcase' })

const { t } = useI18n()

interface Showcase {
  id: string
  name: string
  logoText: string
  industry: 'video' | 'ecom' | 'music' | 'storage' | 'knowledge' | 'saas'
  color: string
  planName: string
  priceLabel: string
  benefits: ReadonlyArray<never>  // 占位, 实际文案走 i18n
  scaleLabel: string
}

const showcases: ReadonlyArray<Showcase> = [
  {
    id: 'tx-video',
    name: '腾讯视频',
    logoText: 'TX',
    industry: 'video',
    color: 'linear-gradient(135deg, #ff7a45 0%, #ff4d4f 100%)',
    planName: 'VIP 连续包年',
    priceLabel: '¥253 / 年',
    benefits: [],
    scaleLabel: '1.2 亿+ 付费会员',
  },
  {
    id: 'alibaba-88vip',
    name: '阿里 88VIP',
    logoText: '88',
    industry: 'ecom',
    color: 'linear-gradient(135deg, #ff6a00 0%, #ff9d00 100%)',
    planName: '88VIP 年卡',
    priceLabel: '¥888 / 年',
    benefits: [],
    scaleLabel: '5000 万+ 用户',
  },
  {
    id: 'netease-music',
    name: '网易云音乐',
    logoText: 'NE',
    industry: 'music',
    color: 'linear-gradient(135deg, #c0392b 0%, #e74c3c 100%)',
    planName: '黑胶 VIP',
    priceLabel: '¥15 / 月',
    benefits: [],
    scaleLabel: '4500 万+ 黑胶 VIP',
  },
  {
    id: 'alipan',
    name: '阿里云盘',
    logoText: 'AP',
    industry: 'storage',
    color: 'linear-gradient(135deg, #4a8cff 0%, #2c5fe0 100%)',
    planName: '超级会员',
    priceLabel: '¥218 / 年',
    benefits: [],
    scaleLabel: '2 亿+ 注册用户',
  },
  {
    id: 'zhihu-xian',
    name: '知乎',
    logoText: 'ZH',
    industry: 'knowledge',
    color: 'linear-gradient(135deg, #056de8 0%, #0084ff 100%)',
    planName: '盐选会员',
    priceLabel: '¥198 / 年',
    benefits: [],
    scaleLabel: '1 亿+ 月活 / 1500 万+ 盐选',
  },
  {
    id: 'feishu',
    name: '飞书',
    logoText: 'FS',
    industry: 'saas',
    color: 'linear-gradient(135deg, #3370ff 0%, #00d6b9 100%)',
    planName: '旗舰版',
    priceLabel: '¥198 / 人·年',
    benefits: [],
    scaleLabel: '600 万+ 企业组织',
  },
]
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

.showcase-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 16px;
  margin-top: 32px;
}

.showcase-card {
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.showcase-card__bar {
  height: 4px;
  width: 100%;
}

.showcase-card__body {
  padding: 20px;
  display: flex;
  flex-direction: column;
  gap: 14px;
  flex: 1;
}

.showcase-card__head {
  display: flex;
  align-items: center;
  gap: 12px;
}

.showcase-card__logo {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 44px;
  height: 44px;
  border-radius: 10px;
  font-size: 16px;
  font-weight: 800;
  color: #fff;
  letter-spacing: 0.5px;
  flex-shrink: 0;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.08);
}

.showcase-card__head-text {
  min-width: 0;
  flex: 1;
}

.showcase-card__name {
  margin: 0;
  font-size: 15px;
  font-weight: 700;
  color: var(--app-text, var(--el-text-color-primary));
}

.showcase-card__industry {
  margin: 2px 0 0;
  font-size: 11px;
  color: var(--app-text-tertiary);
  letter-spacing: 0.4px;
}

.showcase-card__plan {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 8px;
  padding: 8px 12px;
  background: var(--app-bg-muted, var(--el-fill-color-blank));
  border-radius: 8px;
}

.showcase-card__plan-name {
  font-size: 12px;
  font-weight: 500;
  color: var(--app-text-secondary);
}

.showcase-card__plan-price {
  font-size: 14px;
  font-weight: 700;
  font-family: var(--el-font-family-mono, ui-monospace, SFMono-Regular, Menlo, monospace);
  color: var(--app-primary);
}

.showcase-card__benefits {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 6px;
  flex: 1;
}

.showcase-card__benefit {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  line-height: 1.5;
  color: var(--app-text-secondary, var(--el-text-color-regular));

  i {
    flex-shrink: 0;
    width: 14px;
    height: 14px;
    border-radius: 50%;
    background: color-mix(in srgb, var(--el-color-success) 14%, transparent);
    color: var(--el-color-success);
    font-size: 10px;
    display: inline-flex;
    align-items: center;
    justify-content: center;
  }
}

.showcase-card__scale {
  display: flex;
  align-items: center;
  gap: 6px;
  padding-top: 12px;
  border-top: 1px dashed var(--app-separator, var(--el-border-color-extra-light));
  font-size: 11px;
  font-weight: 500;
  color: var(--app-text-tertiary);

  i {
    color: var(--app-primary);
    font-size: 13px;
  }
}

.showcase-footnote {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  margin: 32px 0 0;
  font-size: 11px;
  color: var(--app-text-tertiary);
  text-align: center;

  i {
    font-size: 13px;
    color: var(--app-text-tertiary);
  }
}

@media (max-width: 1024px) {
  .showcase-grid { grid-template-columns: repeat(2, 1fr); }
}
@media (max-width: 640px) {
  .home-section { padding: 56px 16px; }
  .showcase-grid { grid-template-columns: 1fr; }
}
</style>
