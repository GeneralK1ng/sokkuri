# 架构

本目录是 Sokkuri 的架构约定。模块边界、依赖方向、扩展方式与不变量以这里为准；修改这些文档就是修改架构，应先于代码讨论。

与上游 OpenCC 的关系：行为对齐是目标。[divergences.md](divergences.md) 里登记的偏离是已定的设计，不因“上游如此”而回退。

## 目标

- 行为对齐 OpenCC 的 C++ 核心：同样的转换管线、词典语义与 IDS（表意文字描述序列）处理。
- 提供上游库接口没有的 `inspect()`：返回分词与各级转换的中间结果。
- 用 Kotlin Multiplatform 支持 JVM、Android 与 iOS（iosArm64、iosSimulatorArm64），遵守 `explicitApi`、语义化版本与二进制兼容。

## 非目标

以下能力暂不实现，但架构保证日后能加，做法见 [reserved.md](reserved.md)：ocd 与 ocd2 词典、jieba 等插件分词、seal 配置档、流式转换、配置 schema 校验、多级 Pipeline。

## 文档

| 文档 | 内容 |
|---|---|
| [modules.md](modules.md) | 模块结构、职责与允许的依赖；法则 R1–R8 |
| [pipeline.md](pipeline.md) | 转换管线、批量跳过与 `Inspection` |
| [dictionaries.md](dictionaries.md) | 词典接口、实现、`.sok` 格式与缓存 |
| [invariants.md](invariants.md) | 不变量 I1–I10 |
| [extending.md](extending.md) | 加新功能时改哪里、不能碰什么 |
| [divergences.md](divergences.md) | 与上游的有意偏离 |
| [decisions.md](decisions.md) | 决策 D1–D19 及其状态 |
| [reserved.md](reserved.md) | 预留能力的落地方案 |
