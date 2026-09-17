<template>
  <div class="app-page apps-page">
    <FcSectionHeader :title="t('benefit.tenants-title')" :subtitle="t('benefit.tenants-subtitle')">
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
          class="fc-table apps-table"
          :data="filteredApps"
          v-loading="loading"
          row-key="id"
          stripe
          highlight-current-row
          :max-height="600"
        >
          <el-table-column :label="t('benefit.tenant-name')" min-width="200">
            <template #default="{ row }">
              <div class="app-name">
                <strong>{{ row.name }}</strong>
                <span class="app-id-inline">{{ row.id }}</span>
              </div>
            </template>
          </el-table-column>

          <el-table-column :label="t('benefit.app-description')" min-width="220">
            <template #default="{ row }">
              <span class="app-desc">{{ row.description || '—' }}</span>
            </template>
          </el-table-column>

          <el-table-column :label="t('benefit.app-secret')" min-width="280">
            <template #default="{ row }">
              <div class="secret-cell">
                <code class="secret-value">
                  {{ revealed[row.id!] ? (revealedSecrets[row.id!] || row.app_secret) : maskSecret(row.app_secret) }}
                </code>
                <button
                  class="action-icon-btn"
                  :title="revealed[row.id!] ? t('benefit.app-secret-hide') : t('benefit.app-secret-show')"
                  @click="toggleReveal(row)"
                >
                  <i :class="revealed[row.id!] ? 'ri-eye-off-line' : 'ri-eye-line'" />
                </button>
                <button
                  class="action-icon-btn"
                  :title="t('benefit.app-secret-copy')"
                  @click="onCopySecret(row)"
                >
                  <i class="ri-file-copy-line" />
                </button>
                <FcButton
                  size="small"
                  type="danger"
                  plain
                  class="secret-reset-btn"
                  @click="onResetSecret(row)"
                >
                  <i class="ri-refresh-key-line" /> {{ t('benefit.app-secret-reset') }}
                </FcButton>
              </div>
            </template>
          </el-table-column>

          <el-table-column :label="t('benefit.status')" width="100">
            <template #default="{ row }">
              <FcTag :color="row.status === 'ACTIVE' ? 'success' : 'gray'" size="sm">
                {{ row.status === 'ACTIVE' ? t('benefit.active') : t('benefit.inactive') }}
              </FcTag>
            </template>
          </el-table-column>

          <el-table-column :label="t('benefit.app-subs-count')" width="110" align="right">
            <template #default="{ row }">
              <span class="subs-count">{{ row.subscription_count ?? 0 }}</span>
            </template>
          </el-table-column>

          <el-table-column :label="t('benefit.app-created-at')" width="160">
            <template #default="{ row }">{{ formatDate(row.created_at) }}</template>
          </el-table-column>

          <el-table-column :label="t('common.edit')" width="100" fixed="right">
            <template #default="{ row }">
              <div class="actions-cell">
                <FcTooltip :content="t('common.edit')">
                  <button class="action-icon-btn" @click="openDialog(row)">
                    <i class="ri-edit-line" />
                  </button>
                </FcTooltip>
              </div>
            </template>
          </el-table-column>

          <template #empty><FcEmpty /></template>
        </el-table>
      </el-scrollbar>
    </FcSection>

    <!-- 创建/编辑租户 -->
    <FcDialog
      v-model:open="dialogVisible"
      :title="editingId ? t('common.edit') : t('common.create')"
      append-to-body
    >
      <el-form ref="formRef" :model="form" :rules="rules" label-width="120px">
        <el-form-item class="fc-form-item" :label="t('benefit.tenant-name')" prop="name">
          <el-input class="fc-input" v-model="form.name" />
        </el-form-item>
        <el-form-item class="fc-form-item" :label="t('benefit.app-description')">
          <el-input
            class="fc-input"
            v-model="form.description"
            type="textarea"
            :rows="2"
            :placeholder="t('benefit.app-description-placeholder')"
          />
        </el-form-item>
        <el-form-item class="fc-form-item" :label="t('benefit.status')">
          <FcSelect v-model="form.status" :options="statusOptions" />
        </el-form-item>
      </el-form>
      <template #footer>
        <div class="dialog-footer">
          <FcButton v-if="editingId" type="danger" plain @click="onResetFromEdit">
            <i class="ri-refresh-key-line" /> {{ t('benefit.app-secret-reset') }}
          </FcButton>
          <div class="dialog-footer-right">
            <FcButton @click="dialogVisible = false">{{ t('common.cancel') }}</FcButton>
            <FcButton type="primary" :loading="submitting" @click="submitForm">
              {{ t('common.confirm') }}
            </FcButton>
          </div>
        </div>
      </template>
    </FcDialog>

    <!-- 创建/重置后展示密钥 (一次性) -->
    <FcDialog
      v-model:open="newSecretVisible"
      :title="t('benefit.app-secret-new-title')"
      width="480px"
      append-to-body
      :close-on-click-modal="false"
      :close-on-press-escape="false"
      :show-close="false"
    >
      <div class="new-secret-warning">
        <i class="ri-alert-line" />
        <span>{{ t('benefit.app-secret-new-hint') }}</span>
      </div>
      <div class="new-secret-box">
        <code>{{ newSecret }}</code>
        <FcButton @click="onCopyAndClose">{{ t('benefit.app-secret-copy-and-close') }}</FcButton>
      </div>
      <template #footer>
        <FcButton type="primary" @click="newSecretVisible = false">{{ t('common.close') }}</FcButton>
      </template>
    </FcDialog>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted, computed, reactive } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import { getApps, createApp, updateApp, resetAppSecret, revealAppSecret } from '@/api/benefitTenant'
