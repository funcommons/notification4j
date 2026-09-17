package fun.commons.notification4j.util;

import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedTypes;
import org.junit.jupiter.api.Test;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Types;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * 第 26 步 util 层单测：JsonbTypeHandler（PG jsonb ↔ String 直通）。
 * 写入走 setObject(i, value, Types.OTHER)（驱动以 unspecified 类型发送，jsonb 列直接接受，
 * 与 stringtype=unspecified 连接参数解耦）；读取三形态（列名/列下标/存储过程）；
 * null 参数交 BaseTypeHandler 走 setNull；jsonb NULL 列读回 Java null（直通不吞）。
 */
// VECTOR: TAG=step26-unit
class JsonbTypeHandlerTest {

    private final JsonbTypeHandler handler = new JsonbTypeHandler();

    @Test
    void setNonNullParameter_writes_object_with_types_other() throws Exception {
        PreparedStatement ps = mock(PreparedStatement.class);

        handler.setNonNullParameter(ps, 1, "{\"k\":1}", JdbcType.OTHER);

        verify(ps).setObject(1, "{\"k\":1}", Types.OTHER); // unspecified 类型 → jsonb 列直收
        verifyNoMoreInteractions(ps);
    }

    @Test
    void setParameter_with_null_delegates_to_set_null_via_base_class() throws Exception {
        PreparedStatement ps = mock(PreparedStatement.class);

        handler.setParameter(ps, 2, null, JdbcType.OTHER); // BaseTypeHandler：null → setNull(TYPE_CODE)

        verify(ps).setNull(2, JdbcType.OTHER.TYPE_CODE);
    }

    @Test
    void getNullableResult_reads_by_column_name() throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getString("ext")).thenReturn("{\"a\":true}");

        assertThat(handler.getNullableResult(rs, "ext")).isEqualTo("{\"a\":true}");
    }

    @Test
    void getNullableResult_reads_by_column_index() throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getString(3)).thenReturn("[]");

        assertThat(handler.getNullableResult(rs, 3)).isEqualTo("[]");
    }

    @Test
    void getNullableResult_reads_from_callable_statement() throws Exception {
        CallableStatement cs = mock(CallableStatement.class);
        when(cs.getString(2)).thenReturn("{}");

        assertThat(handler.getNullableResult(cs, 2)).isEqualTo("{}");
    }

    @Test
    void jsonb_null_column_reads_back_as_java_null_through_public_result_api() throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getString(anyString())).thenReturn(null);
        when(rs.getString(anyInt())).thenReturn(null);

        assertThat(handler.getResult(rs, "ext")).isNull(); // DB NULL → Java null（getResult 直通）
        assertThat(handler.getResult(rs, 1)).isNull();
    }

    @Test
    void mapped_types_declares_string_binding() {
        assertThat(handler.getClass().getAnnotation(MappedTypes.class).value())
                .containsExactly(String.class);
    }
}
