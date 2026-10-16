# `.sok` 独立读取器

`tools/sok-rs` 按 `SokFormatLayout` 的规范，用 Rust 另写了一个只读的 `.sok` 读取器，与 Kotlin 解码器对同一输入比较判定与结构。它是验证工具，不发布，也不进 `build` 与 `check`。

## 为什么

编码器、解码器与规范出自同一处（D6）。规范漏写的规则，解码器也会漏掉，只靠一份实现查不出来。独立的第二种读法能暴露这类缺口。它已经查出过：解码器接受乱序的键与偏小的 maxKeyLength，结果是静默查错或丢失匹配；编码器在两个平台上把孤立代理项编成不同的字节。处理结果见下文“规范的收紧”。

## 组成

```
tools/sok-rs/
├── build.gradle.kts      调用 cargo 的 Gradle 任务
├── Cargo.toml, Cargo.lock, rust-toolchain.toml, rustfmt.toml
├── fixtures/             Kotlin 编码器写出的小词典
├── sok/                  库：读取与校验（reader）、审计（audit）、摘要（digest）、良构 UTF-8（utf8）
├── sokcheck/             命令行：verify、audit、dump、stats、expect、corpus
└── fuzz/                 libFuzzer 目标 read，需要 nightly
```

## 工程要求

- 任何输入都不能让库 panic：失败一律返回 `Violation`；长度与偏移用 u64 或带检查的运算，release 也打开 `overflow-checks`；禁止 `unsafe`；索引访问由 clippy 的 `indexing_slicing` 拦下。
- 只用标准库，命令行参数手写解析；模糊测试另用 `libfuzzer-sys`。
- 可复现：提交 `Cargo.lock`，cargo 调用都带 `--locked`；`rust-toolchain.toml` 锁定 Rust 版本。
- 质量门：rustfmt（行宽 120）、clippy `-D warnings`、`cargo doc` 无警告、`cargo mutants`。
- `sokcheck` 的退出码：0 通过，1 发现违规或不一致，2 用法或 IO 错误。数据写 stdout，诊断写 stderr，输出按文件名排序，逐字节可复现。

## 规则

### 接受规则

决定接受还是拒绝。必须与 Kotlin `SokDictionary.decode` 接受的集合完全相同。

| 编号 | 规则 |
|---|---|
| V1 | 文件不短于 32 字节的头 |
| V2 | magic 为 `SOK1` |
| V3 | formatVersion 为 1 |
| V4 | flags 与偏移 28 的保留字段都为 0 |
| V5 | maxKeyLength 等于最长键的 UTF-16 长度（I1） |
| V6 | 由 entryCount 与两个 blob 大小推出的文件长度等于实际长度（以 u64 计算） |
| V7 | keyOffsets 从 0 开始，严格递增，最后一项等于 keyBlobBytes |
| V8 | 每个键单独是良构 UTF-8 |
| V9 | valueOffsets 从 0 开始，严格递增，最后一项等于 valueBlobBytes |
| V10 | 每个条目至少有一个候选 |
| V11 | 每个条目的候选恰好铺满它的值区间 |
| V12 | 每个键按码点严格大于前一个键，即有序且不重复（I2） |

检查顺序不属于规范：两侧只比较接受与否，不比较先报哪条规则。

### 审计规则

接受之后再检查，不影响判定，只写进期望行。

| 编号 | 规则 | 不满足时运行时的表现 |
|---|---|---|
| A5 | 每个候选非空，且是良构 UTF-8 | 空候选输出空串；畸形字节降级为 U+FFFD |

A1–A4 已经退役，编号不再使用：它们已成为接受规则，见下一节。编码器保证 A5，打包的 22 个词典都必须通过审计。

### 良构 UTF-8

`utf8.rs` 按 RFC 3629 的字节区间表直接写成，不照搬 Kotlin 的 `Utf8`，以免同一个误读出现两次。它在全部一字节、二字节输入，以及各首字节与续字节边界值的组合上，与 Rust 标准库的 `std::str::from_utf8` 对比。

## 规范的收紧（2026-10-16）

审计发现的四条“规范写了、解码器没查”的规则，改由解码器强制，Kotlin 与 Rust 在同一个提交里一起改：

