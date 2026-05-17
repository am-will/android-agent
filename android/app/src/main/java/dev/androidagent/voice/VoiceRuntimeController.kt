package dev.androidagent.voice

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import dev.androidagent.AgentConfig
import dev.androidagent.AgentConfigStore
import dev.androidagent.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONObject

enum class VoiceRuntimeStatus(val label: String) {
    IDLE("Idle"),
    CONNECTING("Connecting"),
    LISTENING("Listening"),
    THINKING("Thinking"),
    SPEAKING("Speaking"),
    ERROR("Error")
}

data class VoiceRuntimeState(
    val status: VoiceRuntimeStatus = VoiceRuntimeStatus.IDLE,
    val transcript: String = "",
    val isMuted: Boolean = false,
    val error: String? = null
) {
    val isActive: Boolean = status != VoiceRuntimeStatus.IDLE && status != VoiceRuntimeStatus.ERROR
}

class VoiceRuntimeController(
    private val context: Context,
    private val sendStart: (sdp: String, config: AgentConfig) -> Unit,
    private val sendStop: (reason: String) -> Unit,
    private val sendUserPrompt: (text: String, config: AgentConfig) -> Unit,
    private val sendToolCall: (RealtimeToolCall) -> Unit = {},
    private val onStateChanged: (VoiceRuntimeState) -> Unit
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val transcriptNormalizer = RealtimeTranscriptNormalizer()
    private val toolCallAccumulator = RealtimeToolCallAccumulator()
    private val toolOutputsSent = mutableSetOf<String>()
    private var session: RealtimeWebRtcSession? = null
    private var state = VoiceRuntimeState()

    fun start() {
        if (state.isActive || state.status == VoiceRuntimeStatus.CONNECTING) {
            return
        }
        if (!hasMicPermission()) {
            updateState(
                VoiceRuntimeState(
                    status = VoiceRuntimeStatus.ERROR,
                    error = "Microphone permission is required for voice mode."
                )
            )
            openMicPermissionScreen()
            return
        }

        val config = AgentConfigStore.load(context)
        val nextSession = RealtimeWebRtcSession(
            context = context,
            onDataChannelEvent = ::handleDataChannelEvent,
            onConnectionState = ::handleConnectionState
        )
        session = nextSession
        transcriptNormalizer.reset()
        toolCallAccumulator.reset()
        toolOutputsSent.clear()
        updateState(VoiceRuntimeState(status = VoiceRuntimeStatus.CONNECTING))

        scope.launch {
            runCatching {
                val offer = nextSession.createOffer()
                sendStart(offer, config)
                updateState(state.copy(status = VoiceRuntimeStatus.CONNECTING, error = "Waiting for realtime answer."))
            }.onFailure { error ->
                cleanup(sendBackendStop = false)
                updateState(
                    VoiceRuntimeState(
                        status = VoiceRuntimeStatus.ERROR,
                        error = error.message ?: error.toString()
                    )
                )
            }
        }
    }

    fun toggleMute() {
        val muted = !state.isMuted
        session?.setMuted(muted)
        updateState(state.copy(isMuted = muted))
    }

    fun stopFromUi() {
        val spokenPrompt = transcriptNormalizer.snapshot().userText.trim()
        if (spokenPrompt.isNotBlank()) {
            sendUserPrompt(spokenPrompt, AgentConfigStore.load(context))
        }
        cleanup(sendBackendStop = true, reason = "Stopped from Android voice UI")
    }

    fun close() {
        scope.cancel()
        cleanup(sendBackendStop = false)
    }

    fun onRealtimeSdp(payload: JSONObject) {
        val answerSdp = payload.optString("sdp").ifBlank {
            payload.optString("answer").ifBlank { payload.optString("answerSdp") }
        }
        if (answerSdp.isBlank()) {
            showBackendError("Realtime SDP answer was missing.")
            return
        }
        scope.launch {
            runCatching {
                session?.applyAnswer(answerSdp)
                    ?: throw IllegalStateException("No active voice session for SDP answer.")
                updateState(state.copy(status = VoiceRuntimeStatus.LISTENING, error = null))
            }.onFailure { error ->
                showBackendError(error.message ?: error.toString())
            }
        }
    }

    fun onRealtimeTranscriptDelta(payload: JSONObject) {
        scope.launch {
            val transcript = transcriptNormalizer.applyEvent("realtime.transcript_delta", payload)
            updateState(
                state.copy(
                    status = statusForTranscript(payload, default = VoiceRuntimeStatus.SPEAKING),
                    transcript = transcript.displayText,
                    error = null
                )
            )
        }
    }

    fun onRealtimeItemAdded(payload: JSONObject) {
        scope.launch {
            val transcript = transcriptNormalizer.applyEvent("realtime.item_added", payload)
            updateState(
                state.copy(
                    status = statusForTranscript(payload, default = VoiceRuntimeStatus.LISTENING),
                    transcript = transcript.displayText,
                    error = null
                )
            )
        }
    }

    fun onRealtimeSpeechStarted(payload: JSONObject) {
        scope.launch {
            val transcript = transcriptNormalizer.applyEvent("realtime.speech_started", payload)
            updateState(state.copy(status = VoiceRuntimeStatus.LISTENING, transcript = transcript.displayText, error = null))
        }
    }

    fun onRealtimeError(payload: JSONObject) {
        scope.launch {
            showBackendError(payload.optString("message").ifBlank { payload.optString("error").ifBlank { "Realtime voice failed." } })
        }
    }

    fun onRealtimeClosed(payload: JSONObject) {
        scope.launch {
            val reason = payload.optString("reason").ifBlank { "Realtime voice closed." }
            cleanup(sendBackendStop = false)
            updateState(VoiceRuntimeState(status = VoiceRuntimeStatus.IDLE, transcript = state.transcript, error = reason))
        }
    }

    fun onRealtimeToolResult(payload: JSONObject) {
        scope.launch {
            val callId = payload.optString("callId").ifBlank { payload.optString("call_id") }
            if (callId.isBlank() || !toolOutputsSent.add(callId)) {
                return@launch
            }
            val output = JSONObject()
                .put("ok", payload.optBoolean("ok", false))
                .put("status", payload.optString("status").ifBlank { if (payload.optBoolean("ok", false)) "completed" else "failed" })
            payload.optString("output").takeIf { it.isNotBlank() }?.let { output.put("output", it) }
            payload.optString("error").takeIf { it.isNotBlank() }?.let { output.put("error", it) }

            val sentOutput = session?.sendJsonEvent(
                JSONObject()
                    .put("type", "conversation.item.create")
                    .put(
                        "item",
                        JSONObject()
                            .put("type", "function_call_output")
                            .put("call_id", callId)
                            .put("output", output.toString())
                    )
            ) == true
            if (sentOutput) {
                session?.sendJsonEvent(JSONObject().put("type", "response.create"))
                updateState(state.copy(status = VoiceRuntimeStatus.THINKING, error = null))
            } else {
                updateState(state.copy(error = "Could not send realtime tool output."))
            }
        }
    }

    private fun handleDataChannelEvent(raw: String) {
        val event = runCatching { JSONObject(raw) }.getOrNull() ?: return
        val type = event.optString("type")
        if (type.isBlank()) {
            return
        }
        scope.launch {
            toolCallAccumulator.apply(event)?.let { call ->
                sendToolCall(call)
                updateState(state.copy(status = VoiceRuntimeStatus.THINKING, error = null))
                return@launch
            }
            when {
                type.contains("error") -> showBackendError(event.optString("message").ifBlank { event.optString("error").ifBlank { "Realtime voice failed." } })
                type == "input_audio_buffer.speech_started" -> {
                    val transcript = transcriptNormalizer.applyEvent(type, event)
                    updateState(state.copy(status = VoiceRuntimeStatus.LISTENING, transcript = transcript.displayText, error = null))
                }
                type.contains("transcript") || type == "conversation.item.created" || type.startsWith("response.output_text.") -> {
                    val transcript = transcriptNormalizer.applyEvent(type, event)
                    updateState(
                        state.copy(
                            status = statusForTranscript(event, default = statusForDataChannel(type)),
                            transcript = transcript.displayText,
                            error = null
                        )
                    )
                }
            }
        }
    }

    private fun handleConnectionState(connectionState: String) {
        scope.launch {
            if (session == null) {
                return@launch
            }
            when (connectionState) {
                "connected", "completed" -> updateState(state.copy(status = VoiceRuntimeStatus.LISTENING, error = null))
                "failed", "closed", "disconnected" -> updateState(state.copy(status = VoiceRuntimeStatus.ERROR, error = "WebRTC connection $connectionState."))
            }
        }
    }

    private fun statusForTranscript(payload: JSONObject, default: VoiceRuntimeStatus): VoiceRuntimeStatus {
        return when (payload.optString("role").lowercase()) {
            "user" -> VoiceRuntimeStatus.THINKING
            "assistant" -> VoiceRuntimeStatus.SPEAKING
            else -> default
        }
    }

    private fun statusForDataChannel(type: String): VoiceRuntimeStatus {
        return if (type.startsWith("conversation.item.input_audio")) {
            VoiceRuntimeStatus.THINKING
        } else {
            VoiceRuntimeStatus.SPEAKING
        }
    }

    private fun showBackendError(message: String) {
        cleanup(sendBackendStop = false)
        updateState(VoiceRuntimeState(status = VoiceRuntimeStatus.ERROR, transcript = state.transcript, error = message))
    }

    private fun cleanup(sendBackendStop: Boolean, reason: String = "Voice session stopped") {
        val hadSession = session != null || state.isActive
        session?.close()
        session = null
        if (sendBackendStop && hadSession) {
            sendStop(reason)
        }
        updateState(VoiceRuntimeState(transcript = state.transcript))
    }

    private fun updateState(next: VoiceRuntimeState) {
        state = next
        onStateChanged(next)
    }

    private fun hasMicPermission(): Boolean {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    }

    private fun openMicPermissionScreen() {
        context.startActivity(
            Intent(context, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_REQUEST_MIC_PERMISSION, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        )
    }
}
