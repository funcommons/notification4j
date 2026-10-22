# 开发指南 · 认证与令牌（client_credentials / M2M）

> notification4j 使用手册 · 03-开发指南第 2 篇 · 版本口径 **v1.4.0**（Maven `1.4.0` / tag `v1.4.0`）。
> 面向读者：调 OpenAPI 的业务方后端工程师（换 token、带对头、看懂 102xx/103xx 信封）。
> 本文所有端点 / 头 / 错误码 / 参数逐一核实自 v1.4.0 源码与 IT：
> `NfyAuthFlowTest` / `NfyPlatformDomainTest` / `NfyPlatformTenantTest`（backend/notification4j-it）、
> `NfyTenantContexts` / `PlatformTenantService`（starter）、app `application.yml`、
> framework4j v1.7.1 `TenantAuthTemplate` / `TenantAuthEndpoint` / `DomainGuardInterceptor` /
> `TokenInterceptor` / `AccessTokenValidationStrategy` / `UserIdContext`；契约口径见 [api-spec §2.3/§2.4/§6](../../api/api-spec.md)。

## 1. token 双型别模型：谁的身份，能打哪个域

notification4j 是 M2M（机器对机器）鉴权：**没有用户登录**，凭据是平台侧发给租户的一对
`client_id / client_secret`，换取的 JWT 即长期会话凭据（无 refresh_token）。

| 身份 | 怎么获得 | token 内容 | 能打哪个域 |
|---|---|---|---|
| **租户身份** | 租户凭据换 token（§2） | 型别 `TENANT`，claims `tenant_id={雪花id>0}` | 租户域 runtime/admin（`/nfy/api/v1/**`） |
| **平台身份** | 平台凭据换 token（§2 curl ①） | 同样是 **`TENANT` 型别**的**合成 token**，claims `tenant_id=0`（`NfyPlatformDomainTest` 顶部注释 + fwk4j `TenantAuthTemplate.syntheticPlatformTenant`：`framework4j.tenant.platform.tenant-id` 缺省 0；`policies.PLATFORM` 段不在此链路生效） | 平台域（`/nfy/platform/api/v1/**`） |

> 契约文档常把平台身份简写为「PLATFORM token」（api-spec §2.2）；实现口径如上——**型别都是
> TENANT，身份差异在 `tenant_id` 是否为 0**。三域前缀与用途全表见 api-spec §2.2。

### 1.1 双层守卫：型别校验 → 域闸 → X-User-Id

每个 Controller 都标注 `@TenantDomain`（runtime/admin 面）或 `@PlatformDomain`（平台面）+
`@RequiresToken(value="TENANT", type="access")`（starter 22 个 Controller 逐一核实，20 个带此
组合；例外 2 个均无任何注解——`NfyOpsHealthController` 健康探针免鉴权、`NfyOpenRegisterController`
开放域注册免鉴权靠 IP 限流兜底）。请求过三关：

```mermaid
flowchart TD
    A[请求进入] --> B{"① TokenInterceptor<br/>@RequiresToken 型别校验"}
    B -->|无注解| Z[跳过：不校验也不回填 claims<br/>（ops/health、open 域等）]
    B -->|缺 Authorization 头| E1["信封 10200 未提供认证令牌"]
    B -->|JWT 验签失败 / 格式坏| E2["信封 10202 凭证无效 / 10207 格式错误"]
    B -->|token 型别 ≠ TENANT| E3["信封 10300 令牌类型不匹配"]
    B -->|Redis 会话缺失/过期| E4["信封 10201 令牌已过期或不存在"]
    B -->|校验通过| C[claims 回填 TokenContext<br/>（含 tenant_id）]
    C --> D{"② DomainGuardInterceptor<br/>@TenantDomain / @PlatformDomain 域闸"}
    D -->|@PlatformDomain 且 tenant_id≠0<br/>（租户 token 打平台域）| F1["HTTP 403 + 信封 code=403"]
    D -->|@TenantDomain 且 tenant_id=0<br/>（平台 token 打租户域）| F1
    D -->|claim 缺失| F1
    D -->|身份匹配| G["③ UserIdContextInterceptor<br/>解析 X-User-Id 头"]
    G --> H[业务 handler：NfyTenantContexts.tenantId()<br/>T+U 端点再取 userId()，缺头 → 信封 10101]
```

