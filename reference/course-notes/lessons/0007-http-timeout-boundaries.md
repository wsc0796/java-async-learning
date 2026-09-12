> 原课程HTML的正文转写，保留历史观点；当前实现差异以课程勘误为准。

Day 3 · 隔离与超时

# 超时发生在哪个阶段？

同样是“下游很慢”，连接还没建立和连接已经建立，保护动作并不相同。

## 两条时间线

**连接超时**建立 TCP/HTTP 连接阶段迟迟没有成功。项目默认上限为 2 秒。

**读取超时**连接已建立，但等待响应数据超过上限。项目默认上限为 3 秒。

## 回到代码

[CmsFlowAutoConfiguration.java](../../cms-flow/cms-flow-core/src/main/java/com/cms/flow/autoconfigure/CmsFlowAutoConfiguration.java) 把 `connectTimeoutMs` 交给 `HttpClient.connectTimeout`，把 `readTimeoutMs` 交给 `JdkClientHttpRequestFactory.setReadTimeout`。超时结果仍会回到节点失败/fallback 路径。

**可改进点：**为一次 DAG 请求建立 deadline，并把剩余预算传到调度和 HTTP 调用；但这不是一行 `orTimeout` 就完成的改动，还要设计取消传播、节点 fallback、permit 释放和对应测试。

配置证据：[CmsFlowHttpProperties.java](../../cms-flow/cms-flow-core/src/main/java/com/cms/flow/autoconfigure/CmsFlowHttpProperties.java)。

**下一步预测**
再区分 HTTP 超时和外层任务 deadline/取消。
