# Sokkuri 架构设计文档

版本：v1 · 状态：宪法文件 · 适用范围：`com.generalk1ng.sokkuri` 全部模块

> 本文档是 Sokkuri 的架构宪法：模块边界、依赖方向、扩展方式与不变量以本文为准。修改本文档即架构变更，须先于对应代码改动进行讨论。
> 与 OpenCC 上游的关系：行为对齐是目标，本文第 7 章登记的偏离是已决设计，不因"上游如此"而回退。

## 1. 目标与非目标

**目标**

- 行为对齐 OpenCC C++ 核心：同一转换管线、同一词典语义、同一 IDS 表意文字描述序列处理；
- 提供 OpenCC 没有的差异化能力：`inspect()` 分级转换结果；
- Kotlin Multiplatform，首发 JVM、Android、iOS 三端，覆盖 iosArm64 与 iosSimulatorArm64 两个 Apple target；库规范从零成立，遵守 `explicitApi`、语义化版本与二进制兼容意识。

**非目标**

以下各项 v1 不实现，但架构必须允许后续添加，预留位置见第 8 章。

| 项                  | 性质                      | 预留位置                                                            |
|---------------------|---------------------------|---------------------------------------------------------------------|
| ocd / ocd2 词典读取 | 字节序与字宽不可移植      | 注册表保留类型名，报精确错误                                         |
| jieba 及插件分词    | 范围控制                  | `SegmentationProvider` 注册表缝                                     |
| `*_seal` 配置档     | 纯数据                    | `SokkuriConfig` 枚举加法 + dictgen                                  |
| 流式 chunked 转换   | 范围控制                  | `Converter` 纯函数接口之上的包装                                    |
| String view 匹配    | Kotlin 无通用零拷贝视图   | `Dictionary` internal 接口演进，sink 式 `matchAppend` 已实现         |
| 配置 schema 校验    | 上游仅警告，净效应有限     | 注入式 `ConfigValidator` 缝                                         |

## 2. 模块边界与依赖法则

### 2.1 模块结构

```
sokkuri-runtime    聚合门面 + 平台资源加载器 actual + 打包词典数据
└── sokkuri-resource  唯一字节感知层:ResourceLoader、DictionaryFormat 注册表、进程级共享缓存、SokkuriLock
    └── sokkuri-config  OpenCC 配置语义:JSONC 解码、Converter 组装;零 IO,经 Provider 缝落地
        └── sokkuri-engine  纯转换核心:Utf 与 IDS、Dictionary、Conversion、ConversionChain、Segmentation、Converter
            └── sokkuri-api   稳定公开面:SokkuriConfig、SokkuriOptions、Inspection、异常体系、@SokkuriInternalApi

tools:dictgen      词典编译器;同构建的普通模块,非发布产物
tools:benchmark    基准工具;同构建的普通模块,非发布产物
```

`sokkuri-resource` 注册表内的 `.sok` 编解码读写同侧，`DictionaryFormat` 注册表同时承载编码器与解码器。

`tools:dictgen` 把上游词典与配置编译为打包资源：上游 `data/dictionary` 转为 `.sok`，上游 `config` 经重写后落入 `sokkuri-runtime`，产出 16 个配置与 22 个 `.sok`。`:tools:dictgen:run` 负责生成，`:tools:dictgen:checkDictionaries` 负责漂移校验，两者输出均为确定性结果。`.sok` 的字节级规范以 `SokFormatLayout` 为唯一事实源。该模块必须是同构建的普通模块，不能改为 `includeBuild`，因为被包含的构建无法依赖主构建的工程，而它需要共享 `.sok` 格式编解码。

`tools:benchmark` 测量转换吞吐、装配延迟、词典解码与 inspect 开销，只走消费视角，依赖白名单限定为 `runtime` 与 `resource`，禁止依赖 engine 与 config，且不挂入 `check`。

### 2.2 模块职责与允许依赖

| 模块               | 职责                                   | 允许依赖                                               | 禁止                                                      |
|--------------------|----------------------------------------|--------------------------------------------------------|-----------------------------------------------------------|
| `sokkuri-api`      | 消费者需要知道的全部类型               | 无                                                     | IO、平台 API、internal 原语；本平台原语已于骨架期移出      |
| `sokkuri-engine`   | 转换算法，无 IO 纯 Kotlin               | `sokkuri-api`（api）                                     | IO、序列化库、平台 API                                    |
| `sokkuri-config`   | 配置文档模型与组装语义                 | api、engine（均为 api，因签名泄漏）、kotlinx-serialization | IO；一切出界须经 `DictionaryProvider` 或 `SegmentationProvider` |
| `sokkuri-resource` | 字节加载、格式解码、缓存               | api、engine、config（均为 api）                          | 转换语义，不理解 config 文档结构                           |
| `sokkuri-runtime`  | `Sokkuri` 门面、平台 loader            | api（api）、其余（implementation）                         | 转换与解析逻辑，只做组装                                   |
| `tools:dictgen`    | 上游数据 → `.sok` + 重写配置           | 依赖主构建模块，必须是同构建普通模块                    | 发布、持有独立格式实现                                    |
| `tools:benchmark`  | 性能测量：公开路径端到端与格式解码对照 | runtime、resource（白名单）                              | 发布、依赖 engine 或 config、测量进 check                 |

### 2.3 法则

- **R1 依赖方向严格向下**，下层不得感知上层存在。
- **R2 签名泄漏必须用 `api()` 声明**：公开签名中出现的任何类型，其模块必须是 `api` 依赖，保证发布 POM 正确。
- **R3 字节只经 resource**：engine 与 config 零 IO；一切文件与资源读取经 `ResourceLoader`，一切词典落地经 `DictionaryProvider` 缝。
- **R4 internal 法则**：跨层公开声明必须标注 `@SokkuriInternalApi`；`sokkuri-api` 内不得出现 internal 原语，它是纯公开类型层。
- **R5 expect/actual 只允许存在于两处**：`sokkuri-runtime` 的 `defaultResourceLoader()` 与 `sokkuri-resource` 的 `SokkuriLock`。**新增平台 = 为这两处补 actual，不得新增层、不得在上层新增 actual。**
- **R6 可追溯法则**：engine 与 config 中每个移植类须在 KDoc 标注对应 OpenCC 组件名；改行为前先读上游源码，不按记忆改。
- **R7 对齐优先，偏离登记**：与上游行为冲突时默认对齐；确有理由偏离的，登记入第 7 章并说明理由，禁止静默不同。
- **R8 配置宽容度严格等于上游**：等价于 `kParseCommentsFlag | kParseTrailingCommasFlag` 的集合，不更宽也不更窄；`isLenient` 已移除，未知键忽略，未知分词类型报错。

## 3. 管线模型

### 3.1 Converter 组合

engine 定义封闭接口 `Converter`，对齐上游 `Converter` 抽象：

```kotlin
sealed interface Converter {
    fun convert(input: String): String
    fun inspect(input: String): Inspection

    class SingleStage(segmentation: Segmentation?, chain: ConversionChain) : Converter
    class Normalizing(normalization: Converter, main: Converter) : Converter // 配置声明 normalization 时
    // 未来:Pipeline(stages: List<Converter>) —— 接口预留,实现后补
}
```

