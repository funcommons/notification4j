package fun.commons.notification4j.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import fun.commons.framework4j.accesstoken.context.TokenContext;
import fun.commons.framework4j.tenant.context.UserIdContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.test.web.servlet.setup.StandaloneMockMvcBuilder;

import java.util.Map;

/**
 * 第 26 步 controller 层切片单测脚手架（MockMvc standaloneSetup，不起全上下文、不连 DB）。
 * 控制器依赖 TokenContext/UserIdContext（ThreadLocal）——standalone 无 framework 拦截器链，
 * 以测试拦截器在 preHandle 注入（与运行时拦截器同型：请求线程内设置、afterCompletion 清理）；
 * X-User-Id 复用框架 UserIdContextInterceptor（public 内部类，语义与生产一致：头→ThreadLocal）。
 */
final class ControllerTestSupport {

    private ControllerTestSupport() {
    }

    /** TENANT token 上下文注入（tenant_id claim=1）+ X-User-Id 解析，请求后清理；JSON 走 SNAKE_CASE（生产口径） */
    static StandaloneMockMvcBuilder applyRuntimeContext(StandaloneMockMvcBuilder builder) {
        return builder
                .setMessageConverters(snakeCaseJsonConverter())
                .addInterceptors(tokenContextInterceptor(), new UserIdContext.UserIdContextInterceptor());
    }

    /** framework4j web 契约：Jackson SNAKE_CASE（type_code ↔ typeCode）——standalone 默认无此策略 */
    static MappingJackson2HttpMessageConverter snakeCaseJsonConverter() {
        ObjectMapper om = new ObjectMapper();
        om.setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        return new MappingJackson2HttpMessageConverter(om);
    }

    private static HandlerInterceptor tokenContextInterceptor() {
        return new HandlerInterceptor() {
            @Override
            public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
                TokenContext.set("access", Map.of("tenant_id", 1L));
                return true;
            }

            @Override
            public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                        Object handler, Exception ex) {
                TokenContext.clear(); // UserIdContext 由框架 UserIdContextInterceptor.afterCompletion 自清
            }
        };
    }

    /** standalone 下启用 @Valid 校验（Boot 运行时由 web 注入，切片需显式挂接） */
    static LocalValidatorFactoryBean validator() {
        LocalValidatorFactoryBean factory = new LocalValidatorFactoryBean();
        factory.afterPropertiesSet();
        return factory;
    }
}
