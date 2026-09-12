> 历史笔记：NORMAL/FAST拒绝策略等内容已过时，请结合本仓库课程勘误阅读。

# cms-flow-core 源码对照学习笔记（手册 V1.0 × 真实代码）

> 目标：用代码证据验证《学习与面试手册》的每个结论，标注差异，形成能指着代码讲 10 分钟的面试底稿。
> 源码根目录：`.\reference\cms-flow`
> 本文所有路径相对于 `cms-flow-core/src/main/java/com/cms/flow/`（core）与 `cms-flow-biz/src/main/java/com.cms.flow/`（biz）。

---

## 0. 项目骨架速览

| 模块 | 角色 | 关键事实 |
|---|---|---|
| `cms-flow-core` | 编排引擎 | 不依赖任何业务模块；只认 SPI 与 `DagNodeSpec` 中记录的 Class |
| `cms-flow-biz` | 业务应用 | 端口 8080；声明 GoodsCardDag + 全部 SPI 实现 |
| `other-stub` | 模拟下游 | 端口 8081；提供 content/goods/price 三个 batch 接口 |
| `other-stub-api` | 契约层 | DTO 定义，biz 与 stub 共同依赖 |

- JDK 21 + Spring Boot 3.4.1 + Resilience4j 2.2.0（`pom.xml` / `.feisuan/rules/project_rule.md`）
- 框架入口：`autoconfigure/CmsFlowAutoConfiguration.java`（`@AutoConfiguration` + 条件装配）

---

## 1. 启动期：DAG 收集 → 校验 → 注册

**证据链**（全部在 `dag/FlowDagDefinitionCollector.java`）：

1. 实现 `SmartInitializingSingleton.afterSingletonsInstantiated()`（:27）——保证所有单例 Bean 就绪后才装配，SPI Bean 此时已可注入。
2. `getBeansWithAnnotation(FlowDag.class)`（:28）扫描 `@FlowDag` 注解的 Bean。
3. 强制要求实现 `FlowDagDefinition` 接口，否则抛异常（:30-32）。
4. 调业务侧 `definition.configure(topology)`（:36）——业务用 `DagTopology.Builder` 声明节点/边/DAG 级 SPI。
5. `validate(meta, topology)`（:38）三道校验：
   - output 节点必须存在于 nodes（:54-56）
   - 每条边的 from/to 都必须是已声明的节点（:58-62）
   - `detectCycle` 用 **Kahn 拓扑排序**检测环：访问数 < 总节点数即有环（:67-101）
6. `registry.register(new DagDefinition(...))`（:39-47）——8 字段的不可变 record。

**业务声明**（`biz/definition/GoodsCardDag.java:24`）：

```java
@FlowDag(dagKey = "goodsCard", outputNodeKey = "assembleNode")
```
configure 中绑定：Converter + PreValidator + DAG Fallback + DAG CacheKeyGenerator + 4 节点 + 4 边。

**★ Insight ─────────────────────────────────────**
- `SmartInitializingSingleton` 是"启动后装配"的惯用钩子；比 `@PostConstruct` 更晚，比 `ApplicationRunner` 更早，且能拿到全部单例。
- 环检测和运行期调度用的是同一套 Kahn 算法（`TopoScheduler.runWaves` 里也有入度表），启动期防坏图、运行期跑图，一份心智模型两处复用。
`─────────────────────────────────────────────────`

---

## 2. 请求主链：一次 POST /api/aggregate/goodsCard

**完整调用链**（含行号证据）：

```
AggregateController.aggregatePost        biz/web/AggregateController.java:33
 → DefaultFlowService.executeAggregate    engine/DefaultFlowService.java:62
    ├─ registry.get(dagKey)  miss → E0404（:63-66）
    ├─ prepareCoordinator.prepare        engine/DagRequestPrepareCoordinator.java:40
    │   ├─ convert：Map → 强类型（fail-safe，失败保留 Map）
    │   └─ validate：多个 PreValidator 顺序 AND（fail-open，异常视为通过）
    ├─ new ExecutionContext(dagKey, preparedBizParams)   engine/ExecutionContext.java
    ├─ responseCacheCoordinator.tryReadDag（:81） 命中直接返回；stale 时异步刷新
    ├─ scheduler.execute(dag, ctx)（:102）→ TopoScheduler  engine/TopoScheduler.java:50
    │   └─ runWaves（:63）：Kahn 波次 + 每波并行 + join
    ├─ 成功：取 outputNode → 异步写 DAG rc + LKG（:118-123）
    └─ 失败：safeDagFallback 整图兜底（:109-115, :127-131）
```

