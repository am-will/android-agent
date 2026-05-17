package dev.androidagent.voice

import org.json.JSONArray
import org.json.JSONObject

enum class VoiceTranscriptRole(val displayName: String) {
    USER("You"),
    ASSISTANT("Codex")
}

data class VoiceTranscriptLine(
    val itemId: String,
    val role: VoiceTranscriptRole,
    val text: String,
    val isFinal: Boolean
)

data class VoiceTranscriptState(
    val lines: List<VoiceTranscriptLine>
) {
    val displayText: String = lines
        .filter { it.text.isNotBlank() }
        .joinToString("\n") { "${it.role.displayName}: ${it.text}" }
    val userText: String = lines
        .filter { it.role == VoiceTranscriptRole.USER && it.text.isNotBlank() }
        .joinToString("\n") { it.text }
}

class RealtimeTranscriptNormalizer {
    private val lines = LinkedHashMap<String, VoiceTranscriptLine>()
    private var fallbackCounter = 0

    fun reset(): VoiceTranscriptState {
        lines.clear()
        fallbackCounter = 0
        return snapshot()
    }

    fun snapshot(): VoiceTranscriptState = VoiceTranscriptState(lines.values.toList())

    fun applyEvent(type: String, payload: JSONObject): VoiceTranscriptState {
        when (type) {
            "realtime.speech_started",
            "input_audio_buffer.speech_started" -> clearNonFinalUserSpeech()

            "realtime.transcript_delta",
            "response.audio_transcript.delta",
            "response.audio_transcript.done",
            "response.output_audio_transcript.delta",
            "response.output_audio_transcript.done",
            "response.output_text.delta",
            "response.output_text.done",
            "conversation.item.input_audio_transcription.delta",
            "conversation.item.input_audio_transcription.completed" -> applyTranscript(type, payload)

            "realtime.item_added",
            "conversation.item.created" -> applyItemAdded(payload)
        }
        return snapshot()
    }

    private fun clearNonFinalUserSpeech() {
        val activeUserItems = lines.values
            .filter { it.role == VoiceTranscriptRole.USER && !it.isFinal }
            .map { it.itemId }
        activeUserItems.forEach(lines::remove)
    }

    private fun applyTranscript(type: String, payload: JSONObject) {
        val role = roleFrom(payload, defaultRoleFor(type))
        val text = payload.firstString("text", "delta", "transcript")
            ?: payload.optJSONObject("transcript")?.firstString("text", "delta", "transcript")
            ?: return
        val itemId = itemIdFrom(payload, role)
        val isFinal = payload.optBoolean("isFinal", payload.optBoolean("final", type.endsWith(".done") || type.endsWith(".completed")))
        upsertLine(itemId, role, text, isFinal)
    }

    private fun applyItemAdded(payload: JSONObject) {
        val item = payload.optJSONObject("item") ?: payload
        val role = roleFrom(item, roleFrom(payload, VoiceTranscriptRole.ASSISTANT))
        val text = item.firstString("text", "transcript", "delta")
            ?: textFromContentArray(item.optJSONArray("content"))
            ?: textFromContentArray(payload.optJSONArray("content"))
            ?: return
        val itemId = item.firstString("itemId", "item_id", "id")
            ?: payload.firstString("itemId", "item_id", "id")
            ?: nextFallbackId(role)
        upsertLine(itemId, role, text, isFinal = true)
    }

    private fun upsertLine(itemId: String, role: VoiceTranscriptRole, incomingText: String, isFinal: Boolean) {
        val current = lines[itemId]
        val mergedText = mergeText(current?.text.orEmpty(), incomingText, isFinal)
        lines[itemId] = VoiceTranscriptLine(
            itemId = itemId,
            role = current?.role ?: role,
            text = mergedText,
            isFinal = current?.isFinal == true || isFinal
        )
    }

    private fun itemIdFrom(payload: JSONObject, role: VoiceTranscriptRole): String {
        return payload.firstString("itemId", "item_id", "id", "response_id")
            ?: payload.optJSONObject("item")?.firstString("itemId", "item_id", "id")
            ?: nextFallbackId(role)
    }

    private fun nextFallbackId(role: VoiceTranscriptRole): String {
        val activeFallback = lines.values.lastOrNull { line ->
            line.role == role && !line.isFinal && line.itemId.startsWith("${role.name.lowercase()}-live-")
        }
        if (activeFallback != null) {
            return activeFallback.itemId
        }
        fallbackCounter += 1
        return "${role.name.lowercase()}-live-$fallbackCounter"
    }

    private fun roleFrom(payload: JSONObject, default: VoiceTranscriptRole): VoiceTranscriptRole {
        return when (payload.optString("role").lowercase()) {
            "user" -> VoiceTranscriptRole.USER
            "assistant" -> VoiceTranscriptRole.ASSISTANT
            else -> default
        }
    }

    private fun defaultRoleFor(type: String): VoiceTranscriptRole {
        return if (type.startsWith("conversation.item.input_audio")) {
            VoiceTranscriptRole.USER
        } else {
            VoiceTranscriptRole.ASSISTANT
        }
    }

    private fun textFromContentArray(content: JSONArray?): String? {
        if (content == null) {
            return null
        }
        val parts = mutableListOf<String>()
        for (index in 0 until content.length()) {
            val part = content.optJSONObject(index) ?: continue
            part.firstString("text", "transcript")?.let(parts::add)
        }
        return parts.joinToString("").takeIf { it.isNotBlank() }
    }

    private fun mergeText(existing: String, incoming: String, isFinal: Boolean): String {
        if (incoming.isBlank()) {
            return existing
        }
        if (existing.isBlank() || incoming.startsWith(existing)) {
            return incoming
        }
        if (existing.endsWith(incoming) || existing == incoming) {
            return existing
        }
        if (isFinal && incoming.length >= existing.length && commonPrefixLength(existing, incoming) >= minOf(8, existing.length)) {
            return incoming
        }
        val maxOverlap = minOf(existing.length, incoming.length)
        for (overlap in maxOverlap downTo 1) {
            if (existing.regionMatches(existing.length - overlap, incoming, 0, overlap)) {
                return existing + incoming.drop(overlap)
            }
        }
        return existing + incoming
    }

    private fun commonPrefixLength(left: String, right: String): Int {
        val maxLength = minOf(left.length, right.length)
        for (index in 0 until maxLength) {
            if (left[index] != right[index]) {
                return index
            }
        }
        return maxLength
    }
}

private fun JSONObject.firstString(vararg keys: String): String? {
    for (key in keys) {
        val value = optString(key)
        if (value.isNotBlank()) {
            return value
        }
    }
    return null
}
