package com.termux.app.core.deviceapi.actions

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.termux.app.core.api.DeviceApiError
import com.termux.app.core.api.Result
import com.termux.app.core.logging.TermuxLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Device API action for device vibration.
 */
@Singleton
class VibrateAction @Inject constructor(
    @ApplicationContext private val context: Context,
    logger: TermuxLogger
) : DeviceApiActionBase<Unit>(logger) {

    override val actionName: String = "vibrate"
    override val description: String = "Vibrate the device"
    // VIBRATE is a normal manifest permission and does not require a runtime prompt.
    override val requiredPermissions: List<String> = emptyList()

    override suspend fun execute(params: Map<String, String>): Result<Unit, DeviceApiError> {
        return executeWithLogging {
            withContext(Dispatchers.Main) {
                val ms = params["duration"]?.toLongOrNull() ?: 300L
                val force = params["force"]?.toBooleanStrictOrNull() ?: false
                val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                    vm.defaultVibrator
                } else {
                    context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                }
                if (!vibrator.hasVibrator()) {
                    throw IllegalStateException("Device has no vibrator")
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    val effect = if (force) {
                        VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE)
                    } else {
                        VibrationEffect.createOneShot(ms, 64)
                    }
                    vibrator.vibrate(effect)
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(ms)
                }
            }
        }
    }
}
