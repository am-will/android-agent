package dev.androidagent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.Manifest
import android.app.ForegroundServiceStartNotAllowedException
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.app.ServiceCompat
import dev.androidagent.accessibility.AccessibilityCommandExecutor
import dev.androidagent.chat.ChatState
import dev.androidagent.chat.ChatStateReducer
import dev.androidagent.net.PhoneWebSocketClient
import dev.androidagent.voice.VoiceRuntimeController
import dev.androidagent.voice.transcription.VoiceTranscriptionManager
import dev.androidagent.voice.transcription.VoiceTranscriptionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class AgentForegroundService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var overlayController: OverlayController? = null
    private var webSocketClient: PhoneWebSocketClient? = null
    private var voiceRuntimeController: VoiceRuntimeController? = null
    private var voiceTranscriptionManager: VoiceTranscriptionManager? = null
    private var lastNotificationText = DEFAULT_NOTIFICATION_TEXT
    private var isAgentTurnActive = false
    private var chatState = ChatState()

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        broadcastRunningState()
        createChannel()
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(), foregroundServiceType(includeMicrophone = false))
        voiceTranscriptionManager = VoiceTranscriptionManager(onStateChanged = ::handleTranscriptionState)
        overlayController = OverlayController(
            context = this,
            onSubmit = { text -> submitChatText(text) },
            onStop = { requestStopTurn("Stopped from Android overlay") },
            onDismiss = { stopSelf() },
            onStartVoice = {
                promoteVoiceForegroundIfAllowed()
                voiceRuntimeController?.start()
            },
            onToggleVoiceMute = { voiceRuntimeController?.toggleMute() },
            onStopVoice = { voiceRuntimeController?.stopFromUi() },
            onStartTranscription = { startComposerTranscription() },
            onStopTranscription = { stopComposerTranscription() },
            onCancelTranscription = { cancelComposerTranscription() }
        ).also { it.show() }
        voiceRuntimeController = VoiceRuntimeController(
            context = this,
            sendStart = { sdp, config -> webSocketClient?.sendRealtimeStart(sdp, config, AgentLocationProvider.currentBestEffortLocation(this)) },
            sendStop = { reason ->
                webSocketClient?.sendRealtimeStop(reason)
                webSocketClient?.sendStopRequest(reason)
            },
            sendToolCall = { call -> webSocketClient?.sendRealtimeToolCall(call, AgentConfigStore.load(this)) },
            onStateChanged = { state -> overlayController?.setVoiceState(state) }
        )
        connectWebSocket()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        connectWebSocket()
        if (intent?.action == ACTION_STOP_TURN) {
            requestStopTurn("Stopped from Android notification")
            return START_STICKY
        }
        overlayController?.show()
        return START_STICKY
    }

    override fun onDestroy() {
        voiceRuntimeController?.stopFromUi()
        voiceRuntimeController?.close()
        voiceTranscriptionManager?.close()
        serviceScope.cancel()
        webSocketClient?.close()
        overlayController?.hide()
        isRunning = false
        broadcastRunningState()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun connectWebSocket() {
        if (webSocketClient != null) {
            return
        }
        val config = AgentConfigStore.load(this)
        val executor = AccessibilityCommandExecutor(this, overlayController)
        webSocketClient = PhoneWebSocketClient(
            config = config,
            commandExecutor = executor,
            onStatus = ::handleBridgeStatus,
            onRealtimeSdp = { voiceRuntimeController?.onRealtimeSdp(it) },
            onRealtimeTranscriptDelta = { voiceRuntimeController?.onRealtimeTranscriptDelta(it) },
            onRealtimeItemAdded = { voiceRuntimeController?.onRealtimeItemAdded(it) },
            onRealtimeSpeechStarted = { voiceRuntimeController?.onRealtimeSpeechStarted(it) },
            onRealtimeError = { voiceRuntimeController?.onRealtimeError(it) },
            onRealtimeClosed = { voiceRuntimeController?.onRealtimeClosed(it) },
            onRealtimeToolResult = { voiceRuntimeController?.onRealtimeToolResult(it) },
            onRealtimeTaskStatus = { voiceRuntimeController?.onRealtimeTaskStatus(it) },
            onChatMessage = { handleChatMessage(it) }
        ).also { it.connect() }
    }

    private fun submitChatText(text: String) {
        connectWebSocket()
        chatState = ChatStateReducer.localUserMessage(chatState, text)
        overlayController?.setChatState(chatState)
        webSocketClient?.sendChatMessage(
            text = text,
            sessionKey = chatState.sessionKey,
            model = chatState.selectedModel,
            reasoningEffort = chatState.reasoningEffort
        )
        lastNotificationText = "Sent to OpenClaw"
        isAgentTurnActive = true
        updateNotification()
    }

    private fun handleChatMessage(message: org.json.JSONObject) {
        serviceScope.launch {
            chatState = ChatStateReducer.reduce(chatState, message)
            overlayController?.setChatState(chatState)
            chatState.status?.takeIf { it.isNotBlank() }?.let { lastNotificationText = it }
            isAgentTurnActive = chatState.isRunning
            updateNotification()
        }
    }

    private fun startComposerTranscription() {
        val manager = voiceTranscriptionManager ?: return
        if (!hasMicPermission()) {
            overlayController?.setStatus("Microphone permission is required for transcription.")
            openMicPermissionScreen()
            return
        }

        promoteVoiceForegroundIfAllowed()
        val started = manager.startRecording(this) {
            stopComposerTranscription()
        }
        if (!started) {
            overlayController?.setStatus(manager.currentState().error ?: "Could not start transcription recording.")
            restoreBaseForeground()
        }
    }

    private fun stopComposerTranscription() {
        val manager = voiceTranscriptionManager ?: return
        val state = manager.currentState()
        if (!state.isRecording || state.isTranscribing) {
            return
        }

        serviceScope.launch {
            val transcript = manager.stopAndTranscribe(AgentConfigStore.load(this@AgentForegroundService).openAiApiKey)
            restoreBaseForeground()
            if (transcript != null) {
                overlayController?.insertComposerTranscript(transcript)
            }
        }
    }

    private fun cancelComposerTranscription() {
        voiceTranscriptionManager?.cancelRecording()
        restoreBaseForeground()
        overlayController?.setStatus("Transcription recording canceled.")
    }

    private fun handleTranscriptionState(state: VoiceTranscriptionState) {
        overlayController?.setTranscriptionState(state)
    }

    private fun handleBridgeStatus(text: String, status: String) {
        serviceScope.launch {
            overlayController?.setStatus(text)
            lastNotificationText = text.ifBlank { DEFAULT_NOTIFICATION_TEXT }
            isAgentTurnActive = when (status) {
                "working", "tool" -> true
                "done", "error" -> false
                else -> isAgentTurnActive
            }
            updateNotification()
        }
    }

    private fun requestStopTurn(reason: String) {
        connectWebSocket()
        overlayController?.setStatus("Stop requested")
        lastNotificationText = "Stopping active turn..."
        isAgentTurnActive = true
        updateNotification()
        webSocketClient?.sendChatStop(chatState.sessionKey, chatState.activeRunId, reason)
        webSocketClient?.sendStopRequest(reason)
    }

    private fun foregroundServiceType(includeMicrophone: Boolean): Int {
        return when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> {
                var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                if (includeMicrophone) {
                    type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                }
                type
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ->
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            else -> 0
        }
    }

    private fun hasMicPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    }

    private fun promoteVoiceForegroundIfAllowed() {
        if (!hasMicPermission()) {
            return
        }
        runCatching {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(), foregroundServiceType(includeMicrophone = true))
        }.onFailure { error ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && error is ForegroundServiceStartNotAllowedException) {
                Log.w(TAG, "Voice foreground-service promotion was not allowed; continuing with existing foreground service.", error)
            } else if (error is SecurityException || error is IllegalArgumentException) {
                Log.w(TAG, "Voice foreground-service promotion failed; continuing with existing foreground service.", error)
            } else {
                throw error
            }
        }
    }

    private fun restoreBaseForeground() {
        runCatching {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(), foregroundServiceType(includeMicrophone = false))
        }.onFailure { error ->
            if (error is SecurityException || error is IllegalArgumentException) {
                Log.w(TAG, "Foreground-service restore failed; continuing with existing foreground service.", error)
            } else {
                throw error
            }
        }
    }

    private fun openMicPermissionScreen() {
        startActivity(
            Intent(this, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_REQUEST_MIC_PERMISSION, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        )
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(CHANNEL_ID, "Open Claw Agent", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun updateNotification() {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification())
    }

    private fun notification(): Notification {
        val stopIntent = Intent(this, AgentForegroundService::class.java).setAction(ACTION_STOP_TURN)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val stopPendingIntent = PendingIntent.getService(this, 0, stopIntent, flags)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(if (isAgentTurnActive) "Open Claw Agent working" else "Open Claw Agent active")
            .setContentText(lastNotificationText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(lastNotificationText))
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop Turn", stopPendingIntent)
            .build()
    }

    private fun broadcastRunningState() {
        sendBroadcast(
            Intent(ACTION_STATE_CHANGED)
                .setPackage(packageName)
                .putExtra(EXTRA_IS_RUNNING, isRunning)
        )
    }

    companion object {
        private const val TAG = "AgentService"
        private const val ACTION_STOP_TURN = "dev.openclawagent.action.STOP_TURN"
        private const val NOTIFICATION_ID = 1
        private const val DEFAULT_NOTIFICATION_TEXT = "Floating bubble and Open Claw bridge are running"
        const val ACTION_STATE_CHANGED = "dev.openclawagent.action.AGENT_SERVICE_STATE_CHANGED"
        const val EXTRA_IS_RUNNING = "isRunning"
        const val CHANNEL_ID = "open-claw-agent"
        var isRunning: Boolean = false
            private set
    }
}
