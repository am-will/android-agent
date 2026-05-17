package dev.androidagent.voice

import org.json.JSONObject

fun buildRealtimeToolOutputEvents(payload: JSONObject): List<JSONObject> {
    val callId = payload.optString("callId").ifBlank { payload.optString("call_id") }
    require(callId.isNotBlank()) { "Realtime tool result is missing callId." }

    val ok = payload.optBoolean("ok", false)
    val output = JSONObject()
        .put("ok", ok)
        .put("status", payload.optString("status").ifBlank { if (ok) "completed" else "failed" })
    payload.optString("output").takeIf { it.isNotBlank() }?.let { output.put("output", it) }
    payload.optString("error").takeIf { it.isNotBlank() }?.let { output.put("error", it) }

    return listOf(
        JSONObject()
            .put("type", "conversation.item.create")
            .put(
                "item",
                JSONObject()
                    .put("type", "function_call_output")
                    .put("call_id", callId)
                    .put("output", output.toString())
            ),
        JSONObject().put("type", "response.create")
    )
}
