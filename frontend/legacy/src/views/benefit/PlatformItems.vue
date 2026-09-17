<template>
  <div class="app-page platform-items-page">
    <FcSectionHeader :title="t('benefit.platform-items-title')" :subtitle="t('benefit.platform-items-subtitle')" />

    <FcFilterBar>
      <FcSelect
        v-model="filterAppId"
        :placeholder="t('benefit.platform-filter-tenant')"
        :options="tenantOptions"
        style="width: 200px"
        clearable
      />
      <FcSelect
        v-model="filterStatus"
        :placeholder="t('benefit.platform-filter-status')"
        :options="statusOptions"
        style="width: 140px"
        clearable
      />
      <div class="search-wrapper">
        <i class="ri-search-line search-icon" />
        <input
          v-model="searchQuery"
          type="text"
          :placeholder="t('benefit.search-placeholder')"
          class="search-input"
        />
      </div>
      <template #actions>
        <FcButton size="small" @click="onRefresh">
          <i class="ri-refresh-line" /> {{ t('benefit.action-refresh') }}
        </FcButton>
        <FcButton size="small" @click="onCopyAll">
          <i class="ri-file-copy-line" /> {{ t('benefit.platform-export-ids') }}
        </FcButton>
      </template>
    </FcFilterBar>

    <FcSection>
      <el-scrollbar>
        <el-table
          class="fc-table platform-items-table"
          :data="rows"
          v-loading="loading"
          row-key="id"
          stripe
          :max-height="600"
        >
          <el-table-column label="ID" width="170">
            <template #default="{ row }">
              <code class="cell-id" @click="onCopy(row.id)">{{ row.id }}</code>
            </template>
          </el-table-column>
          <el-table-column :label="t('benefit.tenant-appid')" width="160">
            <template #default="{ row }">
              <code class="cell-id" @click="onCopy(row.tenant_id)">{{ row.tenant_id }}</code>
            </template>
          </el-table-column>
          <el-table-column :label="t('benefit.tenant')" min-width="140">
            <template #default="{ row }">
              <span class="tenant-name">{{ row.tenant_name || '—' }}</span>
            </template>
          </el-table-column>
          <el-table-column :label="t('benefit.item-name')" min-width="180">
            <template #default="{ row }">
              <span class="item-name">
                <i :class="row.icon" />
                {{ row.name }}
              </span>
            </template>
          </el-table-column>
          <el-table-column :label="t('benefit.description')" min-width="220" show-overflow-tooltip>
            <template #default="{ row }">{{ row.description }}</template>
          </el-table-column>
          <el-table-column :label="t('benefit.default-deduction')" width="120" align="center">
            <template #default="{ row }">
              <span class="deduction">×{{ row.default_deduction }}</span>
            </template>
          </el-table-column>
          <el-table-column :label="t('benefit.usage')" width="180">
            <template #default="{ row }">
              <el-progress :percentage="row.usage_pct" :stroke-width="6" :show-text="false" />
              <span class="usage-text">{{ row.used }}/{{ row.quota }}</span>
            </template>
          </el-table-column>
          <el-table-column :label="t('benefit.status')" width="100">
            <template #default="{ row }">
              <FcTag :color="row.status === 'ACTIVE' ? 'success' : 'gray'" size="sm">
                {{ row.status === 'ACTIVE' ? t('benefit.active') : t('benefit.inactive') }}
              </FcTag>
            </template>
          </el-table-column>
          <el-table-column :label="t('benefit.updated')" width="160">
            <template #default="{ row }">{{ formatUpdated(row.updated_at) }}</template>
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
          @size-change="onRefresh"
          @current-change="onRefresh"
        />
      </div>
    </FcSection>
  </div>
</template>

<script setup lang="ts">
import { computed, ref, reactive, onMounted, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import {
  FcSection, FcSectionHeader, FcFilterBar, FcButton, FcSelect, FcTag, FcEmpty,
  FcPagination, toast,
} from '@/components/sdk'
import { copySilent } from '@/composables'
import { getPlatformItems } from '@/api/benefitPlatformItem'
import type { PlatformItem } from '@/api/benefitPlatformItem'

defineOptions({ name: 'PlatformItems' })

const { t } = useI18n()

const rows = ref<PlatformItem[]>([])
const total = ref(0)
const loading = ref(false)
const searchQuery = ref('')
const filterAppId = ref<string | undefined>()
const filterStatus = ref<'ACTIVE' | 'INACTIVE' | undefined>()
const pagination = reactive({ page: 1, size: 20 })

const statusOptions = computed(() => [
  { label: 'ACTIVE', value: 'ACTIVE' },
  { label: 'INACTIVE', value: 'INACTIVE' },
])

const fetchItems = async () => {
  loading.value = true
  try {
    const res = await getPlatformItems({
      tenant_id: filterAppId.value,
      status: filterStatus.value,
      keyword: searchQuery.value.trim() || undefined,
      page: pagination.page,
      size: pagination.size,
    })
    const data = res.data || ({} as any)
    rows.value = data.list || []
    total.value = Number(data.total ?? 0)
    pagination.page = Number(data.page ?? pagination.page)
    pagination.size = Number(data.size ?? pagination.size)
  } catch (err: any) {
    toast.error(err.message || t('benefit.action-failed'))
  } finally {
    loading.value = false
  }
}

const formatUpdated = (raw?: string) => {
  if (!raw) return '—'
  const d = new Date(raw)
  if (!isNaN(d.getTime())) {
    const pad = (n: number) => String(n).padStart(2, '0')
    return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`
  }
  return raw
}

const tenantOptions = computed(() => {
  const map = new Map<string, string>()
  rows.value.forEach(i => i.tenant_id && map.set(i.tenant_id, i.tenant_name || i.tenant_id))
  return Array.from(map, ([value, label]) => ({ label, value }))
})

const onRefresh = () => {
  pagination.page = 1
  fetchItems()
}

watch([filterAppId, filterStatus], () => onRefresh())
watch(searchQuery, () => onRefresh())

const onCopy = async (id: string) => {
  const ok = await copySilent(id)
  if (ok) toast.success(t('benefit.copied'))
}

const onCopyAll = async () => {
  const ids = rows.value.map(r => r.id).join('\n')
  const ok = await copySilent(ids)
  if (ok) toast.success(t('benefit.copied-n', { n: rows.value.length }))
  else toast.error(t('benefit.action-failed'))
}

onMounted(() => fetchItems())
</script>

<style scoped lang="scss">
@use '@/styles/benefit-shared' as *;

.platform-items-page {
  display: flex;
  flex-direction: column;
  gap: var(--app-block-mb);
  min-width: 0;
}

@include search-input(240px);
@include cell-id(true);
@include pager-bar();
@include tenant-name();
@include item-name();

.deduction {
  font-weight: 600;
  color: var(--app-primary);
}
.usage-text {
  font-size: 12px;
  color: var(--app-text-tertiary);
  margin-left: 6px;
}
</style>
