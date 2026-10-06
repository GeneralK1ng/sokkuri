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
| 8  | inline 词典 `may_output_tofu`     | 字段存在即报错             | 仅当值为 `true` 时报错，显式 `false` 被接受                            | **移植差距，非设计选择**；修正时须同步调整 `ConfigRewriter` 的默认值输出 | **未对齐**            |
| 9  | `PipelineConverter`               | 支持                       | 接口预留                                                              | 无多 stage 配置需求                   | 架构预留              |
| 10 | 插件动态库加载                    | `dlopen`                   | 永不                                                                  | KMP 无统一 ABI；改走注册表注入         | 已决                  |

## 8. 设计预留：架构保证可添加

> 8.1 已于 2026-09-30 落地，该节保留为已实现形态的记录；其余各节仍为预留。

### 8.1 String view 匹配

**状态：已实现，2026-09-30。** 实现形态取立项时三个备选中的 sink 式，本节的目标形态描述已按实现现状改写。

- `Dictionary` 增加 `matchAppend(text, start, end, out): Int`，命中时把默认 candidate 直写 `out` 并返回命中长度，单位为 UTF-16 码元；未命中返回 -1。默认实现等于 `matchPrefix` 加 append，storage-aware 后端可覆写；
- `SortedTableRetrieval` 的 `matchPrefix` 与 `matchAppend` 共享同一个 `findLongestPrefixIndex` 扫描，下界与组扫单源，胜者选择不可能分叉，只有值的出口不同，即物化与直写之别；
- 键表泛化为 `SortedKeyTable`，承载首码点、码点序比较、前缀判定、UTF-16 长度以及值的物化与直写两个出口。String 后端引用直达；`.sok` 的 byte 后端经 `Utf8` 原语，以 UTF-8 区段与 CharArray 窗口直接做码点比较，探针零解码，非法输入按全序降级为 U+FFFD。`SortedListDictionary` 无需跟进，默认实现即旧行为；
- `DictGroup` 不转发 sink，union 与 short_circuit 都先经子 `matchPrefix` 定胜负，胜者物化一次，组语义天然由默认实现获得；
- `Conversion` 热路径即 I6 的写出点切换为 `matchAppend`；Inspection、`matchExact` 与 mmseg 接口不变。

**实测，同机对比开工基线**：s2t medium 由 21.42 µs 降至 9.4 µs，提升 2.3 倍，重档为 2.3 至 2.6 倍；零拷贝不回退，`decode` 的 sokHeapKiB 约为 0，无任何解码缓存；inspect 与 convert 的开销比为 1.05。

**教训，对立案假设的修正**：其一，JVM 的逃逸分析使命中值物化免费，"值直写"这一层在 JVM 上实测零收益，其收益应在 Native 侧，尚未实测；其二，最大单项收益来自组扫的双扫描融合，即前缀命中之后才计算长度；其三，剩余差距判定为零拷贝逐字节解码的固有 ALU 代价，文本时代为 7.6 µs，要进一步缩小需改用双数组 trie 检索，即 `.sok` 格式 v2，或引入解码缓存而违反零拷贝，两者均不在本节范围内。性能门槛经裁决由"不高于 8 µs，对齐文本时代"修正为"不低于开工基线的 2.2 倍"，实际达成 2.3 倍。

### 8.2 配置 schema 校验

**目标形态**：注入式校验。`ConfigParser` 构造时接受可选的 `ConfigValidator`，该接口为函数接口，把配置 JSON 字符串映射为警告列表，默认不启用；语义对齐上游的仅警告，校验失败不拒绝解析。

**v1 已铺好的路**：`ConfigDocument` 模型即 schema 的形状；`JsonSupport` 是唯一切入点；`ConfigParser` 为 internal，构造可加参。

**届时改动清单**：定义 `ConfigValidator` 接口；schema 文档随资源打包；实现校验器；决定警告出口，不做上游的 `fprintf(stderr)`，因为本项目遵循库规范，由注入方决定丢弃或透传，`Sokkuri` 层默认丢弃；最后翻转登记表第 7 项的状态。

### 8.3 jieba 与插件分词

**目标形态**：注册表注入，不做动态库加载，因为 KMP 无统一 ABI，见登记表第 10 项。config 层不硬编码分词器清单：除内建 `mmseg` 外，类型名查 `SegmentationProvider`，该接口为函数接口，把类型名与字符串配置对映射为 `Segmentation?`，查不到报 `Unsupported`；这与上游 `PluginSegmentation` 的配置对同构。