`SingleStage` 对应上游 `SingleStageConverter`，语义为分词可选、随后每段过链。

`Normalizing` 对应上游 `ConfigBasedConverter`，语义为 normalization 链对整输入预转换后交给主 converter；`inspect` 委托两段并合并表达，见 3.2。

组装职责在 config 层的 `ConfigParser`，engine 不做配置语义。

### 3.2 数据流

```
input ──▶ normalization?(整输入转换链) ──▶ segmentation?(mmseg)
       ──▶ 逐段 ConversionChain(最长前缀替换;IDS 原子;不可起始字符
           批量跳过)──▶ output
```

`ConversionChain` 的语义是阶段 N 的输出喂给 N+1；空链为恒等；中间阶段缓冲，末段直写输出，对齐上游 `AppendConvertedSegment`。

批量跳过只跨过确定无法作为任何词典键首字的字符，且必须同时停在 IDS 运算符上。IDS 的分组不查词典，跳过运算符会使其操作数被当作普通文本转换，故扫描候选集合为键首字与 arity 非零的 IDS 运算符之并，与上游 `Utf8SkipScan::Finalize` 一致。

### 3.3 Inspection 模型

平铺前置段，不用上游的递归 `pipelineStages`，这是为了贴合 inspect 作为调试与工具设施的差异化定位；未来 `Pipeline` 落地时再泛化为树。

```kotlin
class Inspection(
    val input: String,
    val normalizationStages: List<Stage>,  // 无 normalization 时为空列表
    val segments: List<String>,            // 分词结果(normalization 之后)
    val stages: List<Stage>,               // 主链,index 从 1 开始
    val output: String,
)
```

## 4. 词典体系

### 4.1 Dictionary 接口

```kotlin
interface Dictionary {
    val maxKeyLength: Int                                        // UTF-16 码元
    fun matchPrefix(text: CharArray, start: Int, end: Int): PrefixMatch?
    fun matchExact(key: String): DictionaryEntry?                // 候选枚举等非热路径
    fun mayStartKey(codePoint: Int): Boolean                     // 批量跳过优化的依据
    fun matchAppend(text: CharArray, start: Int, end: Int, out: StringBuilder): Int  // 默认实现即 matchPrefix 加写出
}
```

设计立场来自上游的教训：上游 `Dict` 已膨胀至 9 个方法。Sokkuri 保持数据结构接口精简，匹配策略与数据结构分离；批量跳过等策略由 `Conversion` 与 `MaxMatchSegmentation` 消费 `mayStartKey` 实现，`Dictionary` 实现只需如实回答自己的键首字集合。

**禁止**向 `Dictionary` 添加与数据无关的策略方法；新匹配能力优先实现为基于现有方法的纯函数。

`matchPrefix` 的结果只携带命中长度与默认候选，不携带 key，理由见 8.1。`Dictionary`、`DictionaryEntry` 与 `PrefixMatch` 均标注 `@SokkuriInternalApi`。它们是 public 而非 Kotlin `internal`，因为 `sokkuri-resource` 需要跨模块实现；`@SokkuriInternalApi` 以 opt-in 警告标示其非稳定地位，故可在 minor 版本演进。

### 4.2 词典实现谱系

- `SortedListDictionary` 对应上游 `TextDict`，以有序数组加二分实现，是 inline 词典的参照实现，与 `.sok` 共享 `SortedTableRetrieval` 检索算法；
- `.sok` 是生产格式，由 `SokDictionary` 实现，零拷贝保留文件字节、按键与值偏移按需解码 UTF-8，检索与 `SortedListDictionary` 同算法，注册为 `"sok"` 类型；编解码读写同侧，decoder 与 encoder 同放 `sokkuri-resource`，encoder 经 `@SokkuriInternalApi` 跨模块公开给 dictgen，`tools:dictgen` 据此编译全部打包词典，格式单一事实源，两端永不腐化；
- `ocd` 与 `ocd2` 以 `UnsupportedDictionaryFormat` 注册，报精确且可行动的错误。

### 4.3 缓存语义

缓存为进程级、强引用、共享，key 为加载器标识加 `type:file`。本节各项均为有意偏离上游，已登记。

- 上游使用 static `weak_ptr` 并以 mtime 与 size 失效，那是桌面工具读取可变文件的语义；Sokkuri 的词典是打包资源、不可变，无需失效；
- KMP common 没有统一弱引用，跨平台弱缓存无法干净实现；
- 强引用共享使多个 converter 天然共享词典内存，正是移动端所需；
- 加载器标识取加载器实例身份，首次登记时分配 scope。平台默认加载器为单例，故同平台全部 `Sokkuri.create` 共享同一 scope；不同实例永不共享，从而为未来有状态的自定义 loader 保证隔离。
- 身份登记表由 loader 映射到 scope，**只增不减且持强引用**；scope 的稳定性依赖该表仍持有 loader，因为 KMP 无弱引用，表才是持有者。故真实上界是曾传入过的 loader 实例数，而非存活数；当前成立只因平台默认 loader 是单例，恒为 1 条。
- 违反前提的后果，扩展手册 #12 落地时必须先解决：调用方每次 create 新建 loader，则登记表与 `cache` 双双无界增长，每个 loader 各自解出一整套 MB 级词典并永久驻留，身份扫描亦退化为线性。处理方式二选一：给登记表定界，或改用以加载器自报稳定标识的键。

## 5. 扩展手册：后续新功能怎么改

> 每行给出改动位置与**明确禁止**触碰的部分。拿不准时回到第 2.3 节的法则。

