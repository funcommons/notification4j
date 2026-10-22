# 开发指南：消息发送全链（定向 / 批量 / 撤回 / 模板 / 结果查询）

> 适用版本 **v1.4.0**。文中端点、字段、约束与错误码逐一对照仓内源码核验
> （`MessageService` / `BatchJobService` / `MessageTypeService` / `TemplateService` / `DeliveryPlanService` /
> `NfyRuntimeMessageController` / `NfyJobController` / `NfyAdminTypeController` / `NfyAdminTemplateController`，
> 及 IT `NfySmokeTest` / `NfyMessageCancelTest` / `NfyBatchSendJobTest` / `NfyTemplateTest`、E2E `l2-messages.spec.ts`）。
> 契约全集以 [api-spec](../../api/api-spec.md) 为准；换 token 前置见 [auth.md](auth.md)，错误码速查见
> [error-codes.md](../04-参考/error-codes.md)。

## 1. 鉴权口径速览（发送面 vs 查询面）

所有端点都要求 `Authorization: Bearer <TENANT token>`，区别在用户头：

| 面 | 端点 | X-User-Id | 依据 |
|---|---|---|---|
| 发送面 | MSG-001 发送 / MSG-002 批量 / MSG-003 查发送结果 / MSG-009 撤回 / JOB-001 轮询 / TYP / TPL admin | **不需要** | 控制器仅取租户上下文（`NfyTenantContexts.tenantId()`） |
| 查询面 | MSG-004 列表 / MSG-005 详情 / MSG-006 已读 / MSG-007 未读数 / MSG-008 最近 N 条 | **必须**（缺失 `10101`「X-User-Id 不能为空」） | 控制器取用户上下文（`NfyTenantContexts.userId()`） |

## 2. 前置：类型先行（TYP-001）

系统**出厂不预置任何消息类型**（迁移脚本 `V1.0.0__init_nfy_schema.sql` 无种子数据），发送前必须先建类型，
否则 `10601 消息类型不存在或已停用`（`MessageTypeService.requireEnabled`，类型不存在或非 ENABLED 同码同文案）。

**API-TYP-001** `POST /nfy/api/v1/admin/types`（TENANT token）：

| 字段 | 类型 | 必填 | 约束 / 缺省 | 说明 |
|---|---|---|---|---|
| type_code | String | 是 | `^[A-Za-z0-9_]{1,32}$` | 租户内唯一，重复 `10401`「类型编码已存在」 |
| name | String | 是 | 非空 | 展示名 |
| description | String | 否 | 缺省 `""` | |
| default_level | String | 否 | 缺省 `NORMAL`；枚举 `NORMAL/IMPORTANT/URGENT`，非法 `10100` | **仅作展示/管理口径**，发送时未传 level 一律按 NORMAL 落库（见 §3 注） |
| default_channels | String[] | 否 | 缺省 `["INAPP"]`；至少 1 个，元素限 `INAPP/DINGTALK/WECOM/FEISHU/EMAIL`，非法 `10100` | 用户无订阅行时的外发类型语义（见 §4） |

响应 `data.type_id`（雪花数字串）。配套：`GET /nfy/api/v1/admin/types` 列表、`PATCH /nfy/api/v1/admin/types/{type_id}`
（可改 name/description/default_*/status；停用走 `status:"DISABLED"`，停用后发送即 `10601`；无 DELETE 端点）。
另注意 `10402 内置类型不可停用`（built_in=1 时 PATCH DISABLED）。

```bash
curl -s -X POST http://localhost:9200/nfy/api/v1/admin/types \
  -H "Authorization: Bearer $TENANT_TOKEN" -H "Content-Type: application/json" \
  -d '{"type_code":"ORDER","name":"订单通知","default_channels":["INAPP"]}'
# → { "code": 0, ..., "data": { "type_id": "1943...（示例）" }, "fail": false }
```

## 3. API-MSG-001 定向发送

`POST /nfy/api/v1/runtime/messages`（TENANT token，无需 X-User-Id）。请求体字段全集
（`PostMessagesRequest` 注解即真实闸门，宽度对齐 DDL 列宽）：

