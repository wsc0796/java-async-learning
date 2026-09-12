> 原课程HTML的正文转写，保留历史观点；当前实现差异以课程勘误为准。

Lesson 0001 · DAG 波次调度

# 为什么 goods 与 price 能并行？

本课只完成一个胜利：从四条边和十几行调度代码，预测一轮请求的节点执行顺序。

## 先看事实，不看结论

`GoodsCardDag` 声明了四条边：

```text
content → goods
content → price
goods   → assemble
price   → assemble
```

源码：[GoodsCardDag.java:40–44](../../cms-flow/cms-flow-biz/src/main/java/com/cms/flow/definition/GoodsCardDag.java)

## 调度器只做三件关键的事

```text
// 1. 当前入度为 0 的节点进入 current
for (String nodeId : current) {
    // 2. 当前波的每个节点都立即创建并提交一个任务
    wave.add(CompletableFuture.runAsync(
        () -> runNode(dag, ctx, nodeId), invoke));
}
// 3. 当前波全部完成后，才计算下一波
CompletableFuture.allOf(...).join();
```

源码：[TopoScheduler.java:75–104](../../cms-flow/cms-flow-core/src/main/java/com/cms/flow/engine/TopoScheduler.java)。Oracle 文档确认：带 Executor 的 `runAsync` 在指定 Executor 中运行任务；`allOf` 生成一个在所有给定 Future 完成后才完成的 Future，`join` 等待其完成。

到这里先停。不要展开下面的推导。

请只根据四条边和上述三步，写出你的预测：

- 第一、第二、第三波各有哪些节点？
- 第二波会创建几个节点 Future？
- 第二波的 `allOf(...).join()` 是谁在等待？等待哪些 Future？
- 如果 `priceNode` 慢 3 秒，`assembleNode` 能否提前开始？为什么？

完成预测后，再展开“入度如何变化”提示

初始入度来自“有多少条边指向我”。每完成一整波，调度器才把该波所有节点指向的后继入度减一；减到 0 的后继才进入下一波。尝试自己列一张四行表：节点、初始入度、第 1 波后入度、第 2 波后入度。

仍卡住时，再展开“线程与 Future”提示

`execute()` 先把整个 `runWaves` 提交给 `dagScheduler`，HTTP 请求线程在外层 `join`。进入 `runWaves` 后，调度线程又把每个节点任务提交给节点对应的 invoke Executor，并在每一波的 `allOf(...).join()` 等待。

## 本轮要填的八问卡

打开 [异步代码八问速查卡](../reference/0001-async-analysis-card.md)，先只填写第二波。当前不要分析缓存、Fallback、Resilience4j 或虚拟线程的实现。

## 回到真实源码

- [TopoScheduler.java](../../cms-flow/cms-flow-core/src/main/java/com/cms/flow/engine/TopoScheduler.java)：波次计算、任务提交与两个等待点。
- [GoodsCardDag.java](../../cms-flow/cms-flow-biz/src/main/java/com/cms/flow/definition/GoodsCardDag.java)：四条依赖边。
- [ExecutionContext.java](../../cms-flow/cms-flow-core/src/main/java/com/cms/flow/engine/ExecutionContext.java)：各节点共享的结果容器。

## 权威资料

主读：Oracle Java 21 [CompletableFuture API](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/concurrent/CompletableFuture.html)。本轮只查 `runAsync(Runnable, Executor)`、`allOf` 和 `join`，不要扩展成 API 大全。

 **反馈回路**
 把四个预测答案发给 Agent。Agent 会先判断推理是否自洽；若有冲突，只给下一条线索。你也可以随时追问任何不清楚的地方。
