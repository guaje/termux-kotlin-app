package com.termux.app.core.deviceapi.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.termux.app.TermuxActivity
import com.termux.app.core.api.DeviceApiError
import com.termux.app.core.api.Result
import com.termux.app.core.deviceapi.actions.BatteryAction
import com.termux.app.core.deviceapi.actions.ClipboardAction
import com.termux.app.core.deviceapi.actions.ToastAction
import com.termux.app.core.deviceapi.actions.TorchAction
import com.termux.app.core.deviceapi.actions.VibrateAction
import com.termux.app.core.deviceapi.models.BatteryInfo
import com.termux.app.core.deviceapi.models.DeviceApiAction
import com.termux.app.core.deviceapi.models.DeviceApiMessage
import com.termux.app.core.logging.TaggedLogger
import com.termux.app.core.logging.TermuxLogger
import com.termux.app.di.ApplicationScope
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID
import javax.inject.Inject

/**
 * Background service for Device API operations.
 *
 * Provides:
 * - Execution of device API actions via explicit IPC binder
 * - Streaming sensor/location data (extensible)
 * - Event-based communication with terminal sessions
 * - Foreground service for long-running operations (Android O+)
 *
 * Architecture note: All DeviceApiAction implementations are injected via Hilt
 * and dispatched here. This centralizes permission handling, logging, and
 * structured error reporting.
 */
@AndroidEntryPoint
class DeviceApiService : Service() {

    companion object {
        private const val NOTIFICATION_CHANNEL_ID = "termux_device_api"
        private const val NOTIFICATION_ID = 1337
        const val ACTION_STOP_SERVICE = "com.termux.STOP_DEVICE_API_SERVICE"

        fun createIntent(context: Context): Intent =
            Intent(context, DeviceApiService::class.java)

        fun startService(context: Context) {
            val intent = createIntent(context)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopService(context: Context) {
            context.stopService(createIntent(context))
        }
    }

    @Inject lateinit var logger: TermuxLogger
    @Inject lateinit var batteryAction: BatteryAction
    @Inject lateinit var clipboardAction: ClipboardAction
    @Inject lateinit var vibrateAction: VibrateAction
    @Inject lateinit var toastAction: ToastAction
    @Inject lateinit var torchAction: TorchAction
    @Inject @ApplicationScope lateinit var applicationScope: CoroutineScope

    private val log: TaggedLogger by lazy { logger.forTag("DeviceApiService") }

    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
    }

    private val _events = MutableSharedFlow<DeviceApiMessage>(extraBufferCapacity = 50)
    val events: SharedFlow<DeviceApiMessage> = _events.asSharedFlow()

    private val activeStreams = mutableMapOf<String, Job>()
    private val binder = DeviceApiBinder()

    inner class DeviceApiBinder : Binder() {
        fun getService(): DeviceApiService = this@DeviceApiService
    }

    override fun onCreate() {
        super.onCreate()
        log.i("DeviceApiService created")
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP_SERVICE -> {
                log.i("Stopping service via notification action")
                stopSelf()
                return START_NOT_STICKY
            }
        }
        log.i("DeviceApiService started")
        startForeground(NOTIFICATION_ID, createNotification())
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder {
        log.d("Service bound")
        return binder
    }

    override fun onDestroy() {
        log.i("DeviceApiService destroyed")
        cancelAllStreams()
        super.onDestroy()
    }

    // ========== Action Dispatch ==========

