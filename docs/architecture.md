# Sokkuri 架构设计文档

版本:v1 · 状态:宪法文件 · 适用范围:`com.generalk1ng.sokkuri` 全部模块

> 本文档是 Sokkuri 的架构宪法:模块边界、依赖方向、扩展方式与不变量
> 以本文为准。修改本文档即架构变更,须先于对应代码改动进行讨论。
> 与 OpenCC 上游的关系:行为对齐是目标,本文第 7 章登记的偏离是
> 已决设计,不因"上游如此"而回退。

## 1. 目标与非目标

**目标**

- 行为对齐 OpenCC C++ 核心:同一转换管线、同一词典语义、同一
  IDS(表意文字描述序列)处理;
- 提供 OpenCC 没有的差异化能力:`inspect()` 分级转换结果;
- Kotlin Multiplatform,首发 JVM / Android / iOS(iosArm64、
  iosSimulatorArm64),库规范从零成立(`explicitApi`、语义化版本、
  二进制兼容意识)。

**非目标(v1 不实现,但架构必须允许后续添加——见第 8 章)**

| 项                  | 性质                      | 预留位置                                                            |
|---------------------|---------------------------|---------------------------------------------------------------------|
| ocd / ocd2 词典读取 | 字节序/字宽不可移植       | 注册表保留类型名,报精确错误                                         |
| jieba 及插件分词    | 范围控制                  | `SegmentationProvider` 注册表缝                                     |
| `*_seal` 配置档     | 纯数据                    | `SokkuriConfig` 枚举加法 + dictgen                                         |
| 流式 chunked 转换   | 范围控制                  | `Converter` 纯函数接口之上的包装                                    |
| String view 匹配    | Kotlin 无通用零拷贝视图   | `Dictionary` internal 接口演进,**已实现**(M3,sink 式 `matchAppend`) |
| 配置 schema 校验    | 上游 warn-only,净效应有限 | 注入式 `ConfigValidator` 缝                                         |

## 2. 模块边界与依赖法则

### 2.1 模块结构

```
sokkuri-runtime    聚合门面 + 平台资源加载器 actual + 打包词典数据
└── sokkuri-resource  唯一字节感知层:ResourceLoader、DictionaryFormat
    │                 注册表(含 .sok 编解码,读写同侧)、进程级共享缓存、
    │                 SokkuriLock
    └── sokkuri-config  OpenCC 配置语义:JSONC 解码、Converter 组装;
        │               零 IO,经 Provider 缝隙落地
        └── sokkuri-engine  纯转换核心:Utf/IDS、Dictionary、Conversion、
            │               ConversionChain、Segmentation、Converter
            └── sokkuri-api   稳定公开面:SokkuriConfig、SokkuriOptions、Inspection、
                              异常体系、@SokkuriInternalApi
tools:dictgen(已实现,M1)  词典编译器;同构建的普通模块,非发布产物。
                  上游 data/dictionary → `.sok`、上游 config → 重写后的打包
                  JSON,16 个 config + 22 个 `.sok` 落入 sokkuri-runtime
                  资源;`:tools:dictgen:run` 生成,`:tools:dictgen:
                  checkDictionaries` 做漂移校验(确定性,DoD 4)。落地过程
                  见 milestones/m1-dictgen-sok.md(.sok 格式字节级规范以该
                  文档为唯一事实源)。
tools:benchmark(M2)  基准工具;同构建的普通模块,非发布产物。转换吞吐、
                  装配延迟、词典解码、inspect 开销的测量,只走消费视角
                  (runtime/resource 白名单依赖,禁 engine/config),不挂
                  check。子命令与输出格式见 milestones/m2-benchmark.md
                  (§3 为唯一事实源)。
```

### 2.2 模块职责与允许依赖

