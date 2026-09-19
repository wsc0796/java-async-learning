# 当前学习计划与证据

## 阶段状态

| 阶段 | 状态 | 已观察证据 |
|---|---|---|
| S1 接口、多态、线程与任务 | UNDERSTOOD | 能区分 Task、Executor、提交/执行线程，以及 `run/start/execute/join` |
| S2 注解、处理器与同步 AOP | UNDERSTOOD | 能说明注解是元数据、Aspect是处理器、外部代理调用与内部自调用不同，AOP增强不等于切线程 |
| S3 线程池参数与回调 | UNDERSTOOD | 能推演 core→queue→max→reject，比较 Abort/Discard/CallerRuns，区分 ThreadFactory 与拒绝处理器 |
| S4 Future 与 DAG | UNDERSTOOD | 能区分 `supplyAsync`提交、Future句柄、`allOf`完成信号和`join`等待，并解释双层 join 与波次屏障 |
| S5 异步日志与职责隔离 | UNDERSTOOD | 用户提供的GPT会话总结标记完成：能区分业务成功、任务接纳、最终成功及失败/延迟隔离 |
| S6 异常、超时与取消 | UNDERSTOOD | 用户提供的GPT会话已完成cancel/interrupt验收，能区分取消、interrupt、协作退出和业务副作用 |
| S7 虚拟线程与许可 | IN_PROGRESS | 已进入S7；第一道虚拟线程与数据库连接池容量题尚未回答 |
| S8 配置、关闭与迁移验收 | NOT_ASSESSED | 待学习 |

## 已纠正且应保留的关键模型

- `Runnable::run` 等价于 `task -> task.run()`，由当前线程执行；只有 `Thread.start()` 启动新线程。
- Executor 是执行策略对象，不保证换线程；direct executor 与线程池行为不同。
- `@OpLog` 是被动元数据；代理匹配切点后由 `OperationLogAspect` 执行增强。`@Async`控制异步，不是 AOP 日志增强的前提。
- core=2、max=3、queue=2时：任务1/2创建线程，3/4入队，5在队列满后创建第3线程并直接执行，6拒绝。
- DiscardPolicy静默丢弃Future包装任务时，没有代码完成Future，后续`join()`可能永久等待。
- `supplyAsync(..., pool)`立即提交；`allOf`只组合完成条件且返回`CompletableFuture<Void>`；`join`让调用线程等待。
- cms-flow中HTTP请求线程在外层join等待整图，dagScheduler线程在内层`allOf.join`等待整波；节点工作线程执行节点。
- 波次屏障会让只依赖快速节点的后继，也等待同波慢节点完成。

## 已运行的教材实验

助手在本地教材环境中运行并观察过：

- `threads`：main直接执行与worker执行同一Runnable。
- `saturation`：3线程、2排队、1拒绝；任务5先于队列中的任务3/4开始。
- `discard`：没有拒绝异常，但被丢任务对应Future仍未完成。
- `futures`：先提交后汇合约398ms，逐项join约567ms。
- `dag`：content → goods/price → assemble，main等待整图、dag线程等待第二波。

这些是教材行为证据，不代表学习者亲自运行或已经实现相同代码。

## 当前精确检查点

S5已完成，核心模型是：

```java
Object result = proceed();
OperationLogRecord record = buildRecord(result);

try {
    logExecutor.execute(() -> logSink.write(record));
} catch (RejectedExecutionException ex) {
    rejectedCount.incrementAndGet();
}

return result;
```

已完成的判断包括：

- 业务方法返回后，业务成功可以确认。
- 在执行器采用明确拒绝策略时，`execute()`正常返回表示任务被接纳，但不表示已经输出。
- `logSink.write`仍在工作线程阻塞时，最终输出是否成功尚不能确认。
- 如果使用 DiscardPolicy，`execute()`正常返回也不能证明任务被接纳。
- `CallerRunsPolicy`可能让HTTP提交线程亲自执行日志任务，从而破坏延迟隔离。
- `try/catch`可以隔离异常传播，但不能自动隔离耗时。

S6已完成：

- worker中的异常先记录到Future；main只有观察Future时才感知。
- Future已经失败，不等于main已经知道失败。
- `exceptionally`用于失败恢复，`whenComplete`主要观察，`handle`可统一转换成功/失败。
- `future.get(timeout)`只限制调用方等待；等待超时不等于任务超时或任务停止。
- `CompletableFuture.cancel(true)`的`true`不会因此interrupt worker；普通`Future.cancel(true)`会尝试interrupt正在执行任务的线程。
- interrupt是协作信号，不是强制杀线程；阻塞型代码可通过`InterruptedException`响应，CPU型代码需要主动检查中断状态。
- Future已取消、worker收到interrupt、任务实际停止、业务副作用停止是四个独立状态。
- `cancel(true)`不会自动撤销已经提交的数据库更新或已发送MQ消息。
- 能用Future状态、中断观察点、任务结束日志和业务副作用分别验证这些层次。
- `InterruptedException`清除中断标记、`Thread.interrupted()`读取并清除等API细节已校准，不阻塞后续学习。

## 下一道未回答问题

直接从这里继续，不要先讲答案：

> Java 21中通过`Executors.newVirtualThreadPerTaskExecutor()`提交1000个都要访问数据库的任务，而数据库连接池`maximumPoolSize=20`。数据库能不能同时处理1000个查询？如果不能，多出来的约980个任务可能在干什么？先按理解回答，不要求知道Semaphore API。

## 后续路线

1. 完成S7第一模型：虚拟线程降低线程成本，但不增加数据库、HTTP下游或连接池容量。
2. 学习Semaphore并发许可、获取失败/等待、任务完成与提交失败三条释放路径。
3. S7验收：解释虚拟线程、连接池、Semaphore与背压分别控制什么。
4. S8：配置约束、禁用、有界关闭、迁移表与总验收。

## 证据边界

S5/S6完成状态来自用户粘贴的网页端GPT阶段总结，作为跨端交接记录；当前仓库未把这些内容伪装成Codex重新运行或逐题验收的结果。