三条铁律（均 IT 钉死）：

1. **TENANT token 打平台域 → HTTP 403 + 信封 code=403**（`NfyAuthFlowTest:285-288`；反向
   平台 token 打租户域同码，`NfyPlatformDomainTest:198-211`）。注意这是**例外路径**：DomainGuard
   直接写 HTTP 403 + 框架信封；其余认证失败都是 HTTP 200 + 信封 code（`NfyExceptionHandler`
   对 `AuthException` 统一 `@ResponseStatus(OK)`）。
2. **型别不匹配 → 信封 10300「令牌类型不匹配」**（fwk4j v1.7.1 `AccessTokenValidationStrategy`：
   `annotation.value().equals(type)` 不等即抛）。典型触发：client-starter 默认装配注入 **SERVICE 型**
   S2S JWT 调 TENANT 端点（[quickstart-remote §6](../02-快速开始/quickstart-remote.md)）。
   api-spec §6.1 登记的 `10207 令牌类型不匹配` 为占位语义、无实际发射方（fwk4j 自身 10207 只用于
   令牌格式/解析类失败——JWT 段数/Base64/缺 exp 坏包（`TokenUtils.parseToken`）与 Redis 会话
   元数据解析失败（`AccessTokenValidationStrategy`），均与「型别」无关）。
3. **query/用户面数据必须带 X-User-Id**（§3），缺头在业务层被拒，不在鉴权层。

## 2. 换 token（API-AUTH-001）

```http
POST /nfy/api/v1/auth/token
Content-Type: application/x-www-form-urlencoded

grant_type=client_credentials&client_id={client_id}&client_secret={client_secret}
```

端点由 framework4j-tenant `TenantAuthEndpoint` 提供（app yml `framework4j.tenant.auth.path`）；
form body 与 query string 两种形态都接受。该端点在 access-token 拦截与限流两处白名单内，
**无需也不带 Authorization 头**。

### 2.1 请求参数（全）

| 参数 | 取值 | 核实出处 |
|---|---|---|
| `grant_type` | 固定 `client_credentials`，其他值 → 信封 400「不支持的grant_type，仅支持client_credentials」 | `TenantAuthTemplate.postToken` |
| `client_id` | **租户 OpenID（推荐，12 字符混淆串）**；也接受原始雪花 id 数字串、租户 name（三形态向后兼容，`TenantAuthTemplate.findTenant`）；平台身份用出厂字面量 `PLATFORM`（env `PLATFORM_CLIENT_ID` 可改，缺省即 `PLATFORM`） | 同上 + `Framework4jTenantProperties.Platform` |
| `client_secret` | 租户：`tenant_secret` 明文（平台建租户 PTE-001 / 重置 PTE-003 时一次性下发，平时加密存库不回显）；平台：`PLATFORM_CLIENT_SECRET` 环境变量 | `PlatformTenantService.create/resetSecret` + app yml |

### 2.2 响应（成功）

```json
{ "code": 0, "message": "操作成功",
  "data": { "access_token": "eyJhbGciOi...", "token_type": "Bearer", "expires_in": "28800" },
  "error": null, "trace_id": "c0a8010116983728001", "timestamp": 1787700000000 }
```

| 字段 | 说明 |
|---|---|
| `access_token` | JWT。payload 含 `iss/sub/type/nonce/hash/iat/exp/jti` + 嵌套 `claims.tenant_id`（出厂 `embed-claims=true`，token 自包含可解码判身份域；服务端信任源仍是 Redis 会话）。**user_id 永不进 JWT** |
| `token_type` | 恒 `Bearer`，调用时拼 `Authorization: Bearer <access_token>` |
| `expires_in` | 有效期秒数，**字符串**（fwk4j-web 信封 Long→String 策略；IT 断言 `"expires_in":"28800"`）。出厂 28800s=8h，配置上限 43200s=12h |

### 2.3 失败语义（auth 端点专用，与业务错误码分段无关）

