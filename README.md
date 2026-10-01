# Sokkuri

**Sokkuri（すっきり / 晰）** 是 [OpenCC](https://github.com/BYVoid/OpenCC)（开放中文转换）的 Kotlin Multiplatform 移植，一套代码运行在
**JVM、Android、iOS** 三个平台。

名称取自日语「すっきり」（sokkuri）——清爽、利落、一目了然；汉字取「晰」， 寓意转换结果清晰、无歧义。

简繁转换从来不是一一对应的机械替换：简转繁有通用、台湾、香港多套字形标准， 词汇在各地又各有说法（内存/記憶體、硬盘/硬碟/隨身碟）；繁转简要处理多对一的
合并字；日文还有新字体与旧字体之别。Sokkuri 完整移植 OpenCC 的 **16 种转换 配置**，并以 OpenCC 上游测试语料逐条对齐行为。

## 独特优势

- **真多平台**：纯 Kotlin 实现，无 JNI、无平台原生二进制依赖。OpenCC 官方为 C++，社区 Swift 移植只覆盖 Apple 生态；Sokkuri
  让后端与两个移动端共用同一 份词典数据和同一份算法实现。
- **`inspect()` 结构化结果**：除了最终字符串，还返回分词结果与转换链每一级 的分段输出（OpenCC 只有最终字符串），可直接驱动高亮、diff、转换溯源等
  场景。
- **零拷贝词典**：自研 `.sok` 二进制格式，词典驻留内存 ≈ 文件原始大小 （16 档全部词典 ≈ 2.4 MiB）；检索全程零物化——探针直接比较字节区段与
  输入窗口，命中值直写输出缓冲，s2t 实测 9.4µs/op（较物化实现快 2.3–2.6×）。
- **与上游逐条对齐**：golden 测试套件移植 OpenCC `testcases.json` 全部 553 条期望，JVM / Android 宿主 / iOS 模拟器三端全绿；与上游仅有的
  3 处 差异（tofu 风险词典默认排除，与 OpenCC CLI 默认一致）逐案登记在案， 一个开关即可消解。
- **配置齐全**：16 档含上游较新的香港词汇档（`s2hkp` / `hk2sp`）。

## 安装

Maven Central 坐标（只需一个依赖，传递引入其余四个模块）:

```kotlin
// build.gradle.kts
repositories { mavenCentral() }

dependencies {
    implementation("com.generalk1ng.sokkuri:sokkuri-runtime:0.1.0")
}
```

JVM / Android 开箱即用（Android 记得 `Sokkuri.init(context)`）。iOS 端的词典随 klib 以 `kotlin_resources` variant 投递：应用了 Compose Multiplatform 插件的工程零配置；纯 KMP 工程在 shared 模块加一段接线——把资源引入 compilation，并显式拷入 framework 产物包（Kotlin/Native 不会自动拷）:

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

    // 词典资源拷进 framework bundle（iOS loader 经 NSBundle 读取）
    binaries.withType<Framework>().configureEach {
        val copyDictionaries = tasks.register<Copy>("copySokkuriDictionaries") {
            from(sokkuriResources)
            into(outputFile)
        }
        linkTaskProvider.configure { finalizedBy(copyDictionaries) }
    }
}
```

模拟器/真机单元测试同理，把 `sokkuriResources` 拷到测试二进制所在目录即可（本仓 sokkuri-runtime 构建脚本里的 `copyResourcesBeside*` 任务可作参照）。

## 使用

```kotlin
val converter = Sokkuri.create(Config.S2TWP)   // 简体 → 台湾正体（含台湾词汇）
converter.convert("鼠标里面的硅二极管坏了，导致光标分辨率降低。")
// → 滑鼠裡面的矽二極體壞了，導致游標解析度降低。
```

16 种配置，`s`=简体、`t`=繁体、`tw`=台湾、`hk`=香港、`jp`=日文、`p`=含在地词汇：

| 简 → 繁               | 繁 → 简 | 繁体互转        | 日文                 |
|-----------------------|---------|-----------------|----------------------|
| `s2t` 通用繁体        | `t2s`   | `t2tw` / `tw2t` | `jp2t` 新字体→旧字体 |
| `s2tw` 台湾字形       | `tw2s`  | `t2hk` / `hk2t` | `t2jp` 旧字体→新字体 |
| `s2twp` 台湾字形+词汇 | `tw2sp` |                 |                      |
| `s2hk` 香港字形       | `hk2s`  |                 |                      |
| `s2hkp` 香港字形+词汇 | `hk2sp` |                 |                      |

同一句简体，各档实测输出：

```
输入  内存里的一只烤面包机正在读取打印服务器的硬盘。
s2t   內存裏的一隻烤麪包機正在讀取打印服務器的硬盤。
s2hk  內存裏的一隻烤麪包機正在讀取打印服務器的硬盤。
s2tw  內存裡的一隻烤麵包機正在讀取打印服務器的硬盤。
s2twp 記憶體裡的一隻烤麵包機正在讀取列印伺服器的硬碟。
```

`inspect()` 查看分级转换过程：

```kotlin
val inspection = Sokkuri.create(Config.S2T).inspect("软件和网络")
inspection.segments  // [软件和网络]         ← 分词结果
inspection.stages    // stage 1: [軟件和網絡] ← 转换链每级输出
inspection.output    // 軟件和網絡
```

繁 → 简方向默认排除可能在部分设备上显示为缺字"豆腐块"的极端字映射；需要与上游测试语料完全一致时打开开关：

```kotlin
val converter = Sokkuri.create(
    Config.T2S,
    Options { includeTofuRiskDictionaries = true },
)
```

Android 应用须在启动时调用一次 `Sokkuri.init(context)`，以便定位打包在 assets 中的词典；JVM 走 classpath，iOS 走 framework
bundle，均开箱即用。

## 测试与演示

```bash
./gradlew build                                   # 全平台构建 + 全部测试
./gradlew :sokkuri-runtime:jvmTest                # JVM 测试
./gradlew :sokkuri-runtime:iosSimulatorArm64Test  # iOS 模拟器（Apple Silicon 主机）
./gradlew :sokkuri-runtime:testAndroidHostTest    # Android 宿主单元测试
```

- **golden 套件**：OpenCC `testcases.json` 553 条期望 × 16 档配置， 三端运行；
- **词典契约测试**：`.sok` 格式解码校验、文本/二进制双后端检索行为 等价、编码器往返确定性；
- **试用演示** `ConsumerTryoutTest`：每个 profile 的输入输出示例，可直接 运行查看；
- **性能基线**（同机实测）：`.sok` 解码 ≈0ms、堆增量 ≈0（零拷贝实证）， 冷启动建档 ≈45ms，s2t 转换 9.4µs/op。
