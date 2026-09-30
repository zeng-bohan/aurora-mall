package com.zengbohan.aurora.product.web;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** 标记需要 ADMIN 角色的端点（X-User-Role 由网关注入，经 AdminRoleInterceptor 校验）。 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface RequireAdmin {
}