**错误码速查**（`api/FlowResult.java` + 各处）：

| 码 | 含义 | 位置 |
|---|---|---|
| `200` | 成功 | FlowResult.ok |
| `E0001` | 默认失败码 | FlowResult.fail(msg) |
| `E0404` | unknown dagKey | DefaultFlowService:65 |
| `E0500` | DAG 执行抛异常且兜底也失败 | DefaultFlowService:114 |
| `E0502` | 节点执行失败（request build skipped / 调用失败） | HttpNodeExecutor:115,183 |
| `E0503` | 兜底不可用 / 兜底缓存 miss / 输出不可用 | DefaultFlowService:131,215 |

**TopoScheduler 关键实现**（`engine/TopoScheduler.java`）：

- `execute()`（:50）：`CompletableFuture.runAsync(runWaves, dagScheduler).join()` —— **HTTP 线程在此阻塞等待**；Kahn 循环跑在 dagScheduler 池上，编排不占请求线程。
- `runWaves()`（:63）：入度表 + 后继表 → 每波 `CompletableFuture.runAsync` 提交到 `invokeResolver.resolve(spec)` 解析出的池 → `allOf().join()` 波间同步 → 减入度解锁下一波。
- `runNode()`（:116）：按 `NodeType` switch 分发：HTTP → `HttpNodeExecutor`；AGGREGATION → `AggregationNodeExecutor`。**扩展 Dubbo 只需加一个 case**（注释明示）。
- 环兜底：`finished != total` 抛 IllegalStateException（:107-109）。

**ExecutionContext**（`engine/ExecutionContext.java`）：dagKey + bizParams（Map 或强类型，`getBizParamsAs` 取）+ `nodeResults`（ConcurrentHashMap，节点并行写安全）。

**★ Insight ─────────────────────────────────────**
- 调度器用 `dagScheduler` 池跑 Kahn 循环、节点再投到 invoke 池——这是**两级线程转移**：请求线程 → 编排线程 → 执行线程。收益是编排逻辑永不因慢节点阻塞波次推进。
- 聚合失败不会中断整图（`AggregationNodeExecutor.execute` 把失败结果写进 ctx），只有抛 RuntimeException 才触发整图兜底——失败语义分"软失败（记录）"和"硬失败（抛异常）"两层。
`─────────────────────────────────────────────────`

---

## 3. HTTP 节点执行顺序（手册 8.2 验证 ✓）

`nodetype/HttpNodeExecutor.java:72 execute()`：

```
1. resolveFallbackProvider / resolveCacheKeyGenerator（从 Spring 按 Class 取 Bean）
2. responseCacheCoordinator.tryReadNode（:76）—— 读前短路在熔断之前
   ├─ 命中 → putNodeResult 返回；anyStale → scheduleNodeRefresh 异步刷新
3. miss → resilienceGuard.execute(nodeId, () -> invokeHttp)（:95）
   ├─ invokeHttp（:108）：getBean(RequestParser) → build → RestClient POST → ResponseParser.parse
   └─ 成功 → writeNodeAsync（异步写节点 rc）+ writeNodeFallbackCacheAsync（采样写 LKG）
4. 失败 → softFailOrNodeFallback（:104）：节点兜底 → 仍无 → E0502 软失败，留给整图兜底
```

**AggregationNodeExecutor**（`nodetype/AggregationNodeExecutor.java:24`）：getBean(AggregatorHandler) → aggregate → 写 ctx。无远程 I/O。

**★ Insight ─────────────────────────────────────**
- 扩展点全部是"存 Class、运行时 getBean"的**间接查找**（indirect bean lookup）：core 不依赖业务类，业务类不实现框架接口的包内依赖——双向解耦。
- 节点缓存短路发生在熔断**之前**：缓存命中是性能路径，熔断是故障路径，两者不互相干扰。
`─────────────────────────────────────────────────`

---

## 4. SPI 扩展体系（手册 6 验证 ✓）

| SPI | 绑定位置 | 运行时解析 |
|---|---|---|
| `DagRequestParamConverter` | `topology.withRequestParamConverter` | PrepareCoordinator |
| `DagPreValidator`（可多个） | `topology.withPreValidator(...)` | PrepareCoordinator，顺序 AND |
| `NodeRequestParser` / `NodeResponseParser` | `DagNodeSpec.Builder.http(urlKey, parser, parser)` | HttpNodeExecutor.invokeHttp |
| `AggregatorHandler` | `DagNodeSpec.Builder.aggregation(class)` | AggregationNodeExecutor |
| `DagCacheKeyGenerator` / `NodeCacheKeyGenerator` | `withCacheKeyGenerator` | FlowService / HttpNodeExecutor |
| `DagFallbackProvider` / `NodeFallbackProvider` | `withFallback` | FlowService / HttpNodeExecutor |

