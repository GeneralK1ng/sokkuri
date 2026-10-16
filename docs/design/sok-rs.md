# 设计：`.sok` 的 Rust 独立读取器

**状态：实施中，2026-10-16 起按第 11 节分阶段落地：P0 至 P3 的内容已经落地，第 8 节的规范澄清已于当日决定并实施，CI 尚待首次运行。** 本文提出 `tools/sok-rs`：一个按 `SokFormatLayout` 规范独立实现的 `.sok` 只读读取器，以及围绕它的审计、规范对拍与模糊测试。它是验证工具，不是发布产物；实施时按第 9 节修订 `docs/architecture.md`。

## 1. 动机

`.sok` 是生产格式，`SokFormatLayout` 的 KDoc 是它唯一的规范，编码器与解码器同放 `sokkuri-resource`（D6）。D6 保证两端读的是同一份布局常量，却证明不了规范本身写得完整：规范与 Kotlin 解码器出自同一处，规范漏写的规则，解码器也照样漏掉，没有第二种读法能把它暴露出来。

现状里已经有这样的缺口，均经源码核对：

- 规范写了“键按码点序排列”（I2），解码器并不检查；检索依赖它做二分，乱序的文件会被接受，然后静默地查错；
- `maxKeyLength` 解码器只检查不大于键 blob 的字节数；检索按它截断输入窗口，比真实值小时，长键会静默地匹配不上；
- `keyOffsets` 与 `valueOffsets` 只要求严格递增，首项不必为 0，blob 开头可以夹着无人引用的字节；
- 偏移 28 的保留字段，规范写明为 0，解码器不检查，而同为保留语义的 `flags` 是检查的；
- 编码器拒绝空候选，解码器接受长度为 0 的候选；
- 编码器用 `encodeToByteArray` 写键，孤立代理项会被静默替换为 U+FFFD，写出的键与原键不同，排序也可能随之错乱。今天的数据走不到这里，因为上游的 UTF-8 词典文件里不可能有孤立代理项。

历史上解码器出过的缺陷也属于这一类：一张 valueOffsets 表通过了当时的全部检查，解码成功，首次访问时却越界（`SokDecoderValidationTest.candidateHeaderInTheLastByteIsRejected` 记录的回归）。这类问题的共同点是：单靠一份实现自证，查不出来。

另有一个次要动机。8.8 的结论是，内核语言不是关键手段，但将来若重议原生内核，零拷贝、可 mmap 的 Rust 读取器正是它的数据层。本设计只做这一层，不做检索与转换，不预设将来要不要内核。

## 2. 目标与非目标

目标：

- 严格按规范独立实现 `.sok` v1 的读取，不参照 Kotlin 源码，以此复核规范是否自足；
- 把规范要求、但运行时不检查的不变量做成审计规则，对全部打包词典逐项审计；
- 以一份与语言无关的规范化摘要，证明 Rust 与 Kotlin 两个读取器对同一输入得出同一判定、拆出同一结构；
- 以覆盖率引导的模糊测试积累畸形输入，提交为语料，由 `./gradlew build` 在三端回放，与 Rust 的判定逐条比对；
- 收尾时给出规范澄清清单，逐条决定是写进规范、交由解码器强制，还是只做审计。

非目标：

- 不写 `.sok`：Rust 侧没有编码器，编码器只此一份（D6）。测试里按字节拼装的样本只用来构造非法输入，合法样本一律取自 Kotlin 编码器的产物；
- 不进发布产物，不进 `./gradlew build` 与 `check`；不安装 Rust 也能完整构建、测试与发布 Sokkuri；
- 不做检索、转换与 Kotlin 绑定，这些留给第 11 节的远期阶段 P4，届时单独立项；
- 不改 `.sok` 的字节布局；规范澄清只收紧或补写规则，第 8 节逐条说明。

## 3. 组成与目录