import type { AppEntity } from '@/api/benefitTenant'
import {
  FcButton, FcDialog, FcSection, FcSectionHeader,
  FcFilterBar, FcFilterButton,
  FcTag, FcTooltip, FcEmpty, FcSelect,
} from '@/components/sdk'
import { copySilent } from '@/composables'

defineOptions({ name: 'BenefitTenants' })

const { t } = useI18n()

const loading = ref(false)
const apps = ref<AppEntity[]>([])
const searchQuery = ref('')
const filterStatus = ref<'ACTIVE' | 'INACTIVE' | undefined>()
const revealed = reactive<Record<string, boolean>>({})
const revealedSecrets = reactive<Record<string, string>>({})

const filteredApps = computed(() => {
  let list = apps.value
  if (filterStatus.value) list = list.filter(a => a.status === filterStatus.value)
  if (searchQuery.value) {
    const q = searchQuery.value.toLowerCase()
    list = list.filter(a =>
      (a.name || '').toLowerCase().includes(q)
      || (a.id || '').toLowerCase().includes(q)
      || (a.description || '').toLowerCase().includes(q),
    )
  }
  return list
})

const fetchApps = async () => {
  loading.value = true
  try {
    const res = await getApps()
    apps.value = (res.data || []).map(r => ({ ...r }))
  } catch (err: any) {
    ElMessage.error(err.message || t('benefit.action-failed'))
  } finally {
    loading.value = false
  }
}

const maskSecret = (raw: string | undefined) => {
  if (!raw) return '—'
  if (raw.length <= 10) return '••••••'
  return `${raw.slice(0, 4)}${'•'.repeat(Math.min(raw.length - 8, 24))}${raw.slice(-4)}`
}

const toggleReveal = async (row: AppEntity) => {
  const id = row.id!
  if (revealed[id]) {
    revealed[id] = false
    return
  }
  if (!revealedSecrets[id]) {
    try {
      const res = await revealAppSecret(id)
      revealedSecrets[id] = (res.data as any)?.app_secret || ''
    } catch (err: any) {
      ElMessage.error(err.message || t('benefit.action-failed'))
      return
    }
  }
  revealed[id] = true
}

const onCopySecret = async (row: AppEntity) => {
  const real = revealedSecrets[row.id!] || row.app_secret || ''
  const ok = await copySilent(real)
  if (ok) ElMessage.success(t('benefit.app-secret-copied'))
  else ElMessage.error(t('benefit.action-failed'))
}

// —— 创建/编辑 ——
const dialogVisible = ref(false)
const submitting = ref(false)
const editingId = ref<string | null>(null)
const formRef = ref<FormInstance>()
const form = ref<{ name: string; description: string; status: string }>({
  name: '',
  description: '',
  status: 'ACTIVE',
})

const rules: FormRules = {
  name: [{ required: true, message: t('benefit.name-required'), trigger: 'blur' }],
}

const statusOptions = computed(() => [
  { label: t('benefit.active'), value: 'ACTIVE' },
  { label: t('benefit.inactive'), value: 'INACTIVE' },
])

const openDialog = (row: AppEntity | null) => {
  if (row) {
    editingId.value = row.id || null
    form.value = {
      name: row.name || '',
      description: row.description || '',
      status: row.status || 'ACTIVE',
    }
  } else {
    editingId.value = null
    form.value = { name: '', description: '', status: 'ACTIVE' }
  }
  dialogVisible.value = true
}

