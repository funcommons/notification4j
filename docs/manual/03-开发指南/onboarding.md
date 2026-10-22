# 开发指南：接入准备与租户开通

> 适用版本 **v1.4.0**。本文回答接入前的四件事：环境对齐 → 拿到服务实例 → 开通租户拿凭据 → 平台侧配置与接入检查单。
> 端点/参数/错误码核实自 [api-spec](../../api/api-spec.md)（API 唯一事实源）、starter 源码、IT 用例
> （`NfyPlatformTenantTest` / `NfyRegistrationKeyFlowTest`）与 e2e（`frontend/e2e-regression/l1-auth.spec.ts`）。
> 示例统一设 `NFY=http://localhost:9200`（独立部署默认端口）。

## 1. 版本与环境矩阵

| 组件 | 口径 | 出处 |
|---|---|---|
| JDK | 17 | `backend/pom.xml` `<java.version>` |
| Spring Boot | 3.2.7（parent） | `backend/pom.xml` |
| MyBatis-Plus | 3.5.7（BOM 传递） | `backend/pom.xml` |
| framework4j 底座 | v1.7.1（由 starter 传递引入，无需单独声明） | `backend/pom.xml` |
| PostgreSQL | 16（Flyway 自动迁移表结构） | IT 容器 `postgres:16` |
| Redis | 7（token/限流/幂等） | IT 容器 `redis:7-alpine` |

Maven 坐标双口径（同一构件，两种引入方式）：

| 引入方式 | 坐标 |
|---|---|
| JitPack（三方推荐） | `com.github.funcommons.notification4j:notification4j-starter:v1.4.0` |
| 本地构建安装 | `fun.commons.notification4j:notification4j-starter:1.4.0` |

异构语言（Python/Go 等）不引依赖，直接 HTTP 调 OpenAPI（curl 示例见下文与 [api-spec](../../api/api-spec.md)）。

## 2. 先拿到一个通知服务实例（三形态）

| 形态 | 一句话 | 快速开始 |
|---|---|---|
| 独立部署（fat jar） | 独立进程自带数据面/页面/外发引擎，多业务方共享，OpenAPI 直连 | [quickstart-5min.md](../02-快速开始/quickstart-5min.md) |
| 嵌入 local | starter 引入宿主 Spring Boot 进程，`NotifyClient` 零 HTTP 直调 | [quickstart-embed.md](../02-快速开始/quickstart-embed.md) |
| 跨进程 remote | client-starter 零数据面依赖，HTTP 打独立实例（自动 S2S JWT + HMAC 签名） | [quickstart-remote.md](../02-快速开始/quickstart-remote.md) |

三形态业务代码同接口（`NotifyClient` 门面），切换只改依赖坐标与配置
（详见 [../01-概览/product-overview.md](../01-概览/product-overview.md) §4）。前端嵌入消息中心
（iframe 五页 + 铃铛）见 [quickstart-frontend.md](../02-快速开始/quickstart-frontend.md)。

## 3. 租户开通：两条路，终点一致

无论哪条路，终点都是拿到 `{open_id, tenant_secret}`——明文**仅返回一次**（见 §4.2）。

### 3.1 路线 A：平台直建（API-PTE-001，平台域）

适合平台运营方代建、或 L1 信任级直发密钥的场景；发起方是平台侧，完整说明见
[platform-admin.md](platform-admin.md) §3（`$PLATFORM_TOKEN` 的获取也在该篇）。

```bash
curl -X POST "$NFY/nfy/platform/api/v1/tenants" \
  -H "Authorization: Bearer $PLATFORM_TOKEN" -H "Content-Type: application/json" \
  -d '{"name":"租户甲","email":"tenant-a@example.com","privileges":{"signature":false},"config":{"retentionDays":30}}'
```

```json
{"code":0,"data":{"open_id":"3xK9cQ2mWb7d","tenant_secret":"e3f0..."}}
```

- 必填 `name`（≤64）、`email`（合法格式 ≤259，全平台唯一，重复 → `10401`）；可选 `description`（≤512）
  与三组配置 `privileges/config/oem`（字段语义见 §5）。
- 响应不含内部数字 id（雪花 id 不出网，外部一律用 `open_id`）。

### 3.2 路线 B：注册码自助（API-PRK-001 签发 → API-OPEN-001 注册）

**第一步**：平台签发注册码（PRK-001，平台域）：

