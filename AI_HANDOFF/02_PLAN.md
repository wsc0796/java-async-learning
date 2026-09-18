# 当前学习计划与证据

## 阶段状态

| 阶段 | 状态 | 已观察证据 |
|---|---|---|
| S1 接口、多态、线程与任务 | UNDERSTOOD | 能区分 Task、Executor、提交/执行线程，以及 `run/start/execute/join` |
| S2 注解、处理器与同步 AOP | UNDERSTOOD | 能说明注解是元数据、Aspect是处理器、外部代理调用与内部自调用不同，AOP增强不等于切线程 |
| S3 线程池参数与回调 | UNDERSTOOD | 能推演 core→queue→max→reject，比较 Abort/Discard/CallerRuns，区分 ThreadFactory 与拒绝处理器 |
| S4 Future 与 DAG | UNDERSTOOD | 能区分 `supplyAsync`提交、Future句柄、`allOf`完成信号和`join`等待，并解释双层 join 与波次屏障 |
| S5 异步日志与职责隔离 | IN_PROGRESS | 正在区分业务成功、任务接纳、Sink输出成功及拒绝策略对请求线程的影响 |
| S6 异常、超时与取消 | NOT_ASSESSED | 待学习 |
| S7 虚拟线程与许可 | NOT_ASSESSED | 待学习 |
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

S5使用以下模型：

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

已完成的判断：

- 业务方法返回后，业务成功可以确认。
- 在执行器采用明确拒绝策略时，`execute()`正常返回表示任务被接纳，但不表示已经输出。
- `logSink.write`仍在工作线程阻塞时，最终输出是否成功尚不能确认。
- 如果使用 DiscardPolicy，`execute()`正常返回也不能证明任务被接纳。

## 下一道未回答问题

直接从这里继续，不要先讲答案：

> 日志线程池已经饱和，拒绝策略是 `CallerRunsPolicy`，提交线程是 `http-1`，而 `logSink.write(record)` 会阻塞3秒。虽然代码没有调用 `join()`，HTTP响应还会不会被日志阻塞？任务最终由谁执行，为什么？

## 后续路线

1. 完成 CallerRunsPolicy 对隔离目标的影响，并比较适合关键任务与旁路任务的拒绝语义。
2. S5验收：解释业务成功、提交接纳、输出成功三种状态，以及为什么主链只异步提交一次。
3. S6：拒绝、任务异常、业务失败、等待超时、Future状态和实际资源占用。
4. S7：虚拟线程适用边界、Semaphore许可、三条释放路径。
5. S8：配置约束、禁用、有界关闭、迁移表与总验收。
