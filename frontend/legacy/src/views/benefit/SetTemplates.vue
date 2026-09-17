<template>
  <div class="app-page set-templates-page">
    <FcSectionHeader
      :title="t('benefit.set-templates-title')"
      :subtitle="t('benefit.set-templates-subtitle')"
    >
      <template #actions>
        <div class="search-wrapper">
          <i class="ri-search-line search-icon" />
          <input
            v-model="searchQuery"
            type="text"
            :placeholder="t('benefit.search-placeholder')"
            class="search-input"
          />
        </div>
        <FcButton variant="secondary" @click="onRefresh">
          <i class="ri-refresh-line" /> {{ t('benefit.action-refresh') }}
        </FcButton>
        <FcButton type="primary" @click="openDialog(null)">
          <i class="ri-add-line" /> {{ t('common.create') }}
        </FcButton>
      </template>
    </FcSectionHeader>

    <FcFilterBar>
      <FcFilterButton
        :active="filterStatus === undefined"
        @click="filterStatus = undefined"
      >
        {{ t('common.filter-all') }}
      </FcFilterButton>
      <FcFilterButton
        :active="filterStatus === 'ACTIVE'"
        @click="filterStatus = 'ACTIVE'"
      >
        {{ t('benefit.active') }}
      </FcFilterButton>
      <FcFilterButton
        :active="filterStatus === 'INACTIVE'"
        @click="filterStatus = 'INACTIVE'"
      >
        {{ t('benefit.inactive') }}
      </FcFilterButton>
    </FcFilterBar>

    <FcSection>
      <el-scrollbar>
        <el-table
          class="fc-table set-templates-table"
          :data="filteredTemplates"
          v-loading="loading"
          row-key="id"
          stripe
          highlight-current-row
          :max-height="600"
        >
          <el-table-column label="ID" width="180">
            <template #default="{ row }">
              <code class="cell-id" @click="onCopy(row.id)">{{ row.id }}</code>
            </template>
          </el-table-column>

          <el-table-column :label="t('benefit.template-name')" min-width="220">
            <template #default="{ row }">
              <div class="cell-name">
                <div class="cell-name-icon"><i class="ri-file-list-3-line" /></div>
                <div class="cell-name-info">
                  <div class="cell-name-title">{{ row.name }}</div>
                  <div class="cell-name-sub">{{ t('benefit.duration') }} · {{ row.duration ?? '-' }} {{ row.duration_unit }}</div>
                </div>
              </div>
            </template>
          </el-table-column>

          <el-table-column :label="t('benefit.quota')" width="100" align="center">
            <template #default="{ row }"><span class="num-cell">{{ row.quota }}</span></template>
          </el-table-column>

          <el-table-column :label="t('benefit.priority')" width="100" align="center">
            <template #default="{ row }"><span class="num-cell">P{{ row.priority ?? 0 }}</span></template>
          </el-table-column>

          <el-table-column :label="t('benefit.item-refs')" width="120" align="center">
            <template #default="{ row }">
              <span class="ref-count-badge" :class="{ 'is-empty': !row.refs?.length }">
                <i class="ri-stack-line" /> {{ row.refs?.length ?? 0 }}
              </span>
            </template>
          </el-table-column>

          <el-table-column :label="t('benefit.status')" width="120">
            <template #default="{ row }">
              <FcTag :color="row.status === 'ACTIVE' ? 'success' : 'gray'" size="sm">
                {{ row.status === 'ACTIVE' ? t('benefit.active') : t('benefit.inactive') }}
              </FcTag>
            </template>
          </el-table-column>

          <el-table-column :label="t('common.more')" width="120" fixed="right" align="center">
            <template #default="{ row }">
              <el-dropdown trigger="click" @command="(c: string) => onRowAction(c, row)">
                <FcButton variant="text" size="small">
                  {{ t('common.more') }} <i class="ri-arrow-down-s-line" />
                </FcButton>
                <template #dropdown>
                  <el-dropdown-menu>
                    <el-dropdown-item command="edit">
                      <i class="ri-edit-line" /> {{ t('common.edit') }}
                    </el-dropdown-item>
                    <el-dropdown-item command="duplicate">
                      <i class="ri-file-copy-line" /> {{ t('benefit.action-duplicate') }}
                    </el-dropdown-item>
                    <el-dropdown-item command="delete" divided>
                      <i class="ri-delete-bin-line" /> {{ t('common.delete') }}
                    </el-dropdown-item>
                  </el-dropdown-menu>
                </template>
              </el-dropdown>
            </template>
          </el-table-column>

          <template #empty><FcEmpty /></template>
        </el-table>
      </el-scrollbar>
    </FcSection>

    <BenefitSetEditor
      v-model:open="dialogVisible"
      v-model:form="form"
      :item-pool="itemPool"
      :title="editingId ? t('benefit.edit-template') : t('benefit.create-template')"
      :loading="submitting"
      :show-status="true"
      @submit="submitForm"
      @cancel="dialogVisible = false"
    />
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted, computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { getTemplates, createTemplate, updateTemplate, deleteTemplate } from '@/api/benefitTemplate'
import { getItemTemplates } from '@/api/benefitItemTemplate'
import type { GlobalTemplate, ItemTemplate } from '@/types/benefit'
import {
  FcButton, FcSection, FcSectionHeader,
  FcFilterBar, FcFilterButton,
  FcTag, FcEmpty, toast,
} from '@/components/sdk'
import { useClipboard } from '@/composables'
import { ElMessageBox, ElDropdown, ElDropdownMenu, ElDropdownItem } from 'element-plus'
import BenefitSetEditor from './components/BenefitSetEditor.vue'

