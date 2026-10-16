# 预留能力

以下能力暂不实现。每项写明落地时的做法与要改的地方；实现后把结论并入相应文档，并更新 [divergences.md](divergences.md) 与 [decisions.md](decisions.md)。

## 配置 schema 校验

- 做法：`ConfigParser` 构造时可选地接受一个 `ConfigValidator`，把配置 JSON 映射为警告列表，默认不启用。与上游一样只警告，不拒绝配置。
- 已有基础：`ConfigDocument` 就是 schema 的形状；`JsonSupport` 是唯一的入口；`ConfigParser` 是 internal，可以加构造参数。
- 要改：定义 `ConfigValidator`；schema 随资源打包；实现校验器；决定警告的出口（不学上游写 stderr，由注入方决定丢弃或转交，`Sokkuri` 默认丢弃）；更新偏离 #7。

## 插件分词（jieba）

- 做法：注册表注入，不加载动态库（偏离 #9）。除内建的 `mmseg` 外，分词类型名交给 `SegmentationProvider` 查找：输入类型名与字符串配置项，返回 `Segmentation?`，查不到报 `Unsupported`。这与上游 `PluginSegmentation` 的配置项同构。
- 要改：`SegmentationDocument` 收集额外的字符串键传给 provider（上游会把所有额外字符串属性都当作配置项）；`ConfigParser` 加 provider 参数；engine 实现 `Segmentation`；`Sokkuri.create` 的扩展重载接受注册表，与自定义加载器（扩展 #12）一起发布。

## seal 配置档

- 做法：纯数据，不改代码。`SokkuriConfig` 加枚举值（I8）；dictgen 从上游 `SealCharacters` 与 `SealVariants` 生成 `.sok`；改写后的配置放进 runtime 资源；更新 README 与偏离 #4。
- 影响：对用 `when` 穷尽枚举的使用者是源码级变化，可以在 minor 版本发布。

## 流式转换

- 做法：在 `Sokkuri` 之上加有状态的包装。每个分块保留尾部 16 个码点的窗口，窗口终点落在完整的 IDS 之外，防止短语或 IDS 被切断，对齐上游 `ConverterStream`。engine 的 `Converter` 不变。
- 要改：在 runtime 或 api 加流式包装类，提供 `convert(chunk)` 与 `finish()`，注明只能单线程使用；对照上游 `ConverterStream` 写测试；更新偏离 #5。

## 多级 Pipeline

- 做法：`Converter` 增加 `Pipeline(stages: List<Converter>)`，对应上游 `PipelineConverter`；`Inspection` 届时从平铺改为树，是 api 的破坏性变更（扩展 #9）。
- 目前没有需要多级的配置，所以只预留接口位置（偏离 #8）。
