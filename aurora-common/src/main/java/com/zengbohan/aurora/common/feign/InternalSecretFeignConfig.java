package com.zengbohan.aurora.common.feign;

import feign.RequestInterceptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 服务间 Feign 调用的内部密钥注入（ADR-0006）：收敛自 cart/order/payment
 * 三份重复的 FeignConfig（M2 评审标记，M3 T8 收敛）。
 * <p>
 * 双重守卫：{@code @ConditionalOnClass} 挡住不带 openfeign 的 gateway；
 * {@code @ConditionalOnProperty} 让密钥未配置的上下文（如 common 自身测试）
 * 不装配——密钥属性在真实服务里由 nacos 强制下发。
 */
@Configuration
@ConditionalOnClass(RequestInterceptor.class)
@ConditionalOnProperty("aurora.internal.secret")
public class InternalSecretFeignConfig {

    /** 出站服务间调用携带内部密钥，服务侧 InternalSecretFilter 校验。 */
    @Bean
    public RequestInterceptor internalSecretInterceptor(
            @Value("${aurora.internal.secret}") String internalSecret) {
        return template -> template.header("X-Internal-Secret", internalSecret);
    }
}
