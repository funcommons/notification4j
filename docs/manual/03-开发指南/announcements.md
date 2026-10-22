# 开发指南：公告（全员触达 / 确认回执 / 平台公告）

> 适用版本 **v1.4.0**。端点、字段、状态机与错误码逐一对照仓内源码核验（`AnnouncementService` /
> `AnnouncementAdminService` / `DeliveryPlanService` / `NfyAdminAnnouncementController` /
> `NfyRuntimeAnnouncementController` / `NfyPlatformAnnouncementController`，及 IT `NfyAnnouncementAdminTest` /
> `NfyAnnouncementFlowTest` / `NfySmokeTest`、E2E `l3-announcements.spec.ts`）。契约全集以
> [api-spec](../../api/api-spec.md) 为准；token 获取见 [auth.md](auth.md)。

## 1. 公告 vs 定向消息：怎么选

| 维度 | 定向消息（[sending.md](sending.md)） | 公告（本文） |
|---|---|---|
| 触达模型 | fanout-on-write：send 时逐人写 recipient 行 | **fanout-on-read**：只写一条公告主档，用户读时按 `tenant_id IN (0, 本租户) AND PUBLISHED AND now∈[effective_at, expire_at)` 现算生效集，**免逐人落库展开** |
| 接收对象 | `user_ids` 指定（≤1000 / 批量 ≤100000） | **全员**（本租户公告）或**全平台所有租户**（平台公告），不可按人筛选 |
| 确认回执 | 无（仅已读 read_status） | `need_confirm=1` 时支持「我知道了」确认回执，admin 可查 confirm_count 与确认名单 |
| 有效期 | 永久可见（撤回才隐藏） | **生效窗口** `effective_at ~ expire_at`，过期自动从用户侧消失 |
| 未读口径 | 计入 `unread_count` | 需确认且未确认的生效公告计入 `unconfirmed_count`（与未读数合成角标 total） |
| level 缺省 | `NORMAL` | **`IMPORTANT`**（`buildDraft` 缺省值） |
| 站外推送 | 按接收人订阅矩阵展开 | 公共渠道 + 订阅 `ANNOUNCEMENT` 类型的用户渠道（§6） |

一句话选型：**发给「具体的某些人」用消息；广而告之、带生效期/需确认用公告。**

## 2. 租户公告管理面 AAN-001~005（TENANT token）

生命周期状态机（源码真实常量）：**`DRAFT` → `PUBLISHED` → `OFFLINE`**。
`EXPIRED` 仅作为列表过滤枚举保留，**不会有行被置为 EXPIRED**——到期是时间窗判定，不回写状态。

| 端点 | 方法 | 说明 |
|---|---|---|
| `/nfy/api/v1/admin/announcements` | GET / POST | AAN-001 列表（`status` 过滤 + offset/limit，缺省 20 上限 100，updated_at 倒序）/ 创建 |
| `/{announcement_id}` | GET / PATCH | AAN-002 详情（全字段）/ 修改 |
| `/{announcement_id}/publish` | POST | AAN-003 发布（DRAFT→PUBLISHED） |
| `/{announcement_id}/offline` | POST | AAN-004 下线（PUBLISHED→OFFLINE，立即对用户侧不可见） |
| `/{announcement_id}/stats` | GET | AAN-005 统计 `{read_count, confirm_count, confirms:[{userid, confirm_at}]}`（offset/limit 分页） |

创建 Body（`PostAnnouncementsRequest`；创建即 DRAFT，响应 `{announcement_id, status:"DRAFT"}`）：

| 字段 | 类型 | 必填 | 约束 / 缺省 |
|---|---|---|---|
| title | String | 是 | 非空 ≤132 |
| content | String | 是 | 非空 ≤20000（markdown 子集） |
| level | String | 否 | `NORMAL/IMPORTANT/URGENT`，缺省 `IMPORTANT` |
| effective_at | Long | 是 | epoch 毫秒；缺失被参数校验拦截（`@NotNull` → `10100`；service 层另有 `10101「{field} 缺失」`防御） |
| expire_at | Long | 是 | epoch 毫秒；须晚于 effective_at，否则 `10102「生效窗口非法：expire_at 必须晚于 effective_at」` |
| need_confirm | Integer | 否 | 0/1，非此二者 `10100`；缺省 0 |
| link_url | String | 否 | ≤516 |
| channel_ids | String[] | 否 | 公共渠道 id，≤20 个、单个 ≤20 字符；**不校验存在性**（发布外发时过滤无效 id） |
| biz_no | String | 否 | ≤68；幂等闸，同租户重复 `10401「业务号重复，公告已存在」`（空白视同放弃幂等） |

