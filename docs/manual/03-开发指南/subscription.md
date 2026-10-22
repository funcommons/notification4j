# 开发指南：订阅矩阵与免打扰（SUB-001/002）

> 适用版本 **v1.4.0**。订阅矩阵回答一个问题：**某用户的某类消息，从哪些渠道送达**。
> 端点/错误码/校验规则取自 `SubscriptionService` 与 api-spec §5.8（V1.2.1 口径），
> 免打扰投递语义取自 `DeliveryPlanService` 与 [ADR-0011](../../design/adr/adr-0011-quiet-hours-plan-defer.md)，
> 回归用例为 IT `NfySubscriptionFlowTest` / `NfyQuietHoursTest`、E2E `l4-channels-subscriptions.spec.ts`。

## 1. 订阅模型：「用户 × 消息类型 × 渠道」矩阵

每个用户对每个消息类型维护一行勾选（`nfya_subscription`），值为**渠道实例 ID 列表**：

- **INAPP 恒选不可关**：服务端保存时无条件补齐 `"INAPP"` 哨兵且**恒在首位**（用户提交里
  不含 INAPP 也会补）；站内信落库即达，不经投递，无「关闭站内信」语义。
- **强制类型（mandatory）渠道集不可关（`10606`）**：`mandatory=1` 的类型，其
  `default_channels` 中**每个渠道类型**，用户有该类型 `ENABLED` 实例则勾选须保留 ≥1 个
  （关掉最后一个该类型实例 → `10606「强制类型的指定渠道不可关闭」`）；用户无该类型实例则
  **豁免**（INAPP 锁定兜底）——按「类型语义→实例」匹配，而非逐渠道硬卡。
- 渠道删除级联：删除渠道时从该用户订阅行剔除失效 id，行空则回落 `["INAPP"]`。

## 2. SUB-001 查询：矩阵三段

`GET /nfy/api/v1/runtime/subscriptions`（TENANT token + `X-User-Id`）：

| 段 | 内容 |
|---|---|
| `data.types[]` | 本租户 `ENABLED` 类型：`{type_code, name, description, mandatory, default_channels}`。注意 `default_channels` 元素是**渠道类型**枚举（INAPP/DINGTALK/…），而 `items[].channel_ids` 元素是**渠道实例 ID** + `"INAPP"` 哨兵 |
| `data.available_channels[]` | 恒有 `{"channel_id":"INAPP"}` + 本人 `scope=USER` 且 `ENABLED` 的渠道 `{channel_id, channel_type, name}`（公共渠道 scope=TENANT 不在此列） |
| `data.items[]` | 已配置行：`{type_code, channel_ids[, quiet_hours]}`；无记录的类型不出现（前端按 `default_channels` 标注「默认」）；启用的 `quiet_hours` 原样回显，**未启用行不返回该字段**（缺省=未启用，向后兼容旧客户端） |

## 3. SUB-002 保存：PUT 全量替换语义

`PUT /nfy/api/v1/runtime/subscriptions`（TENANT token + `X-User-Id`）：

```json
{ "items": [
    { "type_code": "ORDER",    "channel_ids": ["INAPP", "8712634987123"],
      "quiet_hours": { "start": "22:00", "end": "08:00" } },
    { "type_code": "SECURITY", "channel_ids": ["INAPP"] } ] }
```

- **全量替换 + last-write-wins**：只提交要保留的行，未提交的类型行**删除**；重复 PUT 同内容幂等
  （内容无变化不产生 update）。响应 `data.saved_count`。所以「只改免打扰不动渠道」不存在——
  重提交时缺省 `quiet_hours` 即**重置为未启用**。
- 校验顺序与错误码（`SubscriptionService.save`）：

