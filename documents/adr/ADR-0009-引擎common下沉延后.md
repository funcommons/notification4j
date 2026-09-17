# ADR-0009：外发引擎 common 下沉延后至 V1.1

- 状态：已接受（2026-09-17）
- 关联：技术方案 §5.1（模块拓扑）、ADR-0003

## 背景

技术方案 §5.1 规划 `notification4j-common` 模块承载引擎（scanner/dispatcher/ChannelSender SPI + 4 渠道实现），
使「嵌入自跑引擎」形态可装配。编码第 6b 步实际将引擎落在
`notification-spring-boot-starter` 的 `fun.commons.notification4j.engine` 包。

## 决策

**V1.0 不建 common 模块**，引擎留在 starter；V1.1 出现以下任一信号时再执行纯搬运重构：

1. 出现第二个需要引擎的 artifact（如独立 worker 部署单元）；
2. starter 体积/依赖（jakarta.mail）对纯嵌入方构成负担。

## 理由

- 搬运是无逻辑变化的纯移动（包名+pom），当前只有 1 个消费者，提前分模块只有成本没有收益（开发原则：不过度设计）；
- starter 内实现已通过 62→71 项 IT 验证，搬运可在任意时点零风险执行；
-EMAIL 通道引入的 jakarta.mail/angus-mail 依赖已通过父 pom dependencyManagement 锁定离线版本。

## 影响

- 嵌入形态（enable-api=false + engine.enabled=true）在同一 starter 内自跑引擎——已覆盖；
- 文档 §5.1 模块图与本实现的偏差以本 ADR 为准。
