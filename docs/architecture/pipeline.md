# 转换管线

## Converter

engine 定义封闭接口 `Converter`，对应上游的 `Converter`：

```kotlin
sealed interface Converter {
    fun convert(input: String): String
    fun inspect(input: String): Inspection

    class SingleStage(segmentation: Segmentation?, chain: ConversionChain) : Converter
    class Normalizing(normalization: Converter, main: Converter) : Converter
}
```

- `SingleStage` 对应上游 `SingleStageConverter`：可选的分词，然后每段经过转换链。
- `Normalizing` 对应上游 `ConfigBasedConverter`：配置声明了 normalization 时，先用它转换整个输入，再交给主 converter；`inspect` 合并两段的结果。
- 组装由 config 层的 `ConfigParser` 负责，engine 不理解配置。

## 数据流

```
输入 → normalization（可选，整段转换）→ 分词（可选，mmseg）→ ConversionChain → 输出
```

- `ConversionChain` 中第 N 级的输出是第 N+1 级的输入，空链原样输出，对应上游 `AppendConvertedSegment`。
- 有分词时，每一级一次处理全部分段：只分配一个缓冲，用偏移记录段的边界。每一级只读给定的范围，匹配不会跨段，结果与逐段转换相同。
- 每一级在当前位置做最长前缀匹配。没有匹配时交给 `BulkSkip`：先整体消费一个 IDS（或一个码点），再跳过一串不可能开始匹配的字符。

## 批量跳过

跳过必须停在两类字符上：词典键的首字，以及 arity 不为零的 IDS 运算符。不停在运算符上，IDS 会被拆开，其操作数会被当作普通文本转换。上游 `Utf8SkipScan::Finalize` 也是这样。

- 每一级在构建时取得词典的键首字集合（`Dictionary.keyStarts()`）。跳过一个字只需一次位测试和一次范围比较，不调用词典。
- 无法列举键首字的词典返回 `null`，这时逐字询问 `stopsBulkSkip`。
- `Conversion` 与 `MaxMatchSegmentation` 两个扫描循环都用 `BulkSkip`。不要在跳过循环里直接用 `mayStartKey`：它不认识 IDS 运算符。

## Inspection

```kotlin
class Inspection(
    val input: String,
    val normalizationStages: List<Stage>,  // 没有 normalization 时为空
    val segments: List<String>,            // 分词结果，在 normalization 之后
    val stages: List<Stage>,               // 主转换链，index 从 1 开始
    val output: String,
)
```

结构是平铺的，不用上游递归的 `pipelineStages`：inspect 是调试与工具用的功能，平铺更好用。日后实现多级 Pipeline 时再改成树，见 [reserved.md](reserved.md)。