`DagNodeSpec.build()`（`dag/DagNodeSpec.java:147-177`）有强校验：
- HTTP 节点必须带 urlKey + 双 parser
- AGGREGATION 节点**禁止**绑节点 fallback 与 cache key generator，且强制 `InvokeExecutorType.NORMAL`（:164-172）

节点跨 DAG 复用：`ContentNodeSpec.spec()`（biz）返回静态 `DagNodeSpec`，多个 DAG 可引用同一 Spec 类。

---

## 5. 缓存专题（手册 9 验证 ✓，但有一处重要差异）

### 5.1 两级粒度与读前短路

`cache/response/ResponseCacheCoordinator.java`：
- `tryReadDag`（:71）/ `tryReadNode`（:41）：总开关 → keyGenerator 是否存在 → 该 DAG/节点是否配置 level → TTL → softTtl → bizKey 非空 → 拼 key → `store.get`。
- 命中封装：`nullHolder` → `FlowResult.ok(null)`（防穿透，:212-214）；`ReadResult(result, anyStale, cacheKey)` —— **cacheKey 兼做 SWR 单飞键**（:216）。
- `writeDagAsync`（:138）：仅成功结果；采样率 `effectiveWriteSampleRate`；空值受 `null-cache` 开关控制；全部走 `sideEffectRunner.execute` 异步。
- `putOne`（:230）：TTL 抖动 `ttl-jitter-ratio`（默认 0.1 = ±10%）打散集中失效；空值用独立短 TTL（L1 30s / L2 60s）。

### 5.2 SWR 刷新（手册 9.3-9.4 验证 ✓）

`cache/response/ResponseCacheRefreshCoordinator.java`：
- 触发条件：`softTtlMs == 0` 永不 stale；soft~hard 区间命中 → 返回旧值 + `scheduleRefresh`。
- 三重防抖：
  1. **最小间隔** `min-interval-seconds`（默认 30s）：距上次成功刷新不足则跳过（:50-54）
  2. **单飞** `inflight.putIfAbsent`（:56）：同 key 已有刷新在途则不再提交
  3. **启动抖动** `start-jitter-ms`（默认 2000）：打散同时过期带来的刷新尖峰（:62-67）
- 刷新失败只打日志，不影响已返回的旧值（:73-75）。

### ⚠️ 5.3 手册 vs 代码差异（面试必看）

| 手册说法 | 代码事实 |
|---|---|
| 9.4 "只有抢到**分布式锁**的请求负责回源刷新" | 当前单飞是 **JVM 内 `ConcurrentHashMap`**（`ResponseCacheRefreshCoordinator:23`）。`RedisRefreshSingleFlightLock`（`cache/response/RedisRefreshSingleFlightLock.java`）是**伪代码预留实现，未装配**，方法体直接抛 `UnsupportedOperationException` |
| 9.2 "TTL 可做随机抖动" | ✓ 已实现 `ttl-jitter-ratio` |
| 手册未提 | **Demo 默认 `cache.enabled: false`**（application.yml:121）——不加配置跑 demo 不会命中响应缓存 |

**面试话术**：如果被问"跨实例如何单飞？"——答：当前实现是进程内单飞，多实例下 SWR 刷新每实例各刷一次（可接受，因为还有最小间隔兜底）；已预留 Redis `SET NX PX` + Lua 校验解锁方案（注释里写清了 token 防误删、lease 防死锁），接入点是 `ResponseCacheRefreshCoordinator`。

### 5.4 缓存分层实现

- `CompositeResponseCacheStore`（dagL1 + nodeL1 + optional L2）：L2 命中可回填 L1（`l1BackfillTtl` 参数）。
- Redis Store 条件装配：`@ConditionalOnClass + @ConditionalOnBean(StringRedisTemplate)`（`CmsFlowAutoConfiguration:133-141`）。
- 但 biz 的 application.yml **exclude 了 RedisAutoConfiguration**（:10-14）——Demo 实际只跑 L1 Caffeine。

---

## 6. 熔断专题（手册 10.1 验证 ✓）

`resilience/NodeResilienceGuard.java`：
- Resilience4j `CircuitBreakerRegistry`，**资源名 = nodeId**（:49-50）→ 一节点一熔断器，故障不扩散到健康节点。
- 参数（与手册/yml 完全一致）：sliding-window-size 10、failure-rate-threshold 50%、wait-duration-in-open-state 5000ms、minimum-number-of-calls 5、permitted-number-of-calls-in-half-open-state 3。
- 总开关 `resilience.enabled`（默认 true）；OPEN 时抛 `CallNotPermittedException`（:53-56）。
- 熔断与兜底衔接：`HttpNodeExecutor` 捕获异常 → `softFailOrNodeFallback`（熔断异常也走这条路径）→ 节点 LKG 兜底 → 无则 E0502 软失败 → 整图兜底。

