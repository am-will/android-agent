package dev.androidagent.accessibility

import android.content.Context
import android.graphics.Rect
import android.os.Build
import android.util.DisplayMetrics
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject

class ScreenObserver {
    private val nodesById = linkedMapOf<String, AccessibilityNodeInfo>()

    fun node(id: String): AccessibilityNodeInfo? = nodesById[id]

    fun observe(service: PhoneAccessibilityService): JSONObject {
        nodesById.clear()
        val root = service.rootInActiveWindow
        val nodes = JSONArray()
        val summary = mutableListOf<String>()
        if (root != null) {
            walk(root, nodes, summary, 0)
        }
        return JSONObject()
            .put("deviceId", android.os.Build.MODEL)
            .put("package", root?.packageName?.toString() ?: "")
            .put("activity", service.lastActivityClassName ?: "")
            .put("display", displayJson(service))
            .put("screenSummary", summary.take(12).joinToString(" | "))
            .put("nodes", nodes)
    }

    private fun walk(node: AccessibilityNodeInfo, out: JSONArray, summary: MutableList<String>, depth: Int) {
        if (nodesById.size >= 120 || depth > 12) {
            return
        }
        val text = node.text?.toString().orEmpty()
        val contentDescription = node.contentDescription?.toString().orEmpty()
        if (text.isNotBlank()) {
            summary.add(text)
        } else if (contentDescription.isNotBlank()) {
            summary.add(contentDescription)
        }

        val rect = Rect()
        node.getBoundsInScreen(rect)
        if (!rect.isEmpty && (text.isNotBlank() || contentDescription.isNotBlank() || node.isClickable || node.isScrollable || node.isFocused)) {
            val id = "n${nodesById.size + 1}"
            nodesById[id] = node
            out.put(
                JSONObject()
                    .put("id", id)
                    .put("text", text)
                    .put("contentDescription", contentDescription)
                    .put("className", node.className?.toString().orEmpty())
                    .put("clickable", node.isClickable)
                    .put("enabled", node.isEnabled)
                    .put("focused", node.isFocused)
                    .put("bounds", JSONArray(listOf(rect.left, rect.top, rect.right, rect.bottom)))
            )
        }

        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { child -> walk(child, out, summary, depth + 1) }
        }
    }

    companion object {
        fun displayJson(service: PhoneAccessibilityService): JSONObject {
            val size = realDisplaySize(service)
            val displayMetrics = service.resources.displayMetrics
            return JSONObject()
                .put("widthPx", size.widthPx)
                .put("heightPx", size.heightPx)
                .put("density", displayMetrics.density)
                .put("densityDpi", displayMetrics.densityDpi)
        }

        fun realDisplaySize(service: PhoneAccessibilityService): DisplaySize {
            val windowManager = service.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            if (windowManager != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val bounds = windowManager.currentWindowMetrics.bounds
                if (bounds.width() > 0 && bounds.height() > 0) {
                    return DisplaySize(bounds.width(), bounds.height())
                }
            }

            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager?.defaultDisplay?.getRealMetrics(metrics)
            if (metrics.widthPixels > 0 && metrics.heightPixels > 0) {
                return DisplaySize(metrics.widthPixels, metrics.heightPixels)
            }

            val fallback = service.resources.displayMetrics
            return DisplaySize(fallback.widthPixels, fallback.heightPixels)
        }
    }
}

data class DisplaySize(val widthPx: Int, val heightPx: Int)
