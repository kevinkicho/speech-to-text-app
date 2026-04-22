package com.stt.floater

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Listens for a local broadcast carrying transcribed text, finds the currently
 * focused editable field on screen, and pastes the text into it. Optionally
 * presses IME Enter afterwards to submit.
 *
 * User must enable the service once via Settings → Accessibility → Installed
 * services → "STT Floater auto-paste" → On.
 *
 * Known limitation: Termux's TerminalView is a custom canvas-based view that
 * doesn't expose standard editable node APIs. Paste is a no-op there; the
 * broadcast ingress falls back to the phone clipboard the user long-press-pastes.
 */
class SttAccessibilityService : AccessibilityService() {

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent == null) return
            val text = intent.getStringExtra(EXTRA_TEXT) ?: return
            val submit = intent.getBooleanExtra(EXTRA_SUBMIT, false)
            try {
                pasteIntoFocused(text, submit)
            } catch (e: Exception) {
                Log.w(TAG, "paste failed", e)
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d(TAG, "service connected")
        val filter = IntentFilter(ACTION_PASTE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(receiver, filter)
        }
    }

    override fun onDestroy() {
        try { unregisterReceiver(receiver) } catch (_: Exception) {}
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) { /* no-op */ }

    override fun onInterrupt() { /* no-op */ }

    private fun pasteIntoFocused(text: String, submit: Boolean) {
        val root = rootInActiveWindow
        if (root == null) {
            Log.d(TAG, "no active window — skipped")
            return
        }
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (focused == null || !focused.isEditable) {
            Log.d(TAG, "no focused editable — skipped (focused=${focused?.className})")
            return
        }

        val args = Bundle().apply {
            putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                text,
            )
        }
        val placed = focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        if (!placed) {
            Log.d(TAG, "SET_TEXT returned false on ${focused.className}")
            return
        }

        if (!submit) return

        // In chat apps, Enter inserts a newline and the real "send" is a
        // separate button. Hunt for it by content description / text. Only if
        // we can't find one do we fall back to the IME Enter action (which
        // works for simple single-line inputs like Chrome's address bar).
        val sendBtn = findSendButton(root)
        if (sendBtn != null) {
            val clicked = sendBtn.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            Log.d(TAG, "send button clicked=$clicked label=${sendBtn.contentDescription}/${sendBtn.text}")
            if (clicked) return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val enterAction = AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER
            val ok = focused.performAction(enterAction.id)
            Log.d(TAG, "IME_ENTER=$ok")
        }
    }

    private fun findSendButton(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        // Multilingual "send" label list. contentDescription and text are both checked.
        val keywords = listOf(
            "send", "submit",          // en
            "보내기", "전송",            // ko
            "送信", "発送",              // ja
            "发送",                     // zh-Hans
            "傳送",                     // zh-Hant
            "enviar",                   // es / pt
            "envoyer",                  // fr
            "senden",                   // de
            "invia",                    // it
            "отправить",                // ru
        )
        val found = mutableListOf<AccessibilityNodeInfo>()
        scanForSend(root, keywords, found)
        // Prefer clickable nodes, then those with shorter labels (specific > prose).
        return found
            .sortedWith(compareByDescending<AccessibilityNodeInfo> { it.isClickable }
                .thenBy { labelOf(it).length })
            .firstOrNull()
    }

    private fun scanForSend(
        node: AccessibilityNodeInfo?,
        keywords: List<String>,
        out: MutableList<AccessibilityNodeInfo>,
    ) {
        if (node == null) return
        val label = labelOf(node)
        if (label.isNotEmpty() && keywords.any { label.contains(it, ignoreCase = true) }) {
            out.add(node)
        }
        for (i in 0 until node.childCount) {
            scanForSend(node.getChild(i), keywords, out)
        }
    }

    private fun labelOf(n: AccessibilityNodeInfo): String =
        ((n.contentDescription?.toString() ?: "") + " " + (n.text?.toString() ?: "")).trim()

    companion object {
        const val TAG = "SttAccessibility"
        const val ACTION_PASTE = "com.stt.floater.ACTION_PASTE"
        const val EXTRA_TEXT = "text"
        const val EXTRA_SUBMIT = "submit"
    }
}