**★ Insight ─────────────────────────────────────**
- 熔断只决定"要不要打下游"，给用户什么结果由兜底负责——两个能力正交，代码里熔断抛异常、兜底接异常，责任链清晰。
- 熔断配置在 core 的 Properties 类（`CmsFlowResilienceProperties`），业务零代码即可获得，这是框架"提供能力、业务决定开不开"的体现。
`─────────────────────────────────────────────────`

---

## 7. 失败兜底专题（手册 10.2 验证 ✓）

**LKG（Last Known Good）思路**：成功路径按采样写缓存，失败路径读缓存。

- 成功写（节点级）：`HttpNodeExecutor.writeNodeFallbackCacheAsync`（:126）——总开关 → 该节点是否配置 level/ttl → 采样率（默认 50%，`write-sample-rate: 5000`）→ 仅成功且 data 非空 → sideEffect 异步写。
- 成功写（整图级）：`DefaultFlowService.writeDagFallbackCacheAsync`（:137）——同样的四道闸门。
- 失败读（整图）：`spi/AbstractCachedDagFallbackProvider.fallback`（:42）——level/ttl 未配 → E0503；bizKey 为空 → E0503；cache miss → E0503。
- 失败读（节点）：`HttpNodeExecutor.softFailOrNodeFallback`（:160）——`isNodeFallbackEnabled(dagKey, nodeId)` 按 DAG+节点独立控制。

**yml 配置结构**（`cms.flow.fallback.*`）：
- `enabled: true`、`write-sample-rate: 5000`、key 前缀 `cms-flow:fb`
- `dags.goodsCard.level: L1`，L1 TTL：整图 600s / 节点 300s（**兜底 TTL 显著长于响应缓存**：60s/30s）
- 响应缓存前缀 `cms-flow:rc` vs 兜底前缀 `cms-flow:fb`——两套隔离（手册 10.3 ✓）

---

## 8. 线程池专题（手册 11 验证 ✓）

`executor/FlowExecutorRegistry.java`（池构建） + `autoconfigure/CmsFlowExecutorProperties.java`（默认值）：

| 池 | 默认值 | 拒绝策略 | 说明 |
|---|---|---|---|
| dagScheduler | 8/32/512 | **Abort**（抛异常） | 编排任务不能丢，满了必须暴露 |
| nodeInvokeNormal | 16/32/256 | Discard（计数） | `use-virtual-threads: true` 时切无界 VT |
| fastNodeInvoke | 8/32/128 | Discard（计数） | **懒建**（双检锁，:93-108） |
| slowNodeInvoke | 无界 VT | — | **懒建**（:111-124） |
| sideEffect | 2/4/128 | 丢弃 + metrics 计数 | 旁路任务可丢，绝不反压主链路 |

其他细节：
- 全部包一层 `MdcPropagatingExecutor`（MDC 上下文传递，日志可追踪，:196-198）
- 线程守护化 + 60s keepAlive（:200-233）
- `NodeInvokeExecutorResolver.resolve`（:20）：AGGREGATION 强制 NORMAL；HTTP 按 `InvokeExecutorType`（FAST/SLOW/NORMAL）解析；Spec 未设置默认 NORMAL
- Actuator 端点 `cmsFlowExecutors`（`executor/CmsFlowExecutorEndpoint.java`）暴露池快照
- `SideEffectRunner.execute`（:31）：拒绝时丢弃并计数，`log.warn` 不阻塞调用方

**面试话术**（对应手册 11.3/11.4）：SLOW 与 FAST 解决的问题不同——SLOW 隔离已知慢任务（VT 承载阻塞 I/O），FAST 保护关键低延迟路径（即使未知慢任务落入 NORMAL 也不影响 FAST）。参数是 Demo 值，真实系统要按核数/RT/并发/压测定，能讲清楚职责隔离比背参数更重要。

---

## 9. 手册与代码差异汇总表（面试避坑清单）

