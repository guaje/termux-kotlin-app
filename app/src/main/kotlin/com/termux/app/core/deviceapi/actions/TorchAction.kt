package com.termux.app.core.deviceapi.actions

import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import androidx.core.content.ContextCompat
import com.termux.app.core.api.DeviceApiError
import com.termux.app.core.api.Result
import com.termux.app.core.logging.TermuxLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.suspendCancellableCoroutine
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

@Singleton
class TorchAction @Inject constructor(
    @ApplicationContext private val context: Context,
    logger: TermuxLogger
) : DeviceApiActionBase<Boolean>(logger) {

    override val actionName: String = "torch"
    override val description: String = "Toggle device flashlight"
    override val requiredPermissions: List<String> = listOf(android.Manifest.permission.CAMERA)

    override suspend fun execute(params: Map<String, String>): Result<Boolean, DeviceApiError> {
        // CAMERA is a dangerous runtime permission — verify it before accessing the flash
        if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return Result.error(
                DeviceApiError.PermissionRequired(
                    permission = android.Manifest.permission.CAMERA,
                    apiAction = actionName
                )
            )
        }

        return executeWithLogging {
            val enabled = params["enabled"]?.toBooleanStrictOrNull()
                ?: throw IllegalArgumentException("Missing 'enabled' parameter")
            toggleTorch(enabled)
        }
    }

    @Suppress("DEPRECATION")
    private suspend fun toggleTorch(enabled: Boolean): Boolean =
        suspendCancellableCoroutine { cont ->
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraId = cameraManager.cameraIdList.firstOrNull { id ->
                cameraManager.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }
            if (cameraId == null) {
                cont.resume(false)
                return@suspendCancellableCoroutine
            }
            try {
                cameraManager.setTorchMode(cameraId, enabled)
                cont.resume(true)
            } catch (e: Exception) {
                throw e
            }
        }
}
