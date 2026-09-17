package fun.commons.notification4j.it;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 第 1 步冒烟：评审定稿 DDL（V1.0.0__init_nfy_schema.sql）在真 PG 上可执行，
 * 幂等唯一闸（部分唯一索引）与部分索引就位。
 */
@Testcontainers
@Tag("step1")
class NfySchemaMigrationTest {

    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16");

    static Connection conn;

    @BeforeAll
    static void init() throws Exception {
        Flyway.configure()
                .dataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();
        conn = DriverManager.getConnection(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
    }

    private static List<String> tables() throws Exception {
        ResultSet rs = conn.getMetaData().getTables(null, "public", null, new String[]{"TABLE"});
        List<String> names = new ArrayList<>();
        while (rs.next()) {
            names.add(rs.getString("TABLE_NAME"));
        }
        return names;
    }

    @Test
    void all_business_tables_created() throws Exception {
        assertThat(tables()).contains(
                "nfya_tenant", "nfya_message_type", "nfya_message", "nfya_message_recipient",
                "nfya_announcement", "nfya_announcement_read", "nfya_channel", "nfya_subscription",
                "nfya_template", "nfya_delivery", "nfyp_registration_key", "nfyp_audit_log");
        assertThat(tables()).noneMatch(n -> n.startsWith("ubma") || n.startsWith("ubmp"));
    }

    @Test
    void tenant_contract_columns_match_framework_tenant_entity() throws Exception {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                 "SELECT column_name, is_nullable, data_type FROM information_schema.columns " +
                 "WHERE table_name='nfya_tenant'")) {
            List<String> cols = new ArrayList<>();
            while (rs.next()) cols.add(rs.getString(1));
            // framework4j-tenant TenantEntity 契约列（缺列 = MP 查询即炸, 评审第 1 轮 P0）
            assertThat(cols).contains("description", "tenant_secret_prev", "tenant_secret_prev_at",
                    "privileges", "config", "oem");
        }
    }

    @Test
    void channel_target_md5_unique_blocks_duplicate_webhook() throws Exception {
        String sql = "INSERT INTO nfya_channel (id, tenant_id, userid, channel_type, name, target) " +
                "VALUES (?,?,?,'DINGTALK','n',?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, 200); ps.setLong(2, 9L); ps.setString(3, "u1");
            ps.setString(4, "https://oapi.dingtalk.com/robot/send?access_token=abc");
            ps.executeUpdate();
            ps.setLong(1, 201); ps.setLong(2, 9L); ps.setString(3, "u1");
            ps.setString(4, "https://oapi.dingtalk.com/robot/send?access_token=abc");
            // 同 target 重复注册必须被 md5 表达式唯一闸拒绝（评审定稿 DDL 语义）
            assertThatThrownBy(ps::executeUpdate).hasMessageContaining("uk_nfya_channel_target");
        }
    }

    @Test
    void tenant_email_partial_unique_index_blocks_duplicate_active_email() throws Exception {
        try (Statement st = conn.createStatement()) {
            st.execute("INSERT INTO nfya_tenant (id, name, email, tenant_secret) VALUES (1, 'a', 'a@x.com', 's')");
            st.execute("INSERT INTO nfya_tenant (id, name, email, tenant_secret) VALUES (2, 'b', 'b@x.com', 's')");
        }
        // 同 email 且未删除 → 违反部分唯一索引
        assertThatThrownBy(() -> {
            try (Statement st = conn.createStatement()) {
                st.execute("INSERT INTO nfya_tenant (id, name, email, tenant_secret) VALUES (3, 'c', 'a@x.com', 's')");
            }
        }).hasMessageContaining("uk_nfya_tenant_email");
        // 逻辑删除后同 email 可复用（部分索引 WHERE is_deleted=0）
        try (Statement st = conn.createStatement()) {
            st.execute("UPDATE nfya_tenant SET is_deleted = 1 WHERE id = 1");
            st.execute("INSERT INTO nfya_tenant (id, name, email, tenant_secret) VALUES (4, 'd', 'a@x.com', 's')");
        }
    }

    @Test
    void message_biz_no_is_idempotency_gate() throws Exception {
        String sql = "INSERT INTO nfya_message (id, tenant_id, biz_no, type_code, title, content) VALUES (?,?,?,'ORDER','t','c')";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, 100); ps.setLong(2, 9L); ps.setString(3, "biz-1"); ps.executeUpdate();
            ps.setLong(1, 101); ps.setLong(2, 9L); ps.setString(3, "biz-1");
            // 同租户同 biz_no 第二次插入必须被唯一闸拒绝
            assertThatThrownBy(ps::executeUpdate).hasMessageContaining("uk_nfya_message_tenant_biz_no");
            ps.setLong(1, 102); ps.setLong(2, 8L); ps.setString(3, "biz-1");
            // 不同租户允许同 biz_no（幂等命名空间 = tenant_id）
            ps.executeUpdate();
        }
    }

    @Test
    void partial_indexes_exist() throws Exception {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                 "SELECT indexname FROM pg_indexes WHERE tablename IN ('nfya_message_recipient','nfya_delivery')")) {
            List<String> idx = new ArrayList<>();
            while (rs.next()) idx.add(rs.getString(1));
            assertThat(idx).contains(
                    "idx_nfya_recipient_unread",
                    "idx_nfya_delivery_pending",
                    "idx_nfya_delivery_sending",
                    "uk_nfya_delivery_source");
        }
    }
}
