> 原课程HTML的正文转写，保留历史观点；当前实现差异以课程勘误为准。

Reference 0001

# 异步代码八问

遇到任何异步代码，先填完这张卡，再讨论“快不快”或“安不安全”。

**调用线程**谁发起任务？它何时返回或等待？

**执行线程**谁真正运行任务代码？

**Task**被提交的那段代码究竟是什么？

**Executor**任务被提交给哪个执行器？

**Future**它代表哪个任务或哪些任务的完成状态？

**等待点**哪里发生 `join/get/blocking`？谁在等？

**共享状态**哪些对象会被多个线程读写？

**失败传播**异常令哪个 Future 失败，最终在哪里被观察？

## 三个禁止混淆的概念

**Task** 是要做的事；**Thread** 是运行代码的载体；**Executor** 决定把任务交给谁运行。

## 本项目的空白模板

```text
调用线程：
执行线程：
Task：
Executor：
Future：
等待点：
共享状态：
失败传播：
```

依据：Oracle Java 21 的 [CompletableFuture](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/concurrent/CompletableFuture.html)、[Executor](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/concurrent/Executor.html) 与 [ConcurrentHashMap](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/concurrent/ConcurrentHashMap.html) 文档。
