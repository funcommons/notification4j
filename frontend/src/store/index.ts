import { createPinia } from 'pinia'

const pinia = createPinia()

export default pinia

// 导出 store
// benefit4j 裁剪残留 (user / app / benefitAuth store) 已归档至 legacy/src/store/
export * from './oem'
export * from './preference'
