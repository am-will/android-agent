package dev.androidagent.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast

object ClipboardHelper {
    private const val MESSAGE_LABEL = "open-claw-message"
    private const val CODE_LABEL = "open-claw-code"

    fun copyMessage(context: Context, text: String) {
        copyPlainText(
            context = context,
            label = MESSAGE_LABEL,
            text = text,
            confirmation = "Message copied"
        )
    }

    fun copyCodeBlock(context: Context, text: String) {
        copyPlainText(
            context = context,
            label = CODE_LABEL,
            text = text,
            confirmation = "Code copied"
        )
    }

    fun copyPlainText(
        context: Context,
        label: String,
        text: String,
        confirmation: String = "Copied"
    ) {
        val appContext = context.applicationContext
        val clipboard = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
        Toast.makeText(appContext, confirmation, Toast.LENGTH_SHORT).show()
    }
}
