package dev.androidagent.voice

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RealtimeTranscriptNormalizerTest {
    @Test
    fun mergesOverlappingAndDuplicateAssistantDeltas() {
        val normalizer = RealtimeTranscriptNormalizer()

        normalizer.applyEvent(
            "realtime.transcript_delta",
            JSONObject()
                .put("role", "assistant")
                .put("itemId", "assistant-1")
                .put("delta", "hello wor")
        )
        normalizer.applyEvent(
            "realtime.transcript_delta",
            JSONObject()
                .put("role", "assistant")
                .put("itemId", "assistant-1")
                .put("delta", "world")
        )
        val duplicate = normalizer.applyEvent(
            "realtime.transcript_delta",
            JSONObject()
                .put("role", "assistant")
                .put("itemId", "assistant-1")
                .put("delta", "world")
        )

        assertEquals("hello world", duplicate.lines.single().text)
        assertFalse(duplicate.lines.single().isFinal)
    }

    @Test
    fun finalTranscriptCanReplacePartialText() {
        val normalizer = RealtimeTranscriptNormalizer()

        normalizer.applyEvent(
            "response.audio_transcript.delta",
            JSONObject()
                .put("item_id", "assistant-1")
                .put("delta", "The answer is four")
        )
        val state = normalizer.applyEvent(
            "response.audio_transcript.done",
            JSONObject()
                .put("item_id", "assistant-1")
                .put("transcript", "The answer is forty two.")
        )

        assertEquals("The answer is forty two.", state.lines.single().text)
        assertTrue(state.lines.single().isFinal)
    }

    @Test
    fun speechStartedClearsOnlyNonFinalUserPartial() {
        val normalizer = RealtimeTranscriptNormalizer()

        normalizer.applyEvent(
            "realtime.transcript_delta",
            JSONObject()
                .put("role", "user")
                .put("itemId", "user-final")
                .put("text", "Open settings.")
                .put("isFinal", true)
        )
        normalizer.applyEvent(
            "realtime.transcript_delta",
            JSONObject()
                .put("role", "user")
                .put("itemId", "user-partial")
                .put("text", "Actually")
        )
        val state = normalizer.applyEvent("realtime.speech_started", JSONObject())

        assertEquals(listOf("user-final"), state.lines.map { it.itemId })
        assertEquals("Open settings.", state.lines.single().text)
    }

    @Test
    fun itemAddedExtractsContentArrayAsFinalLine() {
        val normalizer = RealtimeTranscriptNormalizer()
        val content = JSONArray()
            .put(JSONObject().put("type", "output_text").put("text", "Done"))
            .put(JSONObject().put("type", "output_text").put("text", "."))

        val state = normalizer.applyEvent(
            "realtime.item_added",
            JSONObject()
                .put(
                    "item",
                    JSONObject()
                        .put("id", "assistant-item")
                        .put("role", "assistant")
                        .put("content", content)
                )
        )

        assertEquals("assistant-item", state.lines.single().itemId)
        assertEquals("Done.", state.lines.single().text)
        assertTrue(state.lines.single().isFinal)
    }
}
