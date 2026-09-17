<template>
  <div class="app-page item-templates-page">
    <FcSectionHeader
      :title="t('benefit.item-templates-title')"
      :subtitle="t('benefit.item-templates-subtitle')"
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
        <FcButton type="primary" @click="openDialog(null)">
          <i class="ri-add-line" /> {{ t('common.create') }}
        </FcButton>
      </template>
    </FcSectionHeader>

    <FcSection>
      <el-scrollbar>
        <el-table
          class="fc-table item-templates-table"
          :data="filteredTemplates"
          v-loading="loading"
          row-key="id"
          stripe
          highlight-current-row
          :max-height="600"
        >
          <el-table-column label="ID" width="160">
            <template #default="{ row }">
              <code class="cell-id" @click="onCopy(row.id)">{{ row.id }}</code>
            </template>
          </el-table-column>

          <el-table-column :label="t('benefit.item-name')" min-width="200">
            <template #default="{ row }">
              <span class="item-name">
                <i :class="row.icon" />
                {{ row.name }}
              </span>
            </template>
          </el-table-column>

          <el-table-column :label="t('benefit.description')" min-width="240" show-overflow-tooltip>
            <template #default="{ row }">{{ row.description }}</template>
          </el-table-column>

          <el-table-column :label="t('benefit.default-deduction')" width="140" align="center">
            <template #default="{ row }">
              <FcTag v-if="(row.default_deduction ?? 0) === 0" color="gray" size="sm">
                {{ t('benefit.deduction-weighted') }}
              </FcTag>
              <FcTag v-else color="warning" size="sm">
                {{ t('benefit.deduction-metered', { n: row.default_deduction }) }}
              </FcTag>
            </template>
          </el-table-column>

          <el-table-column :label="t('benefit.used-by')" width="120" align="center">
            <template #default="{ row }">
              <span class="usage-pill" :title="t('benefit.used-by-todo')">{{ row.used_by }} {{ t('benefit.tenants') }}</span>
            </template>
          </el-table-column>

          <el-table-column :label="t('benefit.status')" width="120">
            <template #default="{ row }">
              <FcTag :color="row.status === 'ACTIVE' ? 'success' : 'gray'" size="sm">
                {{ row.status === 'ACTIVE' ? t('benefit.active') : t('benefit.inactive') }}
              </FcTag>
            </template>
          </el-table-column>

          <el-table-column :label="t('common.edit')" width="120" fixed="right" align="center">
            <template #default="{ row }">
              <div class="actions-cell">
                <FcTooltip :content="t('common.edit')">
                  <button class="action-icon-btn" @click="openDialog(row)">
                    <i class="ri-edit-line" />
                  </button>
                </FcTooltip>
                <FcTooltip :content="t('common.delete')">
                  <button class="action-icon-btn action-icon-danger" @click="handleDelete(row.id, row.name)">
                    <i class="ri-delete-bin-line" />
                  </button>
                </FcTooltip>
              </div>
            </template>
          </el-table-column>

          <template #empty><FcEmpty /></template>
        </el-table>
      </el-scrollbar>
    </FcSection>

    <!-- 创建/编辑权益项模板 -->
    <FcDialog v-model:open="dialogVisible" :title="editingId ? t('common.edit') : t('common.create')" append-to-body>
      <el-form ref="formRef" :model="form" :rules="rules" label-width="130px">
        <el-form-item class="fc-form-item" :label="t('benefit.item-name')" prop="name">
          <el-input class="fc-input" v-model="form.name" placeholder="免邮特权" />
        </el-form-item>
        <el-form-item class="fc-form-item" :label="t('benefit.icon')">
          <el-input class="fc-input" v-model="form.icon" placeholder="ri-xxx-line" />
        </el-form-item>
        <el-form-item class="fc-form-item" :label="t('benefit.description')">
          <el-input class="fc-input" v-model="form.description" placeholder="每月 4 次免邮特权" />
        </el-form-item>
        <el-form-item class="fc-form-item" :label="t('benefit.default-deduction')">
          <el-input-number v-model="form.default_deduction" :min="0" />
        </el-form-item>
        <el-form-item class="fc-form-item" :label="t('benefit.status')">
          <FcSwitch
            v-model="statusActive"
            :active-text="t('benefit.active')"
            :inactive-text="t('benefit.inactive')"
            inline-prompt
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <FcButton @click="dialogVisible = false">{{ t('common.cancel') }}</FcButton>
        <FcButton type="primary" :loading="submitting" @click="submitForm">
          <i class="ri-check-line" /> {{ t('common.confirm') }}
        </FcButton>
      </template>
    </FcDialog>
  </div>
