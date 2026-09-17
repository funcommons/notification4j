package fun.commons.notification4j.controller;

import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.test.web.servlet.MockMvc;

import java.sql.Connection;
import java.sql.PreparedStatement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 第 28 步 controller 覆盖缺口补齐：NfyOpsHealthController（/nfy/api/v1/ops/health）。
 * 缺口行为 redis ping 异常兜底（合并报告 L34~35）与 DB 探针 catch（内部类 L55~56）——
 * IT 只覆盖健康通路，异常支路 JVM 内可模拟：redis 连接抛错 / openSession 抛错。
 * 无域注解无 @RequiresToken（standalone 免上下文注入，同生产放行口径）。
 */
// VECTOR: TAG=step28-unit
class NfyOpsHealthControllerTest {

    private final SqlSessionFactory sqlSessionFactory = mock(SqlSessionFactory.class);

    @SuppressWarnings("unchecked")
    private final ObjectProvider<RedisConnectionFactory> redisProvider = mock(ObjectProvider.class);

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders
                .standaloneSetup(new NfyOpsHealthController(
                        new NfyOpsHealthController.DataSourceHealth(sqlSessionFactory), redisProvider))
                .build();
    }

    @Test
    void health_redis_ping_failure_marks_redis_down() throws Exception {
        RedisConnectionFactory broken = mock(RedisConnectionFactory.class);
        when(broken.getConnection()).thenThrow(new IllegalStateException("redis 连接失败"));
        when(redisProvider.getIfAvailable()).thenReturn(broken);

        mvc.perform(get("/nfy/api/v1/ops/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.checks.redis.status").value("DOWN"));
    }

    @Test
    void db_probe_returns_false_when_session_open_fails() {
        when(sqlSessionFactory.openSession()).thenThrow(new IllegalStateException("db 连接失败"));
        assertThat(new NfyOpsHealthController.DataSourceHealth(sqlSessionFactory).isUp()).isFalse();
    }

    @Test
    void health_all_up_when_db_and_redis_healthy() throws Exception {
        SqlSession session = mock(SqlSession.class);
        Connection conn = mock(Connection.class);
        PreparedStatement ps = mock(PreparedStatement.class);
        when(sqlSessionFactory.openSession()).thenReturn(session);
        when(session.getConnection()).thenReturn(conn);
        when(conn.prepareStatement("SELECT 1")).thenReturn(ps);
        when(ps.execute()).thenReturn(true);

        RedisConnectionFactory factory = mock(RedisConnectionFactory.class);
        RedisConnection redis = mock(RedisConnection.class);
        when(factory.getConnection()).thenReturn(redis);
        when(redis.ping()).thenReturn("PONG");
        when(redisProvider.getIfAvailable()).thenReturn(factory);

        mvc.perform(get("/nfy/api/v1/ops/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.status").value("UP"))
                .andExpect(jsonPath("$.data.checks.db.status").value("UP"))
                .andExpect(jsonPath("$.data.checks.redis.status").value("UP"));
    }
}
