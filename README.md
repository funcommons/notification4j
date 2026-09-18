# notification4j

通用应用系统的站内消息 / 公告 / 站外通知多租户微中台：应用系统（租户）通过
**OpenAPI / Java Starter（NotifyClient）** 发消息，终端用户在**嵌入式消息中心**
查看通知、自注册 IM 群 webhook 渠道、配置「消息类型 × 渠道」订阅矩阵；独立部署形态
内置外发引擎，将站内消息投递到钉钉 / 企微 / 飞书 / 邮件。

> 基于 framework4j v1.7.1 构建 · JDK 17 · Spring Boot 3.2.7 · PostgreSQL 16 · Redis 7

[![Release](https://img.shields.io/github/v/tag/funcommons/notification4j?label=release&sort=semver)](https://github.com/funcommons/notification4j/releases)
[![License](https://img.shields.io/badge/license-MIT-green.svg)](LICENSE)
[![Java](https://img.shields.io/badge/Java-17-blue.svg)](backend/pom.xml)
[![docs](https://img.shields.io/badge/docs-在线文档-blue?logo=github)](docs/README.md)

> **在线阅读文档**：全部文档随仓库发布，GitHub 直接渲染——入口 [docs/README.md](docs/README.md)
> （需求 PRD / 概要设计 / 接口契约 / 部署手册 / 用户手册 / 测试计划与报告 / ADR / 发布说明）。
> Maven 依赖可通过 JitPack 按 tag 引入：`com.github.funcommons.notification4j:notification4j-starter:v1.3.0`。

## 特性

- **站内信**：定向 fanout-on-write，批量异步 Job，撤回（竞态安全语义，不追回已投递）
- **公告**：fanout-on-read 免落库展开，租户公告与平台公共公告合并生效列表，已读 / 已确认
- **渠道与订阅**：渠道 SPI 适配器（钉钉 / 企微 / 飞书 / 邮件），租户自注册 IM 群 webhook，「消息类型 × 渠道」订阅矩阵（站内信恒选）
- **外发引擎**：DB 队列 + SKIP LOCKED，指数退避重试 + 失败熔断 + 免打扰时段（quiet_hours），按渠道集中出站限速
- **多租户**：租户 OpenID 隔离，token 双型别（PLATFORM / TENANT），租户级 HMAC 签名开关，租户生命周期（SUSPEND 即时阻断）
- **三种部署形态**：独立部署 fat jar / 嵌入 starter（进程内）/ client-starter（跨进程）——业务代码零改动切换
- **嵌入消息中心**：iframe 多入口 + postMessage 握手，消息 / 公告 / 渠道 / 订阅 / 投递五页 + 铃铛，SPA 托管进 jar 免双部署

## 快速开始

### 1. 后端构建与测试

前置：JDK 17、本机 Docker（集成测试用 Testcontainers 拉起 PG16 + Redis7）；framework4j v1.7.1 已在本机 m2。

```bash
cd backend
mvn -o install -DskipTests            # 构建四模块并安装 starter 到本地仓库
mvn -o test -pl notification4j-it     # 集成测试 135 用例（30 套件）
```

只跑冒烟层（一条顺序链跑通核心业务闭环）：`mvn -o test -pl notification4j-it -Dgroups=smoke`。

### 2. 独立部署运行

```bash
cd backend
mvn -o package -pl notification4j-app -DskipTests   # 产物 notification4j-app-1.3.0.jar（fat jar）
```

前置：PostgreSQL 16 + Redis 7（出厂配置指向 `localhost:5432` / `6379`）；三个机密环境变量 fail-fast、无默认值。

```bash
JWT_SECRET=<32B+ 随机串> \
AES_KEY=<32B 随机串> \
PLATFORM_CLIENT_SECRET=<平台域密钥> \
java -jar notification4j-app/target/notification4j-app-1.3.0.jar
```

启动后：消息中心控制台 `http://localhost:9200/`（SPA 由 jar 直接托管），OpenAPI
`http://localhost:9200/swagger-ui/index.html`，tracelog 控制台 `/tracelog/index.html`。

### 3. E2E 回归

前置：本机已创建容器 `nfy4j-e2e-pg`（PG16 → 25432）与 `nfy4j-e2e-redis`（→ 26379）。

```bash
docker start nfy4j-e2e-pg nfy4j-e2e-redis
bash frontend/e2e-regression/bin/start-app.sh        # 启动出厂等价测试实例 :9200（引擎提速旋钮）
cd frontend
NFY_EVIDENCE_DIR=../docs/test/report/local-run/screenshots \
  npx playwright test -c e2e-regression/playwright.config.ts   # 72 用例 · L1~L8 八条业务线
```

## 架构一分钟

同一套业务实现，三种接入形态按依赖坐标切换：

| 形态 | 引入 | 适用 | 数据面 |
|---|---|---|---|
| 独立部署 | `notification4j-app` fat jar | 作为微中台独立运行，多业务方共享 | 自带（PG + Redis + 引擎 + SPA 托管） |
| 嵌入接入 | `notification4j-starter` | 通知能力嵌进宿主应用进程（mode=local） | 共享宿主（MyBatis + PG + Redis） |
| 跨进程接入 | `notification4j-client-starter` | 只发消息 / 公告，调独立部署实例（mode=remote） | **零**（HTTP + S2S JWT + HMAC 签名） |

后端 Maven 多模块（parent: `notification4j-parent`）：

| 模块目录 | artifactId | 职责 |
|---|---|---|
| `notification-spring-boot-starter` | `notification4j-starter` | 全量业务实现：三域 API / 服务 / 外发引擎 / NotifyClient 门面 / SPA 托管 |
| `notification4j-client-starter` | `notification4j-client-starter` | 跨进程轻量接入（零数据面，与全量 starter 同 API 门面） |
| `notification4j-it` | `notification4j-it` | 集成测试层（Testcontainers PG16 + Redis7，Flyway 真实迁移） |
| `notification4j-app` | `notification4j-app` | 独立部署壳（出厂配置 / Flyway baseline / OpenAPI） |

业务方发消息（嵌入与跨进程同一接口，切换形态只换依赖坐标）：

```java
@Autowired NotifyClient notifyClient;
notifyClient.send(tenantId, SendMessageRequest.of(
    "ORDER", List.of("u_1"), "订单已支付", "内容", "NORMAL", null, "biz-1"));   // biz-1 幂等
```

前端消息中心为 Vue 3 微前端（五页 + 铃铛），iframe 嵌入 + postMessage 握手，
接入细节见 [docs/operations/integration-guide.md](docs/operations/integration-guide.md)。

## 测试与质量

四层测试体系（单元 / 冒烟 / 集成回归 / E2E）定义见 `docs/test/test-plan.md`：

| 层 | 命令 | 规模 |
|---|---|---|
| 后端单元（starter 纯单测） | `mvn -o test -pl notification-spring-boot-starter` | 411 用例 |
| 后端集成（Testcontainers） | `mvn -o test -pl notification4j-it` | 135 用例 / 30 套件 |
| 冒烟（集成层子集） | `mvn -o test -pl notification4j-it -Dgroups=smoke` | 8 用例顺序链 |
| 前端组件单测 | `cd frontend && pnpm vitest run` | 386 用例 |
| E2E 回归（Playwright） | `npx playwright test -c e2e-regression/playwright.config.ts` | 72 用例 / 8 业务线 |

覆盖率双门槛（合并口径 = starter 单测 exec + IT exec）：`dto / entity / kms / tracelog / client /
util / controller` 七包行覆盖 100%；整体 BUNDLE 行覆盖 ≥ 96%。明细见
[backend/README.md](backend/README.md)，历轮全量回归证据见 [docs/test/report/](docs/test/report/)。

## 文档导航

全部文档入口与「按角色找文档」索引见 **[docs/README.md](docs/README.md)**（PRD / 系统设计 /
数据库设计 / API 契约 / 11 篇 ADR / 集成指南 / 部署 / 测试报告 / 发布物）。

## License

[MIT](LICENSE)
