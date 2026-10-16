# 设计：`.sok` 的 Rust 独立读取器

**状态：设计稿，2026-10-16，未实施。** 本文提出 `tools/sok-rs`：一个按 `SokFormatLayout` 规范独立实现的 `.sok` 只读读取器，以及围绕它的审计、规范对拍与模糊测试。它是验证工具，不是发布产物；实施时按第 9 节修订 `docs/architecture.md`。

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
tools/sok-rs/                 Cargo 工作区，不属于 Gradle 构建
├── Cargo.toml                工作区清单
├── rust-toolchain.toml       锁定 stable 的确切版本，P0 时取 1.99.1
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
| V4 | flags 为 0 | flags |
| V5 | maxKeyLength 不大于 keyBlobBytes | maxKeyLength |
| V6 | 由 entryCount 与两个 blob 大小推出的文件长度等于实际长度，以 u64 计算，不溢出 | entryCount |
| V7 | keyOffsets 严格递增，末项等于 keyBlobBytes | keyOffsets |
| V8 | 每个键各自是良构 UTF-8 | key |
| V9 | valueOffsets 严格递增，末项等于 valueBlobBytes | valueOffsets |
| V10 | 每个 valueCounts 不小于 1 | valueCounts |
| V11 | 每个条目的候选恰好铺满它的 valueOffsets 区间 | valueBlob |

检查顺序不属于规范：两侧只比对接受与否，不比对首个失败的规则。

### 4.2 审计规则，规范要求而运行时不检查

`sokcheck audit` 在接受之后逐项检查。审计不影响接受判定，但写进判定行，第 6 节的 Kotlin 回放据此决定是否断言检索的正确性。

| 编号 | 规则 | 不满足时运行时的表现 |
|---|---|---|
| A1 | 键按码点严格递增，即有序且唯一（I2） | 二分查找静默地查错 |
| A2 | maxKeyLength 等于各键 UTF-16 长度的最大值（I1） | 偏小时长键静默地匹配不上；偏大时只多耗时间 |
| A3 | keyOffsets 与 valueOffsets 的首项为 0 | 无害，blob 开头夹着无人引用的字节 |
| A4 | 偏移 28 的保留字段为 0 | 无害，但将来的版本无法安全复用该字段 |
| A5 | 每个候选非空，且是良构 UTF-8 | 空候选输出空串；畸形字节逐字节降级为 U+FFFD |

编码器保证了 A1 至 A5。全部 22 个打包词典必须审计通过，这是 P2 的验收条件之一。

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
- 期望文件不提交，在 CI 的同一个 job 里现生成现比对。这样 dictgen 重新生成词典，即 bump pin 时，不需要 Rust。

### 6.2 模糊语料：提交后由三端回放

- 语料存于 `sokkuri-runtime/src/commonTest/resources/sok-conformance/corpus.hex`，每行一个输入，格式为“编号、制表符、十六进制字节”，编号取输入字节的 FNV 摘要；文本而非二进制，便于评审 diff，与 `fuzz-corpus.jsonl` 每行一个输入的做法一致；
- 期望存于同目录的 `expected.txt`，由 `sokcheck expect corpus.hex` 生成并提交。每行为编号与判定，判定是 `reject <规则编号>`，或 `accept <entryCount> <maxKeyLength> <摘要> <审计结果>`；
- `sokkuri-runtime` 的 commonTest 新增 `SokConformanceTest`，经 `readTestResource` 读取两个文件，在 JVM、Android host 与 iOS 上逐条回放。断言有四条：
  - 拒绝时只能抛 `SokkuriException.InvalidFormat`，其他任何异常都算失败；
  - 接受与拒绝必须与期望一致；
  - 接受时摘要必须一致；
  - 接受后要遍历全部条目，并对每个键调用 `matchExact` 与 `matchPrefix`，过程中不得抛出任何异常，以此覆盖“解码通过、首次访问越界”这一类缺陷；审计通过时，还要断言每个键都能查回自己的候选。
- 该测试随 `./gradlew build` 运行，不需要 Rust；改动语料或 Rust 读取器的人负责重新生成 `expected.txt`，CI 的 Rust job 负责发现两者脱节。

