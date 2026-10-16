# String view 匹配（2026-09-30）

目标：命中时不再生成中间字符串，把值直接写进输出缓冲。

## 做法

- `Dictionary` 增加 `matchAppend(text, start, end, out)`：命中时把默认候选写进 `out`，返回匹配长度（UTF-16 码元）；没有命中返回 -1。默认实现等于 `matchPrefix` 加写出，后端可以覆写。
- `SortedTableRetrieval` 的 `matchPrefix` 与 `matchAppend` 共用同一个最长前缀查找，只是值的出口不同（生成字符串或直接写出）。
- 键表抽象为 `SortedKeyTable`。String 后端直接引用；`.sok` 后端用 UTF-8 区段与 `CharArray` 窗口直接比较码点，探针不解码。
- `Conversion` 的写出改用 `matchAppend`（I6）；inspect、`matchExact` 与 mmseg 不变。
- 当时词典组不转发 `matchAppend`；后来 short_circuit 组改为转发，见 [hot-paths.md](hot-paths.md)。

## 结果

- s2t `medium` 由 21.42 µs 降至 9.4 µs（2.3 倍），较重的档快 2.3–2.6 倍。
- 解码仍不复制，也没有解码缓存。inspect 与 convert 的耗时比为 1.05。

## 教训

- JVM 的逃逸分析让命中值的生成几乎免费，“值直接写出”在 JVM 上实测没有收益；收益应在 Native，当时没测。
- 最大的收益来自把前缀判断与长度计算合成一次扫描。
- 当时以为剩下的差距是逐字节解码的固有代价，这个判断是错的：真正的瓶颈是组内的线性扫描，见 [prefix-narrowing.md](prefix-narrowing.md)。
- 性能门槛由“不高于 8 µs”改为“不低于开工时的 2.2 倍”，实际达到 2.3 倍。
