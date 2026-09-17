import { describe, it, expect } from 'vitest'
import { parseFwkTime } from './fwkTime'

// VECTOR: TAG=f8-invalid-date
// 契约（framework4j fwk4j-web Jackson 序列化）：Long→String 精度保护，
// 雪花毫秒时间戳实际以字符串到达（如 "created_at":"1789654352932"）——
// new Date(数字字符串) 返回 Invalid Date，必须经 parseFwkTime 归一。

const MS = 1789654352932

describe('parseFwkTime', () => {
  it('纯数字字符串（Long→String 雪花毫秒）→ Date', () => {
    expect(parseFwkTime(String(MS))?.getTime()).toBe(MS)
  })

  it('number 直传 → Date', () => {
    expect(parseFwkTime(MS)?.getTime()).toBe(MS)
  })

  it('ISO 字符串直接解析', () => {
    const iso = '2026-05-06T07:08:09.000Z'
    expect(parseFwkTime(iso)?.toISOString()).toBe(iso)
  })

  it('秒级时间戳（<1e12）按秒 ×1000', () => {
    expect(parseFwkTime('1700000000')?.getTime()).toBe(1700000000000)
    expect(parseFwkTime(1700000000)?.getTime()).toBe(1700000000000)
  })

  it('1e12 边界：≥1e12 视为毫秒原样，<1e12 视为秒', () => {
    expect(parseFwkTime('999999999999')?.getTime()).toBe(999999999999000)
    expect(parseFwkTime('1000000000000')?.getTime()).toBe(1000000000000)
    expect(parseFwkTime(1000000000000)?.getTime()).toBe(1000000000000)
  })

  it('0 / "0" 视为无时间（与业务 null 语义一致）', () => {
    expect(parseFwkTime(0)).toBeNull()
    expect(parseFwkTime('0')).toBeNull()
  })

  it('非法字符串 → null（而非 Invalid Date）', () => {
    expect(parseFwkTime('abc')).toBeNull()
    expect(parseFwkTime('12abc')).toBeNull()
    expect(parseFwkTime('2026-13-45')).toBeNull()
  })

  it('空值（null/undefined/空串/空白）→ null', () => {
    expect(parseFwkTime(null)).toBeNull()
    expect(parseFwkTime(undefined)).toBeNull()
    expect(parseFwkTime('')).toBeNull()
    expect(parseFwkTime('  ')).toBeNull()
  })

  it('Date 输入透传（无效 Date → null）', () => {
    expect(parseFwkTime(new Date(MS))?.getTime()).toBe(MS)
    expect(parseFwkTime(new Date('not-a-date'))).toBeNull()
  })
})
