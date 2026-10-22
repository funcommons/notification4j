# 安全自查清单（出厂默认盘点 / 上线前检查 / 已知边界）

> notification4j 使用手册 · 06-最佳实践第 2 篇 · 版本口径 **v1.4.0**（底座 framework4j v1.7.1）。
> 「出厂值」列核对自 app `application.yml` 与 fwk4j v1.7.1 properties / 源码；「是否已安全」按
> **出厂即安全 / 需部署侧动作** 区分。取证源：app yml、fwk4j（`AuditProperties` / `RateLimitKeyResolver` /
> `SignatureProperties` / `AccessTokenProperties` / `TenantEntity`）、starter 源码（`NfyaChannel` /
> `NfyaTenantSecretProvider` / `WebhookTargetValidator` / `AuthenticatedHttpTransport` / `RemoteNotifyClient`）、
> 手册 03 / 05 各篇、[api-spec §2/§6](../../api/api-spec.md)。配套实践展开见同目录
> [best-practices.md](best-practices.md)。

## 1. 出厂默认安全面盘点

| 项 | 出厂值 | 是否已安全 | 说明 |
|---|---|---|---|
| 机密注入 fail-fast | `JWT_SECRET`（≥32 字符）/ `AES_KEY`（≥32）/ `PLATFORM_CLIENT_SECRET` 无默认占位 | ✅ 机制已安全 | 缺失即启动失败（占位符不可解析）；**强度由部署侧保证**（§2 第 1 项） |
| `HASH_SALT` | 缺省 `salt` | ⚠️ 需自查 | 防 Redis 泄露反查会话 key 的盐，生产应显式覆盖 |
| token 有效期 | 8h（`expire-time: 28800`，上限 12h）+ Redis 会话双验 + 滑动续 30min | ✅ | M2M 无 refresh；每次校验回 `X-Token-Expire-At` 头（剩余秒数） |
| 防爆破 | 同 `client_id` 连错 5 次锁 15min（成功清零、按 client_id 隔离） | ✅ | 信封 `429` 且 HTTP 200、无 Retry-After 头；失败尝试审计为已登记缺口（§3-9） |
| 域闸与型别 | starter 22 个 Controller 中 20 个带 `@RequiresToken("TENANT")` + `@TenantDomain`/`@PlatformDomain` 组合（例外仅 ops/health 探针与 open 域注册，免鉴权另有兜底） | ✅ | TENANT↔PLATFORM 互打一律 HTTP 403 + 信封 403，双向 IT 钉死（auth.md §1.1） |
| 幂等键 | `Idempotency-Key` 48h + body-hash 校验，3 条精确路径（messages/channels/subscriptions 写面） | ✅ | 同 key 异 body → HTTP 409 + `10501`；同 key 同 body 回放首次响应 |
| 接口签名 | `framework4j.signature.path-patterns: []`（`enabled: true`） | ⚠️ 需自查 | **出厂不强制任何路径签名**（D-1：SPA 与 S2S 共用 runtime，强制 = SPA 全拒 10101）；纯 S2S 需防重放时自行加回 pattern（三层配齐见 [signature.md](../03-开发指南/signature.md) §2） |
| 嵌入 origin 白名单 | 构建时 `VITE_NFY_PARENT_ORIGINS` ∪ 运行时 `oem.hosts`，**fail-closed** | ✅ 机制已安全 | 核验失败/未命中静默忽略、数据面零调用（L8-07/L8-10 实测）；但**出厂 dist 面 1 只有 localhost 开发对**，生产宿主 origin 须走 `oem.hosts`（§2 第 8 项） |
| AES 字段加密 | `tenant_secret` / `tenant_secret_prev` / 渠道加签 `secret` 走 `LazyEncryptedFieldTypeHandler` AES-256-GCM 列 | ✅ | `AES_KEY` fail-fast；接口永不回显明文（SEC-001 仅回 masked） |
| 接口脱敏 | DLV 投递 target 快照 / 渠道列表 target / 租户 email / 签名密钥 masked（前 2+`****`+后 2）/ 注册码 `code_masked`（前 8+`****`） | ✅ | 规则见 channels.md §2.5 / onboarding.md §4.2；`10604` 摘要不回传目标地址 |
| 审计 hash chain | `audit.enabled: true` + fwk4j `hashChainEnabled: true`（SHA-256）+ `uk_nfyp_audit_log_hash` 唯一索引 | ⚠️ 部分 | 表与防篡改链就绪（append-only），但 **v1.4.0 starter 无任何 `@Auditable` 注解点，审计表实际无写入**（fwk4j AOP 切点为 `@annotation(Auditable)`，已检索核实）——见 §3-4 |
| IP 限流 | 100 次/分钟/客户端 IP/路径（Lua 滑窗），`10500` + `Retry-After` | ⚠️ 部分 | 维度是 IP 非 tenant（`default-scope: APP` 大写未命中 fwk4j 小写 `ip/user/app/global` 分支落 default），NAT 共桶 + XFF 信任问题见 §3-2 |
| Druid filters | `stat,slf4j`（显式去 `wall`） | ⚠️ 取舍项 | wall 不识别 PG 方言（`FOR UPDATE OF ... SKIP LOCKED` / 部分索引 WHERE）会拦死引擎与 Flyway；注入防线由参数化查询（MyBatis-Plus 预编译）+ webhook SSRF 白名单承担；要加回 wall 先验证这两条链路 |
| Webhook SSRF 防线 | 仅 https + 官方域名白名单（`oapi.dingtalk.com` / `qyapi.weixin.qq.com` / `open.feishu.cn` / `*.feishu.cn`）+ 仅 443 + DNS 解析禁内网段 | ✅ | 违规 `10609`；注意出口 DNS 走 fake-IP 代理会误伤白名单域名（channels.md §2.2） |
| 开放域注册 | `/nfy/open/**` 免鉴权 + IP 限流兜底 + 注册码 DB 单语句 CAS 原子扣减 | ✅ | 无效/用尽/过期/未知码四态同码 `10608` 防探测 |
| Flyway | `baseline-on-migrate: false` / `validate-on-migrate: true` | ✅ | 非空库拒绝静默 baseline；迁移脚本 checksum 防篡改，历史脚本禁改（升级注意见 release-notes） |
| swagger / api-docs | `/swagger-ui/**`、`/v3/api-docs/**` 在 `access-token.exclude-path-patterns` 放行（免鉴权） | ⚠️ 需自查 | 生产建议网关屏蔽（§2 第 14 项） |
| 健康端点 | `GET /nfy/api/v1/ops/health` 免鉴权 | ✅ 机制已安全 | 仅回 db/redis UP/DOWN，不泄内部细节；DOWN 时 HTTP 仍 200，探活脚本须解析 body；建议限内网/LB |
| CSP / frame-ancestors | 服务端**无实现**（代码检索无 `Content-Security-Policy` 下发） | ⚠️ 需宿主补 | 嵌入可见性不受服务端限制，token 安全靠白名单握手而非「隐藏 iframe」（§3-1） |

