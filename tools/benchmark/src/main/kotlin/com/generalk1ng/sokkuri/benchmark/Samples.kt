/*
 * Copyright 2026 The Sokkuri Authors and contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.generalk1ng.sokkuri.benchmark

import com.generalk1ng.sokkuri.SokkuriConfig

/**
 * The embedded sample set of the benchmark runs:
 * fixed and in-repo on purpose — a benchmark must measure a stable
 * workload, not whatever the current upstream corpus says. `medium` and
 * `long` run against every profile so cross-profile numbers stay
 * comparable; the special samples stress one pipeline aspect and run only
 * on their tagged profile.
 */
@ConsistentCopyVisibility
internal data class Sample internal constructor(
    /** Stable id, echoed in the `[bench]` output line. */
    val id: String,
    /** The exact probe text; never locale- or environment-dependent. */
    val text: String,
    /** Default measured iterations; `--iterations` overrides. */
    val defaultIterations: Int = DEFAULT_ITERATIONS,
) {
    internal companion object {
        /** Default measured iterations for a regular sample. */
        internal const val DEFAULT_ITERATIONS: Int = 20_000

        /**
         * The phrase-stress sample is ~10× costlier per op (219 chars over
         * the 49k-entry STPhrases table), so its default is downshifted.
         */
        internal const val PHRASE_ITERATIONS: Int = 5_000

        /** The long sample is ~17× the length of `medium`, so its default is downshifted further. */
        internal const val LONG_ITERATIONS: Int = 2_000
    }
}

/** Sample definitions plus the profile→samples selection rule. */
internal object Samples {

    /**
     * The pilot probe text, carried verbatim from the original baseline so
     * the convert numbers stay directly comparable across runs.
     */
    internal val MEDIUM: Sample = Sample(
        id = "medium",
        text = "鼠标里面的硅二极管坏了，需要更换。软件开发和硬件维护都需要严谨的态度。" +
                "自干五扇风耳朵，铺眉搧眼。",
    )

    /**
     * Sustained throughput: an original ~800-character article
     * of everyday and technical prose (six paragraphs, newline-separated)
     * whose vocabulary spans the regional phrase tables and the large
     * one-to-many groups (发, 干, 后, 出). Per-op fixed costs vanish at this
     * length, so it measures what a long document costs; it runs on every
     * profile, like `medium`, so throughput stays comparable across them.
     */
    internal val LONG: Sample = Sample(
        id = "long",
        text = "周末的早上，程序员小林照例先去楼下的面包店买了两个牛角面包和一杯热牛奶，然后坐地" +
                "铁去公司加班。这个季度团队要发布新版本的文字转换软件，需要同时支持简体中文、繁体" +
                "中文以及台湾、香港两地的常用词汇。\n" +
                "到了办公室，他打开笔记本电脑，插上鼠标和移动硬盘，先把服务器上的日志下载下来。日" +
                "志显示，昨天晚上有一批用户在上传视频时遇到了网络超时，后台的数据库连接池也出现了" +
                "短暂的阻塞。小林一边喝咖啡，一边在屏幕上逐行分析调用栈，发现问题出在一个默认参数" +
                "上：缓存的过期时间被设置成了零，导致每次请求都要重新读取配置文件。\n" +
                "他把这个发现发到了项目的讨论群里。负责后端的同事老周很快回复说，这个参数是上个月" +
                "为了调试内存泄漏临时改的，后来忘了恢复。两人商量之后，决定先在测试环境里把参数改" +
                "回去，再用压力测试工具模拟一万个并发连接，确认响应时间回到正常范围以后再发布补丁" +
                "。\n" +
                "中午，大家一起去公司附近的餐馆吃饭。菜单上有干炒牛河、酸菜鱼、宫保鸡丁和几种面条" +
                "，小林点了一碗牛肉面，老周则要了一份叉烧饭。饭桌上，产品经理提到，香港和台湾的用" +
                "户经常反馈软件里的一些用词不够地道，比如“信息”在台湾通常说“资讯”，“打印机”" +
                "在台湾叫“印表机”，“出租车”在香港多半叫“的士”。她希望下一个版本能在转换质量" +
                "上再优化一些，尤其是长句里一对多的字形，例如“头发”和“发展”里的“发”、“干净" +
                "”和“干部”里的“干”、“皇后”和“后来”里的“后”，都需要根据上下文选择正确的" +
                "繁体写法。\n" +
                "下午，小林写了一组新的单元测试，覆盖了几百个常见的词组。他还准备了一份基准测试报" +
                "告，记录每个配置文件在不同长度输入下的转换速度和内存占用。报告里的数据表明，修复" +
                "后的版本处理长文本时明显更快，而内存占用几乎没有变化。\n" +
                "傍晚六点，补丁顺利通过了代码审查和自动化测试，被合并进了主分支。小林关上电脑，骑" +
                "着共享单车穿过公园回家。路上他想，下周也许可以试试把词典的检索结构再改进一下，让" +
                "程序在手机上也能跑得同样流畅。",
        defaultIterations = Sample.LONG_ITERATIONS,
    )

    /** Short input: per-op fixed costs (call overhead, segmentation setup) dominate. */
    private val SHORT: Sample = Sample(id = "short", text = "繁體中文轉換測試")

    /**
     * The upstream "noodle sentence" (golden corpus case `s2t_ximian`,
     * input side, verbatim): 219 chars of 2–7-char phrase keys against
     * the largest dictionary — the longest-prefix worst case.
     */
    internal val PHRASES: Sample = Sample(
        id = "phrases",
        text = "细面 宽面 油泼面 臊子面 裤带面 重庆小面 肉酱面 酸菜鱼面 肥肠面 炒码面 牛腩面 牛筋面 " +
                "三鲜面 番茄面 羊肉面 猪肉面 卤肉面 鲁肉面 虾球面 虾仁面 拉揸面 杂烩面 拉杂面 嗱喳面 " +
                "强棒面 酱面 葱油面 油酱面 焖面 焗面 焗猪扒面 猫耳面 削筋面 浆水面 拨鱼面 刀拨面 " +
                "银丝面 菠菜面 黄鱼面 大肠面 焖肉面 大排面 腰花面 咖喱面 麻辣面 酸辣面 火鸡面 " +
                "锅烧面 切仔面 卤味面 炒鳝面 沾面 手工面 出前一丁面 意大利面",
        defaultIterations = Sample.PHRASE_ITERATIONS,
    )

    /** Regional vocabulary + mmseg segmentation (滑鼠/軟體). */
    private val TWP: Sample = Sample(id = "twp", text = "鼠标和软件")

    /** HK variant chain (恒生銀行發佈財報 style). */
    private val HK: Sample = Sample(id = "hk", text = "恒生銀行和恒大集團發佈財報")

    /** Special samples keyed by their tagged profile; `medium` is implicit for all. */
    private val SPECIALS: Map<SokkuriConfig, List<Sample>> = mapOf(
        SokkuriConfig.S2T to listOf(SHORT, PHRASES),
        SokkuriConfig.S2TWP to listOf(TWP),
        SokkuriConfig.S2HK to listOf(HK),
    )

    /**
     * The selection rule: `medium` and `long` plus the specials tagged for
     * [profile] (none for profiles without a stress sample).
     */
    internal fun forProfile(profile: SokkuriConfig): List<Sample> =
        listOf(MEDIUM, LONG) + SPECIALS[profile].orEmpty()
}
