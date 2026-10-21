package com.termux.anchor

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat

/**
 * The loop: read what is on record, ask [AnchorSupervisionPolicy] what to do, do it.
 *
 * Everything about this class is deliberately weak, because that is what makes it usable. It holds only
 * transient display state, it re-reads the file on every tick so a heartbeat that arrived while it was
 * asleep is picked up, and it never decides anything itself - the
 * rules live in [AnchorSupervisionPolicy] where they are tested.
 *
 * It is a foreground service with a permanent notification because the alternative is worse: a service
 * with no notification is the first thing a vendor's sleep policy reaps, and a supervisor reaped at
 * 02:00 is not supervising the thing it exists for. The channel is IMPORTANCE_LOW and soundless, so
 * this costs the user one line in the shade, once.
 */
class AnchorSupervisorService : Service() {

    private val handler = Handler(Looper.getMainLooper())

    /** The last sentence shown, so that an unchanged tick does not re-post a notification. */
    private var shownText: String? = null

    /** The last reason written to the ledger, so that the ledger holds transitions rather than ticks. */
    private var writtenReason: String? = null

    /** The rescue window currently shown, including the facts that bound its short lifetime. */
    private var rescueOverlay: RescueOverlay? = null

    /**
     * Set after every unanswered launch request and cleared when a heartbeat from this boot arrives.
     * While set, the notification is the one the user can act on, because a start the platform will not
     * perform on our behalf is still a start it will perform on the user's.
     */
    private var needsUserRescue = false

