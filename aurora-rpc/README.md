# aurora-rpc

手写简化 RPC（ADR-0008 三部曲之三）：**自定义协议 + Netty 传输 + 序列化 SPI + 动态代理 + 注册发现 + 负载均衡**，与 OpenFeign 可通过配置开关切换（切换收敛见 M3 T8）。核心传输零框架依赖，Spring 胶水可选。

## 设计

### 自定义协议：定长 18 字节头 + body

```
offset  0  magic(2)      —— 魔数 0xA0B0，误连别的协议立刻判掉
offset  2  version(1)    —— 协议版本
offset  3  type(1)       —— 请求/响应
offset  4  serializer(1) —— 序列化实现编号
offset  5  status(1)     —— 业务结果码（响应帧用）
offset  6  requestId(8)  —— 请求/响应配对
offset 14  bodyLength(4) —— body 字节数
offset 18  body
```

- **为什么自定义协议而非 HTTP**：省掉 HTTP 文本解析与 header 开销，requestId 定长寻址让响应挂起表零查找开销；代价是失去了 curl 就能抓包排障的能力，用 JSON 默认序列化缓解。
- **拆包**：`LengthFieldBasedFrameDecoder(maxFrame, offset=14, lengthField=4, ...)`，粘包/半包由它处理，单测用 EmbeddedChannel 喂拼接/切片字节流验证。
- **坏帧防御**：魔数错、版本错、未知 type、bodyLength 超上限（10MB 防错位帧分配巨量内存）一律 `decodeHeader` 返回 null，由调用方关连接——解析层永不抛异常打断 IO 线程。

### 序列化 SPI

`Serializer` 接口（code + serialize + deserialize），按 code 注册到 codec。默认 **JSON**（Jackson，注册 jsr310 支持 LocalDateTime，关闭 `WRITE_DATES_AS_TIMESTAMPS` 与 `FAIL_ON_UNKNOWN_PROPERTIES`）。SPI 可插拔性由测试内第二个实现（uppercase stub）证明。

取舍记录在设计要点：为什么默认 JSON 而不是 Hessian/Kryo/Protobuf——**JDK 原生序列化禁用**（gadget 反序列化漏洞），JSON 胜在可读与跨语言，代价是体积与 CPU；Hessian/Kryo 是二进制高性能但引第三方信任与版本坑，Protobuf 强 schema 但需要先定义 .proto 且 Java 侧可读性差。

## 测试覆盖（9 例，`mvn -pl aurora-rpc test`，CI 可跑、零外部依赖）

| 场景 | 说明 |
| --- | --- |
| 头往返 | 编码→解码头字段全对，长度 = 18 + body |
| 配置防御 | type/body 为 null 拒绝 |
| 坏魔数 | 破坏 magic 后 decodeHeader 返回 null |
| 超长 body | bodyLength 爆表返回 null |
| SPI 可插拔 | 注册第二个实现按 code 取用往返 |
| 集合载荷 | JSON 序列化 List 往返 |
| 单帧拆包 | 完整帧一次读出 |
| 粘包 | 一次读入两帧被正确拆成两个 |
| 半包 | 喂一半不产帧，补齐后产出 |

## 设计要点回顾

1. **定长头的字段取舍**：magic 检错、version 演进、serializer code 让两端协商实现——用 18 字节换来"解析即索引"，没有可变长 header 字段的指针 chase。
2. **坏帧防御在解析层返回 null 而非抛异常**：Netty 的 IO 线程被异常打断会连带关闭连接并影响其他请求，解析层"拒绝"比"报错"更稳。
3. **拆包 offset 紧跟定长头**：bodyLength 在 offset 14，LengthFieldBasedFrameDecoder 靠这个偏移直接切帧，不扫描 body 内容。
