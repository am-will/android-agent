package dev.androidagent

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat

class LauncherActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Settings.canDrawOverlays(this)) {
            val intent = Intent(this, AgentForegroundService::class.java)
                .setAction(AgentForegroundService.ACTION_OPEN_CHAT)
                .putExtra(
                    AgentForegroundService.EXTRA_PANEL_PRESENTATION,
                    AgentForegroundService.PANEL_PRESENTATION_FULLSCREEN
                )
            runCatching { ContextCompat.startForegroundService(this, intent) }
        } else {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            )
        }

        finish()
        overridePendingTransition(0, 0)
    }
}
