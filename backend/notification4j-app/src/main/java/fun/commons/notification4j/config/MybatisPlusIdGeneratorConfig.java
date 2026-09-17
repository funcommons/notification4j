package fun.commons.notification4j.config;

import com.baomidou.mybatisplus.core.incrementer.DefaultIdentifierGenerator;
import com.baomidou.mybatisplus.core.incrementer.IdentifierGenerator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 显式注册 MyBatis Plus 的 snowflake ID 生成器。
 *
 * MyBatis Plus 自带的 {@code IdentifierGeneratorAutoConfiguration} 仅在 Spring Cloud Commons
 * (InetUtils) 在 classpath 时才生效; notification4j-app 不引入 spring-cloud-commons, 因此默认
 * 条件下不会注册。{@code IdType.ASSIGN_ID} 没有生成器时, insert 会以 id=null 提交,
 * 触发 NOT NULL 约束 (10106)。
 */
@Configuration
public class MybatisPlusIdGeneratorConfig {

    @Bean
    @ConditionalOnMissingBean(IdentifierGenerator.class)
    public IdentifierGenerator identifierGenerator() {
        return new DefaultIdentifierGenerator();
    }
}
