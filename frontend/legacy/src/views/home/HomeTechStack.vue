<template>
  <section id="home-tech-stack" class="home-section">
    <div class="home-section__inner">
      <FcSectionHeader
        :title="t('home.tech-title')"
        :subtitle="t('home.tech-subtitle')"
      />

      <!-- 技术栈 -->
      <div class="tech-grid">
        <div v-for="group in techGroups" :key="group.id" class="tech-group">
          <div class="tech-group__label">
            <i :class="group.icon" />
            <span>{{ t(`home.tech.${group.id}-label`) }}</span>
          </div>
          <div class="tech-group__items">
            <FcTag
              v-for="item in group.items"
              :key="item"
              size="md"
              color="gray"
            >{{ item }}</FcTag>
          </div>
        </div>
      </div>

      <!-- 快速开始 -->
      <h3 class="tech-block-title">{{ t('home.quickstart-title') }}</h3>
      <div class="code-block">
        <FcButton size="small" class="code-copy" @click="copyText(codeSnippet)">
          {{ copied ? t('home.copied') : t('home.copy') }}
        </FcButton>
        <pre class="code-pre"><code>{{ codeSnippet }}</code></pre>
      </div>
      <p class="code-hint">{{ t('home.quickstart-hint') }}</p>
    </div>
  </section>
</template>

<script setup lang="ts">
/**
 * HomeTechStack - 平台首页「技术栈 + 快速开始」版块。
 *
 * 上半: 4 组技术栈 (前端 / 后端 / 数据 / 基础设施) 用 FcTag 排版.
 *
 * 下半: 一个真实接入示例 (curl), 用深色代码块 (沿用 EmbedDocs 已修复
 *      的 .code-pre 写法, 不再用 --el-fill-color-darker).
 *
 * 注: 代码块刻意不随主题翻转, 保证两种主题下都清晰可读。
 */
import { ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { FcSectionHeader, FcTag, FcButton, toast } from '@/components/sdk'

defineOptions({ name: 'HomeTechStack' })

const { t } = useI18n()

const techGroups = [
  { id: 'frontend', icon: 'ri-window-line',  items: ['Vue 3', 'Element Plus', 'TypeScript', 'Vite', 'Pinia', 'vue-i18n'] },
  { id: 'backend',  icon: 'ri-server-line',  items: ['Spring Boot', 'MyBatis-Plus', 'Druid', 'Lombok', 'JDK 17', 'Maven'] },
  { id: 'data',     icon: 'ri-database-2-line', items: ['PostgreSQL 16+', 'JSONB + GIN', 'intarray', 'A 股字典表'] },
  { id: 'infra',    icon: 'ri-shield-keyhole-line', items: ['Snowflake', 'OpenID', 'JWT', 'Redis', 'Caffeine', 'Lua 限流'] },
]

const codeSnippet = `# 1. 取 access_token
curl -X POST https://your-host.example.com/benefit/api/v1/auth/token \\
  -H 'Content-Type: application/json' \\
  -d '{"client_id":"PLATFORM","client_secret":"fvUQ54yPgrMg2v2X"}'

# 2. 查询订阅
curl https://your-host.example.com/benefit/api/v1/subscribes?userId=U10001 \\
  -H "Authorization: Bearer \${TOKEN}"

# 3. 扣减权益
curl -X POST https://your-host.example.com/benefit/api/v1/consume \\
  -H "Authorization: Bearer \${TOKEN}" \\
  -H 'Content-Type: application/json' \\
  -d '{
    "userId": "U10001",
    "itemId": 1000000000000000001,
    "num": 1,
    "externalOrderId": "ord-2026-08-01-001"
  }'`

const copied = ref(false)

async function copyText(text: string) {
  try {
    await navigator.clipboard.writeText(text)
    copied.value = true
    toast.success(t('home.copied'))
    setTimeout(() => { copied.value = false }, 1600)
  } catch {
    toast.error(t('common.error'))
  }
}
</script>

<style scoped lang="scss">
.home-section {
  padding: 80px 24px;
  background: var(--app-bg-page, var(--el-bg-color));
}
.home-section__inner {
  max-width: 1200px;
  margin: 0 auto;
}

.tech-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(280px, 1fr));
  gap: 16px;
  margin-top: 32px;
}

.tech-group {
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 18px 20px;
  border-radius: var(--app-radius-md, 12px);
  background: var(--app-bg-card, var(--el-bg-color));
  border: 1px solid var(--app-separator, var(--el-border-color-extra-light));
}
.tech-group__label {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;
  font-weight: 600;
  color: var(--app-text, var(--el-text-color-primary));
  i { color: var(--app-primary); font-size: 18px; }
}
.tech-group__items {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.tech-block-title {
  margin: 48px 0 12px;
  font-size: 18px;
  font-weight: 600;
  color: var(--app-text, var(--el-text-color-primary));
}

.code-block {
  position: relative;
}
.code-copy {
  position: absolute;
  top: 8px;
  right: 8px;
  z-index: 1;
}
.code-pre {
  margin: 0;
  padding: 20px 24px;
  border-radius: var(--app-radius-lg, 12px);
  // 固定深色编辑器背景: --el-fill-color-darker 在浅色主题下是浅灰 (#EBEDF0),
  // 会让近白文字不可见。代码块刻意不随主题翻转, 保证两种主题下都清晰可读。
  background: #1f2430;
  border: 1px solid rgba(255, 255, 255, 0.06);
  color: #e6e6f0;
  overflow-x: auto;
  font-family: var(--el-font-family-mono, ui-monospace, SFMono-Regular, Menlo, monospace);
  font-size: 12px;
  line-height: 1.7;

  code {
    white-space: pre;
    color: inherit;
  }
}

.code-hint {
  margin: 10px 0 0;
  font-size: 12px;
  color: var(--app-text-tertiary);
  text-align: center;
}

@media (max-width: 640px) {
  .home-section { padding: 56px 16px; }
  .tech-grid { grid-template-columns: 1fr; }
}
</style>
