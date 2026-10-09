package com.zengbohan.aurora.common.web;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标记需要 ADMIN 角色的端点（X-User-Role 由网关注入，经 {@link AdminRoleInterceptor} 校验）。
 * <p>
 * 放在 common 供各服务复用：服务端只该有**一处**角色闸门，各模块各复制一份迟早漂移
 * （原先 product 自持一份，seckill 接入时收敛到这里）。
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface RequireAdmin {
}
