<template>
  <FcDialog
    :model-value="open"
    :title="title"
    width="1200px"
    class="composer-dialog"
    append-to-body
    @update:model-value="$emit('update:open', $event)"
  >
    <template v-if="open">
      <FcTabsPanel v-model="activeTab" :tabs="tabs" class="composer-tabs">
        <!-- Tab 1: 基础信息 -->
        <template #tab-basics>
          <el-form ref="formRef" :model="formProxy" :rules="rules" label-width="120px" class="basics-form">
            <el-form-item class="fc-form-item" :label="t('benefit.template-name')" prop="name">
              <el-input
                class="fc-input"
                :model-value="form.name"
                :disabled="readonly"
                :placeholder="t('benefit.template-name-placeholder')"
                @update:model-value="updateField('name', $event)"
              />
            </el-form-item>
            <el-row :gutter="16">
              <el-col :span="12">
                <el-form-item class="fc-form-item" :label="t('benefit.duration')">
                  <el-input-number
                    :model-value="form.duration"
                    :min="0"
                    :disabled="readonly"
                    style="width: 100%"
                    @update:model-value="updateField('duration', $event ?? 0)"
                  />
                </el-form-item>
              </el-col>
              <el-col :span="12">
                <el-form-item class="fc-form-item" :label="t('benefit.duration-unit')">
                  <FcSelect
                    :model-value="form.duration_unit"
                    :options="durationUnitOptions"
                    :disabled="readonly"
                    style="width: 100%"
                    @update:model-value="updateField('duration_unit', $event)"
                  />
                </el-form-item>
              </el-col>
            </el-row>
            <el-row :gutter="16">
              <el-col :span="12">
                <el-form-item class="fc-form-item" :label="t('benefit.quota')">
                  <el-input-number
                    :model-value="form.quota"
                    :min="0"
                    :disabled="readonly"
                    style="width: 100%"
                    @update:model-value="updateField('quota', $event ?? 0)"
                  />
                </el-form-item>
              </el-col>
              <el-col :span="12">
                <el-form-item class="fc-form-item" :label="t('benefit.priority')">
                  <el-input-number
                    :model-value="form.priority"
                    :min="0"
                    :disabled="readonly"
                    style="width: 100%"
                    @update:model-value="updateField('priority', $event ?? 0)"
                  />
                </el-form-item>
              </el-col>
            </el-row>
            <el-row :gutter="16">
              <el-col :span="12">
                <el-form-item class="fc-form-item" :label="t('benefit.refresh-cycle')">
                  <el-input-number
                    :model-value="form.refresh_cycle"
                    :min="0"
                    :disabled="readonly"
                    style="width: 100%"
                    @update:model-value="updateField('refresh_cycle', $event ?? 0)"
                  />
                </el-form-item>
              </el-col>
              <el-col :span="12">
                <el-form-item class="fc-form-item" :label="t('benefit.refresh-cycle-unit')">
                  <FcSelect
                    :model-value="form.refresh_cycle_unit"
                    :options="refreshCycleUnitOptions"
                    :disabled="readonly"
                    style="width: 100%"
                    @update:model-value="updateField('refresh_cycle_unit', $event)"
                  />
                </el-form-item>
              </el-col>
            </el-row>
            <el-form-item v-if="showStatus" class="fc-form-item" :label="t('benefit.status')">
              <FcSwitch
                v-model="statusActive"
                :disabled="readonly"
                :active-text="t('benefit.active')"
                :inactive-text="t('benefit.inactive')"
                inline-prompt
              />
            </el-form-item>
          </el-form>
        </template>

        <!-- Tab 2: 权益项组合 -->
        <template #tab-compose>
          <div class="composer">
            <!-- 左: 候选池 -->
            <div class="composer-side">
              <div class="composer-side-header">
                <span class="composer-side-title">{{ t('benefit.composer-pool-title') }}</span>
                <span class="composer-side-count">{{ availableItems.length }}</span>
              </div>
              <div class="composer-pool-search">
                <i class="ri-search-line composer-pool-search-icon" />
                <input
                  v-model="poolSearch"
                  type="text"
                  :placeholder="t('benefit.composer-pool-search')"
                  class="composer-pool-search-input"
                />
              </div>
              <div class="composer-pool">
                <div
                  v-for="item in filteredPoolItems"
                  :key="item.id"
                  class="pool-card"
                  :class="{ 'is-selected': pickerSelection.includes(item.id), 'is-readonly': readonly }"
                  @click="readonly ? null : togglePick(item.id)"
                >
                  <i :class="item.icon" class="pool-card-icon" />
                  <div class="pool-card-info">
                    <div class="pool-card-name">{{ item.name }}</div>
                    <div class="pool-card-meta">
                      <FcTag
                        v-if="(item.defaultDeduction ?? 0) === 0"
                        color="gray"
                        size="sm"
                      >
                        {{ t('benefit.deduction-weighted') }}
                      </FcTag>
                      <FcTag
                        v-else
                        color="warning"
                        size="sm"
                      >
                        {{ t('benefit.deduction-metered', { n: item.defaultDeduction }) }}
                      </FcTag>
                    </div>
                  </div>
                  <div class="pool-card-check">
                    <i :class="pickerSelection.includes(item.id) ? 'ri-checkbox-circle-fill' : 'ri-add-circle-line'" />
                  </div>
                </div>
                <div v-if="!filteredPoolItems.length" class="pool-empty">
                  <i class="ri-search-eye-line" />
                  <span>{{ t('benefit.composer-pool-empty') }}</span>
                </div>
              </div>
            </div>

            <!-- 右: 选中区 -->
            <div class="composer-main">
              <div class="composer-main-header">
                <div class="composer-main-title">
                  <span>{{ t('benefit.refs-selected', { n: form.refs?.length ?? 0 }) }}</span>
                </div>
                <FcButton
                  v-if="form.refs?.length && !readonly"
                  variant="text"
                  size="small"
                  @click="clearRefs"
                >
                  <i class="ri-delete-bin-line" /> {{ t('benefit.refs-clear') }}
                </FcButton>
              </div>

              <div v-if="!form.refs?.length" class="composer-empty">
                <i class="ri-inbox-archive-line" />
                <p class="composer-empty-title">{{ t('benefit.composer-empty-title') }}</p>
                <p class="composer-empty-hint">{{ t('benefit.composer-empty-hint') }}</p>
              </div>

              <div v-else class="composer-list">
                <div
                  v-for="(ref, idx) in form.refs"
                  :key="ref.item_id || idx"
                  class="compose-card"
                  :draggable="!readonly"
                  @dragstart="readonly ? null : onDragStart(idx)"
                  @dragover.prevent
                  @drop="readonly ? null : onDrop(idx)"
                >
                  <div class="compose-card-drag"><i class="ri-draggable" /></div>
                  <i :class="iconOf(ref.item_id)" class="compose-card-icon" />
                  <div class="compose-card-info">
                    <div class="compose-card-name">{{ nameOf(ref.item_id) }}</div>
                    <FcTag
                      v-if="deductionOf(ref.item_id) > 0"
                      class="compose-card-name-tag"
                      color="warning"
                      size="sm"
                    >
                      {{ t('benefit.deduction-metered', { n: deductionOf(ref.item_id) }) }}
                    </FcTag>
                  </div>
                  <div v-if="deductionOf(ref.item_id) > 0" class="compose-card-fields">
                    <div class="compose-card-field">
                      <label class="compose-card-label">{{ t('benefit.item-quota') }}</label>
                      <el-input-number
                        :model-value="ref.quota"
                        :min="0"
                        :disabled="readonly"
                        controls-position="right"
                        size="small"
                        @update:model-value="updateRefField(idx, 'quota', $event ?? 0)"
                      />
                    </div>
                    <div v-if="(Number(ref.quota) || 0) > 0" class="compose-card-field">
                      <label class="compose-card-label">{{ t('benefit.refresh-cycle') }}</label>
                      <el-input-number
                        :model-value="ref.refresh_cycle"
                        :min="0"
                        :disabled="readonly"
                        controls-position="right"
                        size="small"
                        @update:model-value="updateRefField(idx, 'refresh_cycle', $event ?? 0)"
                      />
                    </div>
                    <div v-if="(Number(ref.quota) || 0) > 0 && (Number(ref.refresh_cycle) || 0) > 0" class="compose-card-field">
                      <label class="compose-card-label">{{ t('benefit.refresh-cycle-unit') }}</label>
                      <FcSelect
                        :model-value="ref.refresh_cycle_unit"
                        :options="refreshCycleUnitOptions"
                        :disabled="readonly"
                        size="small"
                        style="width: 110px"
                        @update:model-value="updateRefField(idx, 'refresh_cycle_unit', $event)"
                      />
                    </div>
                  </div>
                  <FcButton v-if="!readonly" variant="text" size="small" @click="removeRef(idx)">
                    <i class="ri-close-line" />
                  </FcButton>
                </div>
              </div>
            </div>
          </div>
        </template>
      </FcTabsPanel>
    </template>

    <template #footer>
      <div class="composer-footer">
        <span v-if="!readonly" class="composer-footer-hint">
          <i class="ri-information-line" />
          {{ t('benefit.composer-footer-hint') }}
        </span>
        <span v-else />
        <div class="composer-footer-actions">
          <FcButton type="primary" @click="readonly ? handleCancel() : handleSubmit()">{{ t('common.confirm') }}</FcButton>
        </div>
      </div>
    </template>
  </FcDialog>