| #  | 场景                                | 改动位置                                                                                                                                                               | 禁止                                  |
|----|-----------------------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------|-------------------------------------|
| 1  | 新词典格式（`.sok` 及其他）                 | resource：新 `Dictionary` 实现 + `DictionaryFormat`，注册 `DefaultDictionaryFormats`；编码器放同模块 internal                                                                     | engine、config 改动；在 dictgen 另写编码器    |
| 2  | 新分词器（jieba 类）                     | engine：实现 `Segmentation`；config：`SegmentationProvider` 注册表接入（8.3）；用户经 `Sokkuri.create` 高级重载注入                                                                      | `Conversion`、热路径改动                  |
| 3  | 新配置字段                             | config：`ConfigDocument` 加字段 + `ConfigParser` 消费；上游语义优先；未知键继续忽略                                                                                                     | 改变已知键的宽容度（R8）                       |
| 4  | 新内置 profile（seal 类）               | api：`SokkuriConfig` 枚举加法（遵守 I8）；dictgen：词典 + 重写配置进 runtime 资源；README 与第 7 章登记                                                                                      | 重排或删除枚举值                            |
| 5  | 新 SokkuriOptions                  | api：`SokkuriOptions.Builder` 加 `var`；默认值对齐上游或登记偏离                                                                                                                  | 构造函数加法，破坏 ABI                       |
| 6  | 新转换能力（candidates 与 ambiguities 类） | engine：基于 `Dictionary.matchExact` 的纯函数；api：新类型；runtime：`Sokkuri` 委托                                                                                                | `Dictionary` 接口膨胀（4.1）              |
| 7  | 新平台 target（macOS 与 watchOS 等）     | 各模块 `build.gradle.kts` 加 target；resource：`SokkuriLock` actual，可抽 appleMain；runtime：手工接线 source set + loader actual                                                | 新增层；api、engine、config 出现 actual（R5） |
| 8  | 流式 chunked 转换                     | runtime 之上加有状态窗口包装（8.5）；engine `Converter` 纯函数接口不动                                                                                                                 | 在 `Converter` 接口引入流式方法              |
| 9  | `Inspection` 演进                   | 结构调整等于 api 破坏性变更，按 major 版本管理；优先加而不改                                                                                                                                | minor 版本改公开签名                       |
| 10 | String view 匹配                    | **已实现**：`Dictionary` 加 sink 式 `matchAppend`，默认实现等于旧行为；检索共享扫描双出口（internal，自由）                                                                                   | 破坏 `matchPrefix` 既有语义（默认实现与契约套件守门）  |
| 11 | schema 校验                         | 按 8.2 注入 `ConfigValidator`，仅警告                                                                                                                              | 改为硬失败，偏离上游语义                       |
| 12 | 自定义资源加载                           | runtime：`create(config, options, loader)` 落地，`ResourceLoader` 提升为稳定公开 API；自定义 loader 须实现 `missingResourceHint()`，写实搜索范围而非敷衍占位（I9）；**前置**：按 4.3 给 loader 身份登记表定界或换键 | 把 loader 接口下放 engine 或 config（R3）      |
| 13 | 用户自带配置（`fromConfig`）             | runtime：`Sokkuri.fromConfig(json, options)` 接受用户配置；装配复用 `create` 的 loader 与 provider 组合、`CreateResult` 双通道，JSONC 走 `JsonSupport.stripComments`（**前置**：#12 的 loader 缝，配置里引用的词典仍须经 `ResourceLoader` 定位；D11 的消息归属；`ocd/ocd2→sok` 的改写目前只存在于 tools/dictgen 的 `ConfigRewriter`，用户配置里的这两种类型会落到格式注册表的 `Unsupported` 分支） | 在 config 层另开一条解析路径；绕过 `DefaultDictionaryFormats`；把改写逻辑从 dictgen 搬进运行时 |

## 6. 不变量清单

违反以下任一条即缺陷，不视为风格问题。

- **I1** 引擎索引与长度一律为 UTF-16 码元；码点只用于词典排序与 IDS 语义。
- **I2** 词典排序与二分共用 `Utf.compareByCodePoint` 的码点序，禁止 `String.compareTo` 混入。
- **I3** 词典与转换器构造后不可变、线程安全；converter 跨线程共享。
- **I4** 异常分类对齐上游，即 `FileNotFound`、`InvalidFormat`、`InvalidConfig`、`Unsupported`；新增错误类型须为 `SokkuriException` 子类，并同步登记进 `Sokkuri.create` 的 `@Throws`，见 D9。Kotlin/Native 会剪除未出现在任何导出签名里的类型，漏登记的子类对 Swift 不可见；但不致命，`@Throws` 标记本身仍把异常桥接为 `NSError`，只是 `CreateResult.Failure.error` 的精确 cast 退化为基类。`SokkuriCreateThrowsContractTest` 在 jvmTest 守门。
- **I5** 全模块启用 `explicitApi()`；跨层 API 标注 `@SokkuriInternalApi`。
- **I6** 热路径零输入拷贝：引擎以 `CharArray` 窗口处理输入，只在写出时拼接，为 8.1 的 view 演进铺路。
- **I7** JSONC 宽容度等价于上游 rapidjson flags 的集合，不多不少，见 R8。
- **I8** `SokkuriConfig` 枚举只增、不删、不重排；新增值进入第 7 章登记表。
- **I9** 资源缺失一律以 `ResourceLoader.load` 返回 `null` 上报，loader 自身不抛异常。诊断由 `missingResourceHint()` 提供，抛点组合为 `FileNotFound(path, loader.missingResourceHint())`，禁止裸 `FileNotFound(path)`，见 D10。理由是抛点只见路径，loader 是唯一知道搜索范围的组件；缺了它，用户只拿到一句无指引的 not found，被送去查打包而非配置。hint 不得为空。`MissingResourceDiagnosisTest` 在 runtime commonTest 的三端真实 loader 上守门，`ResourceDictionaryProviderTest` 在 resource 守门。
- **I10** `dict.type` 的受理集合只有一处来源，即 `DictDocument` 密封层次。`type` 被 `@JsonClassDiscriminator` 降级为序列化器级判别符，config 层拿不到它的值，于是未知或缺失类型的拒绝只能落在 `JsonSupport` 的模块级默认反序列化器上，报 `InvalidConfig` 并用本库措辞，见 D11。该供应器仅在密封查找落空时被查询，所以禁止在别处复制类型清单，也禁止把这条拒绝换回 kotlinx 的通用措辞，即 `DictDocument`、"polymorphic scope" 与建议读者给自家类加 `@Serializable` 之类。`ConfigParserTest` 以未知类型、缺 `type`、组内嵌套三例守门。

## 7. 上游对齐与偏离登记表

| #  | 点                                | 上游                       | Sokkuri                                                               | 理由                                  | 状态                  |
|----|-----------------------------------|----------------------------|-----------------------------------------------------------------------|---------------------------------------|-----------------------|
| 1  | 词典缓存                          | 进程级 weak + mtime 失效   | 进程级强引用共享                                                      | 资源不可变；KMP 无 common 弱引用       | 已实现                |
| 2  | tofu 词典默认                     | core 默认包含；CLI 默认排除 | 默认排除，经 `SokkuriOptions` 开启                                     | 移动端字体 tofu 风险                  | 已实现                |
| 3  | ocd 与 ocd2                       | 支持                       | `UnsupportedDictionaryFormat` 精确报错                                | 字节序与字宽不可移植                  | 已实现                |
| 4  | jieba 与 seal                     | 支持                       | 不移植                                                                | 范围控制                              | 架构预留（8.3 与 8.4）  |
| 5  | 流式转换                          | `ConverterStream`          | 不实现                                                                | 范围控制；接口兼容（8.5）                | 架构预留              |
| 6  | `matchPrefix` 携带 key 与 value view | 携带 `string_view`         | sink 式 `matchAppend`，返回 length，值直写输出缓冲，无中间字符串（见 8.1） | Kotlin 无零拷贝 view；以 sink 替代视图 | 已实现                |
| 7  | schema 校验                       | 仅警告                     | 不实现                                                                | 结构解码已覆盖净效应；见 8.2           | 架构预留              |
| 8  | `PipelineConverter`               | 支持                       | 接口预留                                                              | 无多 stage 配置需求                   | 架构预留              |
| 9  | 插件动态库加载                    | `dlopen`                   | 永不                                                                  | KMP 无统一 ABI；改走注册表注入         | 已决                  |

inline 词典的 `may_output_tofu` 曾按值判定而非按字段存在判定，与上游不一致；2026-10-06 经上游构建实测确认后已修正，不再作为偏离登记。

## 8. 设计预留：架构保证可添加

> 8.1、8.6 与 8.7 已分别于 2026-09-30、2026-10-15 与 2026-10-15 落地，三节保留为已实现形态的记录；其余各节仍为预留。

### 8.1 String view 匹配

