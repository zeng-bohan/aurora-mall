package com.zengbohan.aurora.seckill.ratelimit;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.cloud.context.config.annotation.RefreshScope;

/**
 * 活动维度限流配置（nacos 下发即时生效）。
 * <pre>
 * aurora:
 *   seckill:
 *     rate-limit:
 *       enabled: true
 *       per-activity-limit: 200
 *       window-seconds: 1
 * </pre>
 * 与网关的路由级限流互补：网关按路由 id 计数（保护整条 /api/seckill/**），
 * 这里按活动计数（保护单个活动不把服务配额吃光）。大促前调阈值即可，
 * 秒杀入口不重启。
 * <p>
 * {@code @RefreshScope}：刷新时本 bean 销毁重建，属性随之整体替换——
 * 避免只改其中一个字段时旧值残留。
 */
@RefreshScope
@ConfigurationProperties(prefix = "aurora.seckill.rate-limit")
public class SeckillRateLimitProperties {

    // 全局开关；关掉后秒杀请求不做活动维度限流（网关路由级限流仍生效）。
    private boolean enabled = true;

    // 单个活动在窗口内允许的最大抢购请求数。
    private int perActivityLimit = 200;

    // 滑动窗口时长（秒）。
    private int windowSeconds = 1;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getPerActivityLimit() {
        return perActivityLimit;
    }

    public void setPerActivityLimit(int perActivityLimit) {
        this.perActivityLimit = perActivityLimit;
    }

    public int getWindowSeconds() {
        return windowSeconds;
    }

    public void setWindowSeconds(int windowSeconds) {
        this.windowSeconds = windowSeconds;
    }
}
