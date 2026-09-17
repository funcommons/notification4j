package fun.commons.notification4j.engine;

import fun.commons.notification4j.entity.NfyaChannel;
import fun.commons.notification4j.entity.NfyaDelivery;

/**
 * 渠道适配器 SPI（技术方案 P5/OCP）：新增渠道 = 新增一个实现 + @Bean，核心引擎零修改。
 * 实现以 channelType() 匹配投递行的 channel_type；同类型多实现时后者覆盖（Map 语义）。
 */
public interface ChannelSender {

    /** 适配的渠道类型（DINGTALK/WECOM/FEISHU/EMAIL；多类型适配器可作展示标识） */
    String channelType();

    /** 类型匹配（默认按 channelType 精确匹配；一实例服务多类型时覆盖） */
    default boolean supports(String channelType) {
        return channelType().equals(channelType);
    }

    /**
     * 执行投递（纯外呼，不落库——状态由引擎统一回写）。
     *
     * @return SendResult(ok=业务码成功, summary=失败摘要/成功回执)
     */
    SendResult send(NfyaDelivery delivery, NfyaChannel channel);

    /**
     * 成功判定 = 渠道业务码成功（钉钉/企微 errcode=0、飞书 code=0、SMTP 2xx），
     * HTTP 200 业务失败不算成功（§4.3 语义声明）。
     */
    record SendResult(boolean ok, String summary) {

        public static SendResult success(String summary) {
            return new SendResult(true, summary);
        }

        public static SendResult fail(String summary) {
            return new SendResult(false, summary);
        }
    }
}
