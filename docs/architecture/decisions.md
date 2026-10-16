# 决策

| 编号 | 决策 | 状态 |
|---|---|---|
| D1 | `Converter` 是封闭接口：`SingleStage` 与 `Normalizing`，Pipeline 预留（[pipeline.md](pipeline.md)） | 已实现 |
| D2 | `Inspection` 平铺，`normalizationStages` 单列 | 已实现 |
| D3 | 词典缓存进程级强引用共享，按加载器实例划分（[dictionaries.md](dictionaries.md)） | 已实现 |
| D4 | `SokkuriOptions` 用 Builder 模式 | 已实现 |
| D5 | inline 词典出现 `may_output_tofu` 即报错，按字段是否存在判定 | 已实现 |
| D6 | `.sok` 的编码器与解码器放在同一模块 | 已实现 |
| D7 | 流式转换不进 v1（[reserved.md](reserved.md)） | 预留 |
| D8 | 自定义加载器的公开化路径（[extending.md](extending.md) #12） | 预留 |
| D9 | 错误两条通道：`create` 抛异常；`createResult` 返回 `CreateResult`，供 Apple 平台使用（I4） | 已实现 |
| D10 | 资源缺失的诊断由加载器提供：`missingResourceHint()` 并入 `FileNotFound`（I9） | 已实现 |
| D11 | 未知或缺失的 `dict.type` 由 `JsonSupport` 的默认反序列化器统一拒绝（I10） | 已实现 |
| D12 | `Sokkuri.fromConfig` 接受使用者自带的 OpenCC 配置（[extending.md](extending.md) #13） | 预留 |
| D13 | CI 分层：构建门禁、上游对齐、发布前置、上游演练（[ci.md](../verification/ci.md)） | 已实现 |
| D14 | OpenCC 参考克隆用锁文件加按需检出，不用 git 子模块（[opencc-clone.md](../verification/opencc-clone.md)） | 已实现 |
| D15 | 最长前缀检索改为逐码点二分收窄；`.sok` v2 暂缓（[prefix-narrowing.md](../performance/prefix-narrowing.md)） | 已实现 |
| D16 | 削减每级转换的固定开销（[stage-overhead.md](../performance/stage-overhead.md)） | 已实现 |
| D17 | 上游比对扩展到 inspect、配置与模糊语料，并提供上游性能参照（[upstream-diff.md](../verification/upstream-diff.md)） | 已实现 |
| D18 | 热点修复：码点位图、每级持有跳过集合、加载时校验键等八项（[hot-paths.md](../performance/hot-paths.md)） | 已实现 |
| D19 | `.sok` 的 Rust 独立读取器、审计与跨实现对拍，并据此收紧规范（[sok-reader.md](../verification/sok-reader.md)） | 已实现 |
