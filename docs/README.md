# notification4j 文档导航

本仓库全部文档的唯一入口。状态标记：**已就绪** = 当前可读；**撰写中** = 文档治理约定路径，
内容由对应交付流补齐（路径即契约，不另设占位文件）。

## 根目录文档

| 文档 | 路径 | 用途 | 读者 |
|---|---|---|---|
| 项目主页 | [README.md](../README.md) | 定位、特性、快速开始、架构一分钟 | 所有人 |
| 许可证 | [LICENSE](../LICENSE) | MIT | 所有人 |
| 贡献指南 | CONTRIBUTING.md | 贡献流程与规约 | 贡献者 |
| 变更日志 | CHANGELOG.md | 版本变更记录 | 所有人 |
| 安全策略 | SECURITY.md | 漏洞报告渠道与安全基线 | 所有人 |

## docs/ 主导航

| 文档中文名 | 路径 | 用途 | 读者 |
|---|---|---|---|
| 开发原则 | [governance/dev-principles.md](governance/dev-principles.md) | 全仓开发纪律与工程约定（已就绪） | 研发 |
| 中间件中台租户设计 | [governance/middleware-tenant-design.md](governance/middleware-tenant-design.md) | 多租户中台通用方案：租户隔离 / token 型别 / 签名开关（已就绪） | 研发 / 架构 |
| 双模式技术方案 | [governance/dual-mode-starter-pattern.md](governance/dual-mode-starter-pattern.md) | user-starter 双模式（local 进程内 / remote 跨进程）通用方案（已就绪） | 研发 / 架构 |
| 产品需求文档（PRD） | [requirements/prd.md](requirements/prd.md) | 功能范围、用户故事、验收标准（已就绪） | 所有人 |
| 系统架构设计说明书（SAD） | [design/system-design.md](design/system-design.md) | 架构视图、模块划分、关键机制（已就绪） | 研发 / 架构 |
| 数据库设计说明书（DBD） | [design/database-design.md](design/database-design.md) | 表结构 / 索引 / 迁移策略（已就绪） | 研发 / DBA |
| 接口设计文档 | [api/api-spec.md](api/api-spec.md) | 三域 API 契约：open / api / platform（已就绪） | 业务方集成 / 前端 / 测试 |
| 嵌入集成指南 | [operations/integration-guide.md](operations/integration-guide.md) | iframe 嵌入 + postMessage 握手 + NotifyClient 接入实操（已就绪） | 业务方集成 |
| 用户使用手册 | operations/user-guide.md | 消息中心页面操作说明（撰写中） | 终端用户 / 业务方 |
| 部署指南 | deployment/deployment-guide.md | 环境要求、配置项、上线步骤、三形态部署（撰写中；过渡期见 [backend/README.md](../backend/README.md) 部署节） | 运维 |
| 测试计划 | test/test-plan.md | 四层测试体系、范围与准入准出（撰写中） | 测试 / 研发 |
| 测试用例 | test/test-cases.md | 用例集与业务线映射（撰写中） | 测试 |
| 测试报告归档 | test/report/ | 历轮全量回归证据（已就绪，见下节） | 测试 / 发布 |
| 发布说明 | release/release-notes.md | 版本特性与已知问题（撰写中） | 所有人 |
| 交付检查单 | release/delivery-checklist.md | 交付门禁逐项核对（撰写中） | 发布 |
| 验收报告 | release/acceptance-report.md | 验收结论与证据索引（撰写中） | 发布 / 验收方 |
| 交付总结 | [release/delivery-summary.md](release/delivery-summary.md) | V1.0 交付范围与达成情况（已就绪） | 发布 / 管理层 |
| 文档模板 | templates/ | 各类文档的标准骨架（撰写中） | 文档作者 |

## 测试报告归档（docs/test/report/）

