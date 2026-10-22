# 参考 · 错误码字典（信封 / HTTP 语义 / 排查速查）

> 版本口径：notification4j **v1.4.0** × framework4j **v1.7.1**。本文每个错误码的语义、触发文案、
> HTTP 行为均从 starter 源码、fwk4j v1.7.1 源码与 IT 实测核验（「源码」列给出发射方类名）；
> 与 [api-spec §6](../../api/api-spec.md) 登记不一致处集中在 §5 声明。背景阅读：
> [认证与令牌](../03-开发指南/auth.md)、[接口签名](../03-开发指南/signature.md)。

## 1. 响应信封格式

所有 JSON 响应（含 HTTP 401/403/409/429 直写场景）都是同一信封（fwk4j-web `ApiResponse`）：

| 字段 | 类型 | 说明 |
|---|---|---|
| code | int | 业务码，`0` = 成功；非 0 见 §3 字典 |
| message | string | 提示文案（本文引用均为源码 / 实测原文） |
| data | object / null | 成功载荷；**失败时恒 null**（唯一例外 10700，本项目未使用） |
| error | array | 仅失败时出现（`@JsonInclude(NON_NULL)`）：`{field, code, message, rejectedValue}`；参数校验时元素 code 为子类型 REQUIRED_MISSING / FORMAT_INVALID / OUT_OF_RANGE（未映射注解落 `INVALID` 兜底；fwk4j `GlobalExceptionHandler` 子码表） |
| success / fail | boolean | 由 code 派生（`isSuccess()` / `isFail()` 序列化，`success = (code == 0)`） |
| trace_id | string | 链路追踪号，双通道：body + `X-Trace-Id` 响应头；不传则服务端生成 |
| timestamp | long | 服务端毫秒时间戳 |

```json
// 成功（Long 一律字符串化，见下；message 取 ApiCode.SUCCESS 文案）
{ "code": 0, "message": "操作成功", "data": { "message_id": "892310293123123" },
  "success": true, "fail": false, "trace_id": "c0a8010116983728001", "timestamp": 1787700000000 }
// 失败示例一：@Valid 参数校验（带 error[]，message 为 ApiCode.PARAM_ERROR 文案）
{ "code": 10100, "message": "请求参数错误", "data": null,
  "error": [{ "field": "user_ids", "code": "REQUIRED_MISSING", "message": "不能为空", "rejectedValue": "[]" }],
  "success": false, "fail": true, "trace_id": "c0a8010116983728001", "timestamp": 1787700000000 }
// 失败示例二：Service 业务校验（无 error[]）
{ "code": 10102, "message": "接收人数量超限(单次≤1000)", "data": null,
  "success": false, "fail": true, "trace_id": "c0a8010116983728001", "timestamp": 1787700000000 }
```

- **Long→String 契约**：ID / 雪花 / 秒数一律字符串（fwk4j-web `WebConfig` 的 Long→String 序列化器，
  防 JS 精度丢失）；连换 token 的 `expires_in` 也是字符串 `"28800"`（`NfyAuthFlowTest:194` 实测）。
  客户端不要用数值比较处理 ID。
- **trace_id 报障口径**：报障（GitHub Issue / 平台工单）必须附 `trace_id`（或 `X-Trace-Id`）+ 大致
  时间点；运维凭它在 tracelog 控制台（`/tracelog/index.html`，OPS token 鉴权）反查该请求全链日志。

**调用方判定顺序**（三步，勿只看一步）：

1. 读 body 信封 `code`：`0` 成功；非 0 进 §3 字典按 code+message 分支（HTTP 200 与否不影响这步）；
2. `code` 相同含义过宽时再读 `error[]`：`field` 定位字段、元素 `code`（REQUIRED_MISSING /
   FORMAT_INVALID / OUT_OF_RANGE）区分校验类别、`rejectedValue` 给被拒原值（截断至 100 字符）；
