# Java 21 最小实验

这里的代码为课程新建的教学示例，参考项目源码位于 `.\reference\cms-flow`。实验不访问网络或数据库。

## 每次只跑一项

先阅读当课预测题，再在 PowerShell 执行：

```powershell
& '.\实验\run.ps1' -Case threads
```

脚本会用 UTF-8 编译两份 Java 文件，编译结果放在本目录 `.classes`，然后运行选中的实验。机器需要 JDK 21 或更高版本，`java` 和 `javac` 均可调用；当前材料使用 Java 21 语义讲解。

| Case | 课程 | 阅读 AsyncLab 中的方法 | 主要观察 |
|---|---|---|---|
| threads | 01 | threads | main 与 worker 的身份、等待点 |
| saturation | 02 | saturation | 3个线程、2个排队、1个拒绝 |
| discard | 02 | discard | 无拒绝异常但 Future 未完成 |
| futures | 03 | futures | 两项任务先提交再汇合，结果组合 |
| dag | 04 | dag | 固定三波、依赖顺序、NORMAL/SLOW |
| starvation | 05 | starvation | 单线程池父等子，子仍排队 |
| failure | 06 | failure | 一个失败，allOf仍等待另一个 |
| timeout | 06 | timeout | get等待超时、orTimeout状态与真实工作 |
| cancel | 06 | cancel | Future取消没有中断本演示任务 |
| virtual | 07 | virtual、submitBounded | 虚拟线程、2个许可、拒绝及释放路径 |
| practice | 08 | 改读 PracticeDag.java | 六个TODO补齐后的三项检查 |

`-Case all` 会运行十项演示，**不会运行尚待完成的 practice**。十项演示中的 `PASS` 表示材料行为检查通过，不能自动填写学习记录的“已掌握”。

`AsyncLab.java` 放在一个文件里方便编译，没有要求一次读完。课程正文中的命令只运行本节需要的方法。

## 命令被执行策略限制时

无需修改系统策略，直接使用 JDK 的单文件启动方式：

```powershell
java '-Dfile.encoding=UTF-8' '-Dstdout.encoding=UTF-8' '-Dstderr.encoding=UTF-8' '.\实验\AsyncLab.java' threads
```

综合练习也可以直接运行：

```powershell
java '-Dfile.encoding=UTF-8' '-Dstdout.encoding=UTF-8' '-Dstderr.encoding=UTF-8' '.\实验\PracticeDag.java'
```

原始练习的预期结果是 `UnsupportedOperationException: TODO 1...`。补齐后才能期待三个“通过”及最终 PASS；异常不是你机器坏了，也不是课程已经完成。

## 如何观察

每次写三句话：我预计谁在哪个线程执行；日志显示了什么；结果与预测不同的原因是什么。毫秒数受机器调度影响，不用追求完全一致。

演示中的门闩（CountDownLatch）是实验夹具：把任务暂停在固定观察点，便于判断队列、完成状态或并发。第一轮会用即可，不必先背它的实现。

破坏性实验只修改当前课程里的副本：例如将 `pool.execute(task)` 改成 `task.run()`，或把许可 2 改为 1。先预测、观察失败、解释原因，再还原。不要对 `.\reference` 的参考项目做同样改动。
