/**
 * fwkTime.ts — framework4j 后端时间字段解析归一（消息中心全站时间展示唯一入口）。
 *
 * 框架契约（fwk4j-web Jackson Long→String 精度保护，关联 framework4j 序列化）：
 * JS Number 安全整数范围外的 Long（雪花 ID、毫秒时间戳）序列化为字符串到达，
 * 实际响应形如 `"created_at":"1789654352932"`。`new Date("1789654352932")`
 * 返回 Invalid Date —— 这就是全站时间显示 Invalid Date 的根因（F-8 缺陷）。
 * 因此凡后端时间字段，一律经 parseFwkTime 归一为 Date | null 后再做格式化。
 */

/** 秒/毫秒分界：1e12 ms ≈ 2001-09-09，更小的数值按秒级时间戳 ×1000 处理 */
const MS_BOUNDARY = 1e12

/**
 * 解析 framework4j 后端时间字段为 Date。
 *
 * @param v 后端时间字段原始值：Long→String 的数字字符串（主体形态）、
 *          number（部分端点直传）、ISO-8601 字符串、Date 透传、空值。
 * @returns 归一后的 Date；空值/非法输入/0（业务「无时间」语义）返回 null。
 */
export function parseFwkTime(v: string | number | Date | null | undefined): Date | null {
  if (v === null || v === undefined) return null
  if (v instanceof Date) return Number.isNaN(v.getTime()) ? null : v
  if (typeof v === 'number') {
    if (v === 0 || !Number.isFinite(v)) return null
    return new Date(Math.abs(v) < MS_BOUNDARY ? v * 1000 : v)
  }
  const s = v.trim()
  if (!s) return null
  // 纯数字字符串：Long→String 契约的主体形态（毫秒；<1e12 按秒级时间戳）
  if (/^-?\d+$/.test(s)) {
    const n = Number(s)
    if (n === 0) return null
    return new Date(Math.abs(n) < MS_BOUNDARY ? n * 1000 : n)
  }
  // 其余按日期字符串（ISO-8601 等）解析；非法 → null（不产生 Invalid Date）
  const d = new Date(s)
  return Number.isNaN(d.getTime()) ? null : d
}