3. HTTP 非 200 的四种直写（401 签名 / 403 域闸 / 429 限流 / 409 幂等）body 仍是同一信封，
   解析逻辑无需特判——只影响网关与监控的分层统计。

## 2. HTTP 状态码与信封 code 的关系

**默认规则：业务错误 HTTP 200 + 信封 code**（fwk4j `GlobalExceptionHandler` 明示策略：业务异常
200、路由层异常保留原状态码）。调用方应**始终解析 body 信封 code**，HTTP 状态码只作辅助分层：

| 场景 | HTTP | 信封 code | 发射 / 直写方（源码） |
|---|---|---|---|
| 业务、参数校验、token 校验类异常 | 200 | 101xx~106xx | fwk4j `GlobalExceptionHandler`、`NfyExceptionHandler`（AuthException 也归 HTTP 200 信封） |
| **auth 端点**（`/nfy/api/v1/auth/token`）：凭据错 / 租户非 ACTIVE | **200** | **401** | fwk4j `TenantAuthTemplate:70`（`NfyAuthFlowTest:202` 实测 isOk） |
| auth 端点：防爆破锁定（5 次锁 15min） | **200** | **429** | `TenantAuthTemplate:64`（`NfyAuthFlowTest:252-253`） |
| auth 端点：grant_type 非法 / 参数缺 | **200** | **400** | `TenantAuthTemplate:55/58` |
| 域闸：token 身份域 ≠ 端点域（TENANT↔PLATFORM 互打、claim 缺失） | **403** | 403 | fwk4j `DomainGuardInterceptor.reject`（`NfyAuthFlowTest:285-288` 实测钉死） |
| tracelog 控制台等非 token 体系鉴权拒绝 | **403** | 403 | `NfyExceptionHandler.handleSecurity`（SecurityException） |
| 签名校验失败（开启签名的路径） | **401** | 10101/10102/10302/10200/10005 | fwk4j `SignatureInterceptor.writeJson`（拦截器直写，不走异常处理器） |
| 限流触发 | **429** | 10500 | fwk4j `RateLimitInterceptor:178`，附 `Retry-After` + `X-RateLimit-*` 三件套 |
| `Idempotency-Key` 重复提交 | **409** | 10501 | fwk4j `IdempotencyInterceptor:138/145/165/171` |
| 路由不存在 / 方法 / 媒体类型 / 上传超限 | 404 / 405 / 415 / 413 | 10400 / 10104 / 10105 / 10505 | fwk4j `GlobalExceptionHandler`（保留原状态码 + 信封） |
| 未捕获异常兜底 | **500** | 10001 | fwk4j `GlobalExceptionHandler.handleException`（5xx 可被 HTTP 维度监控捕获） |

> 易混点：auth 端点的 401/429 是**信封 code**（HTTP 仍 200），与签名面的 HTTP 401 直写不同——
> 只盯 HTTP 401/429 做告警会漏掉凭据爆破与锁定。**建议以信封 code 为准做分支**，HTTP 码仅用于
> 网关 / 监控分层。

## 3. 错误码字典（按段位）

> 「源码」列 = 发射方（starter 类均在 `fun.commons.notification4j` 包下；其余为 framework4j v1.7.1
> 类）。同一 code 多个发射方时逐个列出；message 为文案原文（占位符以 `{}` 示）。
> 排查口径：先 code 定段 → 再 message 定分支 → 最后 `error[]` 定字段。

### 3.0 系统段 10xxx（偶发，非调用方问题）

| code | 含义 | 典型触发（message 原文） | 常见处置 | 源码 |
|---|---|---|---|---|
| 10001 | 系统繁忙 | 未捕获异常兜底「系统繁忙，请稍后再试」（HTTP 500）；NPE 兜底（HTTP 200） | 携 trace_id 报障 | fwk4j `GlobalExceptionHandler` |
| 10005 | 中间件异常 | 签名 nonce 校验 Redis 故障「签名校验中间件异常」 | 稍后重试；持续出现找运维 | `SignatureService:100` |

