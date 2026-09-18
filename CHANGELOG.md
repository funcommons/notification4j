# 更新日志（Changelog）

本项目所有显著变更记录于此。格式遵循 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，
版本号遵循 [语义化版本](https://semver.org/lang/zh-CN/)。

> 版本号口径说明：git tag / GitHub Release 的 v1.2.2 对齐接口文档（`docs/api/api-spec.md`）修订史 V1.2.2；
> Maven 坐标（`backend/pom.xml`，当前 1.0.0）与前端 `package.json` 版本计划在下个交付窗口统一 bump。

## [1.2.2] — 2026-09-18

对应 commit `a91f7a9`（fix: 验收回归修复轮）。八业务线验收回归（Playwright E2E 70 用例 + 既有 IT 深回归）发现并修复全部缺陷，缺陷台账清零。

### Fixed

**P0 × 3（独立部署出厂配置开箱即瘫）**

- **D-1 出厂签名面摘除 runtime 路径**：出厂 `framework4j.signature.path-patterns=[]`。SPA 与 S2S 共用 `/nfy/api/v1/runtime/**` 路径，嵌入面（浏览器端无租户 secret 可签）强制签名会全拒 10101；出厂摘除强制面，`enabled:true` 与密钥解析基础设施保留，S2S 需要防重放时自行加回 pattern（示例见 app `application.yml` 注释）。接口文档 V1.2.2 同步。
- **D-2 签名密钥解析接线（NfyTenantSecretProvider 排序）**：`NfyAutoConfiguration` 补 `@AutoConfigureBefore(SignatureAutoConfiguration)` 并移除 `nfyTenantSecretProvider` 的 `@ConditionalOnMissingBean`——修前 fwk4j InMemory 兜底抢先注册、正确签名的调用也全拒 10200；修后真实租户签名调用端到端打通，新增 `NfySignatureFaceTest` 防回归（enabled+patterns 下未签名 10101 / 真实租户签名 code=0 双钉）。
- **D-3 出厂 druid wall 关闭**：出厂 `filters: stat,slf4j`——wall 不识别 PG 方言（引擎 `FOR UPDATE ... SKIP LOCKED` / Flyway 部分索引 WHERE），默认拦死外发引擎扫描。

**P1 × 1**

- **ND-L5-01 EMAIL 渠道 API 使能闭环**：verify 10604 与 patch 10610 死锁致纯 API 外发闭环断裂。修复：patch 豁免 lastVerifyAt 前置 + 熔断态显式重启用清零（IM 分支不变）；引擎投递成功置 `last_verify_at`。

**P2 × 3**

- **D-4 redisson 排除**：framework4j-redis 传递引入的 redisson-spring-boot-starter 端点不随数据源配置覆盖、恒落默认 127.0.0.1:6379，产品全链零 redisson 使用——`spring.autoconfigure.exclude` 显式排除，杜绝误连。
- **F-1 前端时间解析**：Long→String 契约下前端全站 Invalid Date——统一 `fwkTime` 时间解析。
- **F-2 鉴权失败重握手**：失效 token 曾误导空态——鉴权失败触发 `notifyExpired` 重握手 + 「会话已失效」提示态。

### 验收数据（出厂等价实例）

- 后端：starter 单元测试 **411/411**（双 JaCoCo 门槛）· 全量 IT **129/129**（Testcontainers PG16+Redis7，较上轮 +4 新用例）
- 端到端：Playwright **70/70** · 前端三门 vitest **382** 全绿
- 日志三零：InMemorySecretProvider / Wall 异常 / 误连 6379
- 新增测试资产：`frontend/e2e-regression/`（8 spec + SMTP sink / pg-fixup helper）；`docs/test/report/`（统一测试报告 + 八线报告 + 91 张证据截图）

## [1.0.0] — 2026-09-17

对应 commit `214f56d`（feat: V1.0 全量交付）。首个正式版本。

### Added

- **接口契约 50/50 全实现**（`docs/api/api-spec.md` 附录 A 矩阵）：认证 / open 开放域 / runtime 消息·公告·渠道·订阅·字典 / admin / platform 三域 API + 外发引擎；V1.2 增补契约 #51（API-MSG-009 消息撤回）同窗口交付，每一项均有 Testcontainers 集成测试。
- **外发引擎**：SKIP LOCKED 扫描 · 退避重试（1-5-15min ≤3 次→DEAD）· 渠道熔断（≥5 次 + 属主站内信）· reaper 回收 · 每渠道限速（at-least-once 语义）。
- **V1.2 特性**：消息撤回（ADR-0010 竞态安全语义）· 订阅免打扰时段 quiet_hours（ADR-0011，推迟发送非丢弃）· 人工重投 UI（NfyShell 第五页「投递」）。
- **前端**：Vue3 消息中心五页 + 铃铛（postMessage 嵌入握手 / origin 白名单 / 内存 token / 未读 30s 轮询）。
- **部署三形态**：独立部署（`notification4j-app` fat jar，前端入 jar 免双部署）/ 嵌入（`notification-spring-boot-starter`，local 直调含数据面）/ 跨进程（`notification4j-client-starter`，remote HTTP 零 MyBatis/JDBC/Redis）。
- **文档与决策**：`docs/` 全套（接口/系统设计/数据库设计/PRD/嵌入集成指南/开发原则/租户设计/双模式方案），ADR-0001~0011。

### 质量数据（交付时点）

- 后端：单元测试 409 + 10（client-starter）· 冒烟 8 · 回归 IT 126（Testcontainers PG16+Redis7）
- 覆盖率：合并口径行覆盖 **96.50%**（七包 100% 逐包卡死 + BUNDLE ≥96% 双门槛）
- 前端：vitest 358 · vue-tsc / eslint 0 错 0 警
- 全周期评审循环修复 7 项真实缺陷（remote 模式 NPE、公告跨域计数双口径、app 壳装配等）

### 已知边界（交付时点）

- SMS 渠道（ADR-0004 引 Sms4J）待有网/私服环境落地（V1.2 唯一剩余项）
- legacy e2e 已归档 `frontend/legacy/`（后于 v1.2.2 轮按 `/nfy/tenant/app/**` 重写为 `frontend/e2e-regression/`）

---

[1.2.2]: https://github.com/funcommons/notification4j/releases/tag/v1.2.2
[1.0.0]: https://github.com/funcommons/notification4j/releases/tag/v1.0.0
[Keep a Changelog]: https://keepachangelog.com/zh-CN/1.1.0/
