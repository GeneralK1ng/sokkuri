# Sokkuri

Sokkuri 是 [OpenCC](https://github.com/BYVoid/OpenCC) 的 Kotlin Multiplatform 移植，覆盖 JVM、Android 与 iOS 三个平台，行为与上游 C++ 核心保持一致。

名称取自日语「すっきり」，意为清爽、利落、一目了然；汉字取「晰」，寓意转换结果清晰、无歧义。

## 特性

- **纯 Kotlin 实现，无原生依赖。** OpenCC 官方提供 C++ 核心、C 接口、命令行工具，以及 Python 与 Node.js 绑定，社区另有 Java、Go、WebAssembly、Swift 等移植。Sokkuri 让 JVM、Android 与 iOS 共享同一套 Kotlin 实现与词典数据，不经过 JNI，行为跨端一致。
- **`.sok` 零拷贝词典格式。** 16 档词典合计约 1.7 MiB，驻留内存约等于文件原始大小；检索直接以字节区段比对输入，命中值写入输出缓冲，全程不产生中间字符串。s2t 实测 9.4 µs/op，较物化实现快 2.3 倍，重档可达 2.3 至 2.6 倍。
- **`inspect()` 分级检查。** 除最终字符串外，同时返回分词结果与转换链各级的分段输出，可用于高亮、diff 与转换过程分析；上游仅提供最终结果。
- **与上游逐条对齐。** 测试套件移植自 OpenCC `testcases.json` 全部 568 条期望，在 JVM、Android 宿主与 iOS 模拟器三端全绿。其中仅 3 条期望在默认选项下与上游不同，均由 tofu 风险词典引起，已逐条登记；开启 `includeTofuRiskDictionaries` 即可完全对齐。
- **16 种转换配置**，含上游较新的香港词汇档 `s2hkp` 与 `hk2sp`。

## 安装

Maven Central 坐标：

```kotlin
repositories { mavenCentral() }

dependencies {
  implementation("com.generalk1ng.sokkuri:sokkuri-runtime:0.2.0")
}
```

只需 `sokkuri-runtime` 一个依赖，其余模块经传递引入。

编译环境要求 Kotlin 2.4.20 及以上。元数据版本为 2.4.0，使用更低 Kotlin 版本的工程会在解析依赖时报元数据不兼容；AGP 9 内置的 Kotlin 2.2.0 因此暂时无法消费本库，需改用 Kotlin Multiplatform 插件路径，或等待 AGP 跟进。

JVM 与 Android 直接使用。Android 需在启动时调用一次 `Sokkuri.init(context)`。

iOS 端的词典随 klib 以 `kotlin_resources` 变体投递。应用了 Compose Multiplatform 插件的工程无需额外配置；纯 KMP 工程需在 shared 模块将资源接入 compilation 并拷入 framework 产物，因为 Kotlin/Native 不会自动拷贝资源：

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

模拟器与真机单元测试同理，将 `sokkuriResources` 拷至测试二进制所在目录即可；本仓库 `sokkuri-runtime` 构建脚本中的 `copyResourcesBeside*` 任务可作参考。

## 使用

```kotlin
val converter = Sokkuri.create(SokkuriConfig.S2TWP)   // 简体 → 台湾正体，含台湾惯用词
converter.convert("鼠标里面的硅二极管坏了，导致光标分辨率降低。")
// → 滑鼠裡面的矽二極體壞了，導致游標解析度降低。
```

16 种配置中，`s` 表示简体、`t` 表示繁体、`tw` 表示台湾、`hk` 表示香港、`jp` 表示日文，后缀 `p` 表示同时转换在地词汇。

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
val inspection = Sokkuri.create(SokkuriConfig.S2T).inspect("软件和网络")
inspection.segments  // [软件和网络]：分词结果
inspection.stages    // stage 1: [軟件和網絡]：转换链各级输出
inspection.output    // 軟件和網絡
```

繁转简方向默认排除可能在部分设备上显示为豆腐块的极端字映射；如需与上游测试语料完全一致：

```kotlin
Sokkuri.create(SokkuriConfig.T2S, SokkuriOptions { includeTofuRiskDictionaries = true })
```

### iOS（Swift）

推荐使用 `createResult`。它不抛异常，返回一个可精确分支的 `Sokkuri.CreateResult`：

```swift
import Sokkuri

let config = Sokkuri_apiSokkuriConfig.companion.fromStem(stem: "s2twp")!
let result = Sokkuri.Companion.shared.createResult(
    config: config,
    options: Sokkuri_apiSokkuriOptions.companion.DEFAULT,
)

if let ok = result as? Sokkuri.CreateResultSuccess {
    print(ok.sokkuri.convert(input: "鼠标里面的硅二极管坏了"))
} else if let failure = result as? Sokkuri.CreateResultFailure {
    // failure.error 声明为基类，但可精确 cast 到具体子类
    if let missing = failure.error as? Sokkuri_apiSokkuriException.FileNotFound {
        print("缺少资源：\(missing.path)")
    }
}
```

`create` 同样可用，只是会抛异常，Swift 调用点需加 `try`。它存在的意义是保证异常不会终止进程：**未标注 `@Throws` 的 Kotlin 函数一旦抛异常，Kotlin/Native 会直接终止程序并输出 `Program will be terminated`，而不是把异常交给调用方。**

```swift
do {
    let sokkuri = try Sokkuri.Companion.shared.create(config: config, options: options)
} catch let error as NSError {
    // Kotlin 异常一律以 NSError 形态到达，domain 为 "KotlinException"。
    // `catch let e as SokkuriException` 能编译但永不匹配，只能这样取回：
    print(error.kotlinException ?? error)
}
```

另有两点需要注意。其一，类型名带模块前缀：`sokkuri-api` 模块中的公开类型在 Swift 侧是 `Sokkuri_apiSokkuriConfig`、`Sokkuri_apiSokkuriOptions` 与 `Sokkuri_apiSokkuriException`，主模块的 `Sokkuri` 与 `Sokkuri.CreateResult` 不带前缀。其二，sealed 类过桥后成为普通 Objective-C 类，不能穷尽 `switch`，只能用 `as?` 分支，编译器不检查穷尽性。

## 测试

```bash
./gradlew build                                   # 全平台构建与测试
./gradlew :sokkuri-runtime:jvmTest                # JVM
./gradlew :sokkuri-runtime:iosSimulatorArm64Test  # iOS 模拟器，需 Apple Silicon 主机
./gradlew :sokkuri-runtime:testAndroidHostTest    # Android 宿主单元测试
./gradlew :tools:dictgen:checkDictionaries        # 打包词典对上游的漂移检查
./gradlew :tools:benchmark:run --args="convert --profile all"   # 性能基准
```

- golden 套件：OpenCC `testcases.json` 的 568 条期望，覆盖 16 档，逐档 4 至 108 条，三端运行；
- 词典契约测试：`.sok` 解码校验、文本与二进制双后端检索行为等价、编码往返确定性；
- `inspect()` 结构校验：分词与转换链各级分段满足平铺不变式，且与 `convert()` 结果一致；
- 性能基线，同机实测：`.sok` 解码驻留堆增量约为 0，冷启动建档约 50 ms，s2t 转换 9.4 µs/op。

漂移检查不在 `build` 任务图中，也依赖仓库根目录下的 OpenCC 参考克隆，因此需在本地准备了该克隆后手动运行；发布门禁 `./gradlew build` 不包含它。

## License

Apache License 2.0