| 原审计项 | 现在 | 不强制时的后果 |
|---|---|---|
| A1 键有序且不重复 | V12 | 二分查找静默查错 |
| A2 maxKeyLength 精确 | 并入 V5（原来只要求不超过 keyBlobBytes） | 偏小时长键静默匹配不上 |
| A3 偏移表从 0 开始 | 并入 V7、V9 | blob 开头可以夹带无人引用的字节，同一词典有多种字节表示 |
| A4 保留字段为 0 | 并入 V4 | 将来启用这个字段时，旧解码器会悄悄读错 |

A5 保持只审计：文本词典按上游语义允许空候选，值的宽容解码也是有意保留的行为。

同时修正编码器：含孤立代理项的键与候选改为报 `InvalidFormat`。原来宽容的 UTF-8 编码在 JVM 上写成 `?`、在 Kotlin/Native 上写成 U+FFFD，同一输入在两端编出不同的文件；`"a\uD800"` 与 `"a\uDC00"` 在 JVM 上还会变成同一个键。

代价（JVM）：新进程里 STPhrases 的首次解码由约 15.4 ms 增至约 16.3 ms，之后进程内缓存。使用者看不到变化：运行时只读打包的词典，它们都满足新规则。

## 摘要

判断两个读取器是否拆出同一结构，靠一个两边都能逐字节复现的摘要。它定义在原始字节上，不经 UTF-8 解码，避免把两边不同的降级方式误报为结构差异。

摘要是 FNV-1a 64（偏移基 `0xcbf29ce484222325`，素数 `0x100000001b3`），按顺序吸收：

```
u32le entryCount
u32le maxKeyLength
每个条目，按表中顺序：
    u32le keyByteLength, keyBytes
    u8    candidateCount
    每个候选：u16le byteLength, bytes
```

输出 16 位小写十六进制。用 FNV 而不用 SHA-256，是因为 Kotlin common 没有密码学摘要，而这里只做比较、不防篡改。夹具的摘要另用 Python 独立算出，在两端写死。

## 对拍

Kotlin 侧通过 `SokDictionary` 的 `entryCount`、`rawKey`、`rawCandidates` 读取原始字节。它们标注 `@SokkuriInternalApi`，只供测试使用。

### 打包词典

- `:tools:sok-rs:expectPackaged` 用 `sokcheck expect --packaged` 写出 22 个打包词典的期望。
- `:sokkuri-runtime:sokConformanceTest` 依赖它，并把期望文件的路径通过 `SOKKURI_SOK_EXPECT` 交给 `PackagedSokConformanceTest`，所以这个任务从不跳过。测试逐个比较判定与摘要，要求审计通过，并查回每个键。
- 普通的 `jvmTest` 没有这个变量，该测试报告为 skipped。期望不提交，dictgen 重新生成词典时不需要 Rust。

### 期望行

以 Tab 分隔，多余的尾部字段忽略：

```
<名称>  accept  <entryCount>  <maxKeyLength>  <摘要>  audit-ok | audit:A5
<名称>  reject  <规则编号>
```

规则编号只给人看，Kotlin 侧不比较。

### 回放语料

- `sokkuri-runtime/src/commonTest/resources/sok-conformance/corpus.hex`：每行一个输入，格式为“编号、Tab、十六进制字节”，编号是输入的 FNV-1a 64，读取时校验，手改的行会被拒绝。只由 `sokcheck corpus import` 写入，只增不删，单个输入不超过 512 字节。
- 同目录的 `expected.txt`：由 `sokcheck expect --corpus` 生成并提交。
- `SokConformanceTest` 在三端用 Kotlin 解码器回放，随 `./gradlew build` 运行，不需要 Rust。对每个输入：判定必须一致，拒绝只能抛 `InvalidFormat`；接受时摘要一致，每个键都能用 `matchExact` 与 `matchPrefix` 查回且不抛异常；审计通过时，查回的候选也要一致。
- 改了语料或 Rust 读取器的人负责重写 `expected.txt`；`checkCorpusExpectations` 会发现两者脱节。

### 夹具

