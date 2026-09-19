# 网页端 GPT 学习接续入口

请先读取：

1. [学习目标与教学边界](AI_HANDOFF/01_REQUIREMENT.md)
2. [当前进度、证据与下一题](AI_HANDOFF/02_PLAN.md)
3. 需要概念原文时读取[合并版课程](GPT_COURSE.md)
4. 需要源码取证时读取[cms-flow源码合并版](GPT_SOURCE.md)

当前状态：S1—S5完成，S6进行中并刚进入cancel/interrupt，M0—M6工程实践未开始。不要从S1重新教学，也不要执行M0。

## 可直接复制给 GPT 的提示词

```text
请实际读取以下GitHub材料后接手我的学习：

https://raw.githubusercontent.com/wsc0796/java-async-learning/main/AI_HANDOFF/01_REQUIREMENT.md
https://raw.githubusercontent.com/wsc0796/java-async-learning/main/AI_HANDOFF/02_PLAN.md

需要课程原文时读取：
https://raw.githubusercontent.com/wsc0796/java-async-learning/main/GPT_COURSE.md

需要cms-flow源码时读取：
https://raw.githubusercontent.com/wsc0796/java-async-learning/main/GPT_SOURCE.md

先用3句话说明你实际读到的当前阶段、已掌握内容和下一道未回答问题，然后直接从AI_HANDOFF/02_PLAN.md的“下一道未回答问题”继续。

使用简体中文，一次只问一个新的、可评分的问题。先让我预测，再针对我的回答校准。不要出答案已经写在题干里的显而易见问题。需要实验时请运行并给出真实输出，再让我判断；无法运行时如实说明。当前只教学S6—S8，不启动M0—M6工程实践，不把教材实验通过当成我已经掌握。
```

若 GPT 无法读取某个链接，请明确指出失败链接，不要根据文件名假装已经读取；可以从仓库下载对应Markdown后作为附件上传。
