<template>
  <div class="app-page sets-page">
    <FcSectionHeader :title="t('benefit.sets-title')">
      <template #actions>
        <FcButton v-if="!isPlatform" type="primary" @click="openCreateDialog">
          <i class="ri-add-line" /> {{ t('common.create') }}
        </FcButton>
      </template>
    </FcSectionHeader>

    <div v-if="isPlatform" class="readonly-banner">
      <i class="ri-information-line" />
      <span>{{ t('benefit.platform-readonly-banner') }}</span>
    </div>

    <FcFilterBar>
      <el-form :inline="true" class="filter-form" @submit.prevent>
        <el-form-item v-if="isPlatform" class="fc-form-item" :label="t('benefit.tenant')">
          <FcSelect
            v-model="filterAppId"
            :placeholder="t('benefit.platform-filter-tenant')"
            :options="tenantOptions"
            style="width: 200px"
            clearable
          />
        </el-form-item>
        <el-form-item v-if="isPlatform" class="fc-form-item" :label="t('benefit.status')">
          <FcSelect
            v-model="filterStatus"
            :placeholder="t('common.all')"
            :options="statusOptions"
            style="width: 130px"
            clearable
          />
        </el-form-item>
        <el-form-item class="fc-form-item">
          <div class="search-wrapper">
            <i class="ri-search-line search-icon" />
            <input
              v-model="searchQuery"
              type="text"
              :placeholder="t('benefit.search-placeholder')"
              class="search-input"
            />
          </div>
        </el-form-item>
        <el-form-item class="fc-form-item">
          <FcButton size="small" @click="onReset">
            <i class="ri-refresh-line" /> {{ t('common.reset') }}
          </FcButton>
        </el-form-item>
      </el-form>
    </FcFilterBar>

    <FcSection>
      <el-scrollbar>
        <el-table
          class="fc-table sets-table"
          :data="filteredSets"
          v-loading="loading"
          row-key="id"
          stripe
          highlight-current-row
          :max-height="600"
        >
          <el-table-column label="ID" width="170">
            <template #default="{ row }">
              <code class="cell-id">{{ row.id }}</code>
            </template>
          </el-table-column>
          <el-table-column v-if="isPlatform" :label="t('benefit.tenant-appid')" width="160">
            <template #default="{ row }">
              <code class="cell-id">{{ row.tenant_id }}</code>
            </template>
          </el-table-column>
          <el-table-column v-if="isPlatform" :label="t('benefit.tenant')" min-width="140">
            <template #default="{ row }">
              <span class="tenant-name">{{ row.tenant_name || '—' }}</span>
            </template>
          </el-table-column>
          <el-table-column :label="t('benefit.set-name')" min-width="160">
            <template #default="{ row }">{{ row.name }}</template>
          </el-table-column>
          <el-table-column :label="t('benefit.duration')" width="140">
            <template #default="{ row }">{{ row.duration ?? '-' }} {{ row.duration_unit }}</template>
          </el-table-column>
          <el-table-column :label="t('benefit.quota')" width="100" align="center">
            <template #default="{ row }">{{ row.quota }}</template>
          </el-table-column>
          <el-table-column :label="t('benefit.priority')" width="100" align="center">
            <template #default="{ row }">P{{ row.priority ?? 0 }}</template>
          </el-table-column>
          <el-table-column :label="t('benefit.timing-mode')" width="120" align="center">
            <template #default="{ row }">{{ row.timing_mode || '-' }}</template>
          </el-table-column>
          <el-table-column v-if="isPlatform" :label="t('benefit.subscribe-count')" width="120" align="center">
            <template #default="{ row }">
              <FcTag color="primary" size="sm">{{ row.subscribe_count ?? 0 }}</FcTag>
            </template>
          </el-table-column>
          <el-table-column v-else :label="t('benefit.items')" width="80" align="center">
            <template #default="{ row }">{{ row.items?.length ?? 0 }}</template>
          </el-table-column>
          <el-table-column :label="t('common.edit')" :width="isPlatform ? 80 : 120" fixed="right" align="center">
            <template #default="{ row }">
              <div class="actions-cell">
                <FcTooltip :content="t('common.detail')">
                  <button class="action-icon-btn" @click="openDetailDialog(row)">
                    <i class="ri-information-line" />
                  </button>
                </FcTooltip>
                <template v-if="!isPlatform">
                  <FcTooltip :content="t('common.edit')">
                    <button class="action-icon-btn" @click="openEditDialog(row)">
                      <i class="ri-edit-line" />
                    </button>
                  </FcTooltip>
                  <FcTooltip :content="t('common.delete')">
                    <button class="action-icon-btn action-icon-danger" @click="handleDelete(row.id)">
                      <i class="ri-delete-bin-line" />
                    </button>
                  </FcTooltip>
                </template>
              </div>
            </template>
          </el-table-column>

          <template #empty><FcEmpty /></template>
        </el-table>
      </el-scrollbar>

      <div v-if="isPlatform" class="pager-bar">
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

    <!-- 详情对话框: 平台只读视图 -->
    <BenefitSetEditor
      v-model:open="detailVisible"
      v-model:form="detailForm"
      :item-pool="itemPool"
      :title="t('common.detail')"
      :readonly="true"
      :show-status="isPlatform"
      @cancel="detailVisible = false"
    />

    <!-- 创建/编辑: 共用权益包编辑器 (tenant 模式, 可编辑) -->
    <BenefitSetEditor
      v-if="!isPlatform"
      v-model:open="formVisible"
      v-model:form="form"
      :item-pool="itemPool"
      :title="editingId ? t('common.edit') : t('common.create')"
      :loading="submitting"
      :show-status="false"
      @submit="submitForm"
      @cancel="formVisible = false"
    />
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, onMounted, computed, watch } from 'vue'
import { useRoute } from 'vue-router'
import { useI18n } from 'vue-i18n'
import {
  getBenefitSets, createBenefitSet, updateBenefitSet, deleteBenefitSet,
  getPlatformBenefitSets,
} from '@/api/benefitSet'
import type { BenefitSet, PlatformBenefitSet } from '@/api/benefitSet'
import { getItemTemplates } from '@/api/benefitItemTemplate'
import { getBenefitItems } from '@/api/benefitItem'
import type { ItemTemplate, GlobalTemplate } from '@/types/benefit'
import {
  FcButton, FcSection, FcSectionHeader, FcFilterBar,
  FcTooltip, FcEmpty, FcTag, FcSelect, FcPagination, toast,
} from '@/components/sdk'
import BenefitSetEditor from './components/BenefitSetEditor.vue'
import { ElMessageBox } from 'element-plus'