**状态：已实现，2026-09-30。** 实现形态取立项时三个备选中的 sink 式，本节的目标形态描述已按实现现状改写。

- `Dictionary` 增加 `matchAppend(text, start, end, out): Int`，命中时把默认 candidate 直写 `out` 并返回命中长度，单位为 UTF-16 码元；未命中返回 -1。默认实现等于 `matchPrefix` 加 append，storage-aware 后端可覆写；
- `SortedTableRetrieval` 的 `matchPrefix` 与 `matchAppend` 共享同一个 `findLongestPrefixIndex`，检索单源，胜者选择不可能分叉，只有值的出口不同，即物化与直写之别；
- 键表泛化为 `SortedKeyTable`，承载首码点、码点序比较、按偏移取码点与编码宽度，以及值的物化与直写两个出口；前缀判定与 UTF-16 长度两项原语已随 8.6 的收窄检索移除。String 后端引用直达；`.sok` 的 byte 后端经 `Utf8` 原语，以 UTF-8 区段与 CharArray 窗口直接做码点比较，探针零解码，非法输入按全序降级为 U+FFFD。`SortedListDictionary` 无需跟进，默认实现即旧行为；
- `DictGroup` 不转发 sink，union 与 short_circuit 都先经子 `matchPrefix` 定胜负，胜者物化一次，组语义天然由默认实现获得；
- `Conversion` 热路径即 I6 的写出点切换为 `matchAppend`；Inspection、`matchExact` 与 mmseg 接口不变。

**实测，同机对比开工基线**：s2t medium 由 21.42 µs 降至 9.4 µs，提升 2.3 倍，重档为 2.3 至 2.6 倍；零拷贝不回退，`decode` 的 sokHeapKiB 约为 0，无任何解码缓存；inspect 与 convert 的开销比为 1.05。

**教训，对立案假设的修正**：其一，JVM 的逃逸分析使命中值物化免费，"值直写"这一层在 JVM 上实测零收益，其收益应在 Native 侧，尚未实测；其二，最大单项收益来自组扫的双扫描融合，即前缀命中之后才计算长度；其三，剩余差距判定为零拷贝逐字节解码的固有 ALU 代价，文本时代为 7.6 µs，要进一步缩小需改用双数组 trie 检索，即 `.sok` 格式 v2，或引入解码缓存而违反零拷贝，两者均不在本节范围内。该判定已被 8.6 推翻：差距来自组内线性扫描的算法代价，而非解码；改为逐码点收窄后，s2t 降至 3.25 µs，低于文本时代，`.sok` 格式不变。性能门槛经裁决由"不高于 8 µs，对齐文本时代"修正为"不低于开工基线的 2.2 倍"，实际达成 2.3 倍。

### 8.2 配置 schema 校验

**目标形态**：注入式校验。`ConfigParser` 构造时接受可选的 `ConfigValidator`，该接口为函数接口，把配置 JSON 字符串映射为警告列表，默认不启用；语义对齐上游的仅警告，校验失败不拒绝解析。

**v1 已铺好的路**：`ConfigDocument` 模型即 schema 的形状；`JsonSupport` 是唯一切入点；`ConfigParser` 为 internal，构造可加参。

**届时改动清单**：定义 `ConfigValidator` 接口；schema 文档随资源打包；实现校验器；决定警告出口，不做上游的 `fprintf(stderr)`，因为本项目遵循库规范，由注入方决定丢弃或透传，`Sokkuri` 层默认丢弃；最后翻转登记表第 7 项的状态。

### 8.3 jieba 与插件分词

**目标形态**：注册表注入，不做动态库加载，因为 KMP 无统一 ABI，见登记表第 9 项。config 层不硬编码分词器清单：除内建 `mmseg` 外，类型名查 `SegmentationProvider`，该接口为函数接口，把类型名与字符串配置对映射为 `Segmentation?`，查不到报 `Unsupported`；这与上游 `PluginSegmentation` 的配置对同构。

**届时改动清单**：`SegmentationDocument` 捕获额外字符串键并传给 provider，需自定义解析，上游行为是所有额外字符串属性都成为配置对；`ConfigParser` 构造加 provider 参数；engine 实现 `Segmentation`；`Sokkuri.create` 高级重载接受注册表，与 #12 的自定义 loader 在同一次 API 扩展中落地。

### 8.4 seal 配置档

纯数据路线，无代码改动：`SokkuriConfig` 枚举加值，遵守 I8；dictgen 从上游 `SealCharacters` 与 `SealVariants` 生成 `.sok`；重写配置进 runtime 资源；更新 README 与登记表。对使用穷尽 `when` 的消费者是源码级影响，minor 版本可接受。

### 8.5 流式 chunked 转换

**目标形态**：`Sokkuri` 之上的有状态包装。每个 chunk 保留尾部 16 码点窗口，且窗口终点须落在完整 IDS 边界之外，对齐上游 `ConverterStream` 语义，防止短语或 IDS 跨 chunk 被切断。engine 的 `Converter` 纯函数接口不动。

**届时改动清单**：在 runtime 或 api 层新增流式包装类，显式提供 `convert(chunk)` 与 `finish()`，文档注明单线程使用；对照上游 `ConverterStream` 测试语义；翻转登记表第 5 项的状态。

### 8.6 前缀收窄检索

**状态：已实现，2026-10-15。** 8.1 把剩余差距判定为零拷贝逐字节解码的固有代价，这一判定有误：瓶颈在检索算法，而不在解码。

旧算法以首码点二分定位后，把同一首码点下的键逐个与输入比较。STPhrases 共 49238 个键、3534 个首码点组，“发”组有 653 个键，99% 分位为 173 个；benchmark 的 `medium` 样本平均每个输入字符要做约 53 次整键比较。文本时代用的是同一种组扫描，因此受同样的限制。长文本会扫遍大量的大组，缓存命中率随之下降，单字成本约为 `medium` 的 3 倍。

现算法逐码点收窄范围，依据是 I2 的码点序：同前缀的键在表中连续，并按下一个码点排序，恰在该前缀结束的键排在最前。

- `SortedKeyTable` 新增两个原语。`keyCodePointAt(index, offset)` 返回键在 offset 处的码点，键在此结束时返回 -1，-1 小于任何码点，与码点序一致；`encodedWidth(codePoint)` 返回该码点在键编码中的宽度。offset 以后端的原生单位计，String 后端为 UTF-16 码元，`.sok` 为 UTF-8 字节，检索算法仍只有一份；
- 首码点索引由两个 IntArray 组成，即去重后的首码点与各组起点，首次使用时构建，`mayStartKey` 与检索共用；
- 第一个输入码点经索引取得组范围，此后每个码点以两次二分把范围收窄到该位置码点相同的子范围；范围的首个键恰在此处结束即为一次命中，最后一次命中就是最长前缀，与上游 `Dict::MatchPrefix` 的结果相同。匹配 k 个码点、组内 g 个键时，代价为 O(k log g) 次单码点探针；
- 首码点查找为 inline：`mayStartKey` 对批量跳过的每个字符都要执行一次，作为独立调用时 Kotlin/Native 在以跳过为主的 t2tw 上慢 3% 至 4%，JVM 的 JIT 掩盖了这一点；
- `.sok` 格式 v1 不变，零拷贝不变。新增内存只有组起点数组，22 个词典全部加载时共 67 KiB，s2t 用到的 4 个词典约 35 KiB；
- 不再使用的 `keyPrefixesWindowAt`、`keyUtf16LengthAt`、`Utf8.startsWithRegionAt` 与 `Utf.startsWithAt` 已删除。