| 字段 | 类型 | 必填 | 约束 / 缺省 | 出处 |
|---|---|---|---|---|
| type_code | String | 是 | `^[A-Za-z0-9_]{1,36}$`；本租户已启用类型（否则 `10601`） | `@NotBlank @Pattern` + `requireEnabled` |
| user_ids | String[] | 是 | 非空（空列表 `10100`，fwk4j @Valid 口径）；单个 1~68 字符；**去重后 ≤1000**（超限 `10102`「接收人数量超限(单次≤1000)」） | `@NotEmpty` + `MessageService.send` L50-56 |
| title | String | 是 | 非空，≤132 字符 | `@NotBlank @Size(max=132)` |
| content | String | 是 | 非空，≤2000 字符（markdown 子集，前端渲染） | `@NotBlank @Size(max=2000)` |
| level | String | 否 | 枚举 `NORMAL/IMPORTANT/URGENT`；**缺省 `NORMAL`** | `@Pattern` + `MessageService.send` L62 |
| biz_no | String | 否 | ≤68 字符；缺省服务端生成 UUID（去横线小写）并回显 | `@Size(max=68)` + L59-60 |
| link_url | String | 否 | ≤500 字符且须匹配 `^https?://.*`，否则 `10100`；缺省 `""` | `@Size @Pattern` |

> 请求体**没有** `sender` / `template_code` / `params` / `channels_override` 字段：`sender` 由服务端恒写 `"API"`
> （`MessageService.send` L70，不可指定）；模板相关字段见 §8（v1.4.0 发送链不接模板）。

响应 `data`：

| 字段 | 类型 | 说明 |
|---|---|---|
| message_id | String | 雪花数字串（Long 一律字符串化） |
| biz_no | String | 回显或服务端生成值 |
| receiver_count | integer | 去重后接收人数 |
| inapp_saved | boolean | 站内信已同步落库（true 即发送成功，外发异步） |
| delivery_planned | integer | 本次计划出的站外投递行数（INAPP 哨兵不产行，纯 INAPP 场景恒 0） |

**幂等语义**：`(tenant_id, biz_no)` 唯一约束是**真闸**——同租户同 `biz_no` 重发返回 `10401`「业务号重复，幂等拒绝」
（E2E L2-02 实测），不产生重复消息。可选叠加 `Idempotency-Key: <UUID v4>`（fwk4j 幂等拦截，48h；同 key 同 body
回放首次结果、同 key 不同 body `10501`；不传该 Header 也能正常发送）。撤回消息后同 biz_no 重发**仍** `10401`
（撤回不触碰幂等记录，见 §7）。

高频错误码：`10601`（类型不存在/停用）、`10100`（字段注解校验）、`10102`（人数超限）、`10401`（biz_no 重复）。

## 4. level 三档与 URGENT 全渠道恒投

投递计划在 send 同一事务内由 `DeliveryPlanService.planForMessage` 展开，规则按接收人逐个判定：

| 场景 | 站外目标 | quiet_hours |
|---|---|---|
| `level=URGENT` | 该用户**全部 ENABLED 注册渠道**（USER 域），**不经订阅矩阵** | **豁免**（恒即时） |
| 有订阅行（该类型） | 订阅行勾选的渠道实例（计划时即过滤非 ENABLED） | 命中静默窗 → 投递行 `next_retry_at=窗结束时刻`，**推迟非丢弃**，窗结束引擎自动发出 |
| 无订阅行 | 类型 `default_channels` 类型语义 → 该用户全部 ENABLED 的同类型渠道实例 | 不判定（无订阅行无静默窗来源） |

- `IMPORTANT` 与 `NORMAL` 走**同一条矩阵展开路径**，仅字典/前端展示口径不同（DICT-001 label「重要」）；只有
  `URGENT` 被特判（`resolveTargets` 中 `"URGENT".equals(level)`）。
- **INAPP 哨兵不产投递行**：站内信在 send 事务内同步落库即达（见 §5），`delivery_planned` 只统计站外行。
- 类型 `default_level` 不参与发送缺省：未传 level 一律 NORMAL（`MessageService.send` L62），要 URGENT 必须显式传。
- 渠道注册/订阅矩阵的维护见 [channels.md](channels.md) 与 [subscription.md](subscription.md)。

## 5. INAPP 落库即达，消息中心即时可见

`inapp_saved=true` 即站内信已入库（recipient 行 UNREAD），**不走投递引擎、无延迟**；用户侧立即可查：

- 未读数：`GET /nfy/api/v1/runtime/messages/unread-count`（T+U，`unread_count` + 公告 `unconfirmed_count` 合成 `total`）
- 列表（Cursor）：`GET /nfy/api/v1/runtime/messages`；铃铛下拉：`GET .../messages/recent?limit≤10`
- 详情即置已读：`GET .../messages/{message_id}`（MSG-005 副作用）；批量已读：`POST .../messages/read`