defineOptions({ name: 'BenefitSets' })

const { t } = useI18n()
const route = useRoute()

/** 模式感知: 平台侧只读, 租户侧可写. */
const isPlatform = computed(() => route.path.startsWith('/benefit/platform/app'))

const loading = ref(false)
const sets = ref<BenefitSet[]>([])
const searchQuery = ref('')
const total = ref(0)
const pagination = reactive({ page: 1, size: 20 })
const filterAppId = ref<string | undefined>()
const filterStatus = ref<string | undefined>()

const statusOptions = computed(() => [
  { label: 'ACTIVE', value: 'ACTIVE' },
  { label: 'INACTIVE', value: 'INACTIVE' },
])

const tenantOptions = computed(() => {
  const map = new Map<string, string>()
  ;(sets.value as PlatformBenefitSet[]).forEach(s => {
    if (s.tenant_id) map.set(s.tenant_id, s.tenant_name || s.tenant_id)
  })
  return Array.from(map, ([value, label]) => ({ label, value }))
})

const filteredSets = computed(() => {
  if (!searchQuery.value) return sets.value
  const q = searchQuery.value.toLowerCase()
  return sets.value.filter(s => (s.name || '').toLowerCase().includes(q))
})

const itemPool = ref<ItemTemplate[]>([])

const fetchItemPool = async () => {
  if (itemPool.value.length) return
  try {
    // 平台侧 item 池来自 ubmp_benefit_tmpl_item; 租户侧来自 ubma_benefit_item
    const res = isPlatform.value
      ? await getItemTemplates()
      : await getBenefitItems()
    itemPool.value = res.data || []
  } catch (err: any) {
    toast.error(err.message || t('benefit.action-failed'))
  }
}

// === 详情对话框 (read-only) ===
const detailVisible = ref(false)
const detailForm = ref<GlobalTemplate>({
  name: '', duration: 0, duration_unit: 'month', quota: 0, priority: 0,
  refresh_cycle: 0, refresh_cycle_unit: 'month', refs: [], status: 'ACTIVE',
})

const openDetailDialog = (row: BenefitSet) => {
  detailForm.value = {
    ...row,
    name: row.name || '',
    duration: row.duration || 0,
    duration_unit: row.duration_unit || 'month',
    quota: row.quota || 0,
    priority: row.priority || 0,
    refresh_cycle: row.refresh_cycle || 0,
    refresh_cycle_unit: row.refresh_cycle_unit || 'month',
    refs: (row.items || []).map(i => ({
      item_id: i.item_id,
      quota: i.quota,
      refresh_cycle: i.refresh_cycle,
      refresh_cycle_unit: i.refresh_cycle_unit,
    })),
    status: row.status || 'ACTIVE',
  }
  detailVisible.value = true
  fetchItemPool()
}

// === 创建 / 编辑对话框 (tenant only) ===
const formVisible = ref(false)
const submitting = ref(false)
const editingId = ref<string | null>(null)
const form = ref<GlobalTemplate & { timing_mode?: string }>({
  name: '', duration: 0, duration_unit: 'month', quota: 0, priority: 0,
  refresh_cycle: 0, refresh_cycle_unit: 'month', timing_mode: 'RENEWAL',
  refs: [],
})

