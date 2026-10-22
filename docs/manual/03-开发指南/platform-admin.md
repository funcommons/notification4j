# 开发指南：平台域 API（平台运营方）

> 适用版本 **v1.4.0**。面向**平台运营方**（持有平台密钥的一方）：租户治理、注册码签发、平台公告、
> 跨租户统计与站内信排查。全部端点属平台域 `/nfy/platform/api/v1/*`；契约见
> [api-spec §4.4 / §5.9.2](../../api/api-spec.md)，行为口径取自 IT（`NfyPlatformDomainTest` /
> `NfyPlatformTenantTest` / `NfyRegistrationKeyFlowTest`）与 starter 源码。
> 示例设 `NFY=http://localhost:9200`。

## 1. 平台域鉴权：PLATFORM client 换「合成 token」

平台密钥由部署配置 `framework4j.tenant.platform.client-id / client-secret` 提供
（出厂 client-id 为 `PLATFORM`，secret 走环境变量 `PLATFORM_CLIENT_SECRET`）。换取 token：

```bash
curl -X POST "$NFY/nfy/api/v1/auth/token" \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "grant_type=client_credentials&client_id=PLATFORM&client_secret=$PLATFORM_SECRET"
```

```json
{"code":0,"data":{"access_token":"eyJhbGciOi...","token_type":"Bearer","expires_in":"28800"}}
```

- **合成 token 口径**（第 8a 步 IT 实测钉死，见 `NfyPlatformMessageController` 源码注释）：PLATFORM
  client 经 TenantAuthEndpoint 换到的是「**tenant_id=0 的 TENANT 型别 token**」。平台端点统一标注
  `@RequiresToken(value="TENANT")`（token 校验 + claims 回填）与 `@PlatformDomain`（域闸校验
  `tenant_id == 0`），二者缺一不可。
- 域隔离双向生效（DomainGuard，ADR-0007）：**租户 token（tenant_id>0）打平台域 → HTTP 403 +
  信封 code=403**；PLATFORM token 打租户域同样 403。错误码表中的 `10207 令牌类型不匹配`为登记占位，
  实际型别隔离由 DomainGuard 的 403 表达。
- token 8h 有效；平台公告/统计等落库数据均归 `tenant_id=0`（平台自身即「0 号租户」）。

## 2. 五组 API 速览

| 组 | 端点（前缀 `/nfy/platform/api/v1`） | 用途 | 关键参数 / 语义 |
|---|---|---|---|
| PTE-001~005 租户治理 | `GET/POST /tenants`；`GET/PATCH /tenants/{open_id}`；`POST .../reset-secret`；`POST .../status`；`POST .../type-mandatory` | 建租户 / 详情配置 / 重置密钥 / 生命周期 / 强制订阅 | 创建与重置返回明文 `tenant_secret` 仅一次；`status` 收 `{action}`；PTE-005 为 mandatory 唯一入口 |
| PRK-001 注册码 | `GET/POST /registration-keys` | 签发 / 列表（脱敏） | `{max_uses(1~100,缺省1), expire_hours(1~8760,缺省24), preset}`；完整码一次性返回 |
| PAN-001~004 平台公告 | `GET/POST /announcements`；`GET/PATCH /announcements/{id}`；`POST .../publish`；`POST .../offline` | 全租户公告 | 与租户公告同构、tenant_id=0；**`channel_ids` 提交即被清空**（平台域无渠道资源）；非草稿态修改/重复 publish·offline → 10402 |
| PST-001 跨租户概览 | `GET /stats/overview` | 运营大盘 | → `tenant_count/active_tenants/today_messages/today_deliveries/deliver_success_rate/dead_count/dead_tenants[]` |
| PPM-001/002 平台站内信（v1.4.0） | `GET /messages`；`GET /messages/{message_id}` | 跨租户只读排查 | 筛选 `user_id/type_code/created_from/created_to/keyword` + `offset/limit`（默认 20 上限 100）；详情含 `read_count` |

## 3. 租户治理（PTE-001~005）

