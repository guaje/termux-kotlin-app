package com.termux.app.supervision

/**
 * Every name that crosses the boundary between the two APKs, in one place.
 *
 * These names are the signal contract, kept out of any one implementation so that the app publishing
 * module into its classpath for five strings - so `app/src/main/kotlin/com/termux/app/devkit/
 * DevKitHeartbeat.kt` repeats them as literals and points back here. This is the file to change first
 * when one of them moves, and the reason the literals on the other side carry a pointer instead of a
 * second definition.
 */
object SupervisionSignals {

    /** The app being supervised. Named by the `<queries>` block in the supervisor's manifest. */
    const val TERMUX_PACKAGE: String = "com.termux"

    /**
     * The terminal activity by name, for the restart route. A server that keeps its activity but drops the
     * LAUNCHER category - which is what a headless build does - has no launch intent at all, so the component
     * has to be tried first or supervision would quietly stop being possible.
     */
    const val TERMUX_ACTIVITY_CLASS: String = "com.termux.app.TermuxActivity"

    /** This package. Hardcoded rather than read from Context, so that a variant cannot drift. */
    const val ANCHOR_PACKAGE: String = "com.termux.app.supervision"

    /** Signature-level permission the sender must hold; see its declaration in the manifest. */
    const val PERMISSION_CONTROL: String = "com.termux.app.supervision.permission.CONTROL"

    /** "This app is alive right now." Sent by the app, received by [HeartbeatReceiver]. */
    const val ACTION_HEARTBEAT: String = "com.termux.app.supervision.HEARTBEAT"

    /** long: the app's versionCode, so the supervisor can say which build it last saw. */
    const val EXTRA_VERSION_CODE: String = "com.termux.app.supervision.extra.VERSION_CODE"

    /** string: /proc/sys/kernel/random/boot_id of the device the heartbeat came from. */
    const val EXTRA_BOOT_ID: String = "com.termux.app.supervision.extra.BOOT_ID"

    /** int: the app's answer to "may you restart me", see [SupervisionPolicy.RestartPolicy]. */
    const val EXTRA_POLICY: String = "com.termux.app.supervision.extra.POLICY"

    /** True on the one beat that accompanies a stop the user asked for. Absent means "still running". */
    const val EXTRA_USER_STOPPED: String = "com.termux.app.supervision.extra.USER_STOPPED"
}