10103「请求体格式错误或缺失」（JSON 解析失败）、10104/10105（405/415 路由层）、10106（框架对
IllegalArgument / 唯一键冲突的兜底「数据已存在，请勿重复提交」）同为框架层，正常对接不应碰到。

### 3.1 101xx 请求与参数（最多数的一族）

| code | 含义 | 典型触发（message 原文） | 常见处置 | 源码 |
|---|---|---|---|---|
| 10100 | 参数错误（值非法） | DTO `@Valid` 校验：空 `user_ids` 列表（`NfyBatchSendJobTest:228` 实测 10100 而非 10101）、quiet_hours 违例（「免打扰时间格式须为 HH:mm」等）、`mandatory 仅允许 0/1`、`status 仅允许 ENABLED/DISABLED`、`channel_type 枚举非法`、`default_level 枚举非法`、`secret 长度须在…` | 按 error[].field 定位改参 | fwk4j `GlobalExceptionHandler` + `SubscriptionService:268-276`、`PlatformTypeService:28`、`ChannelVerifier:47` 等 |
| 10101 | 必填缺失 | 「X-User-Id 不能为空」（T+U 端点缺头）；签名四头缺一「签名头缺失」；`@RequestHeader` 缺失；`type_code 不能为空` / `channel_ids 至少 1 个`；`message_ids 与 all 必须/只能二选一` | 补头 / 补字段；X-User-Id 端点族见 auth.md §3 | `NfyTenantContexts:27`、`SignatureService:73`、`SubscriptionService:107/114`、`MessageService:292/297` |
| 10102 | 格式或数值非法（含超限） | 「接收人数量超限(单次≤1000)」「批量发送人数超限(≤100000)」「单次已读数量超限(≤100)」「cursor 格式不正确」「生效窗口非法：expire_at 必须晚于 effective_at」；签名时间窗「签名时间戳过期或超前（容忍 ±300s）」 | 拆批 / 修格式 / 校时（NTP） | `MessageService:55/300/305/360`、`BatchJobService:58`、`AnnouncementAdminService:76/153`、`AnnouncementService:248`、`SignatureService:81-87` |

> 10602/10607 已废弃归位本段（api-spec §6.2）：数量超限、生效窗口非法均落 10102（零 DB 判定归
> 101xx），源码无 10602/10607 发射方。

### 3.2 102xx 认证

| code | 含义 | 典型触发（message 原文） | 常见处置 | 源码 |
|---|---|---|---|---|
| 10200 | 未认证 | 业务端点缺 `Authorization: Bearer`「未提供认证令牌」；签名面「未知的 AccessKey」（AccessKey 反解不到 ACTIVE 租户密钥，含 SUSPEND 同断） | 先换 token 再带头；签名面核对 X-Access-Key 是否租户 open_id | fwk4j `TokenInterceptor:66`、`SignatureService:111` |
| 10201 | token 已过期 / 会话不存在 | 「令牌已过期」「令牌已过期或不存在」（会话被删：PTE-004 SUSPEND/CLOSE 撤销、静置 30min 滑动过期）、「会话已达最大时长，请重新登录」（hardExpire 8h 绝对上限）、「令牌使用次数超限」 | 重新换 token；频繁过期见 auth.md §4 互踢 / 续期口径 | fwk4j `TokenUtils:169`、`AccessTokenValidationStrategy:71/88/112` |
| 10202 | token 无效 | JWT 验签失败「令牌签名验证失败」（服务端 JWT 密钥变更后的旧 token、被篡改） | 重新换 token | fwk4j `TokenUtils:148` |
| 10205 | 会话被顶（互踢） | 「账号已在别处登录」——同一（型别，租户）重换 token 覆盖会话 nonce，旧 token 下次请求即此码 | 给每个接入方独立租户，或错峰重换（auth.md §4） | fwk4j `AccessTokenValidationStrategy:81` |
| 10207 | 令牌格式 / 元数据错误 | 「令牌格式错误」（非三段式、缺 exp/iat、解析失败）、「Token 元数据解析失败」（Redis 会话元数据损坏，罕见）。**不是型别不匹配**（那是 10300，见 §5） | 检查是否完整透传 token；重换 | fwk4j `TokenUtils:132/145/156/163`、`AccessTokenValidationStrategy:157` |
| 10208 | 令牌已注销 | jti 命中 fwk4j 撤销名单「令牌已注销」。本产品 v1.4.0 无管理动作写该名单（SUSPEND/CLOSE 走删会话 → 10201），现网几乎不可见，属 fwk4j 能力位 | 重新换 token | fwk4j `AccessTokenValidationStrategy:56`（名单写入方 `AccessTokenGenerator.revokeToken` 本项目未调用） |

