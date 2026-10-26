package com.termux.app

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.termux.shared.logger.Logger

/**
 * One place that answers "where does a terminal live right now".
 *
 * Seven call sites asked that question by writing `Intent(this, TermuxActivity::class.java)` directly, and each
 * of them was correct only while this app ships a terminal activity. When it stops shipping one they do not all
 * fail the same way, which is what makes the pattern worth collapsing: `PendingIntent.getActivity` on a class
 * that has left the package throws at notification-build time - and `TermuxService` stops itself if its
 * notification cannot be built, so an absent activity would take the sessions with it; a widget tap throws in
 * the receiver; `getLaunchIntentForPackage` answers null and the tap simply does nothing, forever, with no
 * error anywhere. R8 cannot help because the class reference is real right up until the day it is not, and
 * there are no instrumentation tests over any of this.
 *
 * So the question is asked here. The *decision* is a pure function so that the order of preference is a tested
 * fact rather than a chain of null checks someone hopes they got right.
 */
object UiEntryPoint {

    private const val LOG_TAG = "UiEntryPoint"

    enum class Route {
        /** This app still ships a terminal activity, so it is still the thing that shows a terminal. */
        SELF_ACTIVITY,

        /** This app has no terminal of its own; a client package can render its sessions. */
        CLIENT,

        /** No UI anywhere. A foreground service running sessions nobody can look at. */
        NONE
    }

    /**
     * Packages that can render this app's sessions, most preferred first.
     *
     * Empty deliberately. Naming a package that does not exist yet would make the resolver's answer depend on
     * a guess, and a wrong guess here is indistinguishable from a bug at the worst possible moment. When the
     * client ships, its applicationId is added here and nowhere else in the codebase.
     */
    private val CLIENT_PACKAGES: List<String> = emptyList()

    /** This app's own terminal activity, by name rather than by class literal so that the reference outlives the class. */
    private const val SELF_ACTIVITY_CLASS = "com.termux.app.TermuxActivity"

    /**
     * The whole policy: this app's activity wins while it exists, an installed client is next, and there is an
     * answer for neither, because "neither" is the state a headless server is designed to be able to reach.
     */
    fun routeFor(selfActivityPresent: Boolean, clientLaunchIntentPresent: Boolean): Route = when {
        selfActivityPresent -> Route.SELF_ACTIVITY
        clientLaunchIntentPresent -> Route.CLIENT
        else -> Route.NONE
    }

    /** An intent that would show a terminal, or null when nothing installed could. */
    fun terminalIntent(context: Context): Intent? {
        val self = selfActivityIntent(context)
        val client = clientLaunchIntent(context)
        return when (routeFor(self != null, client != null)) {
            Route.SELF_ACTIVITY -> self
            Route.CLIENT -> client
            Route.NONE -> null
        }?.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
    }

    /**
     * Show a terminal if anything can. Returns whether it tried.
     *
     * Callers that must not come from the background still have to check that themselves: a blocked background
     * activity launch does not reliably throw on this platform, which was measured while building the anchor
     * (MEMORY.md §17), so a `true` here means "handed to the platform", not "the user saw a terminal".
     */
    fun openTerminal(context: Context): Boolean {
        val intent = terminalIntent(context) ?: return false
        return runCatching { context.startActivity(intent) }
            .onFailure { Logger.logWarn(LOG_TAG, "could not start ${intent.component}: ${it.message}") }
            .isSuccess
    }

    /** The tap target for a notification, or null when there is nothing to open. */
    fun contentIntent(context: Context, requestCode: Int, extraFlags: Int = 0): PendingIntent? {
        val intent = terminalIntent(context) ?: return null
        return runCatching {
            PendingIntent.getActivity(context, requestCode, intent, PendingIntent.FLAG_IMMUTABLE or extraFlags)
        }.onFailure { Logger.logWarn(LOG_TAG, "no content intent for a notification: ${it.message}") }.getOrNull()
    }

    /**
     * This app's own activity, resolved by name.
     *
     * `PackageManager.NameNotFoundException` is the signal that this build no longer ships a terminal UI, which
     * is exactly the fact the resolver needs; a class literal could not express it because it would not compile
     * after the deletion.
     */
    private fun selfActivityIntent(context: Context): Intent? {
        val component = ComponentName(context.packageName, SELF_ACTIVITY_CLASS)
        return runCatching { context.packageManager.getActivityInfo(component, 0) }
            .getOrNull()?.let { Intent().setComponent(component) }
    }

    private fun clientLaunchIntent(context: Context): Intent? = CLIENT_PACKAGES.firstNotNullOfOrNull { wanted ->
        runCatching {
            context.packageManager.getLaunchIntentForPackage(wanted)?.setPackage(wanted)
        }.getOrNull()
    }

    /** Whether an activity exists in another package, for callers that have to build the intent themselves. */
    fun activityExists(packageManager: PackageManager, component: ComponentName): Boolean =
        runCatching { packageManager.getActivityInfo(component, 0) }.isSuccess
}
