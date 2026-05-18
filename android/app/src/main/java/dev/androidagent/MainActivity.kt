package dev.androidagent

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
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
import dev.androidagent.ui.DesignTokens
import dev.androidagent.ui.Drawables
import dev.androidagent.ui.ThemeTokens
import dev.androidagent.ui.Typography

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
        if (shouldLaunchChatDirectly(intent)) {
            launchChatModalAndFinish()
            return
        }
        buildUi()
        maybeRequestMicPermission(intent)
        maybeStartAgentFromIntent(intent)
    }

    private fun shouldLaunchChatDirectly(launchIntent: Intent?): Boolean {
        if (launchIntent?.getBooleanExtra(EXTRA_SHOW_SETTINGS, false) == true) return false
        if (!Settings.canDrawOverlays(this)) return false
        return true
    }

    private fun launchChatModalAndFinish() {
        val intent = Intent(this, AgentForegroundService::class.java)
            .setAction(AgentForegroundService.ACTION_OPEN_CHAT)
        runCatching { ContextCompat.startForegroundService(this, intent) }
        finish()
        overridePendingTransition(0, 0)
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
        if (shouldLaunchChatDirectly(intent)) {
            launchChatModalAndFinish()
            return
        }
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
        val tokens = tokens()

        statusChips.clear()

        val scrollView = ScrollView(this).apply {
            setBackgroundColor(tokens.background)
            clipToPadding = false
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(DesignTokens.Spacing.xxl), dp(96), dp(DesignTokens.Spacing.xxl), dp(DesignTokens.Spacing.xxxl + 4))
            clipToPadding = false
        }

        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(dp(DesignTokens.Spacing.xxl), bars.top + dp(48), dp(DesignTokens.Spacing.xxl), bars.bottom + dp(DesignTokens.Spacing.xxxl + 4))
            insets
        }

        root.addView(card(tokens).apply {
            addView(pill("Private Open Claw bridge", tokens))
            addView(title("Open Claw Agent", tokens, large = true).apply {
                setPadding(0, dp(DesignTokens.Spacing.lg), 0, 0)
            })
            addView(body("Delegate work to Open Claw on your remote PC from a floating phone bubble. Phone control is available when a task needs it.", tokens).apply {
                setPadding(0, dp(DesignTokens.Spacing.md), 0, 0)
            })

            endpointSummary = body("", tokens, secondary = false).apply {
                setPadding(0, dp(DesignTokens.Spacing.xl), 0, 0)
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            }
            addView(endpointSummary)
        })

        root.addView(card(tokens).apply {
            addView(sectionHeader("Readiness", "The agent only runs when the required Android capabilities are enabled.", tokens))
            addView(statusRow("Overlay", "Required for the floating control bubble.", "overlay", tokens))
            addView(statusRow("Microphone", "Used for realtime voice sessions.", "microphone", tokens))
            addView(statusRow("Location", "Optional context for weather and local questions.", "location", tokens))
            addView(statusRow("Accessibility", "Allows command execution on screen.", "accessibility", tokens))
            addView(statusRow("Agent Bubble", "Foreground service state.", "service", tokens))

            statusText = body("", tokens).apply {
                setPadding(0, dp(DesignTokens.Spacing.lg), 0, 0)
            }
            addView(statusText)
        }, stackedParams())

        root.addView(card(tokens).apply {
            addView(sectionHeader("Connection & Config", "Server URL, auth token, model, reasoning, and system prompt now live in this submenu.", tokens))
            addView(actionButton("Open Connection & Config", ButtonTone.Secondary, tokens) {
                showConnectionConfigMenu()
            }, stackedParams(DesignTokens.Spacing.lg))
        }, stackedParams())

        root.addView(card(tokens).apply {
            addView(sectionHeader("Agent Controls", "Start the bubble after pairing and granting permissions.", tokens))
            addView(actionButton("Start Agent Bubble", ButtonTone.Primary, tokens) {
                ContextCompat.startForegroundService(
                    this@MainActivity,
                    Intent(this@MainActivity, AgentForegroundService::class.java)
                )
                refreshStatusSoon()
            }, stackedParams(DesignTokens.Spacing.lg))
            addView(actionButton("Stop Agent Bubble", ButtonTone.Secondary, tokens) {
                stopService(Intent(this@MainActivity, AgentForegroundService::class.java))
                refreshStatusSoon()
            }, stackedParams(DesignTokens.Spacing.sm + 2))
            addView(actionButton("Grant Overlay Permission", ButtonTone.Secondary, tokens) {
                openOverlaySettings()
            }, stackedParams(DesignTokens.Spacing.sm + 2))
            addView(actionButton("Grant Microphone Permission", ButtonTone.Secondary, tokens) {
                requestMicPermission()
            }, stackedParams(DesignTokens.Spacing.sm + 2))
            addView(actionButton("Grant Location Permission", ButtonTone.Secondary, tokens) {
                requestLocationPermission()
            }, stackedParams(DesignTokens.Spacing.sm + 2))
            addView(actionButton("Open Accessibility Settings", ButtonTone.Secondary, tokens) {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }, stackedParams(DesignTokens.Spacing.sm + 2))
        }, stackedParams())

        setContentView(scrollView.apply {
            addView(root, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        })
        refreshStatus()
    }

    private fun showConnectionConfigMenu() {
        val config = AgentConfigStore.load(this)
        var promptDraft = config.systemPrompt
        val tokens = tokens()

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(DesignTokens.Spacing.xl), dp(DesignTokens.Spacing.md), dp(DesignTokens.Spacing.xl), 0)
        }

        val hostInput = configField("WebSocket URL", config.hostUrl, tokens)
        val deviceInput = configField("Device ID", config.deviceId, tokens)
        val tokenInput = configField("Auth token", config.token, tokens)
        val openAiKeyInput = configField(
            "OpenAI API key for realtime voice",
            config.openAiApiKey,
            tokens,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        )

        content.addView(fieldLabel("Bridge", tokens))
        content.addView(hostInput, stackedParams(DesignTokens.Spacing.sm))
        content.addView(deviceInput, stackedParams(DesignTokens.Spacing.sm + 2))
        content.addView(tokenInput, stackedParams(DesignTokens.Spacing.sm + 2))
        content.addView(openAiKeyInput, stackedParams(DesignTokens.Spacing.sm + 2))

        content.addView(fieldLabel("Model", tokens), stackedParams(DesignTokens.Spacing.lg))
        val modelSpinner = styledSpinner(
            AgentModelOptions.models.map { it.label },
            AgentModelOptions.models.indexOfFirst { it.id == config.model }.coerceAtLeast(0),
            tokens
        )
        content.addView(modelSpinner, stackedParams(DesignTokens.Spacing.sm))

        content.addView(fieldLabel("Reasoning", tokens), stackedParams(DesignTokens.Spacing.lg))
        val reasoningSpinner = styledSpinner(
            AgentModelOptions.reasoningEfforts.map { it.label },
            AgentModelOptions.reasoningEfforts.indexOfFirst { it.id == config.reasoningEffort }.coerceAtLeast(1),
            tokens
        )
        content.addView(reasoningSpinner, stackedParams(DesignTokens.Spacing.sm))

        content.addView(fieldLabel("System prompt", tokens), stackedParams(DesignTokens.Spacing.lg))
        val promptSummary = body(systemPromptPreview(promptDraft), tokens).apply {
            background = Drawables.glassInset(this@MainActivity, tokens, DesignTokens.Radius.md)
            setPadding(dp(DesignTokens.Spacing.lg), dp(DesignTokens.Spacing.md + 2), dp(DesignTokens.Spacing.lg), dp(DesignTokens.Spacing.md + 2))
        }
        content.addView(promptSummary, stackedParams(DesignTokens.Spacing.sm))
        content.addView(actionButton("Edit System Prompt", ButtonTone.Secondary, tokens) {
            showSystemPromptEditor(promptDraft) { updated ->
                promptDraft = updated
                promptSummary.text = systemPromptPreview(promptDraft)
            }
        }, stackedParams(DesignTokens.Spacing.sm + 2))

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
            dialog.window?.setBackgroundDrawable(
                Drawables.glassSurface(this@MainActivity, tokens, DesignTokens.Radius.xl)
            )
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(tokens.accent)
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(tokens.secondaryText)
        }
        dialog.show()
    }

    private fun showSystemPromptEditor(initialText: String, onSave: (String) -> Unit) {
        val tokens = tokens()
        val editor = EditText(this).apply {
            setText(initialText)
            minLines = 10
            maxLines = 18
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setHorizontallyScrolling(false)
            setTextColor(tokens.primaryText)
            setHintTextColor(tokens.tertiaryText)
            textSize = DesignTokens.Text.callout
            background = Drawables.glassInset(this@MainActivity, tokens, DesignTokens.Radius.md)
            setPadding(dp(DesignTokens.Spacing.lg), dp(DesignTokens.Spacing.md + 2), dp(DesignTokens.Spacing.lg), dp(DesignTokens.Spacing.md + 2))
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle("System Prompt")
            .setView(ScrollView(this).apply {
                setPadding(dp(DesignTokens.Spacing.xl), dp(DesignTokens.Spacing.lg), dp(DesignTokens.Spacing.xl), 0)
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
            dialog.window?.setBackgroundDrawable(
                Drawables.glassSurface(this@MainActivity, tokens, DesignTokens.Radius.xl)
            )
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(tokens.accent)
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(tokens.secondaryText)
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.setTextColor(tokens.secondaryText)
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
        val tokens = tokens()

        endpointSummary.text = """
            ${config.deviceId} -> ${config.hostUrl}
            ${modelLabel(config.model)} / ${reasoningLabel(config.reasoningEffort)} reasoning
        """.trimIndent()

        updateChip("overlay", if (overlay) "Granted" else "Missing", if (overlay) tokens.success else tokens.warning)
        updateChip("microphone", if (microphone) "Granted" else "Missing", if (microphone) tokens.success else tokens.warning)
        updateChip("location", if (location) "Granted" else "Optional", if (location) tokens.success else tokens.secondaryText)
        updateChip("accessibility", if (accessibility) "Enabled" else "Disabled", if (accessibility) tokens.success else tokens.warning)
        updateChip("service", if (service) "Running" else "Stopped", if (service) tokens.success else tokens.secondaryText)

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
        val tokens = tokens()
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = tokens.background
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = !tokens.isDark
            isAppearanceLightNavigationBars = !tokens.isDark
        }
    }

    private fun title(text: String, tokens: ThemeTokens, large: Boolean = false): TextView {
        return TextView(this).apply {
            this.text = text
            if (large) Typography.applyLargeTitle(this, tokens) else Typography.applyTitle(this, tokens)
        }
    }

    private fun body(text: String, tokens: ThemeTokens, secondary: Boolean = true): TextView {
        return TextView(this).apply {
            this.text = text
            Typography.applyCallout(this, tokens, secondary = secondary)
            setLineSpacing(dp(2).toFloat(), 1.0f)
        }
    }

    private fun pill(text: String, tokens: ThemeTokens): TextView {
        return TextView(this).apply {
            this.text = text.uppercase()
            Typography.applyOverline(this, tokens)
            setTextColor(tokens.accent)
            background = Drawables.accentSoftSurface(this@MainActivity, tokens)
            setPadding(dp(DesignTokens.Spacing.md), dp(DesignTokens.Spacing.sm), dp(DesignTokens.Spacing.md), dp(DesignTokens.Spacing.sm))
        }
    }

    private fun sectionHeader(title: String, subtitle: String, tokens: ThemeTokens): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(title(title, tokens))
            addView(body(subtitle, tokens).apply {
                setPadding(0, dp(DesignTokens.Spacing.sm), 0, 0)
            })
        }
    }

    private fun statusRow(title: String, subtitle: String, key: String, tokens: ThemeTokens): LinearLayout {
        return LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(DesignTokens.Spacing.lg), 0, 0)

            val copy = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(this@MainActivity).apply {
                    text = title
                    Typography.applyCallout(this, tokens)
                    typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                })
                addView(body(subtitle, tokens).apply {
                    textSize = DesignTokens.Text.footnote
                    setPadding(0, dp(3), dp(DesignTokens.Spacing.md), 0)
                })
            }
            addView(copy, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

            val chip = TextView(this@MainActivity).apply {
                gravity = Gravity.CENTER
                textSize = DesignTokens.Text.footnote
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                setPadding(dp(DesignTokens.Spacing.md), dp(6), dp(DesignTokens.Spacing.md), dp(6))
            }
            statusChips[key] = chip
            addView(chip)
        }
    }

    private fun actionButton(text: String, tone: ButtonTone, tokens: ThemeTokens, onClick: () -> Unit): TextView {
        val (bg, textColor) = when (tone) {
            ButtonTone.Primary -> Drawables.accentSurface(this, tokens, DesignTokens.Radius.md) to tokens.accentInk
            ButtonTone.Secondary -> Drawables.glassSurface(this, tokens, DesignTokens.Radius.md) to tokens.primaryText
        }
        return TextView(this).apply {
            this.text = text
            gravity = Gravity.CENTER
            Typography.applyCallout(this, tokens)
            setTextColor(textColor)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            setPadding(dp(DesignTokens.Spacing.lg), dp(DesignTokens.Spacing.md + 2), dp(DesignTokens.Spacing.lg), dp(DesignTokens.Spacing.md + 2))
            background = bg
            isClickable = true
            isFocusable = true
            minHeight = dp(DesignTokens.Sizes.action)
            setOnClickListener { onClick() }
        }
    }

    private fun fieldLabel(text: String, tokens: ThemeTokens): TextView {
        return TextView(this).apply {
            this.text = text
            Typography.applyFootnote(this, tokens, secondary = true)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            letterSpacing = 0.04f
        }
    }

    private fun configField(
        hint: String,
        value: String,
        tokens: ThemeTokens,
        inputType: Int = InputType.TYPE_CLASS_TEXT
    ): EditText {
        return EditText(this).apply {
            this.hint = hint
            setText(value)
            setSingleLine(true)
            this.inputType = inputType
            setTextColor(tokens.primaryText)
            setHintTextColor(tokens.tertiaryText)
            textSize = DesignTokens.Text.callout
            background = Drawables.glassInset(this@MainActivity, tokens, DesignTokens.Radius.md)
            setPadding(dp(DesignTokens.Spacing.lg), 0, dp(DesignTokens.Spacing.lg), 0)
            minHeight = dp(DesignTokens.Sizes.action)
        }
    }

    private fun styledSpinner(items: List<String>, selection: Int, tokens: ThemeTokens): Spinner {
        val adapter = object : ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, items) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                return (super.getView(position, convertView, parent) as TextView).apply {
                    setTextColor(tokens.primaryText)
                    textSize = DesignTokens.Text.callout
                    setPadding(dp(DesignTokens.Spacing.sm), 0, dp(DesignTokens.Spacing.sm), 0)
                }
            }

            override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View {
                return (super.getDropDownView(position, convertView, parent) as TextView).apply {
                    setTextColor(tokens.primaryText)
                    setBackgroundColor(tokens.surfaceElevated)
                    textSize = DesignTokens.Text.callout
                    setPadding(dp(DesignTokens.Spacing.lg), dp(DesignTokens.Spacing.md), dp(DesignTokens.Spacing.lg), dp(DesignTokens.Spacing.md))
                }
            }
        }.apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }

        return Spinner(this).apply {
            this.adapter = adapter
            setSelection(selection)
            background = Drawables.glassInset(this@MainActivity, tokens, DesignTokens.Radius.md)
            setPadding(dp(DesignTokens.Spacing.md), 0, dp(DesignTokens.Spacing.md), 0)
            minimumHeight = dp(DesignTokens.Sizes.action)
        }
    }

    private fun card(tokens: ThemeTokens): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Drawables.glassSurface(this@MainActivity, tokens, DesignTokens.Radius.xl)
            elevation = dp(DesignTokens.Elevation.low).toFloat()
            setPadding(dp(DesignTokens.Spacing.xxl), dp(DesignTokens.Spacing.xxl), dp(DesignTokens.Spacing.xxl), dp(DesignTokens.Spacing.xxl))
        }
    }

    private fun updateChip(key: String, text: String, color: Int) {
        val tokens = tokens()
        statusChips[key]?.apply {
            this.text = text
            setTextColor(color)
            background = Drawables.rounded(
                fill = tint(color, if (tokens.isDark) 0.20f else 0.12f),
                radius = dp(DesignTokens.Radius.pill).toFloat(),
                strokeColor = tint(color, 0.40f),
                strokeWidth = dp(1).coerceAtLeast(1)
            )
        }
    }

    private fun stackedParams(topMargin: Int = DesignTokens.Spacing.lg): LinearLayout.LayoutParams {
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

    private fun tokens(): ThemeTokens = DesignTokens.resolve(this)

    private fun tint(color: Int, amount: Float): Int {
        return Color.argb(
            (255 * amount).toInt().coerceIn(0, 255),
            Color.red(color),
            Color.green(color),
            Color.blue(color)
        )
    }

    private fun isDarkTheme(): Boolean = DesignTokens.isNightMode(this)

    private fun dp(value: Int): Int = DesignTokens.dp(this, value)

    private enum class ButtonTone {
        Primary,
        Secondary
    }

    companion object {
        const val EXTRA_REQUEST_MIC_PERMISSION = "requestMicPermission"
        const val EXTRA_SHOW_SETTINGS = "showSettings"
        private const val REQUEST_MIC_PERMISSION = 20
        private const val REQUEST_LOCATION_PERMISSION = 21
    }
}