</template>

<script setup lang="ts">
import { computed, reactive, ref, onMounted } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import {
  FcButton, FcDialog, FcSection, FcSectionHeader,
  FcTag, FcTooltip, FcEmpty, FcSwitch, toast,
} from '@/components/sdk'
import { useClipboard } from '@/composables'
import { getItemTemplates, createItemTemplate, updateItemTemplate, deleteItemTemplate } from '@/api/benefitItemTemplate'
import type { ItemTemplate as ApiItemTemplate } from '@/api/benefitItemTemplate'

defineOptions({ name: 'ItemTemplates' })

const { t } = useI18n()
const { copy } = useClipboard()

interface ItemTemplate extends ApiItemTemplate {
  // TODO used_by: 前端派生统计字段，待后端接口补齐后改为真实数据
  used_by: number
}

const templates = ref<ItemTemplate[]>([])
const loading = ref(false)

const fetchTemplates = async () => {
  loading.value = true
  try {
    const res = await getItemTemplates()
    templates.value = (res.data || []).map((r: ApiItemTemplate) => ({
      id: r.id,
      name: r.name || '',
      icon: r.icon || '',
      description: r.description || '',
      default_deduction: r.default_deduction ?? 1,
      status: (r.status === 'INACTIVE' ? 'INACTIVE' : 'ACTIVE'),
      used_by: 0,
    }))
  } catch (err: any) {
    toast.error(err.message || t('benefit.action-failed'))
  } finally {
    loading.value = false
  }
}

const searchQuery = ref('')

const filteredTemplates = computed(() => {
  let rows = templates.value
  if (searchQuery.value) {
    const q = searchQuery.value.toLowerCase()
    rows = rows.filter(r => (r.name || '').toLowerCase().includes(q) || (r.description || '').toLowerCase().includes(q))
  }
  return rows
})

const dialogVisible = ref(false)
const submitting = ref(false)
const editingId = ref<string | null>(null)
const formRef = ref<FormInstance>()
const initialForm = (): ItemTemplate => ({ id: undefined, name: '', icon: '', description: '', default_deduction: 1, status: 'ACTIVE', used_by: 0 })
const form = reactive<ItemTemplate>(initialForm())

const statusActive = computed({
  get: () => form.status !== 'INACTIVE',
  set: (v) => { form.status = v ? 'ACTIVE' : 'INACTIVE' },
})

const rules: FormRules = {
  name: [{ required: true, message: t('benefit.name-required'), trigger: 'blur' }],
}

const openDialog = (row: ItemTemplate | null) => {
  if (row) {
    editingId.value = row.id ?? null
    Object.assign(form, row)
  } else {
    editingId.value = null
    Object.assign(form, initialForm())
  }
  dialogVisible.value = true
}

const submitForm = async () => {
  if (!formRef.value) return
  try { await formRef.value.validate() } catch { return }
  submitting.value = true
  try {
    const payload = {
      name: form.name,
      icon: form.icon,
      description: form.description,
      default_deduction: form.default_deduction,
      status: form.status,
    }
    if (editingId.value) {
      await updateItemTemplate(editingId.value, payload)
      toast.success(t('benefit.update-success'))
    } else {
      await createItemTemplate(payload)
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

const handleDelete = async (id: string | undefined, name: string | undefined) => {
  if (!id) return
  try {
    await ElMessageBox.confirm(`${t('benefit.delete-confirm')} "${name || id}"`)
    await deleteItemTemplate(id)
    toast.success(t('benefit.delete-success'))
    fetchTemplates()
  } catch (e) { /* cancelled */ }
}

const onCopy = (id: string | undefined) => { if (id) copy(id) }

onMounted(() => { fetchTemplates() })
</script>

<style scoped lang="scss">
@use '@/styles/benefit-shared' as *;

.item-templates-page {
  display: flex;
  flex-direction: column;
  gap: var(--app-block-mb);
  min-width: 0;
}

@include search-input(280px);
@include cell-id(true);
@include item-name();
@include action-buttons();

.usage-pill {
  display: inline-block;
  padding: 2px 8px;
  background: var(--app-bg-muted, #f5f5f7);
  border-radius: 10px;
  font-size: 12px;
  font-weight: 500;
  color: var(--app-text-secondary);
}
</style>