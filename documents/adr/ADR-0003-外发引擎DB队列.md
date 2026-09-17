# ADR-0003: 外发引擎 V1.0 = DB 队列 + SKIP LOCKED + 线程池，不引 MQ

## 状态
proposed（2026-09-16，评审第 1 轮修订：补 reaper 与 at-least-once 语义）

## 上下文
站外渠道外发需异步、限速、重试、可观测。framework4j 无任务队列模块（缺口按《开发原则》§1.2 路径 1 自建留痕）。起步量级 ~1.6 万投递/天，峰值设计 35 万/天。

## 备选方案
- A. Kafka/RocketMQ（austin 方案）：运维重，量级未到，引入整套 MQ 栈得不偿失
- B. Redis Stream：轻量但持久化/重试语义需自建，生态工具少
- C. PG 队列表（nfya_delivery）+ `SELECT ... FOR UPDATE SKIP LOCKED` 竞争消费 + 渠道线程池

## 决策
选 C。语义闭环：扫描谓词 `status IN ('PENDING','FAILED') AND next_retry_at <= now()`；领取即置 SENDING（短事务）；SENDING 超 10min 由 reaper 回收重投；**at-least-once**（崩溃回收可能重复投递，IM/邮件场景可接受）；投递幂等真闸 `uk_nfya_delivery_source`；演进触发条件：日外发 >50 万或积压常态 >5min 时拆独立 worker + 引 MQ（delivery 表退化为留痕）。

## 后果
正面：零新中间件、多实例天然安全、投递即留痕（状态机一体）；负面：PG 承担队列读写（峰值 35 万/天 ≈ 4 TPS 扫描 + 状态翻转，可控）；存在 at-least-once 重复窗口（已在 PRD/API 声明）。
