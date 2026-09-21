# 更新日志（Changelog）

本项目所有显著变更记录于此。格式遵循 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，
版本号遵循 [语义化版本](https://semver.org/lang/zh-CN/)。

## [Unreleased]

> 版本号口径说明：git tag / GitHub Release 的 vX.Y.Z 对齐接口文档（`docs/api/api-spec.md`）修订史；
> Maven 坐标（`backend/pom.xml`，当前 1.4.0）与 tag 自 v1.3.0 起一致。

## [1.4.0] — 2026-09-21

平台站内信查询面：API-PPM-001/002 落地平台域跨租户只读查询。Maven 坐标（1.4.0）与 tag v1.4.0 一致。

### Added

- **API-PPM-001/002 平台站内信查询面**：`GET /nfy/platform/api/v1/messages`（跨租户全量列表；`user_id`/`type_code`/`created_from`/`created_to`/`keyword` 可选筛选 + offset/limit 分页，沿 PAN 口径 limit≤100）+ `GET /{message_id}` 详情（全字段 + 已读回执统计 `read_count`）。数据走既有 MessageService 同源查询路径做平台维度包装（只读面，不触发送链），鉴权/信封/注册双通道与 PAN 完全同构（`@PlatformDomain` + `@RequiresToken(TENANT)` 合成口径；autoconfig `nfy.runtime.enable-api` 开关 + 组件扫描）。诉求方：MMagiX2 平台管理员站内信历史查询（nfy 白名单构建期烧入需 relay 面查询数据源）。

### Changed

- pom `1.3.0 → 1.4.0`：Maven 坐标与 tag v1.4.0 一致。
- e2e 启动脚本 `frontend/e2e-regression/bin/start-app.sh`：fat jar 路径由硬编码 `notification4j-app-1.0.0.jar` 改 glob 匹配——后续版本 bump 无需再同步改脚本。

### 验收数据（出厂等价实例）

- 后端：starter 单元测试 **421/421**（双 JaCoCo 门槛）· 全量 IT **136/136**（30 套件，+1 平台域 PPM 用例）
- 端到端：Playwright **72/72** · 前端 vitest **386** 全绿
- fat jar 冒烟：`notification4j-app-1.4.0.jar` 启动单池实证（原生 Druid wrapper 0 / Hikari 0）；PPM 路由 10200 闸 + 已认证 list/detail 全链 code=0
- 测试报告：`docs/test/report/2026-09-21-01/`（本轮发布验证报告 + 截图证据）

## [1.3.0] — 2026-09-19

接入摩擦修复轮：MMagiX2 薄壳接入实测暴露的 3 个 issue 全部修复 + framework4j 升级。Maven 坐标自此与 tag 一致（1.3.0）。

### Added

- **API-OEM-001 `GET /nfy/api/v1/runtime/oem/hosts`（issue #1）**：下发本租户 oem.hosts（T 鉴权无 U，同 DICT-001 口径）。嵌入消息中心 postMessage origin 白名单自此**双面生效**：构建时 `VITE_NFY_PARENT_ORIGINS` ∪ 运行时 `oem.hosts`——构建时名单外的父页 origin，iframe 持候选 token 调本端点核验，命中才接受 NFY_TOKEN（`useEmbedHandshake` 新增 `fetchExtraOrigins`，同 token 在途去重、失败 fail-closed 忽略）。修前 oem.hosts 仅有平台面写入与存储、无任何消费方（白名单实际只有前端构建时半边）。运营时可配，无需重打前端。
- **数据面接管过滤器 `NfyDataPlaneTakeoverFilter`（issue #3）**：starter 以 `AutoConfigurationImportFilter`（`META-INF/spring.factories` 注册，先例 lotask4j）接管原生装配——`framework4j.datasource.enabled=true` 时 veto `DataSourceAutoConfiguration` / `DruidDataSourceAutoConfigure` / `MybatisPlusAutoConfiguration` / `DataSourceTransactionManagerAutoConfiguration`（framework4j 多数据源成唯一池源：单池 + 单 SqlSessionFactory）；`framework4j.redis.enabled=true` 时 veto `RedissonAutoConfigurationV2/V4`；总闸 `nfy.enabled=false` 全放行。接入方只配 `framework4j.datasource.datasources.default.*` 即可启动，**无需** `spring.datasource.*`、**无需** `spring.autoconfigure.exclude`；自管数据面的宿主不受影响。
- **ID 生成器兜底 `NfyMybatisPlusSupportAutoConfiguration`（issue #2）**：`before = MybatisPlusAutoConfiguration` 注册 `@ConditionalOnMissingBean IdentifierGenerator → DefaultIdentifierGenerator`，接入方零配置即得雪花 id（自有 bean 自然让位）。三重兜底口径：MP 3.5.7 SSF builder 自带 NetUtils 兜底、framework4j-id `mpIdGenerator` 缺省启用、本 bean 显式装配缝（app 壳同款 config 收编删除）。

### Changed

- **framework4j v1.5.1 → v1.7.1**：吸收 web advice 拆分（DataAccessExceptionAdvice + @ConditionalOnClass(spring-jdbc)）、accesstoken 启动期 fail-fast 校验（secretKey≥32/hashSalt/policies 清单式报错）、token 自包含（`embed-claims` 缺省 true，payload 携带 tenant_id）、advice 排序修复。零适配成本（全量回归直接通过）；nfy 侧 Spring Boot 3.2.7 / MyBatis-Plus 3.5.7 钉版不变。
- **app 壳配置瘦身**：删除 dev profile `spring.datasource.*` 冗余段与 `spring.autoconfigure.exclude` 手工项（filter 接管）；单池实证——启动日志原生 Druid wrapper 0 / Hikari 0 / framework4j `defaultDataSource @Primary` 唯一。
- pom `1.0.0 → 1.3.0`：Maven 坐标与 tag 对齐（清掉注册在案的版本错位）。

### 验收数据（出厂等价实例）

- 后端：starter 单元测试 **411/411**（双 JaCoCo 门槛，合并口径 96.66%）· 全量 IT **135/135**（30 套件；新增 `NfyRuntimeOemTest`×4 + `NfyDataPlaneTakeoverTest`×2）
- 端到端：Playwright **72/72**（L8 新增 L8-09 运行时白名单命中 / L8-10 未命中拒绝）· 前端 vitest **386** 全绿（+4 握手运行时白名单用例）
- 测试报告：`docs/test/report/2026-09-19-01/`（本轮修复验证报告 + 截图证据）

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

[1.4.0]: https://github.com/funcommons/notification4j/releases/tag/v1.4.0
[1.3.0]: https://github.com/funcommons/notification4j/releases/tag/v1.3.0
[1.2.2]: https://github.com/funcommons/notification4j/releases/tag/v1.2.2
[1.0.0]: https://github.com/funcommons/notification4j/releases/tag/v1.0.0
[Keep a Changelog]: https://keepachangelog.com/zh-CN/1.1.0/
