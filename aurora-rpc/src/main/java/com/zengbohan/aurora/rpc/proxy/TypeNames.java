package com.zengbohan.aurora.rpc.proxy;

import java.util.HashMap;
import java.util.Map;

/**
 * 参数/返回类型名还原：Class#getName 对原始类型返回 "int" 等，
 * Class.forName 解析不了——按 JVM 规范补一张原始类型映射表。
 */
final class TypeNames {

    private static final Map<String, Class<?>> PRIMITIVES = new HashMap<>();

    static {
        PRIMITIVES.put("void", void.class);
        PRIMITIVES.put("int", int.class);
        PRIMITIVES.put("long", long.class);
        PRIMITIVES.put("double", double.class);
        PRIMITIVES.put("float", float.class);
        PRIMITIVES.put("boolean", boolean.class);
        PRIMITIVES.put("byte", byte.class);
        PRIMITIVES.put("short", short.class);
        PRIMITIVES.put("char", char.class);
    }

    private TypeNames() {
    }

    static Class<?> resolve(String name) {
        Class<?> primitive = PRIMITIVES.get(name);
        if (primitive != null) {
            return primitive;
        }
        try {
            return Class.forName(name);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("cannot resolve type: " + name, e);
        }
    }
}
