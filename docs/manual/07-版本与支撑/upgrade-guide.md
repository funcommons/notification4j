# 升级指南：版本口径、升级矩阵与通用步骤

> notification4j 使用手册 · 07-版本与支撑第 1 篇 · 版本口径 **v1.4.0**。
> 版本事实源：[CHANGELOG.md](../../../CHANGELOG.md) / [release-notes.md](../../release/release-notes.md)（含 §升级注意 原文）/ git tag 注释
> （`v1.0.0` / `v1.2.2` / `v1.3.0` / `v1.4.0`）。本文只收编**已发布版本**的升级口径，不做前瞻承诺。
> 部署与配置基础见 [deployment.md](../05-部署与运维/deployment.md)（含 Flyway 行为详解）与 [configuration.md](../05-部署与运维/configuration.md)。

## 1. 版本与坐标口径

### 1.1 版本史（tag 与 Maven 坐标的关系）

| git tag | 发布日期 | Maven 坐标（backend/pom.xml） | 坐标与 tag 是否一致 |
|---|---|---|---|
| `v1.0.0` | 2026-09-17 | 1.0.0 | 一致 |
| `v1.2.2` | 2026-09-18 | **1.0.0（错位在案）** | 否——tag 对齐接口文档修订史，坐标待下窗 bump |
| `v1.3.0` | 2026-09-19 | 1.3.0 | **自此起一致**（清除 v1.2.2 注册在案的版本错位） |
| `v1.4.0` | 2026-09-21 | 1.4.0 | 一致 |

> tag 口径：git tag / GitHub Release 的 `vX.Y.Z` 对齐接口文档（`docs/api/api-spec.md`）修订史；Maven 坐标与 tag
> **自 v1.3.0 起保持一致**。引用 v1.2.2 及更早版本时，注意「tag 版本号 ≠ 坐标版本号」。
> 自助查证：`git tag -l` 看发布线，`backend/pom.xml` 的 `<version>` 看坐标，
> https://github.com/funcommons/notification4j/releases 看 Release 与产物。

### 1.2 坐标双口径（同一构件，两种引入方式）

| 引入方式 | 坐标（以 v1.4.0 为例） | 适用 |
|---|---|---|
| 本地构建（`cd backend && mvn install`） | `fun.commons.notification4j:notification4j-starter:1.4.0`（嵌入）/ `fun.commons.notification4j:notification4j-client-starter:1.4.0`（跨进程） | 仓内联调 / 无法访问 JitPack 的环境 |
| JitPack（按 tag 远程构建） | `com.github.funcommons.notification4j:notification4j-starter:v1.4.0`（版本号带 `v`，即 tag 名） | 三方推荐；**仅 v1.3.0 起 tag 可用**（原因见 §4.2） |

两者指向同一构件，切换只换 `groupId:version`。framework4j（当前 v1.7.1）由 starter 传递引入，无需单独声明。

升级即换版本号（本地口径换 `<version>`，JitPack 口径换 tag 名）。JitPack 换到新 tag 后**首次拉取需等待远程构建**
（常见 1~3 分钟），失败先看构建日志：`https://jitpack.io/com/github/funcommons/notification4j/<tag>/build.log`。

### 1.3 fat jar 名规则

独立部署产物为 `notification4j-app-<Maven 坐标版本>.jar`（如 `notification4j-app-1.4.0.jar`；repackage 前的
thin jar 存为 `.jar.original`）。**jar 名跟随 pom 坐标而非 tag**——在 v1.2.2 上即 `notification4j-app-1.0.0.jar`。
升级脚本不要硬编码 jar 名（v1.4.0 起 E2E 启动脚本已改为 glob 匹配，可效仿）。

## 2. 升级矩阵

> 读法：「破坏性」指升级后行为与旧版不同、需要人工决策的项（安全默认变化、配置语义变化）；「必做动作」为空即
> 纯换版本号。跨多版本升级（如 v1.0.0 → v1.4.0）把途经路径的注意事项**逐条叠加**核对。