```
tools/sok-rs/                 Cargo 工作区；同时是一个不带插件的 Gradle 项目，只放调用 cargo 的任务
├── build.gradle.kts          Gradle 薄封装，见 10.1
├── Cargo.toml                工作区清单
├── Cargo.lock                提交，所有 cargo 调用加 --locked
├── rust-toolchain.toml       锁定 stable 的确切版本 1.99.1
├── rustfmt.toml              行宽 120，与项目一致
├── fixtures/                 Kotlin 编码器产出的小词典，由 Kotlin 测试钉住，见 6.3
├── sok/                      库：读取、校验、审计、规范化摘要
│   ├── src/
│   │   ├── lib.rs            #![forbid(unsafe_code)]
│   │   ├── layout.rs         按规范写成的常量与字段读取
│   │   ├── reader.rs         SokFile：对 &[u8] 的零拷贝视图
│   │   ├── utf8.rs           良构判定，独立实现，不移植 Kotlin 的 Utf8
│   │   ├── audit.rs          审计规则
│   │   └── digest.rs         规范化摘要
│   └── tests/                逐条规则的正反例
├── sokcheck/                 命令行
│   └── src/main.rs
└── fuzz/                     cargo-fuzz 目标，需要 nightly
    └── fuzz_targets/read.rs
```

### 3.1 工程要求

- 库不得因任何输入 panic：一切失败以 `Violation` 返回，长度与偏移的算术用 u64 或 checked 运算，release 配置也打开 `overflow-checks`；`#![forbid(unsafe_code)]` 与 `#![deny(missing_docs)]`；公开面只有只读访问；
- 命令行的退出码固定：0 为通过，1 为发现违规或不一致，2 为用法或 IO 错误；数据写 stdout，诊断写 stderr；输出确定，文件按名排序，格式逐字节可复现；
- 可复现：提交 `Cargo.lock`，每次调用都带 `--locked`；工具链锁定确切版本；edition 2024；
- 质量门：`rustfmt`，行宽 120；`clippy --all-targets -D warnings`；`cargo doc` 不得有警告；
- 测试：每条规则有正反例；摘要有已知答案测试；变异检查用 `cargo mutants`，存活的等价变异逐个说明理由；
- 语言：rustdoc 与注释用英文，与 KDoc 一致；设计与架构文档用中文。

依赖策略：`sok` 与 `sokcheck` 只用标准库，命令行参数手写解析，输出都是简单的文本格式，不引入 serde 或 clap；`fuzz` 只依赖 `libfuzzer-sys`。依赖越少，复核的对象就越纯粹，供应链与 CI 时长也越小。所有 `.rs` 文件与 Kotlin 源文件一样，以 `.idea/copyright/Sokkuri.xml` 定义的 Apache-2.0 头开篇。

## 4. 读取器与规则

`SokFile::parse(&[u8]) -> Result<SokFile, Violation>` 只做运行时也做的校验；通过之后的全部访问都是对原切片的借用，不复制。`Violation` 带一个稳定的规则编号与字节偏移，供 `sokcheck` 报告，并写入第 6 节的判定行。

### 4.1 接受规则，与运行时解码器一一对应

这一组决定“接受或拒绝”，必须与 `SokDictionary.decode` 的接受集合完全相同，否则对拍会报不一致。

| 编号 | 规则 | 对应的 Kotlin 检查 |
|---|---|---|
| V1 | 文件不短于 32 字节的头 | header |
| V2 | magic 为 `SOK1` | magic |
| V3 | formatVersion 为 1 | formatVersion |
| V4 | flags 与偏移 28 的保留字段都为 0 | flags、reserved |
| V5 | maxKeyLength 等于各键 UTF-16 长度的最大值（I1） | maxKeyLength |
| V6 | 由 entryCount 与两个 blob 大小推出的文件长度等于实际长度，以 u64 计算，不溢出 | entryCount |
| V7 | keyOffsets 首项为 0，严格递增，末项等于 keyBlobBytes | keyOffsets |
| V8 | 每个键各自是良构 UTF-8 | key |
| V9 | valueOffsets 首项为 0，严格递增，末项等于 valueBlobBytes | valueOffsets |
| V10 | 每个 valueCounts 不小于 1 | valueCounts |
| V11 | 每个条目的候选恰好铺满它的 valueOffsets 区间 | valueBlob |
| V12 | 每个键按码点严格大于前一个键，即有序且唯一（I2） | key，与前一个键比较 |

检查顺序不属于规范：两侧只比对接受与否，不比对首个失败的规则。V5 要读完全部键才能检查，V12 与 V8 在同一遍里进行。V4、V5、V7、V9 的现行内容与 V12 来自第 8 节的决定。

### 4.2 审计规则，规范要求而运行时不检查

`sokcheck audit` 在接受之后逐项检查。审计不影响接受判定，但写进判定行，第 6 节的 Kotlin 回放据此决定是否断言查回的候选。

