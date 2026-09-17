package fun.commons.notification4j.client;

import java.util.List;

/**
 * 业务方门面（双模式技术方案 §5.1）：业务方只依赖本接口，不感知 local/remote。
 *
 * <p>装配条件（autoconfig）：{@code nfy.client.enabled=true} 显式开启（默认关）；
 * mode=local → LocalNotifyClient（直连 service，缺数据面启动即失败）；
 * mode=remote → RemoteNotifyClient（HttpTransport + JWT + X-Access-Key）。
 *
 * <p>remote 模式下 tenantId 参数由服务端 token 决定（客户端忽略，便于同代码跨模式）。
 */
public interface NotifyClient {

    /** 发送定向消息（站内信 + 订阅矩阵外发计划） */
    SendMessageResult send(long tenantId, SendMessageRequest req);

    /** 快捷公告：创建并立即发布（生效窗口缺省 = 立即生效 7 天） */
    AnnounceResult announce(long tenantId, AnnounceRequest req);

    /** 未读数（站内信 + 公告未确认合成，§5.3） */
    UnreadSummary unreadCount(long tenantId, String userid);

    /** 我的消息列表（Cursor 分页） */
    MessagePage listMessages(long tenantId, String userid, String cursor, Integer limit);

    /** 我的消息列表（默认 limit=20） */
    default MessagePage listMessages(long tenantId, String userid) {
        return listMessages(tenantId, userid, null, null);
    }

    /** 发送结果 */
    record SendMessageResult(String messageId, String bizNo, int receiverCount, boolean inappSaved,
                             int deliveryPlanned) {
    }

    /** 公告发布结果 */
    record AnnounceResult(String announcementId) {
    }

    /** 未读数摘要 */
    record UnreadSummary(long unreadCount, long unconfirmedCount, long total) {
    }

    /** 消息列表项（门面精简视图） */
    record MessageItemSummary(String messageId, String title, String typeCode, String level,
                              String readStatus, Long createdAt) {
    }

    /** 消息分页 */
    record MessagePage(List<MessageItemSummary> list, String nextCursor, boolean hasMore) {
    }
}
