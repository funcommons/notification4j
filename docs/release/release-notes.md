# 发布说明（Release Notes）

> 汇总本项目各正式版本的发布说明。事实源：git tag 注释（`v1.0.0` / `v1.2.2` / `v1.3.0` / `v1.4.0`）、
> GitHub Releases（https://github.com/funcommons/notification4j/releases ）、接口文档 `docs/api/api-spec.md` 修订史（V1.0.0 ~ V1.4.0）。
> 逐项变更明细见根 [CHANGELOG.md](../../CHANGELOG.md)。

---

## v1.4.0 — 平台站内信查询面（API-PPM-001/002）

- **发布日期**：2026-09-21（commit 见 git tag `v1.4.0`）
- **版本口径**：**Maven 坐标 1.4.0 与 tag 一致**（自 v1.3.0 起连续对齐）

### 新增

| 项 | 内容 |
|---|---|
| **API-PPM-001/002** | 平台站内信跨租户只读查询面（诉求方 MMagiX2 平台管理员站内信历史查询）：`GET /nfy/platform/api/v1/messages` 跨租户全量列表（`user_id`/`type_code`/`created_from`/`created_to`/`keyword` 可选筛选 + offset/limit 分页，缺省 0/20、上限 100）+ `GET /{message_id}` 详情（全字段 + 已读回执统计 `read_count`）。数据走既有 MessageService 同源查询路径做平台维度包装——只读面，不触发送链（发送仍归 MSG-001）；`user_id` 经收件人表参数化子查询（防注入）；不存在/非数字 id 统一 10400 防探测（口径同 MSG-005）；鉴权/信封/注册双通道与 PAN 完全同构（`@PlatformDomain` + `@RequiresToken(TENANT)` 合成口径） |

### 变更

- pom `1.3.0 → 1.4.0`
- e2e 启动脚本 `bin/start-app.sh`：fat jar 路径硬编码改 glob 匹配（版本无关，bump 不再同步改脚本）

### 验收

- 四层全绿：starter 单测 **421/421**（双 JaCoCo 门槛）· IT **136/136**（30 套件，+1 平台域 PPM 用例）· vitest **386** · E2E **72/72**
- fat jar（`notification4j-app-1.4.0.jar`）冒烟通过：单池（原生 Druid wrapper 0 / Hikari 0）；PPM 路由 10200 闸 + 已认证 list/detail 全链 code=0
- 测试报告：`docs/test/report/2026-09-21-01/`

---

## v1.3.0 — 接入摩擦修复轮（issue #1/#2/#3 + framework4j v1.7.1）

- **发布日期**：2026-09-19（commit 见 git tag `v1.3.0`）
- **版本口径**：**Maven 坐标自此与 tag 一致（1.3.0）**，清除 v1.2.2 注册在案的版本错位

### 新增

| 项 | 内容 |
|---|---|
| **API-OEM-001**（issue #1） | `GET /nfy/api/v1/runtime/oem/hosts` 下发本租户 oem.hosts；嵌入消息中心 origin 白名单双面生效：构建时 `VITE_NFY_PARENT_ORIGINS` ∪ 运行时 `oem.hosts`（iframe 持候选 token 核验，命中才接受 NFY_TOKEN，fail-closed）；运营时可配、无需重打前端 |
| **数据面接管**（issue #3） | `NfyDataPlaneTakeoverFilter`（AutoConfigurationImportFilter，先例 lotask4j）：接入方只配 `framework4j.datasource.datasources.*` 即可启动——无需 `spring.datasource.*`（消除「Failed to configure a DataSource」与同库双池）、无需 `spring.autoconfigure.exclude`；闸门 `framework4j.datasource.enabled` / `framework4j.redis.enabled` / 总闸 `nfy.enabled` |
| **ID 生成器兜底**（issue #2） | `NfyMybatisPlusSupportAutoConfiguration`（before MybatisPlusAutoConfiguration，`@ConditionalOnMissingBean`）——接入方零配置即得雪花 id，自有 bean 自然让位 |

### 变更

- framework4j **v1.5.1 → v1.7.1**（web advice 拆分 / accesstoken 启动校验 fail-fast / token 自包含 embed-claims / advice 排序修复）；nfy 侧 Boot 3.2.7 / MP 3.5.7 钉版不变，全量回归零适配通过
- app 壳配置瘦身：删除 dev `spring.datasource.*` 与 `spring.autoconfigure.exclude`（filter 接管）；**单池实证**（原生 Druid wrapper 0 / Hikari 0）
- pom `1.0.0 → 1.3.0`

### 验收

- 四层全绿：starter 单测 **411/411**（双 JaCoCo 门槛，合并 96.66%）· IT **135/135**（30 套件，+6 新用例）· vitest **386/386** · E2E **72/72**（L8 +2）
- 测试报告：`docs/test/report/2026-09-19-01/`

---

## v1.2.2 — 验收回归修复轮（缺陷台账清零）

- **发布日期**：2026-09-18（Release 发布于 2026-09-17T23:35Z，commit `a91f7a9`）
- **版本口径**：对齐接口文档修订史 V1.2.2；**Maven 坐标本轮仍为 1.0.0**（下个交付窗口统一 bump）

### 修复清单

