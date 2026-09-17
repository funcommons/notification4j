package fun.commons.notification4j;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/**
 * notification4j 独立部署壳（最薄封装）：仅装配，零业务。
 *
 * <p>{@code @MapperScan} 按 starter 契约注册 mapper（接入方约定，见 NfyAutoConfiguration 类注）——
 * 第 29 步独立启动实测：缺失时数据面 mapper 全缺席，启动即失败
 * （「Consider defining a bean of type NfyaAnnouncementMapper」），故壳层显式声明。</p>
 *
 * <p>{@code scanBasePackages} 收窄到本壳 config 包——第 29 步独立启动实测：默认扫描基包
 * {@code fun.commons.notification4j.**} 会把 starter 里带 {@code @Service} 的服务类一并组件扫描注册，
 * 绕过 NfyAutoConfiguration 的 {@code @ConditionalOnMissingBean}/数据面双闸装配链
 * （ChannelCoreService 找不到 WebhookTargetValidator 即启动失败）。starter 的 Bean 一律经
 * 自动配置（AutoConfiguration.imports + framework4j 各自动配置）进入，扫描只留给壳自身配置。</p>
 */
@SpringBootApplication(scanBasePackages = "fun.commons.notification4j.config")
@MapperScan("fun.commons.notification4j.mapper")
@EnableTransactionManagement
public class NfyApplication {

    public static void main(String[] args) {
        SpringApplication.run(NfyApplication.class, args);
    }
}