**届时改动清单**：`SegmentationDocument` 捕获额外字符串键并传给 provider，需自定义解析，上游行为是所有额外字符串属性都成为配置对；`ConfigParser` 构造加 provider 参数；engine 实现 `Segmentation`；`Sokkuri.create` 高级重载接受注册表，与 #12 的自定义 loader 在同一次 API 扩展中落地。

### 8.4 seal 配置档

纯数据路线，无代码改动：`SokkuriConfig` 枚举加值，遵守 I8；dictgen 从上游 `SealCharacters` 与 `SealVariants` 生成 `.sok`；重写配置进 runtime 资源；更新 README 与登记表。对使用穷尽 `when` 的消费者是源码级影响，minor 版本可接受。

### 8.5 流式 chunked 转换

**目标形态**：`Sokkuri` 之上的有状态包装。每个 chunk 保留尾部 16 码点窗口，且窗口终点须落在完整 IDS 边界之外，对齐上游 `ConverterStream` 语义，防止短语或 IDS 跨 chunk 被切断。engine 的 `Converter` 纯函数接口不动。

**届时改动清单**：在 runtime 或 api 层新增流式包装类，显式提供 `convert(chunk)` 与 `finish()`，文档注明单线程使用；对照上游 `ConverterStream` 测试语义；翻转登记表第 5 项的状态。

## 9. 决策实现状态总表

| 决策  | 内容                                                                 | 代码状态      |
|-----|--------------------------------------------------------------------|-----------|
| D1  | `Converter` 封闭接口化（SingleStage 与 Normalizing，Pipeline 预留）          | 已实现       |
| D2  | `Inspection` 平铺 `normalizationStages`                              | 已实现       |
| D3  | 进程级强引用共享词典缓存，按加载器实例划分 scope                                       | 已实现       |
| D4  | `SokkuriOptions` 改 Builder 模式                                      | 已实现       |
| D5  | inline 词典 `may_output_tofu` 严格报错                                   | 已实现，与上游仍有一处差异，见第 7 章第 8 项 |
| D6  | `.sok` 编解码读写同侧                                                     | 已实现       |
| D7  | 流式转换不进 v1                                                          | 架构预留（8.5） |
| D8  | 自定义 loader 公开化路径，即扩展手册 #12                                          | 架构预留      |
| D9  | 错误上报双通道：`create` 抛异常加 `CreateResult` 非抛路径，用于 Apple 边界               | 已实现       |
| D10 | 资源缺失诊断下沉到 loader：`missingResourceHint()` 组合进 `FileNotFound.detail` | 已实现       |
| D11 | 未知或缺失 `dict.type` 统一由 `JsonSupport` 的模块级默认反序列化器拒绝，见 I10           | 已实现       |
| D12 | `Sokkuri.fromConfig` 接受用户自带 OpenCC 配置                              | 架构预留（扩展手册 #13） |

## 10. 测试与对齐策略

- **三层测试**：engine 纯函数单测；config 语义测试，使用 fake `DictionaryProvider`，覆盖组策略、tofu 过滤、normalization 序与 JSONC；runtime 集成测试，走平台 loader 的真实路径。
- **golden 对齐**：以 `OpenCC/test/testcases/testcases.json` 为最终防线，全部 16 个 profile 逐条比对 convert 输出。
- **专项**：IDS 与 UTF-16 边界 fuzz，覆盖 unpaired surrogate 与越界 IDS 树；词典缓存并发测试；Android host 与 device 路径都须覆盖。
- `OpenCC/` 参考克隆是 spec 与测试语料源，**禁止提交、禁止修改**。

### 10.1 上游对齐的验证边界

第 7 章登记的偏离与本节的对齐结论，依据有三层，可信度依次递减。

第一层是外部数据：568 条 golden 期望移植自上游 `testcases.json`，在三端运行；这是唯一来自本仓库之外的预言机，也是"与上游逐条对齐"这一说法的主要支撑。其覆盖范围有一个明确缺口，该语料不含任何 IDS 用例，因此 IDS 相关的行为无法由它证明。

第二层是自洽性：差分模糊测试把优化后的转换循环与一个从不批量跳过的逐字符参考循环对拍。它证明的是优化相对于参考循环透明，而不是参考循环本身忠实于上游；若参考循环移植有误，两者会一同出错。

第三层是源码推断：其余关于上游行为的结论均来自阅读 `OpenCC/src/` 下的对应实现，未经执行比对。仓库不含可执行的上游构建，故凡标注为"对齐上游"的行为，除非有第一层数据覆盖，其确证程度止于源码推断。