| 模块               | 职责                                   | 允许依赖                                               | 禁止                                                      |
|--------------------|----------------------------------------|--------------------------------------------------------|-----------------------------------------------------------|
| `sokkuri-api`      | 消费者需要知道的全部类型               | 无                                                     | IO、平台 API、internal 原语(本平台原语已于骨架期移出)     |
| `sokkuri-engine`   | 转换算法,无 IO 纯 Kotlin               | `sokkuri-api`(api)                                     | IO、序列化库、平台 API                                    |
| `sokkuri-config`   | 配置文档模型与组装语义                 | api、engine(均为 api——签名泄漏)、kotlinx-serialization | IO(经 `DictionaryProvider` / `SegmentationProvider` 出界) |
| `sokkuri-resource` | 字节加载、格式解码、缓存               | api、engine、config(均为 api)                          | 转换语义(不理解 config 文档结构)                          |
| `sokkuri-runtime`  | `Sokkuri` 门面、平台 loader            | api(api)、其余(implementation)                         | 转换/解析逻辑(只组装)                                     |
| `tools:dictgen`    | 上游数据 → `.sok` + 重写配置           | 依赖主构建模块(必须是同构建普通模块)                   | 发布、持有独立格式实现                                    |
| `tools:benchmark`  | 性能测量:公开路径端到端 + 格式解码对照 | runtime、resource(白名单)                              | 发布、依赖 engine/config、测量进 check                    |

### 2.3 法则(可引用编号)

- **R1 依赖方向严格向下**;下层不得感知上层存在。
- **R2 签名泄漏必须用 `api()`** 声明:公开签名中出现的任何类型,其
  模块必须是 `api` 依赖,保证发布 POM 正确。
- **R3 字节只经 resource**:engine 与 config 零 IO;一切文件/资源
  读取经 `ResourceLoader`,一切词典落地经 `DictionaryProvider` 缝。
- **R4 internal 法则**:跨层公开声明必须标注 `@SokkuriInternalApi`;
  `sokkuri-api` 内不得出现 internal 原语——它是纯公开类型层。
- **R5 expect/actual 只允许存在于两处**:`sokkuri-runtime` 的
  `defaultResourceLoader()` 与 `sokkuri-resource` 的 `SokkuriLock`。
  **新增平台 = 为这两处补 actual,不得新增层、不得在上层新增 actual。**
- **R6 可追溯法则**:engine/config 中每个移植类须在 KDoc 标注对应
  OpenCC 组件名;改行为前先读上游源码,不按记忆改。
- **R7 对齐优先,偏离登记**:与上游行为冲突时,默认对齐;确有理由
  偏离的,登记入第 7 章并说明理由,禁止"静默不同"。
- **R8 配置宽容度严格等于上游**:`kParseCommentsFlag |
  kParseTrailingCommasFlag` 的等价集合;不更宽(`isLenient` 已移除)、
  不更窄,未知键忽略、未知分词类型报错。

## 3. 管线模型(目标架构)

### 3.1 Converter 组合

engine 定义**封闭接口** `Converter`(对齐上游 `Converter` 抽象):

```kotlin
sealed interface Converter {
    fun convert(input: String): String
    fun inspect(input: String): Inspection

    class SingleStage(segmentation: Segmentation?, chain: ConversionChain) : Converter
    class Normalizing(normalization: Converter, main: Converter) : Converter // 配置声明 normalization 时
    // 未来:Pipeline(stages: List<Converter>) —— 接口预留,实现后补
}
```

- `SingleStage` = 上游 `SingleStageConverter`:分词(可选)→ 每段过链;
- `Normalizing` = 上游 `ConfigBasedConverter`:normalization 链整输入
  预转换后交给主 converter;`inspect` 委托两段并合并表达(见 3.2);
- 组装职责在 **config 层**(`ConfigParser`),engine 不做配置语义。

### 3.2 数据流

```
input ──▶ normalization?(整输入转换链) ──▶ segmentation?(mmseg)
       ──▶ 逐段 ConversionChain(最长前缀替换;IDS 原子;不可起始字符
           批量跳过)──▶ output
```

`ConversionChain` 语义:阶段 N 的输出喂给 N+1;空链 = 恒等;中间阶段
缓冲、末段直写输出(上游 `AppendConvertedSegment` 语义)。

### 3.3 Inspection 模型

平铺前置段,不用上游的递归 `pipelineStages`(更贴合 inspect 作为
调试/工具设施的差异化定位;未来 `Pipeline` 落地时再泛化为树):

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

### 4.1 Dictionary 接口(刻意精简)

