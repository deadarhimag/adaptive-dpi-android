package org.adaptivedpi.android

import android.app.UiAutomation
import android.graphics.Bitmap
import android.util.Log
import android.util.Xml
import android.view.accessibility.AccessibilityNodeInfo
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.StringWriter
import org.xmlpull.v1.XmlSerializer

internal const val VPN_CONSENT_TAG = "VpnConsentTest"

// Снимок нужен до ActivityScenario.close(), пока системное окно ещё может быть открыто.
internal fun captureVpnConsentFailure(automation: UiAutomation, cacheDir: File) {
    val directory = File(cacheDir, "vpn-consent-ci")
    runCatching {
        val screenshot = checkNotNull(automation.takeScreenshot())
        try {
            val encoded = ByteArrayOutputStream()
            check(screenshot.compress(Bitmap.CompressFormat.JPEG, 80, encoded))
            check(encoded.size() <= 512 * 1024) { "Снимок экрана превышает лимит" }
            check(directory.isDirectory || directory.mkdirs())
            File(directory, "consent-screen.jpg").writeBytes(encoded.toByteArray())
        } finally {
            screenshot.recycle()
        }
    }.onFailure { Log.w(VPN_CONSENT_TAG, "snapshot=screen exception=${it.javaClass.simpleName}") }

    runCatching {
        val output = StringWriter()
        val xml = Xml.newSerializer().apply { setOutput(output) }
        var remainingNodes = 96
        fun writeNode(node: AccessibilityNodeInfo, depth: Int) {
            if (remainingNodes == 0 || depth == 12) return
            remainingNodes--
            xml.startTag(null, "node")
            xml.identifier("package", node.packageName)
            xml.identifier("class", node.className)
            xml.identifier("resource-id", node.viewIdResourceName)
            xml.attribute(null, "visible", node.isVisibleToUser.toString())
            xml.attribute(null, "enabled", node.isEnabled.toString())
            xml.attribute(null, "clickable", node.isClickable.toString())
            for (index in 0 until minOf(node.childCount, 96)) {
                if (remainingNodes == 0) break
                node.getChild(index)?.let { writeNode(it, depth + 1) }
            }
            xml.endTag(null, "node")
        }

        xml.startDocument("UTF-8", true)
        xml.startTag(null, "windows")
        val windows = automation.windows.take(16)
        automation.rootInActiveWindow?.let { root ->
            xml.startTag(null, "active-root")
            xml.attribute(null, "window-id", root.windowId.toString())
            xml.identifier("package", root.packageName)
            if (windows.none { it.id == root.windowId }) writeNode(root, 0)
            xml.endTag(null, "active-root")
        }
        for (window in windows) {
            val root = window.root
            xml.startTag(null, "window")
            xml.attribute(null, "id", window.id.toString())
            xml.attribute(null, "type", window.type.toString())
            xml.attribute(null, "active", window.isActive.toString())
            xml.attribute(null, "focused", window.isFocused.toString())
            xml.identifier("package", root?.packageName)
            if (root != null) writeNode(root, 0)
            xml.endTag(null, "window")
        }
        xml.endTag(null, "windows")
        xml.endDocument()
        val contents = output.toString()
        check(contents.length <= 64 * 1024) { "Дерево окон превышает лимит" }
        check(directory.isDirectory || directory.mkdirs())
        File(directory, "consent-windows.xml").writeText(contents)
    }.onFailure { Log.w(VPN_CONSENT_TAG, "snapshot=windows exception=${it.javaClass.simpleName}") }
}

private fun XmlSerializer.identifier(name: String, value: CharSequence?) {
    if (value != null) attribute(null, name, value.take(96).filter {
        it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it in "._:/\$-"
    }.toString())
}
