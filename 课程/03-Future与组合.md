# 第 03 课：结果何时回来，多个结果如何组合

> 前置：第 01、02 课。目标：区分提交、等待和结果组合。用时：50 分钟。

## 先预测

```java
var a = CompletableFuture.supplyAsync(() -> queryGoods(), pool);
var b = CompletableFuture.supplyAsync(() -> queryPrice(), pool);
CompletableFuture.allOf(a, b).join();
```

如果两个任务分别模拟耗时 200 ms 和 350 ms，线程充足且无其他竞争，接近 350 ms 还是 550 ms？把第一行改为 `supplyAsync(...).join()` 后才提交 b，会发生什么？

## 从 Future 开始（15 分钟）

`Runnable` 描述无返回值任务；`Callable<T>` 描述有返回值、可抛受检异常的任务。`Executor.execute` 返回 void；`ExecutorService.submit` 可以返回 Future。`submit` 中的任务异常通常保存在 Future 中，需要取结果或显式检查，不能以“日志没报错”当成功。

普通 Future 适合“拿到句柄，再等待或检查”；CompletableFuture 还可以声明“完成后做什么”。泛型 `CompletableFuture<String>` 表示最终成功结果类型是 String；`CompletableFuture<Void>` 表示只关心完成，不携带业务结果。[Java 21 Future](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/concurrent/Future.html)

| 操作 | 本课用法 |
|---|---|
| `runAsync(task, executor)` | 提交无返回值的 Runnable |
| `supplyAsync(supplier, executor)` | 提交产生结果的 Supplier |
| `get()` / `get(timeout, unit)` | 等结果；后者限制这次等待时长；异常需要显式处理 |
| `join()` | 等结果，异常完成通常以 CompletionException 抛出；取消还可能直接抛 CancellationException |
| `allOf(a,b)` | 产生一个等所有输入完成的 Future，结果类型 Void，不自动收集 a/b 的值 |

`join` 不是“开始执行”的开关，任务在提交后就有机会执行。正常情况下显式 executor 决定异步任务去哪里；省略时 CompletableFuture 通常使用公共 ForkJoinPool，不应默认等同于项目业务池。

## 三种组合（10 分钟）

下面是教学示例，项目当前 TopoScheduler 主线使用 `runAsync + allOf + join`，不是已经用全了这些 API。

```java
// 原结果 -> 新值，适合短转换
var name = goods.thenApply(g -> g.name());

// 原结果 -> 下一项异步任务，把嵌套 Future 拉平
var detail = id.thenCompose(i -> queryDetailAsync(i));

// 两项独立任务都成功后合并结果
var card = goods.thenCombine(price, (g, p) -> new Card(g, p));
```

`thenApply` 的函数返回普通值；`thenCompose` 的函数返回下一阶段 Future；`thenCombine` 需要两个结果。非 Async 回调不保证切线程，可能在完成前序任务的线程或注册回调的调用线程执行；耗时工作需要认真选择执行器。[CompletableFuture 语义](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/concurrent/CompletableFuture.html)

## 实验（15 分钟）

```powershell
& '.\实验\run.ps1' -Case futures
```

在 `AsyncLab.futures()` 找到两次 `supplyAsync`、`thenCombine` 和最后一次 `join`。记录线程名、合并结果，以及分别提前等待造成的顺序变化。

手写 3 行：提交 goods；提交 price；等待两项完成。不要求你从头写 main。再补一行通过 `a.join()` 和 `b.join()` 取值——此时二者已完成，不会重新执行查询。

<details><summary>核对提示</summary>

充分并行时接近较慢任务的 350 ms，加调度开销。先提交 a 立即 join，然后才提交 b，就把两个查询串行化，接近 550 ms。若线程池只有一个工作线程，即使先提交两个也未必并行。

</details>

## 回源码与复述（10 分钟）

找到 [TopoScheduler 第 86 行附近](../reference/cms-flow/cms-flow-core/src/main/java/com/cms/flow/engine/TopoScheduler.java#L86)，解释 `List<CompletableFuture<Void>> wave` 保存的是什么。节点业务结果另存在 ExecutionContext，Future 在这里主要充当完成信号。

30 秒复述：**Executor 负责执行，Future 记录完成，allOf 组合完成条件，join 让当前线程等待。** 再自己举一个 thenCompose 与 thenCombine 的区别例子。