| 编号 | 规则 | 不满足时运行时的表现 |
|---|---|---|
| A5 | 每个候选非空，且是良构 UTF-8 | 空候选输出空串；畸形字节逐字节降级为 U+FFFD |

A1 至 A4 原先也是审计规则，按第 8 节的决定移入了接受规则：A1 键有序且唯一成为 V12，A2 maxKeyLength 精确并入 V5，A3 偏移首项为 0 并入 V7 与 V9，A4 保留字段为 0 并入 V4。审计编号不复用。编码器保证 A5，全部 22 个打包词典必须审计通过，这是 P2 的验收条件之一。

### 4.3 良构 UTF-8 的独立实现

`utf8.rs` 按 RFC 3629 的字节区间表直接写成，不移植 `Utf8.isWellFormed` 的实现，以免把同一个误读复制两遍。它对全部一字节、二字节输入，以及每个首字节与各续字节位边界值的组合，与 Rust 标准库的 `std::str::from_utf8` 对拍；后者的严格程度与 RFC 3629 相同。

## 5. 规范化摘要

两个读取器能否拆出同一结构，要靠一个两边都能逐字节复现的摘要来判断。它定义在原始字节上，不经 UTF-8 解码：畸形的值在两端各自的降级方式不同，按解码后的字符串比较会把降级差异误报为结构差异。

摘要为 FNV-1a 64 位，即偏移基 `0xcbf29ce484222325`、素数 `0x100000001b3`，按顺序吸收以下字节流：

```
u32le entryCount
u32le maxKeyLength
对每个条目，按表中顺序：
    u32le keyByteLength, keyBytes
    u8    candidateCount
    对每个候选：u16le byteLength, bytes
```

输出为 16 位小写十六进制。选 FNV 而非 SHA-256，是因为 Kotlin common 的标准库没有密码学摘要，而这里只用于比对、不防篡改，几行代码就能在两边写得完全一致。字节流的定义是本文的规范部分，两侧的实现各自附带同一组已知答案测试。

## 6. 与 Kotlin 的对拍

Kotlin 侧需要拿到每个条目的原始字节区间。为此，`SokDictionary` 新增两个 `@SokkuriInternalApi` 的只读访问：`rawKey(index): ByteArray` 与 `rawCandidates(index): List<ByteArray>`，并以同样的方式公开 `entryCount`。它们只供测试使用，不在任何运行时路径上，也不进入发布的 ABI 检查范围（I5，R4）。

对拍分两条线，都复用项目已有的模式。

### 6.1 打包词典：门控测试

- 用 Rust 生成期望：`sokcheck expect --packaged sokkuri-runtime/src/commonMain/resources/dictionary`，每个文件输出一行：文件名、判定、entryCount、maxKeyLength、摘要、审计结果；
- `sokkuri-runtime` 的 jvmTest 新增 `PackagedSokConformanceTest`，从环境变量 `SOKKURI_SOK_EXPECT` 指向的文件读期望，对每个打包词典计算 Kotlin 侧的摘要并逐行比对；变量未设置时报告为跳过，与 `OPENCC_DIR` 门控的测试同一做法；
- 期望文件不提交，由 Gradle 现生成现比对，见 10.1：`:sokkuri-runtime:sokConformanceTest` 依赖生成期望的任务，并为测试进程设置该变量，所以它从不跳过。这样 dictgen 重新生成词典，即 bump pin 时，不需要 Rust。

### 6.2 模糊语料：提交后由三端回放

- 语料存于 `sokkuri-runtime/src/commonTest/resources/sok-conformance/corpus.hex`，每行一个输入，格式为“编号、制表符、十六进制字节”，编号取输入字节的 FNV 摘要；文本而非二进制，便于评审 diff，与 `fuzz-corpus.jsonl` 每行一个输入的做法一致；
- 期望存于同目录的 `expected.txt`，由 `sokcheck expect --corpus corpus.hex` 生成并提交。每行为编号与判定，判定是 `reject <规则编号>`，或 `accept <entryCount> <maxKeyLength> <摘要> <审计结果>`；
- `sokkuri-runtime` 的 commonTest 新增 `SokConformanceTest`，经 `readTestResource` 读取两个文件，在 JVM、Android host 与 iOS 上逐条回放。断言有四条：
  - 拒绝时只能抛 `SokkuriException.InvalidFormat`，其他任何异常都算失败；
  - 接受与拒绝必须与期望一致；
  - 接受时摘要必须一致；
  - 接受后要遍历全部条目，并对每个键调用 `matchExact` 与 `matchPrefix`，过程中不得抛出任何异常，以此覆盖“解码通过、首次访问越界”这一类缺陷；每个键都必须查回，即 `matchExact` 找到它、`matchPrefix` 的匹配长度等于它的长度，检索依赖的 V5 与 V12 已是接受规则；审计通过时，查回的候选还必须与存储的一致。
