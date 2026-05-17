package dev.androidagent

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.app.UiModeManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.method.ScrollingMovementMethod
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import dev.androidagent.voice.VoiceRuntimeState
import dev.androidagent.voice.VoiceRuntimeStatus
import dev.androidagent.voice.transcription.VoiceTranscriptionState
import kotlinx.coroutines.CompletableDeferred
import kotlin.math.roundToInt

class OverlayController(
    private val context: Context,
    private val onSubmit: (String) -> Unit,
    private val onStop: () -> Unit,
    private val onDismiss: () -> Unit,
    private val onStartVoice: () -> Unit,
    private val onToggleVoiceMute: () -> Unit,
    private val onStopVoice: () -> Unit,
    private val onStartTranscription: () -> Unit,
    private val onStopTranscription: () -> Unit,
    private val onCancelTranscription: () -> Unit
) {
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private val trashShowInterpolator = DecelerateInterpolator()
    private val trashHideInterpolator = AccelerateInterpolator()
    private var bubbleView: View? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var bubblePulseAnimator: AnimatorSet? = null
    private var lastBubbleX: Int? = null
    private var lastBubbleY: Int? = null
    private var panelView: View? = null
    private var panelScrimView: View? = null
    private var trashTargetView: ImageView? = null
    private var trashTargetBounds = Rect()
    private var isBubbleOverTrashTarget = false
    private var isDismissAnimating = false
    private var confirmationView: View? = null
    private var confirmationScrimView: View? = null
    private var statusText: TextView? = null
    private var voiceSurface: LinearLayout? = null
    private var voiceStatusText: TextView? = null
    private var voiceTranscriptText: TextView? = null
    private var voiceTaskText: TextView? = null
    private var voiceResultText: TextView? = null
    private var voiceMuteButton: Button? = null
    private var voiceHangupButton: Button? = null
    private var lastVoiceState = VoiceRuntimeState()
    private var composerInput: EditText? = null
    private var transcriptionMicButton: ImageButton? = null
    private var transcriptionCancelButton: Button? = null
    private var lastTranscriptionState = VoiceTranscriptionState()
    private var automationSuppressionDepth = 0
    private var restoreBubbleAfterAutomation = false

    private data class OverlayPalette(
        val surface: Int,
        val recessedSurface: Int,
        val controlSurface: Int,
        val primaryText: Int,
        val secondaryText: Int,
        val border: Int,
        val highlight: Int,
        val accent: Int
    )

    fun show() {
        if (!Settings.canDrawOverlays(context) || bubbleView != null || automationSuppressionDepth > 0) {
            return
        }
        val bubble = ImageButton(context).apply {
            setImageResource(R.drawable.codex_bubble_logo)
            scaleType = ImageView.ScaleType.FIT_CENTER
            background = bubbleBackgroundForVoiceState(lastVoiceState)
            contentDescription = "Android Agent"
            elevation = dp(8).toFloat()
            setPadding(dp(10), dp(10), dp(10), dp(10))
            setOnClickListener { togglePanel() }
        }
        val params = overlayParams(width = dp(88), height = dp(88), focusable = false).apply {
            gravity = Gravity.TOP or Gravity.START
            x = lastBubbleX ?: dp(16)
            y = lastBubbleY ?: dp(160)
        }
        ensureTrashTarget()
        attachDrag(
            view = bubble,
            params = params,
            onDragStart = { showTrashTarget() },
            onDrag = { dragParams, dragView -> updateTrashTargetState(dragParams, dragView) },
            onDragEnd = { dragParams, dragView ->
                val shouldDismiss = updateTrashTargetState(dragParams, dragView)
                if (shouldDismiss) {
                    animateBubbleDismiss(dragView)
                } else {
                    hideTrashTarget()
                }
            },
            onDragCancel = { hideTrashTarget() }
        ) { togglePanel() }
        windowManager.addView(bubble, params)
        bubbleView = bubble
        bubbleParams = params
        applyBubbleVoiceIndicator(lastVoiceState)
    }

    fun hide() {
        automationSuppressionDepth = 0
        restoreBubbleAfterAutomation = false
        rememberBubblePosition()
        stopBubblePulse()
        bubbleView?.let { windowManager.removeView(it) }
        removeTrashTarget()
        dismissPanel()
        dismissConfirmation()
        bubbleView = null
        bubbleParams = null
    }

    fun suppressAgentChromeForAutomation() {
        automationSuppressionDepth += 1
        if (automationSuppressionDepth > 1) {
            return
        }

        restoreBubbleAfterAutomation = bubbleView != null
        rememberBubblePosition()
        // Automation suppression only clears our chrome; it must not stop turns,
        // hang up voice, or dismiss the foreground service.
        dismissPanel(cancelTranscription = false)
        stopBubblePulse()
        bubbleView?.let {
            it.animate().cancel()
            it.animate().setListener(null)
            windowManager.removeView(it)
        }
        bubbleView = null
        bubbleParams = null
        removeTrashTarget()
    }

    fun restoreAgentChromeAfterAutomation() {
        if (automationSuppressionDepth == 0) {
            return
        }
        automationSuppressionDepth -= 1
        if (automationSuppressionDepth > 0) {
            return
        }

        val shouldRestoreBubble = restoreBubbleAfterAutomation
        restoreBubbleAfterAutomation = false
        if (shouldRestoreBubble) {
            show()
        }
    }

    fun setStatus(text: String) {
        statusText?.text = text
    }

    fun setVoiceState(state: VoiceRuntimeState) {
        lastVoiceState = state
        mainHandler.post { renderVoiceState(state) }
    }

    fun setTranscriptionState(state: VoiceTranscriptionState) {
        lastTranscriptionState = state
        mainHandler.post { renderTranscriptionState(state) }
    }

    fun insertComposerTranscript(transcript: String) {
        val normalized = transcript.trim()
        if (normalized.isBlank()) {
            setStatus("Transcription result was empty.")
            return
        }
        mainHandler.post {
            val input = composerInput
            if (input == null) {
                setStatus("Transcript ready. Open the bubble to review it.")
                return@post
            }
            val existing = input.text.toString()
            val separator = when {
                existing.isBlank() -> ""
                existing.endsWith("\n") -> ""
                else -> "\n"
            }
            val next = existing + separator + normalized
            input.setText(next)
            input.setSelection(next.length)
            setStatus("Transcript added to composer for review.")
        }
    }

    fun askConfirmation(message: String, preview: String?): CompletableDeferred<Boolean> {
        val deferred = CompletableDeferred<Boolean>()
        if (!Settings.canDrawOverlays(context)) {
            deferred.complete(false)
            return deferred
        }
        dismissConfirmation()

        val surface = themeColor(android.R.attr.colorBackground, 0xFFFFFFFF.toInt())
        val primaryText = themeColor(android.R.attr.textColorPrimary, 0xFF111111.toInt())
        val secondaryText = themeColor(android.R.attr.textColorSecondary, 0xFF666666.toInt())
        val accent = themeColor(android.R.attr.colorAccent, 0xFF5B63F6.toInt())

        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(16))
            background = roundedDrawable(surface, dp(24), 0x1F888888)
            elevation = dp(12).toFloat()
        }
        layout.addView(TextView(context).apply {
            text = "Are you sure?"
            textSize = 18f
            setTextColor(primaryText)
        })
        layout.addView(TextView(context).apply {
            text = listOfNotNull(message, preview).joinToString("\n\n")
            setTextColor(secondaryText)
            textSize = 14f
            setPadding(0, dp(8), 0, dp(14))
        })
        val buttons = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }
        buttons.addView(Button(context).apply {
            text = "✕"
            textSize = 20f
            background = roundedDrawable(0x00FFFFFF, dp(18), 0x33888888)
            setOnClickListener {
                dismissConfirmation()
                deferred.complete(false)
            }
        }, LinearLayout.LayoutParams(dp(56), dp(44)).apply { rightMargin = dp(10) })
        buttons.addView(Button(context).apply {
            text = "✓"
            textSize = 20f
            setTextColor(Color.WHITE)
            background = roundedDrawable(accent, dp(18))
            setOnClickListener {
                dismissConfirmation()
                deferred.complete(true)
            }
        }, LinearLayout.LayoutParams(dp(56), dp(44)))
        layout.addView(buttons)

        val scrim = View(context).apply {
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener {
                dismissConfirmation()
                deferred.complete(false)
            }
        }
        val scrimParams = overlayParams(
            width = WindowManager.LayoutParams.MATCH_PARENT,
            height = WindowManager.LayoutParams.MATCH_PARENT,
            focusable = false
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }
        windowManager.addView(scrim, scrimParams)
        confirmationScrimView = scrim

        val params = overlayParams(width = dp(320), height = WindowManager.LayoutParams.WRAP_CONTENT, focusable = false).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = dp(96)
        }
        windowManager.addView(layout, params)
        confirmationView = layout
        return deferred
    }

    private fun togglePanel() {
        if (panelView != null) {
            dismissPanel()
            return
        }

        val palette = overlayPalette()

        val input = EditText(context).apply {
            hint = "Ask Codex to control this phone"
            minLines = 1
            maxLines = 4
            textSize = 14f
            setTextColor(palette.primaryText)
            setHintTextColor(palette.secondaryText)
            background = controlDrawable(palette)
            backgroundTintList = null
            setPadding(dp(15), dp(11), dp(15), dp(11))
        }
        composerInput = input
        transcriptionMicButton = ImageButton(context).apply {
            setImageResource(R.drawable.ic_mic)
            background = accentDrawable(palette, dp(18))
            backgroundTintList = null
            contentDescription = "Start voice transcription"
            setColorFilter(Color.WHITE)
            setPadding(dp(10), dp(10), dp(10), dp(10))
            setOnClickListener {
                if (lastTranscriptionState.isRecording) {
                    onStopTranscription()
                } else {
                    onStartTranscription()
                }
            }
        }
        transcriptionCancelButton = Button(context).apply {
            text = "Cancel"
            textSize = 11f
            isAllCaps = false
            visibility = View.GONE
            setTextColor(palette.primaryText)
            background = controlDrawable(palette)
            backgroundTintList = null
            setOnClickListener { onCancelTranscription() }
        }
        val composerRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(input, LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            ).apply { rightMargin = dp(8) })
            addView(transcriptionMicButton, LinearLayout.LayoutParams(dp(44), dp(44)))
            addView(transcriptionCancelButton, LinearLayout.LayoutParams(dp(72), dp(44)).apply { leftMargin = dp(8) })
        }
        statusText = TextView(context).apply {
            text = "Connected. Ready for a new request."
            textSize = 12f
            setTextColor(palette.secondaryText)
            setPadding(0, dp(10), 0, 0)
        }
        val actionRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }
        actionRow.addView(Button(context).apply {
            text = "Stop"
            isAllCaps = false
            setTextColor(palette.primaryText)
            background = secondaryButtonDrawable(palette)
            backgroundTintList = null
            setOnClickListener {
                onStop()
                setStatus("Stop requested")
            }
        }, LinearLayout.LayoutParams(0, dp(44), 1f).apply { rightMargin = dp(10) })
        actionRow.addView(Button(context).apply {
            text = "Send"
            isAllCaps = false
            setTextColor(Color.WHITE)
            background = accentDrawable(palette, dp(18))
            backgroundTintList = null
            setOnClickListener {
                val text = input.text.toString().trim()
                if (text.isNotEmpty()) {
                    onSubmit(text)
                    input.setText("")
                    setStatus("Sent to PC bridge")
                    dismissPanel()
                }
            }
        }, LinearLayout.LayoutParams(0, dp(44), 1f))

        val title = TextView(context).apply {
            text = "Android Agent"
            textSize = 17f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(palette.primaryText)
            gravity = Gravity.CENTER_VERTICAL
        }
        val settingsButton = ImageButton(context).apply {
            setImageResource(R.drawable.ic_settings_gear)
            background = controlDrawable(palette, dp(14))
            backgroundTintList = null
            contentDescription = "Open Android Agent settings"
            setColorFilter(palette.primaryText)
            setPadding(dp(8), dp(8), dp(8), dp(8))
            setOnClickListener {
                dismissPanel()
                openSettings()
            }
        }
        val micButton = Button(context).apply {
            text = "🗣️"
            textSize = 18f
            isAllCaps = false
            setTextColor(Color.WHITE)
            background = accentDrawable(palette, dp(14))
            backgroundTintList = null
            contentDescription = "Start realtime voice mode"
            setOnClickListener {
                onStartVoice()
                dismissPanel()
            }
        }
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(10))
            addView(title, LinearLayout.LayoutParams(0, dp(40), 1f))
            addView(micButton, LinearLayout.LayoutParams(dp(56), dp(36)).apply { rightMargin = dp(8) })
            addView(settingsButton, LinearLayout.LayoutParams(dp(36), dp(36)))
        }
        val voice = buildVoiceSurface(palette)
        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(16))
            background = recessedPanelDrawable(palette)
            elevation = dp(18).toFloat()
            addView(header)
            addView(voice)
            addView(composerRow, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ))
            addView(actionRow, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(12) })
            addView(statusText)
        }

        val params = overlayParams(width = dp(320), height = WindowManager.LayoutParams.WRAP_CONTENT, focusable = true).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(16)
            y = dp(224)
        }
        input.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                mainHandler.postDelayed({ keepAboveKeyboard(panel, params) }, 300)
                mainHandler.postDelayed({ keepAboveKeyboard(panel, params) }, 700)
            }
        }
        val scrim = View(context).apply {
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener { dismissPanel() }
        }
        val scrimParams = overlayParams(
            width = WindowManager.LayoutParams.MATCH_PARENT,
            height = WindowManager.LayoutParams.MATCH_PARENT,
            focusable = false
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }
        windowManager.addView(scrim, scrimParams)
        panelScrimView = scrim

        attachDrag(header, params, windowView = panel, keepAboveKeyboard = true) {}
        windowManager.addView(panel, params)
        panel.post { keepInsideScreen(panel, params) }
        panelView = panel
        renderVoiceState(lastVoiceState)
        renderTranscriptionState(lastTranscriptionState)
    }

    private fun dismissPanel(cancelTranscription: Boolean = true) {
        if (cancelTranscription && lastTranscriptionState.isRecording) {
            onCancelTranscription()
        }
        panelView?.let { windowManager.removeView(it) }
        panelScrimView?.let { windowManager.removeView(it) }
        panelView = null
        panelScrimView = null
        composerInput = null
        transcriptionMicButton = null
        transcriptionCancelButton = null
        voiceSurface = null
        voiceStatusText = null
        voiceTranscriptText = null
        voiceTaskText = null
        voiceResultText = null
        voiceMuteButton = null
        voiceHangupButton = null
    }

    private fun buildVoiceSurface(palette: OverlayPalette): LinearLayout {
        voiceStatusText = TextView(context).apply {
            textSize = 14f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(palette.primaryText)
        }
        voiceTranscriptText = TextView(context).apply {
            textSize = 13f
            setTextColor(palette.secondaryText)
            setPadding(0, dp(8), 0, dp(10))
            maxHeight = maxTranscriptHeight()
            movementMethod = ScrollingMovementMethod()
            isVerticalScrollBarEnabled = true
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
        }
        voiceTaskText = TextView(context).apply {
            textSize = 12f
            setTextColor(palette.secondaryText)
            setPadding(0, 0, 0, dp(8))
        }
        voiceResultText = TextView(context).apply {
            textSize = 12f
            setTextColor(palette.secondaryText)
            setPadding(0, 0, 0, dp(10))
        }
        voiceMuteButton = Button(context).apply {
            text = "Mute"
            isAllCaps = false
            setTextColor(palette.primaryText)
            background = secondaryButtonDrawable(palette)
            backgroundTintList = null
            setOnClickListener { onToggleVoiceMute() }
        }
        voiceHangupButton = Button(context).apply {
            text = "Hang up"
            isAllCaps = false
            setTextColor(Color.WHITE)
            background = accentDrawable(palette, dp(18))
            backgroundTintList = null
            setOnClickListener { onStopVoice() }
        }
        val voiceActions = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            addView(voiceMuteButton, LinearLayout.LayoutParams(0, dp(40), 1f).apply { rightMargin = dp(8) })
            addView(voiceHangupButton, LinearLayout.LayoutParams(0, dp(40), 1f))
        }
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(dp(14), dp(14), dp(14), dp(14))
            background = recessedInsetDrawable(palette, dp(20))
            addView(voiceStatusText)
            addView(voiceTranscriptText)
            addView(voiceTaskText)
            addView(voiceResultText)
            addView(voiceActions)
            voiceSurface = this
        }
    }

    private fun showVoiceSurface() {
        if (panelView == null) {
            togglePanel()
        }
        voiceSurface?.visibility = View.VISIBLE
    }

    private fun renderVoiceState(state: VoiceRuntimeState) {
        applyBubbleVoiceIndicator(state)
        val shouldShow = state.isActive || state.status == VoiceRuntimeStatus.ERROR || state.transcript.isNotBlank()
        voiceSurface?.visibility = if (shouldShow) View.VISIBLE else View.GONE
        voiceStatusText?.text = buildString {
            append("Voice: ")
            append(state.status.label)
            state.error?.takeIf { it.isNotBlank() }?.let { append(" - ").append(it) }
        }
        voiceTranscriptText?.text = state.transcript.ifBlank { "Voice transcript will appear here." }
        voiceTranscriptText?.post {
            voiceTranscriptText?.let { textView ->
                val scrollAmount = textView.layout?.let { layout ->
                    layout.getLineTop(textView.lineCount) - textView.height + textView.compoundPaddingBottom + textView.compoundPaddingTop
                } ?: 0
                if (scrollAmount > 0) {
                    textView.scrollTo(0, scrollAmount)
                }
            }
        }
        voiceTaskText?.text = buildString {
            val task = state.currentPhoneTask
            if (state.isPhoneTaskRunning && !task.isNullOrBlank()) {
                append("Task: ").append(task)
            } else if (state.queuedPhoneTasks > 0) {
                append("Tasks queued.")
            } else {
                append("No phone task running.")
            }
            if (state.queuedPhoneTasks > 0) {
                append(" Queued: ").append(state.queuedPhoneTasks)
            }
        }
        voiceResultText?.text = state.latestTaskResult ?: "Latest task result will appear here."
        voiceMuteButton?.text = if (state.isMuted) "Unmute" else "Mute"
        voiceMuteButton?.isEnabled = state.isActive
        voiceHangupButton?.isEnabled = state.status != VoiceRuntimeStatus.IDLE
    }

    private fun renderTranscriptionState(state: VoiceTranscriptionState) {
        val palette = overlayPalette()
        val disabledBackground = controlDrawable(palette)

        transcriptionMicButton?.apply {
            isEnabled = !state.isTranscribing
            contentDescription = when {
                state.isRecording -> "Stop recording and transcribe"
                state.isTranscribing -> "Transcribing audio"
                else -> "Start voice transcription"
            }
            background = if (state.isTranscribing) disabledBackground else accentDrawable(palette, dp(18))
            setColorFilter(Color.WHITE)
        }
        transcriptionCancelButton?.visibility = if (state.isRecording) View.VISIBLE else View.GONE

        when {
            state.error != null -> setStatus("Transcription error: ${state.error}")
            state.isTranscribing -> setStatus("Transcribing audio...")
            state.isRecording -> {
                val levelPercent = (state.audioLevel * 100f).roundToInt().coerceIn(0, 100)
                setStatus("Recording for transcription. Level $levelPercent%. Tap mic to stop, or Cancel to discard.")
            }
        }
    }

    private fun applyBubbleVoiceIndicator(state: VoiceRuntimeState) {
        val bubble = bubbleView ?: return
        bubble.background = bubbleBackgroundForVoiceState(state)
        bubble.elevation = if (state.status == VoiceRuntimeStatus.IDLE) dp(8).toFloat() else dp(30).toFloat()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val shadowColor = when (state.status) {
                VoiceRuntimeStatus.CONNECTING,
                VoiceRuntimeStatus.ERROR -> VOICE_GLOW_RED
                VoiceRuntimeStatus.LISTENING,
                VoiceRuntimeStatus.THINKING,
                VoiceRuntimeStatus.SPEAKING -> VOICE_GLOW_GREEN
                VoiceRuntimeStatus.IDLE -> Color.TRANSPARENT
            }
            bubble.outlineAmbientShadowColor = shadowColor
            bubble.outlineSpotShadowColor = shadowColor
        }
        updateBubblePulse(isSpeaking = state.status == VoiceRuntimeStatus.SPEAKING)
    }

    private fun bubbleBackgroundForVoiceState(state: VoiceRuntimeState): GradientDrawable {
        return when (state.status) {
            VoiceRuntimeStatus.CONNECTING,
            VoiceRuntimeStatus.ERROR -> radialGlow(VOICE_GLOW_RED_CENTER, VOICE_GLOW_RED_MID)
            VoiceRuntimeStatus.LISTENING,
            VoiceRuntimeStatus.THINKING,
            VoiceRuntimeStatus.SPEAKING -> radialGlow(VOICE_GLOW_GREEN_CENTER, VOICE_GLOW_GREEN_MID)
            VoiceRuntimeStatus.IDLE -> roundedDrawable(Color.TRANSPARENT, dp(18))
        }
    }

    private fun radialGlow(centerColor: Int, midColor: Int): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            gradientType = GradientDrawable.RADIAL_GRADIENT
            setGradientCenter(0.5f, 0.5f)
            setGradientRadius(dp(48).toFloat())
            colors = intArrayOf(centerColor, midColor, Color.TRANSPARENT)
        }
    }

    private fun updateBubblePulse(isSpeaking: Boolean) {
        val bubble = bubbleView
        if (!isSpeaking || bubble == null) {
            stopBubblePulse()
            bubble?.scaleX = 1f
            bubble?.scaleY = 1f
            return
        }
        if (bubblePulseAnimator?.isStarted == true) {
            return
        }
        val scaleX = ObjectAnimator.ofFloat(bubble, View.SCALE_X, 1f, 1.08f).apply {
            duration = VOICE_PULSE_MS
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = trashShowInterpolator
        }
        val scaleY = ObjectAnimator.ofFloat(bubble, View.SCALE_Y, 1f, 1.08f).apply {
            duration = VOICE_PULSE_MS
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = trashShowInterpolator
        }
        bubblePulseAnimator = AnimatorSet().apply {
            playTogether(scaleX, scaleY)
            start()
        }
    }

    private fun stopBubblePulse() {
        bubblePulseAnimator?.cancel()
        bubblePulseAnimator = null
    }

    private fun dismissConfirmation() {
        confirmationView?.let { windowManager.removeView(it) }
        confirmationScrimView?.let { windowManager.removeView(it) }
        confirmationView = null
        confirmationScrimView = null
    }

    private fun ensureTrashTarget() {
        if (trashTargetView != null) {
            return
        }
        val size = trashTargetSize()
        val target = ImageView(context).apply {
            setImageResource(android.R.drawable.ic_menu_delete)
            setColorFilter(Color.WHITE)
            background = trashTargetBackground(isActive = false)
            contentDescription = "Close Android Agent bubble"
            elevation = dp(12).toFloat()
            setPadding(dp(16), dp(16), dp(16), dp(16))
            alpha = 0f
            scaleX = TRASH_TARGET_HIDDEN_SCALE
            scaleY = TRASH_TARGET_HIDDEN_SCALE
            visibility = View.INVISIBLE
        }
        val params = overlayParams(width = size, height = size, focusable = false).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = dp(28)
            flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
        windowManager.addView(target, params)
        trashTargetView = target
        updateTrashTargetBounds()
    }

    private fun showTrashTarget() {
        ensureTrashTarget()
        trashTargetView?.apply {
            animate().cancel()
            animate().setListener(null)
            background = trashTargetBackground(isActive = false)
            alpha = 0f
            scaleX = TRASH_TARGET_HIDDEN_SCALE
            scaleY = TRASH_TARGET_HIDDEN_SCALE
            visibility = View.VISIBLE
            animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(TRASH_TARGET_SHOW_MS)
                .setInterpolator(trashShowInterpolator)
                .start()
            post { updateTrashTargetBounds() }
        }
        isBubbleOverTrashTarget = false
    }

    private fun hideTrashTarget() {
        trashTargetView?.apply {
            animate().cancel()
            background = trashTargetBackground(isActive = false)
            animate()
                .alpha(0f)
                .scaleX(TRASH_TARGET_HIDDEN_SCALE)
                .scaleY(TRASH_TARGET_HIDDEN_SCALE)
                .setDuration(TRASH_TARGET_HIDE_MS)
                .setInterpolator(trashHideInterpolator)
                .setListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        visibility = View.INVISIBLE
                        animate().setListener(null)
                    }
                })
                .start()
        }
        isBubbleOverTrashTarget = false
    }

    private fun removeTrashTarget() {
        trashTargetView?.let {
            it.animate().cancel()
            it.animate().setListener(null)
            windowManager.removeView(it)
        }
        trashTargetView = null
        trashTargetBounds = Rect()
        isBubbleOverTrashTarget = false
        isDismissAnimating = false
    }

    private fun updateTrashTargetState(params: WindowManager.LayoutParams, view: View): Boolean {
        updateTrashTargetBounds()
        val location = IntArray(2)
        view.getLocationOnScreen(location)
        val centerX = location[0] + view.width / 2
        val centerY = location[1] + view.height / 2
        val dx = centerX - trashTargetBounds.centerX()
        val dy = centerY - trashTargetBounds.centerY()
        val radius = trashTargetBounds.width() / 2
        val isOverTarget = dx * dx + dy * dy <= radius * radius
        if (isBubbleOverTrashTarget != isOverTarget) {
            isBubbleOverTrashTarget = isOverTarget
            trashTargetView?.background = trashTargetBackground(isActive = isOverTarget)
        }
        return isOverTarget
    }

    private fun animateBubbleDismiss(bubble: View) {
        if (isDismissAnimating) {
            return
        }
        stopBubblePulse()
        val target = trashTargetView
        isDismissAnimating = true
        listOfNotNull(bubble, target).forEach { view ->
            view.animate().cancel()
            view.animate().setListener(null)
            view.visibility = View.VISIBLE
            view.alpha = 1f
            view.scaleX = 1f
            view.scaleY = 1f
        }
        target?.background = trashTargetBackground(isActive = true)

        val animators = mutableListOf<Animator>()
        animators.add(dismissAnimatorFor(bubble))
        target?.let { animators.add(dismissAnimatorFor(it)) }

        AnimatorSet().apply {
            playTogether(animators)
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    onDismiss()
                }

                override fun onAnimationCancel(animation: Animator) {
                    isDismissAnimating = false
                }
            })
            start()
        }
    }

    private fun dismissAnimatorFor(view: View): AnimatorSet {
        return AnimatorSet().apply {
            playSequentially(
                scaleAnimator(view, 1.12f, TRASH_TARGET_PULSE_MS),
                scaleAnimator(view, 0.96f, TRASH_TARGET_PULSE_MS),
                shrinkAnimator(view)
            )
        }
    }

    private fun scaleAnimator(view: View, scale: Float, durationMs: Long): ObjectAnimator {
        return ObjectAnimator.ofPropertyValuesHolder(
            view,
            PropertyValuesHolder.ofFloat(View.SCALE_X, scale),
            PropertyValuesHolder.ofFloat(View.SCALE_Y, scale)
        ).apply {
            duration = durationMs
            interpolator = trashShowInterpolator
        }
    }

    private fun shrinkAnimator(view: View): ObjectAnimator {
        return ObjectAnimator.ofPropertyValuesHolder(
            view,
            PropertyValuesHolder.ofFloat(View.SCALE_X, 0f),
            PropertyValuesHolder.ofFloat(View.SCALE_Y, 0f),
            PropertyValuesHolder.ofFloat(View.ALPHA, 0f)
        ).apply {
            duration = TRASH_TARGET_SHRINK_MS
            interpolator = trashHideInterpolator
        }
    }

    private fun updateTrashTargetBounds() {
        val target = trashTargetView ?: return
        val size = trashTargetSize()
        val location = IntArray(2)
        target.getLocationOnScreen(location)
        if (location[0] == 0 && location[1] == 0) {
            val display = context.resources.displayMetrics
            val bottom = display.heightPixels - dp(28)
            val left = (display.widthPixels - size) / 2
            trashTargetBounds.set(left, bottom - size, left + size, bottom)
            return
        }
        val width = target.width.takeIf { it > 0 } ?: size
        val height = target.height.takeIf { it > 0 } ?: size
        trashTargetBounds.set(location[0], location[1], location[0] + width, location[1] + height)
    }

    private fun trashTargetBackground(isActive: Boolean): GradientDrawable {
        return roundedDrawable(
            if (isActive) 0xFFE53935.toInt() else 0xCC333333.toInt(),
            trashTargetSize() / 2
        )
    }

    private fun trashTargetSize(): Int = dp(64)

    private fun maxTranscriptHeight(): Int {
        val modalBudget = (context.resources.displayMetrics.heightPixels * VOICE_MODAL_MAX_SCREEN_FRACTION).toInt()
        return (modalBudget - dp(220)).coerceAtLeast(dp(96))
    }

    private fun rememberBubblePosition() {
        bubbleParams?.let {
            lastBubbleX = it.x
            lastBubbleY = it.y
        }
    }

    companion object {
        private const val TRASH_TARGET_SHOW_MS = 140L
        private const val TRASH_TARGET_HIDE_MS = 110L
        private const val TRASH_TARGET_PULSE_MS = 55L
        private const val TRASH_TARGET_SHRINK_MS = 140L
        private const val TRASH_TARGET_HIDDEN_SCALE = 0.82f
        private const val VOICE_PULSE_MS = 720L
        private const val VOICE_MODAL_MAX_SCREEN_FRACTION = 0.40f
        private const val VOICE_GLOW_RED = 0xFFE53935.toInt()
        private const val VOICE_GLOW_RED_CENTER = 0xF2FF453A.toInt()
        private const val VOICE_GLOW_RED_MID = 0xB3FF453A.toInt()
        private const val VOICE_GLOW_GREEN = 0xFF30D96B.toInt()
        private const val VOICE_GLOW_GREEN_CENTER = 0xF230D96B.toInt()
        private const val VOICE_GLOW_GREEN_MID = 0xB330D96B.toInt()
    }

    private fun openSettings() {
        context.startActivity(
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        )
    }

    private fun overlayParams(width: Int, height: Int, focusable: Boolean): WindowManager.LayoutParams {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        return WindowManager.LayoutParams(
            width,
            height,
            type,
            if (focusable) WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL else WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }
    }

    private fun attachDrag(
        view: View,
        params: WindowManager.LayoutParams,
        windowView: View = view,
        keepAboveKeyboard: Boolean = false,
        onDragStart: () -> Unit = {},
        onDrag: (WindowManager.LayoutParams, View) -> Unit = { _, _ -> },
        onDragEnd: (WindowManager.LayoutParams, View) -> Unit = { _, _ -> },
        onDragCancel: () -> Unit = {},
        onClick: () -> Unit
    ) {
        var startX = 0
        var startY = 0
        var touchX = 0f
        var touchY = 0f
        var moved = false
        var downTime = 0L
        val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
        view.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x
                    startY = params.y
                    touchX = event.rawX
                    touchY = event.rawY
                    downTime = event.eventTime
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - touchX
                    val dy = event.rawY - touchY
                    if (!moved && (kotlin.math.abs(dx) > touchSlop || kotlin.math.abs(dy) > touchSlop)) {
                        moved = true
                        onDragStart()
                    }
                    if (moved) {
                        params.x = startX + dx.toInt()
                        params.y = startY + dy.toInt()
                        keepInsideScreen(windowView, params)
                        if (keepAboveKeyboard) {
                            keepAboveKeyboard(windowView, params)
                        }
                        windowManager.updateViewLayout(windowView, params)
                        onDrag(params, windowView)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val isClick = !moved && event.eventTime - downTime < 250
                    if (isClick) {
                        onClick()
                    } else {
                        onDragEnd(params, windowView)
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    if (moved) {
                        onDragCancel()
                    }
                    moved = false
                    true
                }
                else -> true
            }
        }
    }

    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).toInt()

    private fun keepInsideScreen(view: View, params: WindowManager.LayoutParams) {
        val display = context.resources.displayMetrics
        val maxX = (display.widthPixels - view.width - dp(8)).coerceAtLeast(dp(8))
        val maxY = (display.heightPixels - view.height - dp(8)).coerceAtLeast(dp(8))
        params.x = params.x.coerceIn(dp(8), maxX)
        params.y = params.y.coerceIn(dp(8), maxY)
    }

    private fun keepAboveKeyboard(view: View, params: WindowManager.LayoutParams) {
        val visible = Rect()
        view.getWindowVisibleDisplayFrame(visible)
        val displayHeight = context.resources.displayMetrics.heightPixels
        val keyboardTop = if (visible.bottom > 0 && visible.bottom < displayHeight) {
            visible.bottom
        } else {
            displayHeight - dp(360)
        }
        val maxY = (keyboardTop - view.height - dp(16)).coerceAtLeast(dp(16))
        if (params.y > maxY) {
            params.y = maxY
            windowManager.updateViewLayout(view, params)
        }
    }

    private fun overlayPalette(): OverlayPalette {
        val isDark = isNightMode()
        val primaryText = if (isDark) {
            0xFFF8FAFC.toInt()
        } else {
            themeColor(android.R.attr.textColorPrimary, 0xFF111827.toInt())
        }
        val secondaryText = if (isDark) {
            0xFFB7C0CF.toInt()
        } else {
            themeColor(android.R.attr.textColorSecondary, 0xFF64748B.toInt())
        }
        val accent = if (isDark) {
            0xFF7C9CFF.toInt()
        } else {
            themeColor(android.R.attr.colorAccent, 0xFF245BFF.toInt())
        }
        val surface = if (isDark) {
            0xFF171C26.toInt()
        } else {
            0xFFEEF2F8.toInt()
        }
        return OverlayPalette(
            surface = surface,
            recessedSurface = if (isDark) 0xFF0B1018.toInt() else 0xFFD8DEE9.toInt(),
            controlSurface = if (isDark) 0xFF252D3A.toInt() else 0xFFF8FAFD.toInt(),
            primaryText = primaryText,
            secondaryText = secondaryText,
            border = if (isDark) 0xFF5D6A7D.toInt() else 0xFFCBD5E1.toInt(),
            highlight = if (isDark) 0xFF30394A.toInt() else Color.WHITE,
            accent = accent
        )
    }

    private fun recessedPanelDrawable(palette: OverlayPalette): LayerDrawable {
        return LayerDrawable(
            arrayOf(
                roundedDrawable(palette.recessedSurface, dp(28)),
                roundedDrawable(palette.surface, dp(26), palette.highlight),
                roundedDrawable(Color.TRANSPARENT, dp(26), palette.border)
            )
        ).apply {
            setLayerInset(1, dp(2), dp(2), dp(2), dp(3))
            setLayerInset(2, dp(2), dp(2), dp(2), dp(3))
        }
    }

    private fun recessedInsetDrawable(palette: OverlayPalette, radius: Int): LayerDrawable {
        return LayerDrawable(
            arrayOf(
                roundedDrawable(palette.recessedSurface, radius, palette.border),
                roundedDrawable(blend(palette.recessedSurface, palette.surface, 0.42f), radius - dp(2))
            )
        ).apply {
            setLayerInset(1, dp(1), dp(1), dp(1), dp(1))
        }
    }

    private fun controlDrawable(palette: OverlayPalette, radius: Int = dp(18)): GradientDrawable {
        return roundedDrawable(palette.controlSurface, radius, palette.border)
    }

    private fun secondaryButtonDrawable(palette: OverlayPalette, radius: Int = dp(18)): GradientDrawable {
        return roundedDrawable(palette.controlSurface, radius, palette.border, dp(2))
    }

    private fun accentDrawable(palette: OverlayPalette, radius: Int): GradientDrawable {
        return roundedDrawable(palette.accent, radius, withAlpha(Color.WHITE, 0x33))
    }

    private fun roundedDrawable(color: Int, radius: Int, strokeColor: Int? = null, strokeWidth: Int = 1): GradientDrawable {
        return GradientDrawable().apply {
            setColor(color)
            cornerRadius = radius.toFloat()
            strokeColor?.let { setStroke(strokeWidth, it) }
        }
    }

    private fun themeColor(attr: Int, fallback: Int): Int {
        val typedValue = TypedValue()
        return if (context.theme.resolveAttribute(attr, typedValue, true)) {
            if (typedValue.resourceId != 0) {
                context.getColor(typedValue.resourceId)
            } else {
                typedValue.data
            }
        } else {
            fallback
        }
    }

    private fun isNightMode(): Boolean {
        val configurationNightMode = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val systemNightMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            (context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager)?.nightMode == UiModeManager.MODE_NIGHT_YES
        } else {
            false
        }
        return configurationNightMode || systemNightMode
    }

    private fun withAlpha(color: Int, alpha: Int): Int {
        return (color and 0x00FFFFFF) or (alpha.coerceIn(0, 255) shl 24)
    }

    private fun blend(start: Int, end: Int, fraction: Float): Int {
        val amount = fraction.coerceIn(0f, 1f)
        val inverse = 1f - amount
        return Color.argb(
            (Color.alpha(start) * inverse + Color.alpha(end) * amount).toInt(),
            (Color.red(start) * inverse + Color.red(end) * amount).toInt(),
            (Color.green(start) * inverse + Color.green(end) * amount).toInt(),
            (Color.blue(start) * inverse + Color.blue(end) * amount).toInt()
        )
    }
}
