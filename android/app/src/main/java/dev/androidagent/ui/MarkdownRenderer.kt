package dev.androidagent.ui

import android.content.Context
import android.graphics.Color
import android.widget.TextView
import io.noties.markwon.Markwon
import io.noties.markwon.core.MarkwonTheme
import io.noties.markwon.core.CorePlugin
import io.noties.markwon.AbstractMarkwonPlugin
import io.noties.markwon.MarkwonConfiguration
import io.noties.markwon.linkify.LinkifyPlugin
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin
import io.noties.markwon.ext.tables.TablePlugin
import io.noties.markwon.html.HtmlPlugin

/**
 * Singleton-ish Markwon instance per token theme.
 *
 * Renders chat message text as rich markdown: bold, italic, strikethrough,
 * inline code, fenced code blocks, links, lists, tables, blockquotes, and
 * autolinked URLs. Falls back to plain text if rendering fails for any
 * reason.
 */
object MarkdownRenderer {

    @Volatile private var lastAccent: Int = 0
    @Volatile private var lastSecondary: Int = 0
    @Volatile private var instance: Markwon? = null

    fun render(textView: TextView, source: String, tokens: ThemeTokens) {
        val markwon = obtain(textView.context, tokens)
        try {
            markwon.setMarkdown(textView, source)
        } catch (t: Throwable) {
            textView.text = source
        }
    }

    private fun obtain(context: Context, tokens: ThemeTokens): Markwon {
        val cached = instance
        if (cached != null && lastAccent == tokens.accent && lastSecondary == tokens.secondaryText) {
            return cached
        }
        val codeBg = DesignTokens.withAlpha(tokens.secondaryText, 0x22)
        val codeBlockBg = DesignTokens.withAlpha(tokens.secondaryText, 0x18)
        val markwon = Markwon.builder(context)
            .usePlugin(CorePlugin.create())
            .usePlugin(LinkifyPlugin.create())
            .usePlugin(StrikethroughPlugin.create())
            .usePlugin(TablePlugin.create(context))
            .usePlugin(HtmlPlugin.create())
            .usePlugin(object : AbstractMarkwonPlugin() {
                override fun configureTheme(builder: MarkwonTheme.Builder) {
                    builder
                        .linkColor(tokens.accent)
                        .codeTextColor(tokens.primaryText)
                        .codeBackgroundColor(codeBg)
                        .codeBlockTextColor(tokens.primaryText)
                        .codeBlockBackgroundColor(codeBlockBg)
                        .blockQuoteColor(tokens.accent)
                        .headingBreakColor(Color.TRANSPARENT)
                        .bulletWidth(DesignTokens.dp(context, 4))
                        .listItemColor(tokens.primaryText)
                }
                override fun configureConfiguration(builder: MarkwonConfiguration.Builder) {
                    // defaults are fine
                }
            })
            .build()
        instance = markwon
        lastAccent = tokens.accent
        lastSecondary = tokens.secondaryText
        return markwon
    }
}
