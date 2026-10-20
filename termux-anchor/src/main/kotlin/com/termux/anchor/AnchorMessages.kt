package com.termux.anchor

/**
 * Every name that crosses the boundary between the two APKs, in one place.
 *
 * The app cannot import this object - a dependency on `:termux-anchor` would drag a second application
 * module into its classpath for five strings - so `app/src/main/kotlin/com/termux/app/devkit/
 * DevKitHeartbeat.kt` repeats them as literals and points back here. This is the file to change first
 * when one of them moves, and the reason the literals on the other side carry a pointer instead of a
 * second definition.
 */
object AnchorMessages {

    /** The app being supervised. Named by the `<queries>` block in the anchor's manifest. */
    const val TERMUX_PACKAGE: String = "com.termux"

    /** This package. Hardcoded rather than read from Context, so that a variant cannot drift. */
    const val ANCHOR_PACKAGE: String = "com.termux.anchor"

    /** Signature-level permission the sender must hold; see its declaration in the manifest. */
    const val PERMISSION_CONTROL: String = "com.termux.anchor.permission.CONTROL"

    /** "This app is alive right now." Sent by the app, received by [HeartbeatReceiver]. */
    const val ACTION_HEARTBEAT: String = "com.termux.anchor.HEARTBEAT"

    /** long: the app's versionCode, so the anchor can say which build it last saw. */
    const val EXTRA_VERSION_CODE: String = "com.termux.anchor.extra.VERSION_CODE"

    /** string: /proc/sys/kernel/random/boot_id of the device the heartbeat came from. */
    const val EXTRA_BOOT_ID: String = "com.termux.anchor.extra.BOOT_ID"

    /** int: the app's answer to "may you restart me", see [AnchorSupervisionPolicy.RestartPolicy]. */
    const val EXTRA_POLICY: String = "com.termux.anchor.extra.POLICY"
}
