<template>
  <div class="app-page tenant-templates-page">
    <FcSectionHeader :title="t('benefit.tenant-templates-title')">
      <template #actions>
        <FcButton @click="onRefresh">
          <i class="ri-refresh-line" /> {{ t('benefit.action-refresh') }}
        </FcButton>
      </template>
    </FcSectionHeader>

    <FcFilterBar>
      <div class="search-wrapper">
        <i class="ri-search-line search-icon" />
        <input
          v-model="searchQuery"
          type="text"
          :placeholder="t('benefit.search-placeholder')"
          class="search-input"
        />
      </div>
    </FcFilterBar>

    <FcSection>
      <el-scrollbar>
        <el-table
          class="fc-table tenant-templates-table"
          :data="filteredTemplates"
          v-loading="loading"
          row-key="id"
          stripe
          :max-height="600"
        >
          <el-table-column :label="t('benefit.template-name')" min-width="180">
            <template #default="{ row }">
              <span class="name-text">{{ row.name }}</span>
            </template>
          </el-table-column>
          <el-table-column :label="t('benefit.duration')" width="140">
            <template #default="{ row }">{{ row.duration ?? '-' }} {{ row.duration_unit }}</template>
          </el-table-column>
          <el-table-column :label="t('benefit.quota')" width="100" align="center">
            <template #default="{ row }">{{ row.quota ?? '-' }}</template>
          </el-table-column>
          <el-table-column :label="t('benefit.refresh-cycle')" width="140">
            <template #default="{ row }">
              {{ row.refresh_cycle ?? '-' }} {{ row.refresh_cycle_unit || '' }}
            </template>
          </el-table-column>
          <el-table-column :label="t('benefit.priority')" width="80" align="center">
            <template #default="{ row }">P{{ row.priority ?? 0 }}</template>
          </el-table-column>
          <el-table-column :label="t('benefit.item-refs')" min-width="200">
            <template #default="{ row }">
              <div v-if="!row.refs?.length" class="refs-empty">-</div>
              <div v-else class="refs-cell">
                <span v-for="r in row.refs.slice(0, 3)" :key="r.item_id" class="ref-chip">
                  {{ r.item_id }}
                </span>
                <FcTooltip v-if="row.refs.length > 3" :content="row.refs.slice(3).map((r: any) => r.item_id).join('\n')">
                  <span class="ref-more">+{{ row.refs.length - 3 }}</span>
                </FcTooltip>
              </div>
            </template>
          </el-table-column>
          <el-table-column :label="t('common.edit')" width="160" fixed="right" align="center">
            <template #default="{ row }">
              <div class="actions-cell">
                <FcTooltip :content="t('common.detail')">
                  <button class="action-icon-btn" @click="showDetail(row)">
                    <i class="ri-information-line" />
                  </button>
                </FcTooltip>
                <FcTooltip :content="t('benefit.create-set')">
                  <button class="action-icon-btn action-icon-accent" @click="openCreateDialog(row)">
                    <i class="ri-add-circle-line" />
                  </button>
                </FcTooltip>
              </div>
            </template>
          </el-table-column>

          <template #empty><FcEmpty /></template>
        </el-table>
      </el-scrollbar>
    </FcSection>

    <!-- 详情对话框 -->
    <FcDialog
      v-model:open="detailVisible"
      :title="t('benefit.template-name') + ': ' + (detailTemplate?.name || '')"
      append-to-body
    >
      <el-descriptions :column="2" border>
        <el-descriptions-item :label="t('benefit.duration')">
          {{ detailTemplate?.duration ?? '-' }} {{ detailTemplate?.duration_unit }}
        </el-descriptions-item>
        <el-descriptions-item :label="t('benefit.quota')">{{ detailTemplate?.quota ?? '-' }}</el-descriptions-item>
        <el-descriptions-item :label="t('benefit.priority')">
          P{{ detailTemplate?.priority ?? 0 }}
        </el-descriptions-item>
        <el-descriptions-item :label="t('benefit.refresh-cycle')">
          {{ detailTemplate?.refresh_cycle ?? '-' }} {{ detailTemplate?.refresh_cycle_unit || '' }}
        </el-descriptions-item>
      </el-descriptions>
      <h4 class="refs-title">{{ t('benefit.item-refs') }}</h4>
      <el-table
        class="fc-table refs-table"
        :data="detailTemplate?.refs || []"
        size="small"
      >
        <el-table-column prop="item_id" :label="t('benefit.item-id')" min-width="180" />
        <el-table-column prop="quota" :label="t('benefit.quota')" width="100" align="center" />
        <el-table-column :label="t('benefit.refresh-cycle')" width="180">
          <template #default="{ row }">
            <span>{{ row.refresh_cycle ?? '-' }} {{ row.refresh_cycle_unit || '' }}</span>
          </template>
        </el-table-column>

        <template #empty><FcEmpty /></template>
      </el-table>
      <template #footer>
        <FcButton type="primary" @click="detailVisible = false">{{ t('common.confirm') }}</FcButton>
      </template>
    </FcDialog>

    <!-- 从模板创建权益包 — 共用权益包编辑器的 UI -->
    <BenefitSetEditor
      v-model:open="createVisible"
      v-model:form="createForm"
      :item-pool="itemPool"
      :title="t('benefit.create-from-template')"
      :loading="createSubmitting"
      :show-status="false"
      @submit="submitCreate"
      @cancel="createVisible = false"
    />
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted, computed } from 'vue'
import { useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { getTenantTemplates } from '@/api/benefitTemplate'
import type { GlobalTemplate } from '@/api/benefitTemplate'
import { createBenefitSet } from '@/api/benefitSet'
import { getBenefitItems } from '@/api/benefitItem'
import type { BenefitItem } from '@/api/benefitItem'
import {
  FcSection, FcSectionHeader, FcFilterBar, FcButton, FcDialog,
  FcTooltip, FcEmpty, toast,
} from '@/components/sdk'
import BenefitSetEditor from './components/BenefitSetEditor.vue'

defineOptions({ name: 'BenefitTenantTemplates' })

const { t } = useI18n()
const router = useRouter()
const loading = ref(false)
const templates = ref<GlobalTemplate[]>([])
const searchQuery = ref('')

const detailVisible = ref(false)
const detailTemplate = ref<GlobalTemplate | null>(null)

const createVisible = ref(false)
const createSubmitting = ref(false)

/** 共享给 BenefitSetEditor 的 item 候选池 (租户侧来自 ubma_benefit_item). */
const itemPool = ref<BenefitItem[]>([])

/** 编辑器内部表单状态 (复用 GlobalTemplate 形态, status 字段隐藏). */
const createForm = ref<GlobalTemplate>({
  name: '', duration: 0, duration_unit: 'month', quota: 0, priority: 0,
  refresh_cycle: 0, refresh_cycle_unit: 'month', refs: [], status: 'ACTIVE',
})

const filteredTemplates = computed(() => {
  let list = templates.value
  if (searchQuery.value) {
    const q = searchQuery.value.toLowerCase()
    list = list.filter(t => (t.name || '').toLowerCase().includes(q))
  }
  return list
})

const fetchTemplates = async () => {
  loading.value = true
  try {
    const res = await getTenantTemplates()
    templates.value = (res.data || [])
  } catch (err: any) {
    toast.error(err.message || t('benefit.action-failed'))
  } finally {
    loading.value = false
  }
}

const fetchItemPool = async () => {
  if (itemPool.value.length) return
  try {
    const res = await getBenefitItems()
    itemPool.value = res.data || []
  } catch (err: any) {
    toast.error(err.message || t('benefit.action-failed'))
  }
}

const onRefresh = () => fetchTemplates()

const showDetail = (row: GlobalTemplate) => {
  detailTemplate.value = row
  detailVisible.value = true
}

const openCreateDialog = (row: GlobalTemplate) => {
  createForm.value = {
    name: row.name,
    duration: row.duration || 0,
    duration_unit: row.duration_unit || 'month',
    quota: row.quota || 0,
    priority: row.priority || 0,
    refresh_cycle: row.refresh_cycle || 0,
    refresh_cycle_unit: row.refresh_cycle_unit || 'month',
    refs: (row.refs || []).map(r => ({ ...r })),
    status: 'ACTIVE',
  }
  createVisible.value = true
  fetchItemPool()
}

const submitCreate = async () => {
  createSubmitting.value = true
  try {
    // 后端 /tenant/benefit-sets 接口字段稍简, status/refresh_cycle 不上送 (后端默认 ACTIVE)
    await createBenefitSet({
      name: createForm.value.name,
      duration: createForm.value.duration,
      duration_unit: createForm.value.duration_unit,
      quota: createForm.value.quota,
      priority: createForm.value.priority,
      items: (createForm.value.refs || []).map(r => ({
        item_id: r.item_id,
        quota: r.quota,
        refresh_cycle: r.refresh_cycle,
        refresh_cycle_unit: r.refresh_cycle_unit,
      })),
    } as any)
    toast.success(t('benefit.create-success'))
    createVisible.value = false
    router.push('/benefit/tenant/app/sets')
  } catch (err: any) {
    toast.error(err.message || t('benefit.action-failed'))
  } finally {
    createSubmitting.value = false
  }
}

onMounted(() => { fetchTemplates() })
</script>

<style scoped lang="scss">
@use '@/styles/benefit-shared' as *;

.tenant-templates-page {
  display: flex;
  flex-direction: column;
  gap: var(--app-block-mb);
  min-width: 0;
}

@include search-input(280px);
@include action-buttons();

.name-text { font-weight: 500; color: var(--app-text); }

.action-icon-primary:hover {
  background: color-mix(in srgb, var(--app-primary, var(--el-color-primary)) 12%, transparent);
  color: var(--app-primary, var(--el-color-primary));
}
.action-icon-accent:hover {
  background: color-mix(in srgb, #1ec0a8 12%, transparent);
  color: #1ec0a8;
}

.refs-title {
  margin: 16px 0 8px;
  font-size: 14px;
  font-weight: 600;
  color: var(--app-text);
}

.refs-empty {
  color: var(--app-text-tertiary);
  text-align: center;
}
.refs-cell {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
  align-items: center;
}
.ref-chip {
  display: inline-flex;
  align-items: center;
  padding: 2px 8px;
  background: var(--app-bg-muted, #f5f5f7);
  border-radius: 10px;
  font-size: 12px;
  color: var(--app-text);
  font-family: var(--app-font-mono, monospace);
}
.ref-more {
  font-size: 12px;
  color: var(--app-text-tertiary);
  cursor: default;
}
</style>