## 2. 上线前自查清单

部署与配置项的完整核对表见 [deployment.md](../05-部署与运维/deployment.md) §3 /
[configuration.md](../05-部署与运维/configuration.md) §2，此处聚焦安全面：

**机密与凭据**

- [ ] **必填 env 强度**：`JWT_SECRET` / `AES_KEY` 均 ≥32 字符强随机（`openssl rand -base64 32` 量级）；
      `PLATFORM_CLIENT_SECRET` 非弱值；三者不进 git、不进镜像层
- [ ] **`HASH_SALT` 已显式覆盖**（勿用缺省 `salt`）
- [ ] **DB 凭据已换**：出厂 `admin / test@2026` 为开发默认；两处 url（`spring.flyway.*` 与
      `framework4j.datasource...`）都覆盖且保留 `?stringtype=unspecified`
- [ ] **DB 账号最小权限**：flyway 与业务是两条独立连接配置——flyway 账号给 DDL，业务账号仅需 DML
      （`ddl-mode: PROVIDED`，框架自身不执行 DDL）
- [ ] **平台域密钥单独保管**：`PLATFORM_CLIENT_SECRET` 换到的平台 token 可跨租户管理（建租户/重置密钥/
      SUSPEND/平台公告），与租户密钥分属不同责任人、不同 secret 存储