| 升级路径 | 日期 | 类型 | 破坏性 / 行为变更 | 必做动作 |
|---|---|---|---|---|
| v1.0.0 → v1.2.2 | 2026-09-18 | 验收回归修复轮 | 出厂配置语义变更 ×3、渠道行为 ×1、前端行为 ×2、启动 fail-fast ×1 | 见 §2.1 七条逐项核对 |
| v1.2.2 → v1.3.0 | 2026-09-19 | 接入摩擦修复轮 | 坐标错位清除；数据面接管（旧手工配置变冗余）；framework4j 大版本升级（零适配） | 见 §2.2，嵌入形态重点 |
| v1.3.0 → v1.4.0 | 2026-09-21 | 功能新增轮 | **无破坏**——纯新增只读 API + 版本 bump | 换坐标 `1.3.0 → 1.4.0` / tag `v1.4.0` 即可 |

### 2.1 v1.0.0 → v1.2.2：七条升级注意（收编自 release-notes §升级注意）

| # | 变更 | 内容与影响 | 必做动作 |
|---|---|---|---|
| 1 | **签名面出厂语义变更（D-1）** | 出厂 `framework4j.signature.path-patterns=[]`——不再对 `/nfy/api/v1/runtime/**` 强制 HMAC 签名；`signature.enabled` 仍为 true | 若依赖「出厂强制签名」作防线：**自行加回 pattern**（app `application.yml` 注释含示例；四元组 `X-Access-Key/X-Timestamp/X-Nonce/X-Signature`，±5min + nonce 10min 一次性）。反向注意：配了 patterns 而调用方未签名将统一拒绝 |
| 2 | **出厂 druid filters 变更（D-3）** | 出厂 `stat,slf4j`（不含 wall）——wall 不识别 PG 方言，会拦死引擎 `SKIP LOCKED` 与 Flyway 部分索引 | 如需 wall，先在有网/等价环境验证引擎链路全通再加回 |
| 3 | **redisson 显式排除（D-4）** | app 出厂 `spring.autoconfigure.exclude` 排除 redisson 自动装配（其端点不随数据源配置覆盖，恒落 127.0.0.1:6379） | 嵌入形态自用 redisson 不受影响；如复用本产品 yml 段，知悉该排除项的存在原因 |
| 4 | **EMAIL 渠道行为变更（ND-L5-01）** | `PATCH` 渠道配置豁免「先 verify」前置（lastVerifyAt 豁免）；投递成功回置 `last_verify_at`；IM 分支不变 | 纯 API 外发闭环自此可用；原「先 verify 再 patch」流程不再必需 |
| 5 | **前端行为修复（F-1/F-2）** | 时间字段按 Long→String 契约统一解析（修复全站 Invalid Date）；token 失效触发重握手 + 「会话已失效」态 | 嵌入宿主如自行解析时间字段，同样按字符串型别处理 |
| 6 | **机密注入 fail-fast** | app 启动强依赖 `JWT_SECRET` / `AES_KEY` / `PLATFORM_CLIENT_SECRET` 环境变量，无默认值，缺失即启动中止（长度校验 ≥32 自 v1.3.0 起加严，见 §2.2 #3） | 升级/部署前确认三项注入方式（K8s secret / env 文件等；≥32 强随机） |
| 7 | **数据库** | Flyway `validate-on-migrate: true`；不静默 baseline | 无额外迁移要求；**非空库升级前先人工核对 schema 与迁移链一致**；切勿手工修改历史迁移脚本 |

### 2.2 v1.2.2 → v1.3.0：数据面接管与坐标对齐

| # | 变更 | 内容与影响 | 必做动作 |
|---|---|---|---|
| 1 | **Maven 坐标错位清除** | pom `1.0.0 → 1.3.0`，自此与 tag 一致 | 本地坐标引入方改版本号 `1.0.0 → 1.3.0`（注意 v1.2.2 的坐标仍是 1.0.0）；JitPack 方改 tag `v1.3.0` |
| 2 | **数据面接管（issue #3）** | `NfyDataPlaneTakeoverFilter`（`AutoConfigurationImportFilter`）：`framework4j.datasource.enabled=true` 时 veto 原生 DataSource/Druid/MyBatis-Plus/Tx 自动装配，`framework4j.redis.enabled=true` 时 veto Redisson；总闸 `nfy.enabled=false` 全放行 | 接入方只配 `framework4j.datasource.datasources.default.*` 即可启动；旧的 `spring.datasource.*` 与 `spring.autoconfigure.exclude` 手工项**变死配置，可删**（留着也不报错；自管数据面的宿主设闸关闭即不受影响） |
| 3 | **framework4j v1.5.1 → v1.7.1** | web advice 拆分、accesstoken **启动期 fail-fast 校验**（secretKey≥32 / hashSalt / policies 清单式报错）、token 自包含（`embed-claims` 缺省 true，payload 携带 tenant_id）、advice 排序修复。nfy 侧 Boot 3.2.7 / MP 3.5.7 钉版不变，全量回归零适配通过 | 一般零动作；宿主如**自行配置** `framework4j.access-token.*`，升级后不合规配置会启动即报错（原先可能带病运行）——按报错清单修正即可 |
| 4 | 新增（无破坏） | API-OEM-001 `GET /nfy/api/v1/runtime/oem/hosts`：嵌入 origin 白名单双面生效（构建时 ∪ 运行时 `oem.hosts`，fail-closed）；ID 生成器兜底（零配置雪花 id） | 无需动作；需要运营时调整白名单时配置 `oem.hosts`，无需重打前端 |