auth 端点（`POST /nfy/api/v1/auth/token`）专用信封码——分段外、HTTP 恒 200（实测
`NfyAuthFlowTest:202/246/252-253`）：

| 信封 code | message 原文 | 触发 | 源码 |
|---|---|---|---|
| 400 | `不支持的grant_type，仅支持client_credentials` / `client_id和client_secret不能为空` | 参数缺 / grant_type 非法 | `TenantAuthTemplate:55/58` |
| 401 | `client_id或client_secret无效` | 凭据错、租户不存在、**租户非 ACTIVE** 三态同码防探测 | `TenantAuthTemplate:70` |
| 429 | `认证失败次数过多，已锁定 15 分钟` | 同 client_id 连续失败 5 次锁 15min（成功即清零、按 client_id 隔离、无 Retry-After 头） | `TenantAuthTemplate:64` |

换 token 的排错路径详见 [auth.md](../03-开发指南/auth.md) §2.3（失败语义）与 §5（防爆破）。

### 3.3 103xx 授权与签名

| code | 含义 | 典型触发（message 原文） | 常见处置 | 源码 |
|---|---|---|---|---|
| 10300 | 令牌型别不匹配 / 权限不足 | 「令牌类型不匹配」——token 的 type claim 与端点 `@RequiresToken(value=...)` 不符（本产品型别为 TENANT/PLATFORM/OPS，如 OPS token 打 TENANT 端点）；fwk4j 角色校验失败同码（本项目未用 roles） | 换正确型别 token（auth.md §1 对照表） | fwk4j `AccessTokenValidationStrategy:62`（api-spec 写 10207，见 §5） |
| 10301 | 数据权限不足 | **无发射方**——跨租户 / 越权访问统一落 10400 防探测（api-spec §6.3） | 按 10400 排查 | —（登记占位） |
| 10302 | 签名校验失败 | 「nonce 已被使用（重放攻击）」（重放）；「签名值不匹配」（HMAC 比对不过） | 检查签名串构造 / 密钥 / nonce 生成器；无 SIGNATURE_* 子类型（§5） | fwk4j `SignatureService:104/127` |
| 403（信封） | 域闸拒绝 | 「平台域需要平台身份…」「租户域需要真实租户身份…」「token 缺少有效的 tenant_id claim…」——TENANT token 打平台域、PLATFORM token 打租户域等 | 换对身份域的 token；HTTP 与信封双 403 | fwk4j `DomainGuardInterceptor.reject` |

### 3.4 104xx 资源与状态