| 信封 code | message（实测原文） | 触发 |
|---|---|---|
| 400 | `不支持的grant_type，仅支持client_credentials` / `client_id和client_secret不能为空` | 参数缺失或 grant_type 非法 |
| 401 | `client_id或client_secret无效` | 凭据错、租户不存在、**或租户非 ACTIVE**——三种情形同码同文案防探测（`NfyPlatformTenantTest:230-237` SUSPEND 后换 token 实测） |
| 429 | `认证失败次数过多，已锁定 15 分钟` | 防爆破锁定（§5） |

## 3. X-User-Id：哪些端点必须、什么约束

`X-User-Id` 是**调用方声明的终端用户标识**：透传不鉴权、不建档、不进 JWT（fwk4j
`UserIdContext` 红线：鉴权决策只信 token claim）。约束：字符串、契约约定 **≤64**（api-spec §2.4）；
框架不校验长度与存在性，**存在性由业务层强制**——T+U 端点取值走 `NfyTenantContexts.userId()`，
缺失/空白 → **信封 10101「X-User-Id 不能为空」**。

| 端点族 | 要不要带 | 依据（api-spec §4.2 鉴权列） |
|---|---|---|
| runtime **用户面**：我的消息/详情/已读/未读数/最近消息（MSG-004~008）、公告列表/阅读/确认（ANN-001~003）、我的渠道（CHN-001~005）、订阅矩阵（SUB-001/002） | **必须**（T+U） | 全部标 `T+U` |
| runtime **发送面**：发送/批量/撤回/查发送结果/Job 轮询/字典/oem（MSG-001~003/009、JOB-001、DICT-001、OEM-001） | 不带 | 标 `T`/`T+S可选` |
| admin 管理面、平台域 | 不带 | 标 `T`/`P` |

## 4. token 生命周期与失效

- **会话模型**：一个（型别, tenant_id）对应一条 Redis 会话。**同一租户再换一次 token，旧 token 即失效**
  ——新会话覆盖旧 nonce，旧 token 下次请求 → 信封 10205「账号已在别处登录」。双实例共用同一凭据
  会互踢，请给每个接入方独立租户（或错峰重换）。
- **有效期**：签发起 8h 绝对上限（`hardExpireAt`，到点 10201「会话已达最大时长，请重新登录」）；
  出厂策略 `auto-renew: true` + `renew-increment: 1800`——每次校验通过把会话 TTL 滑动续到 30min，
  即**持续使用最长 8h、静置 30min 即过期**。M2M 无 refresh_token，续期=**到期前用密钥重新换**
  （建议提前 5min，api-spec §2.3）。每次通过校验的响应带 `X-Token-Expire-At` 头（剩余秒数），
  客户端可据此择机重换。
- **登出语义**：无登出端点。平台侧主动失效动作：PTE-004 **SUSPEND/CLOSE 撤销该租户全部存量会话**
  （`PlatformTenantService.changeStatus → TenantSessionRevoker` 删 Redis 会话，在途 token 下次请求
  10201）；PTE-003 重置密钥**不撤在途会话**（v1.4.0 实现口径，`resetSecret` 不调撤销——api-spec
  §5.9.2 该行表述与实现不一致，以实现为准）——旧密钥换新 token 仍有 24h 宽限，需要立即断流请
  配合 SUSPEND。
- **SUSPEND 即时阻断**（三面同断，均已核实）：换 token → 信封 401（与凭据错同码）；在途 token →
  10201；签名面 → `NfyTenantSecretProvider` 对非 ACTIVE 租户不出密钥 → 10200。
- **前端会话失效重握手**：嵌入的消息中心 SPA 收到的 token 过期后触发 `expired` 事件，回到
  waiting 态并重发 READY，向宿主父页重新索取 token（`useEmbedHandshake` 契约）——宿主侧只需
  监听并回发新 token，详见 [frontend-embed.md](frontend-embed.md)（本目录）。

## 5. 防爆破：换 token 锁定策略

