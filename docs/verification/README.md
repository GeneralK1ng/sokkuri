# 验证

本目录说明怎样证明 Sokkuri 的行为与上游一致、`.sok` 文件与规范一致。

| 文档 | 内容 |
|---|---|
| [ci.md](ci.md) | 持续集成的分层与工作流 |
| [opencc-clone.md](opencc-clone.md) | OpenCC 参考克隆的锁定、检出与推进 |
| [upstream-diff.md](upstream-diff.md) | 与上游逐条比对的工具 |
| [sok-reader.md](sok-reader.md) | `.sok` 的 Rust 独立读取器与跨实现对拍 |

## 测试分层

- engine：纯函数单元测试。
- config：语义测试，用假的 `DictionaryProvider`，覆盖组策略、tofu 过滤、normalization 顺序与 JSONC。
- runtime：走平台加载器真实路径的集成测试，包括 golden 套件与 `.sok` 回放语料。
- 专项：IDS 与 UTF-16 边界的模糊测试（含孤立代理项与越界的 IDS 树）、词典缓存的并发测试。

测试写在 `commonTest`，在 JVM、Android host 与 iOS 模拟器三端运行。例外：

- `sokkuri-config` 没有自己的测试，配置语义由 runtime 的 `ConfigParserTest` 端到端测试。
- 词典契约套件 `DictionaryContractSuite` 编译进 engine 与 resource 的测试，每个 `Dictionary` 后端继承它。
- runtime 的 `jvmTest` 放只能在 JVM 上做的检查；tools 有各自的 JVM 测试。
- 依赖参考克隆的测试在没有 `OPENCC_DIR` 时报告为 skipped。

## 对齐的证据

按可信度从高到低：

1. **执行比对**：`tools:upstream-diff` 以 OpenCC 参考构建为准，逐条比对转换输出与 inspect 结构，另比对 75 份配置的接受、拒绝与转换结果。这是唯一能证明行为一致的手段。最近一次（2026-10-16，上游提交 8cf737a4）：16 档、18933 条输入、两种 tofu 口径，转换与 inspect 各 302928 次比对，没有差异。
2. **golden 套件**：从上游 `testcases.json` 移植的 569 条期望。它的覆盖面由上游决定，例如不含 IDS 用例。
3. **自洽性**：差分模糊测试把优化后的转换循环与一个不做批量跳过的逐字参考循环对比。它只证明优化没有改变结果，不证明参考循环忠于上游。
4. **读源码推断**：以上都没覆盖到的结论，来自阅读 `OpenCC/src/`，没有经过执行验证。

第 2、3 层不能代替第 1 层。

## 边界

- 上游的输入是 UTF-8，Sokkuri 的是 `CharArray`。含孤立代理项的字符串没有 UTF-8 形式（上游会抛 `InvalidUTF8`），两侧无法比较，由 Sokkuri 自己的模糊测试覆盖。
- 错误路径（缺资源、畸形配置、未知 `dict.type`）由配置比对覆盖。比较的是失败的类别与阶段，不比较消息措辞。
- 比对与漂移检查需要参考克隆与 C++ 工具链，所以不进 `check`，由 CI 的上游对齐工作流运行。