| 目录 | 内容 |
|---|---|
| [test/report/2026-09-17-01/](test/report/2026-09-17-01/) | 第 1 轮全量回归：统一测试报告 + L1~L8 线报告 + screenshots/（含缺陷取证） |
| [test/report/2026-09-18-01/](test/report/2026-09-18-01/) | 第 2 轮全量回归（基线 v1.2.2）：E2E 70/70 + IT 129/129 双绿，修复项复验 |
| [test/report/2026-09-19-01/](test/report/2026-09-19-01/) | 第 3 轮接入摩擦修复轮（issue #1/#2/#3 + framework4j v1.7.1）：四层全绿 411+135+386+72，单池实证 |
| [test/report/2026-09-21-01/](test/report/2026-09-21-01/) | 第 4 轮（v1.4.0 平台站内信查询面）：四层全绿 421+136+386+72，fat jar 冒烟（单池 + PPM 路由） |
| test/report/local-run/ | 本地跑 E2E 默认证据目录（`NFY_EVIDENCE_DIR` 未设时落此处，跑时生成） |

每轮目录含 `统一测试报告.md`（执行摘要 / 逐线矩阵 / 缺陷台账）与 L1~L8 各业务线报告。

## 设计决策记录（ADR，docs/design/adr/）

| ADR | 决策 |
|---|---|
| [ADR-0001](design/adr/adr-0001-base-on-benefit4j.md) | 底座复用 benefit4j 工程改造，模块按单域收敛 |
| [ADR-0002](design/adr/adr-0002-dual-model-fanout.md) | 消息双模型——定向 fanout-on-write + 公告 fanout-on-read |
| [ADR-0003](design/adr/adr-0003-db-queue-engine.md) | 外发引擎 V1.0 = DB 队列 + SKIP LOCKED + 线程池，不引 MQ |
| [ADR-0004](design/adr/adr-0004-channel-spi-centralized-ratelimit.md) | 渠道接入 = SPI 适配器自研 + fwk4j-rate-limit 集中出站限速 |
| [ADR-0005](design/adr/adr-0005-unread-cache.md) | 未读数 = Redis 写时失效 + 回填 + 公告水位线；V1.0 轮询不推流 |
| [ADR-0006](design/adr/adr-0006-embed-iframe-entry.md) | 嵌入形态 = iframe 多入口 + postMessage 握手 |
| [ADR-0007](design/adr/adr-0007-token-dual-type.md) | token 型别方案 A——PLATFORM/TENANT 独立型别 |
| [ADR-0008](design/adr/adr-0008-per-tenant-signature.md) | HMAC 签名按租户开关（privileges.signature） |
| [ADR-0009](design/adr/adr-0009-engine-common-defer.md) | 外发引擎 common 下沉延后至 V1.1 |
| [ADR-0010](design/adr/adr-0010-cancel-race-safe-semantics.md) | 消息撤回的竞态安全语义（不追回已投递） |
| [ADR-0011](design/adr/adr-0011-quiet-hours-plan-defer.md) | 免打扰时段：计划期 next_retry_at 推迟（引擎零改动） |

## 按角色找文档

| 角色 | 推荐阅读路径 |
|---|---|
| 新人 | 根 README → requirements/prd.md → design/system-design.md → governance/dev-principles.md → test/test-plan.md |
| 业务方集成（后端） | api/api-spec.md → operations/integration-guide.md → design/adr/adr-0007 / adr-0008（token 与签名） |
| 业务方集成（前端嵌入） | operations/integration-guide.md → design/adr/adr-0006（iframe 握手） |
| 运维 / 部署 | deployment/deployment-guide.md（撰写中，过渡期 backend/README.md 部署节）→ design/adr/adr-0003（引擎运维语义） |
| 测试 | test/test-plan.md → test/test-cases.md → test/report/（历轮基线与缺陷台账） |
| 发布 / 验收 | release/release-notes.md → release/delivery-checklist.md → release/acceptance-report.md → release/delivery-summary.md |
