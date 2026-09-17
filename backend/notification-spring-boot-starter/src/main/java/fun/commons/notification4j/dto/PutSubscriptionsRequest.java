package fun.commons.notification4j.dto;

import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * API-SUB-002 全量保存订阅矩阵（§5.8）：全量替换语义，last-write-wins；items 缺失即误配，@NotNull 防呆清空。
 * V1.2 第 20 步：items[] 每行可选 quietHours（免打扰时段，HH:mm 成对出现才算启用；缺省/{}=未启用，
 * 全量替换语义下缺省即重置未启用）；格式与成对校验在 SubscriptionService（10100）。
 */
public record PutSubscriptionsRequest(@NotNull List<Item> items) {

    public record Item(String typeCode, List<String> channelIds, QuietHours quietHours) {
    }

    /** 免打扰时段：{"start":"22:00","end":"08:00"}，跨午夜允许；start=end/不成对/格式错 → 10100 */
    public record QuietHours(String start, String end) {
    }
}
