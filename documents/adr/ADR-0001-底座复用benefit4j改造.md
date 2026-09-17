# ADR-0001: 底座复用 benefit4j 工程改造，模块按单域收敛

## 状态
proposed（2026-09-16，评审第 1 轮确认中）

## 上下文
notification4j 需要多租户中台底座（租户/认证/三域/控制台/嵌入四入口/双模式 starter）。已复制 benefit4j 前后端源码到本仓库。备选开源项目（yudao/JeecgBoot/austin）均无「多租户+三域+嵌入微前端」形态。

## 备选方案
- A. benefit4j 底座改造（技术件原样沿用，权益业务模型替换为消息域）
- B. 引入 yudao/JeecgBoot 全家桶（租户模型与《中间件中台租户设计》不兼容，改造 >50%，违反《开发原则》§2 阈值）
- C. 从零自研底座（重复建设，违反《开发原则》原则一/二）

## 决策
选 A。模块结构按单业务域（N=1）从《双模式技术方案》7 模块收敛为 4 模块：`notification4j-common` / `notification-spring-boot-starter` / `notification4j-app` / `notification4j-it`；common 保留用于自建共享件归口（《开发原则》§1.2）。

## 后果
正面：与 benefit4j 同构，租户/嵌入/双模式生产验证件直接可用；负面：需批量重命名 benefit4j → notification4j 包名/配置键；开源项目仅作分模块参考（PRD §2.3.5 T2~T5），不引入其依赖栈。
