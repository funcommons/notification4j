<template>
  <div class="app-page tenant-dashboard-page">
    <FcSectionHeader
      :title="t('benefit.tenant-dashboard-title')"
      :subtitle="t('benefit.tenant-dashboard-subtitle')"
    />

    <div class="kpi-grid" v-loading="loading">
      <div class="kpi-card kpi-blue">
        <div class="kpi-label">{{ t('benefit.items-title') }}</div>
        <div class="kpi-value">{{ itemCount }}</div>
        <div class="kpi-foot">Benefit Items</div>
      </div>
      <div class="kpi-card kpi-violet">
        <div class="kpi-label">{{ t('benefit.sets-title') }}</div>
        <div class="kpi-value">{{ setCount }}</div>
        <div class="kpi-foot">Benefit Sets</div>
      </div>
      <div class="kpi-card kpi-cyan">
        <div class="kpi-label">{{ t('benefit.available-templates') }}</div>
        <div class="kpi-value">{{ templateCount }}</div>
        <div class="kpi-foot">Templates</div>
      </div>
      <div class="kpi-card kpi-amber">
        <div class="kpi-label">{{ t('benefit.active-subs') }}</div>
        <div class="kpi-value">{{ subCount }}</div>
        <div class="kpi-foot">Active Subscriptions</div>
      </div>
    </div>

    <div class="dashboard-row">
      <FcSection class="dashboard-panel">
        <template #header>
          <div class="panel-header">
            <span>{{ t('benefit.dashboard-tenant-recent-subs') }}</span>
            <FcButton variant="text" size="small" @click="goSubs">
              {{ t('benefit.dashboard-tenant-view-subs') }}
            </FcButton>
          </div>
        </template>

        <div v-if="recentSubs.length === 0" class="empty">
          {{ t('benefit.dashboard-tenant-no-subs') }}
        </div>
        <el-scrollbar v-else>
          <el-table
            class="fc-table recent-subs-table"
            :data="recentSubs"
            size="small"
          >
            <el-table-column :label="t('benefit.user-id')" prop="userid" min-width="180" />
            <el-table-column :label="t('benefit.set-id')" prop="set_id" width="160" />
            <el-table-column :label="t('benefit.status')" width="120">
              <template #default="{ row }">
                <FcTag :color="row.status === 'ACTIVE' ? 'success' : 'gray'" size="sm">
                  {{ row.status === 'ACTIVE' ? t('benefit.active') : t('benefit.inactive') }}
                </FcTag>
              </template>
            </el-table-column>
            <el-table-column :label="t('benefit.updated')" width="160">
              <template #default="{ row }">{{ formatDate(row.created_at) }}</template>
            </el-table-column>

            <template #empty><FcEmpty /></template>
          </el-table>
        </el-scrollbar>
      </FcSection>

      <FcSection class="dashboard-panel">
        <template #header>
          <div class="panel-header">
            <span>{{ t('benefit.dashboard-tenant-app-info') }}</span>
          </div>
        </template>
        <div class="cred-list">
          <div class="cred-row">
            <span class="cred-label">{{ t('benefit.app-id') }}</span>
            <code class="cred-value">{{ tenantId }}</code>
            <FcTooltip :content="t('benefit.copy-id')">
              <button class="action-icon-btn" @click="onCopyAppId">
                <i class="ri-file-copy-line" />
              </button>
            </FcTooltip>
          </div>
          <div class="cred-row">
            <span class="cred-label">{{ t('common.credits') }}</span>
            <span class="cred-text token-preview">{{ tokenPreview }}</span>
          </div>
          <div class="cred-row">
            <span class="cred-label">{{ t('common.success') }}</span>
            <span class="cred-text status-text">
              <i class="ri-checkbox-circle-fill status-icon" />
              {{ t('common.success') }}
            </span>
          </div>
        </div>
      </FcSection>
    </div>

    <FcSection class="dashboard-panel">
      <template #header>
        <div class="panel-header">
          <span>{{ t('benefit.dashboard-tenant-quick-actions') }}</span>
        </div>
      </template>
      <div class="quick-grid">
        <button class="quick-card" @click="goItems">
          <i class="ri-coupon-line" />
          <span>{{ t('benefit.dashboard-tenant-new-item') }}</span>
        </button>
        <button class="quick-card" @click="goSets">
          <i class="ri-stack-line" />
          <span>{{ t('benefit.dashboard-tenant-new-set') }}</span>
        </button>
        <button class="quick-card" @click="goTmpl">
          <i class="ri-file-copy-2-line" />
          <span>{{ t('benefit.dashboard-tenant-from-tmpl') }}</span>
        </button>
        <button class="quick-card" @click="goSubs">
          <i class="ri-list-check" />
          <span>{{ t('benefit.dashboard-tenant-view-subs') }}</span>
        </button>
      </div>
    </FcSection>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted, computed } from 'vue'
import { useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { copySilent } from '@/composables'
import { getBenefitItems } from '@/api/benefitItem'
import { getBenefitSets } from '@/api/benefitSet'
import { getTenantTemplates } from '@/api/benefitTemplate'
import { listSubscriptions } from '@/api/benefitSubscription'
import type { Subscription } from '@/api/benefitSubscription'
import {
  FcSection, FcSectionHeader, FcButton, FcTag, FcTooltip, FcEmpty, toast,
} from '@/components/sdk'

defineOptions({ name: 'BenefitTenantDashboard' })

const { t } = useI18n()
const router = useRouter()

const loading = ref(false)
const itemCount = ref(0)
const setCount = ref(0)
const templateCount = ref(0)
const subCount = ref(0)
const recentSubs = ref<Subscription[]>([])

const tenantId = computed(() => sessionStorage.getItem('benefit4j:tenant_id') || '-')
const tokenPreview = computed(() => {
  const token = sessionStorage.getItem('benefit4j:access_token')
  if (!token) return '—'
  return `${token.slice(0, 8)}${'•'.repeat(12)}${token.slice(-4)}`
})

const fetchAll = async () => {
  loading.value = true
  try {
    const [items, sets, templates, subs] = await Promise.allSettled([
      getBenefitItems(),
      getBenefitSets(),
      getTenantTemplates(),
      listSubscriptions(),
    ])
    if (items.status === 'fulfilled') itemCount.value = items.value.data?.length ?? 0
    if (sets.status === 'fulfilled') setCount.value = sets.value.data?.length ?? 0
    if (templates.status === 'fulfilled') templateCount.value = templates.value.data?.length ?? 0
    if (subs.status === 'fulfilled') {
      const data = subs.value.data as any
      const list: Subscription[] = data?.list || []
      subCount.value = list.filter((s: Subscription) => s.status === 'ACTIVE').length
      recentSubs.value = list.slice(0, 6)
    }
  } catch { /* ignore */ } finally {
    loading.value = false
  }
}

const formatDate = (raw: string | undefined) => {
  if (!raw) return '—'
  const d = new Date(raw)
  if (isNaN(d.getTime())) return raw
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`
}

const onCopyAppId = async () => {
  const ok = await copySilent(tenantId.value)
  if (ok) toast.success(t('benefit.copied'))
}

const goItems = () => router.push('/benefit/tenant/app/items')
const goSets = () => router.push('/benefit/tenant/app/sets')
const goTmpl = () => router.push('/benefit/tenant/app/templates')
const goSubs = () => router.push('/benefit/tenant/app/subscriptions')

onMounted(() => { fetchAll() })
</script>

<style scoped lang="scss">
.tenant-dashboard-page {
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
.kpi-label { font-size: 13px; color: var(--app-text-secondary, var(--el-text-color-secondary)); }
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
  grid-template-columns: minmax(0, 7fr) minmax(0, 5fr);
  gap: 16px;
}
@media (max-width: 900px) {
  .dashboard-row { grid-template-columns: 1fr; }
}

.dashboard-panel { min-width: 0; }

.panel-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  font-weight: 600;
  color: var(--app-text);
}

.cred-list {
  display: flex;
  flex-direction: column;
  gap: 14px;
  padding: 4px 0;
}
.cred-row {
  display: grid;
  grid-template-columns: 90px 1fr auto;
  align-items: center;
  gap: 12px;
  font-size: 13px;
}
.cred-label {
  color: var(--app-text-secondary, var(--el-text-color-secondary));
  font-size: 12px;
}
.cred-value {
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  font-size: 12px;
  background: var(--el-fill-color-light);
  padding: 4px 8px;
  border-radius: 4px;
  word-break: break-all;
  color: var(--app-text);
}
.cred-text {
  color: var(--app-text, var(--el-text-color-primary));
}
.token-preview { font-family: ui-monospace, SFMono-Regular, Menlo, monospace; letter-spacing: 1px; }
.status-text {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}
.status-icon { color: var(--el-color-success); font-size: 16px; }

.action-icon-btn {
  width: 30px;
  height: 30px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border-radius: var(--app-radius-sm, 6px);
  color: var(--app-text-secondary);
  background: none;
  border: none;
  cursor: pointer;
  transition: all 0.15s;
  i { font-size: 16px; }
  &:hover {
    background: var(--app-sidebar-item-hover-bg, var(--el-fill-color-light));
    color: var(--app-text);
  }
}

.empty {
  text-align: center;
  padding: 32px 0;
  color: var(--app-text-secondary, var(--el-text-color-secondary));
  font-size: 13px;
}

.quick-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 12px;
}
@media (max-width: 700px) {
  .quick-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); }
}
.quick-card {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
  padding: 20px 12px;
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
