package fun.commons.notification4j.service;

import fun.commons.notification4j.entity.NfyaChannel;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 第 24 步纯函数层单测：ChannelService#mask 静态脱敏（列表/快照落库共用）。
 * 分支：EMAIL 单字符本地段、@ 缺失/首位、FEISHU path 末段遮蔽、无 path 回落 query 遮蔽、
 * 其余类型 query 遮蔽/无 query 原样、q=0 边界。
 */
// VECTOR: TAG=step24-unit
class ChannelServiceMaskTest {

    private static NfyaChannel channel(String type, String target) {
        NfyaChannel ch = new NfyaChannel();
        ch.setChannelType(type);
        ch.setTarget(target);
        return ch;
    }

    // ---- EMAIL：首字符 + *** + @域 ----

    @Test
    void email_masks_local_part_keeping_domain() {
        assertThat(ChannelService.mask(channel("EMAIL", "justin@example.com"))).isEqualTo("j***@example.com");
    }

    @Test
    void email_single_char_local_part() {
        assertThat(ChannelService.mask(channel("EMAIL", "a@b.co"))).isEqualTo("a***@b.co");
    }

    @Test
    void email_no_at_is_fully_masked() {
        assertThat(ChannelService.mask(channel("EMAIL", "not-an-email"))).isEqualTo("***");
    }

    @Test
    void email_at_at_position_zero_is_fully_masked() {
        assertThat(ChannelService.mask(channel("EMAIL", "@example.com"))).isEqualTo("***");
    }

    @Test
    void email_trailing_at_keeps_first_char() {
        assertThat(ChannelService.mask(channel("EMAIL", "bob@"))).isEqualTo("b***@");
    }

    // ---- FEISHU：path 末段（凭证）一并遮蔽 ----

    @Test
    void feishu_masks_hook_token_in_last_path_segment() {
        assertThat(ChannelService.mask(channel("FEISHU", "https://open.feishu.cn/open-apis/bot/v2/hook/tok-123")))
                .isEqualTo("https://open.feishu.cn/open-apis/bot/v2/hook/****");
    }

    @Test
    void feishu_host_root_only_falls_through_to_query_branch() {
        // 无 path（lastSlash == indexOf("//")+1）→ 保留 host，无 query 原样返回
        assertThat(ChannelService.mask(channel("FEISHU", "https://open.feishu.cn")))
                .isEqualTo("https://open.feishu.cn");
    }

    @Test
    void feishu_trailing_slash_masks_empty_last_segment() {
        assertThat(ChannelService.mask(channel("FEISHU", "https://open.feishu.cn/")))
                .isEqualTo("https://open.feishu.cn/****");
    }

    // ---- 其余类型：query 全遮蔽 ----

    @Test
    void dingtalk_shields_query_string() {
        assertThat(ChannelService.mask(channel("DINGTALK", "https://oapi.dingtalk.com/robot/send?access_token=abc×tamp=1")))
                .isEqualTo("https://oapi.dingtalk.com/robot/send?****");
    }

    @Test
    void wecom_without_query_returned_verbatim() {
        assertThat(ChannelService.mask(channel("WECOM", "https://qyapi.weixin.qq.com/cgi-bin/webhook/send")))
                .isEqualTo("https://qyapi.weixin.qq.com/cgi-bin/webhook/send");
    }

    @Test
    void query_at_position_zero_is_not_shielded() {
        // q>0 才遮蔽：q=0 视为无 query 边界（脏数据防御，原样返回）
        assertThat(ChannelService.mask(channel("DINGTALK", "?access_token=abc"))).isEqualTo("?access_token=abc");
    }
}