升级后验证锚点：数据面接管以 `NfyDataPlaneTakeoverTest` 钉死（裸壳零手工 exclude 的嵌入形态 IT）；出厂等价实例
单池实证口径——启动日志**原生 Druid wrapper 0 / Hikari 0**、framework4j `defaultDataSource @Primary` 唯一。

### 2.3 v1.3.0 → v1.4.0：纯新增

- **无破坏性变更、无行为变更**：仅新增平台域只读查询面 API-PPM-001/002（`GET /nfy/platform/api/v1/messages`
  跨租户列表 + `GET /{message_id}` 详情含 `read_count`；`@PlatformDomain` + `@RequiresToken(TENANT)`，开关
  `nfy.runtime.enable-api`），数据面/配置面/契约存量项均不动。
- 路由装配沿既有三域注册双通道（autoconfig `enable-api` 开关 / 同基包组件扫描），无新增配置项——独立部署 app
  出厂 `enable-api: true` 即含 PPM；嵌入形态不开启 API 面则完全无感。
- 必做动作：换坐标（本地 `1.3.0 → 1.4.0` / JitPack `v1.3.0 → v1.4.0`）或换 fat jar；无配置迁移。
- 验收基线（升级后自检可参照）：四层 421 单测 / 136 IT / 386 vitest / 72 E2E 全绿 + fat jar 冒烟
  （`docs/test/report/2026-09-21-01/`）。

## 3. 通用升级步骤（每次升级过一遍）

> 适用于独立部署（换 fat jar）与嵌入 / 跨进程形态（换 starter 坐标）两种节奏；差异处在步骤 5 分列。
> 业务方（只引 starter）重点关注 1 / 2 / 5 / 6 / 8；独立部署运维全条过。

1. **读版本事实源**：目标版的 [release-notes.md](../../release/release-notes.md) 对应节 + [CHANGELOG.md](../../../CHANGELOG.md)，
   对照本文 §2 矩阵确认是否有新增必做动作。
2. **备份**：数据库 `pg_dump` 全量一份；当前 fat jar（或业务方锁定的 starter 坐标版本）留档——这是回滚的唯一凭据。
3. **Flyway 核对**（详见 [deployment.md §3.4](../05-部署与运维/deployment.md)）：
   - `validate-on-migrate: true`：库内已应用脚本的 checksum 必须与 jar 内脚本一致，**升级前切勿手工修改历史迁移脚本**，
     变更一律走新增 `V*` 脚本；
   - `baseline-on-migrate: false`：**只接受空库**，不会静默 baseline——非空库（已跑旧版本）升级前先人工核对 schema
     与迁移链一致；不要把服务指向带无关对象的「脏」库；
   - 现状：v1.0.0 → v1.4.0 无新增迁移脚本（仅 `V1.0.0` 建 12 表 + `V1.0.1` 触发器，首启即全量应用），跨版本升级无 DDL 变更。
4. **机密环境变量确认**：`JWT_SECRET` / `AES_KEY` / `PLATFORM_CLIENT_SECRET` 三项已注入且合规（≥32 字符，v1.2.2 起 fail-fast）。
5. **替换与灰度建议**：
   - 独立部署：先在预发/出厂等价实例（Testcontainers 或独立 PG/Redis）跑冒烟链，再滚动替换生产实例；
     多实例共用一库时引擎 `SKIP LOCKED` 保证竞争安全，但水平扩展未经压测（test-plan 边界），灰度以「逐台替换」为宜；
   - 嵌入形态：随宿主应用发布节奏灰度；升级 starter 坐标后建议先在集成环境确认数据面单池
     （启动日志无原生 Druid wrapper / Hikari）；
   - 跨大版本（途经 v1.2.2）：独立部署先在预发环境核对 §2.1 的签名面 / druid filters 出厂新语义与自身防线
     设计是否兼容，再上生产。