const submitForm = async () => {
  if (!formRef.value) return
  try {
    await formRef.value.validate()
  } catch {
    return
  }
  submitting.value = true
  try {
    if (editingId.value) {
      await updateApp(editingId.value, form.value)
      ElMessage.success(t('benefit.update-success'))
      dialogVisible.value = false
    } else {
      const res = await createApp(form.value)
      dialogVisible.value = false
      const data = res.data as any
      if (data?.app_secret) {
        newSecret.value = data.app_secret
        newSecretVisible.value = true
        const newId = (data as any).id
        if (newId) revealedSecrets[newId] = data.app_secret
      }
      ElMessage.success(t('benefit.create-success'))
    }
    fetchApps()
  } catch (err: any) {
    ElMessage.error(err.message || t('benefit.action-failed'))
  } finally {
    submitting.value = false
  }
}

// —— 一次性密钥展示 ——
const newSecretVisible = ref(false)
const newSecret = ref('')

const onCopyAndClose = async () => {
  const ok = await copySilent(newSecret.value)
  if (ok) ElMessage.success(t('benefit.app-secret-copied'))
  else ElMessage.error(t('benefit.action-failed'))
  newSecretVisible.value = false
}

// —— 重置密钥 ——
const onResetSecret = async (row: AppEntity) => {
  try {
    await ElMessageBox.confirm(
      t('benefit.app-secret-reset-confirm'),
      `${row.name} (${row.id})`,
      {
        confirmButtonText: t('benefit.app-secret-reset'),
        cancelButtonText: t('common.cancel'),
        type: 'warning',
      },
    )
  } catch {
    return
  }
  await doReset(row.id!)
}

const onResetFromEdit = async () => {
  if (!editingId.value) return
  try {
    await ElMessageBox.confirm(
      t('benefit.app-secret-reset-confirm'),
      `${form.value.name} (${editingId.value})`,
      {
        confirmButtonText: t('benefit.app-secret-reset'),
        cancelButtonText: t('common.cancel'),
        type: 'warning',
      },
    )
  } catch {
    return
  }
  await doReset(editingId.value)
  dialogVisible.value = false
}

const doReset = async (id: string) => {
  try {
    const res = await resetAppSecret(id)
    const data = res.data as any
    if (data?.app_secret) {
      newSecret.value = data.app_secret
      newSecretVisible.value = true
      revealedSecrets[id] = data.app_secret
      revealed[id] = true
      ElMessage.success(t('benefit.app-secret-reset-success'))
    }
    fetchApps()
  } catch (err: any) {
    ElMessage.error(err.message || t('benefit.action-failed'))
  }
}

const formatDate = (raw: string | undefined) => {
  if (!raw) return '—'
  const d = new Date(raw)
  if (isNaN(d.getTime())) return raw
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`
}

onMounted(() => {
  fetchApps()
})
</script>

<style scoped lang="scss">
@use '@/styles/benefit-shared' as *;

.apps-page {
  display: flex;
  flex-direction: column;
  gap: var(--app-block-mb);
  min-width: 0;
}

@include search-input(280px);

.tenant-name {
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.tenant-name strong {
  font-size: 14px;
  color: var(--app-text);
  font-weight: 600;
}
.app-id-inline {
  font-family: monospace;
  font-size: 11px;
  color: var(--app-text-tertiary);
}
.app-desc {
  color: var(--app-text-secondary);
  font-size: 13px;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.secret-cell {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
}
.secret-value {
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  font-size: 12px;
  padding: 4px 8px;
  background: var(--app-bg-muted, #f5f5f7);
  border-radius: var(--app-radius-sm, 6px);
  color: var(--app-text);
  user-select: all;
  max-width: 180px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.secret-reset-btn {
  margin-left: 4px;
}

.subs-count {
  font-weight: 600;
  font-family: ui-monospace, monospace;
  color: var(--app-primary, var(--el-color-primary));
}

@include action-buttons();
.actions-cell { justify-content: flex-start; }

.new-secret-warning {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 12px 16px;
  background: color-mix(in srgb, var(--el-color-warning) 12%, transparent);
  border-left: 3px solid var(--el-color-warning);
  border-radius: var(--app-radius-sm, 6px);
  color: var(--el-text-color-regular);
  font-size: 13px;
  i { font-size: 18px; color: var(--el-color-warning); }
}

.new-secret-box {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-top: 16px;
  padding: 12px 16px;
  background: var(--app-bg-muted, #f5f5f7);
  border-radius: var(--app-radius-md, 12px);
  border: 1px dashed var(--el-color-warning-light-5);

  code {
    flex: 1;
    font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
    font-size: 13px;
    color: var(--el-color-warning-dark-2);
    word-break: break-all;
  }
}

.dialog-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  width: 100%;
}
.dialog-footer-right {
  display: flex;
  gap: 8px;
}
</style>