| 违规 | code / message |
|---|---|
| `type_code` 空 | `10101` |
| 同一 `type_code` 重复提交 | `10100「同一 type_code 重复提交: X」` |
| 类型不存在或已停用 | `10601` |
| `channel_ids` 空/缺失 | `10101「channel_ids 至少 1 个」` |
| 渠道 id 非本人 / 非 USER 面 / 非 ENABLED（含 PENDING 未验证） | `10400「渠道不存在或不可用」` |
| 强制类型关掉最后一个实例 | `10606「强制类型的指定渠道不可关闭」` |
| `quiet_hours` 只给 start/end 其一 | `10100「免打扰 start 与 end 必须成对提供」` |
| `quiet_hours` 格式非 HH:mm（24 小时制正则 `^([01]\d\|2[0-3]):[0-5]\d$`） | `10100「免打扰时间格式须为 HH:mm」` |
| `start` = `end` | `10100「免打扰开始与结束时间不能相同」` |

  校验失败**整单拒绝不落库**（无半截数据）；**跨午夜窗允许**（如 22:00→08:00）。

## 4. mandatory 唯一设置入口：PTE-005（平台域）

`mandatory` 只能由平台侧设置（独立部署形态的平台域接口，PLATFORM 合成 token）：

```bash
POST /nfy/platform/api/v1/tenants/{tenant_open_id}/type-mandatory
# body: {"type_code":"SECURITY","mandatory":1}     # 0|1
```

租户域 admin 面建类型（TYP-001）时**提交该字段即忽略、响应不回显**——租户不可自设强制，
避免租户运营方替终端用户锁死渠道。前端订阅页对强制类型展示锁定态与「URGENT 恒全渠道」文案。

## 5. quiet_hours 投递语义：推迟非丢弃（ADR-0011）

- **计划期推迟**：发送/公告发布展开订阅矩阵时（`DeliveryPlanService`），当前时刻处于该订阅
  静默窗 → 生成的外发投递行 `next_retry_at` 直接置**窗结束时刻**；引擎本就按
  `next_retry_at <= now` 扫描，窗到自动发出——**投递不消失、不丢弃、不取消**。
- **跨午夜两段判定**（如 22:00→08:00）：命中 = `t ≥ start 或 t < end`；窗前段（如 01:00）→
  当日 end，窗后段（如 23:00）→ 次日 end。同日窗命中 = `t ∈ [start, end)`。按**服务端系统默认
  时区**判定（多时区用户概念未定义，登记边界）。
- **天然豁免（IT 验证在案，`NfyQuietHoursTest` 8 用例）**：① **URGENT** 走全部 ENABLED 渠道
  路径、不经订阅矩阵 → 完全忽略免打扰（全渠道即时）；② **INAPP** 站内信落库即达、不产投递行
  → 不受免打扰影响（免打扰只针对外发渠道的「打扰」语义）。
- 脏数据（jsonb 解析失败/字段缺失）**fail-open 即时投递并告警**——宁可打扰不丢消息。
  窗外/未启用 → `next_retry_at` = 计划时刻，即时可扫。

## 6. 发送侧如何消费矩阵

一句话：`DeliveryPlanService.resolveTargets` 按接收人展开——URGENT → 全部 ENABLED 渠道
（绕过矩阵）；有订阅行 → 勾选实例（计划期过滤非 ENABLED）；无订阅行 → 类型
`default_channels` 的类型语义展开为该类型全部 ENABLED 实例。机制全貌见
[技术白皮书 §4](../01-概览/technical-whitepaper.md)（fanout 双模型 + 外发引擎），发送接口见
[sending.md](sending.md)。

## 7. curl 示例：查询 → 全量替换（带 quiet_hours）→ 回显

沿 [quickstart-5min.md](../02-快速开始/quickstart-5min.md) 实例（payload 取自 E2E
`l4-channels-subscriptions.spec.ts` L4-09/L4-12 与 IT `NfySubscriptionFlowTest`）：

