<template>
  <div class="app-page consumptions-page">
    <FcSectionHeader :title="t('benefit.consumptions-title')" />

    <FcFilterBar>
      <el-form :inline="true" class="filter-form" @submit.prevent>
        <el-form-item class="fc-form-item" :label="t('benefit.user-id')">
          <el-input class="fc-input" v-model="filters.userid" :placeholder="t('benefit.user-id')" clearable style="width: 160px" />
        </el-form-item>
        <el-form-item class="fc-form-item" :label="t('benefit.subs-item-id')">
          <el-input class="fc-input" v-model="filters.subs_item_id" :placeholder="t('benefit.subs-item-id')" clearable style="width: 180px" />
        </el-form-item>
        <el-form-item class="fc-form-item" :label="t('benefit.item-id')">
          <el-input class="fc-input" v-model="filters.item_id" :placeholder="t('benefit.item-id')" clearable style="width: 160px" />
        </el-form-item>
        <el-form-item class="fc-form-item" :label="t('benefit.status')">
          <FcSelect v-model="filters.status" :options="statusOptions" :placeholder="t('common.all')" style="width: 140px" />
        </el-form-item>
        <el-form-item class="fc-form-item" :label="t('benefit.order-id')">
          <el-input class="fc-input" v-model="filters.external_order_id" :placeholder="t('benefit.order-id')" clearable style="width: 180px" />
        </el-form-item>
        <el-form-item v-if="isPlatform" class="fc-form-item" :label="t('benefit.tenant')">
          <el-input class="fc-input" v-model="tenantIdInput" :placeholder="t('benefit.tenant-appid-placeholder')" clearable style="width: 180px" />
        </el-form-item>
        <el-form-item class="fc-form-item" :label="t('benefit.keyword')">
          <el-input class="fc-input" v-model="filters.keyword" :placeholder="t('benefit.keyword-placeholder')" clearable style="width: 180px" />
        </el-form-item>
        <el-form-item class="fc-form-item" :label="t('benefit.consume-num')">
          <el-input-number v-model="filters.consume_num_min" :min="0" :placeholder="t('benefit.min')" style="width: 110px" />
          <span class="range-sep">~</span>
          <el-input-number v-model="filters.consume_num_max" :min="0" :placeholder="t('benefit.max')" style="width: 110px" />
        </el-form-item>
        <el-form-item class="fc-form-item" :label="t('benefit.consume-time')">
          <el-date-picker
            v-model="consumeTimeRange"
            type="datetimerange"
            :range-separator="t('benefit.to')"
            :start-placeholder="t('benefit.consume-time-start')"
            :end-placeholder="t('benefit.consume-time-end')"
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
          class="fc-table consumes-table"
          :data="rows"
          v-loading="loading"
          row-key="consume_id"
          stripe
          highlight-current-row
          :max-height="640"
        >
          <el-table-column :label="t('benefit.consume-id')" width="170">
            <template #default="{ row }">
              <code class="cell-id">{{ row.consume_id }}</code>
            </template>
          </el-table-column>
          <el-table-column v-if="isPlatform" :label="t('benefit.tenant-appid')" min-width="140">
            <template #default="{ row }">
              <code class="cell-id">{{ row.tenant_id }}</code>
            </template>
          </el-table-column>
          <el-table-column :label="t('benefit.user-id')" min-width="140">
            <template #default="{ row }">{{ row.userid || '-' }}</template>
          </el-table-column>
          <el-table-column :label="t('benefit.item-name')" min-width="160">
            <template #default="{ row }">
              <code class="cell-id">{{ row.item_id }}</code>
              <span class="muted"> {{ row.item_name }}</span>
            </template>
          </el-table-column>
          <el-table-column :label="t('benefit.subs-item-id')" width="170">
            <template #default="{ row }">
              <code class="cell-id">{{ row.subs_item_id }}</code>
            </template>
          </el-table-column>
          <el-table-column :label="t('benefit.order-id')" min-width="160" show-overflow-tooltip>
            <template #default="{ row }">{{ row.external_order_id || '-' }}</template>
          </el-table-column>
          <el-table-column :label="t('benefit.consume-num')" width="110" align="center">
            <template #default="{ row }">{{ row.consume_num }}</template>
          </el-table-column>
          <el-table-column :label="t('benefit.status')" width="120">
            <template #default="{ row }">
              <FcTag :color="statusColor(row.status)" size="sm">
                {{ statusLabel(row.status) }}
              </FcTag>
            </template>
          </el-table-column>
          <el-table-column :label="t('benefit.consume-time')" min-width="170">
            <template #default="{ row }">{{ formatDate(row.consume_time) }}</template>
          </el-table-column>
          <el-table-column :label="t('common.edit')" width="100" fixed="right" align="center">
            <template #default="{ row }">
              <div class="actions-cell">
                <FcTooltip :content="t('benefit.refund')">
                  <button
                    class="action-icon-btn"
                    :class="{ 'action-icon-warn': row.refundable }"
                    :disabled="!row.refundable"
                    @click="openRefundDialog(row)"
                  >
                    <i class="ri-arrow-go-back-line" />
                  </button>
                </FcTooltip>
              </div>
            </template>
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

    <FcDialog
      v-model:open="refundDialogVisible"
      :title="t('benefit.refund')"
      append-to-body
    >
      <el-form :model="refundForm" label-width="100px">
        <el-form-item class="fc-form-item" :label="t('benefit.consume-id')">
          <code class="cell-id">{{ refundTarget?.consume_id }}</code>
        </el-form-item>
        <el-form-item class="fc-form-item" :label="t('benefit.consume-num')">
          {{ refundTarget?.consume_num }}
        </el-form-item>
        <el-form-item class="fc-form-item" :label="t('benefit.reason')">
          <el-input
            class="fc-input"
            v-model="refundForm.reason"
            type="textarea"
            :rows="3"
            :placeholder="t('benefit.refund-reason-placeholder')"
            maxlength="256"
            show-word-limit
          />
        </el-form-item>
        <el-form-item class="fc-form-item" :label="t('benefit.operator')">
          <el-input
            class="fc-input"
            v-model="refundForm.operator"
            :placeholder="t('benefit.operator-placeholder')"
            maxlength="64"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <FcButton @click="refundDialogVisible = false">{{ t('common.cancel') }}</FcButton>
        <FcButton type="primary" :loading="refunding" @click="onRefund">
          <i class="ri-check-line" /> {{ t('common.confirm') }}
        </FcButton>
      </template>
    </FcDialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'
