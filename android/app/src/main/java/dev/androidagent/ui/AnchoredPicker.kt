package dev.androidagent.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Rect
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import dev.androidagent.R
import dev.androidagent.ui.DesignTokens.dp
import dev.androidagent.ui.DesignTokens.withAlpha

/**
 * In-modal anchored dropdown.
 *
 * Lives as a child of the modal's [FrameLayout] host so it works inside
 * `TYPE_APPLICATION_OVERLAY` windows (no cross-window popup quirks).
 *
 * Use [show] to present a picker anchored under a pill. The picker:
 * - auto-flips above the anchor when there isn't enough room below
 * - dismisses on outside tap, back press from the host's key listener,
 *   or explicit [dismiss]
 * - supports a title, multiple sections with overline headers, optional
 *   leading icons, a trailing check icon for the active row, and a
 *   destructive row tone
 */
class AnchoredPicker(
    private val context: Context,
    private val tokens: ThemeTokens
) {

    data class Row(
        val label: String,
        val sublabel: String? = null,
        val iconRes: Int? = null,
        val selected: Boolean = false,
        val destructive: Boolean = false,
        val enabled: Boolean = true,
        val onSelect: () -> Unit
    )

    data class Section(val title: String? = null, val rows: List<Row>)

    private var scrimView: View? = null
    private var sheetView: View? = null
    private var hostRef: FrameLayout? = null
    private var preDrawListener: ViewTreeObserver.OnPreDrawListener? = null
    private var onDismissCallback: (() -> Unit)? = null
    private var currentAnchor: View? = null

    val isShowing: Boolean
        get() = sheetView != null

    fun isShowingFor(anchor: View): Boolean = sheetView != null && currentAnchor === anchor

    fun show(
        host: FrameLayout,
        anchor: View,
        title: String? = null,
        sections: List<Section>,
        onDismiss: (() -> Unit)? = null
    ) {
        dismiss()
        hostRef = host
        currentAnchor = anchor
        onDismissCallback = onDismiss

        val scrim = View(context).apply {
            setBackgroundColor(Color.TRANSPARENT)
            isClickable = true
            isFocusable = false
            setOnClickListener { dismiss() }
        }
        host.addView(
            scrim,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        scrimView = scrim

        val sheet = buildSheet(title, sections)
        val sheetParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            val sideMargin = dp(context, DesignTokens.Spacing.md)
            leftMargin = sideMargin
            rightMargin = sideMargin
            topMargin = sideMargin
        }
        host.addView(sheet, sheetParams)
        sheetView = sheet

        sheet.alpha = 0f
        sheet.scaleX = 0.96f
        sheet.scaleY = 0.96f

        val observer = sheet.viewTreeObserver
        val preDraw = ViewTreeObserver.OnPreDrawListener {
            positionSheet(host, sheet, anchor)
            sheet.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(140L)
                .start()
            sheet.viewTreeObserver.removeOnPreDrawListener(preDrawListener)
            preDrawListener = null
            true
        }
        preDrawListener = preDraw
        observer.addOnPreDrawListener(preDraw)
    }

    fun dismiss() {
        val host = hostRef
        val sheet = sheetView
        val scrim = scrimView
        val callback = onDismissCallback
        hostRef = null
        sheetView = null
        scrimView = null
        currentAnchor = null
        onDismissCallback = null
        preDrawListener?.let {
            sheet?.viewTreeObserver?.removeOnPreDrawListener(it)
        }
        preDrawListener = null
        if (sheet != null) {
            sheet.animate()
                .alpha(0f)
                .scaleX(0.96f)
                .scaleY(0.96f)
                .setDuration(110L)
                .withEndAction {
                    (sheet.parent as? ViewGroup)?.removeView(sheet)
                }
                .start()
        }
        scrim?.let { host?.removeView(it) }
        callback?.invoke()
    }

    private fun positionSheet(host: FrameLayout, sheet: View, anchor: View) {
        val hostLocation = IntArray(2)
        host.getLocationOnScreen(hostLocation)
        val anchorLocation = IntArray(2)
        anchor.getLocationOnScreen(anchorLocation)

        val anchorTopInHost = anchorLocation[1] - hostLocation[1]
        val anchorLeftInHost = anchorLocation[0] - hostLocation[0]
        val anchorBottomInHost = anchorTopInHost + anchor.height
        val anchorCenterX = anchorLeftInHost + anchor.width / 2

        val params = sheet.layoutParams as FrameLayout.LayoutParams
        val sideMargin = dp(context, DesignTokens.Spacing.md)
        val gap = dp(context, DesignTokens.Spacing.sm)
        val hostHeight = host.height
        val hostWidth = host.width
        val sheetHeight = sheet.measuredHeight.coerceAtLeast(dp(context, 80))
        val sheetWidth = sheet.measuredWidth.coerceAtLeast(dp(context, 200))

        val spaceBelow = hostHeight - anchorBottomInHost
        val spaceAbove = anchorTopInHost
        val placeAbove = spaceBelow < sheetHeight + gap + sideMargin && spaceAbove > spaceBelow

        val targetTop = if (placeAbove) {
            (anchorTopInHost - sheetHeight - gap).coerceAtLeast(sideMargin)
        } else {
            (anchorBottomInHost + gap)
                .coerceAtMost(hostHeight - sheetHeight - sideMargin)
                .coerceAtLeast(sideMargin)
        }
        val rawLeft = anchorCenterX - sheetWidth / 2
        val targetLeft = rawLeft
            .coerceAtLeast(sideMargin)
            .coerceAtMost(hostWidth - sheetWidth - sideMargin)
            .coerceAtLeast(sideMargin)

        params.leftMargin = targetLeft
        params.topMargin = targetTop
        params.rightMargin = 0
        sheet.layoutParams = params
        sheet.pivotX = (anchorCenterX - targetLeft).toFloat().coerceIn(0f, sheetWidth.toFloat())
        sheet.pivotY = if (placeAbove) sheetHeight.toFloat() else 0f
    }

    private fun buildSheet(title: String?, sections: List<Section>): View {
        val padOuter = dp(context, DesignTokens.Spacing.sm)
        val padTop = dp(context, DesignTokens.Spacing.sm)
        val padBottom = dp(context, DesignTokens.Spacing.sm)
        val display = context.resources.displayMetrics
        val maxWidth = (display.widthPixels - dp(context, DesignTokens.Spacing.lg * 2))
            .coerceAtMost(dp(context, 360))
        val maxHeight = (display.heightPixels * 0.55f).toInt()

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = Drawables.dropdownSheet(context, tokens)
            elevation = dp(context, DesignTokens.Elevation.popover).toFloat()
            setPadding(padOuter, padTop, padOuter, padBottom)
            clipToPadding = false
            minimumWidth = dp(context, 200)
            maxWidth.let { mw -> layoutParams = ViewGroup.LayoutParams(mw, ViewGroup.LayoutParams.WRAP_CONTENT) }
        }

        if (!title.isNullOrBlank()) {
            container.addView(TextView(context).apply {
                text = title
                Typography.applyOverline(this, tokens)
                setPadding(dp(context, DesignTokens.Spacing.sm), dp(context, DesignTokens.Spacing.xs), dp(context, DesignTokens.Spacing.sm), dp(context, DesignTokens.Spacing.sm))
            })
        }

        val scroller = ScrollView(context).apply {
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            isVerticalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { maxHeight.let { /* hint */ } }
        }

        val body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        scroller.addView(body, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        sections.forEachIndexed { sectionIndex, section ->
            if (sectionIndex > 0) {
                body.addView(divider(), LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(context, 1).coerceAtLeast(1)
                ).apply {
                    topMargin = dp(context, DesignTokens.Spacing.xs)
                    bottomMargin = dp(context, DesignTokens.Spacing.xs)
                })
            }
            section.title?.takeIf { it.isNotBlank() }?.let { headerText ->
                body.addView(TextView(context).apply {
                    text = headerText
                    Typography.applyOverline(this, tokens)
                    setPadding(dp(context, DesignTokens.Spacing.sm), dp(context, DesignTokens.Spacing.xs), dp(context, DesignTokens.Spacing.sm), dp(context, DesignTokens.Spacing.xs))
                })
            }
            section.rows.forEach { row ->
                body.addView(buildRow(row))
            }
        }

        container.addView(scroller)
        container.maxHeight(maxHeight)
        return container
    }

    private fun View.maxHeight(maxPx: Int) {
        viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                viewTreeObserver.removeOnPreDrawListener(this)
                if (measuredHeight > maxPx) {
                    val lp = layoutParams
                    lp.height = maxPx
                    layoutParams = lp
                }
                return true
            }
        })
    }

    private fun buildRow(row: Row): View {
        val rowView = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = Drawables.dropdownRowBackground(context, tokens)
            isClickable = row.enabled
            isFocusable = row.enabled
            setPadding(
                dp(context, DesignTokens.Spacing.md),
                dp(context, DesignTokens.Spacing.sm),
                dp(context, DesignTokens.Spacing.md),
                dp(context, DesignTokens.Spacing.sm)
            )
            alpha = if (row.enabled) 1f else 0.45f
            minimumHeight = dp(context, DesignTokens.Sizes.pickerRow)
            if (row.enabled) {
                setOnClickListener {
                    row.onSelect()
                    dismiss()
                }
            }
        }

        row.iconRes?.let { iconRes ->
            rowView.addView(ImageView(context).apply {
                setImageResource(iconRes)
                setColorFilter(if (row.destructive) tokens.danger else tokens.secondaryText)
                scaleType = ImageView.ScaleType.CENTER_INSIDE
            }, LinearLayout.LayoutParams(dp(context, 20), dp(context, 20)).apply {
                rightMargin = dp(context, DesignTokens.Spacing.md)
            })
        }

        val labels = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
        }
        labels.addView(TextView(context).apply {
            text = row.label
            Typography.applyCallout(this, tokens)
            if (row.destructive) setTextColor(tokens.danger)
            if (row.selected) setTextColor(tokens.accent)
            isSingleLine = true
        })
        row.sublabel?.takeIf { it.isNotBlank() }?.let { sub ->
            labels.addView(TextView(context).apply {
                text = sub
                Typography.applyCaption(this, tokens)
                setPadding(0, dp(context, 2), 0, 0)
                isSingleLine = true
            })
        }
        rowView.addView(labels, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        if (row.selected) {
            rowView.addView(ImageView(context).apply {
                setImageResource(R.drawable.ic_check)
                setColorFilter(tokens.accent)
                scaleType = ImageView.ScaleType.CENTER_INSIDE
            }, LinearLayout.LayoutParams(dp(context, 18), dp(context, 18)).apply {
                leftMargin = dp(context, DesignTokens.Spacing.md)
            })
        }

        return rowView
    }

    private fun divider(): View = View(context).apply {
        setBackgroundColor(withAlpha(tokens.borderSoft, 0xCC))
    }
}