| 级别 | 缺陷 | 修复 |
|---|---|---|
| **P0×3** | 独立部署出厂配置开箱即瘫：签名面强制 HMAC 而 SPA 无签名层（全拒 10101）；NfyTenantSecretProvider 被 fwk4j InMemory 兜底抢占（正确签名也全拒 10200）；Druid wall 拦死引擎 SKIP LOCKED | 出厂 `path-patterns=[]`（接口文档 V1.2.2 同步）；`@AutoConfigureBefore` 排序修复 + `NfySignatureFaceTest` 防回归；出厂 `filters: stat,slf4j` |
| **P1** | EMAIL 渠道 verify 10604 与 patch 10610 死锁，纯 API 外发闭环断裂 | patch 豁免 lastVerifyAt + 熔断重启用清零（IM 不变）；引擎投递成功置 `last_verify_at` |
| **P2×3** | redisson 落默认 6379；前端全站 Invalid Date；失效 token 误导空态 | autoconfigure.exclude；fwkTime 统一解析；onAuthExpired 重握手 + 会话失效态 |

### 验收数据（出厂等价实例）

- 后端：starter 单测 **411/411**（双 JaCoCo 门槛）· 全量 IT **129/129**（Testcontainers，+4 新用例）
- 端到端：Playwright **70/70**（8 业务线 spec，91 张证据截图）· 前端三门 vitest **382** 全绿
- 日志三零：InMemorySecretProvider / Wall 异常 / 误连 6379

### 新增测试资产

- `frontend/e2e-regression/`：8 spec + SMTP sink / pg-fixup helper，可入 CI 常态回归；
- `docs/test/report/2026-09-17-01/`（第 1 轮：缺陷台账）与 `docs/test/report/2026-09-18-01/`（第 2 轮：修复复验）。

---

## v1.0.0 — 首个正式版本（全量交付）

- **发布日期**：2026-09-17（commit `214f56d`）

### 交付范围

- **接口契约 50/50 全实现**（附录 A 矩阵）：认证 / open / runtime（消息·公告·渠道·订阅·字典）/ admin / platform 三域 + 外发引擎；V1.2 特性同窗口交付：消息撤回（契约 #51，ADR-0010）、订阅免打扰 quiet_hours（ADR-0011）、人工重投 UI——每一项均有 Testcontainers 集成测试。
- **外发引擎**：SKIP LOCKED 扫描 · 退避重试（1-5-15min ≤3 次→DEAD）· 渠道熔断 · reaper 回收 · 每渠道限速（at-least-once）。
- **前端**：Vue3 消息中心五页 + 铃铛（postMessage 嵌入握手 / origin 白名单 / 内存 token / 未读 30s 轮询）。

### 部署三形态

| 形态 | 载体 | 适用 |
|---|---|---|
| 独立部署 | `notification4j-app` fat jar（前端入 jar，免双部署） | 平台运维 |
| 嵌入 | `notification-spring-boot-starter` | 强耦合业务方（local 直调，含数据面） |
| 跨进程 | `notification4j-client-starter` | 轻量业务方（remote HTTP，零 MyBatis/JDBC/Redis） |

### 质量数据（交付时点）

- 后端：单元 409+10 · 冒烟 8 · 回归 IT 126 · 覆盖率 96.50%（七包 100% + BUNDLE ≥96% 双门槛）
- 前端：vitest 358 · vue-tsc / eslint 0 错 0 警
- 全周期评审循环修复 7 项真实缺陷（remote 模式 NPE、公告跨域计数双口径、app 壳装配等）

---

## 升级注意

### 自 v1.0.0（含更早构建产物）升级至 v1.2.2

1. **签名面出厂语义变更（D-1，对 S2S 调用方影响最大）**：
   - v1.2.2 起，独立部署出厂 `framework4j.signature.path-patterns=[]`——**出厂不再对 `/nfy/api/v1/runtime/**` 强制 HMAC 签名**；
   - 若你在 v1.0.0 上依赖「出厂强制签名」作为防线：升级后该防线默认不存在，**必须自行加回 pattern**（app `application.yml` 注释含示例），并确保调用方走四元组签名（`X-Access-Key / X-Timestamp / X-Nonce / X-Signature`，±5min 容差 + nonce 10min 一次性）；密钥注册/轮换走 API-SEC-001（24h 宽限）；
   - 反向注意：`signature.enabled` 仍为 true，若你配置了 patterns 而调用方未签名，将统一拒绝（安全默认）。
2. **出厂 druid filters 变更（D-3）**：出厂 `stat,slf4j`（不再含 wall）。如需 wall，请先在有网/等价环境验证引擎链路全通后再加回——wall 不识别 PG 方言会拦死引擎 SKIP LOCKED 与 Flyway 迁移。
3. **redisson 显式排除（D-4）**：app 出厂 `spring.autoconfigure.exclude` 排除 redisson 自动装配。嵌入形态业务方如自用 redisson 不受影响；如复用本产品 yml 段，注意该排除项的存在原因（其端点不随数据源配置覆盖）。
4. **EMAIL 渠道使能行为变更（ND-L5-01）**：patch 渠道配置不再要求先通过 verify（lastVerifyAt 豁免）；渠道投递成功会回置 `last_verify_at`。IM 渠道分支行为不变。
5. **前端行为修复（F-1/F-2）**：时间字段按 Long→String 契约统一解析（修复全站 Invalid Date）；token 失效触发重新握手并呈现「会话已失效」态（不再误导空态）。嵌入宿主如自行解析时间字段，同样注意字符串型别。
6. **机密注入**：app 启动强依赖 `JWT_SECRET` / `AES_KEY` / `PLATFORM_CLIENT_SECRET` 环境变量（无默认值，缺失 fail-fast），升级/部署时确认注入方式。
7. **数据库**：无额外迁移要求（Flyway `validate-on-migrate: true`；非空库升级前先人工核对 schema 与迁移链一致，不静默 baseline）。
