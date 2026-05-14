package dev.androidagent.accessibility

import android.graphics.Rect
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
        val displayMetrics = service.resources.displayMetrics
        return JSONObject()
            .put("deviceId", android.os.Build.MODEL)
            .put("package", root?.packageName?.toString() ?: "")
            .put("activity", service.lastActivityClassName ?: "")
            .put(
                "display",
                JSONObject()
                    .put("widthPx", displayMetrics.widthPixels)
                    .put("heightPx", displayMetrics.heightPixels)
                    .put("density", displayMetrics.density)
                    .put("densityDpi", displayMetrics.densityDpi)
            )
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
}
