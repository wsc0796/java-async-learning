> 原课程HTML的正文转写，保留历史观点；当前实现差异以课程勘误为准。

Day 3 · 隔离与背压

# permit 代表占用，不代表成功

信号量保护的是“同时执行中的槽位”。任务失败也结束了占用，必须归还 permit。

## 安全结构

```text
if (!semaphore.tryAcquire()) reject();
try {
    invokePrice();
} finally {
    semaphore.release();
}
```

成功、读取超时、网络异常、解析失败和可传播的取消，都应走 `finally`。只在成功路径释放会造成 permit 泄漏。

## 等待策略

`tryAcquire()` 表示立即拒绝；`acquire(timeout)` 表示有界等待。无论选择哪种，都不能让等待无限延长并超过请求 deadline。

相关项目背景：[BoundedVirtualExecutor.java](../../cms-flow/cms-flow-core/src/main/java/com/cms/flow/executor/BoundedVirtualExecutor.java) 已存在，但当前 `slowNodeInvoke` 仍使用无界虚拟线程。

**下一步预测**
判断 permit 等待超过请求 deadline 时应该拒绝还是继续等待。
