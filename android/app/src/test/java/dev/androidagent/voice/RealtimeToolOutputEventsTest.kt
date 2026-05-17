package dev.androidagent.voice

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class RealtimeToolOutputEventsTest {
    @Test
    fun createsFunctionCallOutputAndResponseCreateEvents() {
        val events = buildRealtimeToolOutputEvents(
            JSONObject()
                .put("callId", "call-1")
                .put("ok", true)
                .put("status", "completed")
                .put("output", "Opened Settings")
        )

        val item = events[0].getJSONObject("item")
        val output = JSONObject(item.getString("output"))

        assertEquals("conversation.item.create", events[0].getString("type"))
        assertEquals("function_call_output", item.getString("type"))
        assertEquals("call-1", item.getString("call_id"))
        assertEquals(true, output.getBoolean("ok"))
        assertEquals("completed", output.getString("status"))
        assertEquals("Opened Settings", output.getString("output"))
        assertEquals("response.create", events[1].getString("type"))
    }
}
