package com.zengbohan.aurora.order.dto;

import java.util.List;

/**
 * 分页结果。
 * <p>
 * 字段与 MyBatis-Plus 的 {@code Page} 对齐（records/total/size/current/pages），
 * 这样前端一套分页类型就能同时吃商品列表和订单列表。order 模块没装分页插件，
 * 所以这里手工组装——顺手也把 MyBatis-Plus 那一堆内部字段（optimizeCountSql、
 * countId、maxLimit…）挡在响应之外。
 */
public record OrderPage(List<OrderView> records, long total, long current, long size, long pages) {
}