修改（PATCH）**限 DRAFT 态**，非 DRAFT `10402「非 DRAFT 态不可修改」`（条件更新防并发改态）；可改
title/content/level/need_confirm/link_url/channel_ids/时间窗。发布：非草稿 `10402「非草稿态不可发布」`，成功响应
`{announcement_id, status:"PUBLISHED", delivery_planned }`；下线：非发布态 `10402「已下线或未发布」`，
重复下线同码（幂等失败而非成功）。

## 3. 用户面 ANN-001/002/003（TENANT token + X-User-Id）

- **ANN-001** `GET /nfy/api/v1/runtime/announcements?cursor=&limit=`（缺省 20 上限 50，Cursor 分页，
  published_at 倒序）：**平台公告（tenant 0）+ 本租户公告合并生效列表**；每项含
  `{announcement_id, title, scope, level, content, link_url, need_confirm, published_at, my_status}`，
  `my_status` 由回执反查：`NONE / READ / CONFIRMED`。
- **ANN-002** `POST .../runtime/announcements/{id}/read`：标记阅读，幂等（唯一回执约束兜底），响应 `{read:true}`。
- **ANN-003** `POST .../runtime/announcements/{id}/confirm`：「我知道了」，幂等（重复确认仍返回
  `{confirmed:true}`，不重复计数）；首次确认以条件 UPDATE 原子判定，公告 `confirm_count` 恰好 +1。
  需要确认的公告会推高 `GET .../messages/unread-count` 的 `unconfirmed_count`，确认后恰好减 1（E2E L3-03 实测）。

错误口径：不存在 / 未发布 / 未到生效期 / 跨租户一律 `10400「公告不存在或未生效」`（防探测）；
已过 expire_at 再读/确认 `10402「公告已失效」`。下线后的公告对 read/confirm 同样 `10400`（IT 实测）。

## 4. 平台公告 PAN-001~004（平台域）

基路径 `/nfy/platform/api/v1/announcements`，PLATFORM 身份换取的「tenant_id=0 合成 TENANT token」+
`@PlatformDomain` 域闸（租户 token 访问平台面 → 403）。与 AAN 同构复用同一 service，差异点：

- **scope=PLATFORM、tenant_id=0**：发布后出现在**所有租户**用户的生效列表里（ANN-001 的 `IN (0, tid)` 合并）。
- **channel_ids 强制置空**：平台域无渠道资源语义——创建时忽略提交值，PATCH 时同样把 channel_ids 清空为 `[]`；
  站外推送只走「订阅 ANNOUNCEMENT 类型的用户渠道」分支（§6）。
- 端点：GET/POST 列表创建（PAN-001）、GET/PATCH 详情修改（PAN-002，**限草稿态**）、POST `/{id}/offline`
  （PAN-003）、POST `/{id}/publish`（PAN-004）——无 stats 端点，确认数看 PAN-002 detail 的
  `confirm_count`（回执实数口径，字符串）。
- 平台域的「同时生效 ≤20」按 tenant_id=0 **独立计数**，不占各租户名额。
- 确认回执落**公告归属域**（平台公告归 0 域）：租户用户确认平台公告后，平台侧 detail 的 confirm_count 可见，
  用户侧 my_status/unconfirmed_count 双域反查口径一致（第 28 步修复回归，E2E L3-05 覆盖）。

## 5. 生效窗口与同时生效上限 ≤20

- 生效窗口：`effective_at ≤ now < expire_at` 才出现在用户侧；窗口在创建/PATCH 时校验
  （expire 必须晚于 effective，`10102`）。
- **同租户同时生效上限 20 条**：出处 `AnnouncementAdminService.MAX_CONCURRENT_EFFECTIVE = 20`，publish 时
  统计本租户 `status=PUBLISHED AND expire_at > now` 的条数，达到 20 再发布 → `10611`
  「同时生效公告不可超过20条，请先下线旧公告」。注意口径：**未来生效窗口的已发布公告同样占名额**
  （防「先囤未来公告到期同时生效」击穿上限）；并发双发布的软限竞态为登记在案的已知边界。
  平台域（tenant 0）独立按同规则计数。

## 6. 站外推送与 ANNOUNCEMENT 订阅类型

发布（publish）事务内由 `DeliveryPlanService.planForAnnouncement` 生成站外投递行（返回值即
`delivery_planned`）：