```bash
curl -X POST "$NFY/nfy/platform/api/v1/registration-keys" \
  -H "Authorization: Bearer $PLATFORM_TOKEN" -H "Content-Type: application/json" \
  -d '{"max_uses":1,"expire_hours":24,"preset":{"privileges":{"signature":false}}}'
```

```json
{"code":0,"data":{"registration_key":"nfy-1a2b3c4d5e6f...","max_uses":1,"expire_hours":24}}
```

- `max_uses` 取值 1~100（缺省 1）、`expire_hours` 取值 1~8760（缺省 24）；`preset` 可预绑
  `privileges/config/oem` 三组配置档，注册成功自动套用到新租户（源码 `RegistrationKeyService.applyPreset`）。
- 完整注册码（`nfy-` 前缀随机码）**仅签发时一次性返回**；平台侧列表只回脱敏
  `code_masked`（前 8 位 + `****`）、`used_count`、`consumed_open_id` 等。

**第二步**：业务方开放域自助注册（OPEN-001，**无鉴权**，IP 限流兜底）：

```bash
curl -X POST "$NFY/nfy/open/api/v1/tenants/register" \
  -H "Content-Type: application/json" \
  -d '{"registration_key":"nfy-1a2b3c4d5e6f...","name":"我的业务方"}'
```

```json
{"code":0,"data":{"open_id":"9cQ2mWb7d3xK","tenant_secret":"a71f..."}}
```

- `registration_key`、`name`（≤64）必填；成功即刻可凭返回的凭据换 token（IT 端到端验证）。
- 额度**原子扣减**：单条 `UPDATE ... WHERE status='ACTIVE' AND expire_at > now() AND used_count < max_uses`
  （DB 单语句 CAS，`NfypRegistrationKeyMapper.consume`；Redis 扣减为后续高并发优化登记项）。
- 无效 / 用尽 / 过期 / 未知码一律 `10608 注册码无效或已失效`（同码同文案，防探测）。
- 自助注册的租户 `email` 存 NULL（空串会命中唯一索引谓词 `email IS NOT NULL` 撞键），后续可由平台 PATCH 补齐。

## 4. 凭据体系：client_id 与 tenant_secret

### 4.1 client_id = open_id（不是 name）

换 token（API-AUTH-001）的 `client_id` 传**租户 open_id**——即创建/注册响应里返回的 OpenID 混淆串：

```bash
curl -X POST "$NFY/nfy/api/v1/auth/token" \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "grant_type=client_credentials&client_id=$OPEN_ID&client_secret=$TENANT_SECRET"
```

```json
{"code":0,"data":{"access_token":"eyJhbGciOi...","token_type":"Bearer","expires_in":"28800"}}
```

- 口径出处：api-spec §2.3「`client_id` 传租户 OpenID（内部雪花 id 不出网）」；IT 与 e2e
  （`helpers/nfy.ts` 的 `mintToken(open_id, secret)`）均按此调用。`name` 只是展示名，**不是**凭据。
- `expires_in` 为**字符串秒数**（fwk4j-web 数值字符串化），默认 28800（8h）；M2M 无 refresh_token，
  续期 = 到期前用密钥重新换。
- `401`：凭据错误**或租户非 ACTIVE**（同码同文案，防探测）；`429`：同一 client_id 连错 5 次锁 15min
  （成功即清零，无 Retry-After 头）。

### 4.2 tenant_secret：明文只有两次见面机会

- 48 位随机十六进制串；**仅创建（PTE-001 / OPEN-001）与重置（PTE-003）时返回明文**（源码
  `PlatformTenantService.create/resetSecret`、`RegistrationKeyService.register`），库内密文存储，
  任何查询接口都查不回明文。
- 丢失的唯一补救是平台侧重置（§4.3）；请按数据库密码同级保管，不要提交进代码仓库。

### 4.3 密钥重置与双版本宽限（API-PTE-003，平台域发起）

```bash
curl -X POST "$NFY/nfy/platform/api/v1/tenants/$OPEN_ID/reset-secret" \
  -H "Authorization: Bearer $PLATFORM_TOKEN" -H "Content-Type: application/json" -d '{}'
```

- 返回新 `tenant_secret`（明文一次）；旧密钥写入 prev 列进入 **24h 宽限期**
  （出厂配置 `framework4j.tenant.secret.grace-hours: 24`）：宽限期内**新旧密钥均可换 token**
  （IT `reset_secret_rotates_and_old_invalid` 实测），宽限结束后旧密钥 401。
