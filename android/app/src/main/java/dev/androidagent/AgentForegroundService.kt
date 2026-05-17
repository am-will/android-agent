package dev.androidagent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
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

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        createChannel()
        ServiceCompat.startForeground(this, 1, notification(), foregroundServiceType(includeMicrophone = false))
        voiceTranscriptionManager = VoiceTranscriptionManager(onStateChanged = ::handleTranscriptionState)
        overlayController = OverlayController(
            context = this,
            onSubmit = { text -> webSocketClient?.sendUserRequest(text, AgentConfigStore.load(this)) },
            onStop = { webSocketClient?.sendStopRequest("Stopped from Android overlay") },
            onDismiss = { stopSelf() },
            onStartVoice = {
                promoteVoiceForegroundIfAllowed()
                voiceRuntimeController?.start()
            },
            onToggleVoiceMute = { voiceRuntimeController?.toggleMute() },
            onStopVoice = { voiceRuntimeController?.stopFromUi() },
            onCancelVoiceTask = { webSocketClient?.sendStopRequest("Cancelled from Android voice UI") },
            onStartTranscription = { startComposerTranscription() },
            onStopTranscription = { stopComposerTranscription() },
            onCancelTranscription = { cancelComposerTranscription() }
        ).also { it.show() }
        voiceRuntimeController = VoiceRuntimeController(
            context = this,
            sendStart = { sdp, config -> webSocketClient?.sendRealtimeStart(sdp, config) },
            sendStop = { reason -> webSocketClient?.sendRealtimeStop(reason) },
            sendUserPrompt = { text, config -> webSocketClient?.sendUserRequest(text, config) },
            sendToolCall = { call -> webSocketClient?.sendRealtimeToolCall(call, AgentConfigStore.load(this)) },
            onStateChanged = { state -> overlayController?.setVoiceState(state) }
        )
        connectWebSocket()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        connectWebSocket()
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
            onStatus = { overlayController?.setStatus(it) },
            onRealtimeSdp = { voiceRuntimeController?.onRealtimeSdp(it) },
            onRealtimeTranscriptDelta = { voiceRuntimeController?.onRealtimeTranscriptDelta(it) },
            onRealtimeItemAdded = { voiceRuntimeController?.onRealtimeItemAdded(it) },
            onRealtimeSpeechStarted = { voiceRuntimeController?.onRealtimeSpeechStarted(it) },
            onRealtimeError = { voiceRuntimeController?.onRealtimeError(it) },
            onRealtimeClosed = { voiceRuntimeController?.onRealtimeClosed(it) },
            onRealtimeToolResult = { voiceRuntimeController?.onRealtimeToolResult(it) },
            onRealtimeTaskStatus = { voiceRuntimeController?.onRealtimeTaskStatus(it) }
        ).also { it.connect() }
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
            ServiceCompat.startForeground(this, 1, notification(), foregroundServiceType(includeMicrophone = true))
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
            ServiceCompat.startForeground(this, 1, notification(), foregroundServiceType(includeMicrophone = false))
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
            val channel = NotificationChannel(CHANNEL_ID, "Android Agent", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun notification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Android Agent active")
            .setContentText("Floating bubble and phone bridge are running")
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "AgentService"
        const val CHANNEL_ID = "android-agent"
        var isRunning: Boolean = false
            private set
    }
}
