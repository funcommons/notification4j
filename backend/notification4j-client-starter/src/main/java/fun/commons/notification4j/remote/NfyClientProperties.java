package fun.commons.notification4j.remote;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * client-starter 裁剪版配置（���码第 30 步）：与全量 starter 的 {@code NfyProperties} 同前缀
 * {@code nfy.runtime}，仅保留 remote 跨进程接入所需四项 + 必要超时。
 *
 * <p>与全量 starter 的口径差异（刻意）：{@code mode} 默认 {@code remote}（本 starter 无数据面，
 * local 不可能成立；显式配 local 由装配期 Assert fail-fast 提示改用全量 starter）。
 * 配置文件从全量 starter 切换过来时 {@code mode: remote} 行原样兼容。
 */
@Data
@ConfigurationProperties(prefix = "nfy.runtime")
public class NfyClientProperties {

    /**
     * 运行模式：client-starter 仅支持 remote（缺数据面，local 装配即失败）
     */
    private String mode = "remote";

    /**
     * remote 模式下的远程服务端点，例如 http://notification4j-svc:8080（必填，缺失启动即失败）
     */
    private String remoteUrl;

    /**
     * remote 模式下业务方 tenant_id (OpenID, 用于 S2S JWT claims + X-Access-Key)
     */
    private String remoteTenantId;

    /**
     * remote 模式下业务方 tenant_secret (HMAC-SHA256 签名密钥, 从独立部署 notification4j 平台获取)
     */
    private String remoteTenantSecret;

    /**
     * HTTP 连接超时 ms（默认新建 RestTemplate 时生效；业务方已提供唯一 RestTemplate Bean 时不改写）
     */
    private int connectTimeoutMs = 5000;

    /**
     * HTTP 读超时 ms（默认新建 RestTemplate 时生效；业务方已提供唯一 RestTemplate Bean 时不改写）
     */
    private int readTimeoutMs = 10000;
}