**验证**：契约套件新增随机对拍测试，以暴力求得的最长前缀为准，在两个后端与三端上运行；对收窄逻辑植入的三种边界错误，均在前两轮被它检出。golden 套件不变通过；`upstream-diff` 两种 tofu 口径各 298848 次比对，零差异。

**实测方法**：同机 A/B，A 为收窄之前的提交，B 为本节实现。JVM 为 Azul Zulu 21，每次测量一个独立进程，A 与 B 按 ABBA 交替 5 轮，取各轮中位数的中位数；Native 为 iOS 模拟器上 release 优化的测试二进制，同样交替 3 轮。`medium` 为 48 字，`long` 为 benchmark 新增的 823 字长文本。单位为 µs/op。

| 档 | JVM medium | JVM long | Native medium | Native long |
|---|---|---|---|---|
| s2t | 10.00 → 3.25（3.08×） | 527.71 → 76.13（6.93×） | 15.21 → 6.96（2.19×） | 670.75 → 163.67（4.10×） |
| s2tw | 20.13 → 6.63（3.04×） | 1043.67 → 145.88（7.15×） | 30.21 → 14.04（2.15×） | 1309.25 → 329.54（3.97×） |
| s2twp | 21.04 → 7.42（2.84×） | 1083.67 → 166.33（6.52×） | 32.00 → 14.96（2.14×） | 1364.21 → 351.96（3.88×） |
| s2hk | 20.00 → 6.67（3.00×） | 1043.92 → 150.38（6.94×） | 29.96 → 14.00（2.14×） | 1317.08 → 323.29（4.07×） |
| s2hkp | 20.38 → 7.04（2.89×） | 1078.71 → 156.83（6.88×） | 30.62 → 14.54（2.11×） | 1330.67 → 335.00（3.97×） |
| t2s | 1.54 → 1.38（1.12×） | 25.54 → 23.33（1.09×） | 3.38 → 3.00（1.12×） | 55.50 → 50.29（1.10×） |
| tw2s | 4.00 → 3.13（1.28×） | 70.46 → 49.79（1.42×） | 8.17 → 6.79（1.20×） | 147.08 → 118.00（1.25×） |
| tw2sp | 4.67 → 3.67（1.27×） | 89.42 → 62.25（1.44×） | 9.50 → 7.75（1.23×） | 181.42 → 139.96（1.30×） |
| hk2s | 3.08 → 2.58（1.19×） | 55.92 → 43.71（1.28×） | 6.83 → 6.17（1.11×） | 123.75 → 108.83（1.14×） |
| hk2sp | 3.46 → 2.96（1.17×） | 60.67 → 49.96（1.21×） | 7.42 → 6.75（1.10×） | 136.88 → 118.46（1.16×） |
| t2tw | 0.75 → 0.79（0.95×） | 13.17 → 13.71（0.96×） | 2.21 → 2.21（1.00×） | 38.67 → 38.83（1.00×） |
| tw2t | 1.83 → 1.38（1.33×） | 35.96 → 23.83（1.51×） | 4.08 → 3.29（1.24×） | 77.00 → 60.33（1.28×） |
| t2hk | 0.92 → 0.96（0.96×） | 18.21 → 16.88（1.08×） | 2.29 → 2.33（0.98×） | 43.71 → 41.71（1.05×） |
| hk2t | 1.13 → 1.04（1.09×） | 20.71 → 17.75（1.17×） | 2.83 → 2.71（1.05×） | 53.42 → 49.33（1.08×） |
| t2jp | 0.63 → 0.71（0.89×） | 19.71 → 19.04（1.04×） | 1.12 → 1.12（1.00×） | 18.67 → 17.92（1.04×） |
| jp2t | 1.08 → 1.08（1.00×） | 23.58 → 20.54（1.15×） | 2.71 → 2.71（1.00×） | 56.21 → 52.04（1.08×） |

- 简转繁五档在 JVM 上快 2.8 至 3.1 倍，长文本快 6.5 至 7.2 倍，s2t 长文本吞吐由每秒 156 万字升至 1081 万字；Native 上分别快 2.1 至 2.2 倍与 3.9 至 4.1 倍；
- 其余档词典小、组也小，提升在 1.0 至 1.5 倍之间。表中低于 1 的值，`medium` 列的差异只有 1 至 2 个计时周期，即 42 ns 左右；t2tw 的 JVM `long` 为 0.96 倍，两侧各轮的取值范围相互重叠。为此对 t2tw、t2hk、t2jp、jp2t 另做 10 轮、每样本 2 万次迭代的 JVM 加测，`long` 为 1.01 至 1.17 倍，`medium` 为 0.98 至 1.05 倍；Native 的波动小得多，`long` 全部不低于 1.00 倍。两端均无回退；
- 专项样本，JVM：`short` 2.42 → 0.54，`phrases` 35.75 → 12.54，`twp` 4.04 → 1.17，`hk` 5.13 → 1.67；Native 的 `phrases` 为 50.08 → 21.08；
- 新进程中建档后的首次转换，s2t 由 10.15 ms 降至 2.90 ms，其中包含首码点索引的构建；建档耗时、`decode` 与建档后的常驻堆均不变，因为索引在首次转换时才构建；
- inspect 与 convert 同步提速，s2t 由 10.17 µs 降至 3.58 µs。开销比由 0.94 至 1.05 升至 1.05 至 1.17：inspect 的分段与列表开销是固定的，转换本身变快后占比上升。

**对 `.sok` v2 的结论**：收窄之后，STPhrases 上每个输入字符只剩十余次单码点探针。双数组 trie 能把它降到每字一次状态转移，但按码点建的 trie 估计 0.75 至 1 MiB，按字节建的 1.9 至 2.5 MiB，而现有 22 个词典合计 1.7 MiB，体积与常驻内存都会明显增长，剩余收益却已不确定。v2 暂缓，待 profile 表明检索仍是主要开销时再议。下一个线索是每级转换的固定开销：s2tw 多出的那一级词典很小，耗时却约为 s2t 的两倍，已在 8.7 查明并处理。

### 8.7 各级转换的固定开销

**状态：已实现，2026-10-15。** 8.6 留下的线索是 s2tw 的耗时约为 s2t 的两倍，而它多出的那一级词典很小。分段计时表明，主因不在第二级，而在 s2tw 多出的 mmseg 分词：s2t 没有分词，s2tw、s2twp、s2hk 与 s2hkp 都先以 STPhrases 与 Generated 的 union 把全文切段，再逐段跑转换链。

s2tw 处理 823 字长文本的耗时构成，取自单次运行的分段计时，单位为 µs：

| 环节 | 改动前 | 改动后 |
|---|---|---|
| 归一化，即 CJK 兼容字 | 5.1 | 2.0 |
| mmseg 分词 | 49.2 | 46.2 |
| 第一级转换，与 s2t 相同 | 66.0 | 61.9 |
| 第二级 TWVariants | 9.9 | 4.6 |
| 其余，即合计减去以上各项，主要是 173 段每段每级的复制 | 15.2 | 4.6 |
| 合计 | 145.4 | 119.3 |