消息中心前端（五页 + 铃铛）嵌入方式见 [quickstart-frontend.md](../02-快速开始/quickstart-frontend.md)。

## 6. API-MSG-002 批量发送 + API-JOB-001 轮询

`POST /nfy/api/v1/runtime/messages/batch`（TENANT token）。Body 与 MSG-001 完全同构（同一 DTO），差异仅人数上限：
**去重后 ≤100000**，超限 `10102`「批量发送人数超限(≤100000)」；空列表 `10100`。

响应立即返回（异步受理）：`{ job_id, poll_url: "/nfy/api/v1/runtime/jobs/{job_id}", total }`。

**JOB-001** `GET /nfy/api/v1/runtime/jobs/{job_id}` 轮询，状态体 `{ job_id, tenant_id, status, total, finished,
failed_items[] }`：

- 状态机：`RUNNING → DONE | PARTIAL | FAILED`（终态判定：无失败批次=DONE；有失败但至少一批成功=PARTIAL；
  一批未成=FAILED——典型如 type_code 不存在，每批前置校验都失败 → `FAILED`，IT `NfyBatchSendJobTest` 实测）。
- `failed_items` 为失败批次的 user_id 列表，**截断 ≤100**（`MAX_FAILED_ITEMS`）。
- 状态存 Redis（`nfy:job:{id}`），**TTL 48h**，过期/不存在/跨租户一律 `10400`「任务不存在或已过期」（防探测）。
- 轮询语义：无推送/回调，调用方以 2s 量级间隔拉到终态为止（E2E L2-03 实测口径）。

内部拆批口径（对调用方可见的副作用）：**每 1000 人一条 message**（复用 MSG-001 同一事务路径）；显式传 `biz_no`
时首批复用原值、后续批自动加 `-bN` 后缀（N=批号，防跨批撞唯一闸）；未传则每批独立 UUID。因此 >1000 人的批量会
产生多条 message_id，撤回（§7）需逐 message_id 调用。

**批量 vs 多条 MSG-001 取舍**：

| 维度 | MSG-001 同步单发 | MSG-002 异步批量 |
|---|---|---|
| 人数 | ≤1000 | ≤100000 |
| 响应 | 落库即回 message_id | 仅 job_id，结果靠轮询 |
| 事务粒度 | 一次请求一条 message | 每 1000 人一条 message，逐批事务（失败不影响已成功批） |
| 适用 | 事务内通知、需立即拿 message_id/幂等拒识 | 营销/全员类大风控面推送 |

注意：批量**提交端点本身无幂等闸**（不在 Idempotency-Key path-patterns 内）——同 body 重复提交会生成两个 job；
显式 biz_no 重提时其首批将撞 `10401` 而计入 failed_items；若原 job 已全部成功（重提各批全 10401、
finished=0），终态为 **FAILED** 而非 PARTIAL（`BatchJobService` 终态判定 `failed 空→DONE / finished=0→FAILED /
否则 PARTIAL`）——重提请自行换 biz_no，原 job 结果以 JOB-001 查询为准。

## 7. API-MSG-009 消息撤回

`POST /nfy/api/v1/runtime/messages/{message_id}/cancel`（TENANT token，发送方行为，无需 X-User-Id，幂等免
Idempotency-Key）。响应 `{ message_id, status: "CANCELLED", cancelled_deliveries }`。语义（源码
`MessageService.cancel` + ADR-0010，IT `NfyMessageCancelTest` 全覆盖）：

1. **属主防探测**：不存在 / 跨租户 / 非数字 id 一律 `10400`「消息不存在或无权访问」，不暴露存在性。
2. **状态机**：`SENT → CANCELLED` 条件 UPDATE；已 CANCELLED 再撤**幂等成功**（code=0，
   `cancelled_deliveries=0`，E2E L2-06 实测）。
3. **级联外发拦截**：仅 nfya_delivery 中 `PENDING` 行 CAS 置 `CANCELLED`（带前置防与引擎 claim 竞态）；
   已 SENDING/SUCCESS 的投递**不追回**（at-least-once，已在线上的消息不撤回）。
4. **用户侧可见性**：撤回后列表（MSG-004）、最近 N 条（MSG-008）不再返回；详情（MSG-005）`10400`；
   未读数（MSG-007）不再计入（后端 SQL 统一过滤，无需前端配合）。