- 该测试随 `./gradlew build` 运行，不需要 Rust；改动语料或 Rust 读取器的人负责重新生成 `expected.txt`，CI 的 Rust job 以 `sokcheck expect --corpus corpus.hex --check expected.txt` 发现两者脱节，即 10.1 的 `checkCorpusExpectations`。

规则编号只写进期望文件供人阅读，Kotlin 侧不比对：它的异常消息以字段命名，与规则编号的对应关系不必固化。

### 6.3 合法样本：Kotlin 编码的夹具

Rust 的测试与模糊种子需要合法的小词典，而 Rust 侧不编码。`tools/sok-rs/fixtures/` 存放 Kotlin 编码器写出的几个小 `.sok`：前缀相互包含的 ASCII 键、多字节与增补平面的键、多候选与 U+FFFD 候选、空词典。`sokkuri-resource` 的 jvmTest 新增 `SokFixtureFilesTest`，把每个夹具与编码器当场的输出逐字节比较，编码器若有改动而夹具未更新即失败；以 `SOKKURI_WRITE_SOK_FIXTURES=1` 加 `--rerun` 运行时改为写出夹具。夹具因此始终是编码器的真实产物。

## 7. 模糊测试

依照 2026-10-15 的决定，模糊测试只在本地运行，CI 只回放已提交的语料，与 `upstream-diff fuzz` 相同。

- 目标 `read`：对任意字节调用 `SokFile::parse`；接受时遍历全部条目与候选、运行审计、计算摘要。不得 panic：安全 Rust 的切片越界会 panic，因此“接受之后的任何访问都在界内”由此得到检验。cargo-fuzz 需要 nightly 工具链，只在这一个目录使用，以 `rustup toolchain install nightly` 与 `cargo install cargo-fuzz` 安装；
- 种子：`fixtures/` 中的夹具，见 6.3，它们都是 Kotlin 编码器的产物；另有 `sok/tests/violations.rs` 的手写用例，各在 `prefixes.sok` 或 `empty.sok` 上改动几个字节，恰好违反一条接受规则，或只违反审计规则而仍被接受。Rust 侧仍不编码。手写用例由忽略的测试 `write_corpus_seeds` 写出，`importCorpusSeeds` 把它们并入语料；
- 流程：`./gradlew :tools:sok-rs:fuzz` 串起全部步骤。先并入手写种子，把 `corpus.hex` 导出为 `build/fuzz/corpus/read` 下的工作语料，再以夹具为额外种子运行 `cargo +nightly fuzz run read -- -max_len=512 -max_total_time=600`，时长可由 `-PfuzzSeconds` 改；然后 `cargo fuzz cmin` 精简，`sokcheck corpus import` 按编号去重后追加进 `corpus.hex`，最后 `sokcheck expect --corpus` 重写 `expected.txt`。之后照常运行 `./gradlew build`，Kotlin 与 Rust 的任何分歧都会在三端的回放中报出；
- 上限：单个输入不超过 512 字节，语料只增不删，目标在数百条以内；
- 覆盖率由 Rust 读取器引导。两侧实现的是同一组规则，探索到 Rust 分支的输入，大概率也会走到 Kotlin 对应的分支；这与 `upstream-diff fuzz` 由上游覆盖率引导、再回放给 Sokkuri 的思路相同。

手写用例不可省。模糊输入大多同时违反多条规则，Kotlin 侧又不比对规则编号，所以删掉解码器的某一条检查，只要还有别的检查拒绝同一输入，判定就不变，回放也就证明不了每条检查都在。逐条关掉 Kotlin 解码器的检查来验证：只用首次十分钟运行精简出的 125 条输入，V3 至 V11 的十二处检查只有五处被发现；并入手写用例后，V1 至 V11 的十四处发现了十二处。这一过程也补出两类用例：截在魔数之后、第 28 字节之前的文件，因为解码器不读保留字段，截在其后的文件会先被大小检查拒绝，V1 就无从体现；以及值区段末尾多出一个无人引用的字节，因为原有用例让末项越过区段，同时也破坏了铺满，V9 的末项检查同样无从体现；键区段补了对称的用例。

