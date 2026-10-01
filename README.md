# Sokkuri

Sokkuri 是 [OpenCC](https://github.com/BYVoid/OpenCC) 的 Kotlin Multiplatform 移植，覆盖 JVM、Android、iOS 三个平台，行为与上游
C++ 核心保持一致。

名称取自日语「すっきり」（sokkuri）——清爽、利落、一目了然；汉字取「晰」， 寓意转换结果清晰、无歧义。

## 特性

- **纯 Kotlin 实现，无原生依赖。** OpenCC 官方仅提供 C++ 库，社区已有移植只覆盖 Apple 平台。Sokkuri 不经过
  JNI，后端与移动端由此共享同一套实现和词典数据，行为跨端一致。
- **`.sok` 零拷贝词典格式。** 词典驻留内存约等于文件原始大小（16 档合计约 2.4 MiB）；检索直接以字节区段比对输入，命中值写入输出缓冲，全程不产生中间字符串。s2t
  实测 9.4 µs/op，较物化实现快 2.3–2.6 倍。
- **`inspect()` 分级检查。** 除最终字符串外，返回分词结果与转换链各级的分段输出（OpenCC 仅给出最终结果），可用于高亮、diff
  与转换过程分析。
- **与上游逐条对齐。** 测试套件移植自 OpenCC `testcases.json` 全部 553 条期望，JVM / Android 宿主 / iOS 模拟器三端全绿；与上游仅有的
  3 处差异（tofu 风险词典默认排除，与 CLI 默认行为一致）已逐案登记，一个开关即可完全对齐。
- **16 种转换配置**，含上游较新的香港词汇档 `s2hkp` / `hk2sp`。

## 安装

Maven Central 坐标：

```kotlin
repositories { mavenCentral() }

dependencies {
    implementation("com.generalk1ng.sokkuri:sokkuri-runtime:0.1.0")
}
```

只需 `sokkuri-runtime` 一个依赖，其余模块经传递引入。JVM 与 Android 直接使用；Android 需在启动时调用一次
`Sokkuri.init(context)`。

iOS 端的词典随 klib 以 `kotlin_resources` 变体投递：应用了 Compose Multiplatform 插件的工程无需额外配置；纯 KMP 工程需在
shared 模块将资源接入 compilation 并拷入 framework 产物（Kotlin/Native 不会自动拷贝资源）：

```kotlin
// shared/build.gradle.kts
import org.jetbrains.kotlin.gradle.ComposeKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.plugin.extraProperties
import org.jetbrains.kotlin.gradle.plugin.mpp.Framework
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.resources.KotlinTargetResourcesPublication

@OptIn(ComposeKotlinGradlePluginApi::class)
kotlin.targets.withType<KotlinNativeTarget>().configureEach {
    val kmpResources = project.extraProperties
        .get(KotlinTargetResourcesPublication.EXTENSION_NAME) as KotlinTargetResourcesPublication
    val sokkuriResources = kmpResources.resolveResources(this)

    compilations["main"].defaultSourceSet.resources.srcDir(sokkuriResources)

    // 词典资源拷入 framework bundle（iOS loader 经 NSBundle 读取）
    binaries.withType<Framework>().configureEach {
        val copyDictionaries = tasks.register<Copy>("copySokkuriDictionaries") {
            from(sokkuriResources)
            into(outputFile)
        }
        linkTaskProvider.configure { finalizedBy(copyDictionaries) }
    }
}
```

模拟器与真机单元测试同理，将 `sokkuriResources` 拷至测试二进制所在目录即可（本仓库 sokkuri-runtime 构建脚本中的
`copyResourcesBeside*` 任务可作参考）。

## 使用

```kotlin
val converter = Sokkuri.create(Config.S2TWP)   // 简体 → 台湾正体（含台湾惯用词）
converter.convert("鼠标里面的硅二极管坏了，导致光标分辨率降低。")
// → 滑鼠裡面的矽二極體壞了，導致游標解析度降低。
```

16 种配置：`s` 简体、`t` 繁体、`tw` 台湾、`hk` 香港、`jp` 日文，后缀 `p` 表示包含在地词汇。

| 简 → 繁               | 繁 → 简 | 繁体互转        | 日文                   |
|-----------------------|---------|-----------------|------------------------|
| `s2t` 通用繁体        | `t2s`   | `t2tw` / `tw2t` | `jp2t` 新字体 → 旧字体 |
| `s2tw` 台湾字形       | `tw2s`  | `t2hk` / `hk2t` | `t2jp` 旧字体 → 新字体 |
| `s2twp` 台湾字形+词汇 | `tw2sp` |                 |                        |
| `s2hk` 香港字形       | `hk2s`  |                 |                        |
| `s2hkp` 香港字形+词汇 | `hk2sp` |                 |                        |

同一输入在各档下的差异：

```
内存里的一只烤面包机正在读取打印服务器的硬盘。
s2t   內存裏的一隻烤麪包機正在讀取打印服務器的硬盤。
s2hk  內存裏的一隻烤麪包機正在讀取打印服務器的硬盤。
s2tw  內存裡的一隻烤麵包機正在讀取打印服務器的硬盤。
s2twp 記憶體裡的一隻烤麵包機正在讀取列印伺服器的硬碟。
```

`inspect()` 返回转换过程：

```kotlin
val inspection = Sokkuri.create(Config.S2T).inspect("软件和网络")
inspection.segments  // [软件和网络]：分词结果
inspection.stages    // stage 1: [軟件和網絡]：转换链各级输出
inspection.output    // 軟件和網絡
```

繁转简方向默认排除可能在部分设备上显示为"豆腐块"的极端字映射；如需与上游测试语料完全一致：

```kotlin
Sokkuri.create(Config.T2S, Options { includeTofuRiskDictionaries = true })
```

## 测试

```bash
./gradlew build                                   # 全平台构建与测试
./gradlew :sokkuri-runtime:jvmTest                # JVM
./gradlew :sokkuri-runtime:iosSimulatorArm64Test  # iOS 模拟器（Apple Silicon 主机）
./gradlew :sokkuri-runtime:testAndroidHostTest    # Android 宿主单元测试
./gradlew :tools:dictgen:checkDictionaries        # 打包词典对上游的漂移检查
./gradlew :tools:benchmark:run --args="convert --profile all"   # 性能基准
```

- golden 套件：OpenCC `testcases.json` 553 条期望 × 16 档配置，三端运行；
- 词典契约测试：`.sok` 解码校验、文本/二进制双后端检索行为等价、编码往返确定性；
- `ConsumerTryoutTest`：每个 profile 的输入输出示例，可直接运行查看；
- 性能基线（同机实测）：`.sok` 解码驻留堆增量 ≈ 0，冷启动建档约 45 ms，s2t 转换 9.4 µs/op。

## License

Apache License 2.0
