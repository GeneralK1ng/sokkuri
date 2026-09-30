package com.generalk1ng.sokkuri.engine

import com.generalk1ng.sokkuri.SokkuriInternalApi

/**
 * Text segmentation strategy, the port of OpenCC's `Segmentation` interface.
 *
 * Implementations split `text` into contiguous segments — ranges are
 * inclusive UTF-16 indices into [chars]. The conversion chain then converts
 * each segment independently. The only built-in strategy is
 * [MaxMatchSegmentation]; the interface exists so alternative segmenters
 * (OpenCC's jieba plugin being the upstream example) can be added without
 * touching the engine.
 */
@SokkuriInternalApi
public interface Segmentation {
    public fun segment(chars: CharArray): List<IntRange>
}