6. **升级后验证**：`curl http://<host>:9200/nfy/api/v1/ops/health` 应 `data.status=UP`（db/redis 双 UP）；
   完整清单见 [deployment.md §7](../05-部署与运维/deployment.md)。
   嵌入形态另确认：发一条测试消息走通 send → 投递 → 未读数链路（对应冒烟链用例）。
7. **回滚口径**：当前无跨版本 DDL 变更，回滚 = 换回备份 jar / 回退坐标版本重启；已发生的业务数据不回迁
   （v1.4.0 新增面只读、不写库，回滚无数据残留问题）。
8. **版本锁建议**：嵌入 / 跨进程形态在宿主 pom 中**显式锁定** starter 版本号，勿用 `LATEST`/区间——
   本项目坐标自 v1.3.0 起才与 tag 对齐，区间解析在旧版本上会踩 §1.1 / §4.2 的坑。

## 4. 历史已知坑对照（升级排障时先看这里）

### 4.1 v1.2.2 三项 P0（独立部署出厂配置开箱即瘫，均已修复）

| 缺陷 | 修前现象 | 修复 | 升级者关注点 |
|---|---|---|---|
| D-1 签名面摘除 runtime 路径 | SPA 与 S2S 共用 `/nfy/api/v1/runtime/**`，出厂强制 HMAC 使浏览器端全拒 10101 | 出厂 `path-patterns=[]`，`enabled:true` 与密钥解析基础设施保留 | S2S 需防重放自行加回 pattern（§2.1 #1） |
| D-2 签名密钥解析接线 | fwk4j InMemory 兜底抢先注册，正确签名也全拒 10200 | `@AutoConfigureBefore` 排序修复 + `NfySignatureFaceTest` 防回归 | 纯内部修复，接入方零动作；排障时别再怀疑密钥顺序 |
| D-3 出厂 druid 关 wall | wall 不识别 PG 方言，拦死引擎 `FOR UPDATE ... SKIP LOCKED` 与 Flyway 部分索引 WHERE | 出厂 `filters: stat,slf4j` | 加回 wall 前先验证引擎链路（§2.1 #2） |

### 4.2 v1.2.2 tag 的 JitPack Error 说明

**现象**：经 JitPack 引入 `com.github.funcommons.notification4j:*:v1.2.2`（或 `v1.0.0`）会构建失败/报 Error。

**根因**：JitPack 构建配置 `jitpack.yml`（显式 JDK 17 + Maven 3.9.9 + `-f backend/pom.xml`）于 v1.2.2 打 tag
**之后**才落库，首个包含它的 tag 是 `v1.3.0`。旧 tag 上 JitPack 按默认环境构建：默认 JDK 8 跑不动 Spring Boot 3.x
（`--release` 报错）、默认 Maven 过旧（maven-install-plugin 3.1.2 要求 3.6.3+）、且仓根无 pom（pom 在 `backend/`
子目录），三重叠加必然失败。build.log 中的典型报错即这三类，不必在业务侧排查。

**结论**：JitPack 引入口径**仅支持 `v1.3.0` 及之后 tag**。确需 v1.2.2 及更早版本时，本地构建后改用
`fun.commons.*` 坐标（且注意其版本号是 1.0.0，见 §1.1 错位说明）。

顺带说明：这不是「v1.2.2 代码有问题」——该 tag 的本地构建与运行完全正常（第 2 轮回归 129 IT + 70 E2E 双绿），
只是 JitPack 远程构建环境未被该 tag 覆盖。

## 5. 相关文档

| 主题 | 文档 |
|---|---|
| 各版本变更明细 / 验收数据 | [CHANGELOG.md](../../../CHANGELOG.md) · [release-notes.md](../../release/release-notes.md) |
| 部署 / 配置 / Flyway 详解 | [deployment.md](../05-部署与运维/deployment.md) · [configuration.md](../05-部署与运维/configuration.md) |
| 签名与 token 口径 | [signature.md](../03-开发指南/signature.md) · [auth.md](../03-开发指南/auth.md) |
| 常见问题 | [faq.md](faq.md) |
