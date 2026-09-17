package fun.commons.notification4j.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfo;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.apache.ibatis.builder.MapperBuilderAssistant;

import java.lang.reflect.Field;

/**
 * 第 25 步 service 层 mock 单测公共脚手架（测试侧，main/ 零改动）。
 * 1. injectBaseMapper：反射注入 ServiceImpl#baseMapper（第 24 步 serviceWithMapper 模式收敛复用）；
 * 2. initTableInfo：MyBatis-Plus LambdaUpdateWrapper#set 列名解析是【调用期】急解析（列缓存来自
 *    TableInfoHelper），cancel/markRead/confirm/changeStatus 等 set 路径须预初始化实体 TableInfo，
 *    否则「can not find lambda cache for this entity」——一次性初始化后全局缓存复用。
 * 注意：LambdaQueryWrapper 的 eq/orderBy 条件段是 SQL 生成期才解析列名 → 纯 mock mapper 下无需初始化；
 * 本 helper 只为 set/setSql(LambdaUpdateWrapper) 与显式 TableInfo 路径服务。
 */
// VECTOR: TAG=step25-unit
final class ServiceMockSupport {

    private ServiceMockSupport() {
    }

    /** 反射注入 ServiceImpl#baseMapper 字段（第 24 步验证过的模式） */
    static <T extends ServiceImpl<?, ?>> T injectBaseMapper(T service, Object mapper) {
        try {
            Field f = ServiceImpl.class.getDeclaredField("baseMapper");
            f.setAccessible(true);
            f.set(service, mapper);
            return service;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("baseMapper 注入失败", e);
        }
    }

    /** 预初始化实体 TableInfo（LambdaUpdateWrapper#set 急解析依赖；重复调用幂等，MP 自带缓存） */
    static void initTableInfo(Class<?>... entityClasses) {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        for (Class<?> entity : entityClasses) {
            TableInfo info = TableInfoHelper.initTableInfo(assistant, entity);
            if (info == null) {
                throw new IllegalStateException("TableInfo 初始化失败: " + entity.getName());
            }
        }
    }

    /**
     * 预置 ServiceImpl 的 mapperClass/entityClass 私有缓存（3.5.7）：lambdaQuery() 链式入口会走
     * getMapperClass() → MybatisUtils.getMybatisMapperProxy(baseMapper)，纯 mock mapper 非
     * MybatisMapperProxy 会抛「Unable to get MybatisMapperProxy」——预置缓存字段后短路，不再触代理。
     */
    static void primeServiceImplMetadata(ServiceImpl<?, ?> service, Class<?> mapperClass, Class<?> entityClass) {
        setField(service, "mapperClass", mapperClass);
        setField(service, "entityClass", entityClass);
    }

    private static void setField(Object target, String name, Object value) {
        try {
            java.lang.reflect.Field f = ServiceImpl.class.getDeclaredField(name);
            f.setAccessible(true);
            f.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("字段预置失败: " + name, e);
        }
    }
}
