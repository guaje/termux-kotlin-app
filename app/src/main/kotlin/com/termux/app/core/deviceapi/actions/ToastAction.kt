package com.termux.app.core.deviceapi.actions

import android.content.Context
import android.widget.Toast
import com.termux.app.core.api.DeviceApiError
import com.termux.app.core.api.Result
import com.termux.app.core.logging.TermuxLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ToastAction @Inject constructor(
    @ApplicationContext private val context: Context,
    logger: TermuxLogger
) : DeviceApiActionBase<Unit>(logger) {

    override val actionName: String = "toast"
    override val description: String = "Show a short toast message"

    override suspend fun execute(params: Map<String, String>): Result<Unit, DeviceApiError> {
        return executeWithLogging {
            withContext(Dispatchers.Main) {
                val text = params["text"] ?: ""
                val long = params["long"]?.toBooleanStrictOrNull() ?: false
                Toast.makeText(context, text, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
            }
        }
    }
}
