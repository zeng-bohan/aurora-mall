package com.zengbohan.aurora.gateway.auth;

import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;

/**
 * 内部路径守卫：/internal/** 端点只供服务间 Feign 调用，网关路由的用户流量
 * 一律拒绝；/actuator/prometheus 等指标端点同样不对网关暴露（Prometheus 从
 * 宿主直连服务端口抓取）。没有这道守卫，StripPrefix 会把 /api/order/internal/
 * orders/{id} 还原成服务侧 /internal/orders/{id}，内部密钥过滤器又因网关注入
 * 而放行——任何登录用户都能查任意订单（外部审查一.1 实测）。
 * <p>
 * 穿越防御：URI.getPath() 已解码，点段（../ 与 %2e%2e）会原样透传，由下游
 * Tomcat 归一化后命中内部路径——因此解码路径里出现 ".." 段直接拒绝，不做
 * 归一化后匹配（归一化实现只要有一个角落漏掉就是绕过）。
 */
@Component
public class InternalPathGuardFilter implements GlobalFilter, Ordered {

    private static final byte[] BODY =
            "{\"code\":40400,\"message\":\"资源不存在\",\"data\":null}".getBytes(StandardCharsets.UTF_8);

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        var route = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR);
        if (route == null) {
            return chain.filter(exchange);
        }
        // 路由已匹配。必须按下游实际会看到的形态推算：先按 StripPrefix 语义
        // 剥掉 /api/{service}（丢弃空段、保留 ; 参数——与 SCG tokenize 一致），
        // 再做下游侧归一化（剥 ; 参数、折叠空段、解析点段——与 CoyoteAdapter
        // 映射前顺序一致）。顺序错了 //internal/、internal;x=1、a/..; 三类
        // 变体都能穿过前缀检查。
        String decoded = exchange.getRequest().getURI().getPath(); // 已解码
        String downstream = normalizeDownstream(stripApiAndService(decoded));
        String raw = exchange.getRequest().getURI().getRawPath();  // 未解码
        // 编码点段（%2e%2e）：解码后即为点段，上面已拦；raw/decoded 里再兜
        // 编码形态（客户端故意编码点段本身就可疑，双重编码亦同）
        if (raw != null && raw.toLowerCase().contains("%2e")
                || decoded.toLowerCase().contains("%2e")) {
            return reject(exchange);
        }
        if (downstream.startsWith("/internal/") || downstream.equals("/internal")
                || downstream.startsWith("/actuator/")) {
            // 对外伪装成 404：不暴露内部端点/指标端点的存在性
            return reject(exchange);
        }
        return chain.filter(exchange);
    }

    /**
     * StripPrefix=2 语义：丢 /api/{service} 两段。空段丢弃、; 参数原样保留
     * （与 SCG tokenize 一致——归一化留给下游侧的 normalizeDownstream）。
     */
    static String stripApiAndService(String decodedPath) {
        java.util.List<String> kept = new java.util.ArrayList<>();
        for (String segment : decodedPath.split("/", -1)) {
            if (!segment.isEmpty()) {
                kept.add(segment);
            }
        }
        if (kept.size() < 2) {
            return "/";
        }
        return "/" + String.join("/", kept.subList(2, kept.size()));
    }

    /**
     * 归一化下游路径：剥 ; 路径参数 → 丢弃空段（// 折叠）→ 解析 . 与 ..
     * （.. 弹出上一段，段不足则忽略）。与 Tomcat CoyoteAdapter 的映射前
     * 归一化顺序对齐，保证“这里看到的 = 下游匹配到的”。
     */
    static String normalizeDownstream(String decodedPath) {
        String[] rawSegments = decodedPath.split("/", -1);
        java.util.Deque<String> stack = new java.util.ArrayDeque<>();
        for (String segment : rawSegments) {
            if (segment.isEmpty() || segment.equals(".")) {
                continue; // 空段折叠、当前段跳过
            }
            int semi = segment.indexOf(';');
            if (semi >= 0) {
                segment = segment.substring(0, semi);
            }
            if (segment.isEmpty()) {
                continue; // 纯路径参数段
            }
            if (segment.equals("..")) {
                stack.pollLast(); // 上跳：弹出最新的段（addLast 与 pollLast 配对）
            } else {
                stack.addLast(segment);
            }
        }
        StringBuilder normalized = new StringBuilder("/");
        java.util.Iterator<String> it = stack.iterator();
        boolean first = true;
        while (it.hasNext()) {
            if (!first) {
                normalized.append('/');
            }
            normalized.append(it.next());
            first = false;
        }
        return normalized.toString();
    }

    private static Mono<Void> reject(ServerWebExchange exchange) {
        var response = exchange.getResponse();
        response.setStatusCode(HttpStatus.NOT_FOUND);
        response.getHeaders().set("Content-Type", "application/json;charset=UTF-8");
        return response.writeWith(Mono.just(response.bufferFactory().wrap(BODY)));
    }

    @Override
    public int getOrder() {
        return -300; // 早于鉴权(-100)与限流(-200)：内部路径最先封死
    }
}