规则编号只写进期望文件供人阅读，Kotlin 侧不比对：它的异常消息以字段命名，与规则编号的对应关系不必固化。

## 7. 模糊测试

依照 2026-10-15 的决定，模糊测试只在本地运行，CI 只回放已提交的语料，与 `upstream-diff fuzz` 相同。

- 目标 `read`：对任意字节调用 `SokFile::parse`；接受时遍历全部条目与候选、运行审计、计算摘要。不得 panic：安全 Rust 的切片越界会 panic，因此“接受之后的任何访问都在界内”由此得到检验。cargo-fuzz 需要 nightly 工具链，只在这一个目录使用，以 `rustup toolchain install nightly` 与 `cargo install cargo-fuzz` 安装；
- 种子：Kotlin 编码器产出的小词典，加上 `SokDecoderValidationTest` 的各类损坏样本。种子由一个 jvmTest 级别的一次性入口写出，Rust 侧不编码；
- 流程：`cargo fuzz run read -- -max_len=512 -max_total_time=600`，再 `cargo fuzz cmin` 精简，然后 `sokcheck corpus import fuzz/corpus/read` 按编号去重后追加进 `corpus.hex`，最后 `sokcheck expect` 重新生成 `expected.txt`。之后照常运行 `./gradlew build`，Kotlin 与 Rust 的任何分歧都会在三端的回放中报出；
- 上限：单个输入不超过 512 字节，语料只增不删，目标在数百条以内；
- 覆盖率由 Rust 读取器引导。两侧实现的是同一组规则，探索到 Rust 分支的输入，大概率也会走到 Kotlin 对应的分支；这与 `upstream-diff fuzz` 由上游覆盖率引导、再回放给 Sokkuri 的思路相同。

## 8. 规范澄清的待决项

P2 结束时逐条决定，决定之后写进 `SokFormatLayout` 的 KDoc，需要时同步收紧 Kotlin 解码器，并把对应规则由审计移入接受规则。我的建议附在每条之后。

| 项 | 现状 | 建议 |
|---|---|---|
| 键有序且唯一（A1） | 规范写了，编码器保证，解码器不查 | 解码器强制。V8 已在逐键校验 UTF-8，相邻比较只是同一遍里多一次比较；运行时的正确性依赖它 |
| maxKeyLength 精确（A2） | 解码器只查上界 | 解码器强制等于各键 UTF-16 长度的最大值。同样可在逐键校验时顺带算出；偏小会静默地丢掉匹配 |
| 偏移首项为 0（A3） | 只要求严格递增 | 写进规范，并由解码器强制。代价为零，并使字节布局唯一 |
| 保留字段为 0（A4） | 规范写了，解码器不查 | 由解码器强制，与 `flags` 一致，以免将来复用该字段时旧文件被误读 |
| 候选非空且良构（A5） | 编码器拒绝空候选，解码器宽容 | 只做审计。文本词典现已允许空候选，见架构文档 10.4；值的宽容降级是有意保留的全序行为 |
| 孤立代理项的键 | 编码器静默替换为 U+FFFD | 编码器改为拒绝，报出 `InvalidFormat`；属于编码器修正，单独提交 |

收紧解码器只会拒绝原本被接受的文件。全部打包词典都满足这些规则，P2 的审计即可证明；对外的影响仅限于自行编码 `.sok` 的调用方，而 `.sok` 的编码器至今只在 `@SokkuriInternalApi` 之后。

## 9. 架构文档的修订

实施的第一个提交先改 `docs/architecture.md`，此后的代码都在修订后的规则之内：

- §2.1 的模块结构图加入 `tools/sok-rs`，注明它是 Cargo 工作区，不是其余 tools 那样的同构建 Gradle 模块；
- §2.2 的职责表新增一行。职责：`.sok` 规范的独立读取、审计与对拍语料。允许依赖：Rust 标准库，模糊测试另用 `libfuzzer-sys`。禁止：写 `.sok`、进入发布产物、挂入 Gradle 的 `check`；
- §5 扩展手册第 1 行，即新词典格式，追加：改动 `.sok` 布局须在同一组提交里同步 `SokFormatLayout`、Kotlin 编解码、Rust 读取器与对拍期望，`SokFormatLayout` 仍是唯一的规范；
- §9 新增 D19：`.sok` 的独立读取器与对拍，见本文；
- §10 新增 10.5，记录 CI 的接法与本地模糊测试的流程。

