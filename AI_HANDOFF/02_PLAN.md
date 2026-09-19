# 当前实战计划与证据

## 总体状态

| 阶段 | 工程状态 | 学习状态 | 证据或下一步 |
|---|---|---|---|
| 理论 S1—S6 | 已结束 | 有既有证据 | 已建立任务、执行器、代理、Future、失败与取消等核心模型 |
| 理论 S7—S8 | 已结束 | 用户自报 | 本仓库未追加逐题验收结论 |
| M0 环境与骨架 | VERIFIED | SKIPPED | Java 21、Maven Wrapper、Spring Boot 骨架及2个测试已验证；不是学习者亲手实现 |
| M1 接口与同步服务 | IN_PROGRESS | NOT_ASSESSED | 当前只做不可变 `OperationLogRecord` 与测试 |
| M2—M6 | TODO | NOT_ASSESSED | 尚未开始 |

## M0 实际工程证据

2026-09-19 在本地 `operation-log-lab` 中实际执行：

```powershell
java -version
.\mvnw.cmd -version
.\mvnw.cmd test
```

结果：

- Eclipse Temurin Java 21.0.11 LTS。
- Maven Wrapper 3.9.10。
- Spring Boot 4.1.1。
- `BUILD SUCCESS`，测试共2个，失败0、错误0、跳过0。
- 一个测试验证 Spring 上下文启动；另一个通过 Spring Web 上下文与 MockMvc 验证 `GET /demo/ping` 返回 HTTP 200 和 `ok`。

这些结果证明 M0 工程骨架可运行，不证明学习者已经亲手掌握 M0。

## 当前小循环：M1 不可变日志记录

学习者需要亲手创建：

- `OperationLogRecord.java`
- `OperationLogRecordTests.java`

记录至少表达：操作名称、方法标识、耗时、成功状态、可选异常类型。不得保存方法参数、返回对象或异常消息。

开始编码前由学习者决定并通过测试固定以下边界：

1. 耗时能否为负数。
2. 成功记录能否携带异常类型。
3. 失败记录是否必须携带异常类型。
4. 操作名称和方法标识为空时如何处理。

完成标准：

- 正常记录与非法记录都有有效断言。
- 项目全部测试通过。
- 学习者能解释至少两个不变量，以及为什么在创建记录时阻止非法状态。

## 当前唯一问题

先不要提供实现代码。请学习者回答：

> 当 `success == true` 时是否允许 `exceptionType` 有值？当 `success == false` 时是否必须有 `exceptionType`？请给出规则和理由。

回答校准后，再让学习者亲手写第一个实现和测试。不要自动继续到 `LogSink` 或 M2。

## 后续 M1 路线

1. 完成不可变记录及不变量测试。
2. 定义 `LogSink`，实现正式 `ConsoleLogSink` 与测试用线程安全 `InMemoryLogSink`。
3. 实现构造器注入的同步 `OperationLogService`。
4. 分别验证纯 Java 手动注入与 Spring 明确装配。
5. 完成 M1 teach-back 后，才决定是否进入 M2。

## 证据边界

- S7—S8的结束状态来自学习者当前自报，不写成 Codex 重新逐题验收。
- M0测试由助手重新运行，M0学习状态因此保持 `SKIPPED`。
- M1尚无学习者代码、测试或解释证据，学习状态保持 `NOT_ASSESSED`。
