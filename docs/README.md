# 文档

| 目录 | 内容 | 什么时候读 |
|---|---|---|
| [architecture/](architecture/README.md) | 模块边界、法则、不变量、扩展方式、上游偏离与决策 | 跨模块改动或加新功能之前 |
| [verification/](verification/README.md) | 怎样证明行为与上游一致、`.sok` 与规范一致：测试分层、CI、参考克隆、比对工具 | 改测试或 CI、推进上游锁定之前 |
| [performance/](performance/README.md) | 当前性能、与上游的对照、历次优化记录 | 做性能工作之前 |

## 写法约定

- 文档用中文和全角标点，一段一行，不硬换行；代码、KDoc 与 rustdoc 用英文。
- 写短句，能用列表或表格就不写长段落。同一件事只写在一处，其他地方链接过去。
- 编号一经分配就不再复用：法则 R、不变量 I、决策 D、偏离 #、扩展 #、`.sok` 规则 V 与 A。规则退役时保留说明。
- 代码注释引用文档时写路径加编号，例如 `docs/architecture/invariants.md, I9`，不写章节号。

## 新内容放哪里

- 架构层面的约定：放进 `architecture/` 的对应文档；新的决策在 [decisions.md](architecture/decisions.md) 加一行。
- 新的验证工具或检查：在 `verification/` 加一篇，并在其 README 加一行。
- 性能工作：在 `performance/` 加一篇记录，更新其 README 的现状与索引。
- 尚未实施的设计提案：放 `docs/design/<主题>.md`。落地后把结论并入上面的文档，删除提案。