`tools/sok-rs/fixtures/` 是 Kotlin 编码器写出的四个小词典（前缀相互包含、多字节与增补平面、多候选、空词典）。`SokFixtureFilesTest` 把它们与编码器的当前输出逐字节比较；用 `SOKKURI_WRITE_SOK_FIXTURES=1` 加 `--rerun` 运行可重新写出。Rust 侧不编码，合法样本只取自这里。

## 手写用例

模糊测试的输入大多同时违反好几条规则。Kotlin 侧不比较规则编号，删掉解码器的某一条检查，只要还有别的检查拒绝同一输入，回放结果就不变。所以只靠模糊语料，证明不了每条检查都还在：实测在 Kotlin 解码器里逐条关掉检查，最初的 125 条模糊输入只发现 V3–V11 十二处中的五处。

`sok/tests/violations.rs` 的 `cases()` 是一组手写用例，每个只改合法夹具的几个字节，恰好违反一条接受规则；另有一组只违反审计规则、仍被接受的用例。忽略的测试 `write_corpus_seeds` 把它们写成种子，`importCorpusSeeds` 并入语料。现有 35 个。

现在关掉解码器的 18 处检查，回放能发现 16 处。剩下的两处不需要用例：V11 要求候选恰好铺满值区间，而每个候选至少占 2 字节的长度前缀，所以“候选数至少为 1”（V10）与“值偏移严格递增”（V9 的递增部分）在 V11 之下互相蕴含，关掉任何一处，另一处照样拒绝同样的文件。

## 模糊测试

只在本地运行（2026-10-15 的决定），CI 只回放已提交的语料。

- 目标 `read` 对任意字节调用 `SokFile::parse`；接受时遍历全部条目与候选，运行审计，计算摘要。安全 Rust 的越界访问会 panic，所以“接受之后的访问都在界内”也由它检验。
- `./gradlew :tools:sok-rs:fuzz` 串起整个流程：并入手写种子 → 导出语料 → `cargo +nightly fuzz run`（默认 600 秒，可用 `-PfuzzSeconds` 改）→ `cargo fuzz cmin` 精简 → 导入 `corpus.hex` → 重写 `expected.txt`。需要 nightly 工具链与 cargo-fuzz。
- 记录：2026-10-16 运行两次，各十分钟，约 5.07 亿次与 5.26 亿次（后一次在规范收紧之后），都没有 panic，回放也没有发现分歧。语料现有 236 条，接受 59 条、拒绝 177 条。

## Gradle 任务

| 任务 | 内容 | 在 `sokConformance` 中 |
|---|---|---|
| `cargoLint` | rustfmt、clippy、rustdoc | 是 |
| `cargoTest` | `cargo test` | 是 |
| `verifyPackaged` | 对打包词典运行 `sokcheck audit` | 是 |
| `expectPackaged` | 写出打包词典的期望 | 是（经 `sokConformanceTest`） |
| `:sokkuri-runtime:sokConformanceTest` | 运行 `PackagedSokConformanceTest` | 是 |
| `checkCorpusExpectations` | 核对 `expected.txt` 仍是 Rust 读取器的结论 | 是 |
| `cargoMutants` | `cargo mutants`；唯一的等价变异登记在 `.cargo/mutants.toml`，其余存活即失败 | 否，需另装 cargo-mutants |
| `importCorpusSeeds` | 把手写用例并入语料，之后运行 `writeCorpusExpectations` | 否 |
| `writeCorpusExpectations` | 重写 `expected.txt` | 否 |
| `fuzz` | 上面的模糊测试流程 | 否 |

- 统一入口 `./gradlew sokConformance`；CI 的 `sok reader` 任务运行同一条命令。
- cargo 按 `CARGO`、`~/.cargo/bin/cargo`、`PATH` 的顺序查找（rustup 只把 PATH 写进 `~/.profile`，zsh 不读它）。
- 所有 cargo 任务逐个执行（共享构建服务，`maxParallelUsages = 1`）：每次调用 cargo 都会重新链接 `target/debug` 下的二进制，即使没有改动；并行的 `cargo run` 会撞上正在被替换的文件，实际出现过 No such file or directory 与 macOS 上的 SIGKILL。