| # | 手册章节 | 手册说法 | 代码事实 | 面试含义 |
|---|---|---|---|---|
| 1 | 9.4 | 分布式锁单飞 | JVM 内 ConcurrentHashMap 单飞；Redis 锁是伪代码未装配 | 跨实例刷新会重复执行；要能讲升级路径 |
| 2 | 手册未提 | — | 转换器失败 fail-safe（保留 Map）；校验器异常 fail-open（视为通过） | 这是代码里的显式设计，回答"校验器挂了会怎样"是加分点 |
| 3 | 手册未提 | — | Demo `cache.enabled: false` | 演示/实验要开缓存需改 yml；面试讲"能力与开关分离" |
| 4 | 11.2 | NORMAL"可配置切虚拟线程" | 默认 `use-virtual-threads: false`（平台池） | 注意默认是平台池 |
| 5 | 手册未提 | — | AGGREGATION 节点禁止绑 fallback/cacheKeyGenerator，强制 NORMAL | DagNodeSpec.build 的防御性校验，可讲"框架强约束" |
| 6 | 2.1 | "当前 Demo 中直接提供 Dubbo 节点实现" | 实际只有 HTTP 节点；扩展 Dubbo 需自己加 executor + switch case | 手册此处略夸大，如实回答"预留了扩展点" |
| 7 | 手册未提 | — | biz exclude RedisAutoConfiguration | L2 只是可选能力，Demo 不启用 |

---

## 10. 高频面试追问的代码级回答抓手（手册 15 增强版）

1. **为什么用 DAG 不用手写 CompletableFuture？** → DAG 把依赖显式化（edges），框架统一做：注册校验（Collector）、波次调度（TopoScheduler）、缓存/熔断/兜底/线程治理；业务只写 configure + SPI。
2. **为什么 Converter 放 DAG 入口？** → 一次 Map→强类型，避免每个 Parser 重复 `Map.get`；`getBizParamsAs(Class)` 统一取参（ExecutionContext:47）。
3. **PreValidator 为什么在调度前？** → `DefaultFlowService` 在 `scheduler.execute` 之前调 `prepareCoordinator.prepare`；reject 直接返回错误码，不占线程不打下游。
4. **DAG 缓存和节点缓存为什么都要？** → tryReadDag 命中直接跳过整图（收益最大）；tryReadNode 细粒度，节点可跨 DAG 复用（SkuIdNodeCacheKeyGenerator 绑在 Content/Goods/Price 三个节点上）。
5. **SWR 为什么还要单飞？** → 软过期瞬间所有请求都会读到旧值并触发刷新；`inflight.putIfAbsent` 只放一个刷新任务，其余直接返回旧值。
6. **熔断 OPEN 后呢？** → 抛 CallNotPermittedException → HttpNodeExecutor 捕获 → 节点兜底 → E0502 软失败 → 整图兜底。熔断不直接给结果，兜底才给结果。
7. **为什么编排池不能做 I/O？** → dagScheduler 8/32/512 Abort 策略 + runWaves 只做调度；若慢调用占住编排线程，波次推进被阻塞（手册 11.1 的"编排线程自己跑 I/O"）。
8. **sideEffect 满了为什么可以丢？** → 主请求已有结果，写缓存是"锦上添花"；反压主链路反而拖慢用户请求（SideEffectRunner 拒绝只计数）。

---

## 11. 自查清单（第 17 章 + 代码验证版）

- [ ] 能画两时间线：启动期（Collector→configure→validate→register）与请求期（Controller→FlowService→Prepare→tryReadDag→TopoScheduler→Executor）
- [ ] 能默写 DagDefinition 的 8 个字段
- [ ] 能讲 tryReadNode 的 6 道闸门（总开关/keyGenerator/level/ttl/softTtl/bizKey）
- [ ] 能讲 SWR 三重防抖（minInterval / inflight 单飞 / startJitter）
- [ ] 能指出 5 类线程池及各自拒绝策略
- [ ] 能说出 4 个错误码（E0404/E0500/E0502/E0503）各自触发场景
- [ ] 能如实说明：Redis L2 与跨实例单飞锁是预留能力，Demo 未启用

## 12. 运行与实验方法

```bash
# 1. 启动下游 stub（8081）与 biz（8080），两个独立进程
mvn -pl other-stub spring-boot:run
mvn -pl cms-flow-biz spring-boot:run

# 2. 请求
curl -X POST http://localhost:8080/api/aggregate/goodsCard \
  -H 'Content-Type: application/json' \
  -d '{"bizParams":{"skuId":"112398","contentId":"c1"}}'

# 3. 观察线程池
curl http://localhost:8080/actuator/cmsFlowExecutors
```

**实验建议**（对应 learning 工作区 Day6-7）：把 `cache.enabled` 改为 true → 观察第二次请求的缓存命中；把 stub 停掉 → 观察熔断计数与 LKG 兜底返回；调整 `write-sample-rate` 观察采样效果。
