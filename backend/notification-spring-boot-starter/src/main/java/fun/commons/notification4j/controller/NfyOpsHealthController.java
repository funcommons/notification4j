package fun.commons.notification4j.controller;

import fun.commons.framework4j.web.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 运维健康检查（/nfy/api/v1/ops/health；application.yml exclude-path-patterns 放行无鉴权）。
 * 无域注解（DomainGuard 放行）+ 无 @RequiresToken（TokenInterceptor 跳过）；
 * db=SELECT 1，redis=PING（连接工厂缺失时 DOWN）；任一 DOWN → status=DOWN。
 */
@RestController
@RequestMapping("/nfy/api/v1/ops")
@RequiredArgsConstructor
public class NfyOpsHealthController {

    private final DataSourceHealth dataSourceHealth;
    private final ObjectProvider<RedisConnectionFactory> redisFactory;

    @GetMapping("/health")
    public ApiResponse<Map<String, Object>> health() {
        boolean dbUp = dataSourceHealth.isUp();
        boolean redisUp = false;
        try {
            RedisConnectionFactory f = redisFactory.getIfAvailable();
            redisUp = f != null && "PONG".equalsIgnoreCase(f.getConnection().ping());
        } catch (Exception e) {
            redisUp = false;
        }
        boolean up = dbUp && redisUp;
        Map<String, Object> checks = new LinkedHashMap<>();
        checks.put("db", Map.of("status", dbUp ? "UP" : "DOWN"));
        checks.put("redis", Map.of("status", redisUp ? "UP" : "DOWN"));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("status", up ? "UP" : "DOWN");
        data.put("checks", checks);
        return ApiResponse.success(data);
    }

    /** DB 探针（函数化便于单测；SELECT 1） */
    @RequiredArgsConstructor
    public static class DataSourceHealth {
        private final org.apache.ibatis.session.SqlSessionFactory sqlSessionFactory;

        boolean isUp() {
            try (var session = sqlSessionFactory.openSession()) {
                return session.getConnection().prepareStatement("SELECT 1").execute();
            } catch (Exception e) {
                return false;
            }
        }
    }
}
