# 词典

## 接口

```kotlin
interface Dictionary {
    val maxKeyLength: Int                                          // 最长键，UTF-16 码元
    fun matchPrefix(text: CharArray, start: Int, end: Int): PrefixMatch?
    fun matchLength(text: CharArray, start: Int, end: Int): Int    // 只要长度，分词用
    fun matchAppend(text: CharArray, start: Int, end: Int, out: StringBuilder): Int  // 值直接写进输出，转换用
    fun matchExact(key: String): DictionaryEntry?                  // 非热路径
    fun mayStartKey(codePoint: Int): Boolean
    fun keyStarts(): CodePointSet?                                 // mayStartKey 的精确集合，批量跳过用
}
```

- 接口保持精简（上游的 `Dict` 已有 9 个方法）。匹配策略不放进词典：`Conversion`、`MaxMatchSegmentation` 与 `BulkSkip` 基于这些方法实现策略，词典只回答自己的数据。
- 不要给 `Dictionary` 加与数据无关的策略方法。新的匹配能力优先写成基于现有方法的纯函数。
- `matchLength`、`matchAppend` 与 `keyStarts` 有默认实现，后端可按需覆写。
- `matchPrefix` 只返回匹配长度与默认候选，不返回键。
- 只有 `Conversion` 调用 `matchAppend`：`.sok` 词典把值直接解码进输出；short_circuit 组转给第一个命中的子词典；union 组仍用 `matchPrefix` 决定胜者，因为转发会让胜者多检索一次，实测更慢。
- `Dictionary`、`DictionaryEntry` 与 `PrefixMatch` 标注 `@SokkuriInternalApi`。`sokkuri-resource` 要跨模块实现它们，所以不能用 Kotlin 的 `internal`；opt-in 标注表明它们不稳定，可以在 minor 版本中改。

## 实现

- `SortedListDictionary` 对应上游 `TextDict`，是有序数组，用于 inline 与文本词典，也是参照实现。
- `SokDictionary` 读取生产格式 `.sok`，注册为 `"sok"` 类型。它直接引用文件字节，不复制，也不缓存解码结果。
- 两者共用 `SortedTableRetrieval` 的检索算法：用首码点集合定位分组，再逐码点二分收窄，原理见 [prefix-narrowing.md](../performance/prefix-narrowing.md)。不要退回组内线性扫描；契约测试有暴力求解的对照。
- `DictGroup` 实现 short_circuit 与 union 两种组策略。
- `ocd` 与 `ocd2` 注册为 `UnsupportedDictionaryFormat`，报出明确的错误。

## .sok 格式

- 字节布局只在一处定义：`SokFormatLayout` 的 KDoc。
- 编码器与解码器都在 `sokkuri-resource`（D6）。编码器经 `@SokkuriInternalApi` 提供给 dictgen，不另写第二份。
- 解码器的接受规则 V1–V12 与 Rust 独立读取器一一对应，见 [sok-reader.md](../verification/sok-reader.md)。改动这些规则时，Kotlin 解码器、Rust 读取器与 `SokFormatLayout` 在同一个提交里一起改。
- 键在加载时逐个校验为良构 UTF-8，所以检索时的键探针不再校验。值的解码保持宽容：畸形字节降级为 U+FFFD。
- 编码器拒绝含孤立代理项的键与候选：宽容的 UTF-8 编码在 JVM 上写成 `?`，在 Kotlin/Native 上写成 U+FFFD，同一输入会在两端编出不同的文件。

## 缓存

解码后的词典放在进程级缓存里，强引用、全局共享，键为“加载器标识 + 类型 + 文件”。这是有意偏离上游（[divergences.md](divergences.md) #1）：

- 上游用 static `weak_ptr`，按文件的修改时间与大小失效，适合读可变文件的桌面工具。Sokkuri 的词典是打包资源，不会变，不需要失效。
- KMP 的 common 代码没有弱引用。
- 强引用共享让多个转换器共用一份词典内存，适合移动端。

加载器标识按实例身份分配。平台默认加载器是单例，所以同一平台上的所有 `Sokkuri.create` 共用一个标识；不同的加载器实例从不共用，为将来有状态的自定义加载器保证隔离。

并发：`ResourceDictionaryProvider` 加锁查缓存，在锁外解码，再加锁放入；同时解码的两方中，后到的丢弃自己的结果。

注意：身份登记表只增不减，并强引用加载器（没有弱引用，表就是持有者）。现在没问题，因为默认加载器是单例。实现自定义加载器（[extending.md](extending.md) #12）之前必须先解决：如果调用方每次 `create` 都新建加载器，登记表与缓存会无限增长，每个加载器各自解码并常驻一整套词典。办法二选一：给登记表设上限，或改用加载器自报的稳定标识作键。