const { t } = useI18n()
const { copy } = useClipboard()

const loading = ref(false)
const templates = ref<GlobalTemplate[]>([])
const searchQuery = ref('')
const filterStatus = ref<'ACTIVE' | 'INACTIVE' | undefined>()

const filteredTemplates = computed(() => {
  let rows = templates.value
  if (filterStatus.value) rows = rows.filter(r => r.status === filterStatus.value)
  if (searchQuery.value) {
    const q = searchQuery.value.toLowerCase()
    rows = rows.filter(r => (r.name || '').toLowerCase().includes(q))
  }
  return rows
})

const itemPool = ref<ItemTemplate[]>([])

const dialogVisible = ref(false)
const submitting = ref(false)
const editingId = ref<string | null>(null)
const form = ref<GlobalTemplate>({
  name: '', duration: 0, duration_unit: 'month', quota: 0, priority: 0,
  refresh_cycle: 0, refresh_cycle_unit: 'month', refs: [], status: 'ACTIVE',
})

const fetchTemplates = async () => {
  loading.value = true
  try {
    const res = await getTemplates()
    templates.value = res.data
  } catch (err: any) {
    toast.error(err.message || t('benefit.action-failed'))
  } finally {
    loading.value = false
  }
}

const fetchItemPool = async () => {
  if (itemPool.value.length) return
  try {
    const res = await getItemTemplates()
    itemPool.value = res.data || []
  } catch (err: any) {
    toast.error(err.message || t('benefit.action-failed'))
  }
}

const onRefresh = () => fetchTemplates()

const openDialog = (row: GlobalTemplate | null) => {
  if (row) {
    editingId.value = row.id!
    form.value = { ...row, refs: row.refs?.map(r => ({ ...r })) || [] }
  } else {
    editingId.value = null
    form.value = { name: '', duration: 0, duration_unit: 'month', quota: 0, priority: 0, refresh_cycle: 0, refresh_cycle_unit: 'month', refs: [], status: 'ACTIVE' }
  }
  dialogVisible.value = true
  fetchItemPool()
}

const submitForm = async () => {
  submitting.value = true
  try {
    if (editingId.value) {
      await updateTemplate(editingId.value, form.value)
      toast.success(t('benefit.update-success'))
    } else {
      await createTemplate(form.value)
      toast.success(t('benefit.create-success'))
    }
    dialogVisible.value = false
    fetchTemplates()
  } catch (err: any) {
    toast.error(err.message || t('benefit.action-failed'))
  } finally {
    submitting.value = false
  }
}

const handleDelete = async (id: string) => {
  try {
    await ElMessageBox.confirm(t('benefit.delete-confirm'))
    await deleteTemplate(id)
    toast.success(t('benefit.delete-success'))
    fetchTemplates()
  } catch (e) { /* cancelled */ }
}

const onRowAction = (cmd: string, row: GlobalTemplate) => {
  if (cmd === 'edit') openDialog(row)
  else if (cmd === 'delete') handleDelete(row.id!)
  else if (cmd === 'duplicate') {
    const clone = { ...row, id: undefined, name: row.name + ' (副本)', refs: row.refs?.map(r => ({ ...r })) || [] }
    openDialog(clone)
  }
}

const onCopy = (id: string) => copy(id)

onMounted(() => { fetchTemplates() })
</script>

<style scoped lang="scss">
@use '@/styles/benefit-shared' as *;

.set-templates-page {
  display: flex;
  flex-direction: column;
  gap: var(--app-block-mb);
  min-width: 0;
}

@include search-input(280px);
@include cell-id(true);

.cell-name {
  display: flex;
  align-items: center;
  gap: 10px;
}
.cell-name-icon {
  width: 36px;
  height: 36px;
  border-radius: 8px;
  background: linear-gradient(135deg, var(--el-color-primary-light-9), var(--el-color-primary-light-7));
  color: var(--app-primary);
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 18px;
  flex-shrink: 0;
}
.cell-name-title {
  font-weight: 600;
  color: var(--app-text);
  font-size: 14px;
}
.cell-name-sub {
  font-size: 12px;
  color: var(--app-text-tertiary);
  margin-top: 2px;
}

.num-cell {
  font-variant-numeric: tabular-nums;
  font-weight: 500;
  color: var(--app-text);
}

.ref-count-badge {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 2px 10px;
  background: var(--el-color-primary-light-9);
  color: var(--app-primary);
  border-radius: 999px;
  font-size: 12px;
  font-weight: 600;
}
.ref-count-badge.is-empty {
  background: var(--app-bg-muted, #f5f5f7);
  color: var(--app-text-tertiary);
}
</style>