- [ ] **租户 secret 保管**：明文仅创建/重置响应出现一次，离线封存；client-starter 的
      `remote-tenant-secret` 走 env/secret 注入；上线冒烟验证两键齐——**缺任一键签名四头与 Bearer 一并不注入、
      仅打 WARN**（`AuthenticatedHttpTransport.enrich`），首个请求 `10200`
- [ ] **签名密钥长度合规**：SEC-001 注册/轮换的密钥 16~71 位，否则信封 `10100`

**接入面与白名单**

- [ ] **签名是否需要**：纯 S2S → 加回 pattern + `privileges.signature=true` + SEC-001 注册 + 调用方两键；
      嵌入/混合形态 → 不开（决策表见 best-practices §2.1）
- [ ] **密钥轮换预案**：签名侧无宽限，顺序固定「下发 → 切换 → 验证 → 登记」（best-practices §2.2）
- [ ] **白名单最小化（双面）**：运行时 `oem.hosts` 只配实际宿主 origin（origin 精确串：scheme+host+port、
      无路径；同源反代形态记得配**宿主域**）；自建 dist 时 `VITE_NFY_PARENT_ORIGINS` 同样最小化——
      新增 host 属安全变更，走平台审批
- [ ] **注册码生命周期**：`max_uses`（1~100）/ `expire_hours`（1~8760）按需最小化发放；完整码仅签发时一次性
      返回，平台列表只见脱敏 `code_masked`
- [ ] **token 治理**：到期前 5min 重换；多实例勿共用同一租户凭据（单会话模型互踢 `10205`）——每接入方独立租户；
      换 token 失败退避重试（连错 5 次锁 15min）

**渠道与租户治理**

- [ ] **EMAIL/IM 渠道密钥保管**：渠道加签 secret 落库即 AES-GCM 加密列（提交后不回显）；SMTP 凭据
      `nfy.runtime.engine.mail-username/mail-password` 走环境变量覆盖，勿写进 yml 入 git；IM webhook 只收
      官方域名（`10609` 白名单外一律拒）
- [ ] **公共渠道治理**：ACH 面（scope=TENANT，admin 域）注册的 webhook/secret 由租户管理员保管；公共渠道熔断
      **只打日志、无属主站内信告警**——需人工巡检渠道列表 `status=DISABLED`
- [ ] **SUSPEND 流程演练**：PTE-004 三面同断（换 token 401 / 在途 `10201` / 签名面 `10200`）+ RESUME 恢复
      原密钥，至少演练一次并留应急 runbook
- [ ] **测试/生产隔离**：优先分实例；同实例至少分租户，且平台域操作（PAN/PTE——平台公告对全部租户可见）仅
      生产专人执行（best-practices §4.1）

**日志、暴露面与响应**

- [ ] **日志敏感信息**：stdout 日志（出厂无文件 appender，由容器体系收集）不打印明文密钥；对外响应脱敏面核对
      （§1 表）；注意 `error[].rejectedValue` 截断 100 字符但**会回显被拒原值**——勿把密钥放业务字段提交
- [ ] **iframe sandbox / CSP（宿主侧补位）**：跨源拓扑 iframe 可加 `sandbox="allow-scripts allow-same-origin"`
      （六页面无外链，无需 `allow-popups`）；宿主页 CSP `frame-src` 放行 nfy 源；服务端不下发
      `frame-ancestors`（§3-1），勿依赖「隐藏 iframe」当防线
- [ ] **暴露面收敛**：生产网关屏蔽 `/swagger-ui/**`、`/v3/api-docs/**`（免鉴权直出契约）；tracelog 控制台
      静态页 `/tracelog/**` 放行但其 API 强制 OPS token（`require-auth: true` + `NfyTraceLogAuthValidator`）；
      health 端点限内网/LB