    private val tick = object : Runnable {
        override fun run() {
            supervise()
            handler.postDelayed(this, AnchorSupervisionPolicy.CHECK_INTERVAL_MS)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createChannel()
        startForegroundNotification()
        handler.removeCallbacks(tick)
        handler.post(tick)
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        removeRescueOverlay()
        super.onDestroy()
    }

    /** Nothing binds to this service; it is started, and it stays started by being in foreground. */
    override fun onBind(intent: Intent?): IBinder? = null

    private fun supervise() {
        val state = AnchorState(this)
        val bootId = AnchorState.readBootId()
        val snapshot = state.read()
        val nowMs = System.currentTimeMillis()
        refreshRescueOverlay(snapshot, bootId, nowMs)
        val report = AnchorSupervisionPolicy.decide(
            snapshot.toWatch(nowMs, state.supervisionEnabled, bootId)
        )

        when (report.action) {
            AnchorSupervisionPolicy.Action.RESTART -> attemptRestart(state, snapshot, bootId, report)
            AnchorSupervisionPolicy.Action.HOLD -> hold(state, snapshot, bootId, report)
            AnchorSupervisionPolicy.Action.WAIT -> watching(snapshot, bootId)
        }
    }

    /**
     * Not acting, and saying why once.
     *
     * The reason is written to the ledger only when it changes. A HOLD repeated every fifteen seconds is
     * the same sentence about the same minute of the same problem, and a ledger that repeats it fills
     * itself out of the 500 lines that a human would actually read.
     */
    private fun hold(
        state: AnchorState,
        snapshot: AnchorState.Snapshot,
        bootId: String,
        report: AnchorSupervisionPolicy.Report
    ) {
        if (report.reason != writtenReason) {
            state.recordHold(snapshot, bootId, report.reason)
            writtenReason = report.reason
        }
        show(report.reason)
    }

    /** Nothing to act on. The notification says "watching", because that is all that is true. */
    private fun watching(snapshot: AnchorState.Snapshot, bootId: String) {
        if (snapshot.lastHeartbeatMs > 0L && bootId == snapshot.bootId) {
            // A heartbeat from this boot: whatever failed earlier has recovered, so the notification
            // stops claiming otherwise.
            needsUserRescue = false
        }
        writtenReason = null
        show(WATCHING_TEXT)
    }

    private fun attemptRestart(
        state: AnchorState,
        snapshot: AnchorState.Snapshot,
        bootId: String,
        report: AnchorSupervisionPolicy.Report
    ) {
        writtenReason = null
        val canDrawOverlays = runCatching { Settings.canDrawOverlays(this) }.getOrDefault(false)
        val method = AnchorSupervisionPolicy.rescueMethod(canDrawOverlays)
        val overlayShown = method == AnchorSupervisionPolicy.RescueMethod.OVERLAY_AND_LAUNCH &&
            showRescueOverlay(snapshot.lastHeartbeatMs)
        val methodRecord = report.reason + rescueMethodRecord(method, overlayShown)
        val outcome = requestTermuxForeground()
        // True after every attempt, not only after one that threw. BAL (measured here, razr fold 2026 /
        // Android 16: "Background activity launch blocked! goo.gle/android-bal ...
        // callingUidHasVisibleActivity: false, callingUidProcState: FOREGROUND_SERVICE,
        // resultIfPiCreatorAllowsBal: BAL_BLOCK") refuses this start inside ActivityTaskManager, so
        // nothing reaches this call site as an exception: with `outcome.isFailure` as the only trigger the
        // notification kept its calm text while Termux stayed dead and the user was never offered the tap
        // that would have worked. An unanswered attempt is the state in which a human is needed, and the
        // next heartbeat from this boot clears the flag in [watching].
        needsUserRescue = true

        // Written either way, and worded to what actually happened. The attempt counter in the state is
        // what the backoff ladder is read from, and a launch the platform refused is an attempt in the
        // only sense the ladder cares about: it cost the device a wake and it produced no heartbeat.
        val record = outcome.fold(
            { methodRecord + "; the launch request was handed over, which is not evidence that an " +
                "activity appeared - only the next heartbeat will show that" },
            { failure ->
                methodRecord + "; the launch request was refused: ${failure.javaClass.simpleName}: " +
                    failure.message
            }
        )
        outcome.exceptionOrNull()?.let {
            Log.w(TAG, "the launch request for ${AnchorMessages.TERMUX_PACKAGE} was refused", it)
        }
        state.recordRestartAttempt(snapshot, bootId, record)
        show(record)
    }

    /** State the route that was actually available without treating the launch request as recovery. */
    private fun rescueMethodRecord(
        method: AnchorSupervisionPolicy.RescueMethod,
        overlayShown: Boolean
    ): String = when {
        method == AnchorSupervisionPolicy.RescueMethod.LAUNCH_ONLY ->
            "; no overlay right, so the launch was attempted blind and is expected to be dropped unless " +
                "this app happens to be on screen"
        overlayShown ->
            "; asked the platform for the foreground, with an overlay window up first (the visible window " +
                "is what makes the launch legal from the background)"
        else ->
            "; the overlay right was held, but the window could not be shown; the launch was attempted " +
                "without a visible non-app window"
    }

    /**
     * Put up the one window this permission is for before asking the platform for the foreground.
     *
     * The permission can be revoked between [Settings.canDrawOverlays] and `addView`, and window manager
     * can reject a request for reasons this process cannot inspect. Both were kept non-fatal because the
     * blind launch and the notification tap are still useful; the warning and ledger route preserve the
     * distinction instead of making a failed overlay look present.
     */
    private fun showRescueOverlay(lastHeartbeatMs: Long): Boolean {
        if (rescueOverlay != null) return true
        return runCatching {
            val shownAtMs = System.currentTimeMillis()
            val view = buildRescueOverlay()
            windowManager().addView(view, rescueOverlayLayoutParams())
            rescueOverlay = RescueOverlay(view, shownAtMs, lastHeartbeatMs)
        }.onFailure {
            Log.w(TAG, "the rescue overlay could not be shown", it)
        }.isSuccess
    }

    /** A full-width strip is enough to be visible to Android and enough to give the user a rescue tap. */
    private fun buildRescueOverlay(): View {
        val padding = (12 * resources.displayMetrics.density).toInt()
        return FrameLayout(this).apply {
            setBackgroundColor(Color.argb(230, 48, 48, 48))
            setPadding(padding, padding, padding, padding)
            addView(TextView(context).apply {
                text = "Termux stopped reporting. Tap to ask Android to open it."
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            })
            setOnClickListener {
                val outcome = requestTermuxForeground()
                outcome.exceptionOrNull()?.let { failure ->
                    Log.w(TAG, "the tapped rescue request for ${AnchorMessages.TERMUX_PACKAGE} was refused", failure)
                }
                removeRescueOverlay()
            }
        }
    }

    /** The old alert type is only used on Android 7, below TYPE_APPLICATION_OVERLAY's API level. */
    private fun rescueOverlayLayoutParams(): WindowManager.LayoutParams = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            // minSdk 24 still needs the deprecated alert type; newer releases reject it in favour of
            // TYPE_APPLICATION_OVERLAY, so the old constant cannot escape this version branch.
            WindowManager.LayoutParams.TYPE_SYSTEM_ALERT
        },
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP
    }

    /** A heartbeat from this boot or an expired lifetime makes the rescue window no longer justified. */
    private fun refreshRescueOverlay(snapshot: AnchorState.Snapshot, bootId: String, nowMs: Long) {
        val overlay = rescueOverlay ?: return
        val lastHeartbeatMs = if (snapshot.bootId == bootId) {
            snapshot.lastHeartbeatMs
        } else {
            overlay.lastHeartbeatAtShowMs
        }
        val wanted = AnchorSupervisionPolicy.overlayStillWanted(
            nowMs,
            overlay.shownAtMs,
            lastHeartbeatMs,
            overlay.lastHeartbeatAtShowMs
        )
        if (!wanted) removeRescueOverlay()
    }

    /** Removal is best effort because a dead window token must not take down the foreground service. */
    private fun removeRescueOverlay() {
        val overlay = rescueOverlay ?: return
        rescueOverlay = null
        runCatching { windowManager().removeView(overlay.view) }
            .onFailure { Log.w(TAG, "the rescue overlay could not be removed", it) }
    }

    private fun windowManager(): WindowManager = getSystemService(WINDOW_SERVICE) as WindowManager

    /**
     * Hand the platform an intent to bring Termux to the foreground.
     *
     * READ THIS BEFORE TRUSTING A SUCCESSFUL RETURN, AND BEFORE WRITING A STATUS LINE ABOUT ONE. What
     * this can prove is that `startActivity` did not throw, i.e. that the intent was accepted by
     * ActivityTaskManager. It cannot prove that an activity was started, that a process was spawned, or
     * that the user saw anything: the platform is allowed to drop a background activity start, and since
     * Android 10 it does exactly that by default for an app with no visible window, silently, with no
     * exception and no line in the caller's log. The investigation this module came out of lost two
     * sessions to a status line that read "startActivity succeeded, Termux is back"; the sentence was
     * true, the conclusion drawn from it was not, and everything downstream trusted it.
     *
     * So: success here means "requested". The only proof of recovery the anchor accepts is the next
     * heartbeat, and the notification says as much while it waits for one.
     *
     * MEASURED ON THE HANDSET, TWICE, WITH OPPOSITE OUTCOMES - do not read a quiet log as success:
     * - anchor in the foreground (its own activity visible): force-stop at 12:36:55, launch at the 45 s
     *   window, Termux running again at 12:37:32 with topResumedActivity=com.termux/.app.TermuxActivity.
     * - anchor in the background, two hours later, screen awake, launcher on top: three attempts at the
     *   45/90/180 s rungs, every one of them blocked, Termux never came back, and the only thing either
     *   process logged was the platform line quoted in [attemptRestart].
     * The difference is the caller's window state, not the intent, and it is the whole restriction on this
     * design: a background foreground-service may not start an activity. The paths that still work are the
     * notification's tap-through, which is a user action and therefore exempt, and a visible non-app
     * window. The latter is the route used by the overlay attempt here, and it was settled by measurement
     * rather than inferred from the refusal's fields:
     * - overlay right granted, launcher on top: killed 11:31:02, Termux running again 11:31:32 (30 s), and
     *   `Background activity launch blocked` appeared zero times afterwards.
     * - right revoked, same procedure: nothing came back within 60 s and the block line appeared once. The
     *   grant is what changed the outcome, not the passage of time.
     * - after a full reboot, same test: killed 11:37:43, back 11:38:29 (46 s), zero blocks.
     * What is still not established is whether a window of *this* shape stays eligible indefinitely, since
     * the overlay is dismissed as soon as a heartbeat returns - the exemption is only needed for the moment
     * of the launch request, which is how it is used.
     */
    private fun requestTermuxForeground(): Result<Unit> = runCatching {
        val launch = packageManager.getLaunchIntentForPackage(AnchorMessages.TERMUX_PACKAGE)
            ?: error("no launch intent for ${AnchorMessages.TERMUX_PACKAGE}; it is not installed, or not " +
                "visible through this package's <queries> block")
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(launch)
    }

    private fun show(reason: String) {
        val text = if (needsUserRescue) rescueNotificationText() else reason
        if (text == shownText) return
        shownText = text
        notificationManager().notify(NOTIFICATION_ID, buildNotification(text))
    }

    /** Without the overlay right, the notification must say plainly that only the user's tap is reliable. */
    private fun rescueNotificationText(): String = if (Settings.canDrawOverlays(this)) {
        RESCUE_TEXT
    } else {
        NO_OVERLAY_RESCUE_TEXT
    }

    private fun startForegroundNotification() {
        val notification = buildNotification(WATCHING_TEXT)
        shownText = WATCHING_TEXT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(text: String): Notification {
        val content = Intent(this, AnchorActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val tapThrough = PendingIntent.getActivity(
            this,
            PENDING_LAUNCH_ANCHOR,
            content,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val rescue = if (needsUserRescue) termuxLaunchPendingIntent() else null

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Termux anchor")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            // The system icon rather than a drawable of our own: this module carries no resources, and
            // an icon is not worth a res/ directory that then has to be kept consistent with a theme.
            .setSmallIcon(if (needsUserRescue) android.R.drawable.stat_notify_error else android.R.drawable.ic_menu_manage)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            // A refused launch goes to Termux rather than to this screen: the user's own tap is a
            // user-initiated activity start, which the platform does not drop, so it is the one path
            // that still works when ours does not.
            .setContentIntent(rescue ?: tapThrough)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    /**
     * The tap-to-rescue intent. Same launch request as [requestTermuxForeground], delivered by the
     * system on the user's behalf instead of by this process, which is precisely why it is kept: a
     * PendingIntent fired from a notification tap is not a background activity start.
     */
    private fun termuxLaunchPendingIntent(): PendingIntent? {
        val launch = packageManager.getLaunchIntentForPackage(AnchorMessages.TERMUX_PACKAGE) ?: return null
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return PendingIntent.getActivity(
            this,
            PENDING_LAUNCH_TERMUX,
            launch,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW).apply {
            description = "Whether Termux is still reporting that it is alive"
            setShowBadge(false)
            // IMPORTANCE_LOW means no sound and no vibration already; this is here so that nobody adds
            // a sound attribute to the channel later and has to decide what it was for.
            setSound(null, null)
        }
        notificationManager().createNotificationChannel(channel)
    }

    private fun notificationManager(): NotificationManager =
        getSystemService(NotificationManager::class.java)

    companion object {
        private const val TAG = "AnchorSupervisor"

        private const val CHANNEL_ID = "termux-anchor-supervision"
        private const val CHANNEL_NAME = "Termux supervision"

        private const val NOTIFICATION_ID = 1
        private const val PENDING_LAUNCH_ANCHOR = 1
        private const val PENDING_LAUNCH_TERMUX = 2

        private const val WATCHING_TEXT = "watching for heartbeats from com.termux"

        /** What the user is asked to do when an overlay-backed request is still unanswered. */
        private const val RESCUE_TEXT = "Termux stopped responding — tap to resume"

        /** The missing right and the still-working user route, without promising that either already ran. */
        private const val NO_OVERLAY_RESCUE_TEXT =
            "Termux stopped responding — allow display over other apps so background rescue can be " +
                "attempted; without it nothing comes back by itself. Tap this notification to resume Termux."
    }

    private data class RescueOverlay(
        val view: View,
        val shownAtMs: Long,
        val lastHeartbeatAtShowMs: Long
    )
}