1. **租户公告**：`channel_ids` 勾选的**公共渠道**（scope=TENANT 且 ENABLED，属本租户；无效 id 静默跳过）
   ——每个公共渠道一条投递行，userid 为空串；
2. **两类公告都有**：订阅矩阵中 **type_code=`ANNOUNCEMENT`** 的用户，按其订阅行勾选的渠道实例外发；
   命中 quiet_hours 静默窗则推迟到窗结束（推迟非丢弃）。

前提：租户需先创建 `type_code` 为 `ANNOUNCEMENT` 的消息类型（订阅保存会校验类型存在且启用，否则 `10601`），
用户在订阅页对其勾选渠道——见 [subscription.md](subscription.md)。站内展示（ANN-001 列表）不依赖任何类型/渠道。

## 7. curl 全链（租户公告：建 → 发布 → 合并生效 → 确认 → 统计）

payload 取自 IT `NfyAnnouncementAdminTest` / E2E `l3-announcements.spec.ts`；时间字段为 epoch 毫秒。

```bash
BASE=http://localhost:9200
EFF=$(($(date +%s) * 1000 - 3600))          # 一小时前生效
EXP=$(($(date +%s) * 1000 + 7 * 86400000))  # 7 天后失效

# ① 创建（→ DRAFT）
curl -s -X POST $BASE/nfy/api/v1/admin/announcements \
  -H "Authorization: Bearer $TENANT_TOKEN" -H "Content-Type: application/json" \
  -d "{\"title\":\"国庆放假通知\",\"content\":\"10-01 至 10-07 放假\",\"level\":\"IMPORTANT\",
       \"effective_at\":$EFF,\"expire_at\":$EXP,\"need_confirm\":1,\"biz_no\":\"ann-holiday-1\"}"
# → data: { "announcement_id": "1943...", "status": "DRAFT" }
ANN_ID=1943...

# ② 发布（草稿才可发布；重复发布 10402；生效满 20 条时 10611）
curl -s -X POST $BASE/nfy/api/v1/admin/announcements/$ANN_ID/publish \
  -H "Authorization: Bearer $TENANT_TOKEN"
# → data: { "announcement_id": "...", "status": "PUBLISHED", "delivery_planned": 0 }

# ③ 用户侧合并生效列表（平台公告也会混排在其中；published_at 倒序）
curl -s "$BASE/nfy/api/v1/runtime/announcements?limit=20" \
  -H "Authorization: Bearer $TENANT_TOKEN" -H "X-User-Id: u_1001"
# → data.list[].my_status: "NONE"

# ④ 标记阅读 + 确认（均幂等；确认后 my_status=CONFIRMED）
curl -s -X POST $BASE/nfy/api/v1/runtime/announcements/$ANN_ID/read \
  -H "Authorization: Bearer $TENANT_TOKEN" -H "X-User-Id: u_1001"
curl -s -X POST $BASE/nfy/api/v1/runtime/announcements/$ANN_ID/confirm \
  -H "Authorization: Bearer $TENANT_TOKEN" -H "X-User-Id: u_1001"
# → data: { "confirmed": true }

# ⑤ 统计（read_count / confirm_count 为字符串——Long→String 序列化契约）；下线用 /offline
curl -s "$BASE/nfy/api/v1/admin/announcements/$ANN_ID/stats" \
  -H "Authorization: Bearer $TENANT_TOKEN"
# → data: { "read_count": "1", "confirm_count": "1", "confirms": [{ "userid": "u_1001", "confirm_at": "..." }] }
```

平台公告把 `BASE` 段换成 `/nfy/platform/api/v1/announcements`、token 换 PLATFORM 身份即可（建 → publish →
全租户用户在 ③ 的列表可见；创建时无须也不接受 channel_ids）。

## 8. 进程内快捷方式（NotifyClient）

嵌入形态可用门面一步「创建并立即发布」（`LocalNotifyClient.announce` = create + publish）：
`AnnounceRequest.of(title, content, level, needConfirm, effectiveAtEpochMs, bizNo)`，生效窗口缺省
**立即生效 + 7 天失效**、bizNo 缺省放弃幂等；返回 `AnnounceResult(announcementId)`。
门面装配与示例见 [quickstart-embed.md](../02-快速开始/quickstart-embed.md)。

## 下一步

- 定向消息发送/批量/撤回：[sending.md](sending.md) · 订阅矩阵：[subscription.md](subscription.md)
- 平台域管理与租户生命周期：[platform-admin.md](platform-admin.md)
- 错误码速查：[error-codes.md](../04-参考/error-codes.md) · 契约全集：[api-spec](../../api/api-spec.md)
