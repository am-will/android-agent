package dev.androidagent.voice

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RealtimeToolCallAccumulatorTest {
    @Test
    fun accumulatesArgumentDeltasUntilDone() {
        val accumulator = RealtimeToolCallAccumulator()

        assertNull(
            accumulator.apply(
                JSONObject()
                    .put("type", "response.output_item.added")
                    .put(
                        "item",
                        JSONObject()
                            .put("type", "function_call")
                            .put("id", "item-1")
                            .put("call_id", "call-1")
                            .put("name", "run_phone_task")
                    )
            )
        )
        assertNull(
            accumulator.apply(
                JSONObject()
                    .put("type", "response.function_call_arguments.delta")
                    .put("item_id", "item-1")
                    .put("call_id", "call-1")
                    .put("delta", "{\"instruction\":\"Open")
            )
        )

        val call = accumulator.apply(
            JSONObject()
                .put("type", "response.function_call_arguments.done")
                .put("item_id", "item-1")
                .put("call_id", "call-1")
                .put("delta", " Settings\"}")
        )

        assertEquals("call-1", call?.callId)
        assertEquals("run_phone_task", call?.name)
        assertEquals("Open Settings", call?.arguments?.getString("instruction"))
    }

    @Test
    fun ignoresDuplicateDoneEvents() {
        val accumulator = RealtimeToolCallAccumulator()
        val done = JSONObject()
            .put("type", "response.output_item.done")
            .put(
                "item",
                JSONObject()
                    .put("type", "function_call")
                    .put("id", "item-1")
                    .put("call_id", "call-1")
                    .put("name", "run_phone_task")
                    .put("arguments", "{\"instruction\":\"Open Settings\"}")
            )

        val first = accumulator.apply(done)
        val duplicate = accumulator.apply(done)

        assertEquals("call-1", first?.callId)
        assertNull(duplicate)
    }
}
