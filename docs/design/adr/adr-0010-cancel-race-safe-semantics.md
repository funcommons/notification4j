# ADR-0010 消息撤回的竞态安全语义（不追回已投递）

日期：2026-09-17 ｜ 状态：Accepted ｜ 关联：V1.2 编码第 19 步、ADR-0003（外发引擎 DB 队列）、ADR-0002（消息双模型）

## 背景

API-MSG-009 撤回（`POST /runtime/messages/{message_id}/cancel`）需与外发引擎并发：引擎 claim 将投递 PENDING→SENDING（短事务），发送完成回写 SUCCESS（CAS eq SENDING）。撤回若无视投递当前状态强改，会出现「引擎已发出、撤回又改状态」的双写竞态。撤回也是幂等敏感操作（重复点击/重试）。

## 决策

1. **撤回永不追回已线上投递**：级联拦截仅对 `status='PENDING'` 的投递做条件 UPDATE 置 CANCELLED；已 SENDING/SUCCESS/FAILED/DEAD 不动。语义与引擎 at-least-once（ADR-0003）一致——「已发出的不收回」是该语义的自然推论，不引入撤回通道与渠道侧的二次协议。
2. **消息主行同样条件 UPDATE**（eq status='SENT'）：已 CANCELLED 再撤 → 0 行更新 → 幂等成功（返回 cancelled_deliveries=0），不报错。
3. **防探测同码**：不存在/跨租户/非数字 id 一律 10400 同文案「消息不存在或无权访问」。
4. **biz_no 幂等记录不动**：撤回不解除 biz_no 幂等，同 biz_no 重发仍 10401；「已撤回」语义经 MSG-003 send-results 透出（message.status=CANCELLED）。

## 理由

- 双条件 UPDATE（tenant+status 前置）与引擎 CAS 天然互斥：撤回与 claim 同时命中同一行时，必有一方 0 行更新，无中间态；
- 强取消（撤回 SENDING）需要渠道侧撤回协议支持（钉钉/企微/邮件均无通用撤回 API），引入即过度设计；
- 竞态安全由 PG 单条 UPDATE 原子性保证，无锁无分布式协调。

## 影响

- 用户侧列表/详情/未读数对 CANCELLED 消息不可见（list/detail/unread 三处过滤）；
- 「已投递后撤回」表现为消息对用户消失但渠道消息仍在——文档 §5.5.2 已明示；
- IT：NfyMessageCancelTest 5 用例（含已 SUCCESS 投递撤回不追回断言）；引擎关闭场景下条件 UPDATE 契约仍成立，未做真引擎并发压测（登记于交付总结）。