- [ ] **限流容量核对**：调用方出口 IP 集中（NAT/网关侧共 IP）时评估 `default-limit` 提额或 fwk4j scope 调整
      （best-practices §3.1）；反代覆盖/清洗客户端自带 `X-Forwarded-For`（§3-2）
- [ ] **报障流程**：业务报障附 `trace_id`（body + `X-Trace-Id` 双通道）开 GitHub Issue；**安全漏洞走
      [SECURITY.md](../../../SECURITY.md) 渠道，勿开公开 issue**

## 3. 已知安全边界（如实声明）与加固建议

1. **CSP / frame-ancestors 无实现**：服务端不下发任何 `Content-Security-Policy` 响应头（api-spec PTE-002 的
   frame-ancestors 表述为规划口径）。加固：宿主侧 CSP `frame-src` + iframe `sandbox` + 白名单握手三件套
   兜底（frontend-embed.md §8）。
2. **IP 限流维度与 XFF 信任**：出厂实际按客户端 IP 分桶（fwk4j `RateLimitKeyResolver` 仅认小写 scope，`APP`
   落 default 分支）；且 `resolveIp` **信任 `X-Forwarded-For` 首值**——反代必须覆盖/清洗客户端自带的 XFF，
   否则伪造头即可换桶绕过限流。纯 S2S 部署可改小写 `app`（按 `X-Access-Key` 头；无该头的请求共落 `unknown`
   桶，混合形态勿用）。
3. **HTTP / 信封双轨语义**：业务与 token 校验失败一律 **HTTP 200 + 信封 code**（102xx / 10300 等）；域闸
   `403`、签名面 `401`、限流 `429`、幂等 `409` 为 HTTP 直写；auth 端点的凭据错/锁定是**信封 401/429 且
   HTTP 200**。监控与 WAF 规则必须以信封 code 为准，只盯 HTTP 状态码会漏掉凭据爆破（error-codes.md §2）。
4. **审计零写入**：`nfyp_audit_log` 表与 hash chain 配置就绪，但 starter 无 `@Auditable` 注解点，v1.4.0
   审计表不产生任何行（含平台域写操作）。需要审计留痕时在管理面方法上补 fwk4j `@Auditable` 注解
   （AOP 切点 `@annotation(Auditable)` 自动落库）。
5. **`X-User-Id` 透传不鉴权**：框架不校验其长度（契约 ≤64）与真实性，鉴权决策只信 token claim；存在性由
   业务层强制（T+U 端点缺失 `10101`）。嵌入面的用户身份真实性完全取决于宿主后端代换 token 时的映射——
   按 claim 绑定校验属推荐级加固（frontend-embed.md §3）。
6. **`privileges.signature` 仅存储**：租户开关登记在 `nfya_tenant.privileges`，无运行时读取点；拦截是否发生
   完全由部署侧 `framework4j.signature.path-patterns` 决定——开了登记不开 pattern 等于没开（三层配齐）。
7. **`retentionDays` 无清理任务**：仅存储的预留配置，历史数据保留需 DBA 自管（best-practices §3.2）。
8. **Metrics 零埋点**：backend 零 micrometer 依赖，无 `/actuator/metrics` / Prometheus 面；当前可观测面 =
   health 端点 + 结构化日志（trace_id 三贯通）+ DB 投递表巡检（operations.md §6）。
9. **防爆破失败尝试无审计**：换 token 失败仅计数锁定，不落审计（api-spec §2.3 已登记缺口，可随 §3-4 一并补）。

## 下一步

- 实践展开（幂等 / 轮换顺序 / 容量 / 反模式）：[best-practices.md](best-practices.md)
- 错误码与 HTTP 语义：[error-codes.md](../04-参考/error-codes.md) · 签名协议：[signature.md](../03-开发指南/signature.md)
- 漏洞报告渠道：[SECURITY.md](../../../SECURITY.md)
