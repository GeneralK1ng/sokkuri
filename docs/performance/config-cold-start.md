# 配置解析的冷启动开销（2026-10-16）

## 问题

0.2.1 的 JVM 冷启动（新进程中第一次建档加第一次转换）比 0.2.0 慢 8–10 ms：s2t 由 65.7 ms 增至 73.8 ms，t2s 由 53.6 ms 增至 63.2 ms。

## 定位

只把 `sokkuri-config` 的 jar 在两个版本之间互换，中位数如下：

| 组合 | s2t | t2s |
|---|---|---|
| 0.2.0 | 66.5 ms | 52.8 ms |
| 0.2.0，换上 0.2.1 的 config | 76.2 ms | 62.6 ms |
| 0.2.1，换回 0.2.0 的 config | 60.9 ms | 50.5 ms |
| 0.2.1 | 72.3 ms | 62.1 ms |

- 差距全部来自配置解析，约 +10 ms。词典与检索的改动本身让冷启动快了 2–6 ms。
- 0.2.1 建档时多加载约 120 个类：kotlinx.serialization 多 59 个，Kotlin 标准库多 31 个，sokkuri-config 多 14 个，其余来自 JDK。配置文件只有几百字节，多出的是第一次运行这些代码的一次性开销。
- JFR 的执行采样看不到这部分：时间花在 JVM 内部的类加载与链接上，不在 Java 代码里。

## 做法

1. `NameSerializer` 原先用 `PrimitiveSerialDescriptor` 声明带名字的描述符。kotlinx 要检查这个名字不与内建类型重名，为此初始化整张内建序列化器表，加载约 70 个类。改为直接用 `String.serializer().descriptor`；JSON 不读基本类型的序列名，行为不变。约 −7 ms。
2. `DictPath` 原先用字符串模板拼路径。目标为 JVM 11 时，模板编译成 invokedynamic，第一次调用要现场生成方法句柄类。改用 `StringBuilder`。约 −1 ms。

## 结果

新 JVM 中计时 `Sokkuri.create` 与第一次 `convert`，只替换 `sokkuri-config` 的 jar，与改动前交替测 9 轮取中位数：

| 档 | 改动前 | 改动后 |
|---|---|---|
| s2t | 73.6 ms | 65.6 ms |
| t2s | 62.6 ms | 54.5 ms |

已回到 0.2.0 的水平。配置比对 75 份全部一致。iOS 没有类加载这一环，影响应当小得多，未测量。

## 没有做的

解析时仍会把 `name` 和两个转换列表解成 JSON 树，用来照上游忽略不是字符串的 `name`、跳过不是对象的列表成员，约占 2 ms。把这两条规则移进 `JsonStructure` 的文本改写就能省掉，但改动较大，暂不做。
