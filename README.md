# Sokkuri

Sokkuri 是 [OpenCC](https://github.com/BYVoid/OpenCC) 的 Kotlin Multiplatform 移植，支持 JVM、Android 与 iOS，转换结果与上游 C++ 核心一致。

名称取自日语「すっきり」（清爽、利落），汉字取「晰」。

## 特性

- **纯 Kotlin，没有原生依赖**：三端共用同一套实现与词典，不经过 JNI。
- **与上游一致**：上游测试用例的 569 条期望在三端运行，默认选项下只有 3 条因 tofu 设置而有意不同（见下文）；与 OpenCC 参考构建逐条比对 16 档、近 1.9 万条输入，转换结果与 inspect 结构都没有差异。
- **`inspect()`**：除了最终结果，还返回分词与转换链各级的输出，结构与上游 `Converter::Inspect` 一致。上游只在 C++ 与命令行中提供这项能力。
- **16 种转换配置**，包括香港词汇档 `s2hkp` 与 `hk2sp`。
- **零拷贝的词典格式 `.sok`**：词典共约 1.7 MiB，加载时不复制。s2t 转换 48 个字约 1.8 µs（JVM）。详见[性能](docs/performance/README.md)。

## 安装

```kotlin
repositories { mavenCentral() }

dependencies {
  implementation("com.generalk1ng.sokkuri:sokkuri-runtime:0.2.1")
}
```

- 只需依赖 `sokkuri-runtime`，其余模块会传递引入。
- 需要 Kotlin 2.4.20 或更高版本：库的元数据版本为 2.4.0，更低版本的 Kotlin 会报元数据不兼容。AGP 9 内置的 Kotlin 2.2.0 因此暂时用不了本库，可以改用 Kotlin Multiplatform 插件。
- Android：启动时调用一次 `Sokkuri.init(context)`。

### iOS 的词典资源

词典随 klib 以 `kotlin_resources` 变体发布。用了 Compose Multiplatform 插件的工程不需要额外配置。纯 KMP 工程要在 shared 模块里把资源接入编译，并拷进 framework，因为 Kotlin/Native 不会自动拷贝资源：

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

    // 把词典拷进 framework，iOS 的加载器经 NSBundle 读取
    binaries.withType<Framework>().configureEach {
        val copyDictionaries = tasks.register<Copy>("copySokkuriDictionaries") {
            from(sokkuriResources)
            into(outputFile)
        }
        linkTaskProvider.configure { finalizedBy(copyDictionaries) }
    }
}
```

单元测试同理：把 `sokkuriResources` 拷到测试二进制所在的目录，可参考本仓库 `sokkuri-runtime` 构建脚本里的 `copyResourcesBeside*` 任务。

## 使用

```kotlin
val converter = Sokkuri.create(SokkuriConfig.S2TWP)   // 简体 → 台湾正体，含台湾惯用词
converter.convert("鼠标里面的硅二极管坏了，导致光标分辨率降低。")
// → 滑鼠裡面的矽二極體壞了，導致游標解析度降低。
```

配置名中 `s` 为简体，`t` 为繁体，`tw` 为台湾，`hk` 为香港，`jp` 为日文；后缀 `p` 表示同时转换地区惯用词。

| 简 → 繁 | 繁 → 简 | 繁体互转 | 日文 |
|---|---|---|---|
| `s2t` 通用繁体 | `t2s` | `t2tw` / `tw2t` | `jp2t` 新字体 → 旧字体 |
| `s2tw` 台湾字形 | `tw2s` | `t2hk` / `hk2t` | `t2jp` 旧字体 → 新字体 |
| `s2twp` 台湾字形与词汇 | `tw2sp` | | |
| `s2hk` 香港字形 | `hk2s` | | |
| `s2hkp` 香港字形与词汇 | `hk2sp` | | |

同一句话在各档下的结果：

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
inspection.stages    // stage 1: [軟件和網絡]：转换链各级的输出
inspection.output    // 軟件和網絡
```

繁转简默认不使用少数可能在部分设备上显示为豆腐块（□）的字形映射，因此有 3 条上游测试期望与上游不同。需要与上游完全一致时：

```kotlin
Sokkuri.create(SokkuriConfig.T2S, SokkuriOptions { includeTofuRiskDictionaries = true })
```

### Swift

推荐用 `createResult`，它不抛异常：

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
    // failure.error 声明为基类，可以 cast 到具体的子类
    if let missing = failure.error as? Sokkuri_apiSokkuriException.FileNotFound {
        print("缺少资源：\(missing.path)")
    }
}
```

也可以用 `create`，调用时加 `try`。Kotlin 异常到 Swift 一律是 `NSError`（domain 为 `KotlinException`），`catch let e as SokkuriException` 能编译但永远不会匹配：

```swift
do {
    let sokkuri = try Sokkuri.Companion.shared.create(config: config, options: options)
} catch let error as NSError {
    print(error.kotlinException ?? error)
}
```

另外：

- `sokkuri-api` 里的类型在 Swift 中带模块前缀，如 `Sokkuri_apiSokkuriConfig`；`Sokkuri` 与 `Sokkuri.CreateResult` 不带。
- sealed 类在 Swift 中是普通类，不能用 `switch` 穷尽，只能用 `as?` 判断。

## 开发

```bash
./gradlew build                                   # 全部平台的构建与测试
./gradlew :sokkuri-runtime:jvmTest                # 只跑 JVM 测试
./gradlew :sokkuri-runtime:iosSimulatorArm64Test  # iOS 模拟器测试，需要 Apple Silicon
./gradlew :tools:benchmark:run --args="convert --profile s2t"   # 性能基准
```

以下检查不在 `build` 里：

- 与上游比对、词典漂移检查：需要 OpenCC 参考克隆（`./gradlew fetchOpencc` 按锁定的提交检出），比对还需要 cmake 与 C++ 编译器。命令是 `./gradlew :tools:upstream-diff:run` 与 `./gradlew :tools:dictgen:checkDictionaries`。CI 每周运行它们，发布前也会运行。
- `.sok` 的 Rust 独立读取器：`./gradlew sokConformance`，需要 Rust。不装 Rust 不影响构建与发布。

设计与验证的文档见 [docs/](docs/README.md)。

## License

Apache License 2.0
