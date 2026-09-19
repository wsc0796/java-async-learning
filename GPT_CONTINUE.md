# 网页端 GPT 实战接续入口

请先读取：

1. [学习目标与协作边界](AI_HANDOFF/01_REQUIREMENT.md)
2. [当前进度、工程证据与下一步](AI_HANDOFF/02_PLAN.md)
3. 需要理论回顾时读取[合并版课程](GPT_COURSE.md)
4. 需要 cms-flow 源码取证时读取[源码合并版](GPT_SOURCE.md)
5. 需要完整工程阶段定义时读取[操作日志实战任务书](reference/OPERATION_LOG_WORKFLOW.md)

当前状态：理论学习阶段已经结束；M0 工程骨架已验证；M1 正在进行。当前第一个亲手任务是实现不可变 `OperationLogRecord` 及其单元测试。

## 可直接复制给 GPT 的提示词

```text
请实际读取以下 GitHub 材料后接手我的 Java 工程实战：

https://raw.githubusercontent.com/wsc0796/java-async-learning/main/AI_HANDOFF/01_REQUIREMENT.md
https://raw.githubusercontent.com/wsc0796/java-async-learning/main/AI_HANDOFF/02_PLAN.md

需要完整任务书时读取：
https://raw.githubusercontent.com/wsc0796/java-async-learning/main/reference/OPERATION_LOG_WORKFLOW.md

先用3句话说明你实际读到的当前模式、已验证工程阶段和当前亲手任务，然后直接继续M1。

使用简体中文，一次只推进一个小循环。先让我确定行为契约和边界，再让我亲手写代码；你负责检查diff、运行或审阅测试结果、指出与契约不符的位置，并且每次最多给一条不含完整答案的提示。不要替我写核心实现，不要自动进入M2。理论内容只在代码或解释暴露出具体缺口时回顾。
```

当前 Spring 项目源码保存在学习者本地工作区；本次公开同步只保存精炼进度、任务契约和测试证据。需要网页端审查尚未推送的代码时，由学习者粘贴 diff 或在后续明确同步工程源码。

若 GPT 无法读取某个链接，请明确指出失败链接，不要根据文件名假装已经读取。
