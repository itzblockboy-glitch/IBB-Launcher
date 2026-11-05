package net.kdt.pojavlaunch

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import dev.ibb.launcher.core.IbbLog

/**
 * Static bridge the Cacio/AWT layer calls back into.
 *
 * `awt_bridge.c` resolves `net/kdt/pojavlaunch/MainActivity` and invokes these static methods when
 * an **AWT-backed screen** (the old Forge installer, or a LWJGL2 version) wants to open a link or
 * touch the clipboard. Only those legacy paths use them: modern versions render through GLFW and
 * never load AWT.
 *
 * The class name and the four method signatures are ABI (`awt_bridge.c`):
 *  * `openLink(String)`
 *  * `querySystemClipboard()`
 *  * `putClipboardData(String, String)`
 *  * and the pair used by the clipboard handshake.
 *
 * The context is captured from [dev.ibb.launcher.IbbApplication], so no Activity instance is held.
 */
object MainActivity {

    private const val TAG = "MainActivity"

    @Volatile
    private var appContext: Context? = null

    /** Clipboard value handed back to the AWT bridge after [querySystemClipboard]. */
    @Volatile
    var clipboardContents: String? = null
        private set

    /** Called once from `IbbApplication.onCreate`. */
    fun attach(context: Context) {
        appContext = context.applicationContext
    }

    private fun clipboard(): ClipboardManager? =
        appContext?.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager

    /** Opens a URL in the browser. Called from the game/AWT thread. */
    @JvmStatic
    fun openLink(link: String) {
        val context = appContext ?: return
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(link)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }.onFailure { IbbLog.w(TAG, "Could not open $link", it) }
    }

    /** Same as [openLink]; upstream registers one method under two names. */
    @JvmStatic
    fun openPath(path: String) = openLink(path)

    /** Reads the Android clipboard into [clipboardContents] for the native side to pick up. */
    @JvmStatic
    fun querySystemClipboard() {
        clipboardContents = runCatching {
            clipboard()?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(appContext)
                ?.toString()
        }.getOrNull()
    }

    /** Writes a string to the Android clipboard. */
    @JvmStatic
    fun putClipboardData(data: String, mimeType: String?) {
        runCatching {
            clipboard()?.setPrimaryClip(android.content.ClipData.newPlainText(mimeType ?: "text", data))
        }.onFailure { IbbLog.w(TAG, "Could not write the clipboard", it) }
    }
}
