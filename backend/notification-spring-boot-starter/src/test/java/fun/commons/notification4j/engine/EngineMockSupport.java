package fun.commons.notification4j.engine;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfo;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;

import java.lang.reflect.Field;

/**
 * 第 26 步 engine 层单测脚手架（测试侧，main/ 零改动；与 service 包 ServiceMockSupport 同型收敛）：
 * 1. initTableInfo：引擎回写全走 LambdaUpdateWrapper#set/setSql（【调用期】急解析列名，
 *    列缓存来自 TableInfoHelper）——纯 mock mapper 下必须预初始化实体 TableInfo，
 *    否则「can not find lambda cache for this entity」；一次性初始化后全局缓存复用。
 * 2. setRunning：DeliveryEngine#running 为 private volatile，scanOnce/reapOnce 守卫读它；
 *    单测不起调度线程（start() 依赖 conf.enabled + 真调度器），反射置位后直调调度入口。
 */
final class EngineMockSupport {

    private EngineMockSupport() {
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

    /** 反射置/清 SmartLifecycle#running 守卫位（不起真调度线程即直调 scanOnce/reapOnce） */
    static void setRunning(DeliveryEngine engine, boolean running) {
        try {
            Field f = DeliveryEngine.class.getDeclaredField("running");
            f.setAccessible(true);
            f.setBoolean(engine, running);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("running 置位失败", e);
        }
    }
}