存活的两处是等价变异，不需要用例：V11 要求每个条目的候选恰好铺满它的值区段，而每个候选至少占两字节的长度前缀，于是在铺满的前提下，V10 的“候选数至少为 1”与 V9 的“值偏移严格递增”互相蕴含。关掉其中一条，另一条照样拒绝同一批文件，接受集合不变。

第 8 节收紧之后，原有语料里的接受输入大多变成了拒绝，接受的只剩 19 条，于是在新规则下重跑十分钟：约 5.26 亿次，没有 panic，精简后新增 72 条，语料共 236 条，其中接受 59 条、拒绝 177 条。收紧也补出一类手写用例：只比前一个键小、却比更早的键乃至之前整段键区都大的键。只有与紧邻的前一个键比较才能发现它，实现若把比较的起点误留在第一个键，其余用例都察觉不到。此时解码器共有十八处检查，回放发现其中十六处，存活的仍是 V9 递增与 V10 这对等价变异。

## 8. 规范澄清的决定

2026-10-16 逐条决定，全部采纳原先的建议。规则写进 `SokFormatLayout` 的 KDoc，Kotlin 解码器与 Rust 读取器在同一个提交里同步收紧，每条规则一个提交。

| 项 | 原状 | 决定 | 落地 |
|---|---|---|---|
| 键有序且唯一（原 A1） | 规范写了，编码器保证，解码器不查 | 解码器强制；运行时的正确性依赖它 | 新接受规则 V12，与 V8 在同一遍里比较相邻两键的字节 |
| maxKeyLength 精确（原 A2） | 解码器只查不大于 keyBlobBytes | 解码器强制等于各键 UTF-16 长度的最大值；偏小会静默地丢掉匹配 | 并入 V5。原来的上界被相等蕴含，随之删去 |
| 偏移首项为 0（原 A3） | 只要求严格递增 | 写进规范并由解码器强制，使字节布局唯一 | 并入 V7 与 V9 |
| 保留字段为 0（原 A4） | 规范写了，解码器不查 | 解码器强制，与 `flags` 一致，以免将来复用该字段时被误读 | 并入 V4 |
| 候选非空且良构（A5） | 编码器拒绝空候选，解码器宽容 | 维持只审计 | 文本词典允许空候选，见架构文档 10.4；值的宽容降级是有意保留的行为 |
| 含孤立代理项的键与候选 | 编码器经宽容的 `encodeToByteArray()` 编码 | 编码器拒绝，报出 `InvalidFormat` | 单独的修正提交，候选一并拒绝 |

原稿说孤立代理项被“静默替换为 U+FFFD”，并不准确。实测宽容编码在 JVM 上把它写成 `?`，即 0x3F，在 Kotlin/Native 上才是 U+FFFD，同一组条目会在两端编出不同的文件。排序与查重又都在编码之前按字符串进行，`"a\uD800"` 与 `"a\uDC00"` 在 JVM 上会编成同一个键，编码器自己就能写出违反 V12 的文件。候选同样会被平台相关地改写，所以一并拒绝。dictgen 从 UTF-8 文本读取词典，读不出孤立代理项，打包词典不受影响。

编号的处理：审计编号不复用，A1 至 A4 退役，审计只剩 A5；接受规则新增 V12，V4、V5、V7、V9 的内容扩大。检查顺序仍不属于规范，见 4.1。

代价，同机在 JVM 上实测。预热后的中位数：STPhrases 的解码由约 0.95 ms 增至约 1.23 ms，STCharacters 由约 45 µs 增至约 62 µs。使用者真正付出的是新进程里的首次解码，解码结果随后由 `DictionaryCache` 在进程内复用：STPhrases 由约 15.4 ms 增至约 16.3 ms，STCharacters 没有可分辨的变化。iOS 未单独测量。这个结果经过两处优化：只有字节长度超过当前最长键的键才计算 UTF-16 长度，因为 UTF-16 码元数不会超过 UTF-8 字节数，计数也只看首字节而不解码；比较相邻两键时沿用上一个键的起点，只在首个不同的字节上做无符号比较。不做这两处优化时，首次解码约为 17.6 ms。

