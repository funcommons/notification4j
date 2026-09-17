<template>
  <div class="app-page compensations-page">
    <FcSectionHeader :title="t('benefit.compensations-title')" />

    <FcFilterBar>
      <el-form :inline="true" class="filter-form" @submit.prevent>
        <el-form-item v-if="isPlatform" class="fc-form-item" :label="t('benefit.tenant')">
          <el-input class="fc-input" v-model="tenantIdInput" :placeholder="t('benefit.tenant-appid-placeholder')" clearable style="width: 180px" />
        </el-form-item>
        <el-form-item class="fc-form-item">
          <FcButton type="primary" @click="openDialog()">
            <i class="ri-add-line" /> {{ t('benefit.compensation-create') }}
          </FcButton>
        </el-form-item>
      </el-form>
    </FcFilterBar>

    <FcSection>
      <div class="hint-block">
        <i class="ri-information-line" />
        <div>
          <div class="hint-title">{{ t('benefit.compensation-hint-title') }}</div>
          <div class="hint-body">{{ t('benefit.compensation-hint-body') }}</div>
        </div>
      </div>
    </FcSection>

    <FcDialog
      v-model:open="dialogVisible"
      :title="t('benefit.compensation-create')"
      append-to-body
      width="640px"
    >
      <el-form :model="form" label-width="120px" :rules="rules" ref="formRef">
        <el-form-item class="fc-form-item" :label="t('benefit.subscribe-id')" prop="subscribe_id">
          <el-input class="fc-input" v-model="form.subscribe_id" :placeholder="t('benefit.subscribe-id-placeholder')" clearable />
        </el-form-item>
        <el-form-item class="fc-form-item" :label="t('benefit.subs-item-id')" prop="subs_item_id">
          <el-input class="fc-input" v-model="form.subs_item_id" :placeholder="t('benefit.subs-item-id-placeholder')" clearable />
        </el-form-item>
        <el-form-item class="fc-form-item" :label="t('benefit.item-id')" prop="item_id">
          <el-input class="fc-input" v-model="form.item_id" :placeholder="t('benefit.item-id-placeholder')" clearable />
        </el-form-item>
        <el-form-item class="fc-form-item" :label="t('benefit.adjust-num')" prop="adjust_num">
          <el-input-number v-model="form.adjust_num" :min="1" :max="999999" style="width: 100%" />
        </el-form-item>
        <el-form-item class="fc-form-item" :label="t('benefit.adjust-type')" prop="adjust_type">
          <FcSelect v-model="form.adjust_type" :options="adjustTypeOptions" />
        </el-form-item>

        <template v-if="form.adjust_type === 'ADD'">
          <el-form-item class="fc-form-item" :label="t('benefit.source-type')">
            <el-input class="fc-input" v-model="form.source_type" :placeholder="t('benefit.source-type-placeholder')" clearable />
          </el-form-item>
          <el-form-item class="fc-form-item" :label="t('benefit.bucket-priority')">
            <el-input-number v-model="form.priority" :min="0" :max="999" style="width: 100%" />
          </el-form-item>
          <el-form-item class="fc-form-item" :label="t('benefit.bucket-expires')">
            <el-date-picker
              v-model="form.expires_at"
              type="datetime"
              :placeholder="t('benefit.bucket-expires-placeholder')"
              value-format="YYYY-MM-DDTHH:mm:ssZ"
              style="width: 100%"
            />
          </el-form-item>
        </template>

        <el-form-item class="fc-form-item" :label="t('benefit.reason')">
          <el-input class="fc-input" v-model="form.reason" type="textarea" :rows="2" maxlength="255" show-word-limit />
        </el-form-item>
        <el-form-item class="fc-form-item" :label="t('benefit.operator')">
          <el-input class="fc-input" v-model="form.operator" maxlength="64" />
        </el-form-item>
      </el-form>

      <template #footer>
        <FcButton @click="dialogVisible = false">{{ t('common.cancel') }}</FcButton>
        <FcButton type="primary" :loading="submitting" @click="onSubmit">
          <i class="ri-check-line" /> {{ t('common.confirm') }}
        </FcButton>
      </template>
    </FcDialog>

    <FcDialog
      v-model:open="resultVisible"
      :title="t('benefit.compensation-result')"
      append-to-body
      width="540px"
    >
      <div v-if="lastResult" class="result-block">
        <el-descriptions :column="1" border>
          <el-descriptions-item :label="t('benefit.compensation-id')">{{ lastResult.compensation_id || '-' }}</el-descriptions-item>
          <el-descriptions-item :label="t('benefit.subscribe-id')">{{ lastResult.subscribe_id || '-' }}</el-descriptions-item>
          <el-descriptions-item :label="t('benefit.subs-item-id')">{{ lastResult.subs_item_id || '-' }}</el-descriptions-item>
          <el-descriptions-item :label="t('benefit.adjust-type')">
            <FcTag :color="lastResult.adjust_type === 'ADD' ? 'success' : 'warning'" size="sm">{{ lastResult.adjust_type }}</FcTag>
          </el-descriptions-item>
          <el-descriptions-item :label="t('benefit.adjust-num')">{{ lastResult.adjust_num }}</el-descriptions-item>
          <el-descriptions-item :label="t('benefit.source-type')">{{ lastResult.source_type || '-' }}</el-descriptions-item>
          <el-descriptions-item :label="t('benefit.bucket-priority')">{{ lastResult.bucket_priority ?? '-' }}</el-descriptions-item>
          <el-descriptions-item :label="t('benefit.bucket-expires')">{{ lastResult.expires_at || t('benefit.bucket-expires-never') }}</el-descriptions-item>
          <el-descriptions-item :label="t('benefit.quota')">{{ lastResult.quota_limit }}</el-descriptions-item>
          <el-descriptions-item :label="t('benefit.created-at')">{{ formatDate(lastResult.created_at) }}</el-descriptions-item>
        </el-descriptions>
      </div>
      <template #footer>
        <FcButton type="primary" @click="resultVisible = false">{{ t('common.confirm') }}</FcButton>
      </template>
    </FcDialog>
  </div>
