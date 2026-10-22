# notification4j 使用手册

> 面向**三方接入方**的文档中心：读懂 notification4j 的功能、架构与价值，并按角色实操对接。
> 版本口径：**v1.4.0**（git tag `v1.4.0`，Maven 坐标 `1.4.0`）。终端用户（收消息的人）的页面操作手册见
> [docs/operations/user-guide.md](../operations/user-guide.md)（另册），不在本手册范围。

## 1. 本手册覆盖什么

- **业务方后端接入**：引 starter / 调 OpenAPI 发消息、发公告、查投递；
- **业务方前端嵌入**：把消息中心（五页 + 铃铛）iframe 嵌进宿主应用；
- **自托管与运维**：独立部署、配置项、日常运维与升级。

不覆盖：源码贡献流程（[CONTRIBUTING.md](../../CONTRIBUTING.md)）、内部设计与实现细节
（[docs/design/](../design/) SAD / ADR）、测试体系（[docs/test/test-plan.md](../test/test-plan.md)）。

## 2. 按角色选阅读路径

### 路径 A：后端接入 · 嵌入形态（引 starter，进程内直调）

| 步 | 文档 | 目的 |
|---|---|---|
| 1 | [01-概览/product-overview.md](01-概览/product-overview.md) | 建立产品与三形态全局认知 |
| 2 | [02-快速开始/quickstart-embed.md](02-快速开始/quickstart-embed.md) | 引依赖、配数据面、发出第一条消息 |
| 3 | [03-开发指南/onboarding.md](03-开发指南/onboarding.md) | 开通租户、拿密钥、配消息类型 |
| 4 | [03-开发指南/auth.md](03-开发指南/auth.md) | 换 token（client_credentials）与 token 型别 |
| 5 | [03-开发指南/signature.md](03-开发指南/signature.md) | HMAC 签名（按租户开关，可选） |
| 6 | [03-开发指南/sending.md](03-开发指南/sending.md) | 发送/批量/撤回/查询结果全链 |
| 7 | [04-参考/error-codes.md](04-参考/error-codes.md) | 错误码排查速查 |

### 路径 B：轻量接入 · 跨进程（client-starter，零数据面）

| 步 | 文档 | 目的 |
|---|---|---|
| 1 | [01-概览/product-overview.md](01-概览/product-overview.md) | 确认 remote 形态适用性 |
| 2 | [02-快速开始/quickstart-remote.md](02-快速开始/quickstart-remote.md) | 引 client-starter、指向远端实例 |
| 3 | [03-开发指南/onboarding.md](03-开发指南/onboarding.md) | 在独立部署实例上开通本租户 |
| 4 | [03-开发指南/auth.md](03-开发指南/auth.md) | 换 token 与凭据管理 |
| 5 | [03-开发指南/sending.md](03-开发指南/sending.md) | NotifyClient 发送（与嵌入形态同一接口） |
| 6 | [04-参考/error-codes.md](04-参考/error-codes.md) | 错误码排查速查 |

### 路径 C：前端嵌入（iframe 消息中心 + 铃铛）

| 步 | 文档 | 目的 |
|---|---|---|
| 1 | [01-概览/product-overview.md](01-概览/product-overview.md) | 认识五页 + 铃铛与嵌入模型 |
| 2 | [02-快速开始/quickstart-frontend.md](02-快速开始/quickstart-frontend.md) | 嵌第一个 iframe 入口 |
| 3 | [03-开发指南/frontend-embed.md](03-开发指南/frontend-embed.md) | postMessage 握手 / token 传递 / origin 白名单 |
| 4 | [03-开发指南/auth.md](03-开发指南/auth.md) | 嵌入面鉴权口径（TENANT token + X-User-Id） |
| 5 | [04-参考/error-codes.md](04-参考/error-codes.md) | 错误码排查速查 |

### 路径 D：自托管 / 运维（独立部署形态）

| 步 | 文档 | 目的 |
|---|---|---|
| 1 | [01-概览/product-overview.md](01-概览/product-overview.md) | 产品与部署形态选型 |
| 2 | [01-概览/technical-whitepaper.md](01-概览/technical-whitepaper.md)（选读） | 架构深读 |
| 3 | [05-部署与运维/deployment.md](05-部署与运维/deployment.md) | 环境 / 构建 / 启动 / 健康验证 |
| 4 | [05-部署与运维/configuration.md](05-部署与运维/configuration.md) | 全量配置项与出厂默认 |
| 5 | [05-部署与运维/operations.md](05-部署与运维/operations.md) | 日常运维（引擎 / 投递 / 容量） |
| 6 | [07-版本与支撑/upgrade-guide.md](07-版本与支撑/upgrade-guide.md) | 版本升级与兼容口径 |

