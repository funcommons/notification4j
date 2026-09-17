<template>
  <div class="app-page items-page">
    <FcSectionHeader :title="t('benefit.items-title')">
      <template #actions>
        <FcButton type="primary" @click="openDialog(null)">
          <i class="ri-add-line" /> {{ t('common.create') }}
        </FcButton>
      </template>
    </FcSectionHeader>

    <FcFormDraftBanner
      :visible="draft.hasDraft.value"
      @restore="onRestoreDraft"
      @discard="draft.discard()"
    />

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
          class="fc-table items-table"
          :data="filteredItems"
          v-loading="loading"
          row-key="id"
          stripe
          highlight-current-row
          :max-height="600"
        >
          <el-table-column label="ID" width="180">
            <template #default="{ row }">
              <code class="cell-id" @click="onCopyId(row.id)">{{ row.id }}</code>
            </template>
          </el-table-column>
          <el-table-column :label="t('benefit.item-name')" min-width="160">
            <template #default="{ row }">
              <span class="item-name">
                <i :class="row.icon" />
                {{ row.name }}
              </span>
            </template>
          </el-table-column>
          <el-table-column :label="t('benefit.icon')" width="120">
            <template #default="{ row }">{{ row.icon }}</template>
          </el-table-column>
          <el-table-column :label="t('benefit.description')" min-width="200" show-overflow-tooltip>
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

    <FcDialog
      v-model:open="dialogVisible"
      :title="editingId ? t('common.edit') : t('common.create')"
      :close-on-press-escape="!dirty.isDirty.value"
      append-to-body
    >
      <el-form ref="formRef" :model="form" :rules="rules" label-width="120px">
        <el-form-item class="fc-form-item" :label="t('benefit.item-name')" prop="name">
          <el-input class="fc-input" v-model="form.name" />
        </el-form-item>
        <el-form-item class="fc-form-item" :label="t('benefit.icon')">
          <el-input class="fc-input" v-model="form.icon" />
        </el-form-item>
        <el-form-item class="fc-form-item" :label="t('benefit.description')">
          <el-input class="fc-input" v-model="form.description" type="textarea" :rows="2" />
        </el-form-item>
        <el-form-item class="fc-form-item" :label="t('benefit.default-deduction')">
          <el-input-number v-model="form.default_deduction" :min="0" />
        </el-form-item>
      </el-form>
      <template #footer>
        <FcButton @click="closeDialog">{{ t('common.cancel') }}</FcButton>
        <FcButton
          type="primary"
          :loading="submit.loading.value"
          :disabled="submit.loading.value"
          @click="onSubmit"
        >
          <i class="ri-check-line" /> {{ t('common.confirm') }}
        </FcButton>
      </template>
    </FcDialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, onUnmounted, reactive, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { getBenefitItems, createBenefitItem, updateBenefitItem, deleteBenefitItem } from '@/api/benefitItem'
import type { BenefitItem } from '@/api/benefitItem'
import {
  FcButton, FcDialog, FcSection, FcSectionHeader, FcFilterBar,
  FcTag, FcTooltip, FcEmpty, FcFormDraftBanner, toast,
} from '@/components/sdk'
import {
  useDirtyForm,
  useFormDraft,
  useIdempotentSubmit,
  useRecentList,
  openCommandPalette,
  registerCommands,
  unregisterCommands,
  type CommandItem,
} from '@/composables'
import { useClipboard } from '@/composables'
import { ElMessageBox, type FormInstance, type FormRules } from 'element-plus'

defineOptions({ name: 'BenefitItems' })

const { t } = useI18n()
const { copy } = useClipboard()

const loading = ref(false)
const items = ref<BenefitItem[]>([])
const searchQuery = ref('')

const filteredItems = computed(() => {
  if (!searchQuery.value) return items.value
  const q = searchQuery.value.toLowerCase()
  return items.value.filter(i => (i.name || '').toLowerCase().includes(q))
})

