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
