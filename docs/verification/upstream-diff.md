# 上游比对工具

`tools:upstream-diff` 以 OpenCC 参考克隆的构建为准，与 Sokkuri 逐条比对。第一次运行时它把克隆静态构建到 `build/upstream-opencc`，之后复用，直到克隆换了提交或加 `--rebuild`。它需要 cmake 与 C++ 编译器，不进 `check`。

## 采集器

四个子命令共用一个 C++ 采集器 `opencc_harness.cpp`。它作为资源随工具分发，运行时链接参考构建的静态库编译。它的行协议写在源码开头，与 Kotlin 侧的 `UpstreamHarness` 是同一份约定的两半。缓存的二进制记录源码的 SHA-256，源码一改就重新编译。

## 子命令

- **`diff`（默认）**：逐条比对转换输出与 inspect 结构。上游的 inspect 是嵌套的流水线，比对前按 Sokkuri 的 `Converter.Normalizing` 展开为平铺结构。语料包括 golden 的全部输入、固定的边界用例、`fuzz-corpus.jsonl` 与按种子生成的随机样本，覆盖 16 档。每次运行一种 tofu 口径，`--tofu` 表示包含 tofu 风险词典。有任何不一致即以非零状态退出。
- **`configs`**：配置比对。`ConfigCases` 列出 75 份配置与一组夹具词典，涵盖合法结构、tofu 过滤、文本词典的格式边界与各类非法输入。两侧在两种口径下各自加载，比较是否接受、失败的类别与阶段，以及接受时探针文本的转换结果。失败类别只取两侧都能表达的粒度：资源缺失、内容无效、不支持。
  - 有意的偏离 3 处：`ocd2`（偏离 #3）、分词插件（偏离 #9）、`sok` 类型（上游不认识）。
  - 已知差距：清单现为空。首次运行发现的 7 处已于 2026-10-15 修复。
  - 两张清单都会检查过期：清单里的用例不再分歧，或指向不存在的用例，运行即失败。
- **`fuzz`**：用 libFuzzer 扩充比对语料。目标让每个输入经过全部 16 档、两种口径的转换与 inspect，由上游的代码覆盖率引导。只保留比对能用的输入（合法 UTF-8、不含换行与 NUL），精简后追加到 `fuzz-corpus.jsonl`，只增不删，每次 `diff` 都会回放。需要带 libFuzzer 的 clang：macOS 用 Homebrew 的 LLVM，或用 `FUZZ_CXX` 指定。它补的是代码路径上的边界，词典内容的覆盖仍靠 golden 与随机语料。
- **`bench`**：在 `tools:benchmark` 的样本文本上为上游计时，输出与 benchmark 的 `[bench]` 行同形，以 `kind=upstream` 区分。样本放在 `tools/shared-resources/samples`，两个工具共用。最近的对照结果见 [performance](../performance/README.md)。
