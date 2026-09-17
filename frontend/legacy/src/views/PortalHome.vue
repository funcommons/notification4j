<template>
  <div class="portal-home">
    <HomeTopNav />
    <HomeHero />
    <HomeShowcase />
    <HomeFeatures />
    <HomeArchitecture />
    <HomeTechStack />
    <HomeDocs />
    <HomeFooter />
  </div>
</template>

<script setup lang="ts">
/**
 * PortalHome - 平台首页 (根路径 / 渲染).
 *
 * 仿开源软件官网风格: 顶部导航 + Hero + 特性 + 架构 + 技术栈 + 文档 + 页脚.
 * 沿用 PortalEntry 的「裸 root」渲染 (不挂 AppLayout, 不用 sidebar),
 * meta.layout = 'blank' 路由守卫不动.
 *
 * 所有 7 个区块拆为独立子组件, 各自维护样式 + i18n.
 */
import HomeTopNav from './home/HomeTopNav.vue'
import HomeHero from './home/HomeHero.vue'
import HomeShowcase from './home/HomeShowcase.vue'
import HomeFeatures from './home/HomeFeatures.vue'
import HomeArchitecture from './home/HomeArchitecture.vue'
import HomeTechStack from './home/HomeTechStack.vue'
import HomeDocs from './home/HomeDocs.vue'
import HomeFooter from './home/HomeFooter.vue'

defineOptions({ name: 'PortalHomePage' })
</script>

<style scoped lang="scss">
.portal-home {
  min-height: 100vh;
  background: var(--app-bg-page, var(--el-bg-color));
  color: var(--app-text, var(--el-text-color-primary));
}
</style>
