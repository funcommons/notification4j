package fun.commons.notification4j.client;

import java.util.List;

/** 发送请求（门面 DTO；level 缺省 NORMAL，bizNo 缺省服务端生成 UUID） */
public record SendMessageRequest(String typeCode, List<String> userIds, String title, String content,
                                 String level, String linkUrl, String bizNo) {

    public static SendMessageRequest of(String typeCode, List<String> userIds, String title, String content,
                                        String level, String linkUrl, String bizNo) {
        return new SendMessageRequest(typeCode, userIds, title, content, level, linkUrl, bizNo);
    }
}
