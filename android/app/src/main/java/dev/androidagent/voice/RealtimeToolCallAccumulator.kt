package dev.androidagent.voice

import org.json.JSONObject

data class RealtimeToolCall(
    val callId: String,
    val itemId: String?,
    val name: String,
    val arguments: JSONObject
)

class RealtimeToolCallAccumulator {
    private val pending = mutableMapOf<String, PendingCall>()
    private val completedKeys = mutableSetOf<String>()

    fun reset() {
        pending.clear()
        completedKeys.clear()
    }

    fun apply(event: JSONObject): RealtimeToolCall? {
        val type = event.optString("type")
        val item = event.optJSONObject("item")
        if (!type.contains("function_call") && item?.optString("type") != "function_call") {
            return null
        }

        val callId = event.callIdOrNull() ?: return null
        val itemId = event.optString("item_id").ifBlank { event.optString("itemId").ifBlank { null } }
        val key = itemId ?: callId
        if (completedKeys.contains(key)) {
            return null
        }

        val pendingCall = pending.getOrPut(key) {
            PendingCall(callId = callId, itemId = itemId)
        }
        event.optString("name").takeIf { it.isNotBlank() }?.let { pendingCall.name = it }
        event.optString("delta").takeIf { it.isNotEmpty() }?.let { pendingCall.argumentsBuilder.append(it) }

        if (item != null && item.optString("type") == "function_call") {
            item.optString("name").takeIf { it.isNotBlank() }?.let { pendingCall.name = it }
            item.optString("arguments").takeIf { it.isNotBlank() }?.let {
                pendingCall.argumentsBuilder.clear()
                pendingCall.argumentsBuilder.append(it)
            }
        }

        if (!type.endsWith(".done") && type != "response.output_item.done") {
            return null
        }

        event.optString("arguments").takeIf { it.isNotBlank() }?.let {
            pendingCall.argumentsBuilder.clear()
            pendingCall.argumentsBuilder.append(it)
        }

        val name = pendingCall.name.takeIf { it.isNotBlank() } ?: return null
        val parsedArguments = pendingCall.argumentsBuilder.toString().ifBlank { "{}" }
        val arguments = runCatching { JSONObject(parsedArguments) }.getOrElse {
            JSONObject().put("instruction", parsedArguments)
        }
        pending.remove(key)
        completedKeys.add(key)
        return RealtimeToolCall(callId = pendingCall.callId, itemId = pendingCall.itemId, name = name, arguments = arguments)
    }

    private data class PendingCall(
        val callId: String,
        val itemId: String?,
        var name: String = "",
        val argumentsBuilder: StringBuilder = StringBuilder()
    )
}

private fun JSONObject.callIdOrNull(): String? {
    optString("call_id").takeIf { it.isNotBlank() }?.let { return it }
    optString("callId").takeIf { it.isNotBlank() }?.let { return it }
    optJSONObject("item")?.optString("call_id")?.takeIf { it.isNotBlank() }?.let { return it }
    return null
}