创建租户（明文密钥仅此一次；`email` 全平台唯一，重复 → 10401）：

```bash
curl -X POST "$NFY/nfy/platform/api/v1/tenants" \
  -H "Authorization: Bearer $PLATFORM_TOKEN" -H "Content-Type: application/json" \
  -d '{"name":"租户甲","email":"tenant-a@example.com","privileges":{"signature":false},"config":{"retentionDays":30}}'
```

```json
{"code":0,"data":{"open_id":"3xK9cQ2mWb7d","tenant_secret":"e3f0..."}}
```

- 详情 `GET /tenants/{open_id}` 回显 `privileges/config/oem` 三组配置，email 脱敏（`t***a@example.com`）；
  列表 `GET /tenants` 回 `{open_id,name,email(脱敏),status,created_at}`。
- 配置修改 `PATCH /tenants/{open_id}`：可改 `name/description` 与三组配置（传哪组、哪组整组覆盖）；
  字段语义与安全注意事项见 [onboarding.md](onboarding.md) §5。

强制订阅（PTE-005，mandatory 的**唯一**设置入口——租户域 admin 面提交该字段会被忽略）：

```bash
curl -X POST "$NFY/nfy/platform/api/v1/tenants/$OPEN_ID/type-mandatory" \
  -H "Authorization: Bearer $PLATFORM_TOKEN" -H "Content-Type: application/json" \
  -d '{"type_code":"ORDER","mandatory":1}'
```

- `mandatory` 仅取 0/1，缺失或非 0/1 → 10100；租户或类型不存在 → 10400（同码防探测）。

## 4. 注册码（PRK-001）

```bash
curl -X POST "$NFY/nfy/platform/api/v1/registration-keys" \
  -H "Authorization: Bearer $PLATFORM_TOKEN" -H "Content-Type: application/json" \
  -d '{"max_uses":1,"expire_hours":24,"preset":{"privileges":{"signature":false}}}'
```

```json
{"code":0,"data":{"registration_key":"nfy-1a2b3c4d5e6f...","max_uses":1,"expire_hours":24}}
```

- 完整码仅签发时一次性返回；`GET` 列表只回脱敏 `code_masked`（前 8 位 + `****`）、`used_count`、
  `consumed_open_id`（未消费为 null）。
- 额度扣减为 DB 单语句原子 CAS（`used_count < max_uses AND ACTIVE AND 未过期`），业务方凭码在开放域
  自助注册（OPEN-001，无鉴权）；无效/用尽/过期一律 10608 同码防探测。完整接入流程见
  [onboarding.md](onboarding.md) §3.2。

## 5. 平台公告（PAN-001~004）

```bash
curl -X POST "$NFY/nfy/platform/api/v1/announcements" \
  -H "Authorization: Bearer $PLATFORM_TOKEN" -H "Content-Type: application/json" \
  -d '{"title":"平台公告P1","content":"c","effective_at":1759000000000,"expire_at":1759100000000,"need_confirm":1,"channel_ids":["999"],"biz_no":"pan-1"}'
```

```json
{"code":0,"data":{"announcement_id":"892310293123123","status":"DRAFT"}}
```

- 与租户公告同构（`title` ≤132 / `content` ≤20000 / `level` NORMAL|IMPORTANT|URGENT / 时间窗为 epoch
  毫秒 / `biz_no` ≤68），但 scope=PLATFORM、tenant_id=0，publish 后**全部租户**的 runtime 公告列表可见。
- **`channel_ids` 置空禁用**：平台域无渠道资源，即使提交非空（如上例 `["999"]`）服务端也强制清空
  （详情恒回 `[]`）；站外触达只走「订阅 ANNOUNCEMENT 站外渠道的用户渠道」路径。
- 状态机同租户公告：PATCH 限 DRAFT（已发布修改 → 10402）；publish 后重复 publish → 10402；
  offline 立即对用户侧不可见，重复 offline → 10402。
- 生效公告配额：平台（tenant 0）**独立计数**，同时生效 ≤ 20 条，第 21 条 publish → 10611
  （与各租户自己的 20 条互不挤占）。
