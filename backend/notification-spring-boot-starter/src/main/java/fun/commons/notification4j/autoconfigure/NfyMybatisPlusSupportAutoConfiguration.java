package fun.commons.notification4j.autoconfigure;

import com.baomidou.mybatisplus.core.incrementer.DefaultIdentifierGenerator;
import com.baomidou.mybatisplus.core.incrementer.IdentifierGenerator;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * MyBatis-Plus 雪花 ID 生成器兜底（V1.3，GitHub issue #2 补实现）。
 * <p>
 * MP 自带的 {@code IdentifierGeneratorAutoConfiguration} 仅在 Spring Cloud Commons
 * （InetUtils）在 classpath 时才生效；本产品依赖树不含 spring-cloud-commons，接入方
 * 不自带 IdentifierGenerator 时 {@code IdType.ASSIGN_ID} 首条 INSERT 即以 id=null
 * 提交触发 NOT NULL 约束（10106），且报错与「忘配 X」观感距离极远。
 * <p>
 * 顺序纪律：必须先于 {@code MybatisPlusAutoConfiguration} 注册（MP 构建
 * SqlSessionFactory 时经 ObjectProvider 解析 IdentifierGenerator，晚了取不到），
 * 因此独立成类并声明 {@code before}，不能放进 after MP 的 {@link NfyAutoConfiguration}。
 * 接入方自有 {@code IdentifierGenerator} bean（用户配置先于自动配置求值）自然让位。
 * 独立部署壳此前在 notification4j-app 自带同款 config，兜底收编 starter 后已删。
 */
@AutoConfiguration(before = com.baomidou.mybatisplus.autoconfigure.MybatisPlusAutoConfiguration.class)
public class NfyMybatisPlusSupportAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(IdentifierGenerator.class)
    public IdentifierGenerator nfyIdentifierGenerator() {
        return new DefaultIdentifierGenerator();
    }
}
