package com.zengbohan.aurora.inventory.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UrlPathHelper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 库存写路径的用户身份守卫（外部审查一.1 的服务侧纵深）：/stocks/** 只供
 * order 服务经 Feign 调用（带内部密钥、不带用户身份）。网关会为转发流量注入
 * X-User-Id——凡携带它的请求一律 403，任何登录用户都无法再借网关改库存。
 *
 * <p>路径判定必须用 Spring 自己的解析结果（{@link UrlPathHelper}），不能用
 * {@code request.getRequestURI()}：后者是容器给的原始串，保留矩阵参数（{@code ;}），
 * 而 Spring MVC 匹配时会剥掉它。两者不一致就是一个绕过面——{@code PUT
 * /api/inventory/stocks;x=1/76} 在守卫看来不以 {@code /stocks/} 开头，于是放行；
 * 下游 MVC 剥掉 {@code ;x=1} 后正常匹配到 {@code /stocks/{skuId}}，普通登录用户
 * 借此可改任意 SKU 的库存。改用 {@code UrlPathHelper} 后，守卫看到的路径与 MVC
 * 最终匹配的路径一致，这类"视图不一致"的绕过不再成立。
 * （网关侧的 InternalPathGuardFilter 是同一个坑，那里是手工归一化修的。）
 *
 * <p>判定取"默认拒绝"：归一化后的路径落在 /stocks 之下就检查身份，其余放行。
 */
@Component
@ConditionalOnProperty(name = "aurora.internal.secret")
public class StockUserIdentityGuardFilter extends OncePerRequestFilter {

    private static final byte[] REJECTION =
            "{\"code\":40300,\"message\":\"无权访问\",\"data\":null}".getBytes(StandardCharsets.UTF_8);

    /**
     * 与 MVC 同源的路径解析：默认 {@code removeSemicolonContent = true}，
     * 即会剥掉 {@code ;} 矩阵参数——正是要与下游对齐的那一步。
     */
    private static final UrlPathHelper PATH_HELPER = new UrlPathHelper();

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        if (request.getHeader("X-User-Id") != null) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType("application/json;charset=UTF-8");
            response.getOutputStream().write(REJECTION);
            return;
        }
        filterChain.doFilter(request, response);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !underStocks(request);
    }

    /** 归一化后的路径是否落在 /stocks 之下。 */
    private static boolean underStocks(HttpServletRequest request) {
        try {
            String path = PATH_HELPER.getPathWithinApplication(request);
            // /admin/stocks/** 不在此列：那条路径由 @RequireAdmin 收口
            return path != null && (path.equals("/stocks") || path.startsWith("/stocks/"));
        } catch (RuntimeException e) {
            // 解析不出来就按"受保护"处理：这里 fail-closed。放行一个可疑请求
            // 比误拦一个正常请求危险得多，400/404 的代价远小于被改库存。
            return true;
        }
    }
}
