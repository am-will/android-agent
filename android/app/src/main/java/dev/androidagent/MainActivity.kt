package dev.androidagent

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.ViewGroup
import android.view.accessibility.AccessibilityManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import dev.androidagent.accessibility.PhoneAccessibilityService

class MainActivity : ComponentActivity() {
    private lateinit var hostInput: EditText
    private lateinit var deviceInput: EditText
    private lateinit var tokenInput: EditText
    private lateinit var systemPromptInput: EditText
    private lateinit var modelSpinner: Spinner
    private lateinit var reasoningSpinner: Spinner
    private lateinit var statusText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 10)
        }
        buildUi()
        maybeRequestMicPermission(intent)
        maybeStartAgentFromIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        maybeRequestMicPermission(intent)
        maybeStartAgentFromIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun buildUi() {
        val config = AgentConfigStore.load(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 48, 32, 32)
        }

        root.addView(TextView(this).apply {
            text = "Android-to-Codex Agent"
            textSize = 24f
        })

        root.addView(TextView(this).apply {
            text = "Pair this phone with the PC bridge, grant overlay and accessibility permissions, then start the agent bubble."
        })

        hostInput = EditText(this).apply {
            hint = "WebSocket URL"
            setText(config.hostUrl)
            setSingleLine(true)
        }
        deviceInput = EditText(this).apply {
            hint = "Device ID"
            setText(config.deviceId)
            setSingleLine(true)
        }
        tokenInput = EditText(this).apply {
            hint = "Auth token"
            setText(config.token)
            setSingleLine(true)
        }

        root.addView(hostInput)
        root.addView(deviceInput)
        root.addView(tokenInput)

        root.addView(TextView(this).apply {
            text = "Model"
            textSize = 18f
            setPadding(0, 24, 0, 4)
        })
        modelSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                AgentModelOptions.models.map { it.label }
            )
            setSelection(AgentModelOptions.models.indexOfFirst { it.id == config.model }.coerceAtLeast(0))
        }
        root.addView(modelSpinner)

        root.addView(TextView(this).apply {
            text = "Reasoning level"
            textSize = 18f
            setPadding(0, 16, 0, 4)
        })
        reasoningSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                AgentModelOptions.reasoningEfforts.map { it.label }
            )
            setSelection(AgentModelOptions.reasoningEfforts.indexOfFirst { it.id == config.reasoningEffort }.coerceAtLeast(1))
        }
        root.addView(reasoningSpinner)

        root.addView(TextView(this).apply {
            text = "System prompt"
            textSize = 18f
            setPadding(0, 24, 0, 4)
        })
        root.addView(TextView(this).apply {
            text = "This prompt is sent with every new request. Each request starts a fresh Codex thread."
            textSize = 13f
        })
        systemPromptInput = EditText(this).apply {
            hint = "System prompt"
            setText(config.systemPrompt)
            minLines = 8
            maxLines = 16
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setHorizontallyScrolling(false)
        }
        root.addView(systemPromptInput)

        root.addView(Button(this).apply {
            text = "Save Settings"
            setOnClickListener {
                saveConfig()
                refreshStatus()
            }
        })

        root.addView(Button(this).apply {
            text = "Grant Overlay Permission"
            setOnClickListener { openOverlaySettings() }
        })

        root.addView(Button(this).apply {
            text = "Grant Microphone Permission"
            setOnClickListener { requestMicPermission() }
        })

        root.addView(Button(this).apply {
            text = "Open Accessibility Settings"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        })

        root.addView(Button(this).apply {
            text = "Start Agent Bubble"
            setOnClickListener {
                saveConfig()
                ContextCompat.startForegroundService(
                    this@MainActivity,
                    Intent(this@MainActivity, AgentForegroundService::class.java)
                )
                refreshStatus()
            }
        })

        root.addView(Button(this).apply {
            text = "Stop Agent Bubble"
            setOnClickListener {
                stopService(Intent(this@MainActivity, AgentForegroundService::class.java))
                refreshStatus()
            }
        })

        statusText = TextView(this).apply { textSize = 14f }
        root.addView(statusText)

        setContentView(ScrollView(this).apply {
            addView(root, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        })
        refreshStatus()
    }

    private fun saveConfig() {
        AgentConfigStore.save(
            this,
            AgentConfig(
                hostUrl = hostInput.text.toString().trim(),
                deviceId = deviceInput.text.toString().trim(),
                token = tokenInput.text.toString().trim(),
                systemPrompt = systemPromptInput.text.toString().trim().ifBlank { DefaultSystemPrompt.text },
                model = AgentModelOptions.models.getOrElse(modelSpinner.selectedItemPosition) { AgentModelOptions.models.first() }.id,
                reasoningEffort = AgentModelOptions.reasoningEfforts.getOrElse(reasoningSpinner.selectedItemPosition) { AgentModelOptions.reasoningEfforts[1] }.id
            )
        )
    }

    private fun openOverlaySettings() {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName")
        )
        startActivity(intent)
    }

    private fun refreshStatus() {
        val overlay = Settings.canDrawOverlays(this)
        val microphone = hasMicPermission()
        val accessibility = isAccessibilityEnabled()
        statusText.text = """
            Overlay permission: ${if (overlay) "granted" else "missing"}
            Microphone permission: ${if (microphone) "granted" else "missing"}
            Accessibility service: ${if (accessibility) "enabled" else "disabled"}
            Foreground service: ${if (AgentForegroundService.isRunning) "running" else "stopped"}
        """.trimIndent()
    }

    private fun maybeRequestMicPermission(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_REQUEST_MIC_PERMISSION, false) == true) {
            requestMicPermission()
        }
    }

    private fun requestMicPermission() {
        if (!hasMicPermission()) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_MIC_PERMISSION)
        }
    }

    private fun hasMicPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    }

    private fun maybeStartAgentFromIntent(intent: Intent?) {
        if (intent?.getBooleanExtra("startAgent", false) == true) {
            ContextCompat.startForegroundService(
                this,
                Intent(this, AgentForegroundService::class.java)
            )
        }
    }

    private fun isAccessibilityEnabled(): Boolean {
        val expected = ComponentName(this, PhoneAccessibilityService::class.java)
        val enabledSetting = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ).orEmpty()
        val enabledBySecureSetting = enabledSetting.split(':').any { flattened ->
            ComponentName.unflattenFromString(flattened)?.let { component ->
                component.packageName == expected.packageName && component.className == expected.className
            } == true
        }

        val manager = getSystemService(ACCESSIBILITY_SERVICE) as AccessibilityManager
        val enabledByManager = manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { service ->
                val serviceInfo = service.resolveInfo.serviceInfo
                serviceInfo.packageName == expected.packageName && serviceInfo.name == expected.className
            }

        return enabledBySecureSetting || enabledByManager
    }

    companion object {
        const val EXTRA_REQUEST_MIC_PERMISSION = "requestMicPermission"
        private const val REQUEST_MIC_PERMISSION = 20
    }
}