```kotlin
interface Dictionary {
    val maxKeyLength: Int                                        // UTF-16 码元
    fun matchPrefix(text: CharArray, start: Int, end: Int): PrefixMatch?
    fun matchExact(key: String): DictionaryEntry?                // 候选枚举等非热路径
    fun mayStartKey(codePoint: Int): Boolean                     // 批量跳过优化的等价物
}
```

设计立场(对齐上游的教训):上游 `Dict` 已膨胀至 9 个方法。Sokkuri
保持数据结构接口精简——**匹配策略与数据结构分离**:skip-table /
批量跳过等策略属 engine 内部的 `PrefixMatcher` 类(等价上游
`PrefixMatch::Tables`),按字典身份缓存,`Dictionary` 实现不强制关心。
**禁止**向 `Dictionary` 添加与数据无关的策略方法;新匹配能力优先
实现为 `PrefixMatcher` 的能力或基于现有方法的纯函数。
`matchPrefix` 结果不携带 key(见 8.1 的理由);`Dictionary`、
`PrefixMatch` 均为 internal,演进自由。

### 4.2 词典实现谱系

- `SortedListDictionary` = 上游 `TextDict`(有序数组 + 二分),inline 词典的参照实现,与 `.sok` 共享 `SortedTableRetrieval`
  检索算法;
- `.sok`(生产格式,M1 已落地)= `SokDictionary`:零拷贝 (保留文件字节、 按键/值偏移按需解码 UTF-8)、检索与
  `SortedListDictionary` 同算法, 注册 `"sok"` 类型, **编解码读写同侧**:decoder 与 encoder 同放
  `sokkuri-resource`(encoder 经 `@SokkuriInternalApi` 跨模块公开给 dictgen),`tools:dictgen` 编译全部打包词典——格式单一事实源,两端
  永不腐化;
- `ocd`/`ocd2` 以 `UnsupportedDictionaryFormat` 注册,报精确、可行动的
  错误。

### 4.3 缓存语义(有意偏离上游,已登记)

**进程级、强引用、共享**。key = 加载器标识 + `type:file`。

- 上游是 static `weak_ptr` + (mtime, size) 失效——那是桌面工具读
  可变文件的语义;Sokkuri 的词典是打包资源、不可变,无需失效;
- KMP common 没有统一弱引用,弱缓存跨平台做不了干净;
- 强引用共享 = 多 converter 天然共享词典内存,正是移动端所需;
- 加载器标识 = 加载器**实例身份**(首次登记分配 scope;平台默认
  加载器为单例,故同平台全部 `Sokkuri.create` 共享同一 scope;
  不同实例永不共享——为未来有状态的自定义 loader 保证隔离)。
- 身份登记表(loader → scope)**只增不减且持强引用**:scope 的稳定性
  依赖该表仍持有 loader(KMP 无弱引用,表才是持有者)。故真实上界是
  **曾传入过的** loader 实例数,不是存活数;当前成立只因平台默认
  loader 是单例(恒 1 条)。
