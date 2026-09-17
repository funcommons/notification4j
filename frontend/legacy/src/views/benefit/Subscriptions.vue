<template>
  <div class="app-page subscriptions-page">
    <FcSectionHeader :title="t('benefit.subscriptions-title')" />

    <FcFilterBar>
      <el-form :inline="true" class="filter-form" @submit.prevent>
        <el-form-item class="fc-form-item" :label="t('benefit.user-id')">
          <el-input
            class="fc-input"
            v-model="filters.userid"
            :placeholder="t('benefit.user-id')"
            clearable
            style="width: 160px"
          />
        </el-form-item>
        <el-form-item class="fc-form-item" :label="t('benefit.set-id')">
          <el-input
            class="fc-input"
            v-model="filters.set_id"
            :placeholder="t('benefit.set-id')"
            clearable
            style="width: 160px"
          />
        </el-form-item>
        <el-form-item class="fc-form-item" :label="t('benefit.status')">
          <FcSelect v-model="filters.status" :options="statusOptions" :placeholder="t('common.all')" style="width: 130px" />
        </el-form-item>
        <el-form-item class="fc-form-item" :label="t('benefit.order-id')">
          <el-input
            class="fc-input"
            v-model="filters.external_order_id"
            :placeholder="t('benefit.order-id')"
            clearable
            style="width: 180px"
          />
        </el-form-item>
        <el-form-item v-if="isPlatform" class="fc-form-item" :label="t('benefit.tenant')">
          <el-input
            class="fc-input"
            v-model="tenantIdInput"
            :placeholder="t('benefit.tenant-appid-placeholder')"
            clearable
            style="width: 180px"
          />
        </el-form-item>
        <el-form-item class="fc-form-item" :label="t('benefit.keyword')">
          <el-input
            class="fc-input"
            v-model="filters.keyword"
            :placeholder="t('benefit.keyword-placeholder')"
            clearable
            style="width: 180px"
          />
        </el-form-item>
        <el-form-item class="fc-form-item" :label="t('benefit.date-begin')">
          <el-date-picker
            v-model="dateBeginRange"
            type="datetimerange"
            :range-separator="t('benefit.to')"
            :start-placeholder="t('benefit.date-begin-start')"
            :end-placeholder="t('benefit.date-begin-end')"
            value-format="YYYY-MM-DDTHH:mm:ssZ"
            style="width: 340px"
          />
        </el-form-item>
        <el-form-item class="fc-form-item" :label="t('benefit.created-at')">
          <el-date-picker
            v-model="createdAtRange"
            type="datetimerange"
            :range-separator="t('benefit.to')"
            :start-placeholder="t('benefit.created-at-start')"
            :end-placeholder="t('benefit.created-at-end')"
            value-format="YYYY-MM-DDTHH:mm:ssZ"
            style="width: 340px"
          />
        </el-form-item>
        <el-form-item class="fc-form-item">
          <FcButton type="primary" :loading="loading" @click="onSearch">
            <i class="ri-search-line" /> {{ t('common.search') }}
          </FcButton>
          <FcButton @click="onReset">
            <i class="ri-refresh-line" /> {{ t('common.reset') }}
          </FcButton>
        </el-form-item>
      </el-form>
    </FcFilterBar>

    <FcSection>
      <el-scrollbar>
        <el-table
          class="fc-table subs-table"
          :data="rows"
          v-loading="loading"
          row-key="subscribe_id"
          stripe
          highlight-current-row
          :max-height="640"
          :expand-row-keys="expandedRows"
          @expand-change="onExpandChange"
        >
          <el-table-column type="expand">
            <template #default="{ row }">
              <div class="bucket-detail">
                <div class="bucket-detail-header">
                  <span class="bucket-detail-title">
                    <i class="ri-stack-line" /> {{ t('benefit.bucket-detail-title') }}
                  </span>
                  <span class="bucket-detail-meta" v-if="bucketLoading[row.subscribe_id || '']">
                    <i class="ri-loader-4-line ri-spin" /> {{ t('benefit.loading') }}
                  </span>
                </div>
                <el-table
                  class="fc-table"
                  v-if="bucketMap[row.subscribe_id || '']?.length"
                  :data="bucketMap[row.subscribe_id || '']"
                  size="small"
                  border
                  :empty-text="t('benefit.bucket-empty')"
                >
                  <el-table-column :label="t('benefit.subs-item-id')" prop="id" width="170">
                    <template #default="{ row: b }"><code class="cell-id">{{ b.id }}</code></template>
                  </el-table-column>
                  <el-table-column :label="t('benefit.item-id')" prop="item_id" width="140">
                    <template #default="{ row: b }"><code class="cell-id">{{ b.item_id }}</code></template>
                  </el-table-column>
                  <el-table-column :label="t('benefit.source-type')" prop="source_type" width="140" />
                  <el-table-column :label="t('benefit.bucket-priority')" prop="bucket_priority" width="110" align="center" />
                  <el-table-column :label="t('benefit.quota')" width="100" align="center">
                    <template #default="{ row: b }">{{ b.quota_limit ?? '-' }}</template>
                  </el-table-column>
                  <el-table-column :label="t('benefit.bucket-consumed')" width="110" align="center">
                    <template #default="{ row: b }">{{ b.period_consumed ?? 0 }}</template>
                  </el-table-column>
                  <el-table-column :label="t('benefit.bucket-frozen')" width="100" align="center">
                    <template #default="{ row: b }">{{ b.frozen_consumed ?? 0 }}</template>
                  </el-table-column>
                  <el-table-column :label="t('benefit.bucket-expires')" min-width="170">
                    <template #default="{ row: b }">{{ b.expires_at ? formatDate(b.expires_at) : t('benefit.bucket-expires-never') }}</template>
                  </el-table-column>
                </el-table>
                <div v-else-if="!bucketLoading[row.subscribe_id || '']" class="bucket-empty">
                  {{ t('benefit.bucket-empty') }}
                </div>
              </div>
            </template>
          </el-table-column>
          <el-table-column :label="t('benefit.subscribe-id')" width="170">
            <template #default="{ row }">
              <code class="cell-id">{{ row.subscribe_id || row.id }}</code>
            </template>
          </el-table-column>
          <el-table-column v-if="isPlatform" :label="t('benefit.tenant-appid')" min-width="140">
            <template #default="{ row }">
              <code class="cell-id">{{ row.tenant_id }}</code>
              <span class="muted"> {{ row.app_name }}</span>
            </template>
          </el-table-column>
          <el-table-column :label="t('benefit.user-id')" min-width="140">
            <template #default="{ row }">{{ row.userid }}</template>
          </el-table-column>
          <el-table-column :label="t('benefit.set-name')" min-width="160">
            <template #default="{ row }">
              <code class="cell-id">{{ row.set_id }}</code>
              <span class="muted"> {{ row.set_name }}</span>
            </template>
          </el-table-column>
          <el-table-column :label="t('benefit.status')" width="120">
            <template #default="{ row }">
              <FcTag :color="statusColor(row.status)" size="sm">
                {{ statusLabel(row.status) }}
              </FcTag>
            </template>
          </el-table-column>
          <el-table-column :label="t('benefit.order-id')" min-width="160" show-overflow-tooltip>
            <template #default="{ row }">{{ row.external_order_id || '-' }}</template>
          </el-table-column>
          <el-table-column :label="t('benefit.date-begin')" min-width="170">
            <template #default="{ row }">{{ formatDate(row.date_begin) }}</template>
          </el-table-column>
          <el-table-column :label="t('benefit.date-end')" min-width="170">
            <template #default="{ row }">{{ formatDate(row.date_end) }}</template>
          </el-table-column>
          <el-table-column :label="t('benefit.quota')" width="100" align="center">
            <template #default="{ row }">{{ row.quota_limit ?? '-' }}</template>
          </el-table-column>
          <el-table-column :label="t('benefit.created-at')" min-width="160">
            <template #default="{ row }">{{ formatDate(row.created_at) }}</template>
          </el-table-column>

          <template #empty><FcEmpty /></template>
        </el-table>
      </el-scrollbar>

      <div class="pager-bar">
        <FcPagination
          v-model:current-page="pagination.page"
          v-model:page-size="pagination.size"
          :total="total"
          :page-sizes="[10, 20, 50, 100]"
          layout="total, sizes, prev, pager, next, jumper"
          @size-change="onSearch"
          @current-change="onSearch"
        />
      </div>
    </FcSection>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { listSubscriptions, platformListSubscriptions, listSubscriptionItems, platformListSubscriptionItems } from '@/api/benefitSubscription'
