package com.myai.assistant

import android.accessibilityservice.AccessibilityService
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class AssistantAccessibilityService : AccessibilityService() {

    companion object {
        var instance: AssistantAccessibilityService? = null
    }

    private val handler = Handler(Looper.getMainLooper())

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    fun readCurrentScreenText(): String {
        val root = rootInActiveWindow ?: return "Screen read nahi ho pa rahi (Accessibility ON karein)."
        val sb = StringBuilder()
        extractText(root, sb)
        val result = sb.toString().trim()
        return if (result.isBlank()) "Screen par koi text nahi mila." else result.take(2500)
    }

    private fun extractText(node: AccessibilityNodeInfo?, sb: StringBuilder) {
        if (node == null) return
        if (!node.text.isNullOrBlank()) sb.append(node.text).append(" | ")
        for (i in 0 until node.childCount) extractText(node.getChild(i), sb)
    }

    fun clickNodeByTextOrDesc(query: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val byText = root.findAccessibilityNodeInfosByText(query)
        for (node in byText) {
            if (clickSelfOrParent(node)) return true
        }
        return findAndClickByDesc(root, query)
    }

    private fun findAndClickByDesc(node: AccessibilityNodeInfo?, query: String): Boolean {
        if (node == null) return false
        val desc = node.contentDescription?.toString() ?: ""
        if (desc.contains(query, ignoreCase = true) && clickSelfOrParent(node)) return true
        for (i in 0 until node.childCount) {
            if (findAndClickByDesc(node.getChild(i), query)) return true
        }
        return false
    }

    private fun clickSelfOrParent(node: AccessibilityNodeInfo?): Boolean {
        var curr = node
        while (curr != null) {
            if (curr.isClickable) {
                curr.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                return true
            }
            curr = curr.parent
        }
        return false
    }

    fun typeInEditText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val editNode = findEditText(root) ?: return false
        val args = Bundle()
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        return editNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    private fun findEditText(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.className == "android.widget.EditText") return node
        for (i in 0 until node.childCount) {
            val res = findEditText(node.getChild(i))
            if (res != null) return res
        }
        return null
    }

    fun autoTapWhatsAppSend() {
        handler.postDelayed({
            clickNodeByTextOrDesc("Send")
        }, 3000)
    }

    fun autoSendInstagramDM(message: String) {
        handler.postDelayed({
            clickNodeByTextOrDesc("Message")
            handler.postDelayed({
                if (typeInEditText(message)) {
                    handler.postDelayed({
                        clickNodeByTextOrDesc("Send")
                    }, 1200)
                }
            }, 2500)
        }, 3500)
    }

    fun autoTapFirstYouTubeVideo() {
        handler.postDelayed({
            val root = rootInActiveWindow ?: return@postDelayed
            clickFirstClickableVideo(root)
        }, 3200)
    }

    private fun clickFirstClickableVideo(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false
        val desc = node.contentDescription?.toString() ?: ""
        if (desc.contains("minutes", ignoreCase = true) || desc.contains("seconds", ignoreCase = true) || desc.contains("views", ignoreCase = true)) {
            if (clickSelfOrParent(node)) return true
        }
        for (i in 0 until node.childCount) {
            if (clickFirstClickableVideo(node.getChild(i))) return true
        }
        return false
    }
}