import { useI18n } from 'vue-i18n'
import {
  listConsumes, platformListConsumes,
  refundConsume, platformRefundConsume,
} from '@/api/benefitConsume'
import type { ConsumeRecord } from '@/api/benefitConsume'
import {
  FcButton, FcSection, FcSectionHeader, FcFilterBar,
  FcTag, FcTooltip, FcEmpty, FcDialog, FcSelect, FcPagination, toast,
} from '@/components/sdk'

defineOptions({ name: 'BenefitConsumptions' })

const { t } = useI18n()
const route = useRoute()

const isPlatform = computed(() => route.path.startsWith('/benefit/platform/app'))

const loading = ref(false)
const rows = ref<ConsumeRecord[]>([])
const total = ref(0)

const initialFilters = () => ({
  userid: '',
  subs_item_id: '',
  item_id: '',
  status: '',
  external_order_id: '',
  keyword: '',
  consume_num_min: undefined as number | undefined,
  consume_num_max: undefined as number | undefined,
})
const filters = reactive(initialFilters())

const consumeTimeRange = ref<[string, string] | null>(null)
const tenantIdInput = ref('')
const pagination = reactive({ page: 1, size: 20 })

const statusOptions = computed(() => [
  { label: t('benefit.consume-status.COMMITTED'), value: 'COMMITTED' },
  { label: t('benefit.consume-status.RESERVED'), value: 'RESERVED' },
  { label: t('benefit.consume-status.RELEASED'), value: 'RELEASED' },
  { label: t('benefit.consume-status.REFUNDED'), value: 'REFUNDED' },
])