影响面：收紧只会拒绝原本被接受的文件，全部 22 个打包词典都满足新规则。`Sokkuri.create` 固定使用 `defaultResourceLoader()`，运行时只读打包词典，使用者今天无法传入自己的 `.sok`，所以对外没有可见变化；影响要到 D12 开放自定义配置之后才会出现。词典与解码器同版本发布，不存在跨版本的兼容问题。

## 9. 架构文档的修订

实施的第一个提交先改 `docs/architecture.md`，此后的代码都在修订后的规则之内：

- §2.1 的模块结构图加入 `tools/sok-rs`，注明它是 Cargo 工作区，Gradle 只为它注册调用 cargo 的任务，不像其余 tools 那样编译 Kotlin；
- §2.2 的职责表新增一行。职责：`.sok` 规范的独立读取、审计与对拍语料。允许依赖：Rust 标准库，模糊测试另用 `libfuzzer-sys`。禁止：写 `.sok`、进入发布产物、挂入 Gradle 的 `check`；
- §5 扩展手册第 1 行，即新词典格式，追加：改动 `.sok` 布局须在同一组提交里同步 `SokFormatLayout`、Kotlin 编解码、Rust 读取器与对拍期望，`SokFormatLayout` 仍是唯一的规范；
- §9 新增 D19：`.sok` 的独立读取器与对拍，见本文；
- §10 新增 10.5，记录 CI 的接法与本地模糊测试的流程。

本地的 CLAUDE.md 同步补上命令与上述规则，并把其中 tools 模块都是同构建 JVM 应用的说法改为只约束 Gradle 模块。

## 10. Gradle 封装与持续集成

### 10.1 Gradle 任务

`tools/sok-rs` 在 `settings.gradle.kts` 中以 `:tools:sok-rs` 纳入构建，但不应用任何插件，因此没有 `build`、`check`、`test` 这类生命周期任务，`./gradlew build` 与 `check` 永远不会触及它。它只注册调用 cargo 的任务，Rust 代码仍由 cargo 构建：

| 任务 | 内容 | 阶段 |
|---|---|---|
| `:tools:sok-rs:cargoLint` | `cargo fmt --check`，`cargo clippy --all-targets --locked -D warnings`，`cargo doc` 无警告 | P0 |
| `:tools:sok-rs:cargoTest` | `cargo test --locked` | P0 |
| `:tools:sok-rs:verifyPackaged` | 对打包词典运行 `sokcheck verify`，P2 起加 `audit` | P1 |
| `:tools:sok-rs:expectPackaged` | 把打包词典的期望写到 `build/sok/packaged-expect.txt` | P1 |
| `:sokkuri-runtime:sokConformanceTest` | 以 jvm 测试的类与类路径运行 `PackagedSokConformanceTest`，依赖 `expectPackaged` 并设置 `SOKKURI_SOK_EXPECT` | P1 |
| `:tools:sok-rs:checkCorpusExpectations` | 由已提交的语料重新生成期望，与 `expected.txt` 逐字节比较 | P3 |
| `sokConformance` | 聚合以上全部；只有 `:tools:sok-rs` 定义它，故在根目录直接运行即可 | P1 起 |
| `:tools:sok-rs:cargoMutants` | `cargo mutants`；唯一的等价变异登记在 `.cargo/mutants.toml`，其余存活即失败。需另行安装，不在 `sokConformance` 中 | P1 |
| `:tools:sok-rs:importCorpusSeeds` | 运行忽略的测试 `write_corpus_seeds`，把手写用例并入 `corpus.hex`；之后须运行下一项 | P3 |
| `:tools:sok-rs:writeCorpusExpectations` | 由 `corpus.hex` 重写 `expected.txt` | P3 |
| `:tools:sok-rs:fuzz` | 第 7 节的整条流程；需要 nightly 与 cargo-fuzz，只在本地运行，不在 `sokConformance` 中 | P3 |