- 违反前提的后果(扩展手册 #12 落地时必须先解决):调用方每次 create
  新建 loader,则登记表与 `cache` 双双无界增长——每个 loader 各自解出
  一整套词典(MB 级)并永久驻留——身份扫描亦退化为线性。处理方式二选
  一:给登记表定界,或改用以加载器自报稳定标识的键。

## 5. 扩展手册:后续新功能怎么改

> 每行给出改动位置与**明确禁止**触碰的部分。拿不准时回到第 2.3 节
> 的法则。

| #  | 场景                                    | 改动位置                                                                                                                      | 禁止                                                |
|----|-----------------------------------------|-------------------------------------------------------------------------------------------------------------------------------|-----------------------------------------------------|
| 1  | 新词典格式(`.sok` 及其他)               | resource:新 `Dictionary` 实现 + `DictionaryFormat`,注册 `DefaultDictionaryFormats`;编码器放同模块 internal                    | engine、config 改动;在 dictgen 另写编码器           |
| 2  | 新分词器(jieba 类)                      | engine:实现 `Segmentation`;config:`SegmentationProvider` 注册表接入(8.3);用户经 `Sokkuri.create` 高级重载注入                 | `Conversion`、热路径改动                            |
| 3  | 新配置字段                              | config:`ConfigDocument` 加字段 + `ConfigParser` 消费;上游语义优先;未知键继续忽略                                              | 改变已知键的宽容度(R8)                              |
| 4  | 新内置 profile(seal 类)                 | api:`SokkuriConfig` 枚举加法(遵守 I8);dictgen:词典 + 重写配置进 runtime 资源;README 与第 7 章登记                                    | 重排/删除枚举值                                     |
| 5  | 新 SokkuriOptions                              | api:`SokkuriOptions.Builder` 加 `var`;默认值对齐上游或登记偏离                                                                       | 构造函数加法(破坏 ABI)                              |
| 6  | 新转换能力(candidates / ambiguities 类) | engine:基于 `Dictionary.matchExact` 的纯函数;api:新类型;runtime:`Sokkuri` 委托                                                | `Dictionary` 接口膨胀(4.1)                          |
| 7  | 新平台 target(macOS/watchOS 等)         | 各模块 `build.gradle.kts` 加 target;resource:`SokkuriLock` actual(可抽 appleMain);runtime:手工接线 source set + loader actual | 新增层;api/engine/config 出现 actual(R5)            |
| 8  | 流式 chunked 转换                       | runtime 之上加有状态窗口包装(8.5);engine `Converter` 纯函数接口不动                                                           | 在 `Converter` 接口引入流式方法                     |
| 9  | `Inspection` 演进                       | 结构调整 = api 破坏性,major 版本管理;优先"加"不"改"                                                                           | minor 版本改公开签名                                |
| 10 | String view 匹配                        | **已实现(M3)**:`Dictionary` 加 sink 式 `matchAppend`(默认实现=旧行为),检索共享扫描双出口(internal,自由)                       | 破坏 `matchPrefix` 既有语义(默认实现与契约套件守门) |
| 11 | schema 校验                             | 按 8.2 注入 `ConfigValidator`(warn-only)                                                                                      | 改为硬失败(偏离上游语义)                            |
| 12 | 自定义资源加载                          | runtime:`create(config, options, loader)` 落地,`ResourceLoader` 提升为稳定公开 API;**前置**:按 4.3 给 loader 身份登记表定界或换键 | 把 loader 接口下放engine/config(R3)                 |

## 6. 不变量清单

违反以下任一条即缺陷,不视为"风格问题":

- **I1** 引擎索引与长度一律为 UTF-16 码元;码点只用于词典排序与 IDS 语义。
- **I2** 词典排序与二分共用 `Utf.compareByCodePoint`(码点序),禁止
  `String.compareTo` 混入。
- **I3** 词典与转换器构造后不可变、线程安全;converter 跨线程共享。
- **I4** 异常分类对齐上游(FileNotFound / InvalidFormat / InvalidConfig /
  Unsupported);新增错误类型须为 `SokkuriException` 子类,并同步登记进
  `Sokkuri.create` 的 `@Throws`(见 D9)。Kotlin/Native 会剪除未出现在任何
  导出签名里的类型,漏登记的子类对 Swift 不可见;但不致命——`@Throws`
  标记本身仍把异常桥接为 `NSError`,只是 `CreateResult.Failure.error` 的
  精确 cast 退化为基类。`SokkuriCreateThrowsContractTest`(jvmTest)守门。
- **I5** 全模块 `explicitApi()`;跨层 API 标注 `@SokkuriInternalApi`。
- **I6** 热路径零输入拷贝:引擎以 `CharArray` 窗口处理输入,只在写出时
  拼接(为 8.1 的 view 演进铺路)。
- **I7** JSONC 宽容度 = 上游 rapidjson flags 的等价集合,不多不少(R8)。
- **I8** `SokkuriConfig` 枚举只增、不删、不重排;新增值进入第 7 章登记表。

## 7. 上游对齐与偏离登记表

| #  | 点                                | 上游                       | Sokkuri                                                               | 理由                                  | 状态                  |
|----|-----------------------------------|----------------------------|-----------------------------------------------------------------------|---------------------------------------|-----------------------|
| 1  | 词典缓存                          | 进程级 weak + mtime 失效   | 进程级强引用共享                                                      | 资源不可变;KMP 无 common 弱引用       | 已实现                |
| 2  | tofu 词典默认                     | core 默认包含;CLI 默认排除 | 默认排除,`SokkuriOptions` 开启                                               | 移动端字体 tofu 风险                  | 已实现                |
| 3  | ocd / ocd2                        | 支持                       | `UnsupportedDictionaryFormat` 精确报错                                | 字节序/字宽不可移植                   | 已实现                |
| 4  | jieba / seal                      | 支持                       | 不移植                                                                | 范围控制                              | 架构预留(8.3/8.4)     |
| 5  | 流式转换                          | `ConverterStream`          | 不实现                                                                | 范围控制;接口兼容(8.5)                | 架构预留              |
| 6  | `matchPrefix` 携带 key/value view | 携带(string_view)          | sink 式 `matchAppend`:返回 length,值直写输出缓冲,无中间字符串(见 8.1) | Kotlin 无零拷贝 view;以 sink 替代视图 | 已实现(2026-09-30,M3) |
| 7  | schema 校验                       | warn-only                  | 不实现                                                                | 结构解码已覆盖净效应;见 8.2           | 架构预留              |
| 8  | inline 词典 `may_output_tofu`     | 报错                       | 报错                                                                  | 对齐                                  | 已实现                |
| 9  | `PipelineConverter`               | 支持                       | 接口预留                                                              | 无多 stage 配置需求                   | 架构预留              |
| 10 | 插件动态库加载                    | dlopen                     | 永不                                                                  | KMP 无统一 ABI;改走注册表注入         | 已决                  |

## 8. 设计预留:架构保证可添加

> 8.1 (String view 匹配)已于 2026-09-30 由 M3 落地,该节保留为
> 已实现形态的记录;其余各节仍为预留。

### 8.1 String view 匹配

**状态:已实现 (2026-09-30,M3)**。实现形态 = 立项时备选三态中的 **sink 式**(本节目标形态描述按实现现状改写):

- `Dictionary` 增加 `matchAppend(text, start, end, out): Int`——命中时 把默认 candidate 直写 `out` 并返回命中长度 (UTF-16
  码元),未命中 返回 -1; **默认实现 = `matchPrefix` + append**,storage-aware 后端覆写;
- `SortedTableRetrieval` 的 `matchPrefix` 与 `matchAppend` 共享同一个
  `findLongestPrefixIndex` 扫描 (下界 + 组扫单源),胜者选择不可能分叉, 只有值的出口不同 (物化 vs 直写);
- 键表泛化为 `SortedKeyTable`(首码点 / 码点序比较 / 前缀判定 / UTF-16 长度 / 值的物化与直写两个出口):String 后端引用直达,
  `.sok` byte 后端 经 `Utf8` 原语 (UTF-8 区段 vs CharArray 窗口直接码点比较,探针零解码, 非法输入全序降级 U+FFFD);
  `SortedListDictionary` 无需跟进 (默认实现 即旧行为);
- `DictGroup` 不转发 sink (union/short_circuit 先经子 `matchPrefix`
  定胜负,胜者物化一次)——组语义天然由默认实现获得;
- `Conversion` 热路径 (I6 写出点)切换为 `matchAppend`;Inspection、
  `matchExact`、mmseg 接口不变。

**实测 (m3 §8,同机 vs 开工基线)**:s2t medium 21.42 → 9.4µs (2.3×), 重档 2.3–2.6×;零拷贝不回退 (`decode`
sokHeapKiB≈0,无任何解码缓存); inspect/convert 开销比 1.05。

**教训 (立项假设的修正)**:① JVM escape analysis 使命中值物化免费,
"值直写"层在 JVM 实测 **零收益**(其收益应在 Native,未实测);② 最大 单项收益来自组扫 **双扫描融合**(前缀命中才算长度);③
剩余差距 (文本时代 7.6µs)判定为零拷贝逐字节解码的固有 ALU 代价——进一步需 双数组 trie 检索 (= `.sok` 格式 v2)或解码缓存
(违零拷贝),均不在 本节范围。M3 性能门槛经裁决由"≤8µs 对齐文本时代"修正为"≥2.2× vs 开工基线"(2.3× 达成),完整归因见
m3-string-view.md §8。

### 8.2 配置 schema 校验

**目标形态**:注入式校验——`ConfigParser` 构造接受可选
`ConfigValidator`(函数接口:配置 JSON 字符串 → 警告列表),默认不启用;
语义对齐上游 warn-only(校验失败不拒绝解析)。

**v1 已铺好的路**:`ConfigDocument` 模型即 schema 的形状;`JsonSupport`
是唯一切入点;`ConfigParser` 为 internal,构造可加参。

**届时改动清单**:① 定义 `ConfigValidator` 接口;② schema 文档随
资源打包;③ 实现校验器;④ 决定警告出口——**不做上游的
`fprintf(stderr)`**(库规范):由注入方决定丢弃或透传,`Sokkuri` 层
默认丢弃;⑤ 登记表 #7 状态翻转。

### 8.3 jieba / 插件分词

**目标形态**:注册表注入,不做动态库加载(KMP 无统一 ABI,登记 #10)。
config 层不硬编码分词器清单:除内建 `mmseg` 外,类型名查
`SegmentationProvider`(`fun interface`:类型名 + 字符串配置对 →
`Segmentation?`),查不到报 `Unsupported`——与上游
`PluginSegmentation` 的配置对(config pairs)同构。

**届时改动清单**:① `SegmentationDocument` 捕获额外字符串键并传给
provider(需自定义解析,上游行为:所有额外字符串属性成为配置对);
② `ConfigParser` 构造加 provider 参数;③ engine 实现 `Segmentation`;
④ `Sokkuri.create` 高级重载接受注册表(与 #12 自定义 loader 同一次
API 扩展落地)。

### 8.4 seal 配置档

纯数据路线,无代码改动:① `SokkuriConfig` 枚举加值(遵守 I8);② dictgen
从上游 `SealCharacters` / `SealVariants` 生成 `.sok`;③ 重写配置进
runtime 资源;④ README/登记表更新。对 exhaustive `when` 消费者是
源码级影响,minor 版本可接受。

### 8.5 流式 chunked 转换

**目标形态**:`Sokkuri` 之上的有状态包装:每 chunk 保留尾部 16 码点
窗口,且窗口终点须落在完整 IDS 边界外(上游 `ConverterStream`
语义),防短语/IDS 跨 chunk 被切。engine `Converter` 纯函数接口不动。

**届时改动清单**:① runtime 或 api 层新增流式包装类(显式
`convert(chunk)` / `finish()`,文档注明单线程使用);② 对照上游
`ConverterStream` 测试语义;③ 登记表 #5 状态翻转。

## 9. 决策实现状态总表

| 决策 | 内容                                                       | 代码状态      |
|----|----------------------------------------------------------|-----------|
| D1 | `Converter` 封闭接口化(SingleStage / Normalizing,Pipeline 预留) | 已实现       |
| D2 | `Inspection` 平铺 `normalizationStages`                    | 已实现       |
| D3 | 进程级强引用共享词典缓存(加载器实例 scope)                                | 已实现       |
| D4 | `SokkuriOptions` 改 Builder 模式                            | 已实现       |
| D5 | inline 词典 `may_output_tofu` 严格报错                         | 已实现       |
| D6 | `.sok` 编解码读写同侧                                           | 已实现       |
| D7 | 流式转换不进 v1                                                | 架构预留(8.5) |
| D8 | 自定义 loader 公开化路径(扩展手册 #12)                               | 架构预留      |
| D9 | 错误上报双通道:`create` 抛异常 + `CreateResult` 非抛路径(Apple 边界)     | 已实现       |

## 10. 测试与对齐策略

- **三层测试**:engine 纯函数单测;config 语义测试(fake
  `DictionaryProvider`,覆盖组策略 / tofu 过滤 / normalization 序 /
  JSONC);runtime 集成测试(平台 loader 真实路径)。
- **golden 对齐**(词典落地后立即接入):以 `OpenCC/test/testcases/
  testcases.json` 为最终防线,全部 16 个 profile 逐条比对 convert 输出。
- **专项**:IDS / UTF-16 边界 fuzz(unpaired surrogate、越界 IDS 树);
  词典缓存并发测试;Android host 与 device 路径都须覆盖。
- `OpenCC/` 参考克隆是 spec 与测试语料源,**禁止提交、禁止修改**。