</template>

<script setup lang="ts">
import { ref, computed, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import type { GlobalTemplate, ItemTemplate, TemplateItemRef } from '@/types/benefit'
import type { BenefitItem } from '@/api/benefitItem'
import {
  FcButton, FcDialog, FcTag, FcSelect, FcSwitch, FcTabsPanel,
} from '@/components/sdk'
import type { FormInstance, FormRules } from 'element-plus'

/** 池项最小形态: id/name/icon/default_deduction, 平台模板与租户 item 共有的列. */
type PoolItem = Pick<ItemTemplate, 'id' | 'name' | 'icon' | 'default_deduction'> & Partial<BenefitItem>

const props = defineProps<{
  open: boolean
  form: GlobalTemplate
  itemPool: PoolItem[]
  title: string
  loading?: boolean
  showStatus?: boolean
  readonly?: boolean
}>()

const emit = defineEmits<{
  'update:open': [v: boolean]
  'update:form': [v: GlobalTemplate]
  submit: []
  cancel: []
}>()

const { t } = useI18n()

const activeTab = ref<'basics' | 'compose'>('basics')
const tabs = computed(() => [
  { label: t('benefit.tab-basics'), value: 'basics' },
  { label: t('benefit.tab-compose'), value: 'compose' },
])
const formRef = ref<FormInstance>()

const rules: FormRules = {
  name: [{ required: true, message: t('benefit.name-required'), trigger: 'blur' }],
}

/** Local proxy of `props.form` so element-plus forms can v-model directly. */
const formProxy = computed(() => props.form)

const updateField = <K extends keyof GlobalTemplate>(key: K, value: GlobalTemplate[K]) => {
  emit('update:form', { ...props.form, [key]: value })
}
const updateRefField = <K extends keyof TemplateItemRef>(idx: number, key: K, value: TemplateItemRef[K]) => {
  const refs = (props.form.refs || []).map((r, i) => (i === idx ? { ...r, [key]: value } : r))
  emit('update:form', { ...props.form, refs })
}

/** FcSwitch uses boolean; bridge to string status field on the form. */
const statusActive = computed({
  get: () => props.form.status !== 'INACTIVE',
  set: (v: boolean) => updateField('status', v ? 'ACTIVE' : 'INACTIVE'),
})

// 选项
const durationUnitOptions = computed(() => [
  { label: t('benefit.day'), value: 'day' },
  { label: t('benefit.month'), value: 'month' },
  { label: t('benefit.year'), value: 'year' },
])
const refreshCycleUnitOptions = computed(() => [
  { label: t('benefit.day'), value: 'day' },
  { label: t('benefit.week'), value: 'week' },
  { label: t('benefit.month'), value: 'month' },
  { label: t('benefit.year'), value: 'year' },
])

// 候选池 — 来自父级 itemPool prop
interface SelectableItem {
  id: string
  name: string
  icon: string
  defaultDeduction: number
}

const availableItems = computed<SelectableItem[]>(() =>
  props.itemPool.map(i => ({
    id: String(i.id),
    name: i.name || i.id || '',
    icon: i.icon || 'ri-question-line',
    defaultDeduction: Number(i.default_deduction ?? 0),
  }))
)

const iconOf = (id: string) => availableItems.value.find(i => i.id === id)?.icon || 'ri-question-line'
const nameOf = (id: string) => availableItems.value.find(i => i.id === id)?.name || id
const deductionOf = (id: string) => availableItems.value.find(i => i.id === id)?.defaultDeduction ?? 0

// 组合器内部状态
const pickerSelection = ref<string[]>([])
const poolSearch = ref('')
const dragIndex = ref<number | null>(null)

const filteredPoolItems = computed(() => {
  const all = availableItems.value
  if (!poolSearch.value) return all
  const q = poolSearch.value.toLowerCase()
  return all.filter(i =>
    i.name.toLowerCase().includes(q) || i.id.includes(q)
  )
})

// dialog 打开时重置 pickerSelection / poolSearch / tab
watch(() => props.open, (v) => {
  if (v) {
    pickerSelection.value = (props.form.refs || []).map(r => r.item_id).filter(Boolean) as string[]
    poolSearch.value = ''
    activeTab.value = 'basics'
  }
})

const togglePick = (id: string) => {
  if (pickerSelection.value.includes(id)) {
    pickerSelection.value = pickerSelection.value.filter(x => x !== id)
    const refs = (props.form.refs || []).filter(r => r.item_id !== id)
    emit('update:form', { ...props.form, refs })
  } else {
    pickerSelection.value = [...pickerSelection.value, id]
    const refs = [...(props.form.refs || []), { item_id: id, quota: 0, refresh_cycle: 0, refresh_cycle_unit: 'month' }]
    emit('update:form', { ...props.form, refs })
  }
}

const removeRef = (idx: number) => {
  const removedId = props.form.refs?.[idx]?.item_id
  const refs = (props.form.refs || []).filter((_, i) => i !== idx)
  emit('update:form', { ...props.form, refs })
  if (removedId) {
    pickerSelection.value = pickerSelection.value.filter(x => x !== removedId)
  }
}

const clearRefs = () => {
  emit('update:form', { ...props.form, refs: [] })
  pickerSelection.value = []
}

const onDragStart = (idx: number) => { dragIndex.value = idx }
const onDrop = (targetIdx: number) => {
  if (dragIndex.value === null || dragIndex.value === targetIdx) return
  const list = [...(props.form.refs || [])]
  const moved = list.splice(dragIndex.value, 1)[0]
  if (moved) list.splice(targetIdx, 0, moved)
  emit('update:form', { ...props.form, refs: list })
  dragIndex.value = null
}

const handleSubmit = async () => {
  if (props.readonly) {
    emit('cancel')
    return
  }
  if (formRef.value) {
    try { await formRef.value.validate() } catch { return }
  }
  emit('submit')
}

const handleCancel = () => {
  emit('cancel')
}
</script>

<style scoped lang="scss">
/* ============ Composer Dialog ============ */
:deep(.composer-dialog .el-dialog__body) {
  padding: 0 24px 8px;
  min-height: 480px;
}
.composer-tabs {
  --fc-tabs-padding-y: 0;
}
:deep(.composer-tabs .fc-tabs) {
  padding: 4px 0;
}
:deep(.composer-tabs .fc-tab) {
  font-weight: 500;
  padding: 8px 20px;
}
.basics-form { padding: 24px 0 8px; max-width: 640px; }

.composer {
  display: grid;
  grid-template-columns: 320px 1fr;
  gap: 16px;
  margin-top: 16px;
  height: 460px;
}

/* ----- 左: 候选池 ----- */
.composer-side {
  background: var(--app-bg-muted, #f7f8fa);
  border-radius: 10px;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}
.composer-side-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 12px 16px 8px;
  font-weight: 600;
  font-size: 13px;
  color: var(--app-text-secondary);
}
.composer-side-count {
  background: var(--el-color-primary-light-9);
  color: var(--app-primary);
  padding: 1px 8px;
  border-radius: 999px;
  font-size: 11px;
  font-weight: 600;
}
.composer-pool-search {
  position: relative;
  margin: 0 16px 12px;
}
.composer-pool-search-icon {
  position: absolute;
  left: 10px;
  top: 50%;
  transform: translateY(-50%);
  color: var(--app-text-tertiary);
  font-size: 16px;
  pointer-events: none;
}
.composer-pool-search-input {
  width: 100%;
  padding: 6px 10px 6px 32px;
  background: var(--app-bg-page, #fff);
  border: 1px solid var(--el-border-color-extra-light);
  border-radius: var(--app-radius-sm, 6px);
  font-size: 13px;
  color: var(--app-text);
  outline: none;
  transition: border-color 0.15s;
  &:focus {
    border-color: var(--app-primary);
  }
  &::placeholder { color: var(--app-text-tertiary); }
}
.composer-pool {
  flex: 1;
  overflow-y: auto;
  padding: 0 12px 12px;
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.pool-card {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 12px;
  background: #fff;
  border: 1px solid var(--app-border-color, #ebeef5);
  border-radius: 8px;
  cursor: pointer;
  transition: all 0.15s;
}
.pool-card:hover {
  border-color: var(--el-color-primary-light-7);
  transform: translateY(-1px);
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.04);
}
.pool-card.is-selected {
  border-color: var(--app-primary);
  background: var(--el-color-primary-light-9);
}
.pool-card-icon {
  width: 32px;
  height: 32px;
  border-radius: 8px;
  background: var(--app-bg-muted);
  color: var(--app-primary);
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 16px;
  flex-shrink: 0;
}
.pool-card.is-selected .pool-card-icon {
  background: var(--app-primary);
  color: #fff;
}
.pool-card-info { flex: 1; min-width: 0; }
.pool-card-name {
  font-size: 13px;
  font-weight: 500;
  color: var(--app-text);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.pool-card-meta {
  margin-top: 4px;
  display: inline-flex;
}
.pool-card-check {
  color: var(--app-text-tertiary);
  font-size: 18px;
}
.pool-card.is-selected .pool-card-check { color: var(--app-primary); }
.pool-empty {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  padding: 40px 20px;
  color: var(--app-text-tertiary);
  font-size: 13px;
}

/* ----- 右: 选中区 ----- */
.composer-main {
  background: #fff;
  border: 1px solid var(--app-border-color, #ebeef5);
  border-radius: 10px;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}
.composer-main-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 12px 16px;
  border-bottom: 1px solid var(--app-border-color, #ebeef5);
}
.composer-main-title {
  display: flex;
  align-items: baseline;
  gap: 12px;
  font-weight: 600;
  font-size: 14px;
  color: var(--app-text);
}
.composer-empty {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 60px 24px;
  color: var(--app-text-tertiary);
  text-align: center;
}
.composer-empty i {
  font-size: 48px;
  color: var(--app-border-color);
  margin-bottom: 12px;
}
.composer-empty-title {
  font-size: 14px;
  font-weight: 500;
  color: var(--app-text-secondary);
  margin: 0 0 4px;
}
.composer-empty-hint {
  font-size: 12px;
  color: var(--app-text-tertiary);
  margin: 0;
  max-width: 260px;
}

.composer-list {
  flex: 1;
  overflow-y: auto;
  padding: 8px;
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.compose-card {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 10px 12px;
  background: var(--app-bg-page, #fff);
  border: 1px solid var(--app-border-color, #ebeef5);
  border-radius: 8px;
  transition: all 0.15s;
}
.compose-card:hover {
  border-color: var(--el-color-primary-light-7);
  background: var(--el-color-primary-light-9);
}
.compose-card-drag {
  color: var(--app-text-tertiary);
  cursor: grab;
  font-size: 16px;
  user-select: none;
}
.compose-card-drag:active { cursor: grabbing; }
.compose-card-icon {
  width: 32px;
  height: 32px;
  border-radius: 8px;
  background: var(--app-bg-muted);
  color: var(--app-primary);
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 16px;
  flex-shrink: 0;
}
.compose-card-info { flex: 1; min-width: 0; }
.compose-card-name {
  font-size: 13px;
  font-weight: 500;
  color: var(--app-text);
}
.compose-card-name-tag {
  margin-top: 4px;
  display: inline-flex;
}
.compose-card-fields {
  display: flex;
  align-items: flex-end;
  gap: 12px;
}
.compose-card-field {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 2px;
}
.compose-card-label {
  font-size: 11px;
  color: var(--app-text-tertiary);
}

/* ----- Footer ----- */
.composer-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  width: 100%;
}
.composer-footer-hint {
  font-size: 12px;
  color: var(--app-text-tertiary);
  display: inline-flex;
  align-items: center;
  gap: 4px;
}
.composer-footer-actions {
  display: flex;
  gap: 8px;
}
</style>