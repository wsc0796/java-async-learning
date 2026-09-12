> 原课程HTML的正文转写，保留历史观点；当前实现差异以课程勘误为准。

Day 3 · 隔离与背压

# 队列满了，失败要暴露

有界线程池的价值不只是“排队”，还在于它能把资源耗尽明确暴露出来。

## 实际边界

`nodeInvokeNormal` 默认最多 32 个运行任务、队列最多 256 个；再提交任务时，实际代码使用 Abort handler，计数后抛出 `RejectedExecutionException`。这与 `sideEffect` 的“丢弃并计数”是不同策略。

## 失败传播

节点提交被拒绝 → `runWaves` 异常完成 → `TopoScheduler.execute` 抛出 → `DefaultFlowService` 尝试整图 fallback。拒绝数可通过 `/actuator/cmsFlowExecutors` 的池快照观察。

源码：[FlowExecutorRegistry.java](../../cms-flow/cms-flow-core/src/main/java/com/cms/flow/executor/FlowExecutorRegistry.java)、[TopoScheduler.java](../../cms-flow/cms-flow-core/src/main/java/com/cms/flow/engine/TopoScheduler.java)。

**下一步预测**
比较线程池队列和信号量：两者都能限制并发，但等待与拒绝语义不同。