| code | 含义 | 典型触发（message 原文） | 常见处置 | 源码 |
|---|---|---|---|---|
| 10400 | 资源不存在或无权访问（防探测统一码） | 「资源不存在或无权访问」「消息不存在或无权访问」「模板/租户/类型/公告/投递记录/任务不存在」「渠道不存在或不可用」——查不到与查别人的同码同文案 | 先核对 id 归属本租户；勿重试 | `MessageService:121/165/199`、`PlatformMessageService:63`、`TemplateService:110`、`BatchJobService:83` 等 |
| 10401 | 唯一冲突（幂等 / 重复注册族） | 发送面「业务号重复，幂等拒绝」（biz_no）；管理面「类型编码已存在」「模板编码已存在」「邮箱已被使用」「该渠道已存在」「业务号重复，公告已存在」 | **重发成功请求**（换新 biz_no 才发新消息）；注册类改编码 | `MessageService:75`、`MessageTypeService:47`、`TemplateService:56`、`PlatformTenantService:81`、`ChannelCoreService:83`、`AnnouncementAdminService:50/66` |
| 10402 | 状态冲突 | 「非 DRAFT 态不可修改」「非草稿态不可发布」「已下线或未发布」「公告已失效」「非 DEAD 状态不可重投」「内置类型不可停用」「状态机非法迁移」「存在未完成投递任务…暂不可注销」 | 按状态机走（announcements.md §2/§5）；重投仅 DEAD | `AnnouncementAdminService:137/160/175/208`、`AnnouncementService:191`、`DeliveryAdminService:71`、`MessageTypeService:72`、`PlatformTenantService:178-192` |

### 3.5 105xx 流控与幂等

| code | 含义 | 典型触发 | 常见处置 | 源码 |
|---|---|---|---|---|
| 10500 | 限流（HTTP 429） | 出厂 **100 次/分钟**（Lua 滑窗），覆盖 `/nfy/api/v1/runtime/**`、assets、`/nfy/open/**`；auth 端点白名单豁免。**维度实际按客户端 IP（每路径独立 key）**——fwk4j v1.7.1 scope 仅认小写 `ip`/`user`/`app`/`global`，app yml `default-scope: APP` 未命中任何分支落 default→IP（key=`notification4j:rl:APP:{ip}:{path}`）；yml 注释「按 tenant_id」为陈旧注释（[configuration.md](../05-部署与运维/configuration.md) §9 勘误） | 按 `Retry-After` 秒数退避重试；持续触顶申请调额（注意：同出口 IP/NAT 后的多个用户共享一个桶） | fwk4j `RateLimitInterceptor:178` + app `application.yml`（`default-limit:100` / `default-window:1m` / `default-scope:APP`）；另 auth 面 Redis 写失败同码「Token 存储失败，请稍后重试」（`AccessTokenGenerator:122`） |
| 10501 | 重复提交（HTTP 409） | `Idempotency-Key` 同 key 异 body（「请勿重复提交」）；同 key 前一请求仍在处理（「…前一请求仍在处理中，请稍后重试」）；同 key 同 body 则**回放首次成功响应（HTTP 200）** | 确认业务意图：继续用同 key 收首次结果，或换 key 重发 | fwk4j `IdempotencyInterceptor:138/145/165/171`；覆盖 messages/channels/subscriptions 三条集合路径的写方法 POST/PUT/PATCH/DELETE（app yml，实际命中 POST 与 PUT subscriptions） |

### 3.6 106xx 业务自定义（本项目登记）