## 3. 文档地图

> 路径即契约：全部 24 篇文档均已就绪，改名须同步更新本地图与阅读路径。
> 专题速查：发送链细节见 `03-开发指南/sending.md`，公告运营见 `announcements.md`，渠道与订阅见
> `channels.md` / `subscription.md`，平台域管理见 `platform-admin.md`。

| 目录 | 文档 |
|---|---|
| **01-概览** | [product-overview.md](01-概览/product-overview.md)（产品简介）· [technical-whitepaper.md](01-概览/technical-whitepaper.md)（技术白皮书） |
| **02-快速开始** | [quickstart-5min.md](02-快速开始/quickstart-5min.md) · [quickstart-embed.md](02-快速开始/quickstart-embed.md) · [quickstart-remote.md](02-快速开始/quickstart-remote.md) · [quickstart-frontend.md](02-快速开始/quickstart-frontend.md) |
| **03-开发指南** | [onboarding.md](03-开发指南/onboarding.md) · [auth.md](03-开发指南/auth.md) · [signature.md](03-开发指南/signature.md) · [sending.md](03-开发指南/sending.md) · [announcements.md](03-开发指南/announcements.md) · [channels.md](03-开发指南/channels.md) · [subscription.md](03-开发指南/subscription.md) · [frontend-embed.md](03-开发指南/frontend-embed.md) · [platform-admin.md](03-开发指南/platform-admin.md) |
| **04-参考** | [error-codes.md](04-参考/error-codes.md) |
| **05-部署与运维** | [deployment.md](05-部署与运维/deployment.md) · [configuration.md](05-部署与运维/configuration.md) · [operations.md](05-部署与运维/operations.md) |
| **06-最佳实践** | [best-practices.md](06-最佳实践/best-practices.md) · [security-checklist.md](06-最佳实践/security-checklist.md) · [troubleshooting.md](06-最佳实践/troubleshooting.md) |
| **07-版本与支撑** | [upgrade-guide.md](07-版本与支撑/upgrade-guide.md) · [faq.md](07-版本与支撑/faq.md) |

## 4. 版本与坐标

| 项 | 值 |
|---|---|
| 当前版本 | v1.4.0（git tag / GitHub Release；Maven 坐标 1.4.0，自 v1.3.0 起与 tag 一致） |
| fat jar 产物 | `notification4j-app-1.4.0.jar` |
| Maven 坐标 | `fun.commons.notification4j:notification4j-starter:1.4.0`（嵌入）/ `fun.commons.notification4j:notification4j-client-starter:1.4.0`（跨进程） |
| JitPack 引入 | `com.github.funcommons.notification4j:notification4j-starter:v1.4.0`（同 tag 构建安装全部 backend 模块，client-starter 同前缀可解析） |
| 底座 | framework4j v1.7.1 · JDK 17 · Spring Boot 3.2.7 · MyBatis-Plus 3.5.7 · PostgreSQL 16 · Redis 7 |

## 5. 报障与反馈

- 缺陷 / 需求：[GitHub Issues](https://github.com/funcommons/notification4j/issues)（附 `trace_id` 与复现步骤）；
- 安全漏洞：走 [SECURITY.md](../../SECURITY.md) 渠道，勿开公开 issue。

## 6. 与其他事实源的关系

| 事实源 | 定位 |
|---|---|
| [docs/api/api-spec.md](../api/api-spec.md) | **API 唯一事实源**：三域 54 项契约、错误码、信封、限流与分页口径；本手册只做导读，冲突时以它为准 |
| [docs/design/](../design/)（SAD / DBD / 11 篇 ADR） | 架构与实现决策事实源（fanout 双模型、token 双型别、签名开关、引擎语义等） |
| [CHANGELOG.md](../../CHANGELOG.md) 与 [docs/release/release-notes.md](../release/release-notes.md) | 版本事实源：每个版本改了什么、验收数据 |
| [docs/requirements/prd.md](../requirements/prd.md) | 需求与产品语义事实源（订阅矩阵 / 等级 / 免打扰等产品口径） |
| [docs/README.md](../README.md) | 仓库全量文档导航（本手册是其「按角色找文档」的手册侧入口） |
| docs/operations/integration-guide.md（已下线） | 旧嵌入集成实操，已收编入本手册 03-开发指南/frontend-embed.md 与 02-快速开始/quickstart-frontend.md |