    /**
     * Execute a device API action by enum entry.
     */
    suspend fun executeAction(
        action: DeviceApiAction,
        params: Map<String, String> = emptyMap()
    ): Result<String, DeviceApiError> {
        val requestId = UUID.randomUUID().toString()
        val startTime = System.currentTimeMillis()

        log.d("Dispatching action", mapOf("action" to action.actionName, "requestId" to requestId))

        _events.tryEmit(DeviceApiMessage.ApiRequest(
            id = requestId,
            action = action.actionName,
            parameters = params
        ))

        return try {
            val result = dispatchAction(action, params)
            when (result) {
                is Result.Success -> {
                    val data = result.data
                    val duration = System.currentTimeMillis() - startTime
                    _events.tryEmit(DeviceApiMessage.ApiResponse(
                        id = UUID.randomUUID().toString(),
                        requestId = requestId,
                        action = action.actionName,
                        data = data,
                        executionTimeMs = duration
                    ))
                    Result.success(data)
                }
                is Result.Error -> {
                    emitError(requestId, action.actionName, result.error)
                    Result.error(result.error)
                }
                is Result.Loading -> {
                    Result.error(DeviceApiError.SystemException(
                        operation = action.actionName,
                        cause = IllegalStateException("Unexpected loading state")
                    ))
                }
            }
        } catch (e: SecurityException) {
            val err = DeviceApiError.PermissionRequired(permission = "unknown", apiAction = action.actionName)
            emitError(requestId, action.actionName, err)
            Result.error(err)
        } catch (e: Exception) {
            val err = DeviceApiError.SystemException(operation = action.actionName, cause = e)
            emitError(requestId, action.actionName, err)
            Result.error(err)
        }
    }

    /**
     * Convenience: get battery status directly.
     */
    suspend fun getBatteryStatus(): Result<BatteryInfo, DeviceApiError> =
        batteryAction.execute()

    // ========== Private Dispatch ==========

    private suspend fun dispatchAction(
        action: DeviceApiAction,
        params: Map<String, String>
    ): Result<String, DeviceApiError> {
        return when (action) {
            DeviceApiAction.BATTERY_STATUS -> {
                batteryAction.execute(params).map { json.encodeToString(it) }
            }
            DeviceApiAction.CLIPBOARD_GET, DeviceApiAction.CLIPBOARD_SET -> {
                clipboardAction.execute(params)
            }
            DeviceApiAction.VIBRATE -> {
                vibrateAction.execute(params).map { json.encodeToString(true) }
            }
            DeviceApiAction.TOAST -> {
                toastAction.execute(params).map { json.encodeToString(true) }
            }
            DeviceApiAction.TORCH -> {
                torchAction.execute(params).map { json.encodeToString(it) }
            }
            else -> Result.error(DeviceApiError.FeatureNotAvailable(action.actionName))
        }
    }

    // ========== Streaming (placeholder extensibility) ==========

    fun startStream(
        action: DeviceApiAction,
        params: Map<String, String> = emptyMap()
    ): Result<String, DeviceApiError> {
        val streamId = UUID.randomUUID().toString()
        log.d("Starting stream", mapOf("action" to action.actionName, "streamId" to streamId))
        return Result.error(DeviceApiError.FeatureNotAvailable("Streaming for ${action.actionName}"))
    }

    fun stopStream(streamId: String) {
        activeStreams[streamId]?.let { job ->
            log.d("Stopping stream", mapOf("streamId" to streamId))
            job.cancel()
            activeStreams.remove(streamId)
            _events.tryEmit(DeviceApiMessage.StreamEnded(
                id = UUID.randomUUID().toString(),
                streamId = streamId,
                action = "unknown",
                reason = "stopped"
            ))
        }
    }

    private fun cancelAllStreams() {
        log.d("Cancelling all streams", mapOf("count" to activeStreams.size))
        activeStreams.values.forEach { it.cancel() }
        activeStreams.clear()
    }

    // ========== Helpers ==========

    private fun emitError(requestId: String, action: String, error: DeviceApiError) {
        _events.tryEmit(DeviceApiMessage.ApiError(
            id = UUID.randomUUID().toString(),
            requestId = requestId,
            action = action,
            errorCode = error.code,
            errorMessage = error.message
        ))
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Device API Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Running device API operations"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, TermuxActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this,
            0,
            createIntent(this).apply { action = ACTION_STOP_SERVICE },
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Termux Device API")
            .setContentText("Device API service running")
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentIntent(pendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}
