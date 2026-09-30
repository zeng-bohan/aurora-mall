package com.zengbohan.aurora.rpc.loadbalance;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 负载均衡策略选择：{@code aurora.rpc.load-balance.strategy: round-robin|random}。
 */
@ConfigurationProperties(prefix = "aurora.rpc.load-balance")
public class LoadBalancerProperties {

    private String strategy = "round-robin";

    public String getStrategy() {
        return strategy;
    }

    public void setStrategy(String strategy) {
        this.strategy = strategy;
    }
}