import type { Subscription, SubscriptionBucket } from '@/api/benefitSubscription'
import {
  FcButton, FcSection, FcSectionHeader, FcFilterBar,
  FcTag, FcEmpty, FcSelect, FcPagination, toast,
} from '@/components/sdk'

defineOptions({ name: 'BenefitSubscriptions' })

const { t } = useI18n()
const route = useRoute()

const isPlatform = computed(() => route.path.startsWith('/benefit/platform/app'))

const loading = ref(false)
const rows = ref<Subscription[]>([])
const total = ref(0)

// 多源桶展开
const expandedRows = ref<string[]>([])
const bucketMap = reactive<Record<string, SubscriptionBucket[]>>({})
const bucketLoading = reactive<Record<string, boolean>>({})

const initialFilters = () => ({
  userid: '',
  set_id: '',
  status: '',
  external_order_id: '',
  keyword: '',
})
const filters = reactive(initialFilters())

const dateBeginRange = ref<[string, string] | null>(null)
const createdAtRange = ref<[string, string] | null>(null)

const tenantIdInput = ref('')

const pagination = reactive({ page: 1, size: 20 })

const statusOptions = computed(() => [
  { label: t('benefit.sub-status.ACTIVE'), value: 'ACTIVE' },
  { label: t('benefit.sub-status.DISABLED'), value: 'DISABLED' },
  { label: t('benefit.sub-status.EXPIRED'), value: 'EXPIRED' },
])