5. **幂等记录不动**：同 biz_no 重发仍 `10401`；MSG-003 send-results **保持返回**该消息（`status=CANCELLED`），
   运营排查口径不变。

竞态语义详证见 [ADR-0010](../../design/adr/adr-0010-cancel-race-safe-semantics.md)。

## 8. 模板域（TPL-001/002/003）

| 端点 | 方法 | 说明 |
|---|---|---|
| `/nfy/api/v1/admin/templates` | GET / POST | TPL-001 列表 / 创建 |
| `/nfy/api/v1/admin/templates/{template_id}` | GET / PATCH / DELETE | TPL-002 详情 / 修改 / 逻辑删（删后同 code 可重建） |
| `/nfy/api/v1/admin/templates/preview` | POST | TPL-003 渲染预览 |

创建 Body（动态 schema，必填缺省任一 → `10100「{field} 不能为空」`）：`template_code`（租户内唯一，重复
`10401`「模板编码已存在」）、`name`、`type_code`、`title_tpl`、`content_tpl` 必填；`channel_content` 可选——
分渠道覆盖 `{ "DINGTALK": { "title_tpl": "...", "content_tpl": "..." } }`，未覆盖项回落通用模板。

渲染：`{{param}}` 占位符（参数名限 `\w` 即 `[A-Za-z0-9_]`，中文等非 \w 名称原样保留不渲染）；**参数缺失 →
`10603`「模板参数缺失:{name}」**（message 携带首个缺失参数名）。preview 传
`{ "template_code", "type_code", "params": {...} }`，返回 `{ template_id, title, content, channel_content{} }`；
模板不存在 `10400`「模板不存在」。

**模板与直接 content 的关系（v1.4.0 实况）**：MSG-001 请求体**没有** `template_code`/`params` 字段
（api-spec §5.2 表格中该项为演进预留，尚未接线；`MessageService.send` 落库恒 `templateId=0`、`params="{}"`）。
发送要复用模板，两步走：先调 TPL-003 preview 拿渲染后的 title/content，再以直发字段提交 MSG-001
（10603 错误也只会出现在 preview 一步，发送侧不再校验参数）。

```bash
# 建模板 → 渲染预览（payload 取自 IT NfyTemplateTest）
curl -s -X POST http://localhost:9200/nfy/api/v1/admin/templates \
  -H "Authorization: Bearer $TENANT_TOKEN" -H "Content-Type: application/json" \
  -d '{"template_code":"ORDER_TPL","name":"订单模板","type_code":"ORDER",
       "title_tpl":"订单 {{orderNo}} 已支付","content_tpl":"金额 {{amount}}",
       "channel_content":{"DINGTALK":{"content_tpl":"钉钉:{{orderNo}}"}}}'
curl -s -X POST http://localhost:9200/nfy/api/v1/admin/templates/preview \
  -H "Authorization: Bearer $TENANT_TOKEN" -H "Content-Type: application/json" \
  -d '{"template_code":"ORDER_TPL","type_code":"ORDER","params":{"orderNo":"OD-9","amount":"256.80"}}'
```

## 9. 发送结果与状态查询（无回调，纯拉取）

平台**不向发送方回调**任何投递结果（渠道 webhook 仅作为投递目标存在，方向相反）；状态一律拉取：

- **API-MSG-003** `GET /nfy/api/v1/runtime/send-results/{biz_no}`（TENANT token）：返回
  `{ message_id, biz_no, type_code, status, receiver_count, read_count, created_at, deliveries:[{ userid,
  channel_type, status, sent_at, error_message }] }`；biz_no 不存在 `10400`；撤回消息保持返回（§7）。
- **API-DLV-001/002**（admin 面，运维排查）：`GET /nfy/api/v1/admin/deliveries?biz_no=&userid=&channel_type=
  &status=&created_after/ before=&offset=&limit=` 投递记录分页查询（target 脱敏）；`POST
  /nfy/api/v1/admin/deliveries/{delivery_id}/retry` 人工重投（**仅 DEAD** 可重投，非 DEAD `10402`）。

投递状态机（PENDING/SENDING/SUCCESS/FAILED/DEAD，DICT-001 字典）与排障见
[troubleshooting.md](../06-最佳实践/troubleshooting.md)；渠道侧细节见 [channels.md](channels.md)。

## 10. curl 全链示例（建类型 → 发 → 幂等拒绝 → 撤回）

payload 取自 IT `NfySmokeTest` / `NfyMessageCancelTest` 真实用例；`$TENANT_TOKEN` 获取见 [auth.md](auth.md)。

