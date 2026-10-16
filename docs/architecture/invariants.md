# 不变量

违反任何一条都是缺陷，不是风格问题。

- **I1** engine 里的索引与长度都以 UTF-16 码元计。码点只用于词典排序与 IDS。
- **I2** 词典排序与二分查找都用 `Utf.compareByCodePoint` 的码点序，不混用 `String.compareTo`：两者在增补平面上结果不同。`.sok` 解码器在加载时检查键的顺序（V12）。
- **I3** 词典与转换器构造后不可变、线程安全，可以跨线程共享。
- **I4** 异常分类对齐上游：`FileNotFound`、`InvalidFormat`、`InvalidConfig`、`Unsupported`。新的错误类型必须是 `SokkuriException` 的子类，并加进 `Sokkuri.create` 的 `@Throws`（D9）。
  - 原因：漏加时，Kotlin/Native 会把这个类型从 Swift 侧剪掉。异常仍会变成 `NSError`，但 `CreateResult.Failure.error` 只能 cast 到基类。
  - 检查：`SokkuriCreateThrowsContractTest`。
- **I5** 所有模块启用 `explicitApi()`；跨模块的公开声明标注 `@SokkuriInternalApi`。
- **I6** 热路径不复制输入：engine 在 `CharArray` 的窗口上工作，只在写出时拼接。
- **I7** JSONC 的宽容度与上游 rapidjson 的设置相同，不多不少，见 [modules.md](modules.md) R8。
- **I8** `SokkuriConfig` 枚举只增不删，也不重排。
- **I9** 资源缺失时 `ResourceLoader.load` 返回 `null`，加载器自己不抛异常。抛出点写 `FileNotFound(path, loader.missingResourceHint())`，不写裸的 `FileNotFound(path)`（D10）。提示不能为空。
  - 原因：抛出点只知道路径，只有加载器知道去哪里找过。没有提示，使用者只看到一句 not found，容易被误导去查打包。
  - 检查：runtime 的 `MissingResourceDiagnosisTest`（三端的真实加载器）与 resource 的 `ResourceDictionaryProviderTest`。
- **I10** `dict.type` 可接受的取值只在一处定义：`DictDocument` 的密封层次。未知或缺失的类型由 `JsonSupport` 的模块级默认反序列化器拒绝，报 `InvalidConfig`，措辞用本库自己的（D11）。
  - 原因：`type` 是序列化的判别符，config 层拿不到它的值，只能在这里拒绝。
  - 不要在别处复制类型清单，也不要换回 kotlinx 的通用措辞（例如提到 `DictDocument`、“polymorphic scope”，或建议使用者加 `@Serializable`）。
  - 检查：`ConfigParserTest` 的三个用例。
