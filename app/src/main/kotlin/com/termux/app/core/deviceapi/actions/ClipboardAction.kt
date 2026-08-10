package com.termux.app.core.deviceapi.actions

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import com.termux.app.core.api.DeviceApiError
import com.termux.app.core.api.Result
import com.termux.app.core.logging.TermuxLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Device API action for reading and writing the system clipboard.
 *
 * No special permissions are required; clipboard access is available to
 * foreground applications on all supported Android versions.
 */
@Singleton
class ClipboardAction @Inject constructor(
    @ApplicationContext private val context: Context,
    logger: TermuxLogger
) : DeviceApiActionBase<String>(logger) {

    override val actionName: String = "clipboard"
    override val description: String = "Get or set clipboard contents"

    override suspend fun execute(params: Map<String, String>): Result<String, DeviceApiError> {
        return executeWithLogging {
            withContext(Dispatchers.Main) {
                val setText = params["text"]
                if (setText != null) {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("termux", setText))
                    setText
                } else {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    // On API 29+ accessing clipboard from background is restricted;
                    // here we run on main dispatcher so it should work when app is foreground.
                    val clip = clipboard.primaryClip
                    val item = clip?.getItemAt(0)
                    item?.text?.toString() ?: ""
                }
            }
        }
    }
}
