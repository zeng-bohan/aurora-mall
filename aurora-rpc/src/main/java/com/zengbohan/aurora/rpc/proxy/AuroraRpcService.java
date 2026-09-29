package com.zengbohan.aurora.rpc.proxy;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标记一个实现类作为 RPC 服务导出：{@code value} 指定对外的服务接口，
 * 接口全名即服务名（注册中心里的 service 名）。
 * <pre>
 * &#64;AuroraRpcService(ProductApi.class)
 * public class ProductRpcService implements ProductApi { ... }
 * </pre>
 * 纯 Java 注解（无 Spring 依赖）；Spring 侧的扫描装配在消费端胶水里做。
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface AuroraRpcService {

    /** 对外暴露的服务接口。 */
    Class<?> value();
}
