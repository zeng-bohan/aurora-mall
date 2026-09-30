package com.zengbohan.aurora.rpc.spring;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * RPC 装配属性（aurora-rpc 的 Spring 胶水）：
 * <pre>
 * aurora:
 *   rpc:
 *     enabled: true              # 总开关（消费端代理/提供端导出都挂在它上）
 *     registry-addr: localhost:8848
 *     timeout-millis: 3000       # 单次调用等待
 *     host: 127.0.0.1            # 提供端上报地址（消费端忽略）
 *     port: 9181                 # 提供端 RPC 监听端口（消费端忽略）
 * </pre>
 */
@ConfigurationProperties(prefix = "aurora.rpc")
public class AuroraRpcProperties {

    private boolean enabled;
    private String registryAddr = "localhost:8848";
    private long timeoutMillis = 3000;
    private String host = "127.0.0.1";
    private int port = 9181;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getRegistryAddr() {
        return registryAddr;
    }

    public void setRegistryAddr(String registryAddr) {
        this.registryAddr = registryAddr;
    }

    public long getTimeoutMillis() {
        return timeoutMillis;
    }

    public void setTimeoutMillis(long timeoutMillis) {
        this.timeoutMillis = timeoutMillis;
    }

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }
}
