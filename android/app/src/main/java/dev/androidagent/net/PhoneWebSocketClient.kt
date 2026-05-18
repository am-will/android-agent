package dev.androidagent.net

import dev.androidagent.AgentConfig
import dev.androidagent.AgentLocation
import dev.androidagent.accessibility.AccessibilityCommandExecutor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import android.os.Handler
import android.os.Looper
import android.util.Log
import dev.androidagent.voice.RealtimeToolCall
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class PhoneWebSocketClient(
    private val config: AgentConfig,
    private val commandExecutor: AccessibilityCommandExecutor,
    private val onStatus: (String, String) -> Unit,
    private val onRealtimeSdp: (JSONObject) -> Unit = {},
    private val onRealtimeTranscriptDelta: (JSONObject) -> Unit = {},
    private val onRealtimeItemAdded: (JSONObject) -> Unit = {},
    private val onRealtimeSpeechStarted: (JSONObject) -> Unit = {},
    private val onRealtimeError: (JSONObject) -> Unit = {},
    private val onRealtimeClosed: (JSONObject) -> Unit = {},
    private val onRealtimeToolResult: (JSONObject) -> Unit = {},
    private val onRealtimeTaskStatus: (JSONObject) -> Unit = {},
    private val onChatMessage: (JSONObject) -> Unit = {}
) : WebSocketListener() {
    private val client = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .build()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var socket: WebSocket? = null
    private var manuallyClosed = false
    private var reconnectAttempts = 0
    private var connected = false

    fun connect() {
        manuallyClosed = false
        connected = false
        val request = Request.Builder().url(config.hostUrl).build()
        socket = client.newWebSocket(request, this)
        onStatus("Connecting to ${config.hostUrl}", "info")
    }

    fun close() {
        manuallyClosed = true
        connected = false
        socket?.close(1000, "service stopped")
        socket = null
        mainHandler.removeCallbacksAndMessages(null)
        client.dispatcher.executorService.shutdown()
    }

    fun sendUserRequest(text: String, requestConfig: AgentConfig = config) {
        val message = JSONObject()
            .put("type", "user_request")
            .put("deviceId", requestConfig.deviceId)
            .put("inputType", "text")
            .put("text", text)
            .put("systemPrompt", requestConfig.systemPrompt)
            .put("model", requestConfig.model)
            .put("reasoningEffort", requestConfig.reasoningEffort)
        socket?.send(message.toString())
    }

    fun sendStopRequest(reason: String) {
        val message = JSONObject()
            .put("type", "agent_control")
            .put("deviceId", config.deviceId)
            .put("action", "stop")
            .put("reason", reason)
        socket?.send(message.toString())
    }

    fun sendChatOpen(sessionKey: String? = null): Boolean {
        val message = JSONObject()
            .put("type", "chat.open")
            .put("deviceId", config.deviceId)
        sessionKey?.takeIf { it.isNotBlank() }?.let { message.put("sessionKey", it) }
        return sendJson(message)
    }

    fun sendChatMessage(text: String, sessionKey: String? = null, model: String? = null, reasoningEffort: String? = null): Boolean {
        val message = JSONObject()
            .put("type", "chat.send")
            .put("deviceId", config.deviceId)
            .put("text", text)
        sessionKey?.takeIf { it.isNotBlank() }?.let { message.put("sessionKey", it) }
        model?.takeIf { it.isNotBlank() }?.let { message.put("model", it) }
        reasoningEffort?.takeIf { it.isNotBlank() }?.let { message.put("reasoningEffort", it) }
        return sendJson(message, reportChatError = true)
    }

    fun sendChatStop(sessionKey: String? = null, runId: String? = null, reason: String = "Stopped from Android chat") {
        val message = JSONObject()
            .put("type", "chat.stop")
            .put("deviceId", config.deviceId)
            .put("reason", reason)
        sessionKey?.takeIf { it.isNotBlank() }?.let { message.put("sessionKey", it) }
        runId?.takeIf { it.isNotBlank() }?.let { message.put("runId", it) }
        sendJson(message, reportChatError = true)
    }

    fun sendChatSelectSession(sessionKey: String) {
        val message = JSONObject()
            .put("type", "chat.select_session")
            .put("deviceId", config.deviceId)
            .put("sessionKey", sessionKey)
        sendJson(message, reportChatError = true)
    }

    fun sendChatNewSession(label: String? = null, model: String? = null) {
        val message = JSONObject()
            .put("type", "chat.new_session")
            .put("deviceId", config.deviceId)
        label?.takeIf { it.isNotBlank() }?.let { message.put("label", it) }
        model?.takeIf { it.isNotBlank() }?.let { message.put("model", it) }
        sendJson(message, reportChatError = true)
    }

    fun sendChatSetModel(sessionKey: String?, model: String) {
        val message = JSONObject()
            .put("type", "chat.set_model")
            .put("deviceId", config.deviceId)
            .put("model", model)
        sessionKey?.takeIf { it.isNotBlank() }?.let { message.put("sessionKey", it) }
        sendJson(message, reportChatError = true)
    }

    fun sendChatSetReasoning(sessionKey: String?, reasoningEffort: String) {
        val message = JSONObject()
            .put("type", "chat.set_reasoning")
            .put("deviceId", config.deviceId)
            .put("reasoningEffort", reasoningEffort)
        sessionKey?.takeIf { it.isNotBlank() }?.let { message.put("sessionKey", it) }
        sendJson(message, reportChatError = true)
    }

    fun sendChatControlCommand(command: String, args: JSONObject = JSONObject()) {
        val message = JSONObject()
            .put("type", "chat.control_command")
            .put("deviceId", config.deviceId)
            .put("command", command)
            .put("args", args)
        socket?.send(message.toString())
    }

    fun sendRealtimeStart(sdp: String, requestConfig: AgentConfig = config, location: AgentLocation? = null) {
        val message = JSONObject()
            .put("type", "realtime.start")
            .put("deviceId", requestConfig.deviceId)
            .put("sdp", sdp)
            .put("systemPrompt", requestConfig.systemPrompt)
            .put("model", requestConfig.model)
            .put("reasoningEffort", requestConfig.reasoningEffort)
        requestConfig.openAiApiKey.takeIf { it.isNotBlank() }?.let { message.put("openAiApiKey", it) }
        location?.let { message.put("location", it.toJson()) }
        val sent = socket?.send(message.toString()) == true
        Log.i(TAG, "sendRealtimeStart sent=$sent sdpLength=${sdp.length}")
        if (!sent) {
            onRealtimeError(JSONObject().put("type", "realtime.error").put("message", "Phone WebSocket is not connected for realtime voice."))
        }
    }

    fun sendRealtimeStop(reason: String) {
        val message = JSONObject()
            .put("type", "realtime.stop")
            .put("deviceId", config.deviceId)
            .put("reason", reason)
        val sent = socket?.send(message.toString()) == true
        Log.i(TAG, "sendRealtimeStop sent=$sent")
    }

    fun sendRealtimeToolCall(call: RealtimeToolCall, requestConfig: AgentConfig = config) {
        val message = JSONObject()
            .put("type", "realtime.tool_call")
            .put("deviceId", requestConfig.deviceId)
            .put("callId", call.callId)
            .put("name", call.name)
            .put("arguments", call.arguments)
        call.itemId?.let { message.put("itemId", it) }
        val sent = socket?.send(message.toString()) == true
        Log.i(TAG, "sendRealtimeToolCall sent=$sent callId=${call.callId} name=${call.name}")
        if (!sent) {
            onRealtimeError(JSONObject().put("type", "realtime.error").put("message", "Phone WebSocket is not connected for realtime tool calls."))
        }
    }

    override fun onOpen(webSocket: WebSocket, response: Response) {
        reconnectAttempts = 0
        connected = true
        val register = JSONObject()
            .put("type", "register")
            .put("deviceId", config.deviceId)
            .put("token", config.token)
            .put(
                "capabilities",
                JSONArray(listOf("accessibility_tree", "gestures", "text_input", "screenshots", "app_launch", "realtime_voice", "gateway_chat"))
            )
        webSocket.send(register.toString())
        sendChatOpen()
        onStatus("Connected and registered as ${config.deviceId}", "info")
    }

    override fun onMessage(webSocket: WebSocket, text: String) {
        val message = JSONObject(text)
        if (message.optString("type").startsWith("realtime.")) {
            Log.i(TAG, "received ${message.optString("type")}")
        }
        when (message.optString("type")) {
            "command" -> handleCommand(webSocket, message)
            "agent_status" -> onStatus(message.optString("text"), message.optString("status", "info"))
            "chat.state",
            "chat.history",
            "chat.delta",
            "chat.reasoning_delta",
            "chat.reasoning_clear",
            "chat.final",
            "chat.error",
            "chat.tool_event",
            "chat.models",
            "chat.commands",
            "chat.tools",
            "chat.sessions",
            "chat.usage" -> onChatMessage(message)
            "realtime.sdp" -> onRealtimeSdp(message)
            "realtime.transcript_delta" -> onRealtimeTranscriptDelta(message)
            "realtime.item_added" -> onRealtimeItemAdded(message)
            "realtime.speech_started" -> onRealtimeSpeechStarted(message)
            "realtime.error" -> onRealtimeError(message)
            "realtime.closed" -> onRealtimeClosed(message)
            "realtime.tool_result" -> onRealtimeToolResult(message)
            "realtime.task_status" -> onRealtimeTaskStatus(message)
        }
    }

    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
        connected = false
        onStatus("WebSocket error: ${t.message}", "error")
        scheduleReconnect()
    }

    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
        connected = false
        onStatus("Disconnected: $reason", "error")
        scheduleReconnect()
    }

    private fun sendJson(message: JSONObject, reportChatError: Boolean = false): Boolean {
        val type = message.optString("type", "message")
        val sent = connected && socket?.send(message.toString()) == true
        Log.i(TAG, "send $type sent=$sent connected=$connected")
        if (!sent) {
            val error = "Bridge is not connected. Check the PC bridge at ${config.hostUrl}; reconnecting..."
            onStatus(error, "error")
            if (reportChatError) {
                onChatMessage(JSONObject()
                    .put("type", "chat.error")
                    .put("deviceId", config.deviceId)
                    .put("message", error))
            }
        }
        return sent
    }

    private fun scheduleReconnect() {
        if (manuallyClosed) {
            return
        }
        val delayMs = (1_000L * (reconnectAttempts + 1)).coerceAtMost(10_000L)
        reconnectAttempts += 1
        mainHandler.postDelayed({ connect() }, delayMs)
    }

    private fun handleCommand(webSocket: WebSocket, message: JSONObject) {
        val id = message.getString("id")
        val command = message.getString("command")
        val args = message.optJSONObject("args") ?: JSONObject()
        commandExecutor.execute(command, args) { result ->
            val response = JSONObject()
                .put("id", id)
                .put("type", "result")
                .put("ok", result.ok)
                .put("observation", result.observation)
                .put("error", result.error)
            result.screenshotBase64?.let { response.put("screenshotBase64", it) }
            result.screenshot?.let { response.put("screenshot", it) }
            webSocket.send(response.toString())
        }
    }

    companion object {
        private const val TAG = "PhoneWebSocketClient"
    }
}