| code | 含义 | 典型触发（message 原文） | 常见处置 | 源码 |
|---|---|---|---|---|
| 10601 | 消息类型不存在或已停用 | 「消息类型不存在或已停用」——MSG-001 同步拒；**批量发送时 job 异步 FAILED**（受理仍是 code=0） | 先 TYP-001 建类型 / 恢复 ENABLED；批量场景轮询 job 状态 | `MessageTypeService:87`；批量异步见 `NfyBatchSendJobTest:232` |
| 10603 | 模板参数缺失 | 「模板参数缺失:{name}」——**模板渲染预览（TPL-003 preview）**时模板占位符未在 params 提供；发送面不校验模板参数（[sending.md](../03-开发指南/sending.md) §8） | preview 前补齐 params 再试 | `TemplateService:149`（render，仅 preview 调用） |
| 10604 | 渠道验证失败 | 「渠道验证失败:EMAIL 通道暂不支持主动验证(首次投递时校验)」「渠道验证失败:HTTP 4xx/5xx」「渠道验证失败:渠道无响应或连接失败」「渠道验证失败:{响应摘要}」 | EMAIL 走首次投递验证；IM 核对 webhook 机器人可用性（channels.md §2.4） | `ChannelVerifier:46/109/114/117/120` |
| 10605 | 同类型渠道数量超限 | 「同类型渠道数量超限(≤5)」——同 channel_type 第 6 个被拒 | 删旧或换类型 | `ChannelCoreService:63` |
| 10606 | 强制类型渠道不可关 | 「强制类型的指定渠道不可关闭」——mandatory=1 类型须保留 ≥1 个该类型 ENABLED 渠道（用户无该类型渠道时豁免） | 保留一个，或平台侧先解除 mandatory（subscription.md §4） | `SubscriptionService:182` |
| 10608 | 注册码无效或已失效 | 不存在 / 停用 / 已过期 / 次数用尽四态**同码防探测**（原子扣减 SQL：`status='ACTIVE' AND expire_at>now() AND used_count<max_uses`） | 向平台重新申领注册码（onboarding.md §3.2） | `RegistrationKeyService:81/87/100` + `NfypRegistrationKeyMapper.consume` |
| 10609 | Webhook 目标非法（SSRF 防线） | 「Webhook 地址非法或非官方域名」——非 https / 非 443 / 域名不在官方白名单（钉钉/企微/飞书）/ DNS 解析失败 / 解析出内网 IP | 只用官方机器人地址；自托管扩展白名单见 channels.md §2.2 | `WebhookTargetValidator:53/59/74/78` |
| 10610 | 渠道状态不符（熔断） | 「渠道连续失败已自动停用，请重新验证」——IM 渠道置 ENABLED 前须已验证且未熔断（fail_count≥5）；EMAIL 有豁免口径 | 先 CHN-003 重验证再启用（channels.md §5） | `ChannelCoreService:141` |
| 10611 | 同时生效公告超限 | 「同时生效公告不可超过20条，请先下线旧公告」 | 先下线旧公告再发布（announcements.md §5） | `AnnouncementAdminService:183` |

登记但**当前无发射方**：10301、10502（降级）、10602/10607（已归位 10102）、10700（部分成功）、
10900/10901——为 api-spec 登记占位或预留，排查时不应出现，出现即异常，附 trace_id 报障。

## 4. 症状 → 排查速查

| 症状 | 先看 | 结论 / 动作 | 详见 |
|---|---|---|---|
| 换 token 一直 401（信封） | client_id 是否租户 open_id（非 name）、secret 是否最新 | 三态同码防探测；SUSPEND 也同码，找平台确认租户态 | [auth.md](../03-开发指南/auth.md) §2.3/§4 |
| 换 token 第 6 次 429（信封） | 是否坏凭据密集重试 | 防爆破锁 5 次锁 15min，正确凭据也被拒，等解锁 | auth.md §5 |
| 业务请求 HTTP 200 但 code=10200 | 有没有带 `Authorization: Bearer` | token 没带或已失效；签名面则查 X-AccessKey | auth.md §6 |
| code=10201 频繁出现 | 是否多实例共用同一租户凭据 | 会话互踢（10205）或 8h 硬到期；独立租户 / 错峰重换 | auth.md §4 |
| code=10205 | 谁还在用同一凭据换 token | 新 token 顶掉旧会话；每接入方一个租户 | auth.md §4 |
| code=10207 还是 10300？ | message | 10207=令牌破损（格式/元数据）；10300=型别用错（如 OPS token 打 TENANT 端点） | auth.md §1 |
| HTTP 403（信封 code=403） | token 身份域 vs 端点域 | TENANT↔PLATFORM 互打；换对 token | auth.md §1.1 |
| HTTP 401（信封 10101/10102/10302） | 签名四头与 ±5min 时间窗 | 签名面专用：缺头 / 时钟漂移 / 重放 / 密钥不匹配 | [signature.md](../03-开发指南/signature.md) §3.1 |
| code=10401 该重发还是换号？ | 触发源 | biz_no 重复=上次已受理，**原样即成功**（可查 MSG-003 结果）；要发新内容必须换 biz_no | [sending.md](../03-开发指南/sending.md) §9 |
| HTTP 409 + 10501 | Idempotency-Key 是否复用 | 同 key 异 body 才拒；同 key 同 body 会回放首次结果 | sending.md §3 |
| code=10604（EMAIL） | 是否调了 verify | EMAIL 无主动验证，首次投递时校验；配好 SMTP 即可 | [channels.md](../03-开发指南/channels.md) §3 |
| code=10609 注册渠道被拒 | webhook 域名是否官方 | SSRF 白名单 + DNS 内网校验，自建网关域名会被拒 | channels.md §2.2 |
| 批量 job 卡 FAILED | job 详情 reason | 未知类型异步失败（10601 语义）；类型先建好再发 | sending.md §6 |
| 前端 iframe 握手失败 | 宿主 origin 是否在白名单 | 双面 ∪ 白名单 fail-closed；加 oem.hosts 或重建 dist | [frontend-embed.md](../03-开发指南/frontend-embed.md) §4 |
| code=10606 保存订阅被拒 | 该类型 mandatory | 平台侧（PTE-005）解除强制，或保留一个该类型渠道 | [subscription.md](../03-开发指南/subscription.md) §4 |

