# 扩展手册

每项写明改哪里、不能碰什么。拿不准时回到 [modules.md](modules.md) 的法则。

## #1 新词典格式

- 改：resource 新增 `Dictionary` 实现与 `DictionaryFormat`，注册到 `DefaultDictionaryFormats`；编码器放在同一模块。改 `.sok` 布局时，同一组提交里同步改 `SokFormatLayout`、Kotlin 编解码、`tools/sok-rs` 与对拍期望。
- 禁止：改 engine 或 config；在 dictgen 里另写编码器。

## #2 新分词器（如 jieba）

- 改：engine 实现 `Segmentation`；config 经 `SegmentationProvider` 注册表接入，见 [reserved.md](reserved.md)；使用者通过 `Sokkuri.create` 的扩展重载注入。
- 禁止：改 `Conversion` 或热路径。

## #3 新配置字段

- 改：config 的 `ConfigDocument` 加字段，`ConfigParser` 消费它。语义以上游为准，未知的键继续忽略。
- 禁止：改变已知键的宽容度（R8）。

## #4 新内置配置档（如 seal）

- 改：api 的 `SokkuriConfig` 加枚举值（I8）；dictgen 生成词典并把改写后的配置放进 runtime 资源；更新 README 与 [divergences.md](divergences.md)。
- 禁止：重排或删除枚举值。

## #5 新的 SokkuriOptions 选项

- 改：api 的 `SokkuriOptions.Builder` 加 `var`。默认值对齐上游，否则登记偏离。
- 禁止：给构造函数加参数，那会破坏 ABI。

## #6 新转换能力（如列出候选、歧义）

- 改：engine 基于 `Dictionary.matchExact` 写纯函数；api 加新类型；runtime 的 `Sokkuri` 委托过去。
- 禁止：往 `Dictionary` 接口加方法（见 [dictionaries.md](dictionaries.md)）。

## #7 新平台（如 macOS、watchOS）

- 改：各模块的 `build.gradle.kts` 加 target；resource 补 `SokkuriLock` 的 actual（可抽出 appleMain）；runtime 手工接好 source set，补加载器的 actual。
- 禁止：新增层；在 api、engine、config 里出现 actual（R5）。

## #8 流式转换

- 改：在 runtime 之上加有状态的窗口包装，见 [reserved.md](reserved.md)。
- 禁止：给 `Converter` 接口加流式方法。

## #9 改 Inspection

- 改：结构调整是 api 的破坏性变更，按 major 版本处理。优先只加不改。
- 禁止：在 minor 版本中改公开签名。

## #10 String view 匹配

- 已实现：`Dictionary.matchAppend` 把值直接写进输出，默认实现等于旧行为，见 [string-view.md](../performance/string-view.md)。
- 禁止：改变 `matchPrefix` 已有的语义（由默认实现与契约测试守住）。

## #11 配置 schema 校验

- 改：按 [reserved.md](reserved.md) 注入 `ConfigValidator`，只发警告。
- 禁止：校验失败时拒绝配置，那会偏离上游。

## #12 自定义资源加载器

- 改：runtime 加 `create(config, options, loader)`，`ResourceLoader` 升为稳定的公开 API。自定义加载器必须实现 `missingResourceHint()`，如实写出搜索范围（I9）。
- 前提：先按 [dictionaries.md](dictionaries.md) 的缓存一节，给加载器身份登记表设上限或换键。
- 禁止：把加载器接口移到 engine 或 config（R3）。

## #13 使用者自带配置（D12）

- 改：runtime 加 `Sokkuri.fromConfig(json, options)`。复用 `create` 的加载器与 provider 组合、`CreateResult` 双通道；JSONC 走 `JsonSupport.stripComments`。
- 前提：#12 的加载器接口，因为配置里引用的词典仍要经 `ResourceLoader` 找到；错误消息归属遵守 D11。`ocd`、`ocd2` 改写为 `sok` 的逻辑目前只在 dictgen 的 `ConfigRewriter` 里，使用者配置里的这两种类型会被报为 `Unsupported`。
- 禁止：在 config 层另开一条解析路径；绕过 `DefaultDictionaryFormats`；把改写逻辑从 dictgen 搬进运行时。