本地的 CLAUDE.md 同步补上命令与上述规则，并把其中 tools 模块都是同构建 JVM 应用的说法改为只约束 Gradle 模块。

## 10. 持续集成

在 `ci.yml` 新增 job `sok reader`，与 `Build gate` 并列，初期不设为必需检查，稳定之后再议。

- 运行环境优先用 `ubuntu-latest`，成本低于 macOS。Kotlin 侧的门控测试只跑 `sokkuri-runtime:jvmTest`，需要确认 Linux 上配置这个含 iOS target 的工程不会失败；若失败，退回 macOS；
- 工具链：用 `rust-toolchain.toml` 锁定版本；Rust 由 `setup-build-env` 新增的可选输入安装，JDK 与 Gradle 沿用现有设置，环境定义仍只有一处（10.2）；
- 步骤：
  1. `cargo fmt --check` 与 `cargo clippy -- -D warnings`；
  2. `cargo test`；
  3. 对打包词典运行 `sokcheck verify` 与 `sokcheck audit`；
  4. P3 起，对已提交的语料运行 `sokcheck expect`，结果须与 `expected.txt` 逐字节相同；
  5. 生成打包词典的期望，以 `SOKKURI_SOK_EXPECT` 运行 `PackagedSokConformanceTest`，并断言它没有被跳过，与 `upstream.yml` 断言门控测试确实运行的做法相同；
- 不在 CI 里做模糊测试，nightly 工具链也不进 CI。

## 11. 实施阶段

每个阶段内部按项目惯例逐项提交。验收的标准都是“测试与变异检查”：Rust 侧用 `cargo mutants`，以 `cargo install cargo-mutants` 安装；Kotlin 侧沿用植入错误的做法。

| 阶段 | 内容 | 验收 |
|---|---|---|
| P0 | 修订架构文档；建立 Cargo 工作区、工具链锁定、许可证头与 `.gitignore` | `cargo test` 在空骨架上通过；`./gradlew build` 不受影响 |
| P1 | 读取器与 V1 至 V11；`verify`、`dump`、`stats`、`expect --packaged`；Kotlin 的原始访问与 `PackagedSokConformanceTest`；CI job | 每条规则都有正反例；`SokDecoderValidationTest` 的每类损坏在 Rust 侧得到同一判定；22 个打包词典两侧摘要一致；CI 绿 |
| P2 | 审计规则 A1 至 A5；对第 8 节逐条做出决定，并按决定提交规范与解码器的改动 | 打包词典全部审计通过；每条收紧的规则在两侧都有反例 |
| P3 | 模糊目标、种子、语料导入；`corpus.hex` 与 `expected.txt`；`SokConformanceTest` 三端回放 | 本地十分钟运行后，语料在三端回放全部一致；对拍发现的任何分歧都已修复或登记 |
| P4 | 远期，不在本次范围：只读检索 API 与 mmap，作为原生内核的数据层 | 重议内核时单独立项 |

## 12. 风险与代价

- 维护两份读取器：格式改动必须两边同步。缓解：格式本身极少改动；§5 第 1 行写明同步义务；CI 的对拍会立即报出不同步；
- 工具链负担：贡献者构建与发布 Sokkuri 都不需要 Rust，只有改动 `tools/sok-rs`、语料或期望的人需要；nightly 只用于本地的模糊测试；
- 规则漂移：Rust 读取器若与规范不一致，会让 `expected.txt` 与 Kotlin 一起“错得一致”。缓解：Rust 侧严格按规范写，不参照 Kotlin 源码；每条规则的正反例由人从规范推出，并随规范的 KDoc 一起评审；
- 摘要碰撞：FNV-1a 64 位不防恶意碰撞。这里只做比对，输入来自本项目自己的语料，可以接受。
