<template>
  <div class="app-page platform-dashboard-page">
    <FcSectionHeader
      :title="t('benefit.platform-dashboard-title')"
      :subtitle="t('benefit.platform-dashboard-subtitle')"
    />

    <div class="kpi-grid" v-loading="loading">
      <div class="kpi-card kpi-blue">
        <div class="kpi-label">{{ t('benefit.active-subs') }}</div>
        <div class="kpi-value">{{ stats?.total_active_subscriptions ?? '-' }}</div>
        <div class="kpi-foot">{{ t('benefit.subscriptions-title') }}</div>
      </div>
      <div class="kpi-card kpi-violet">
        <div class="kpi-label">{{ t('benefit.quota-committed') }}</div>
        <div class="kpi-value">{{ stats?.total_quota_committed ?? '-' }}</div>
        <div class="kpi-foot">Committed</div>
      </div>
      <div class="kpi-card kpi-cyan">
        <div class="kpi-label">{{ t('benefit.total-consumed') }}</div>
        <div class="kpi-value">{{ stats?.total_consumed ?? '-' }}</div>
        <div class="kpi-foot">Consumed</div>
      </div>
      <div class="kpi-card kpi-amber">
        <div class="kpi-label">{{ t('benefit.total-liability') }}</div>
        <div class="kpi-value">{{ stats?.total_liability ?? '-' }}</div>
        <div class="kpi-foot">Frozen + Liability</div>
      </div>
    </div>

    <div class="dashboard-row">
      <FcSection class="dashboard-panel">
        <template #header>
          <div class="panel-header">
            <span>{{ t('benefit.dashboard-tenant-distribution') }}</span>
            <FcTag color="gray" size="sm">{{ apps.length }} {{ t('benefit.tenants') }}</FcTag>
          </div>
        </template>
        <div class="dist-list">
          <div class="dist-row">
            <span class="dist-label">
              <i class="ri-checkbox-circle-line dist-ico-active" />
              {{ t('benefit.dashboard-active-tenant') }}
            </span>
            <span class="dist-value">{{ activeCount }}</span>
          </div>
          <el-progress :percentage="activePct" :stroke-width="10" :show-text="false" status="success" />
          <div class="dist-row mt">
            <span class="dist-label">
              <i class="ri-pause-circle-line dist-ico-inactive" />
              {{ t('benefit.dashboard-inactive-tenant') }}
            </span>
            <span class="dist-value">{{ inactiveCount }}</span>
          </div>
          <el-progress :percentage="inactivePct" :stroke-width="10" :show-text="false" status="exception" />
        </div>
      </FcSection>

      <FcSection class="dashboard-panel">
        <template #header>
          <div class="panel-header">
            <span>{{ t('benefit.dashboard-top-tenants') }}</span>
          </div>
        </template>
        <div v-if="topTenants.length === 0" class="empty">{{ t('benefit.dashboard-no-recent') }}</div>
        <div v-else class="top-list">
          <div v-for="(t2, i) in topTenants" :key="t2.id" class="top-row">
            <span class="top-rank">{{ i + 1 }}</span>
            <span class="top-name" :title="t2.name">{{ t2.name || t2.id }}</span>
            <span class="top-bar-wrap">
              <span class="top-bar" :style="{ width: barWidth(t2.subscription_count) + '%' }" />
            </span>
            <span class="top-count">{{ t2.subscription_count }}</span>
          </div>
        </div>
      </FcSection>
    </div>

    <div class="dashboard-row">
      <FcSection class="dashboard-panel dashboard-panel-wide">
        <template #header>
          <div class="panel-header">
            <span>{{ t('benefit.dashboard-recent-items') }}</span>
            <FcButton variant="text" size="small" @click="goItems">
              {{ t('benefit.dashboard-action-view-items') }}
            </FcButton>
          </div>
        </template>
        <el-scrollbar>
          <el-table
            class="fc-table recent-items-table"
            :data="recentItems"
            size="small"
          >
            <el-table-column :label="t('benefit.item-name')" min-width="200">
              <template #default="{ row }">
                <span class="item-name">
                  <i :class="row.icon" />
                  {{ row.name }}
                </span>
              </template>
            </el-table-column>
            <el-table-column :label="t('benefit.tenant')" width="160">
              <template #default="{ row }">
                <FcTag color="gray" size="sm">
                  {{ row.tenant_name || row.tenant_id }}
                </FcTag>
              </template>
            </el-table-column>
            <el-table-column :label="t('benefit.usage')" width="180">
              <template #default="{ row }">
                <el-progress :percentage="row.usage_pct" :stroke-width="6" :show-text="false" />
                <span class="usage-text">{{ row.used }}/{{ row.quota }}</span>
              </template>
            </el-table-column>

            <template #empty><FcEmpty /></template>
          </el-table>
        </el-scrollbar>
      </FcSection>

      <FcSection class="dashboard-panel dashboard-panel-narrow">
        <template #header>
          <div class="panel-header">
            <span>{{ t('benefit.dashboard-quick-actions') }}</span>
          </div>
        </template>
        <div class="quick-grid">
          <button class="quick-card" @click="goApps">
            <i class="ri-shield-user-line" />
            <span>{{ t('benefit.dashboard-action-new-app') }}</span>
          </button>
          <button class="quick-card" @click="goItems">
            <i class="ri-coupon-line" />
            <span>{{ t('benefit.dashboard-action-new-item') }}</span>
          </button>
          <button class="quick-card" @click="goTmplSet">
            <i class="ri-stack-line" />
            <span>{{ t('benefit.dashboard-action-new-tmpl') }}</span>
          </button>
          <button class="quick-card" @click="goItems">
            <i class="ri-eye-line" />
            <span>{{ t('benefit.dashboard-action-view-items') }}</span>
          </button>
        </div>
      </FcSection>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted, computed } from 'vue'
import { useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { benefitClient } from '@/api/benefitClient'
import { getApps } from '@/api/benefitTenant'
import type { ApiResponse } from '@/api/benefitClient'
import type { AppEntity } from '@/api/benefitTenant'
import { getPlatformItems } from '@/api/benefitPlatformItem'
import type { PlatformItem } from '@/api/benefitPlatformItem'
import {
  FcSection, FcSectionHeader, FcButton, FcTag, FcEmpty,
} from '@/components/sdk'

defineOptions({ name: 'BenefitPlatformDashboard' })

const { t } = useI18n()
const router = useRouter()

interface LiabilityStats {
  total_active_subscriptions: string
  total_quota_committed: string
  total_consumed: string
  total_frozen: string
  total_liability: string
}

const loading = ref(false)
const stats = ref<LiabilityStats | null>(null)
const apps = ref<AppEntity[]>([])
const recentItems = ref<PlatformItem[]>([])

const fetchAll = async () => {
  loading.value = true
  try {
    const [s, a, it] = await Promise.allSettled([
      benefitClient.get<any, ApiResponse<LiabilityStats>>('benefit/api/v1/platform/statistics/liabilities'),
      getApps(),
      getPlatformItems(),
    ])
    if (s.status === 'fulfilled') stats.value = s.value.data
    if (a.status === 'fulfilled') apps.value = a.value.data || []
    if (it.status === 'fulfilled') recentItems.value = (it.value.data?.list || []).slice(0, 6)
  } catch { /* ignore */ } finally {
    loading.value = false
  }
}

const activeCount = computed(() => apps.value.filter(a => a.status === 'ACTIVE').length)
const inactiveCount = computed(() => apps.value.length - activeCount.value)
const activePct = computed(() => apps.value.length === 0 ? 0 : Math.round(activeCount.value * 100 / apps.value.length))
const inactivePct = computed(() => 100 - activePct.value)

const topTenants = computed(() => {
  return [...apps.value]
    .sort((a, b) => (b.subscription_count || 0) - (a.subscription_count || 0))
    .slice(0, 5)
    .map(a => ({
      id: a.id,
      name: a.name,
      subscription_count: a.subscription_count || 0,
    }))
})

const barWidth = (n: number) => {
  const max = topTenants.value[0]?.subscription_count || 1
  if (max === 0) return 0
  return Math.max(6, Math.round(n * 100 / max))
}

const goApps = () => router.push('/benefit/platform/app/apps')
const goItems = () => router.push('/benefit/platform/app/items')
const goTmplSet = () => router.push('/benefit/platform/app/templates/set')

onMounted(() => { fetchAll() })
</script>

<style scoped lang="scss">
.platform-dashboard-page {
  display: flex;
  flex-direction: column;
  gap: var(--app-block-mb);
  min-width: 0;
}

.kpi-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 16px;
}
@media (max-width: 900px) {
  .kpi-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); }
}