const dialogVisible = ref(false)
const editingId = ref<string | null>(null)
const formRef = ref<FormInstance>()
const initialForm = (): BenefitItem => ({ name: '', icon: '', description: '', default_deduction: 0 })
const form = reactive<BenefitItem>(initialForm())

const rules: FormRules = {
  name: [{ required: true, message: t('benefit.name-required'), trigger: 'blur' }],
}

const draft = useFormDraft(form, 'benefit-items:form')
const dirty = useDirtyForm(form, () => initialForm())

const submit = useIdempotentSubmit(async () => {
  if (!formRef.value) return
  try {
    await formRef.value.validate()
  } catch {
    return
  }
  if (editingId.value) {
    await updateBenefitItem(editingId.value, form)
    toast.success(t('benefit.update-success'))
  } else {
    await createBenefitItem(form)
    toast.success(t('benefit.create-success'))
  }
  dirty.markClean()
  draft.discard()
  dialogVisible.value = false
  await fetchItems()
})

const recent = useRecentList<BenefitItem>({ key: 'benefit-items' })

const fetchItems = async () => {
  loading.value = true
  try {
    const res = await getBenefitItems()
    items.value = res.data
  } catch (err: any) {
    toast.error(err.message || t('benefit.action-failed'))
  } finally {
    loading.value = false
  }
}

const openDialog = (row: BenefitItem | null) => {
  if (row) {
    editingId.value = row.id!
    Object.assign(form, row)
    recent.pick(row)
  } else {
    editingId.value = null
    Object.assign(form, initialForm())
  }
  dialogVisible.value = true
}

const closeDialog = () => {
  dialogVisible.value = false
}

const onSubmit = async () => {
  try {
    await submit()
  } catch (err: any) {
    if (err?.code === 10501) {
      toast.warning(t('ux.error.DUPLICATE_SUBMISSION'))
    } else if (err?.message) {
      toast.error(err.message)
    }
  }
}

const onRestoreDraft = () => {
  if (draft.restore()) {
    toast.success(t('ux.draft.restored'))
  }
  draft.discard()
}

const handleDelete = async (id: string, name: string) => {
  try {
    await ElMessageBox.confirm(`${t('benefit.delete-confirm')} "${name}"`)
    await deleteBenefitItem(id)
    toast.success(t('benefit.delete-success'))
    await fetchItems()
  } catch (e) { /* cancelled */ }
}

const onCopyId = (id: string) => copy(id)

const commands: CommandItem[] = [
  {
    id: 'items.new',
    label: '新建权益项',
    labelKey: 'benefit.create',
    icon: 'ri-add-line',
    group: '操作',
    shortcut: 'N',
    keywords: ['new', 'create', 'add'],
    handler: () => openDialog(null),
  },
  {
    id: 'items.refresh',
    label: '刷新列表',
    icon: 'ri-refresh-line',
    group: '操作',
    shortcut: 'R',
    keywords: ['refresh', 'reload'],
    handler: () => fetchItems(),
  },
  {
    id: 'items.copy-id',
    label: '复制当前选中 ID',
    icon: 'ri-file-copy-line',
    group: '操作',
    keywords: ['copy', 'id'],
    handler: () => copy(editingId.value ?? ''),
  },
  {
    id: 'palette.open',
    label: '打开命令面板',
    labelKey: 'ux.shortcut.palette',
    icon: 'ri-command-line',
    group: '导航',
    shortcut: 'Cmd+K',
    keywords: ['palette', 'command'],
    handler: () => openCommandPalette(),
  },
]

registerCommands(commands)

onMounted(() => { fetchItems() })

onUnmounted(() => {
  unregisterCommands(commands.map(c => c.id))
})
</script>

<style scoped lang="scss">
@use '@/styles/benefit-shared' as *;

.items-page {
  display: flex;
  flex-direction: column;
  gap: var(--app-block-mb);
  min-width: 0;
}

@include search-input(280px);
@include cell-id(true);
@include item-name();
@include action-buttons();
</style>