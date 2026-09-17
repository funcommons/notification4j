package fun.commons.notification4j.client;

import java.time.OffsetDateTime;

/** 公告请求（门面 DTO；时间窗缺省 = 立即生效 + 7 天失效；bizNo 缺省放弃幂等） */
public record AnnounceRequest(String title, String content, String level, Integer needConfirm,
                              Long effectiveAtEpochMs, Long expireAtEpochMs, String bizNo) {

    public static AnnounceRequest of(String title, String content, String level, Integer needConfirm,
                                     Long effectiveAtEpochMs, String bizNo) {
        return new AnnounceRequest(title, content, level, needConfirm, effectiveAtEpochMs, null, bizNo);
    }

    public Long effectiveAtOrDefault() {
        return effectiveAtEpochMs != null ? effectiveAtEpochMs : OffsetDateTime.now().minusSeconds(1).toInstant().toEpochMilli();
    }

    public Long expireAtOrDefault() {
        return expireAtEpochMs != null ? expireAtEpochMs : OffsetDateTime.now().plusDays(7).toInstant().toEpochMilli();
    }
}