```bash
TENANT_TOKEN=...; BASE=http://localhost:9200

# ⓪ 前置：建类型（default_channels 元素是渠道类型枚举；mandatory 不可在此设）
curl -s -X POST $BASE/nfy/api/v1/admin/types \
  -H "Authorization: Bearer $TENANT_TOKEN" -H "Content-Type: application/json" \
  -d '{"type_code":"ORDER","name":"订单通知","default_channels":["INAPP","DINGTALK"]}'

# ① 查询矩阵三段（无 ENABLED 渠道时 available_channels 仅 INAPP 哨兵；items 未配置为空）
curl -s $BASE/nfy/api/v1/runtime/subscriptions \
  -H "Authorization: Bearer $TENANT_TOKEN" -H "X-User-Id: u_1001"
# → {"code":0,"data":{"types":[{"type_code":"ORDER","mandatory":0,"default_channels":["INAPP","DINGTALK"]},...],
#      "available_channels":[{"channel_id":"INAPP"},{"channel_id":"1943...","channel_type":"DINGTALK","name":"SRE 报警群"}],
#      "items":[]}}

# ② PUT 全量替换：只提交渠道实例（INAPP 服务端补齐恒首位）+ 免打扰 22:00→08:00（跨午夜窗）
curl -s -X PUT $BASE/nfy/api/v1/runtime/subscriptions \
  -H "Authorization: Bearer $TENANT_TOKEN" -H "Content-Type: application/json" -H "X-User-Id: u_1001" \
  -d '{"items":[{"type_code":"ORDER","channel_ids":["1943..."],
       "quiet_hours":{"start":"22:00","end":"08:00"}}]}'
# → {"code":0,"data":{"saved_count":1}}

# ③ 回显：INAPP 已补齐首位、quiet_hours 原样返回；再 PUT 缺省 quiet_hours 即重置（字段消失）
curl -s $BASE/nfy/api/v1/runtime/subscriptions \
  -H "Authorization: Bearer $TENANT_TOKEN" -H "X-User-Id: u_1001"
# → ..."items":[{"type_code":"ORDER","channel_ids":["INAPP","1943..."],
#      "quiet_hours":{"start":"22:00","end":"08:00"}}]...

# ④ 全量替换删行：只提交部分类型 → 其余行删除（本例提交空集即清空全部订阅）
curl -s -X PUT $BASE/nfy/api/v1/runtime/subscriptions \
  -H "Authorization: Bearer $TENANT_TOKEN" -H "Content-Type: application/json" -H "X-User-Id: u_1001" \
  -d '{"items":[]}'
# → {"code":0,"data":{"saved_count":0}}，items 回到 []
```

## 8. 常见坑速查

| 坑 | 现象 / 原因 | 处置 |
|---|---|---|
| 渠道勾不上（`10400`） | 渠道是 `PENDING`（未验证）、他人渠道、跨租户渠道或公共渠道（scope=TENANT）——`channel_ids` 只收**本人 USER 面 ENABLED 实例** | IM 先 verify 成功；EMAIL 先 `PATCH {"status":"ENABLED"}`（[channels.md §3](channels.md)） |
| 公共渠道想进用户矩阵 | admin 面建的公共渠道不出现 `available_channels`——scope 隔离，公共渠道只服务公告外发 | 公告 `channel_ids` 引用（[announcements.md](announcements.md)）；个人推送须用户自注册 |
| PUT 后其它类型勾选「丢了」 | 全量替换语义：未提交的类型行被删除，非 bug | 保存前 GET 回读全量 items，改后整包回提交 |
| 免打扰设置「不生效」 | 只勾了 INAPP（无外发渠道）→ 本就不产投递行；或消息是 `URGENT` → 恒全渠道即时 | 免打扰只推迟**外发渠道**；URGENT/INAPP 天然豁免（§5） |
| 只想改免打扰不动渠道 | 重提交缺省 `quiet_hours` 会**重置为未启用**（last-write-wins 无隐式保留） | 该行渠道 + quiet_hours 一起提交 |
| 强制类型锁死没法关 | 平台设了 `mandatory=1`，有 ENABLED 实例时关最后实例 `10606` | 属预期（安全告警类不可静默）；无实例用户豁免，仅 INAPP 合法 |
| 订阅页「站内信」列显示未勾选 | 已知 UI 显示缺陷 E-L4-02（前端 checkbox 绑定空串）；服务端恒补 INAPP 恒首位，契约不受影响 | 展示层缺陷登记不改，接口回读为准 |

## 9. 下一步

- 渠道注册/验证/熔断恢复：[channels.md](channels.md)
- 发送与投递结果查询：[sending.md](sending.md) · 错误码总表：[error-codes.md](../04-参考/error-codes.md)
- 产品语义（矩阵/等级/免打扰口径）：[产品概览](../01-概览/product-overview.md) ·
  架构机制（fanout 双模型/引擎）：[技术白皮书 §4](../01-概览/technical-whitepaper.md)
