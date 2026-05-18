package dev.androidagent.ui

import android.graphics.Typeface
import android.widget.TextView

/**
 * Typography helpers — every TextView in the redesigned UI should be styled
 * through these so the type scale stays consistent.
 */
object Typography {

    fun applyCaption(tv: TextView, tokens: ThemeTokens, emphasis: Boolean = false) {
        tv.textSize = DesignTokens.Text.caption
        tv.setTextColor(if (emphasis) tokens.secondaryText else tokens.tertiaryText)
        tv.includeFontPadding = false
        tv.typeface = if (emphasis) Typeface.create(tv.typeface, Typeface.BOLD) else tv.typeface
        tv.letterSpacing = 0.02f
    }

    fun applyFootnote(tv: TextView, tokens: ThemeTokens, secondary: Boolean = false) {
        tv.textSize = DesignTokens.Text.footnote
        tv.setTextColor(if (secondary) tokens.secondaryText else tokens.primaryText)
        tv.includeFontPadding = false
    }

    fun applyBody(tv: TextView, tokens: ThemeTokens, secondary: Boolean = false) {
        tv.textSize = DesignTokens.Text.body
        tv.setTextColor(if (secondary) tokens.secondaryText else tokens.primaryText)
        tv.includeFontPadding = false
    }

    fun applyCallout(tv: TextView, tokens: ThemeTokens, secondary: Boolean = false) {
        tv.textSize = DesignTokens.Text.callout
        tv.setTextColor(if (secondary) tokens.secondaryText else tokens.primaryText)
        tv.includeFontPadding = false
    }

    fun applyHeadline(tv: TextView, tokens: ThemeTokens, color: Int? = null) {
        tv.textSize = DesignTokens.Text.headline
        tv.setTextColor(color ?: tokens.primaryText)
        tv.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        tv.includeFontPadding = false
    }

    fun applyTitle(tv: TextView, tokens: ThemeTokens) {
        tv.textSize = DesignTokens.Text.title
        tv.setTextColor(tokens.primaryText)
        tv.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        tv.includeFontPadding = false
    }

    fun applyLargeTitle(tv: TextView, tokens: ThemeTokens) {
        tv.textSize = DesignTokens.Text.largeTitle
        tv.setTextColor(tokens.primaryText)
        tv.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        tv.includeFontPadding = false
        tv.letterSpacing = -0.01f
    }

    fun applyOverline(tv: TextView, tokens: ThemeTokens) {
        tv.text = tv.text?.toString()?.uppercase()
        tv.textSize = DesignTokens.Text.caption
        tv.setTextColor(tokens.tertiaryText)
        tv.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        tv.letterSpacing = 0.10f
        tv.includeFontPadding = false
    }

    /** For tool args/output blocks — monospace block. */
    fun applyMono(tv: TextView, tokens: ThemeTokens) {
        tv.textSize = DesignTokens.Text.footnote
        tv.setTextColor(tokens.secondaryText)
        tv.typeface = Typeface.MONOSPACE
        tv.includeFontPadding = false
    }
}