const statusColor = (s?: string) => {
  switch (s) {
    case 'COMMITTED': return 'success'
    case 'RESERVED': return 'warning'
    case 'RELEASED': return 'gray'
    case 'REFUNDED': return 'primary'
    default: return 'gray'
  }
}
const statusLabel = (s?: string) => {
  const key = s ? `benefit.consume-status.${s}` : 'benefit.consume-status.UNKNOWN'
  return t(key)
}

const formatDate = (iso?: string) => (iso ? new Date(iso).toLocaleString() : '-')

const buildParams = () => {
  const p: Record<string, any> = {
    userid: filters.userid || undefined,
    subs_item_id: filters.subs_item_id || undefined,
    item_id: filters.item_id || undefined,
    status: filters.status || undefined,
    external_order_id: filters.external_order_id || undefined,
    keyword: filters.keyword || undefined,
    consume_num_min: filters.consume_num_min,
    consume_num_max: filters.consume_num_max,
    page: pagination.page,
    size: pagination.size,
  }
  if (consumeTimeRange.value && consumeTimeRange.value.length === 2) {
    p.consume_time_start = consumeTimeRange.value[0]
    p.consume_time_end = consumeTimeRange.value[1]
  }
  if (isPlatform.value && tenantIdInput.value) {
    p.tenant_id = tenantIdInput.value
  }
  return p
}

const onSearch = async () => {
  loading.value = true
  try {
    const api = isPlatform.value ? platformListConsumes : listConsumes
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
  consumeTimeRange.value = null
  tenantIdInput.value = ''
  pagination.page = 1
  onSearch()
}

// === 退减对话框 ===
const refundDialogVisible = ref(false)
const refundTarget = ref<ConsumeRecord | null>(null)
const refundForm = reactive({ reason: '', operator: '' })
const refunding = ref(false)

const openRefundDialog = (row: ConsumeRecord) => {
  refundTarget.value = row
  refundForm.reason = ''
  refundForm.operator = ''
  refundDialogVisible.value = true
}

const onRefund = async () => {
  if (!refundTarget.value?.consume_id) return
  refunding.value = true
  try {
    const payload = {
      reason: refundForm.reason || undefined,
      operator: refundForm.operator || undefined,
    }
    if (isPlatform.value) {
      await platformRefundConsume(
        refundTarget.value.consume_id,
        payload,
        tenantIdInput.value || undefined,
      )
    } else {
      await refundConsume(refundTarget.value.consume_id, payload)
    }
    toast.success(t('benefit.refund-success'))
    refundDialogVisible.value = false
    onSearch()
  } catch (err: any) {
    toast.error(err.message || t('benefit.action-failed'))
  } finally {
    refunding.value = false
  }
}

onMounted(onSearch)
</script>

<style scoped lang="scss">
.consumptions-page {
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
  .range-sep { margin: 0 4px; color: var(--app-text-tertiary); }
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
.pager-bar {
  display: flex;
  justify-content: flex-end;
  padding: 12px 4px 0;
}
.actions-cell { display: flex; gap: 4px; justify-content: center; }
.action-icon-btn {
  width: 30px;
  height: 30px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border-radius: var(--app-radius-sm, 6px);
  color: var(--app-text-tertiary);
  background: none;
  border: none;
  cursor: pointer;
  i { font-size: 16px; }
  &:hover:not(:disabled) {
    background: var(--app-sidebar-item-hover-bg, var(--el-fill-color-light));
    color: var(--app-text);
  }
  &:disabled { cursor: not-allowed; opacity: 0.4; }
}
.action-icon-warn:not(:disabled) {
  color: var(--el-color-warning);
  &:hover { background: color-mix(in srgb, var(--el-color-warning) 12%, transparent); }
}
</style>