- cargo 的查找顺序为 `CARGO` 环境变量、`~/.cargo/bin/cargo`、`PATH`，在任务执行时解析；找不到时报错，写明这三处与 rustup 的安装方式。rustup 只把 PATH 写进 `~/.profile`，而 zsh 不读它，所以第二处不能省；
- 任务类型是脚本内定义的 `CargoTask`，经注入的 `ExecOperations` 调用，与根构建的 `OpenccCloneTask` 同一写法，兼容配置缓存；
- 只读任务不声明输出，每次都运行，cargo 自身是增量的；写期望的任务声明输出文件，供依赖它的测试任务追踪。
- 全部 cargo 任务经一个 `maxParallelUsages = 1` 的共享构建服务逐个执行。cargo 每次调用都会重新链接 `target/debug` 下的二进制，即使没有任何改动：实测一次空的 `cargo run` 就让 `target/debug/sokcheck` 换了 inode。cargo 的构建锁管不到执行二进制的那一刻，于是并行的 `cargo run` 会撞上正被替换的文件，`sokConformance` 实际出现过两种症状：No such file or directory，以及 macOS 上的 SIGKILL。cargo 在内部本就并行编译，逐个执行几乎不损失时间。

### 10.2 持续集成

在 `ci.yml` 新增 job `sok reader`，与 `Build gate` 并列，初期不设为必需检查，稳定之后再议。

- 运行环境为 `macos-latest`：`setup-build-env` 只支持 macOS，构建门禁也在这里跑过，换到 Linux 能省成本，但要另行证明这个含 iOS target 的工程在 Linux 上能配置，留作后续优化；
- 工具链：`setup-build-env` 新增输入 `rust`，默认关闭。开启时按 `rust-toolchain.toml` 的版本以 rustup 安装，只装 rustfmt 与 clippy 两个组件，并缓存 cargo 的注册表与 `target` 目录；JDK 与 Gradle 沿用现有设置，环境定义仍只有一处；
- 步骤只有一条：`./gradlew sokConformance`。各项检查都在 Gradle 任务里，本地与 CI 运行的是同一条命令；
- 不在 CI 里做模糊测试，nightly 工具链也不进 CI；
- P2 之后，`upstream.yml` 的演练 job 在重新生成词典后加跑 `:tools:sok-rs:verifyPackaged`，让 bump pin 产出的词典同样经过审计。

## 11. 实施阶段

每个阶段内部按项目惯例逐项提交。验收的标准都是“测试与变异检查”：Rust 侧用 `cargo mutants`，以 `cargo install cargo-mutants` 安装；Kotlin 侧沿用植入错误的做法。

| 阶段 | 内容 | 验收 |
|---|---|---|
| P0 | 修订架构文档；建立 Cargo 工作区、工具链锁定、许可证头与 `.gitignore`；`:tools:sok-rs` 与 `cargoLint`、`cargoTest` | 两个任务在骨架上通过；`./gradlew build` 不受影响，也不调用 cargo |
| P1 | 读取器与 V1 至 V11；`verify`、`dump`、`stats`、`expect`；夹具与 `SokFixtureFilesTest`；Kotlin 的原始访问与 `PackagedSokConformanceTest`；10.1 的 P1 任务与 CI job | 每条规则都有正反例；`SokDecoderValidationTest` 的每类损坏在 Rust 侧得到同一判定；22 个打包词典两侧摘要一致；CI 绿 |
| P2 | 审计规则 A1 至 A5；对第 8 节逐条做出决定，并按决定提交规范与解码器的改动 | 打包词典全部审计通过；每条收紧的规则在两侧都有反例 |
| P3 | 模糊目标、种子、语料导入；`corpus.hex` 与 `expected.txt`；`SokConformanceTest` 三端回放 | 本地十分钟运行后，语料在三端回放全部一致；对拍发现的任何分歧都已修复或登记 |
| P4 | 远期，不在本次范围：只读检索 API 与 mmap，作为原生内核的数据层 | 重议内核时单独立项 |

## 12. 风险与代价

- 维护两份读取器：格式改动必须两边同步。缓解：格式本身极少改动；§5 第 1 行写明同步义务；CI 的对拍会立即报出不同步；
- 工具链负担：贡献者构建与发布 Sokkuri 都不需要 Rust，只有改动 `tools/sok-rs`、语料或期望的人需要；nightly 只用于本地的模糊测试。`:tools:sok-rs` 虽然纳入了 Gradle 设置，但不应用插件，配置它不调用 cargo，不会拖慢或破坏没有 Rust 的构建；
- 规则漂移：Rust 读取器若与规范不一致，会让 `expected.txt` 与 Kotlin 一起“错得一致”。缓解：Rust 侧严格按规范写，不参照 Kotlin 源码；每条规则的正反例由人从规范推出，并随规范的 KDoc 一起评审；
- 摘要碰撞：FNV-1a 64 位不防恶意碰撞。这里只做比对，输入来自本项目自己的语料，可以接受。
