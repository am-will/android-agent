package dev.androidagent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import dev.androidagent.accessibility.AccessibilityCommandExecutor
import dev.androidagent.net.PhoneWebSocketClient

class AgentForegroundService : Service() {
    private var overlayController: OverlayController? = null
    private var webSocketClient: PhoneWebSocketClient? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        createChannel()
        startForeground(1, notification())
        overlayController = OverlayController(
            context = this,
            onSubmit = { text -> webSocketClient?.sendUserRequest(text, AgentConfigStore.load(this)) },
            onStop = { webSocketClient?.sendStopRequest("Stopped from Android overlay") }
        ).also { it.show() }
        connectWebSocket()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        connectWebSocket()
        overlayController?.show()
        return START_STICKY
    }

    override fun onDestroy() {
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
            onStatus = { overlayController?.setStatus(it) }
        ).also { it.connect() }
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