JFR 采样另显示，词典组的 `mayStartKey` 占转换时间的 11% 至 20%：批量跳过的每个字符，都要对组内每个子词典各做一次二分。

改动共四项，外加一项构建上的修正：

- 首码点索引查找前先比较上下界，范围之外的码点直接返回。CJK 兼容字词典的键全在 U+F900 以上，常用汉字因此不再二分，长文本的归一化在每一档上都由约 5 µs 降到 2 µs；
- 有分词时，转换链逐级处理全部段，每一级只分配一次缓冲，并记录各段输出在缓冲中的位置，下一级仍按段转换。此前每段每级都要复制输入，并分配 StringBuilder、String 与数组。每一级只读取所给的范围，因此匹配仍不跨段，输出与逐段转换逐字相同；
- `Dictionary` 新增 `matchLength`，默认实现为 `matchPrefix` 的长度，检索层与词典组各自覆写；分词只需要边界，改用它后不再物化值；
- `Dictionary` 新增 `firstCodePoints`，返回 `mayStartKey` 所接受的码点集合，无法列举时返回 null。词典组在首次查询时合并子词典的集合，`mayStartKey` 由每个子词典各一次二分变为整组一次二分；任一子词典返回 null 时退回逐个询问；
- 修正：合并改为对有序集合的线性归并，并且只有 `mayStartKey` 被查询的组才保留集合，仅作为子词典的组为父组临时合并、不保留。

**代价**：词典组随每个转换器创建，保留的集合因此按转换器计。实测每个转换器在首次转换后多占的堆，s2t 为 23 KiB，s2tw 为 37 KiB，s2twp 为 39 KiB，t2s 为 15 KiB；改动前转换器之间共享全部词典，几乎不占额外内存。新进程中的首次转换多出合并集合的耗时，s2t 由 2.45 ms 增至 2.98 ms，s2twp 由 4.94 ms 增至 5.27 ms，t2s 持平；这是 JVM 解释执行阶段的代价，Native 上合并只需数十微秒。

**否决的方案**：让词典组把 `matchAppend` 直写转发给胜出的子词典。union 需要先比较各子词典的长度，再让胜者写出，胜者因此多检索一次；JVM 上 s2t 反而由 66.4 µs 变为 69.5 µs。Native 没有逃逸分析，物化代价更高，结论可能不同，尚未测量。

**验证**：契约套件的随机对拍测试加入了 `matchLength` 与 `firstCodePoints`；词典组新增嵌套随机组上 `matchLength` 与 `matchPrefix` 一致、合并集合与逐个询问一致、无法列举时退回的测试；转换链新增段隔离测试，以及随机分段下逐级处理与逐段处理一致的测试。每项新测试都经变异检查，确认能检出植入的错误。golden 套件不变通过；`upstream-diff` 两种 tofu 口径各 298848 次比对，零差异。

**实测**：方法同 8.6，A 为 8.6 的实现，B 为本节四项改动；修正一项只影响集合的构建，上面的内存与首次转换数字取自修正之后。单位为 µs/op。

| 档 | JVM medium | JVM long | Native medium | Native long |
|---|---|---|---|---|
| s2t | 3.17 → 3.08（1.03×） | 73.96 → 71.25（1.04×） | 6.96 → 5.92（1.18×） | 163.29 → 143.58（1.14×） |
| s2tw | 6.50 → 5.63（1.15×） | 142.21 → 122.88（1.16×） | 14.17 → 10.75（1.32×） | 321.04 → 261.04（1.23×） |
| s2twp | 7.17 → 6.08（1.18×） | 163.25 → 140.75（1.16×） | 15.12 → 11.75（1.29×） | 352.33 → 293.71（1.20×） |
| s2hk | 6.54 → 5.54（1.18×） | 147.88 → 125.88（1.17×） | 14.00 → 10.75（1.30×） | 321.88 → 265.58（1.21×） |
| s2hkp | 6.88 → 5.83（1.18×） | 156.46 → 128.38（1.22×） | 14.58 → 11.00（1.33×） | 335.04 → 269.08（1.25×） |
| t2s | 1.33 → 0.75（1.77×） | 21.54 → 12.38（1.74×） | 3.04 → 1.58（1.92×） | 50.92 → 24.96（2.04×） |
| tw2s | 3.13 → 2.17（1.44×） | 48.88 → 33.33（1.47×） | 6.79 → 3.96（1.72×） | 118.38 → 69.29（1.71×） |
| tw2sp | 3.63 → 2.38（1.53×） | 61.33 → 40.63（1.51×） | 7.79 → 4.38（1.78×） | 140.71 → 87.71（1.60×） |
| hk2s | 2.58 → 1.63（1.58×） | 43.04 → 27.92（1.54×） | 6.12 → 2.96（2.07×） | 107.71 → 52.54（2.05×） |
| hk2sp | 2.88 → 1.71（1.68×） | 50.00 → 29.42（1.70×） | 6.71 → 3.04（2.21×） | 118.58 → 55.17（2.15×） |
| t2tw | 0.79 → 0.33（2.39×） | 13.42 → 5.71（2.35×） | 2.25 → 0.71（3.18×） | 38.92 → 12.42（3.13×） |
| tw2t | 1.38 → 0.92（1.50×） | 23.21 → 15.63（1.48×） | 3.29 → 2.00（1.65×） | 60.38 → 35.46（1.70×） |
| t2hk | 0.92 → 0.38（2.42×） | 16.75 → 8.25（2.03×） | 2.33 → 0.83（2.80×） | 41.54 → 17.88（2.32×） |
| hk2t | 1.04 → 0.50（2.08×） | 17.54 → 9.13（1.92×） | 2.71 → 1.04（2.60×） | 49.25 → 17.67（2.79×） |
| t2jp* | 0.71 → 0.50（1.42×） | 10.52 → 7.44（1.41×） | 1.12 → 0.83（1.35×） | 18.04 → 13.21（1.37×） |
| jp2t | 1.04 → 0.42（2.48×） | 19.58 → 11.00（1.78×） | 2.71 → 1.00（2.71×） | 52.29 → 22.08（2.37×） |

\* t2jp 在全档同进程的 JVM 运行中呈 JIT 双峰，`medium` 一项各轮在 0.46 至 1.00 之间跳动；表中 JVM 数字取自单独的 10 轮加测，每样本 2 万次迭代。

- 简转繁五档在 JVM 上快 1.03 至 1.22 倍，Native 上快 1.14 至 1.33 倍；s2t 没有分词，只得到归一化与组索引的收益；
- 其余各档在 JVM 上快 1.4 至 2.5 倍，Native 上快 1.35 至 3.2 倍；繁转简与变体档的词典组多，跳过的字符也多，组索引收益最大；
- 专项样本，JVM：`short` 0.50 → 0.38，`phrases` 12.38 → 10.96，`hk` 1.67 → 1.25，`twp` 持平；Native 的 `phrases` 为 21.08 → 17.79；inspect 同步提速，tw2sp 由 3.92 µs 降至 2.71 µs；建档耗时与 `decode` 不变。