const statusColor = (s?: string) => {
  switch (s) {
    case 'ACTIVE': return 'success'
    case 'DISABLED': return 'warning'
    case 'EXPIRED': return 'gray'
    default: return 'gray'
  }
}
const statusLabel = (s?: string) => {
  const key = s ? `benefit.sub-status.${s}` : 'benefit.sub-status.UNKNOWN'
  return t(key)
}

const formatDate = (iso?: string) => (iso ? new Date(iso).toLocaleString() : '-')

const buildParams = () => {
  const p: Record<string, any> = {
    userid: filters.userid || undefined,
    set_id: filters.set_id || undefined,
    status: filters.status || undefined,
    external_order_id: filters.external_order_id || undefined,
    keyword: filters.keyword || undefined,
    page: pagination.page,
    size: pagination.size,
  }
  if (dateBeginRange.value && dateBeginRange.value.length === 2) {
    p.date_begin_start = dateBeginRange.value[0]
    p.date_begin_end = dateBeginRange.value[1]
  }
  if (createdAtRange.value && createdAtRange.value.length === 2) {
    p.created_at_start = createdAtRange.value[0]
    p.created_at_end = createdAtRange.value[1]
  }
  if (isPlatform.value && tenantIdInput.value) {
    p.tenant_id = tenantIdInput.value
  }
  return p
}

const onSearch = async () => {
  loading.value = true
  try {
    const api = isPlatform.value ? platformListSubscriptions : listSubscriptions
    const res = await api(buildParams() as any)
    const data = res.data as any
    rows.value = data?.list || []
    total.value = Number(data?.total ?? 0)
    pagination.page = Number(data?.page ?? pagination.page)
    pagination.size = Number(data?.size ?? pagination.size)
  } catch (err: any) {
    toast.error(err.message || t('benefit.action-failed'))
  } finally {
    loading.value = false
  }
}

const onReset = () => {
  Object.assign(filters, initialFilters())
  dateBeginRange.value = null
  createdAtRange.value = null
  tenantIdInput.value = ''
  pagination.page = 1
  onSearch()
}

const loadBuckets = async (subscribeId: string) => {
  bucketLoading[subscribeId] = true
  try {
    const api = isPlatform.value ? platformListSubscriptionItems : listSubscriptionItems
    const params: any = {}
    if (isPlatform.value && tenantIdInput.value) params.tenant_id = tenantIdInput.value
    const res = await api(subscribeId, params) as any
    bucketMap[subscribeId] = (res.data?.list as SubscriptionBucket[]) || []
  } catch (err: any) {
    bucketMap[subscribeId] = []
    toast.error(err.message || t('benefit.action-failed'))
  } finally {
    bucketLoading[subscribeId] = false
  }
}

const onExpandChange = async (_row: any, expandedList: any[]) => {
  const ids = (expandedList || []).map(r => r.subscribe_id).filter(Boolean)
  expandedRows.value = ids
  for (const id of ids) {
    if (!bucketMap[id]) await loadBuckets(id)
  }
}

onMounted(onSearch)
</script>

<style scoped lang="scss">
.subscriptions-page {
  display: flex;
  flex-direction: column;
  gap: var(--app-block-mb);
  min-width: 0;
}
.filter-form {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 0;
  :deep(.el-form-item) { margin-bottom: 8px; margin-right: 12px; }
}
.cell-id {
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  font-size: 12px;
  padding: 1px 6px;
  background: var(--app-bg-muted, #f5f5f7);
  border-radius: 4px;
  color: var(--app-text);
}
.muted { color: var(--app-text-tertiary); margin-left: 4px; font-size: 12px; }
.bucket-detail {
  padding: 8px 16px 16px 48px;
  background: var(--app-bg-muted, #fafafa);
}
.bucket-detail-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 8px;
}
.bucket-detail-title {
  font-weight: 600;
  font-size: 13px;
  color: var(--app-text);
  i { margin-right: 4px; color: var(--app-color-primary); }
}
.bucket-detail-meta {
  font-size: 12px;
  color: var(--app-text-tertiary);
  i { margin-right: 4px; }
}
.bucket-empty {
  font-size: 13px;
  color: var(--app-text-tertiary);
  padding: 12px 0;
}
.ri-spin { animation: ri-spin 1s linear infinite; }
@keyframes ri-spin { to { transform: rotate(360deg); } }
.pager-bar {
  display: flex;
  justify-content: flex-end;
  padding: 12px 4px 0;
}
</style>