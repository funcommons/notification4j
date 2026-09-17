package fun.commons.notification4j.autoconfigure;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 独立部署形态：消息中心前端 SPA 内嵌托管（编码第 29 步「免双部署」——前端产物入 jar，一个进程同时服务
 * 页面与 API，运维只部署 {@code notification4j-app} 一件 fat jar）。
 *
 * <p><b>模块归属（为何在 starter 而非 app）：</b>静态产物与 fallback 转发同置于 starter，因为 IT 层
 * （notification4j-it）依赖 starter——资源与控制器只有进入 starter classpath，托管行为才能被
 * {@code NfyStaticPageTest} 以 MockMvc 全链路验证；app 依赖 starter，开启开关即开箱即得，
 * 部署语义不受影响。</p>
 *
 * <p><b>防嵌入方污染（与 NfyAutoConfiguration 同等的装配纪律）：</b>
 * 双重保险——① 开关 {@code nfy.static-page.enabled} 缺省即关（{@code havingValue="true"} 且
 * {@code matchIfMissing=false}）：嵌入形态不声明该开关时本装配整体缺席，不注册任何 handler/转发；
 * ② 产物落位 {@code classpath:/nfy-console/} 而非 {@code classpath:/static/}：Boot 对 /static、
 * /META-INF/resources 等默认静态位是<b>无条件</b>托管（不归本装配管），产物若放 /static 会以文件形式
 * 泄入嵌入方默认静态面（/index.html、/assets/** 直接可访问）；/nfy-console/ 不在默认静态位列表，
 * 不开开关时对运行时完全不可见（仅增加 jar 体积，见下「体积权衡」）。</p>
 *
 * <p><b>API 面优先（硬约束）：</b>转发控制器只接管 {@code /} 与前端路由空间 {@code /nfy/tenant/**}
 * （路由表：app 壳 {@code /nfy/tenant/app/*} + bell 单页壳 {@code /nfy/tenant/page/bell}，见
 * frontend/src/router/index.ts）。API 前缀（{@code /nfy/api/**}、{@code /nfy/open/**}、
 * {@code /nfy/platform/api/**}）与本控制器映射零交集，且已注册 controller 的 pattern 更具体、
 * 匹配优先级恒高于宽泛 pattern——未匹配的 API 路径照常 404（fwk4j-web NoResource → 10400 信封），
 * 绝不会被静态页吞成 200 HTML。</p>
 *
 * <p><b>资源映射口径：</b>{@code /assets/**} 双 location（先 {@code nfy-console/assets/} 命中 hash
 * 产物，再回退 {@code nfy-console/} 根）——SPA 入口 index.html 与 public 根文件（favicon.svg/logo.svg）
 * 借同前缀以 {@code forward:/assets/<file>} 提供，避开 exact-pattern 资源解析歧义；缓存不配置强
 * Cache-Control，仅靠 Last-Modified 协商（index.html 与 hash 产物共用前缀，给 /assets/** 设不可变
 * 强缓存会把改版后的 SPA 入口钉死在陈旧 hash 上）。</p>
 *
 * <p><b>产物再生成（dist 精确拷贝，node_modules 永不入 jar）：</b>
 * {@code cd frontend && pnpm build}（vite base='/'，部署于 9200 根路径无需调整）→
 * {@code cp -R frontend/dist/ notification-spring-boot-starter/src/main/resources/nfy-console/}
 * （先删旧目录再整拷）→ 重新 install starter 并打包 app。详见面 {@code backend/README.md}「部署」节。
 * 体积注记：dist 全量约 6.9MB（element-plus/three/fabric chunk 为主），starter jar 同幅增重，
 * 换取嵌入方零配置可选开启——嵌入方若在意体积，可自行裁剪 public/ 遗留资产后重打前端。</p>
 *
 * @author notification4j
 */
@AutoConfiguration
@ConditionalOnClass(WebMvcConfigurer.class)
@ConditionalOnProperty(name = "nfy.static-page.enabled", havingValue = "true")
public class NfyStaticPageConfig implements WebMvcConfigurer {

    /** 产物在 starter jar 内的 classpath 根（刻意避开 Boot 默认静态位 classpath:/static/，见类注） */
    public static final String CONSOLE_LOCATION = "classpath:/nfy-console/";

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // /assets/**：先命中 hash 产物（nfy-console/assets/），未命中回退产物根（index.html/favicon.svg/logo.svg）
        registry.addResourceHandler("/assets/**")
                .addResourceLocations(CONSOLE_LOCATION + "assets/", CONSOLE_LOCATION);
        // public/ 遗留目录（benefit4j 裁剪残留，nfy 页面当前不引用，保留可服务性随 dist 同步）
        registry.addResourceHandler("/images/**")
                .addResourceLocations(CONSOLE_LOCATION + "images/");
        registry.addResourceHandler("/oem/**")
                .addResourceLocations(CONSOLE_LOCATION + "oem/");
    }

    @Bean
    @ConditionalOnMissingBean
    public NfyStaticPageController nfyStaticPageController() {
        return new NfyStaticPageController();
    }

    /**
     * SPA history 路由 fallback：vue-router {@code createWebHistory} 的深链（如
     * {@code /nfy/tenant/app/messages}）刷新/直开时服务端并无该文件，forward 到 SPA 入口
     * index.html 由前端路由接管。仅两个入口映射，宽泛度收敛到前端路由表实际占用空间（见类注）。
     */
    @Controller
    public static class NfyStaticPageController {

        @GetMapping({"/", "/nfy/tenant/**"})
        public String spaEntry() {
            return "forward:/assets/index.html";
        }

        /** index.html {@code <link rel="icon" href="/favicon.svg">}（vite public 根文件，非 hash 产物） */
        @GetMapping("/favicon.svg")
        public String favicon() {
            return "forward:/assets/favicon.svg";
        }

        /** 前端 bundle 内引用的 {@code /logo.svg}（vite public 根文件） */
        @GetMapping("/logo.svg")
        public String logo() {
            return "forward:/assets/logo.svg";
        }
    }
}