## 5. 与 api-spec §6 的差异声明（以实现为准）

以下差异均已源码 / IT 实证，api-spec 为登记口径（行号为当前文档版本）：

1. **型别不匹配：10300 而非 10207**。api-spec L73/L622/L646 称「10207 令牌类型不匹配」为铁律与
   登记扩展；实现发射方是 fwk4j `AccessTokenValidationStrategy:62` 的 **10300**「令牌类型不匹配」。
   10207 的实际语义是「令牌格式 / 元数据错误」（`TokenUtils:132-163`）。排查认 code+message。
2. **签名无 SIGNATURE_* 子类型**。api-spec L106（§2.4）/L623 称四态错误码「10302 + error 子类型
   SIGNATURE_MISSING/EXPIRED/REPLAYED/INVALID」；v1.7.1 实现无子类型字段，四态落
   **10101 / 10102 / 10302 / 10200** 四个不同 code（`SignatureService:73-127`）。与
   [signature.md](../03-开发指南/signature.md) §3.1 差异声明同口径。
3. **10602 已废弃归位 10102**（一致项，确认）。api-spec L635 删除线登记与实现一致：数量超限均
   发 10102（`MessageService:55`、`BatchJobService:58`），源码无 10602 发射方。
4. **空 user_ids 列表：HTTP 面是 10100 非 10101**。api-spec L334（§5.2 错误码表）写「10101 |
   user_ids 为空」；实测 HTTP 请求被 DTO `@NotEmpty` 先行拦截为 **10100**（fwk4j PARAM_ERROR，
   `NfyBatchSendJobTest:228`）。10101「接收人列表不能为空」（`MessageService:51`）只在进程内
   直调（LocalNotifyClient 绕过 @Valid）路径兜底可见。
5. **auth 端点信封码补充确认**。api-spec L622 括注「auth 端点凭据错误实为信封 401、锁定 429」
   与实现一致（`TenantAuthTemplate`）；本文 §2 进一步钉死其 **HTTP 仍为 200**——这与域闸 403、
   签名 401、限流 429、幂等 409 的「HTTP 直写」是两回事。
6. **10205 / 10208 未入 api-spec §6.1 分段表**（L621-622 仅列 10200/10201/10202/10207）。实测
   10205「账号已在别处登录」真实存在且高频（同租户重复换 token 即触发，
   `AccessTokenValidationStrategy:81`，auth.md §4/§6 已收录）；10208「令牌已注销」为本产品
   暂无发射方的能力位（§3.2）。对接方分支逻辑应把 10201/10202/10205/10208 统一按「重新换
   token」处理。