</template>

<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { postCompensation, platformPostCompensation } from '@/api/benefitCompensation'
import type { CompensationResponse, PostCompensationRequest } from '@/api/benefitCompensation'
import {
  FcButton, FcSection, FcSectionHeader, FcFilterBar,
  FcSelect, FcDialog, FcTag, toast,
} from '@/components/sdk'

defineOptions({ name: 'BenefitCompensations' })

const { t } = useI18n()
const route = useRoute()

const isPlatform = computed(() => route.path.startsWith('/benefit/platform/app'))

const dialogVisible = ref(false)
const resultVisible = ref(false)
const submitting = ref(false)
const tenantIdInput = ref('')
const lastResult = ref<CompensationResponse | null>(null)
const formRef = ref()

const form = reactive<PostCompensationRequest>({
  subscribe_id: '',
  subs_item_id: '',
  item_id: '',
  adjust_num: 1,
  adjust_type: 'ADD',
  reason: '',
  operator: '',
  source_type: '',
  priority: 0,
  expires_at: null,
})

const rules = {
  subscribe_id: [{ required: true, message: 'subscribe_id 不能为空', trigger: 'blur' }],
  subs_item_id: [{ required: true, message: 'subs_item_id 不能为空', trigger: 'blur' }],
  item_id: [{ required: true, message: 'item_id 不能为空', trigger: 'blur' }],
  adjust_num: [{ required: true, type: 'number' as const, min: 1, message: 'adjust_num ≥ 1', trigger: 'change' }],
  adjust_type: [{ required: true, message: 'adjust_type 不能为空', trigger: 'change' }],
}

const adjustTypeOptions = computed(() => [
  { label: t('benefit.adjust-type-ADD'), value: 'ADD' },
  { label: t('benefit.adjust-type-REDUCE'), value: 'REDUCE' },
])

const openDialog = () => {
  Object.assign(form, {
    subscribe_id: '',
    subs_item_id: '',
    item_id: '',
    adjust_num: 1,
    adjust_type: 'ADD',
    reason: '',
    operator: '',
    source_type: '',
    priority: 0,
    expires_at: null,
  })
  dialogVisible.value = true
}

const onSubmit = async () => {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return
  submitting.value = true
  try {
    const api = isPlatform.value ? platformPostCompensation : postCompensation
    const payload: PostCompensationRequest = {
      subscribe_id: form.subscribe_id,
      subs_item_id: form.subs_item_id,
      item_id: form.item_id,
      adjust_num: form.adjust_num,
      adjust_type: form.adjust_type,
      reason: form.reason || undefined,
      operator: form.operator || undefined,
    }
    if (form.adjust_type === 'ADD') {
      if (form.source_type) payload.source_type = form.source_type
      if (form.priority !== null && form.priority !== undefined) payload.priority = form.priority
      if (form.expires_at) payload.expires_at = form.expires_at
    }
    const res = await api(payload, isPlatform.value ? tenantIdInput.value : undefined) as any
    lastResult.value = (res.data as CompensationResponse) || null
    dialogVisible.value = false
    resultVisible.value = true
    toast.success(t('benefit.compensation-success'))
  } catch (err: any) {
    toast.error(err.message || t('benefit.action-failed'))
  } finally {
    submitting.value = false
  }
}

const formatDate = (iso?: string) => (iso ? new Date(iso).toLocaleString() : '-')
</script>

<style scoped lang="scss">
.compensations-page {
  display: flex;
  flex-direction: column;
  gap: var(--app-block-mb);
  min-width: 0;
}
.filter-form {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  :deep(.el-form-item) { margin-bottom: 8px; margin-right: 12px; }
}
.hint-block {
  display: flex;
  gap: 12px;
  padding: 14px 16px;
  border-radius: 8px;
  background: var(--app-bg-muted, #f5f5f7);
  color: var(--app-text-secondary);
  font-size: 13px;
  line-height: 1.6;
  i { color: var(--app-color-primary); font-size: 18px; margin-top: 2px; }
}
.hint-title { font-weight: 600; color: var(--app-text); margin-bottom: 4px; }
.result-block { padding: 0 4px; }
</style>