.kpi-card {
  border-radius: 10px;
  padding: 18px 20px;
  background: var(--app-bg-elevated, var(--el-bg-color));
  border: 1px solid var(--el-border-color-lighter);
  position: relative;
  overflow: hidden;
  min-height: 100px;
  display: flex;
  flex-direction: column;
  justify-content: space-between;

  &::before {
    content: '';
    position: absolute;
    top: 0; left: 0; bottom: 0;
    width: 4px;
    background: var(--app-primary, var(--el-color-primary));
  }
  &.kpi-blue::before { background: #5b8def; }
  &.kpi-violet::before { background: #7e6dff; }
  &.kpi-cyan::before { background: #1ec0a8; }
  &.kpi-amber::before { background: #f0a13e; }
}
.kpi-label {
  font-size: 13px;
  color: var(--app-text-secondary, var(--el-text-color-secondary));
}
.kpi-value {
  font-size: 26px;
  font-weight: 700;
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  color: var(--app-text, var(--el-text-color-primary));
  line-height: 1.1;
}
.kpi-foot {
  font-size: 11px;
  color: var(--app-text-tertiary, var(--el-text-color-tertiary));
  letter-spacing: 0.5px;
  text-transform: uppercase;
}

.dashboard-row {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
  gap: 16px;
}
@media (max-width: 900px) {
  .dashboard-row { grid-template-columns: 1fr; }
}

.dashboard-panel { min-width: 0; }
.dashboard-panel-wide {
  grid-column: span 1;
}
.dashboard-panel-narrow {
  grid-column: span 1;
}

.panel-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  font-weight: 600;
  color: var(--app-text);
}

.dist-list {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: 4px 0;
}
.dist-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  font-size: 13px;
}
.dist-row.mt { margin-top: 14px; }
.dist-label {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  color: var(--app-text-regular, var(--el-text-color-regular));
}
.dist-ico-active { color: var(--el-color-success); font-size: 16px; }
.dist-ico-inactive { color: var(--el-color-danger); font-size: 16px; }
.dist-value {
  font-weight: 600;
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  color: var(--app-text);
}

.top-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 4px 0;
}
.top-row {
  display: grid;
  grid-template-columns: 24px 1fr 60px 50px;
  align-items: center;
  gap: 10px;
  font-size: 13px;
}
.top-rank {
  width: 20px;
  height: 20px;
  border-radius: 50%;
  background: var(--el-color-primary-light-9);
  color: var(--el-color-primary);
  font-size: 11px;
  font-weight: 700;
  display: inline-flex;
  align-items: center;
  justify-content: center;
}
.top-name {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: var(--app-text, var(--el-text-color-primary));
}
.top-bar-wrap {
  height: 6px;
  background: var(--el-fill-color-light);
  border-radius: 3px;
  overflow: hidden;
}
.top-bar {
  display: block;
  height: 100%;
  background: linear-gradient(90deg, var(--el-color-primary-light-5), var(--el-color-primary));
  border-radius: 3px;
}
.top-count {
  text-align: right;
  font-weight: 600;
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  color: var(--app-text);
}

.empty {
  text-align: center;
  padding: 32px 0;
  color: var(--app-text-secondary, var(--el-text-color-secondary));
  font-size: 13px;
}

.item-name {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-weight: 500;
  color: var(--app-text);
}
.item-name i {
  color: var(--app-primary, var(--el-color-primary));
  font-size: 16px;
}
.usage-text {
  font-size: 11px;
  color: var(--app-text-secondary, var(--el-text-color-secondary));
  margin-left: 6px;
}

.quick-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 10px;
}
.quick-card {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
  padding: 18px 12px;
  background: var(--el-fill-color-blank);
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;
  cursor: pointer;
  transition: all 0.15s;
  color: var(--el-text-color-regular);
  font-size: 13px;

  i {
    font-size: 22px;
    color: var(--el-color-primary);
  }
  &:hover {
    border-color: var(--el-color-primary-light-5);
    background: var(--el-color-primary-light-9);
    transform: translateY(-1px);
  }
}
</style>
