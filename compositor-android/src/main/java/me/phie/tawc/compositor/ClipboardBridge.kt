package me.phie.tawc.compositor

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Process-local bridge between Android's real ClipboardManager and the
 * native Wayland selection state. Text-only by design for now.
 *
 * The Android→Wayland direction is announce-only: change/focus/startup
 * syncs read just the [android.content.ClipDescription] (which never
 * triggers Android's "pasted from clipboard" toast) and tell native a
 * text clip exists. The real `getPrimaryClip()` read happens in
 * [getTextForPaste], only when a client actually pastes — so the toast
 * fires exactly once per paste.
 *
 * Since Android 10, clip-changed listeners only fire while this app holds
 * input focus, so copies made in other apps are invisible until the user
 * returns. [syncOnWindowFocusGained] re-announces the clipboard when a
 * compositor window regains focus to pick those up.
 */
object ClipboardBridge {
    private const val TAG = "tawc"
    private const val MAX_TEXT_BYTES = 1024 * 1024

    /** Clip label [setTextFromCompositor] writes. Announces carrying it
     *  are echoes of our own Wayland→Android mirror, which native must
     *  not bounce back over a live client-owned selection. */
    private const val OWN_CLIP_LABEL = "tawc"

    /** Clipboard reads can still be denied (null) for a short window
     *  after `onWindowFocusChanged(true)`; retry briefly before giving up. */
    private const val FOCUS_SYNC_ATTEMPTS = 3
    private const val FOCUS_SYNC_RETRY_MS = 250L

    private val handler = Handler(Looper.getMainLooper())

    /** Volatile: [getTextForPaste] runs on a native clipboard-fetch
     *  thread; everything else stays on the main thread. */
    @Volatile
    private var clipboard: ClipboardManager? = null

    /** One-entry `(timestamp → text)` cache for [getTextForPaste]:
     *  repeat pastes of an unchanged clip skip `getPrimaryClip()`, and
     *  with it the per-read paste toast some OEM builds show. Disabled
     *  when the clip carries no timestamp (0). */
    @Volatile
    private var pasteCache: Pair<Long, String>? = null

    private var focusSyncAttemptsLeft = 0

    private val listener = ClipboardManager.OnPrimaryClipChangedListener {
        announceClip()
    }

    private val focusSyncRunnable = object : Runnable {
        override fun run() {
            focusSyncAttemptsLeft--
            if (!announceClip() && focusSyncAttemptsLeft > 0) {
                handler.postDelayed(this, FOCUS_SYNC_RETRY_MS)
            }
        }
    }

    fun attach(context: Context) {
        val ctx = context.applicationContext
        clipboard?.removePrimaryClipChangedListener(listener)
        clipboard = ctx.getSystemService(ClipboardManager::class.java)
        clipboard?.addPrimaryClipChangedListener(listener)
    }

    fun detach() {
        clipboard?.removePrimaryClipChangedListener(listener)
        handler.removeCallbacks(focusSyncRunnable)
        clipboard = null
        pasteCache = null
        focusSyncAttemptsLeft = 0
    }

    fun setTextFromCompositor(text: String) {
        val cm = clipboard ?: return
        if (text.toByteArray(Charsets.UTF_8).size > MAX_TEXT_BYTES) {
            Log.w(TAG, "ClipboardBridge: refusing to write compositor text over ${MAX_TEXT_BYTES}B")
            return
        }
        cm.setPrimaryClip(ClipData.newPlainText(OWN_CLIP_LABEL, text))
    }

    /** Debug-only write; production copies go through [setTextFromCompositor].
     *  [asHtml] mimics Firefox/Gecko web-content copies: an HTML clip whose
     *  description has no text/plain MIME but whose item carries the text.
     *  The label is deliberately not [OWN_CLIP_LABEL] so dev clips announce
     *  like a foreign app's. */
    fun setTextFromDevAction(text: String, asHtml: Boolean = false) {
        val cm = clipboard ?: return
        if (text.toByteArray(Charsets.UTF_8).size > MAX_TEXT_BYTES) {
            Log.w(TAG, "ClipboardBridge: refusing to write dev text over ${MAX_TEXT_BYTES}B")
            return
        }
        val clip = if (asHtml) {
            ClipData.newHtmlText("tawc-dev", text, "<span>$text</span>")
        } else {
            ClipData.newPlainText("tawc-dev", text)
        }
        cm.setPrimaryClip(clip)
    }

    /** Debug-only full read; production pastes go through [getTextForPaste]. */
    fun getTextForDevAction(): String {
        return currentText() ?: ""
    }

    /** Announce whatever clip the service starts up with. */
    fun announceCurrentClip() {
        announceClip()
    }

    /** Re-announce the clipboard now that the app regained window focus,
     *  catching copies made in other apps while we were backgrounded. */
    fun syncOnWindowFocusGained() {
        if (clipboard == null) return
        handler.removeCallbacks(focusSyncRunnable)
        focusSyncAttemptsLeft = FOCUS_SYNC_ATTEMPTS
        focusSyncRunnable.run()
    }

    /** The real clipboard read backing a paste in progress. Called via
     *  reverse JNI from a native clipboard-fetch thread; ClipboardManager
     *  is a binder proxy, safe to use off the main thread. */
    fun getTextForPaste(): String? {
        val cm = clipboard ?: return null
        val ts = cm.primaryClipDescription?.timestamp ?: 0L
        if (ts != 0L) {
            pasteCache?.let { (cachedTs, cachedText) ->
                if (cachedTs == ts) return cachedText
            }
        }
        val text = currentText() ?: return null
        if (ts != 0L) pasteCache = ts to text
        return text
    }

    /** Describe the current clip to native without reading its content.
     *  Returns false when the description read was denied (no focus yet)
     *  so the focus-gain retry loop keeps going.
     *
     *  The HTML MIME covers Firefox/Gecko web-content copies, whose clips
     *  advertise text/html only. Deliberately not a text/`*` wildcard:
     *  URI and Intent clips advertise text/uri-list / text/vnd.android.intent
     *  yet carry no item text, and announcing them would replace a live
     *  client selection with an unpasteable one. Clips whose text lives
     *  solely in an item with no text MIME in the description are not
     *  caught — acceptable, rare. */
    private fun announceClip(): Boolean {
        val desc = clipboard?.primaryClipDescription ?: return false
        if (!desc.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN) &&
            !desc.hasMimeType(ClipDescription.MIMETYPE_TEXT_HTML)
        ) return true
        val ownWrite = desc.label?.toString() == OWN_CLIP_LABEL
        NativeBridge.nativeOnAndroidClipAvailable(desc.timestamp, ownWrite)
        return true
    }

    /** No ClipDescription MIME gate: Firefox/Gecko copies of web content are
     *  HTML clips that advertise only text/html yet carry the plain text in
     *  the item. Non-text clips (images, URIs) have a null item text. */
    private fun currentText(): String? {
        val clip = clipboard?.primaryClip ?: return null
        if (clip.itemCount <= 0) return null
        val text = clip.getItemAt(0).text ?: return null
        // UTF-8 is never shorter than UTF-16 code units, so the cheap
        // length check bounds the encode the byte check needs.
        if (text.length > MAX_TEXT_BYTES) return null
        val str = text.toString()
        if (str.toByteArray(Charsets.UTF_8).size > MAX_TEXT_BYTES) return null
        return str
    }
}
