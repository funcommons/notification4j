<template>
  <div class="app-page platform-login-page" :style="loginPageStyle">
    <div class="login-card">
      <div class="brand-area">
        <h1 class="brand-title">{{ oemTitle }}</h1>
        <p class="brand-subtitle">{{ t('benefit.platform-login') }}</p>
      </div>

      <div class="form-area">
        <h2 class="form-title">{{ t('auth.welcome-back') }}</h2>
        <p class="form-subtitle">{{ t('auth.login-to-continue') }}</p>

        <el-form ref="formRef" :model="form" :rules="rules" @submit.prevent="handleLogin">
          <el-form-item class="fc-form-item" prop="clientId">
            <el-input class="fc-input"
              v-model="form.clientId"
              :placeholder="t('benefit.client-id-placeholder')"
              :prefix-icon="Key"
              size="large"
              clearable
            />
          </el-form-item>

          <el-form-item class="fc-form-item" prop="clientSecret">
            <el-input class="fc-input"
              v-model="form.clientSecret"
              type="password"
              :placeholder="t('benefit.client-secret-placeholder')"
              :prefix-icon="Lock"
              size="large"
              show-password
              @keyup.enter="handleLogin"
            />
          </el-form-item>

          <div v-if="isDev" class="mock-hint">
            <i class="ri-information-line" />
            <span>{{ t('benefit.login-hint-platform') }}</span>
          </div>

          <el-form-item class="fc-form-item">
            <FcButton
              type="primary"
              size="large"
              :loading="loading"
              class="submit-btn"
              @click="handleLogin"
            >
              {{ t('auth.login-btn') }}
            </FcButton>
          </el-form-item>
        </el-form>
      </div>

      <div v-if="oemFooter" class="footer">{{ oemFooter }}</div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed } from 'vue'
import { useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { FcButton, toast } from '@/components/sdk'
import { type FormInstance, type FormRules } from 'element-plus'
import { Key, Lock } from '@element-plus/icons-vue'
import { useBenefitAuthStore } from '@/store/benefitAuth'
import { useOemStore } from '@/store/oem'

defineOptions({ name: 'PlatformLoginPage' })

const { t } = useI18n()
const router = useRouter()
const auth = useBenefitAuthStore()
const oem = useOemStore()

const oemTitle = computed(() => oem.config.title || 'Benefit4j')
const oemFooter = computed(() => oem.config.footerText || '')
const isDev = import.meta.env.DEV
const loginPageStyle = computed(() => {
  if (oem.config.loginBgUrl) {
    return {
      backgroundImage: `url(${oem.config.loginBgUrl})`,
      backgroundSize: 'cover',
      backgroundPosition: 'center',
    }
  }
  return {}
})

const formRef = ref<FormInstance>()
const loading = ref(false)

const form = reactive({
  clientId: '',
  clientSecret: '',
})

const rules: FormRules = {
  clientId: [
    { required: true, message: t('benefit.client-id-placeholder'), trigger: 'blur' },
  ],
  clientSecret: [
    { required: true, message: t('benefit.client-secret-placeholder'), trigger: 'blur' },
  ],
}

async function handleLogin() {
  if (!formRef.value) return
  try {
    await formRef.value.validate()
  } catch {
    return
  }

  loading.value = true
  try {
    await auth.login(form.clientId, form.clientSecret)
    toast.success(t('auth.login-success'))
    router.replace('/benefit/platform/app/dashboard')
  } catch (err: any) {
    const msg = err?.response?.data?.message || err?.message || t('auth.login-failed')
    toast.error(msg)
  } finally {
    loading.value = false
  }
}
</script>

<style scoped lang="scss">
.platform-login-page {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  background: linear-gradient(135deg, #e0f2fe 0%, #f0fdfa 100%);
  padding: 20px;
}

.login-card {
  width: 420px;
  max-width: 100%;
  background: #ffffff;
  border-radius: 16px;
  padding: 40px 36px 28px;
  box-shadow: 0 10px 40px rgba(15, 23, 42, 0.08);
}

.brand-area {
  text-align: center;
  margin-bottom: 32px;
}

.brand-title {
  margin: 0 0 6px;
  font-size: 22px;
  font-weight: 700;
  color: var(--app-primary, var(--el-color-primary));
  letter-spacing: 0.5px;
}

.brand-subtitle {
  margin: 0;
  font-size: 13px;
  color: var(--app-text-tertiary, var(--el-text-color-secondary));
}

.form-area { margin-bottom: 16px; }

.form-title {
  margin: 0 0 4px;
  font-size: 20px;
  font-weight: 600;
  color: var(--app-text, var(--el-text-color-primary));
}

.form-subtitle {
  margin: 0 0 24px;
  font-size: 13px;
  color: var(--app-text-tertiary, var(--el-text-color-secondary));
}

:deep(.el-input__wrapper) {
  border-radius: 8px;
  padding-left: 12px;
}
:deep(.el-input--large .el-input__wrapper) {
  padding: 4px 12px;
}

.mock-hint {
  display: flex;
  align-items: center;
  gap: 6px;
  margin: -4px 0 16px;
  padding: 8px 12px;
  background: color-mix(in srgb, var(--el-color-warning) 12%, transparent);
  border-radius: 6px;
  font-size: 12px;
  color: #92400e;
  i { font-size: 14px; }
}

.submit-btn {
  width: 100%;
  border-radius: 8px;
  font-weight: 600;
  letter-spacing: 2px;
}

.footer {
  margin-top: 12px;
  text-align: center;
  font-size: 12px;
  color: var(--el-text-color-placeholder);
}

@media (max-width: 480px) {
  .login-card {
    padding: 32px 24px 20px;
    border-radius: 12px;
  }
  .brand-title { font-size: 20px; }
  .form-title { font-size: 18px; }
}
</style>