- 列表 `GET /announcements?status=PUBLISHED&offset=&limit=`（Offset 分页）。

## 6. 跨租户概览（PST-001）

```bash
curl -s "$NFY/nfy/platform/api/v1/stats/overview" -H "Authorization: Bearer $PLATFORM_TOKEN"
```

```json
{"code":0,"data":{"tenant_count":"42","active_tenants":"40","today_messages":"1024",
  "today_deliveries":"980","deliver_success_rate":"9876","dead_count":"3",
  "dead_tenants":[{"open_id":"3xK9cQ2mWb7d","dead_count":"3"}]}}
```

- 数值字段由 Long 字符串化（fwk4j-web 信封口径）。`deliver_success_rate` 为**万分比**（0~10000；当日无样本取 0）。
- `dead_tenants` 为 DEAD 投递最多的租户 Top 20（`{open_id, dead_count}`，Long 字符串化），
  用于快速定位问题租户。

## 7. 平台站内信查询（PPM-001/002，v1.4.0）

跨租户**只读**排查面：数据源为 `nfya_message` 主档，不触发送链（发送仍走租户域 MSG-001）。
`user_id` 筛选经收件人表子查询（语义 =「发给该用户的消息」）；`keyword` 匹配标题或正文。

```bash
curl -s "$NFY/nfy/platform/api/v1/messages?user_id=u_plat&type_code=ORDER&limit=20" \
  -H "Authorization: Bearer $PLATFORM_TOKEN"
```

```json
{"code":0,"data":{"list":[{"message_id":"892310293123123","tenant_id":"88701",
  "type_code":"ORDER","level":"NORMAL","title":"作品生成失败","status":"SENT",
  "receiver_count":"1","created_at":"1787700000000"}],"total":"1"}}
```

详情 `GET /messages/{message_id}` 在列表字段之上追加 `biz_no/content/link_url/sender/read_count`
（已读回执统计；Long 一律字符串化，如 `"read_count":"0"`）。

- 防探测：不存在或非数字 id 一律 10400（与资源不存在同码）。
- 租户 token 打平台消息面 → DomainGuard 403（IT 实测）；`offset` 默认 0、`limit` 默认 20 上限 100。

## 8. 租户生命周期语义（PTE-004）

```bash
curl -X POST "$NFY/nfy/platform/api/v1/tenants/$OPEN_ID/status" \
  -H "Authorization: Bearer $PLATFORM_TOKEN" -H "Content-Type: application/json" \
  -d '{"action":"SUSPEND"}'
```

| action | 前置状态 | 效果（源码 `PlatformTenantService.changeStatus` + IT 实测） |
|---|---|---|
| SUSPEND | 仅 ACTIVE | **即时阻断**：换 token 立即 401（与凭据错同码防探测）、全部存量会话撤销、签名面同断（非 ACTIVE 租户不出签名密钥） |
| RESUME | 仅 SUSPENDED | 恢复 ACTIVE，原密钥可继续换 token |
| CLOSE | 非 CLOSED | 终态。前置：该租户无 PENDING/SENDING 投递任务，否则 10402（「存在未完成投递任务 N 条，暂不可注销」）；执行时撤销全部会话。「冷静期」为契约预留（V1.1 登记），当前以未投递任务数为闸 |

- 状态机 `ACTIVE ⇄ SUSPENDED → CLOSED`：CLOSED 为终态，任何再迁移 → 10402；
  非法 action（如 DELETE）→ 10100（仅允许 SUSPEND/RESUME/CLOSE）。
- 密钥重置（PTE-003）与生命周期独立：reset-secret 走 24h 双版本宽限且不撤销存量 token，
  详见 [onboarding.md](onboarding.md) §4.3。

相关阅读：[onboarding.md](onboarding.md)（接入方视角的开通流程）、
[../04-参考/error-codes.md](../04-参考/error-codes.md)（错误码速查）、
[api-spec §6](../../api/api-spec.md)（错误码体系与防探测约定）。
