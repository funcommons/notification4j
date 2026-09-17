<template>
  <div class="app-page consume-direct-page">
    <FcSectionHeader :title="t('benefit.consume-direct-title')" />

    <FcFilterBar>
      <el-form :inline="true" class="filter-form" @submit.prevent>
        <el-form-item v-if="isPlatform" class="fc-form-item" :label="t('benefit.tenant')">
          <el-input class="fc-input" v-model="tenantIdInput" :placeholder="t('benefit.tenant-appid-placeholder')" clearable style="width: 180px" />
        </el-form-item>
        <el-form-item class="fc-form-item">
          <FcButton type="primary" @click="openDialog()">
            <i class="ri-flashlight-line" /> {{ t('benefit.consume-direct-create') }}
          </FcButton>
        </el-form-item>
      </el-form>
    </FcFilterBar>

    <FcSection>
      <div class="hint-block">
        <i class="ri-information-line" />
        <div>
          <div class="hint-title">{{ t('benefit.consume-direct-hint-title') }}</div>
          <div class="hint-body">{{ t('benefit.consume-direct-hint-body') }}</div>
        </div>
      </div>
    </FcSection>

    <FcDialog
      v-model:open="dialogVisible"
      :title="t('benefit.consume-direct-create')"
      append-to-body
      width="560px"
    >
      <el-form :model="form" label-width="120px" :rules="rules" ref="formRef">
        <el-form-item class="fc-form-item" :label="t('benefit.user-id')" prop="userid">
          <el-input class="fc-input" v-model="form.userid" :placeholder="t('benefit.user-id')" clearable />
        </el-form-item>
        <el-form-item class="fc-form-item" :label="t('benefit.item-id')" prop="item_id">
          <el-input class="fc-input" v-model="form.item_id" :placeholder="t('benefit.item-id')" clearable />
        </el-form-item>
        <el-form-item class="fc-form-item" :label="t('benefit.consume-num')" prop="consume_num">
          <el-input-number v-model="form.consume_num" :min="1" :max="999999" style="width: 100%" />
        </el-form-item>
        <el-form-item class="fc-form-item" :label="t('benefit.order-id')" prop="external_order_id">
          <el-input class="fc-input" v-model="form.external_order_id" :placeholder="t('benefit.order-id')" clearable />
        </el-form-item>
        <el-form-item class="fc-form-item" :label="t('benefit.partial-allowed')">
          <FcSwitch v-model="form.partial_allowed" />
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
      :title="t('benefit.consume-direct-result')"
      append-to-body
      width="600px"
    >
      <div v-if="lastResult" class="result-block">
        <el-alert
          v-if="lastResult.consume_num !== lastResult.requested"
          type="warning"
          :title="t('benefit.consume-direct-partial-hint')"
          :description="`${lastResult.consume_num} / ${lastResult.requested}`"
          show-icon
          :closable="false"
        />
        <el-descriptions :column="1" border style="margin-top: 12px">
          <el-descriptions-item :label="t('benefit.consume-num')">{{ lastResult.consume_num }}</el-descriptions-item>
          <el-descriptions-item :label="t('benefit.consume-direct-total-available')">{{ lastResult.total_available }}</el-descriptions-item>
          <el-descriptions-item :label="t('benefit.consume-direct-requested')">{{ lastResult.requested }}</el-descriptions-item>
          <el-descriptions-item :label="t('benefit.consume-direct-consume-ids')">
            <div class="id-list">
              <code v-for="(id, i) in (lastResult.consume_ids || [])" :key="i" class="cell-id">{{ id }}</code>
            </div>
          </el-descriptions-item>
          <el-descriptions-item :label="t('benefit.consume-direct-breakdown')">
            <el-table class="fc-table" :data="lastResult.source_breakdown || []" size="small" border>
              <el-table-column :label="t('benefit.subs-item-id')" prop="subs_item_id">
                <template #default="{ row }"><code class="cell-id">{{ row.subs_item_id }}</code></template>
              </el-table-column>
              <el-table-column :label="t('benefit.source-type')" prop="source_type" width="140" />
              <el-table-column :label="t('benefit.consume-num')" prop="consumed" width="100" align="center" />
            </el-table>
          </el-descriptions-item>
        </el-descriptions>
      </div>
      <template #footer>
        <FcButton type="primary" @click="resultVisible = false">{{ t('common.confirm') }}</FcButton>
      </template>
    </FcDialog>

    <FcDialog
      v-model:open="failVisible"
      :title="t('benefit.consume-direct-failed')"
      append-to-body
      width="440px"
    >
      <el-result icon="error" :title="t('benefit.consume-direct-failed')" :sub-title="lastError">
        <template #extra>
          <FcButton type="primary" @click="failVisible = false">{{ t('common.confirm') }}</FcButton>
        </template>
      </el-result>
    </FcDialog>
  </div>
</template>

<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { postConsumeDirect, platformPostConsumeDirect } from '@/api/benefitConsume'
import type { ConsumeDirectResponse, PostConsumeDirectRequest } from '@/api/benefitConsume'
import {
  FcButton, FcSection, FcSectionHeader, FcFilterBar,
  FcSwitch, FcDialog, toast,
} from '@/components/sdk'

defineOptions({ name: 'BenefitConsumeDirect' })

const { t } = useI18n()
const route = useRoute()

const isPlatform = computed(() => route.path.startsWith('/benefit/platform/app'))

const dialogVisible = ref(false)
const resultVisible = ref(false)
const failVisible = ref(false)
const submitting = ref(false)
const tenantIdInput = ref('')
const lastResult = ref<ConsumeDirectResponse | null>(null)
const lastError = ref('')
const formRef = ref()

const form = reactive<PostConsumeDirectRequest>({
  userid: '',
  item_id: '',
  external_order_id: '',
  consume_num: 1,
  partial_allowed: false,
})

const rules = {
  userid: [{ required: true, message: 'userid 不能为空', trigger: 'blur' }],
  item_id: [{ required: true, message: 'item_id 不能为空', trigger: 'blur' }],
  external_order_id: [{ required: true, message: 'external_order_id 不能为空', trigger: 'blur' }],
  consume_num: [{ required: true, type: 'number' as const, min: 1, message: 'consume_num ≥ 1', trigger: 'change' }],
}

const openDialog = () => {
  Object.assign(form, {
    userid: '',
    item_id: '',
    external_order_id: '',
    consume_num: 1,
    partial_allowed: false,
  })
  dialogVisible.value = true
}

const onSubmit = async () => {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return
  submitting.value = true
  try {
    const api = isPlatform.value ? platformPostConsumeDirect : postConsumeDirect
    const res = await api(form, isPlatform.value ? tenantIdInput.value : undefined) as any
    lastResult.value = (res.data as ConsumeDirectResponse) || null
    dialogVisible.value = false
    resultVisible.value = true
    toast.success(t('benefit.consume-direct-success'))
  } catch (err: any) {
    lastError.value = err.message || String(err)
    dialogVisible.value = false
    failVisible.value = true
  } finally {
    submitting.value = false
  }
}
</script>

<style scoped lang="scss">
.consume-direct-page {
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
.cell-id {
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  font-size: 12px;
  padding: 1px 6px;
  background: var(--app-bg-muted, #f5f5f7);
  border-radius: 4px;
  color: var(--app-text);
  display: inline-block;
  margin-right: 4px;
}
.id-list {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
}
</style>