| 项 | 出厂值 | 核实 |
|---|---|---|
| 锁定阈值 | 连续失败 **5 次**（`framework4j.tenant.auth.max-fail`） | `TenantAuthTemplate` + IT |
| 锁定时长 | **15 分钟**（`lock-minutes`，固定窗口自首次失败起算；**成功一次即清零**） | 同上 |
| 锁定维度 | **client_id**——A 租户被锁不影响 B 租户（`NfyAuthFlowTest:257-264`） | 同上 |
| 锁定表现 | 信封 code=429；**锁定期间正确凭据也被拒**（`NfyAuthFlowTest:234-254`）；无 Retry-After 头 | 同上 |

auth 端点不在限流 path-patterns 内（白名单豁免），防爆破锁是其唯一闸——客户端重换 token 不要
密集重试坏凭据。失败尝试的审计落库属已登记缺口（api-spec §2.3）。

## 6. 常见错误码速查（认证相关）

| code（信封） | HTTP | 触发条件 | 处置 |
|---|---|---|---|
| 10200 | 200 | 请求未带 `Authorization: Bearer`（T 端点）；签名面未知 AccessKey 同码 | 先换 token 再带头发起；签名场景核对 X-Access-Key 是否租户 OpenID |
| 10201 | 200 | token 过期/会话被撤销（重置密钥、SUSPEND、被新 token 顶掉、静置超时） | 重新换 token；持续被顶见 §4 互踢说明 |
| 10202 | 200 | JWT 验签失败（密钥变更后旧 token、被篡改） | 重新换 token |
| 10300 | 200 | token 型别与端点不符（如 SERVICE 型打 TENANT 端点） | 用 AUTH-001 换的 TENANT token |
| 10101 | 200 | T+U 端点缺 `X-User-Id` | 补头（§3 端点族清单） |
| 403 | **403** | 域闸拒绝：租户 token 打平台域 / 平台 token 打租户域 / claim 缺失 | 核对 §1 身份-域对照表，换对 token |
| 401 | 200 | 仅 auth 端点：凭据错或租户非 ACTIVE（防探测同码） | 核对 client_id/secret；确认租户 ACTIVE |
| 429 | 200 | auth 端点防爆破锁定（5 次锁 15min） | 等 15min 或换正确凭据由成功清零 |

## 7. curl 示例（独立部署形态，出厂端口 9200）

```bash
# ① 换平台身份 token（平台域管理操作：建租户/签发注册码/平台公告/跨租户统计）
curl -s -X POST http://localhost:9200/nfy/api/v1/auth/token \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "grant_type=client_credentials&client_id=PLATFORM&client_secret=$PLATFORM_CLIENT_SECRET"
# → data.access_token 即平台 token（合成 tenant_id=0）；平台建租户返回 open_id + tenant_secret（明文仅此一次）

# ② 换租户身份 token（client_id 用 PTE-001 返回的 open_id，12 字符；数字 id 亦可）
curl -s -X POST http://localhost:9200/nfy/api/v1/auth/token \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "grant_type=client_credentials&client_id=$OPEN_ID&client_secret=$TENANT_SECRET"

# ③ 带 X-User-Id 查询（T+U 端点示例：未读数；缺该头 → 10101）
curl -s http://localhost:9200/nfy/api/v1/runtime/messages/unread-count \
  -H "Authorization: Bearer $TENANT_TOKEN" \
  -H "X-User-Id: u_1001"
```

> Java 侧的 token 缓存/重换完整写法（含到期前 5min 重换与 429 防锁）见
> [quickstart-remote.md §6](../02-快速开始/quickstart-remote.md)；端到端 curl 全流程见
> [quickstart-5min.md](../02-快速开始/quickstart-5min.md)。

## 8. 相关路径

- [onboarding.md](onboarding.md)（开通租户、拿 open_id/tenant_secret）、[signature.md](signature.md)（HMAC 签名，可选）、
  [sending.md](sending.md)（发送全链）、[frontend-embed.md](frontend-embed.md)（SPA token 传递与过期重握手）
- [error-codes.md](../04-参考/error-codes.md)（全量错误码）、api-spec [§2.3 换 token](../../api/api-spec.md) / [§6 错误码](../../api/api-spec.md)
