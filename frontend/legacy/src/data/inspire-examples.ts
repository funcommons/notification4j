/**
 * inspire-examples.ts — AI 灵感 prompt 示例数据.
 *
 * 按 locale 维护, 供 video.vue handleAiInspire 随机抽取.
 *
 * ⚠️ 不能用 t() 拿数组: vue-i18n 的 t() 对数组 key 返回原始 key 字符串,
 * 会把 "create.inspire-examples" 直接写回 prompt 输入框. 这里直接 export.
 */

import type { SupportedLocale } from '@/locales'

export const INSPIRE_EXAMPLES: Record<SupportedLocale, string[]> = {
  'zh-CN': [
    '一位少女在樱花树下漫步，花瓣飘落，唯美浪漫',
    '繁华都市夜景，车水马龙，霓虹闪烁，赛博朋克风格',
    '雪山之巅，日出时分，金光洒满大地，壮丽景色',
    '深海世界，热带鱼群游弋，珊瑚礁色彩斑斓',
    '未来科技城市，飞行汽车穿梭，建筑高耸入云',
  ],
  'en-US': [
    'A girl walking under cherry blossoms, petals falling, romantic and dreamy',
    'Bustling city night scene, neon lights, cyberpunk style',
    'Snowy mountain peak at sunrise, golden light bathing the land, magnificent scenery',
    'Deep sea world, tropical fish swimming, colorful coral reefs',
    'Future tech city, flying cars soaring between towering skyscrapers',
  ],
}