**剩余的大头**：在简转繁的四个分词档里，分词与第一级转换以同一个 union 检索两遍：对已成词的段，第一级必然再次命中同一个键。这部分约占这四档耗时的三分之一。复用分词结果可以省去它，但这依赖“分词词典就是第一级首个子组”的配置结构，还需论证与上游逐段语义等价，留待单独研究。

## 9. 决策实现状态总表

| 决策  | 内容                                                                 | 代码状态      |
|-----|--------------------------------------------------------------------|-----------|
| D1  | `Converter` 封闭接口化（SingleStage 与 Normalizing，Pipeline 预留）          | 已实现       |
| D2  | `Inspection` 平铺 `normalizationStages`                              | 已实现       |
| D3  | 进程级强引用共享词典缓存，按加载器实例划分 scope                                       | 已实现       |
| D4  | `SokkuriOptions` 改 Builder 模式                                      | 已实现       |
| D5  | inline 词典 `may_output_tofu` 严格报错，按字段存在判定                              | 已实现       |
| D6  | `.sok` 编解码读写同侧                                                     | 已实现       |
| D7  | 流式转换不进 v1                                                          | 架构预留（8.5） |
| D8  | 自定义 loader 公开化路径，即扩展手册 #12                                          | 架构预留      |
| D9  | 错误上报双通道：`create` 抛异常加 `CreateResult` 非抛路径，用于 Apple 边界               | 已实现       |
| D10 | 资源缺失诊断下沉到 loader：`missingResourceHint()` 组合进 `FileNotFound.detail` | 已实现       |
| D11 | 未知或缺失 `dict.type` 统一由 `JsonSupport` 的模块级默认反序列化器拒绝，见 I10           | 已实现       |
| D12 | `Sokkuri.fromConfig` 接受用户自带 OpenCC 配置                              | 架构预留（扩展手册 #13） |
| D13 | 持续集成分层：构建门禁、上游对齐、发布前置对齐、上游演练，见 10.2                     | 已实现       |
| D15 | 最长前缀检索改为逐码点二分收窄，`.sok` 格式 v2 暂缓，见 8.6                           | 已实现       |
| D16 | 削减各级转换的固定开销：首码点上下界、按级处理分段、分词只取长度、词典组合并首码点集合，见 8.7 | 已实现       |
| D14 | OpenCC 参考克隆以锁文件加按需检出管理，不用 git 子模块，见 10.3                       | 已实现       |

## 10. 测试与对齐策略

- **三层测试**：engine 纯函数单测；config 语义测试，使用 fake `DictionaryProvider`，覆盖组策略、tofu 过滤、normalization 序与 JSONC；runtime 集成测试，走平台 loader 的真实路径。
- **golden 对齐**：以 `OpenCC/test/testcases/testcases.json` 为最终防线，全部 16 个 profile 逐条比对 convert 输出。
- **专项**：IDS 与 UTF-16 边界 fuzz，覆盖 unpaired surrogate 与越界 IDS 树；词典缓存并发测试；Android host 与 device 路径都须覆盖。
- `OpenCC/` 参考克隆是 spec 与测试语料源，**禁止提交、禁止修改**；取得方式与锁定见 10.3。

### 10.1 上游对齐的验证边界

第 7 章登记的偏离与本节的对齐结论，依据分四层，可信度依次递减。

第一层是执行比对：`tools:upstream-diff` 以 OpenCC 参考克隆的 cmake 构建为预言机，对同一语料逐条比对两侧输出。语料含上游 golden 的全部输入、固定的边界用例与按种子生成的随机样本，覆盖全部 16 个 profile，并在排除与包含 tofu 风险词典两种口径下各跑一遍。2026-10-06 的结果为 16 档 × 18678 条输入 × 两种口径，零处不一致。这是唯一能证明行为一致的依据，也是确认 IDS 跳过缺陷、发现 inline `may_output_tofu` 分歧的手段。

第二层是外部语料：568 条 golden 期望移植自上游 `testcases.json`，在三端运行。它认证的是上游自己写下的用例，覆盖面由上游决定，IDS 缺陷能穿过它正是因为该语料不含任何 IDS 用例。

第三层是自洽性：差分模糊测试把优化后的转换循环与一个从不批量跳过的逐字符参考循环对拍。它证明优化相对于参考循环透明，而不证明参考循环本身忠实于上游；若参考循环移植有误，两者会一同出错。第二层与第三层都不能替代第一层。

第四层是源码推断：未被上述任一层覆盖的上游行为结论，均来自阅读 `OpenCC/src/` 下的实现，未经执行比对。

执行比对有一个本质边界：上游的转换入口收 UTF-8，而 Sokkuri 收 `CharArray`。持有孤立代理项的 Kotlin 字符串没有对应的 UTF-8 形式，上游会在那里抛 `InvalidUTF8`，因此这类输入两侧不可比，由 Sokkuri 自身的模糊测试覆盖。尚未纳入比对的还有 `inspect()` 的结构，以及缺资源、畸形配置、未知 `dict.type` 等错误路径。

该工具不挂入 `check`，因为它需要 C++ 工具链与克隆的 cmake 构建，日常构建不应有此前提。

### 10.2 持续集成

**状态：已实现，2026-10-15。** 此前仓库只有 tag 触发的 `release.yml`，日常 push 不运行任何检查；10.1 的第一层执行比对与 `:tools:dictgen:checkDictionaries` 只能手动执行，漏跑一次，偏离就可能随版本发布。现由三个工作流与两个 composite action 落地。

设计原则：

- **日常门禁即发布门禁**：`ci.yml` 与 `release.yml` 执行同一条 `./gradlew build`，主干为绿即具备发布条件，不另设第二套标准。ABI 校验 `checkKotlinAbi` 挂在 `check` 下，随之生效。
- **对齐结论可复现**：对齐检查只针对 `sokkuri-runtime/src/commonTest/resources/testcases/UPSTREAM` 钉住的上游提交，同一提交的结论可以复现；跟踪上游最新提交由演练任务承担，失败只发出提示，不阻断任何流程。
- **跳过不冒充通过**：依赖克隆的验收测试在缺少 `OPENCC_DIR` 时报告为 skipped，日常门禁中它们必然 skipped；对齐层设置 `OPENCC_DIR` 运行它们，并校验报告中没有 skipped，防止它们在对齐层也悄悄跳过。
- **环境只定义一次**：JDK、Gradle、Android SDK 与 Kotlin/Native 缓存集中在 `.github/actions/setup-build-env`，三个工作流共用，不会各自漂移；取得 OpenCC 由 `.github/actions/checkout-opencc` 调用根构建的 `fetchOpencc` 任务完成，与本地检出是同一份实现，见 10.3。