const openCreateDialog = () => {
  editingId.value = null
  form.value = {
    name: '', duration: 0, duration_unit: 'month', quota: 0, priority: 0,
    refresh_cycle: 0, refresh_cycle_unit: 'month', timing_mode: 'RENEWAL',
    refs: [],
  }
  formVisible.value = true
  fetchItemPool()
}

const openEditDialog = (row: BenefitSet) => {
  editingId.value = row.id!
  form.value = {
    ...row,
    name: row.name || '',
    duration: row.duration || 0,
    duration_unit: row.duration_unit || 'month',
    quota: row.quota || 0,
    priority: row.priority || 0,
    refresh_cycle: row.refresh_cycle || 0,
    refresh_cycle_unit: row.refresh_cycle_unit || 'month',
    timing_mode: row.timing_mode || 'RENEWAL',
    refs: (row.items || []).map(i => ({
      item_id: i.item_id,
      quota: i.quota,
      refresh_cycle: i.refresh_cycle,
      refresh_cycle_unit: i.refresh_cycle_unit,
    })),
  }
  formVisible.value = true
  fetchItemPool()
}

const submitForm = async () => {
  submitting.value = true
  try {
    const payload = {
      name: form.value.name,
      duration: form.value.duration,
      duration_unit: form.value.duration_unit,
      quota: form.value.quota,
      priority: form.value.priority,
      refresh_cycle: form.value.refresh_cycle,
      refresh_cycle_unit: form.value.refresh_cycle_unit,
      timing_mode: form.value.timing_mode,
      items: (form.value.refs || []).map(r => ({
        item_id: r.item_id,
        quota: r.quota,
        refresh_cycle: r.refresh_cycle,
        refresh_cycle_unit: r.refresh_cycle_unit,
      })),
    } as any
    if (editingId.value) {
      await updateBenefitSet(editingId.value, payload)
      toast.success(t('benefit.update-success'))
    } else {
      await createBenefitSet(payload)
      toast.success(t('benefit.create-success'))
    }
    formVisible.value = false
    onSearch()
  } catch (err: any) {
    toast.error(err.message || t('benefit.action-failed'))
  } finally {
    submitting.value = false
  }
}

const handleDelete = async (id: string) => {
  try {
    await ElMessageBox.confirm(t('benefit.delete-confirm'))
    await deleteBenefitSet(id)
    toast.success(t('benefit.delete-success'))
    onSearch()
  } catch (e) { /* cancelled */ }
}

// === 数据加载 (按模式选择 API) ===
const onSearch = async () => {
  loading.value = true
  try {
    if (isPlatform.value) {
      const res = await getPlatformBenefitSets({
        tenant_id: filterAppId.value,
        status: filterStatus.value || undefined,
        keyword: searchQuery.value.trim() || undefined,
        page: pagination.page,
        size: pagination.size,
      })
      const data = (res.data || {}) as { list?: PlatformBenefitSet[]; total?: number; page?: number; size?: number }
      sets.value = (data.list || []) as any
      total.value = Number(data.total ?? 0)
      pagination.page = Number(data.page ?? pagination.page)
      pagination.size = Number(data.size ?? pagination.size)
    } else {
      const res = await getBenefitSets()
      sets.value = (res.data || [])
    }
  } catch (err: any) {
    toast.error(err.message || t('benefit.action-failed'))
  } finally {
    loading.value = false
  }
}

const onReset = () => {
  filterAppId.value = undefined
  filterStatus.value = undefined
  searchQuery.value = ''
  pagination.page = 1
  onSearch()
}

onMounted(() => { onSearch() })

// 平台模式下 tenant_id/status/keyword 变更自动触发查询
if (isPlatform.value) {
  watch([filterAppId, filterStatus, searchQuery], () => {
    pagination.page = 1
    onSearch()
  })
}
</script>

<style scoped lang="scss">
@use '@/styles/benefit-shared' as *;

.sets-page {
  display: flex;
  flex-direction: column;
  gap: var(--app-block-mb);
  min-width: 0;
}

@include search-input(280px);
@include cell-id(false);
@include pager-bar();
@include tenant-name();
@include action-buttons();

.readonly-banner {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 10px 14px;
  background: color-mix(in srgb, var(--app-primary) 8%, transparent);
  border: 1px solid color-mix(in srgb, var(--app-primary) 25%, transparent);
  border-radius: var(--app-radius-sm, 8px);
  color: var(--app-primary);
  font-size: 13px;
  i { font-size: 16px; }
}

.filter-form {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 0;
  :deep(.el-form-item) { margin-bottom: 8px; margin-right: 12px; }
}
</style>