- 宽限的目的是让接入方平滑轮换密钥；轮换完成后旧密钥自然失效，无需额外操作。
- 注意：重置**不撤销**已签发的 access token（源码 `resetSecret` 无撤销调用）；要立即断掉全部会话，
  用租户停用/注销（SUSPEND/CLOSE，见 [platform-admin.md](platform-admin.md) §8）。

## 5. 平台侧配置项：privileges / config / oem

PTE-001 创建时可携带，PTE-002 PATCH 可修改（**传哪组、哪组整组覆盖**；未传的组保持不变），
GET 详情回显全部（email 脱敏）。三组均为 jsonb 配置档，**服务端无默认值**——创建时未传即空对象 `{}`，
需要什么配什么（源码 `PlatformTenantService.patchJsonColumn`）。字段契约（api-spec §5.9.2、
`PatchTenantRequest`）：

| 字段 | 类型/取值 | 语义 |
|---|---|---|
| `privileges.signature` | bool | HMAC 签名租户级开关（ADR-0008）：未开启 = 不校验；开启后密钥未注册 = runtime 写面全拒（10302 + error 子类型），密钥注册/轮换走 API-SEC-001。出厂不强制任何路径签名（`framework4j.signature.path-patterns` 为空） |
| `privileges.delivery` | `{"email":bool,...}` | 外发渠道启停组 |
| `config.retentionDays` | int | 数据保留天数 |
| `config.alert_user_ids` | string[] | 告警接收用户 |
| `config.mail` | object | 邮件渠道参数（`password` AES-GCM 加密存储） |
| `oem.theme` / `title` / `logo` | string | 白标外观（logo 经 `/nfy/` base 前缀下发） |
| `oem.hosts` | string[] | iframe `frame-ancestors` / postMessage origin 白名单（运行时经 API-OEM-001 下发）；**新增 host 属嵌入安全边界变更，须二次确认** |

> 运行面当前实际读取的只有 `oem.hosts`（API-OEM-001 下发）；`privileges.signature` 为登记开关，
> 实际拦截是否发生由部署侧 `framework4j.signature.path-patterns` 决定（见 [signature.md](signature.md) §2）；
> `retentionDays` / `alert_user_ids` / `mail` 为配置档预留字段（starter 源码暂无读取方）。
> 配置变更即时生效并审计（api-spec §5.9.2）。

## 6. 接入检查单

发出第一条消息前逐项确认（口径均已核实）：

| 类别 | 检查项 | 依据 / 未过的表现 |
|---|---|---|
| 安全基线 | `tenant_secret` 已离线妥善保存（明文只出现一次） | 丢失只能平台 reset-secret |
| 安全基线 | token 到期前约 5min 重换；密钥/token 不落代码仓库 | 401 不区分凭据错/停用（防探测） |
| 安全基线 | 换 token 失败重试有退避 | 连错 5 次锁 15min（429） |
| 类型与渠道 | 已创建消息类型（**无预置类型**，发送前必须先 TYP-001 建类型） | 未建/停用 → 10601 |
| 类型与渠道 | 知道 level 缺省 = `NORMAL`（**不取类型的 default_level**） | `MessageService` 源码 |
| 类型与渠道 | 强制订阅（mandatory）只能请平台设置（PTE-005 唯一入口） | 租户域提交即忽略 |
| 配额意识 | runtime 出厂限流 100 次/分钟/客户端 IP/路径（Lua 滑窗；出厂 `default-scope: APP` 未命中 fwk4j 小写 scope 分支，实际按 IP 维限流，NAT 共桶） | 超限 → 10500 + Retry-After |
| 配额意识 | 同时生效公告 ≤ 20 条/租户（平台公告独立计数） | 超限 → 10611 |
| 配额意识 | 同类型用户渠道 ≤ 5 个/用户 | 超限 → 10605 |
| Header 纪律 | 发送面无需 `X-User-Id`；**查询面（T+U 端点）必带** | 缺 → 10101 |
| Header 纪律 | 创建类写操作带 `Idempotency-Key`（UUID v4；豁免清单见 api-spec §2.4） | 同 key 不同 body → 10501 |

错误码全表见 [../04-参考/error-codes.md](../04-参考/error-codes.md) 与 api-spec §6。

下一步：token 与型别细节见 [auth.md](auth.md)；发送/批量/撤回见 [sending.md](sending.md)；
平台运营方专用面见 [platform-admin.md](platform-admin.md)。
