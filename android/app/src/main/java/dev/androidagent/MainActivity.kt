package dev.androidagent

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityManager
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import dev.androidagent.accessibility.PhoneAccessibilityService

class MainActivity : ComponentActivity() {
    private lateinit var endpointSummary: TextView
    private lateinit var statusText: TextView
    private val statusChips = mutableMapOf<String, TextView>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val serviceStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AgentForegroundService.ACTION_STATE_CHANGED) {
                refreshStatus()
            }
        }
    }
    private var serviceStateReceiverRegistered = false
    private var systemPromptText: String = DefaultSystemPrompt.text

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applySystemBars()
        if (Build.VERSION.SDK_INT >= 33) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 10)
        }
        buildUi()
        maybeRequestMicPermission(intent)
        maybeStartAgentFromIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        registerServiceStateReceiver()
        refreshStatus()
    }

    override fun onStop() {
        unregisterServiceStateReceiver()
        super.onStop()
    }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
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

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_MIC_PERMISSION || requestCode == REQUEST_LOCATION_PERMISSION) {
            refreshStatusSoon()
        }
    }

    private fun buildUi() {
        val config = AgentConfigStore.load(this)
        systemPromptText = config.systemPrompt
        val palette = palette()

        statusChips.clear()

        val scrollView = ScrollView(this).apply {
            setBackgroundColor(palette.background)
            clipToPadding = false
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(96), dp(24), dp(36))
            clipToPadding = false
        }

        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(dp(24), bars.top + dp(48), dp(24), bars.bottom + dp(36))
            insets
        }

        root.addView(card(palette).apply {
            addView(pill("Private Open Claw bridge", palette))
            addView(title("Open Claw Agent", palette, 34f).apply {
                setPadding(0, dp(18), 0, 0)
            })
            addView(body("Delegate work to Open Claw on your remote PC from a floating phone bubble. Phone control is available when a task needs it.", palette).apply {
                setPadding(0, dp(10), 0, 0)
            })

            endpointSummary = body("", palette).apply {
                setPadding(0, dp(22), 0, 0)
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            }
            addView(endpointSummary)
        })

        root.addView(card(palette).apply {
            addView(sectionHeader("Readiness", "The agent only runs when the required Android capabilities are enabled.", palette))
            addView(statusRow("Overlay", "Required for the floating control bubble.", "overlay", palette))
            addView(statusRow("Microphone", "Used for realtime voice sessions.", "microphone", palette))
            addView(statusRow("Location", "Optional context for weather and local questions.", "location", palette))
            addView(statusRow("Accessibility", "Allows command execution on screen.", "accessibility", palette))
            addView(statusRow("Agent Bubble", "Foreground service state.", "service", palette))

            statusText = body("", palette).apply {
                setPadding(0, dp(18), 0, 0)
            }
            addView(statusText)
        }, stackedParams())

        root.addView(card(palette).apply {
            addView(sectionHeader("Connection & Config", "Server URL, auth token, model, reasoning, and system prompt now live in this submenu.", palette))
            addView(actionButton("Open Connection & Config", ButtonTone.Secondary, palette) {
                showConnectionConfigMenu()
            }, stackedParams(18))
        }, stackedParams())

        root.addView(card(palette).apply {
            addView(sectionHeader("Agent Controls", "Start the bubble after pairing and granting permissions.", palette))
            addView(actionButton("Start Agent Bubble", ButtonTone.Primary, palette) {
                ContextCompat.startForegroundService(
                    this@MainActivity,
                    Intent(this@MainActivity, AgentForegroundService::class.java)
                )
                refreshStatusSoon()
            }, stackedParams(18))
            addView(actionButton("Stop Agent Bubble", ButtonTone.Secondary, palette) {
                stopService(Intent(this@MainActivity, AgentForegroundService::class.java))
                refreshStatusSoon()
            }, stackedParams(10))
            addView(actionButton("Grant Overlay Permission", ButtonTone.Secondary, palette) {
                openOverlaySettings()
            }, stackedParams(10))
            addView(actionButton("Grant Microphone Permission", ButtonTone.Secondary, palette) {
                requestMicPermission()
            }, stackedParams(10))
            addView(actionButton("Grant Location Permission", ButtonTone.Secondary, palette) {
                requestLocationPermission()
            }, stackedParams(10))
            addView(actionButton("Open Accessibility Settings", ButtonTone.Secondary, palette) {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }, stackedParams(10))
        }, stackedParams())

        setContentView(scrollView.apply {
            addView(root, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        })
        refreshStatus()
    }

    private fun showConnectionConfigMenu() {
        val config = AgentConfigStore.load(this)
        var promptDraft = config.systemPrompt
        val palette = palette()

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), 0)
        }

        val hostInput = configField("WebSocket URL", config.hostUrl, palette)
        val deviceInput = configField("Device ID", config.deviceId, palette)
        val tokenInput = configField("Auth token", config.token, palette)
        val openAiKeyInput = configField(
            "OpenAI API key for realtime voice",
            config.openAiApiKey,
            palette,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        )

        content.addView(fieldLabel("Bridge", palette))
        content.addView(hostInput, stackedParams(8))
        content.addView(deviceInput, stackedParams(10))
        content.addView(tokenInput, stackedParams(10))
        content.addView(openAiKeyInput, stackedParams(10))

        content.addView(fieldLabel("Model", palette), stackedParams(18))
        val modelSpinner = styledSpinner(
            AgentModelOptions.models.map { it.label },
            AgentModelOptions.models.indexOfFirst { it.id == config.model }.coerceAtLeast(0),
            palette
        )
        content.addView(modelSpinner, stackedParams(8))

        content.addView(fieldLabel("Reasoning", palette), stackedParams(18))
        val reasoningSpinner = styledSpinner(
            AgentModelOptions.reasoningEfforts.map { it.label },
            AgentModelOptions.reasoningEfforts.indexOfFirst { it.id == config.reasoningEffort }.coerceAtLeast(1),
            palette
        )
        content.addView(reasoningSpinner, stackedParams(8))

        content.addView(fieldLabel("System prompt", palette), stackedParams(18))
        val promptSummary = body(systemPromptPreview(promptDraft), palette).apply {
            background = rounded(palette.surfaceAlt, dp(18), palette.border)
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }
        content.addView(promptSummary, stackedParams(8))
        content.addView(actionButton("Edit System Prompt", ButtonTone.Secondary, palette) {
            showSystemPromptEditor(promptDraft) { updated ->
                promptDraft = updated
                promptSummary.text = systemPromptPreview(promptDraft)
            }
        }, stackedParams(10))

        val dialog = AlertDialog.Builder(this)
            .setTitle("Connection & Config")
            .setView(ScrollView(this).apply {
                addView(content, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            })
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save") { _, _ ->
                val saved = AgentConfig(
                    hostUrl = hostInput.text.toString().trim(),
                    deviceId = deviceInput.text.toString().trim(),
                    token = tokenInput.text.toString().trim(),
                    openAiApiKey = openAiKeyInput.text.toString().trim(),
                    systemPrompt = promptDraft.trim().ifBlank { DefaultSystemPrompt.text },
                    model = AgentModelOptions.models.getOrElse(modelSpinner.selectedItemPosition) { AgentModelOptions.models.first() }.id,
                    reasoningEffort = AgentModelOptions.reasoningEfforts.getOrElse(reasoningSpinner.selectedItemPosition) { AgentModelOptions.reasoningEfforts[1] }.id
                )
                AgentConfigStore.save(this, saved)
                systemPromptText = saved.systemPrompt
                refreshStatus()
            }
            .create()
        dialog.setOnShowListener {
            dialog.window?.setBackgroundDrawable(rounded(palette.surface, dp(28), palette.border))
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(palette.primary)
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(palette.muted)
        }
        dialog.show()
    }

    private fun showSystemPromptEditor(initialText: String, onSave: (String) -> Unit) {
        val palette = palette()
        val editor = EditText(this).apply {
            setText(initialText)
            minLines = 10
            maxLines = 18
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setHorizontallyScrolling(false)
            setTextColor(palette.text)
            setHintTextColor(palette.muted)
            background = rounded(palette.surfaceAlt, dp(18), palette.border)
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle("System Prompt")
            .setView(ScrollView(this).apply {
                setPadding(dp(20), dp(16), dp(20), 0)
                addView(editor)
            })
            .setNegativeButton("Cancel", null)
            .setNeutralButton("Reset") { _, _ ->
                onSave(DefaultSystemPrompt.text)
            }
            .setPositiveButton("Save") { _, _ ->
                onSave(editor.text.toString().trim().ifBlank { DefaultSystemPrompt.text })
            }
            .create()
        dialog.setOnShowListener {
            dialog.window?.setBackgroundDrawable(rounded(palette.surface, dp(28), palette.border))
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(palette.primary)
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(palette.muted)
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.setTextColor(palette.muted)
        }
        dialog.show()
    }

    private fun systemPromptPreview(text: String): String {
        val normalized = text.trim().replace(Regex("\\s+"), " ")
        return if (normalized.length <= 140) {
            normalized
        } else {
            "${normalized.take(137)}..."
        }
    }

    private fun openOverlaySettings() {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName")
        )
        startActivity(intent)
    }

    private fun refreshStatus() {
        if (!::statusText.isInitialized || !::endpointSummary.isInitialized) return

        val config = AgentConfigStore.load(this)
        val overlay = Settings.canDrawOverlays(this)
        val microphone = hasMicPermission()
        val location = AgentLocationProvider.hasLocationPermission(this)
        val accessibility = isAccessibilityEnabled()
        val service = AgentForegroundService.isRunning
        val palette = palette()

        endpointSummary.text = """
            ${config.deviceId} -> ${config.hostUrl}
            ${modelLabel(config.model)} / ${reasoningLabel(config.reasoningEffort)} reasoning
        """.trimIndent()

        updateChip("overlay", if (overlay) "Granted" else "Missing", if (overlay) palette.success else palette.warning)
        updateChip("microphone", if (microphone) "Granted" else "Missing", if (microphone) palette.success else palette.warning)
        updateChip("location", if (location) "Granted" else "Optional", if (location) palette.success else palette.muted)
        updateChip("accessibility", if (accessibility) "Enabled" else "Disabled", if (accessibility) palette.success else palette.warning)
        updateChip("service", if (service) "Running" else "Stopped", if (service) palette.success else palette.muted)

        statusText.text = if (overlay && microphone && accessibility) {
            "Ready. Start the bubble when your Open Claw bridge is listening."
        } else {
            "Finish the missing permission steps before expecting reliable automation."
        }
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

    private fun requestLocationPermission() {
        if (!AgentLocationProvider.hasLocationPermission(this)) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION),
                REQUEST_LOCATION_PERMISSION
            )
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
            refreshStatusSoon()
        }
    }

    private fun registerServiceStateReceiver() {
        if (serviceStateReceiverRegistered) return

        ContextCompat.registerReceiver(
            this,
            serviceStateReceiver,
            IntentFilter(AgentForegroundService.ACTION_STATE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        serviceStateReceiverRegistered = true
    }

    private fun unregisterServiceStateReceiver() {
        if (!serviceStateReceiverRegistered) return

        unregisterReceiver(serviceStateReceiver)
        serviceStateReceiverRegistered = false
    }

    private fun refreshStatusSoon() {
        refreshStatus()
        mainHandler.postDelayed({ refreshStatus() }, 150)
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

    private fun applySystemBars() {
        val palette = palette()
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = palette.background
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = !isDarkTheme()
            isAppearanceLightNavigationBars = !isDarkTheme()
        }
    }

    private fun title(text: String, palette: Palette, size: Float): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = size
            setTextColor(palette.text)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            includeFontPadding = false
        }
    }

    private fun body(text: String, palette: Palette): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = 15f
            setTextColor(palette.muted)
            setLineSpacing(dp(2).toFloat(), 1.0f)
        }
    }

    private fun pill(text: String, palette: Palette): TextView {
        return TextView(this).apply {
            this.text = text.uppercase()
            textSize = 11f
            letterSpacing = 0.08f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            setTextColor(palette.primary)
            background = rounded(palette.surfaceAlt, dp(999), palette.border)
            setPadding(dp(12), dp(7), dp(12), dp(7))
        }
    }

    private fun sectionHeader(title: String, subtitle: String, palette: Palette): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(title(title, palette, 22f))
            addView(body(subtitle, palette).apply {
                setPadding(0, dp(8), 0, 0)
            })
        }
    }

    private fun statusRow(title: String, subtitle: String, key: String, palette: Palette): LinearLayout {
        return LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(18), 0, 0)

            val copy = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(this@MainActivity).apply {
                    text = title
                    textSize = 15f
                    setTextColor(palette.text)
                    typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                })
                addView(body(subtitle, palette).apply {
                    textSize = 13f
                    setPadding(0, dp(3), dp(10), 0)
                })
            }
            addView(copy, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

            val chip = TextView(this@MainActivity).apply {
                gravity = Gravity.CENTER
                textSize = 12f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                setPadding(dp(10), dp(7), dp(10), dp(7))
            }
            statusChips[key] = chip
            addView(chip)
        }
    }

    private fun actionButton(text: String, tone: ButtonTone, palette: Palette, onClick: () -> Unit): TextView {
        val background = when (tone) {
            ButtonTone.Primary -> palette.primary
            ButtonTone.Secondary -> palette.surfaceAlt
        }
        val border = when (tone) {
            ButtonTone.Primary -> palette.primary
            ButtonTone.Secondary -> palette.border
        }
        val textColor = when (tone) {
            ButtonTone.Primary -> palette.onPrimary
            ButtonTone.Secondary -> palette.text
        }

        return TextView(this).apply {
            this.text = text
            gravity = Gravity.CENTER
            textSize = 15f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            setTextColor(textColor)
            setPadding(dp(16), dp(14), dp(16), dp(14))
            this.background = rounded(background, dp(18), border)
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }
    }

    private fun fieldLabel(text: String, palette: Palette): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = 13f
            letterSpacing = 0.04f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            setTextColor(palette.muted)
        }
    }

    private fun configField(
        hint: String,
        value: String,
        palette: Palette,
        inputType: Int = InputType.TYPE_CLASS_TEXT
    ): EditText {
        return EditText(this).apply {
            this.hint = hint
            setText(value)
            setSingleLine(true)
            this.inputType = inputType
            setTextColor(palette.text)
            setHintTextColor(palette.muted)
            background = rounded(palette.surfaceAlt, dp(18), palette.border)
            setPadding(dp(16), 0, dp(16), 0)
        }
    }

    private fun styledSpinner(items: List<String>, selection: Int, palette: Palette): Spinner {
        val adapter = object : ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, items) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                return (super.getView(position, convertView, parent) as TextView).apply {
                    setTextColor(palette.text)
                    textSize = 15f
                }
            }

            override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View {
                return (super.getDropDownView(position, convertView, parent) as TextView).apply {
                    setTextColor(palette.text)
                    setBackgroundColor(palette.surface)
                    textSize = 15f
                    setPadding(dp(16), dp(14), dp(16), dp(14))
                }
            }
        }.apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }

        return Spinner(this).apply {
            this.adapter = adapter
            setSelection(selection)
            background = rounded(palette.surfaceAlt, dp(18), palette.border)
            setPadding(dp(12), 0, dp(12), 0)
        }
    }

    private fun card(palette: Palette): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(palette.surface, dp(28), palette.border)
            elevation = dp(2).toFloat()
            setPadding(dp(22), dp(22), dp(22), dp(22))
        }
    }

    private fun updateChip(key: String, text: String, color: Int) {
        statusChips[key]?.apply {
            this.text = text
            setTextColor(color)
            background = rounded(tint(color, if (isDarkTheme()) 0.18f else 0.12f), dp(999), tint(color, 0.35f))
        }
    }

    private fun rounded(fill: Int, radius: Int, stroke: Int? = null): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radius.toFloat()
            setColor(fill)
            stroke?.let { setStroke(dp(1), it) }
        }
    }

    private fun stackedParams(topMargin: Int = 16): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            this.topMargin = dp(topMargin)
        }
    }

    private fun modelLabel(id: String): String {
        return AgentModelOptions.models.firstOrNull { it.id == id }?.label ?: id
    }

    private fun reasoningLabel(id: String): String {
        return AgentModelOptions.reasoningEfforts.firstOrNull { it.id == id }?.label ?: id
    }

    private fun palette(): Palette {
        return if (isDarkTheme()) {
            Palette(
                background = Color.parseColor("#090E1A"),
                surface = Color.parseColor("#111827"),
                surfaceAlt = Color.parseColor("#182235"),
                text = Color.parseColor("#F8FAFC"),
                muted = Color.parseColor("#9CA3AF"),
                border = Color.parseColor("#2A3650"),
                primary = Color.parseColor("#7C9CFF"),
                onPrimary = Color.parseColor("#07101F"),
                success = Color.parseColor("#4ADE80"),
                warning = Color.parseColor("#FBBF24")
            )
        } else {
            Palette(
                background = Color.parseColor("#F5F7FB"),
                surface = Color.WHITE,
                surfaceAlt = Color.parseColor("#EEF3FF"),
                text = Color.parseColor("#101827"),
                muted = Color.parseColor("#64748B"),
                border = Color.parseColor("#D8E0EF"),
                primary = Color.parseColor("#245BFF"),
                onPrimary = Color.WHITE,
                success = Color.parseColor("#16A34A"),
                warning = Color.parseColor("#D97706")
            )
        }
    }

    private fun tint(color: Int, amount: Float): Int {
        return Color.argb(
            (255 * amount).toInt().coerceIn(0, 255),
            Color.red(color),
            Color.green(color),
            Color.blue(color)
        )
    }

    private fun isDarkTheme(): Boolean {
        return (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    private enum class ButtonTone {
        Primary,
        Secondary
    }

    private data class Palette(
        val background: Int,
        val surface: Int,
        val surfaceAlt: Int,
        val text: Int,
        val muted: Int,
        val border: Int,
        val primary: Int,
        val onPrimary: Int,
        val success: Int,
        val warning: Int
    )

    companion object {
        const val EXTRA_REQUEST_MIC_PERMISSION = "requestMicPermission"
        private const val REQUEST_MIC_PERMISSION = 20
        private const val REQUEST_LOCATION_PERMISSION = 21
    }
}
