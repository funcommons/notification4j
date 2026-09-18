package fun.commons.notification4j.autoconfigure;

import org.springframework.beans.factory.Aware;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.env.Environment;
import org.springframework.boot.autoconfigure.AutoConfigurationImportFilter;
import org.springframework.boot.autoconfigure.AutoConfigurationMetadata;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * nfy starter 数据面接管过滤器（V1.3，GitHub issue #3；先例 lotask4j AstsServerAutoConfigurationExcludeFilter）。
 *
 * <p>问题：framework4j-datasource 的 MultiDataSourceRegistrar 在自动配置装载趟里排在
 * 原生装配 <em>之后</em>（字典序 fun.* &gt; com.alibaba.* / com.baomidou.*），导致：
 * druid 原生 {@code DruidDataSourceAutoConfigure}（matchIfMissing=true）先注册
 * DruidDataSourceWrapper，其 afterPropertiesSet 向 {@code spring.datasource.*} 索要 url ——
 * 接入方只配 {@code framework4j.datasource.datasources.*} 时启动即报
 * 「Failed to configure a DataSource: 'url' attribute is not specified」；补了
 * spring.datasource.* 又得到第二个连接池（同库双池）。MP 原生装配同理抢注第二套
 * SqlSessionFactory。framework4j v1.7.1 上游仍未修（datasource 模块 v1.5.1→v1.7.1 零变更），
 * 官方 FAQ 的口径是让接入方手工 {@code spring.autoconfigure.exclude} —— 本过滤器把这一步收进 starter。
 *
 * <p>语义与闸门：
 * <ul>
 *   <li>filter 是 import 选择期 <em>skip</em> 而非 exclude：被过滤类不在 classpath 时安全空转；</li>
 *   <li>数据面四项仅当 {@code framework4j.datasource.enabled=true}（framework4j 多数据源
 *       真正接管时）才 veto —— 数据面关掉的接入方（自管 spring.datasource + MP 原生装配）
 *       完全不受影响；</li>
 *   <li>redisson 两项仅当 {@code framework4j.redis.enabled=true} 才 veto（D-4：本产品全链
 *       零 redisson，且其客户端端点不随 host/port 覆盖、恒连默认 6379；接管 app 壳原有的
 *       spring.autoconfigure.exclude 手工项）；</li>
 *   <li>总闸 {@code nfy.enabled=false} 全部放行，宿主恢复原生装配（逃生门）。</li>
 * </ul>
 *
 * <p>filter 由 SpringFactoriesLoader 实例化（非 Spring bean），selector 只回调
 * {@link Aware} 家族的 Environment/BeanFactory/ResourceLoader 三种 —— 这里用
 * {@link EnvironmentAware}（ApplicationContextAware 不会被调）。注册于
 * {@code META-INF/spring.factories} 的 AutoConfigurationImportFilter 键。
 *
 * @author notification4j-team
 * @version 1.3.0
 */
public class NfyDataPlaneTakeoverFilter implements AutoConfigurationImportFilter, EnvironmentAware {

    /** 总闸：nfy.enabled=false 时全部放行（宿主自管数据面/缓存面） */
    static final String ENABLED_KEY = "nfy.enabled";

    /** framework4j 多数据源接管前提（matchIfMissing=false，缺省不接管也不 veto） */
    static final String DATA_PLANE_KEY = "framework4j.datasource.enabled";

    /** framework4j redis 接管前提 */
    static final String REDIS_KEY = "framework4j.redis.enabled";

    /** 数据面四项（HashSet：候选数组可能含 null，Set.of 会 NPE） */
    private static final Set<String> DATA_PLANE_VETOED = new HashSet<>(List.of(
            "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration",
            "com.alibaba.druid.spring.boot3.autoconfigure.DruidDataSourceAutoConfigure",
            "com.baomidou.mybatisplus.autoconfigure.MybatisPlusAutoConfiguration",
            "org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration"));

    /** redisson 原生装配（framework4j-redis 传递引入，产品零使用） */
    private static final Set<String> REDISSON_VETOED = new HashSet<>(List.of(
            "org.redisson.spring.starter.RedissonAutoConfigurationV2",
            "org.redisson.spring.starter.RedissonAutoConfigurationV4"));

    private Environment environment;

    @Override
    public void setEnvironment(Environment environment) {
        this.environment = environment;
    }

    @Override
    public boolean[] match(String[] classNames, AutoConfigurationMetadata metadata) {
        boolean[] match = new boolean[classNames.length];
        boolean enabled = environment.getProperty(ENABLED_KEY, Boolean.class, true);
        if (!enabled) {
            java.util.Arrays.fill(match, true); // 全放行
            return match;
        }
        boolean dataPlane = environment.getProperty(DATA_PLANE_KEY, Boolean.class, false);
        boolean redis = environment.getProperty(REDIS_KEY, Boolean.class, false);
        for (int i = 0; i < classNames.length; i++) {
            String candidate = classNames[i];
            boolean vetoed = candidate != null
                    && ((dataPlane && DATA_PLANE_VETOED.contains(candidate))
                        || (redis && REDISSON_VETOED.contains(candidate)));
            match[i] = !vetoed;
        }
        return match;
    }
}
