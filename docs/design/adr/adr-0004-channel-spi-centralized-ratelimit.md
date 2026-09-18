# ADR-0004: 渠道接入=SPI 适配器自研 + fwk4j-rate-limit 集中出站限速

## 状态
proposed（2026-09-16，评审第 1 轮修订：限速器从 Guava 改为集中式）

## 上下文
需接入钉钉/企微/飞书群机器人 webhook（V1.0）与邮件 SMTP（V1.0）、短信（V1.2）。三 IM 协议差异（msgtype vs msg_type、加签位置、业务码判定）须收敛。群机器人有硬限额（钉钉/企微 20 条/min/机器人），nfy-app 多实例部署下限速必须集中，否则限额翻倍有封禁风险。

## 备选方案
- A. 引入 austin handler 层：拖入其 Kafka/Flink/Apollo 全栈（PRD §2.3.5 T2 已否）
- B. 自研 ChannelSender SPI + 4 实现（协议参照 group-robot/HertzBeat）
- 限速：Guava 本地令牌桶（双实例 20/min→40/min，超限额）vs fwk4j-rate-limit 编程式 `tryAcquireFixedWindow("nfy:ch:"+channel_id, limit, 60)`（Redis Lua 集中式）

## 决策
选 B + fwk4j-rate-limit 集中限速（固定窗口：默认 75% 档（15/min）降低边界越限概率，需硬保证时配 50% 档（10/min）；编程式提供滑动窗口则优先）。SPI 接口与 4 实现**全部放 `notification4j-common/engine`**（评审第 3 轮修正：下沉 common 使「业务方同库嵌入且自跑引擎」形态可装配；DB 访问经 DeliveryStore port 注入）；短信 V1.2 引 Sms4J 依赖实现 SmsChannelSender，不自研短信聚合。

## 后果
正面：新增渠道=新实现+配置（OCP）；限速命中开发原则红线零自建；负面：IM 适配器代码需自测三平台协议（群机器人协议稳定、体量小，风险低）。
