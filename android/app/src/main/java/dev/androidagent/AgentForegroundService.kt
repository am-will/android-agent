package dev.androidagent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.app.ServiceCompat
import dev.androidagent.accessibility.AccessibilityCommandExecutor
import dev.androidagent.net.PhoneWebSocketClient
import dev.androidagent.voice.VoiceRuntimeController

class AgentForegroundService : Service() {
    private var overlayController: OverlayController? = null
    private var webSocketClient: PhoneWebSocketClient? = null
    private var voiceRuntimeController: VoiceRuntimeController? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        createChannel()
        ServiceCompat.startForeground(this, 1, notification(), foregroundServiceType(includeMicrophone = false))
        overlayController = OverlayController(
            context = this,
            onSubmit = { text -> webSocketClient?.sendUserRequest(text, AgentConfigStore.load(this)) },
            onStop = { webSocketClient?.sendStopRequest("Stopped from Android overlay") },
            onStartVoice = {
                if (hasMicPermission()) {
                    ServiceCompat.startForeground(this, 1, notification(), foregroundServiceType(includeMicrophone = true))
                }
                voiceRuntimeController?.start()
            },
            onToggleVoiceMute = { voiceRuntimeController?.toggleMute() },
            onStopVoice = { voiceRuntimeController?.stopFromUi() }
        ).also { it.show() }
        voiceRuntimeController = VoiceRuntimeController(
            context = this,
            sendStart = { sdp, config -> webSocketClient?.sendRealtimeStart(sdp, config) },
            sendStop = { reason -> webSocketClient?.sendRealtimeStop(reason) },
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
            onRealtimeClosed = { voiceRuntimeController?.onRealtimeClosed(it) }
        ).also { it.connect() }
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
        const val CHANNEL_ID = "android-agent"
        var isRunning: Boolean = false
            private set
    }
}
