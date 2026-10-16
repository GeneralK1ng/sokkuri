# 模块

## 结构

```
sokkuri-runtime    门面 Sokkuri、平台资源加载器、打包的词典与配置
└── sokkuri-resource  唯一做 IO 的层：ResourceLoader、DictionaryFormat 注册表、进程级词典缓存、SokkuriLock、.sok 编解码
    └── sokkuri-config  OpenCC 配置语义：JSONC 解码、组装 Converter；不做 IO，经 Provider 接口取词典
        └── sokkuri-engine  转换核心：Utf 与 IDS、Dictionary、Conversion、ConversionChain、分词、Converter
            └── sokkuri-api   公开类型：SokkuriConfig、SokkuriOptions、Inspection、异常、@SokkuriInternalApi

tools:dictgen        词典编译器
tools:benchmark      性能基准
tools:upstream-diff  与上游的比对和性能参照
tools/sok-rs         .sok 的 Rust 独立读取器（Cargo 工作区）
```

使用者只依赖 `sokkuri-runtime`，其余模块经传递引入。tools 都不发布。

## 职责与依赖

| 模块 | 职责 | 允许依赖 | 禁止 |
|---|---|---|---|
| `sokkuri-api` | 使用者需要知道的全部类型 | 无 | IO、平台 API、内部原语 |
| `sokkuri-engine` | 转换算法，纯 Kotlin | api | IO、序列化库、平台 API |
| `sokkuri-config` | 配置文档模型与组装 | api、engine、kotlinx-serialization | IO；对外只经 `DictionaryProvider` 与 `SegmentationProvider` |
| `sokkuri-resource` | 读字节、解码格式、缓存 | api、engine、config | 转换语义；理解配置文档的结构 |
| `sokkuri-runtime` | `Sokkuri` 门面、平台加载器 | 其余全部 | 转换与解析逻辑，只做组装 |
| `tools:dictgen` | 上游数据 → `.sok` 与改写后的配置 | 主构建的模块 | 发布；另写一份格式实现 |
| `tools:benchmark` | 走使用者路径的性能测量 | runtime、resource | 发布；依赖 engine 或 config；进 `check` |
| `tools:upstream-diff` | 与上游比对执行、inspect 与配置；上游性能参照 | runtime；配置比对另用 config、resource | 发布；进 `check` |
| `tools/sok-rs` | `.sok` 规范的独立读取、审计与对拍语料 | Rust 标准库；模糊测试用 `libfuzzer-sys` | 写 `.sok`；发布；进 `build` 或 `check` |

engine 与 config 的依赖都用 `api()`，因为它们的类型出现在上层的公开签名里（R2）。

## 工具模块

- `tools:dictgen` 把上游词典与配置编译成打包资源：16 个配置、22 个 `.sok`。`run` 生成，`checkDictionaries` 检查漂移，输出确定。它必须用 `include()` 纳入同一构建，而不是 `includeBuild`，因为它要复用 `sokkuri-resource` 的 `.sok` 编码器。
- `tools:benchmark` 测量转换、建档、解码与 inspect 的耗时，只走使用者的公开路径。
- `tools:upstream-diff` 以 OpenCC 参考构建为准做比对，见 [upstream-diff.md](../verification/upstream-diff.md)。配置比对要直接调用 `ConfigParser`：在 D12 落地之前，使用者无法让 Sokkuri 加载任意配置。
- `tools/sok-rs` 见 [sok-reader.md](../verification/sok-reader.md)。Gradle 只为它注册调用 cargo 的任务、不应用插件，所以 `build` 与 `check` 不碰它，没有 Rust 也能构建和发布。它只读不写，编码器仍只有一份（D6）。

## 法则

- **R1** 依赖严格向下，下层不知道上层存在。
- **R2** 公开签名里出现的类型，其所在模块必须用 `api()` 依赖，这样发布的 POM 才正确。
- **R3** 只有 resource 碰字节：engine 与 config 不做 IO；读文件经 `ResourceLoader`，加载词典经 `DictionaryProvider`。
- **R4** 跨模块使用的公开声明标注 `@SokkuriInternalApi`；`sokkuri-api` 只放公开类型，不放内部原语。
- **R5** expect/actual 只有两处：runtime 的 `defaultResourceLoader()` 与 resource 的 `SokkuriLock`。新增平台只为这两处补 actual，不新增层，也不在其他模块加 actual。
- **R6** engine 与 config 中每个移植的类，在 KDoc 写明对应的 OpenCC 组件。改行为前先读上游源码，不凭记忆。
- **R7** 与上游冲突时默认对齐。确需偏离的，登记进 [divergences.md](divergences.md) 并写明理由，不许悄悄不同。
- **R8** 配置的宽容度与上游完全相同：只允许注释与尾随逗号（rapidjson 的 `kParseCommentsFlag | kParseTrailingCommasFlag`），未知的键忽略，未知的分词类型报错，不开 `isLenient`。成员的读法也照上游：
  - 重复的成员取第一个，由 `JsonStructure` 在解码前删去其余；
  - `name` 不是字符串时忽略；
  - 转换列表里不是对象的成员跳过；
  - inline 词典的重复键，只在上游会加载这个词典时才报错。
