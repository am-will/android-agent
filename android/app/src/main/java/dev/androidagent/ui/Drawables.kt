package dev.androidagent.ui

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.content.res.ColorStateList
import androidx.core.content.ContextCompat
import dev.androidagent.ui.DesignTokens.dp
import dev.androidagent.ui.DesignTokens.withAlpha

/**
 * Drawable factories that paint surfaces in the iMessage-glassy aesthetic.
 *
 * All factories return a fresh `Drawable`, never share mutable instances.
 */
object Drawables {

    // ----- raw rounded rectangle helpers -----

    fun rounded(
        fill: Int,
        radius: Float,
        strokeColor: Int? = null,
        strokeWidth: Int = 1
    ): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = radius
        setColor(fill)
        strokeColor?.let { setStroke(strokeWidth, it) }
    }

    fun roundedAsymmetric(
        fill: Int,
        radii: FloatArray,
        strokeColor: Int? = null,
        strokeWidth: Int = 1
    ): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadii = radii
        setColor(fill)
        strokeColor?.let { setStroke(strokeWidth, it) }
    }

    fun topRounded(
        fill: Int,
        radius: Float,
        strokeColor: Int? = null,
        strokeWidth: Int = 1
    ): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadii = floatArrayOf(radius, radius, radius, radius, 0f, 0f, 0f, 0f)
        setColor(fill)
        strokeColor?.let { setStroke(strokeWidth, it) }
    }

    fun circle(fill: Int, strokeColor: Int? = null, strokeWidth: Int = 1): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(fill)
            strokeColor?.let { setStroke(strokeWidth, it) }
        }

    // ----- glass surfaces -----

    /**
     * The main floating modal background. Layered: a soft outer halo,
     * the glass surface itself, and a 1px inner border.
     */
    fun glassPanel(context: Context, tokens: ThemeTokens, radius: Int = DesignTokens.Radius.xl): LayerDrawable {
        val r = dp(context, radius).toFloat()
        val halo = rounded(
            fill = if (tokens.isDark) withAlpha(Color.BLACK, 0x33) else withAlpha(Color.BLACK, 0x11),
            radius = r + dp(context, 2)
        )
        val body = rounded(
            fill = tokens.surfaceGlass,
            radius = r,
            strokeColor = tokens.borderSoft,
            strokeWidth = dp(context, 1).coerceAtLeast(1)
        )
        return LayerDrawable(arrayOf(halo, body)).apply {
            setLayerInset(1, dp(context, 1), dp(context, 1), dp(context, 1), dp(context, 1))
        }
    }

    /** Standard card / popover surface — like glassPanel but flatter. */
    fun glassSurface(context: Context, tokens: ThemeTokens, radius: Int = DesignTokens.Radius.lg): GradientDrawable {
        return rounded(
            fill = tokens.surfaceElevated,
            radius = dp(context, radius).toFloat(),
            strokeColor = tokens.borderSoft,
            strokeWidth = dp(context, 1).coerceAtLeast(1)
        )
    }

    /** A recessed input/inset surface — composer field, tool body, transcript area. */
    fun glassInset(context: Context, tokens: ThemeTokens, radius: Int = DesignTokens.Radius.md): GradientDrawable {
        return rounded(
            fill = tokens.surfaceInset,
            radius = dp(context, radius).toFloat(),
            strokeColor = tokens.borderSoft,
            strokeWidth = dp(context, 1).coerceAtLeast(1)
        )
    }

    /** Pill control background (Model / Reason / icon buttons in idle state). */
    fun pillSurface(context: Context, tokens: ThemeTokens, radius: Int = DesignTokens.Radius.pill): GradientDrawable {
        return rounded(
            fill = tokens.surfaceInset,
            radius = dp(context, radius).toFloat(),
            strokeColor = tokens.borderSoft,
            strokeWidth = dp(context, 1).coerceAtLeast(1)
        )
    }

    /** Tonal accent pill (toggled-on / status chips). */
    fun accentSoftSurface(context: Context, tokens: ThemeTokens, radius: Int = DesignTokens.Radius.pill): GradientDrawable {
        return rounded(
            fill = tokens.accentSoft,
            radius = dp(context, radius).toFloat(),
            strokeColor = withAlpha(tokens.accent, 0x55),
            strokeWidth = dp(context, 1).coerceAtLeast(1)
        )
    }

    /** Solid accent — primary action button, user chat bubble. */
    fun accentSurface(context: Context, tokens: ThemeTokens, radius: Int = DesignTokens.Radius.md): GradientDrawable {
        return rounded(
            fill = tokens.accent,
            radius = dp(context, radius).toFloat()
        )
    }

    /** Danger / destructive surface (trash target, hangup confirmation). */
    fun dangerSurface(context: Context, tokens: ThemeTokens, radius: Int = DesignTokens.Radius.pill): GradientDrawable {
        return rounded(
            fill = tokens.danger,
            radius = dp(context, radius).toFloat()
        )
    }

    fun dangerSoftSurface(context: Context, tokens: ThemeTokens, radius: Int = DesignTokens.Radius.md): GradientDrawable {
        return rounded(
            fill = tokens.dangerSoft,
            radius = dp(context, radius).toFloat(),
            strokeColor = withAlpha(tokens.danger, 0x55),
            strokeWidth = dp(context, 1).coerceAtLeast(1)
        )
    }

    // ----- chat bubbles (asymmetric: tail corner is tight) -----

    private fun bubbleRadii(context: Context, large: Int, tight: Int, tightCorner: TightCorner): FloatArray {
        val lr = dp(context, large).toFloat()
        val tr = dp(context, tight).toFloat()
        // order: TL_x, TL_y, TR_x, TR_y, BR_x, BR_y, BL_x, BL_y
        return when (tightCorner) {
            TightCorner.BOTTOM_RIGHT -> floatArrayOf(lr, lr, lr, lr, tr, tr, lr, lr)
            TightCorner.BOTTOM_LEFT -> floatArrayOf(lr, lr, lr, lr, lr, lr, tr, tr)
        }
    }

    enum class TightCorner { BOTTOM_RIGHT, BOTTOM_LEFT }

    fun chatBubbleUser(context: Context, tokens: ThemeTokens): GradientDrawable {
        return roundedAsymmetric(
            fill = tokens.bubbleUser,
            radii = bubbleRadii(context, large = DesignTokens.Radius.lg, tight = DesignTokens.Spacing.sm, tightCorner = TightCorner.BOTTOM_RIGHT)
        )
    }

    fun chatBubbleAssistant(context: Context, tokens: ThemeTokens): GradientDrawable {
        return roundedAsymmetric(
            fill = tokens.bubbleAssistant,
            radii = bubbleRadii(context, large = DesignTokens.Radius.lg, tight = DesignTokens.Spacing.sm, tightCorner = TightCorner.BOTTOM_LEFT),
            strokeColor = tokens.borderSoft,
            strokeWidth = dp(context, 1).coerceAtLeast(1)
        )
    }

    fun chatBubbleSystem(context: Context, tokens: ThemeTokens): GradientDrawable {
        return rounded(
            fill = tokens.bubbleSystem,
            radius = dp(context, DesignTokens.Radius.md).toFloat(),
            strokeColor = withAlpha(tokens.danger, 0x44),
            strokeWidth = dp(context, 1).coerceAtLeast(1)
        )
    }

    // ----- picker / dropdown surfaces -----

    fun dropdownSheet(context: Context, tokens: ThemeTokens): LayerDrawable {
        val r = dp(context, DesignTokens.Radius.md).toFloat()
        val halo = rounded(
            fill = if (tokens.isDark) withAlpha(Color.BLACK, 0x55) else withAlpha(Color.BLACK, 0x18),
            radius = r + dp(context, 2)
        )
        val body = rounded(
            fill = tokens.surfaceElevated,
            radius = r,
            strokeColor = tokens.border,
            strokeWidth = dp(context, 1).coerceAtLeast(1)
        )
        return LayerDrawable(arrayOf(halo, body)).apply {
            setLayerInset(1, dp(context, 1), dp(context, 1), dp(context, 1), dp(context, 2))
        }
    }

    fun dropdownRowBackground(context: Context, tokens: ThemeTokens): Drawable {
        val pressed = rounded(
            fill = if (tokens.isDark) withAlpha(Color.WHITE, 0x14) else withAlpha(Color.BLACK, 0x10),
            radius = dp(context, DesignTokens.Radius.sm).toFloat()
        )
        val idle = rounded(
            fill = Color.TRANSPARENT,
            radius = dp(context, DesignTokens.Radius.sm).toFloat()
        )
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), pressed)
            addState(intArrayOf(android.R.attr.state_focused), pressed)
            addState(intArrayOf(android.R.attr.state_selected), pressed)
            addState(intArrayOf(), idle)
        }
    }

    // ----- composite surfaces -----

    fun rippleOver(
        context: Context,
        tokens: ThemeTokens,
        base: Drawable,
        rippleColor: Int = if (tokens.isDark) withAlpha(Color.WHITE, 0x33) else withAlpha(Color.BLACK, 0x22)
    ): Drawable {
        return RippleDrawable(ColorStateList.valueOf(rippleColor), base, null)
    }

    /**
     * Builds a horizontal two-icon drawable suitable for use as a compound
     * drawable on a TextView. Each icon is rendered at [iconDp] x [iconDp] dp
     * separated by [gapDp] dp.
     */
    fun horizontalIconPair(
        context: Context,
        leftRes: Int,
        rightRes: Int,
        tint: Int,
        iconDp: Int = 14,
        gapDp: Int = 2
    ): Drawable {
        val left = ContextCompat.getDrawable(context, leftRes)!!.mutate()
        val right = ContextCompat.getDrawable(context, rightRes)!!.mutate()
        left.setTint(tint)
        right.setTint(tint)
        val iconPx = dp(context, iconDp)
        val gapPx = dp(context, gapDp)
        left.setBounds(0, 0, iconPx, iconPx)
        right.setBounds(iconPx + gapPx, 0, iconPx * 2 + gapPx, iconPx)
        val layer = LayerDrawable(arrayOf(left, right))
        layer.setBounds(0, 0, iconPx * 2 + gapPx, iconPx)
        return layer
    }

    /** Voice transcript-area inset matches glassInset but with a softer accent border. */
    fun voiceTranscriptSurface(context: Context, tokens: ThemeTokens): GradientDrawable {
        return rounded(
            fill = tokens.surfaceInset,
            radius = dp(context, DesignTokens.Radius.md).toFloat(),
            strokeColor = withAlpha(tokens.accent, 0x44),
            strokeWidth = dp(context, 1).coerceAtLeast(1)
        )
    }

    /** A radial halo behind the floating bubble (idle / voice states). */
    fun bubbleHalo(context: Context, centerColor: Int, midColor: Int): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            gradientType = GradientDrawable.RADIAL_GRADIENT
            setGradientCenter(0.5f, 0.5f)
            setGradientRadius(dp(context, 48).toFloat())
            colors = intArrayOf(centerColor, midColor, Color.TRANSPARENT)
        }
    }
}
