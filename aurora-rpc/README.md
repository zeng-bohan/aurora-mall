# aurora-rpc

手写简化 RPC（ADR-0008 三部曲之三）：**自定义协议 + Netty 传输 + 序列化 SPI + 动态代理 + 注册发现 + 负载均衡**，与 OpenFeign 可通过配置开关切换（切换收敛见 M3 T8）。核心传输零框架依赖，Spring 胶水可选。

## 设计

### 传输层 `RpcServer` / `RpcClient`（Netty）

- **线程模型**：服务端 boss 组（1 线程，接连接）+ worker 组（IO 线程）+ 业务线程池（跑处理器）。业务处理与 IO 隔离，慢处理器不阻塞读事件循环；全部为 daemon 线程，测试收尾干净
- **内部密钥握手**（复刻 HTTP 侧 `X-Internal-Secret` 语义）：连接建立后客户端先发 `H:<secret>` 控制帧，服务端校验并把 `AUTHENTICATED` 标记打到 channel 属性上；此后该连接上的请求才被受理。未握手直接发请求 → 服务端回 `UNAUTHORIZED` 错误帧再断连（客户端拿得到原因）；密钥错误 → 直接断连
- **有界业务池 + 过载保护**：池满的请求以 `OVERLOADED` status 立即回绝——不无界堆积内存、不断连，调用方可退避重试
- **空闲连接回收**：服务端读空闲超时（默认 90s，可配）关连接——对端进程崩溃不发 FIN 的半开连接不会泄漏文件描述符
- **requestId → Future 挂起**：客户端每次调用分配唯一 requestId，响应按 requestId 找回挂起的 promise；断线时在途 Future **立即失败**（而非等超时），`stop()` 同样立即失败在途调用
- **超时快速失败**：`promise.get(timeout)`，超时抛 `RpcTimeoutException`；发送失败立即失败该调用
- **心跳与断线重连**：心跳间隔独立于重连退避（连接空闲超过 `heartbeatIntervalMillis` 才发 ping，不随退避周期抖动）；客户端读空闲（3 个心跳周期无响应）判定失联主动断开；服务端重启后客户端按退避自动重连，调用自动恢复
- **处理器异常以 status 回传**：业务处理器抛异常时，服务端以 `status=ERROR` + 消息回传而**不**断连；调用方收到 `RpcRemoteException`（"对端业务失败"），与 `RpcUnavailableException`（"对端不可达"）语义区分；响应回显请求的 serializerCode（两端按请求协商实现）
- 消息类型：pipeline 以 `ByteBuf` 为消息类型（`LengthFieldBasedFrameDecoder` 之后），出站写入用 `Unpooled.wrappedBuffer` 包装；拆包配置统一走 `ProtocolCodec.newFrameDecoder()`（body 上限可配）

**踩坑记录**：`connect().sync()` 不能在 client 的 event-loop 线程上调用——那个线程正是完成连接的线程，会自锁；改用异步 connect + listener 设置 channel。另有一个隐蔽 bug：`decodeHeader` 只解头、body 原为占位（供拆包器先看长度），传输层若误用它拿到的 body 与真实数据不符——为此专门提供 `decode()` 切出真实 body，并有单测锁住两者区别（仅解头的帧不按声明长度分配内存，坏帧头声明 10MB 也不会被放大成实际分配）。

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

## 测试覆盖（25 例，`mvn -pl aurora-rpc test`，CI 可跑、零外部依赖）

| 场景 | 说明 |
| --- | --- |
| 头往返 | 编码→解码头字段全对，长度 = 18 + body |
| decode 切真实 body | decodeHeader 只解头（body 空、声明长度如实报告），decode 切出真实 body（单测锁定两者区别） |
| decode 截断 body | body 未收全返回 null |
| 坏魔数/超长 body | 破坏 magic、bodyLength 正溢出（>上限）与负数三路都拒绝 |
| 配置防御 | type/body 为 null、未知 serializer code fail-fast |
| SPI 可插拔 | 注册第二个实现按 code 取用往返 |
| 集合与嵌套载荷 | List 与嵌套 record（List<Inner> + Map）JSON 往返 |
| 单帧拆包 / 粘包 / 半包 | EmbeddedChannel 喂拼接、切片字节流 |
| 传输：往返 | 真 Netty server/client 随机端口正常往返 |
| 传输：大 body | 512KB 请求体往返不截断 |
| 传输：处理器异常 | 以 RpcRemoteException 穿透，连接不断 |
| 传输：超时 | 300ms 阈值对 2s 慢处理器快速失败（远早于完成） |
| 传输：错误密钥 | 密钥错的连接始终拿不到成功响应 |
| 传输：未握手请求 | 裸 socket 直接发请求 → UNAUTHORIZED 错误帧回传 |
| 传输：并发配对 | 8 线程 × 20 次乱序响应按 requestId 正确配对、零串台 |
| 传输：同客户端断线重连 | 服务端同端口重启，同一客户端退避重连、调用自动恢复 |
| 传输：空闲回收 | 服务端读空闲后半开连接被回收（client isConnected 翻转可观察） |
| 传输：心跳失联检测 | 连到"只收不发"的死端，读空闲后主动断开 |
| 传输：过载拒绝 | 业务池 1 线程 + 队列 1：第 3 个起 OVERLOADED 拒绝、不断连，释放后照常服务 |
| 传输：stop 失败在途 | stop() 立即失败挂起调用（远早于 10s 超时） |

## 设计要点回顾

1. **定长头的字段取舍**：magic 检错、version 演进、serializer code 让两端协商实现——用 18 字节换来"解析即索引"，没有可变长 header 字段的指针 chase。
2. **坏帧防御在解析层返回 null 而非抛异常**：Netty 的 IO 线程被异常打断会连带关闭连接并影响其他请求，解析层"拒绝"比"报错"更稳。
3. **拆包 offset 紧跟定长头**：bodyLength 在 offset 14，LengthFieldBasedFrameDecoder 靠这个偏移直接切帧，不扫描 body 内容。
4. **握手 vs 逐帧鉴权**：把内部密钥放在连接级握手（一次）而非每帧带（每请求开销）；代价是密钥轮换需重连，但对内部服务可接受。
5. **处理器异常回传而非断连**：区分"对端业务失败"（可重试/可告警）与"对端不可达"（快速失败/熔断），让调用方的熔断器只对真正的不可用计数。
6. **异步 connect 而非 sync**：在 event-loop 线程上同步等待连接完成会自锁，这是 Netty 单线程模型下的经典陷阱。
