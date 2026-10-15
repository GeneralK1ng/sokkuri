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

package com.generalk1ng.sokkuri.upstreamdiff

import com.generalk1ng.sokkuri.Inspection
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The comparison's common ground: decoding the harness's `inspect` lines
 * and flattening upstream's pipeline tree the way Sokkuri reports
 * normalization.
 */
class InspectionAlignmentTest {

    private fun single(segments: List<String>, vararg stages: List<String>, output: String) =
        UpstreamInspection(
            segments = segments,
            stages = stages.mapIndexed { i, s -> UpstreamStage(i + 1, s) },
            pipelineStages = emptyList(),
            output = output,
        )

    @Test
    fun decodesTheHarnessLineIncludingEscapes() {
        // A line exactly as the harness writes it for the input a"b\c<TAB>d<U+0001>e.
        val line = """{"segments":["a\"b\\c\td\u0001e"],"stages":[{"index":1,"segments":["x"]}],""" +
            """"pipelineStages":[],"output":"x"}"""
        val decoded = Json.decodeFromString(UpstreamInspection.serializer(), line)
        assertEquals(listOf("a\"b\\c\td\u0001e"), decoded.segments)
        assertEquals(listOf(UpstreamStage(1, listOf("x"))), decoded.stages)
        assertEquals("x", decoded.output)
    }

    @Test
    fun anUnknownFieldIsAProtocolError() {
        val line = """{"segments":[],"stages":[],"pipelineStages":[],"output":"","extra":1}"""
        assertFailsWith<IllegalArgumentException> { Json.decodeFromString(UpstreamInspection.serializer(), line) }
    }

    @Test
    fun aConverterWithoutPipelineIsAlreadyFlat() {
        val flat = FlatInspection.of(single(listOf("ab", "c"), listOf("AB", "C"), output = "ABC"))
        assertEquals(
            FlatInspection(emptyList(), listOf("ab", "c"), listOf(FlatStage(1, listOf("AB", "C"))), "ABC"),
            flat,
        )
    }

    @Test
    fun normalizationPipelineFlattensLikeSokkuri() {
        // ConfigBasedConverter: [normalization converter, main converter].
        val upstream = UpstreamInspection(
            segments = emptyList(),
            stages = emptyList(),
            pipelineStages = listOf(
                single(listOf("鼠标里面"), listOf("鼠标里面"), output = "鼠标里面"),
                single(listOf("鼠标", "里面"), listOf("鼠標", "裏面"), listOf("鼠標", "裡面"), output = "鼠標裡面"),
            ),
            output = "鼠標裡面",
        )
        val sokkuri = Inspection(
            input = "鼠标里面",
            normalizationStages = listOf(Inspection.Stage(1, listOf("鼠标里面"))),
            segments = listOf("鼠标", "里面"),
            stages = listOf(Inspection.Stage(1, listOf("鼠標", "裏面")), Inspection.Stage(2, listOf("鼠標", "裡面"))),
            output = "鼠標裡面",
        )
        assertEquals(FlatInspection.of(upstream), FlatInspection.of(sokkuri))
        assertNull(FlatInspection.of(upstream).firstDifference(FlatInspection.of(sokkuri)))
    }

    @Test
    fun nestedPipelinesContributeNormalizationInOrder() {
        val inner = UpstreamInspection(
            segments = emptyList(),
            stages = emptyList(),
            pipelineStages = listOf(
                single(listOf("a"), listOf("b"), output = "b"),
                single(listOf("b"), listOf("c"), output = "c"),
            ),
            output = "c",
        )
        val upstream = UpstreamInspection(
            segments = emptyList(),
            stages = emptyList(),
            pipelineStages = listOf(inner, single(listOf("c"), listOf("d"), output = "d")),
            output = "d",
        )
        val flat = FlatInspection.of(upstream)
        assertEquals(listOf(FlatStage(1, listOf("b")), FlatStage(1, listOf("c"))), flat.normalizationStages)
        assertEquals(listOf("c"), flat.segments)
        assertEquals(listOf(FlatStage(1, listOf("d"))), flat.stages)
        assertEquals("d", flat.output)
    }

    @Test
    fun firstDifferenceNamesTheFirstDifferingField() {
        val expected = FlatInspection(emptyList(), listOf("ab"), listOf(FlatStage(1, listOf("AB"))), "AB")
        val differentStage = expected.copy(stages = listOf(FlatStage(1, listOf("A", "B"))))
        val difference = assertNotNull(expected.firstDifference(differentStage))
        assertContains(difference, "stages:")
        val differentSegments = differentStage.copy(segments = listOf("a", "b"))
        assertContains(assertNotNull(expected.firstDifference(differentSegments)), "segments:")
        assertContains(assertNotNull(expected.firstDifference(expected.copy(output = "X"))), "output:")
    }
}
