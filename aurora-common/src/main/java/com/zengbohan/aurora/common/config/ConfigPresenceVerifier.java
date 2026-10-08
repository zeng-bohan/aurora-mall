package com.zengbohan.aurora.common.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;

/**
 * 配置中心托管键的快速失败守卫：列出 aurora.config.required-keys 中
 * 环境无法解析的每一个键，并给出
 * 指向导入脚本的提示。各服务声明自己需要的键；
 * 空清单（common 自身的测试）为空操作。
 */
@Component
public class ConfigPresenceVerifier implements InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(ConfigPresenceVerifier.class);

    private final Environment environment;
    private final String requiredKeys;

    public ConfigPresenceVerifier(Environment environment,
                                  @Value("${aurora.config.required-keys:}") String requiredKeys) {
        this.environment = environment;
        this.requiredKeys = requiredKeys;
    }

    @Override
    public void afterPropertiesSet() {
        if (requiredKeys == null || requiredKeys.isBlank()) {
            return;
        }
        List<String> missing = Arrays.stream(requiredKeys.split(","))
                .map(String::trim)
                .filter(key -> !key.isEmpty() && environment.getProperty(key) == null)
                .toList();
        if (!missing.isEmpty()) {
            throw new IllegalStateException(
                    "Missing required configuration keys: " + missing
                            + " — the nacos config center is unreachable, the dev namespace"
                            + " was not imported, or a key was deleted there."
                            + " Run docker/nacos/import.sh and verify nacos is up"
                            + " (startup must fail fast when config is unavailable).");
        }
        log.info("required config keys present: {}", requiredKeys);
    }
}
