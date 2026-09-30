# Sokkuri

Sokkuri（すっきり / 晰）是 [OpenCC](https://github.com/BYVoid/OpenCC)（开放中文转换）的
Kotlin Multiplatform 移植，目标平台为 JVM、Android 与 iOS。行为对齐 OpenCC 的 C++
核心：相同的转换管线（归一化 → 可选 mmseg 分词 → 转换链）、相同的词典语义
（最长前缀匹配、`short_circuit` / `union` 组策略）、相同的 IDS
（表意文字描述序列）处理——并额外暴露分级转换结果（`inspect`），
这是 OpenCC 本身没有的能力。

## 模块结构

一个家族五个 Gradle 模块，统一发布在 `com.generalk1ng.sokkuri` 坐标下。
依赖方向严格向下，下层绝不依赖上层。

```
sokkuri-runtime    聚合门面 + 打包词典（config/、*.sok）
└── sokkuri-resource  资源加载接口 + 词典格式注册
    └── sokkuri-config  JSONC 配置解析（OpenCC data/config 模式）
        └── sokkuri-engine  纯转换引擎：UTF/IDS、词典、
            │              词典组、转换链、mmseg 分词
            └── sokkuri-api  公开 API：Config 预设配置、Options、
                           Inspection、异常体系、SokkuriInternalApi
```

- **sokkuri-api** —— 应用开发者需要的全部内容：`Config`（OpenCC 内置的
  16 种配置档）、`Options`、`Inspection`、异常体系，以及
  `@SokkuriInternalApi` 标记（ opting-in 后才可见，避免下层 API 混进日常补全）。
- **sokkuri-engine** —— OpenCC 算法移植，无 IO 的纯 Kotlin：
  码点序、有序数组词典（等价 `TextDict`）、组策略、带 IDS 原子性的
  最长前缀转换、mmseg 最大正向分词、分阶段转换器。
- **sokkuri-config** —— OpenCC `data/config/*.json` 文档的宽松
  （JSONC）解析器，包含 tofu 风险词典过滤与 match-policy 校验，
  通过 `DictionaryProvider` 接口落地词典加载。
- **sokkuri-resource** —— 唯一感知 IO 的层：`ResourceLoader` 接口、 文本/`.sok` 词典格式注册表（`.sok` 零拷贝、编解码读写同侧），以及
  带锁带缓存的 `ResourceDictionaryProvider`（等价 OpenCC 的 `DictCache`）。
- **sokkuri-runtime** —— 消费者唯一需要依赖的聚合模块。承载平台默认
  资源加载器（JVM 走 classpath，Android 走 assets 并需
  `Sokkuri.init(context)`，iOS 走 bundle），打包词典数据 （`config/*.json` + `dictionary/*.sok`）落在这里。

架构的完整约定（模块边界、依赖法则、扩展方式、上游偏离登记）见
[docs/architecture.md](docs/architecture.md)。

## 内置配置与词典数据

`Sokkuri.create` 读取打包资源中的 `config/<stem>.json`。注意这些是**生成 产物**：由 `tools/dictgen` 构建工具（
`./gradlew :tools:dictgen:run`）将 OpenCC 上游配置重写为引用 `.sok` 词典、并把上游词典表编译为 `.sok`， 并非上游原文。
`./gradlew :tools:dictgen:checkDictionaries` 可随时校验 打包产物与上游源零漂移（重跑确定性由它保证）。

## 使用

```kotlin
val converter = Sokkuri.create(Config.S2TWP)          // 简 → 台（含在地词）
val output = converter.convert("鼠标里面的硅二极管坏了")
val inspection = converter.inspect(input)             // 逐级分段结果
```

Android 应用须在启动时调用一次 `Sokkuri.init(context)`，
以便定位打包在 assets 中的词典。

## 构建与测试

```bash
./gradlew build                              # 全平台构建
./gradlew :sokkuri-runtime:jvmTest           # JVM 测试
./gradlew :sokkuri-runtime:iosSimulatorArm64Test   # iOS 测试（Apple Silicon 主机）
./gradlew :sokkuri-runtime:testAndroidHostTest     # Android 宿主单元测试（JVM）
./gradlew :tools:dictgen:checkDictionaries       # 词典产物 vs 上游源 漂移校验
./gradlew :tools:benchmark:run --args="convert --profile all"   # 性能基线（convert/create/decode/inspect 四子命令，--help 自描述）
```

## 当前状态

**16 个 profile 端到端可用**（M1 已交付）：全部词典以自研 `.sok` 二进制 格式打包（零拷贝解码，冷启动 create ≈22ms、驻留堆
≈2.4MiB），golden 对齐上游 `testcases.json` 全量语料——16 个移植 stem × 553 条期望， JVM / Android 宿主 / iOS 模拟器三平台全绿（3
条例外经逐案分析登记为 tofu 风险词典的刻意分叉，开启 `includeTofuRiskDictionaries` 后全部消解）。
`tools:dictgen` 词典编译器与配置重写器已落地，打包产物可由
`checkDictionaries` 持续校验。 **String-view 化已完成**：`.sok` 词典检索全程 零物化（探针零解码、命中值直写输出），转换吞吐较优化前提升
2.3–2.6×（s2t 21.4→9.4µs/op， 同机基线），零拷贝驻留堆不变；剩余差距为零拷贝解码的固有代价，进一步需 trie 检索结构。
