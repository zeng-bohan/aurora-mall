package com.zengbohan.aurora.rpc.proxy;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 调用请求载荷（线上格式）：接口名 + 方法名 + 参数类型名 + 实参。
 * <p>
 * 参数类型名用 {@link Class#getName()}（含数组/内部类形态），服务端按它把
 * JSON 泛化的参数节点转换回具体类型（泛型擦除后 Object[] 直接反序列化会退化成 Map）。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Invocation(String interfaceName, String methodName,
        String[] parameterTypeNames, String returnTypeName, Object[] args) {
}
