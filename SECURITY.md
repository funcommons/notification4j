# 安全策略（Security Policy）

## 支持的版本

| 版本 | 支持状态 |
|---|---|
| v1.2.2（git tag / GitHub Release 口径；Maven 坐标当前仍为 1.0.0） | ✅ 接受安全报告与修复 |
| v1.0.0 及更早 | ❌ 停止支持，请升级至 v1.2.2 |

## 出厂安全语义（部署前必读）

以下为本仓库出厂配置的实际安全行为（事实源：`backend/notification4j-app/src/main/resources/application.yml` 与 starter 代码）。**部署到生产前请逐条确认是否符合你的威胁模型。**

### 1. 签名面（S2S 防重放）出厂为空 —— 需要时自行加回

- 出厂 `framework4j.signature.path-patterns=[]`：**不对任何路径强制 HMAC 签名**。这是 v1.2.2 的 D-1 修复决策：独立部署形态下消息中心 SPA 与 S2S 调用方共用 `/nfy/api/v1/runtime/**` 路径，浏览器端无租户 secret 可签，出厂强制签名会使嵌入面全拒（10101）。
- `signature.enabled=true` 与按租户密钥解析基础设施（`NfyTenantSecretProvider`）**保留**：S2S 对接需要防重放时，自行加回 pattern（app yml 注释含示例），密钥注册/轮换走 API-SEC-001，旧密钥轮换宽限 24h。
- 签名协议：HMAC-SHA256 四元组 Header `X-Access-Key / X-Timestamp / X-Nonce / X-Signature`，时间戳容差 ±5min，nonce 一次性 10min 防重放（fwk4j-signature）；按租户开关 `privileges.signature`（ADR-0008），密钥已注册即强制校验（安全默认）。
- 防回归测试：`NfySignatureFaceTest`（enabled+patterns 下未签名拒绝 / 真实租户签名放行双钉）。

### 2. 机密只经环境变量注入，不入库不入 git

独立部署（app）启动强依赖以下环境变量，**无默认值，缺失即 fail-fast**：

| 环境变量 | 用途 |
|---|---|
| `JWT_SECRET` | Access token 签名密钥（fwk4j-accesstoken） |
| `AES_KEY` | 敏感字段加密密钥（AES-256-GCM 字段级加密，租户密钥/EMAIL 凭据等加密列） |
| `PLATFORM_CLIENT_SECRET` | 平台域 client_credentials 换 token 凭据（`PLATFORM_CLIENT_ID` 出厂默认 `PLATFORM`） |
| `HASH_SALT` | 哈希盐（有出厂默认 `salt`，生产建议显式覆盖） |

租户 secret、渠道凭据等业务机密落库一律经加密列（实体 typeHandler），不得明文列存储。

### 3. 用户面双因子：TENANT token + X-User-Id

runtime 用户面端点要求「租户级 TENANT token + `X-User-Id` Header」双因子；`X-User-Id` 缺失由 `NfyTenantContexts` 守卫统一拒绝（10101），不会落 userid=null 的脏数据。平台域端点使用 PLATFORM 合成 token（tenant_id=0）+ `@PlatformDomain` 域守卫（HTTP 403）。换 token 端点 `/nfy/api/v1/auth/token` 防爆破：**连续失败 5 次锁定 15 分钟**（`max-fail: 5 / lock-minutes: 15`）；token 有效期 8h（上限 12h），自动续期。

### 4. 渠道 webhook 注册 SSRF 白名单

用户自注册 IM 群 webhook（钉钉/企微/飞书）经 `WebhookTargetValidator` 强制：必须 **https + 官方域名白名单**（`oapi.dingtalk.com` / `qyapi.weixin.qq.com` / `open.feishu.cn` / `*.feishu.cn`）；且 **DNS 解析逐 IP 校验禁内网段**（环回/私网/link-local 均拒），解析失败同 10609 拒绝。EMAIL 通道地址格式与长度上限（254）同面校验。

### 5. 其它出厂面（摘述）

- 鉴权豁免路径收敛为最小集（auth/token、`/nfy/open/**`、ops/health、swagger、tracelog 静态页）；open 开放域无 token，以 IP 限流兜底；
- 默认限流：APP 维度 100 次/1min（Lua 滑动窗口），覆盖 runtime/assets/open 面；换 token 端点在限流白名单（由防爆破锁兜底）；
- 写面幂等（messages/channels/subscriptions）：Idempotency-Key 48h + body hash 校验，防同 key 不同 body 重放；
- tracelog 控制台 API 为 OPS-only（`require-auth: true`，未配校验器启动即失败），日志提权作用域限本项目包名；
- 生命周期联动：租户 SUSPEND 即阻断认证并撤销存量会话；签名密钥面同断（SecretProvider 校验 ACTIVE）。

## 报告漏洞

**请勿以公开 issue 报告安全漏洞。**

- 首选：GitHub 私密安全报告（Security Advisories → "Report a vulnerability"）：
  <https://github.com/funcommons/notification4j/security/advisories/new>
- 邮箱通道：funcommons 组织安全邮箱 **待配置**（本仓库尚未登记安全联系邮箱，配置后此处更新）。

报告请附：影响版本、复现步骤/POC、影响评估。我们会在确认后同步修复、发版并在 Advisory 中致谢。
