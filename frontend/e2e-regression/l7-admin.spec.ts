import { test, expect, type Page } from '@playwright/test'
import {
  BASE, platformToken, createTenant, nfy, expectCode0,
  evidence, get, post, patch, uniq,
} from './helpers/nfy'

/**
 * L7 Admin 运营治理线 · Playwright 端到端回归
 * 契约：TYP-001/002（类型管理）、ACH-001/002（公共渠道 + SSRF 白名单拒绝）、
 *       DLV-001（投递查询过滤）、SEC-001（签名密钥轮换/宽限字段/脱敏）、
 *       STAT-001（租户概览：created_at 时间基、空数据日成功率 0）、
 *       TPL-001/002/003（模板管理与渲染预览）、OPS（健康检查免 token）
 * 对应 IT 深回归：NfyTemplateTest / NfySignatureKeyTest / NfyOpsHealthTest
 * 实测口径：admin 面 TENANT token 免签名（签名只挂 runtime 面）；本实例签名已关（已知缺陷 #2，
 * 出厂应开启），签名「验证行为」不可端到端观测，仅断言密钥管理面。
 * 渲染时机（NfyTemplateTest 钉死）：模板渲染仅在 preview 端点；直发路径 template_id=0 内容透传。
 */
test.describe('L7 Admin 运营治理线', () => {
  let page: Page
  let token = ''       // 本线自建租户 token（admin 面免签名）
  let typeCode = ''    // L7-01 创建的类型编码
  let typeId = ''      // L7-01 返回的类型 id
  let tplCode = ''     // L7-09 创建的模板编码
  let tplId = ''       // L7-09 返回的模板 id

  test.beforeAll(async ({ browser }) => {
    page = await browser.newPage()
    const plat = await platformToken()
    const t = await createTenant(plat, uniq('运营治理租户'))
    token = t.token
  })
  test.afterAll(async () => { await page.close() })

  test('L7-01 TYP-001 建类型：default_channels=[INAPP] 落库并列表回显', async () => {
    typeCode = uniq('TYP')
    const r = expectCode0(await nfy<Record<string, string>>(await post(`${BASE}/nfy/api/v1/admin/types`,
      { type_code: typeCode, name: '公告外发类型', default_level: 'NORMAL', default_channels: ['INAPP'] }, { token })))
    typeId = r.data.type_id
    expect(typeId, 'type_id 为字符串化雪花（Long→字符串防精度丢失）').toMatch(/^\d+$/)
    const list = expectCode0(await nfy<Array<Record<string, unknown>>>(await get(`${BASE}/nfy/api/v1/admin/types`, { token })))
    const t = Array.isArray(list.data) ? list.data.find((v) => v.type_code === typeCode) : undefined
    expect(t, '列表含新类型').toBeTruthy()
    expect(JSON.stringify(t && t.default_channels), 'default_channels 回显 INAPP').toContain('INAPP')
    expect(t && t.mandatory, '租户域创建 mandatory 恒 0（唯一入口在平台域）').toBe(0)
    expect(t && t.status).toBe('ENABLED')
    await evidence(page, 'L7-01-建类型', {
      请求: 'POST /nfy/api/v1/admin/types {type_code,name,default_channels:[INAPP]}',
      响应: { code: r.code, type_id: r.data.type_id },
      列表回显: t,
    })
  })

  test('L7-02 TYP-002 更新类型 + 非法枚举 10100', async () => {
    const up = expectCode0(await nfy<Record<string, string>>(await patch(
      `${BASE}/nfy/api/v1/admin/types/${typeId}`, { name: '公告外发类型改', default_level: 'IMPORTANT' }, { token })))
    expect(up.data.status, '更新后仍 ENABLED').toBe('ENABLED')
    const list = expectCode0(await nfy<Array<Record<string, unknown>>>(await get(`${BASE}/nfy/api/v1/admin/types`, { token })))
    const t = (Array.isArray(list.data) ? list.data : []).find((v) => v.type_code === typeCode)
    expect(t && t.name, '改名回显').toBe('公告外发类型改')
    expect(t && t.default_level, 'default_level 更新回显').toBe('IMPORTANT')
    const ill = await nfy(await patch(`${BASE}/nfy/api/v1/admin/types/${typeId}`,
      { default_level: 'HIGH' }, { token }))
    expect(ill.code, 'default_level 枚举外值 10100').toBe(10100)
    await evidence(page, 'L7-02-更新类型', {
      请求: `PATCH /nfy/api/v1/admin/types/${typeId} {name,default_level}`,
      回显: { name: t && t.name, default_level: t && t.default_level, status: up.data.status },
      非法枚举: { default_level: 'HIGH', code: ill.code, message: ill.message },
    })
  })

  test('L7-03 TYP→MSG 链路：类型发消息 → 站内信落库/未读/详情即已读', async () => {
    const m1 = expectCode0(await nfy<Record<string, unknown>>(await post(`${BASE}/nfy/api/v1/runtime/messages`,
      { type_code: typeCode, user_ids: ['uA'], title: '链路消息1', content: '内容A', biz_no: uniq('l7biz') },
      { token, userId: 'uA' })))
    expect(m1.data.inapp_saved, '站内信同步落库').toBe(true)
    expect(m1.data.delivery_planned, 'INAPP 哨兵不产外发投递行').toBe(0)
    const m2 = expectCode0(await nfy<Record<string, unknown>>(await post(`${BASE}/nfy/api/v1/runtime/messages`,
      { type_code: typeCode, user_ids: ['uB'], title: '链路消息2', content: '内容B', biz_no: uniq('l7biz') },
      { token, userId: 'uB' })))
    expect(m2.code).toBe(0)
    const uc0 = expectCode0(await nfy<Record<string, number>>(await get(`${BASE}/nfy/api/v1/runtime/messages/unread-count`,
      { token, userId: 'uA' })))
    expect(Number(uc0.data.unread_count), 'uA 未读=1').toBe(1)
    const det = expectCode0(await nfy<Record<string, unknown>>(await get(
      `${BASE}/nfy/api/v1/runtime/messages/${m1.data.message_id}`, { token, userId: 'uA' })))
    expect(det.data.read_status, 'MSG-006 详情即已读').toBe('READ')
    const uc1 = expectCode0(await nfy<Record<string, number>>(await get(`${BASE}/nfy/api/v1/runtime/messages/unread-count`,
      { token, userId: 'uA' })))
    expect(Number(uc1.data.unread_count), '已读后未读归 0').toBe(0)
    await evidence(page, 'L7-03-类型消息链路', {
      发送: '2 条消息（uA/uB，biz_no 幂等底座）',
      未读联动: 'uA 1 → 详情即已读 → 0',
      详情回显: { title: det.data.title, content: det.data.content, read_status: det.data.read_status },
      delivery_planned: m1.data.delivery_planned + '（INAPP 站内信落库即达）',
    })
  })

  test('L7-04 ACH-001 注册公共渠道（EMAIL）→ 列表可见且 target 脱敏', async () => {
    const r = expectCode0(await nfy<Record<string, string>>(await post(`${BASE}/nfy/api/v1/admin/channels`,
      { channel_type: 'EMAIL', name: '运营公共邮箱', target: `ops-${uniq('pub')}@e2e.test` }, { token })))
    expect(r.data.status, '新渠道 PENDING（不发验证消息 P99≤300ms）').toBe('PENDING')
    const list = expectCode0(await nfy<{ list: Array<Record<string, unknown>> }>(await get(`${BASE}/nfy/api/v1/admin/channels`, { token })))
    const ch = list.data.list.find((c) => c.channel_id === r.data.channel_id)
    expect(ch, '列表可见').toBeTruthy()
    expect(String(ch && ch.target), 'target 快照脱敏').toMatch(/\*\*\*/)
    expect(ch && ch.fail_count).toBe(0)
    await evidence(page, 'L7-04-公共渠道注册', {
      请求: 'POST /nfy/api/v1/admin/channels {channel_type:EMAIL,...}（body 无 userid，scope=TENANT）',
      注册: { channel_id: r.data.channel_id, status: r.data.status },
      列表项: ch,
    })
  })

  test('L7-05 ACH 非法 target 拒绝：渠道枚举 10100 / SSRF 白名单 10609 / 邮箱格式 10100', async () => {
    const badEnum = await nfy(await post(`${BASE}/nfy/api/v1/admin/channels`,
      { channel_type: 'SMS', name: 'x', target: 'https://oapi.dingtalk.com/robot/send?access_token=x' }, { token }))
    expect(badEnum.code, 'channel_type 枚举非法').toBe(10100)
    const ssrf = await nfy(await post(`${BASE}/nfy/api/v1/admin/channels`,
      { channel_type: 'DINGTALK', name: 'x', target: 'https://evil.example.com/webhook' }, { token }))
    expect(ssrf.code, '非官方域名 10609（SSRF 白名单）').toBe(10609)
    const badEmail = await nfy(await post(`${BASE}/nfy/api/v1/admin/channels`,
      { channel_type: 'EMAIL', name: 'x', target: 'not-an-email' }, { token }))
    expect(badEmail.code, '邮箱格式非法').toBe(10100)
    await evidence(page, 'L7-05-非法target拒绝', {
      枚举非法: { channel_type: 'SMS', code: badEnum.code, message: badEnum.message },
      SSRF白名单: { target: 'https://evil.example.com/webhook', code: ssrf.code, message: ssrf.message },
      邮箱格式: { code: badEmail.code, message: badEmail.message },
      白名单口径: 'IM 仅 https+官方域(oapi.dingtalk.com/qyapi.weixin.qq.com/*.feishu.cn)+443+DNS 非内网',
    })
  })

  test('L7-06 DLV-001 投递查询：租户隔离 total + status/channel_type/biz_no 过滤契约', async () => {
    const all = expectCode0(await nfy<{ list: unknown[]; total: string }>(await get(`${BASE}/nfy/api/v1/admin/deliveries`, { token })))
    expect(all.data.list, '本租户 INAPP-only 造数不产生外发行（哨兵语义）').toHaveLength(0)
    expect(Number(all.data.total), 'total 为本租户口径（跨租户不泄漏）').toBe(0)
    const byStatus = expectCode0(await nfy<{ list: unknown[] }>(await get(`${BASE}/nfy/api/v1/admin/deliveries?status=SUCCESS`, { token })))
    expect(byStatus.data.list).toHaveLength(0)
    const byChan = expectCode0(await nfy<{ list: unknown[] }>(await get(`${BASE}/nfy/api/v1/admin/deliveries?channel_type=EMAIL&limit=5&offset=0`, { token })))
    expect(byChan.data.list, 'channel_type/status/分页参数接受').toHaveLength(0)
    const byBiz = expectCode0(await nfy<{ list: unknown[] }>(await get(`${BASE}/nfy/api/v1/admin/deliveries?biz_no=ghost-biz-no`, { token })))
    expect(byBiz.data.list, 'biz_no 子查询定位无命中不炸').toHaveLength(0)
    await evidence(page, 'L7-06-投递查询契约', {
      查询: 'GET /nfy/api/v1/admin/deliveries（status/channel_type/biz_no/limit/offset）',
      本租户total: all.data.total,
      过滤断言: '全部 code=0 且空列表',
      造数说明: 'ENABLED 渠道才能进投递计划：EMAIL 主动验证 10604（首次投递校验）、IM 需真实官方 webhook——本共享实例不可达，正向行造数与 L5 同口径需独立部署+真实渠道环境（报告已登记观察项）',
    })
  })

  test('L7-07 SEC-001 密钥轮换：状态脱敏 → rotate → prev 宽限字段 → 短 secret 10100', async () => {
    const before = expectCode0(await nfy<Record<string, unknown>>(await get(`${BASE}/nfy/api/v1/admin/signature-key`, { token })))
    expect(before.data.configured).toBe(true)
    expect(String(before.data.masked), '脱敏格式 前2+****+后2').toMatch(/^\S{2}\*\*\*\*\S{2}$/)
    expect(before.data.has_prev, '初始无 prev').toBe(false)
    const fresh = 'rotated-' + uniq('sec') + '-abcdefghijklmnop'
    const rot = expectCode0(await nfy<Record<string, unknown>>(await post(`${BASE}/nfy/api/v1/admin/signature-key`,
      { secret: fresh }, { token })))
    expect(rot.data.rotated).toBe(true)
    expect(JSON.stringify(rot.data), '轮换响应不回显完整明文').not.toContain(fresh)
    const after = expectCode0(await nfy<Record<string, unknown>>(await get(`${BASE}/nfy/api/v1/admin/signature-key`, { token })))
    expect(after.data.has_prev, '旧密钥进 prev（24h 宽限窗）').toBe(true)
    expect(Number(after.data.prev_at), 'prev_at 宽限起点时间戳').toBeGreaterThan(0)
    expect(JSON.stringify(after.data), '新旧明文均不出现').not.toContain(fresh)
    const short = await nfy(await post(`${BASE}/nfy/api/v1/admin/signature-key`, { secret: 'short' }, { token }))
    expect(short.code, '明文 <16 位拒绝').toBe(10100)
    const still = expectCode0(await nfy(await get(`${BASE}/nfy/api/v1/admin/types`, { token })))
    expect(still.code, '轮换不吊销存量会话（区别于平台面 SUSPEND）').toBe(0)
    await evidence(page, 'L7-07-签名密钥轮换', {
      before: before.data,
      rotate: { rotated: rot.data.rotated, masked: rot.data.masked },
      after: after.data,
      短secret: { code: short.code, message: short.message },
      存量会话: 'rotate 后原 token 仍可用（code=0）',
      范围注记: '签名「验证行为」需签名开启环境（本实例 signature.enabled=false，已知缺陷 #2）——此处仅验密钥管理面',
    })
  })

  test('L7-08 STAT-001 租户概览：created_at 时间基计数 + 空数据日成功率 0', async () => {
    const s = expectCode0(await nfy<Record<string, string>>(await get(`${BASE}/nfy/api/v1/admin/stats/overview`, { token })))
    expect(Number(s.data.today_sent), '今日发送=本租户 L7-03 的 2 条（租户隔离精确计数）').toBe(2)
    expect(Number(s.data.today_delivered), '外发 SUCCESS=0（INAPP 不走投递）').toBe(0)
    expect(s.data.deliver_success_rate, '空数据日成功率取 0（无样本≠全成功，第 22 步口径统一）').toBe('0')
    expect(Number(s.data.read_rate_7d), '7d 已读率=1/2 受众已读=5000 万分比').toBe(5000)
    expect(Number(s.data.channel_count), 'ENABLED 渠道数（PENDING 不计）').toBe(0)
    await evidence(page, 'L7-08-租户概览统计', {
      口径: 'today 系列时间基 created_at 自然日；rate 万分比定标 0~10000；空数据日 0',
      stats: s.data,
      对应造数: 'L7-03 发送 2 条（uA 已读 / uB 未读）',
    })
  })

  test('L7-09 TPL-001/002 模板创建/列表/详情/更新 + code 租户内唯一 10401', async () => {
    tplCode = uniq('TPL')
    const r = expectCode0(await nfy<Record<string, string>>(await post(`${BASE}/nfy/api/v1/admin/templates`,
      { template_code: tplCode, name: '订单通知模板', type_code: typeCode,
        title_tpl: '订单 {{orderNo}} 已支付', content_tpl: '金额 {{amount}} 元',
        channel_content: { DINGTALK: { content_tpl: '钉钉:{{orderNo}}' } } }, { token })))
    tplId = r.data.template_id
    expect(tplId).toBeTruthy()
    const det = expectCode0(await nfy<Record<string, unknown>>(await get(`${BASE}/nfy/api/v1/admin/templates/${tplId}`, { token })))
    expect(det.data.title_tpl, '占位符原样回显（渲染仅在 preview）').toContain('{{orderNo}}')
    expect(JSON.stringify(det.data.channel_content), '渠道覆盖配置回显').toContain('钉钉:{{orderNo}}')
    expectCode0(await nfy(await patch(`${BASE}/nfy/api/v1/admin/templates/${tplId}`, { name: '订单通知模板改' }, { token })))
    const det2 = expectCode0(await nfy<Record<string, unknown>>(await get(`${BASE}/nfy/api/v1/admin/templates/${tplId}`, { token })))
    expect(det2.data.name).toBe('订单通知模板改')
    const list = expectCode0(await nfy<{ list: Array<Record<string, unknown>> }>(await get(`${BASE}/nfy/api/v1/admin/templates`, { token })))
    expect(list.data.list.map((t) => t.template_code)).toContain(tplCode)
    const dup = await nfy(await post(`${BASE}/nfy/api/v1/admin/templates`,
      { template_code: tplCode, name: '重复', type_code: typeCode, title_tpl: 't', content_tpl: 'c' }, { token }))
    expect(dup.code, 'template_code 租户内唯一').toBe(10401)
    await evidence(page, 'L7-09-模板管理', {
      创建: { template_id: tplId, title_tpl: det.data.title_tpl },
      更新: 'name → 订单通知模板改（详情回显）',
      列表: '含 ' + tplCode,
      重复code: { code: dup.code, message: dup.message },
    })
  })

  test('L7-10 TPL-003 渲染预览：全参渲染 + 渠道覆盖 + 缺参 10603 + 未知模板 10400', async () => {
    const ok = expectCode0(await nfy<Record<string, unknown>>(await post(`${BASE}/nfy/api/v1/admin/templates/preview`,
      { template_code: tplCode, type_code: typeCode, params: { orderNo: 'A123', amount: '99' } }, { token })))
    expect(ok.data.title, 'title_tpl 渲染').toBe('订单 A123 已支付')
    expect(ok.data.content, 'content_tpl 渲染').toBe('金额 99 元')
    const cc = ok.data.channel_content as Record<string, Record<string, string>>
    expect(cc.DINGTALK.content, 'channel_content 按渠道覆盖渲染').toBe('钉钉:A123')
    const miss = await nfy(await post(`${BASE}/nfy/api/v1/admin/templates/preview`,
      { template_code: tplCode, type_code: typeCode, params: { orderNo: 'A123' } }, { token }))
    expect(miss.code, '缺参数 10603').toBe(10603)
    expect(miss.message, 'message 指明缺失参数名').toContain('amount')
    const ghost = await nfy(await post(`${BASE}/nfy/api/v1/admin/templates/preview`,
      { template_code: 'GHOST' + uniq('x'), type_code: typeCode, params: {} }, { token }))
    expect(ghost.code, '未知模板 10400').toBe(10400)
    await evidence(page, 'L7-10-模板渲染预览', {
      全参渲染: { title: ok.data.title, content: ok.data.content, channel_content: ok.data.channel_content },
      缺参: { code: miss.code, message: miss.message },
      未知模板: { code: ghost.code },
    })
  })

  test('L7-11 模板发送渲染时机观察：直发路径内容透传（渲染仅 preview，V1.1 模板发送未接线）', async () => {
    const m = expectCode0(await nfy<Record<string, unknown>>(await post(`${BASE}/nfy/api/v1/runtime/messages`,
      { type_code: typeCode, user_ids: ['uC'], title: '直发{{orderNo}}', content: '直发内容 {{orderNo}} {{amount}}', biz_no: uniq('l7tpl') },
      { token, userId: 'uC' })))
    const det = expectCode0(await nfy<Record<string, unknown>>(await get(
      `${BASE}/nfy/api/v1/runtime/messages/${m.data.message_id}`, { token, userId: 'uC' })))
    expect(det.data.content, '站内信 content 原样透传（未做模板替换）').toBe('直发内容 {{orderNo}} {{amount}}')
    await evidence(page, 'L7-11-直发渲染时机', {
      发送: '带模板的类型 + 含 {{var}} 的 content',
      站内信content: det.data.content,
      对照: 'L7-10 preview 同参数已正确渲染「订单 A123 已支付」',
      定级: '观察项（P3）：NfyTemplateTest 契约即渲染仅在 preview；直发 template_id=0 —— 模板发送链路（V1.1）未接线，若产品宣称 V1.0 支持模板直发则为缺陷',
    })
  })

  test('L7-12 OPS 健康检查：免 token 可达，db/redis 全 UP', async () => {
    const r = expectCode0(await nfy<Record<string, unknown>>(await get(`${BASE}/nfy/api/v1/ops/health`)))
    expect(r.data.status, '整体 UP').toBe('UP')
    const checks = r.data.checks as Record<string, { status: string }>
    expect(checks.db.status, 'db=SELECT 1').toBe('UP')
    expect(checks.redis.status, 'redis=PING').toBe('UP')
    await evidence(page, 'L7-12-运维健康检查', {
      请求: 'GET /nfy/api/v1/ops/health（无 Authorization，exclude-path-patterns 放行）',
      响应: r.data,
      trace_id: r.trace_id,
    })
  })
})