| 层         | 工作流                        | 触发                               | 内容                                                                                                                                                                              | 失败的含义                         |
|------------|-------------------------------|------------------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|------------------------------------|
| 1 构建门禁 | `ci.yml`                      | push 到 `main`、所有 PR、手动      | `./gradlew build --continue`：JVM、Android host 与 iOS 模拟器三端测试，tools 单测，ABI 校验                                                                                          | 阻断合并                           |
| 2 上游对齐 | `upstream.yml` 的 pinned 任务 | 每周一定时、手动、由发布流程调用   | 按 `UPSTREAM` 取得 OpenCC；golden 语料逐字节比对；`checkDictionaries`；带 `OPENCC_DIR` 运行 `:sokkuri-runtime:jvmTest` 与 `:tools:dictgen:test` 并校验未跳过；`upstream-diff` 两种 tofu 口径各跑一次 | 阻断发布                           |
| 3 发布     | `release.yml`                 | 推送 `v*` tag                      | 校验 tag 与 `VERSION_NAME` 一致；调用第 2 层；通过后执行 `build` 与 `publishToMavenCentral`                                                                                          | 不上传                             |
| 演练       | `upstream.yml` 的 head 任务   | 每周一定时、手动                   | 上游默认分支的最新提交若已偏离锁定，就演练一次推进锁定：在工作区内把锁改到该提交并检出，重新生成词典、换入新的 golden 语料，再跑 `:sokkuri-runtime:jvmTest`、带克隆的验收测试与两种口径的比对；摘要列出推进将改变的资源 | 只提示上游已变化，需评估是否推进锁定 |

除只读 `gradle.properties` 的版本校验跑在 ubuntu-latest 外，全部任务使用 macos-latest：iOS 模拟器测试只能在 macOS 上运行，统一 runner 也让环境只需维护一份；第 2 层所需的 cmake、clang++ 与 python3 在该镜像中预装。

实现要点：

- 第 2 层的两个测试任务加 `--rerun`：环境变量不是任务输入，构建缓存可能直接复用缺少克隆时的 skipped 结果。
- `upstream-diff` 的两种口径是两个独立步骤：Gradle 拒绝空的 `--args`。
- OpenCC 以深度 1 按提交浅取，约 50 MB；没有 tag 时 OpenCC 的 cmake 回落到默认版本号，构建不受影响。
- 缓存：Gradle 由 `setup-gradle` 负责；`~/.konan` 以 `gradle/libs.versions.toml` 为键，只在编译 Apple target 的任务中恢复，实测纯 JVM 任务不触碰该目录；Android SDK 以组件清单为键，键与 sdkmanager 参数须随 `android-compileSdk` 同步。OpenCC 的 cmake 构建不缓存：本机（18 核）实测，含 cmake 构建的首次运行 14 秒，之后每种口径约 3 秒。
- JDK 用 Azul Zulu 21：`gradle/gradle-daemon-jvm.properties` 把守护进程钉在 Azul 21，换用其他发行版会让 Gradle 每次另行下载一份 JDK。
- Action 取各自最新主版本，唯独 `gradle/actions/setup-gradle` 停在 v5：v6 把缓存功能移入专有组件，升级即接受 Gradle 的使用条款，这属于项目决策，不是普通的版本升级。
- `shell: bash` 自带 pipefail，`gradlew … | tee` 由此保留 Gradle 的退出码；同样因为 pipefail，安装 SDK 时的 `yes |` 必须改写，否则 `yes` 收到 SIGPIPE 会让步骤失败。
- 演练任务的产物只留在 CI 工作区，不提交；推进锁定仍由人工完成，步骤见 10.3。
- 失败时上传测试报告与比对日志作为 artifact，保留 14 天；各工作流的 `GITHUB_TOKEN` 只有 `contents: read`，checkout 不持久化凭据，发布凭据只出现在 `release.yml` 的发布步骤。
- 第 1 层在 PR 有新提交时取消旧的运行，`main` 上的每个提交都保留结果；第 1 层不设路径过滤，因为它是必需检查，被过滤掉的 PR 永远等不到结果、无法合并。发布流程维持不取消策略。
- 公开仓库连续 60 天没有活动时，GitHub 会停用定时工作流，需要在 Actions 页面重新启用。

**仓库设置（需手动）**：在 `main` 的分支保护规则中把 `Build gate` 设为必需检查；工作流文件本身无法声明这一点。

### 10.3 OpenCC 参考克隆

**状态：已实现，2026-10-15。**

`OpenCC/` 是上游仓库在仓库根目录下的检出，列在 `.gitignore` 中，不进入任何提交。它是行为 spec，是 `tools:dictgen` 生成打包词典的输入，是 `tools:upstream-diff` 构建预言机的源码，也是 golden 语料的出处。发布产物不依赖它：`./gradlew build` 与发布流程都不读取克隆。

锁定只记录在一处：`sokkuri-runtime/src/commonTest/resources/testcases/UPSTREAM` 的 `Commit:` 行。打包词典、配置与 golden 语料都由这一提交派生并已提交进仓库，克隆只是随时可以按锁重新取得的工作副本。

- `./gradlew fetchOpencc` 把克隆检出到锁定提交，语义同 `git submodule update --init`：克隆不存在时按提交浅取；已存在时只在缺少该提交时拉取，完整克隆保持完整，浅克隆保持浅；随后以分离头指针检出。跟踪文件有改动的克隆不会被移动。
- `checkOpenccClone` 在克隆不在锁定提交、或跟踪文件有改动时失败。`:tools:dictgen:run`、`:tools:dictgen:checkDictionaries` 与 `:tools:upstream-diff:run` 都依赖它，克隆过期时直接报错，不会把克隆的版本差异误报成 Sokkuri 的漂移或分歧。以 `--opencc-dir` 指向别处的运行可用 `-x checkOpenccClone` 跳过该检查。
- `upstream-diff` 缓存的 cmake 构建记录它对应的克隆提交，克隆移到别的提交后自动重建。
- CI 的 `.github/actions/checkout-opencc` 调用同一个 `fetchOpencc`，取得克隆的逻辑只有一份。
- 依赖克隆的验收测试仍按 `OPENCC_DIR` 定位克隆，本身不校验锁定；对齐层在运行它们之前，已由 `checkDictionaries` 校验过同一个克隆。

推进锁定的步骤：改写 `UPSTREAM` 的 `Commit:` 与 `Date:` 行；运行 `./gradlew fetchOpencc`；运行 `./gradlew :tools:dictgen:run`；按 `UPSTREAM` 中的命令刷新 golden 语料，并更新其中的用例计数；带 `OPENCC_DIR` 运行测试，再运行两种口径的 `upstream-diff`；全部通过后一并提交。顺序是先改锁、再检出、再生成，CI 的演练任务按同样的顺序在工作区内执行。

不用 git 子模块的理由：上游源码是否进入发布产物，决定了社区的常见做法。需要随包发布上游源码的项目用子模块，例如 Rust 的 `-sys` crate；只在开发与验证时使用上游、产物派生后提交的项目用锁文件加按需拉取，例如 `unicode-segmentation` 按固定的 Unicode 版本下载数据并生成表格。Sokkuri 属于后者。锁文件已在 git 中记录了提交，子模块在可复现性上没有额外收益，却带来 `git pull` 不更新子模块、`commit -a` 误移指针等使用负担。

其他被否决的做法：把上游整体或部分 vendor 进仓库，会让几十 MB 源码永久进入历史，且 `upstream-diff` 仍需完整源码，最终两套机制并存；下载 GitHub 归档 tarball 再校验 sha256，GitHub 不保证归档字节稳定，校验和会无故失效，而按提交经 git 拉取本身就是内容寻址的。
