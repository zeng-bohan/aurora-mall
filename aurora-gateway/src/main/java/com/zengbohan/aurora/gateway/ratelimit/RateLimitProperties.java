package com.zengbohan.aurora.gateway.ratelimit;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.cloud.context.config.annotation.RefreshScope;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 网关限流配置（nacos 下发即时生效）。
 * <pre>
 * aurora:
 *   rate-limit:
 *     enabled: true
 *     routes:
 *       order:            # 路由 id（application.yml routes 的 id）
 *         limit: 100
 *         window-seconds: 10
 * </pre>
 * 没有规则的路由不限流；enabled=false 全局关闭。多实例共享配额（Redis ZSET）。
 * <p>
 * {@code @RefreshScope}：刷新时整 bean 销毁重建——Map 属性的 rebind 是合并语义，
 * {@code routes: {}} 清不掉已有键，销毁重建才能真正移除规则；重建路径重跑
 * {@link Rule#validate}，坏规则在装配期暴露而非运行期静默失效。
 */
@RefreshScope
@ConfigurationProperties(prefix = "aurora.rate-limit")
public class RateLimitProperties {

    // 全局开关。
    private boolean enabled = true;

    // 路由 id → 限流规则；没有规则的路径直接放行。
    private Map<String, Rule> routes = new LinkedHashMap<>();

    // 装配期校验全部规则（过滤器构造时调用，@RefreshScope 重建即重跑）。
    public void validateAll() {
        routes.forEach((route, rule) -> rule.validate(route));
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Map<String, Rule> getRoutes() {
        return routes;
    }

    public void setRoutes(Map<String, Rule> routes) {
        this.routes = routes;
    }

    public static class Rule {

        // 窗口内允许的最大请求数。
        private int limit;
        // 滑动窗口时长（秒）。
        private int windowSeconds = 1;

        public int getLimit() {
            return limit;
        }

        public void setLimit(int limit) {
            this.limit = limit;
        }

        public int getWindowSeconds() {
            return windowSeconds;
        }

        public void setWindowSeconds(int windowSeconds) {
            this.windowSeconds = windowSeconds;
        }

        // fail-fast：坏规则（非正配额/窗口）在装配期暴露，而不是运行期静默失效。
        public void validate(String route) {
            if (limit <= 0) {
                throw new IllegalStateException("rate-limit rule for route '" + route + "' needs a positive limit");
            }
            if (windowSeconds <= 0) {
                throw new IllegalStateException("rate-limit rule for route '" + route + "' needs a positive window-seconds");
            }
        }
    }
}