```bash
BASE=http://localhost:9200

# ① 建类型（无预置类型，先建后发）
curl -s -X POST $BASE/nfy/api/v1/admin/types \
  -H "Authorization: Bearer $TENANT_TOKEN" -H "Content-Type: application/json" \
  -d '{"type_code":"SMOKE_ORDER","name":"冒烟订单类型","default_channels":["INAPP"]}'

# ② 定向发送（3 接收人；发送面无需 X-User-Id）
curl -s -X POST $BASE/nfy/api/v1/runtime/messages \
  -H "Authorization: Bearer $TENANT_TOKEN" -H "Content-Type: application/json" \
  -d '{"type_code":"SMOKE_ORDER","user_ids":["u_1001","u_1002","u_1003"],
       "title":"订单已支付","content":"订单 **OD1** 已支付","biz_no":"smoke-biz-1"}'
# → data: { "message_id": "1943...", "biz_no": "smoke-biz-1",
#           "receiver_count": 3, "inapp_saved": true, "delivery_planned": 0 }
MESSAGE_ID=1943...   # ← 取上一步真实 message_id

# ③ 同 biz_no 重发 → 幂等拒绝
curl -s -X POST $BASE/nfy/api/v1/runtime/messages \
  -H "Authorization: Bearer $TENANT_TOKEN" -H "Content-Type: application/json" \
  -d '{"type_code":"SMOKE_ORDER","user_ids":["u_1001"],"title":"重发","content":"c","biz_no":"smoke-biz-1"}'
# → { "code": 10401, "message": "业务号重复，幂等拒绝", ... }

# ④ 查发送结果（含投递明细；撤回后仍可查）
curl -s $BASE/nfy/api/v1/runtime/send-results/smoke-biz-1 \
  -H "Authorization: Bearer $TENANT_TOKEN"

# ⑤ 撤回（已 CANCELLED 再撤幂等成功，cancelled_deliveries=0）
curl -s -X POST $BASE/nfy/api/v1/runtime/messages/$MESSAGE_ID/cancel \
  -H "Authorization: Bearer $TENANT_TOKEN"
# → data: { "message_id": "...", "status": "CANCELLED", "cancelled_deliveries": 0 }

# ⑥ 批量（>1000 人走这里；≤100000）：提交后按 poll_url 轮询至终态
curl -s -X POST $BASE/nfy/api/v1/runtime/messages/batch \
  -H "Authorization: Bearer $TENANT_TOKEN" -H "Content-Type: application/json" \
  -d '{"type_code":"SMOKE_ORDER","user_ids":["u_2001","u_2002"],"title":"批量通知","content":"c"}'
# → data: { "job_id": "...", "poll_url": "/nfy/api/v1/runtime/jobs/...", "total": 2 }
curl -s $BASE/nfy/api/v1/runtime/jobs/<job_id> -H "Authorization: Bearer $TENANT_TOKEN"
```

## 11. 最佳实践小结（均有源码/契约支撑）

- **biz_no 是跨请求幂等键**：用「业务域 + 事件 + 唯一号」设计（如 `order-pay-{orderId}`），重试/重放天然去重；
  不关心幂等可缺省由服务端生成 UUID（但拿不到幂等保护）。批量重提务必换号（§6）。
- **URGENT 是逃生通道不是常规等级**：恒全渠道、绕订阅矩阵、免打扰豁免——只用于安全/资损级事件；常规分级用
  NORMAL/IMPORTANT（两者投递路径相同，仅展示差异）。
- **人数分界 1000/100000**：≤1000 且需要同步结果 → MSG-001；更大规模 → MSG-002 轮询终态，勿在调用方自行
  循环发千次 MSG-001（每条都是独立事务与独立幂等键，失败面更大）。
- **撤回只保证「未投递的不再投」**：已 SUCCESS 的站外消息不追回（at-least-once）；发错内容优先撤回 + 重发新
  biz_no，勿复用旧号（仍会 10401）。
- **站内信是同步语义**：`inapp_saved=true` 即已可见可查，勿再轮询「落库状态」；要等的只有站外投递（send-results/
  DLV-001）。

## 下一步

- 公告（全员广播 / 确认回执）：[announcements.md](announcements.md)
- 渠道注册与订阅矩阵：[channels.md](channels.md) · [subscription.md](subscription.md)
- 错误码速查：[error-codes.md](../04-参考/error-codes.md) · 契约全集：[api-spec](../../api/api-spec.md)
