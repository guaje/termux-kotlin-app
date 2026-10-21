package com.termux.anchor

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.util.TypedValue
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/**
 * The anchor's entire user interface: what it last saw, how many times it has tried since, and the
 * switch that decides whether it may try at all.
 *
 * Built in code, with no layout XML and no viewBinding - not as a stylistic preference but because the
 * module owns almost no resources at all: `res/values/strings.xml` exists only because a manifest cannot
 * put a literal in `android:label` or `android:description`, and everything a user reads is drawn here.
 * A layout file would be the first thing to argue for `android.defaults.buildfeatures.resvalues`, which
 * gradle.properties switches off repository-wide, for a screen this small.
 *
 * Opening this screen is also one of three things that start the supervisor loop - the others are
 * BOOT_COMPLETED and a heartbeat arriving - and it is the one the user controls. That matters at the very
 * start: an installed-but-never-opened anchor is in the stopped state, where Android delivers no
 * custom-action broadcasts at all, so the app can be beating loudly and nothing hears it. Measured on the
 * handset: heartbeats went unsent-to-anyone until the anchor was launched once, and state appeared within
 * one beat of the first launch (MEMORY.md §17).
 */
class AnchorActivity : AppCompatActivity() {

    private lateinit var state: AnchorState
    private lateinit var status: TextView
    private lateinit var overlayCapability: TextView
    private lateinit var batteryCapability: TextView

    /**
     * The outcome of the last thing the user asked this screen to do. Kept in the view rather than in a
     * toast, because a toast about a launch that may or may not have happened is easy to miss and this is
     * the one place where the difference between "requested" and "happened" has to be legible.
     */
    private var lastAction: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        state = AnchorState(this)
        status = TextView(this).apply { setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f) }
        overlayCapability = TextView(this).apply { setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f) }
        batteryCapability = TextView(this).apply { setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f) }
        setContentView(screen())
        startSupervisorLoop()
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun screen(): ScrollView {
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (16 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding, padding, padding)
            addView(status)
            addView(supervisionSwitch())
            addView(overlayCapabilityRow())
            addView(batteryCapabilityRow())
            addView(resumeButton())
        }
        return ScrollView(this).apply { addView(column) }
    }

    private fun supervisionSwitch(): CheckBox = CheckBox(this).apply {
        text = "Restart Termux when it stops reporting"
        isChecked = state.supervisionEnabled
        setOnCheckedChangeListener { _, checked ->
            state.setSupervisionEnabled(checked)
            render()
        }
    }

    /**
     * Explain the exact trade-off next to the Settings route: the grant enables the visible-window
     * route, while refusing it still leaves detection and the user-initiated notification tap intact.
     */
    private fun overlayCapabilityRow(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(overlayCapability)
        addView(Button(context).apply {
            text = "Manage display over other apps"
            setOnClickListener { openOverlaySettings() }
        })
    }

    /** OEM Settings builds do not all expose the dedicated action, so app details remains the fallback. */
    private fun openOverlaySettings() {
        val packageUri = Uri.parse("package:$packageName")
        val openedOverlayPage = runCatching {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, packageUri))
        }.isSuccess
        if (openedOverlayPage) return

        runCatching {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri))
        }.onFailure { failure ->
            lastAction = "Settings could not open this app's details: ${failure.message}"
            render()
        }
    }

    /**
     * Whether this package may skip battery optimization, offered because it was measured to matter: the
     * vendor's battery care filters this app's start at boot ("isBatteryCareAllowedSelfStart restricted
     * com.termux.anchor by mode") while com.termux is on the doze whitelist. Granting it is the user's
     * call, and the anchor's behaviour is unchanged either way apart from *who* starts it after a reboot.
     */
    private fun batteryCapabilityRow(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(batteryCapability)
        addView(Button(context).apply {
            text = "Ask to ignore battery optimization"
            setOnClickListener {
                lastAction = runCatching {
                    startActivity(
                        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                            Uri.parse("package:$packageName"))
                    )
                }.recoverCatching {
                    // Not every build implements the direct request; the list screen is the same decision
                    // with one more tap, and going to the app's details page is the last way to offer it.
                    startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                }.exceptionOrNull()?.let { failure ->
                    "the battery optimization screen could not be opened: ${failure.message}"
                } ?: "the system was asked; come back here to see whether it said yes"
                render()
            }
        })
    }

    private fun resumeButton(): Button = Button(this).apply {
        text = "Resume Termux now"
        setOnClickListener {
            // From an activity in the foreground, with the user's tap behind it, this is the one path on
            // which the platform is not allowed to drop the start. The supervisor service cannot say the
            // same about its own attempt - see AnchorSupervisorService.requestTermuxForeground(), which
            // is why the sentence below says "requested" and not "done".
            lastAction = runCatching { launchTermux() }.exceptionOrNull()?.let { failure ->
                "the launch request was refused: ${failure.message}"
            } ?: "launch request handed over; the next heartbeat is what proves Termux came back"
            render()
        }
    }

    private fun launchTermux() {
        val launch = requireNotNull(packageManager.getLaunchIntentForPackage(AnchorMessages.TERMUX_PACKAGE)) {
            "no launch intent for ${AnchorMessages.TERMUX_PACKAGE}"
        }
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(launch)
    }

    private fun startSupervisorLoop() {
        val loop = Intent(this, AnchorSupervisorService::class.java)
        // Opening this screen puts the package in the foreground, which is the state the platform lets
        // start a foreground service, so a refusal here means something is wrong with the ROM rather
        // than with this call. It stays non-fatal either way: the status text below is read from disk,
        // and the loop is started by the next heartbeat too.
        runCatching { ContextCompat.startForegroundService(this, loop) }
    }

    private fun render() {
        renderOverlayCapability()
        renderBatteryCapability()
        val snapshot = state.read()
        val bootId = AnchorState.readBootId()
        val ageSeconds = (System.currentTimeMillis() - snapshot.lastHeartbeatMs) / 1000
        status.text = buildString {
            appendLine(AnchorMessages.ANCHOR_PACKAGE)
            lastAction?.let { appendLine(it) }
            appendLine("Last heartbeat: " + if (snapshot.lastHeartbeatMs == 0L) {
                "never - nothing has been reported to this anchor yet"
            } else {
                "${ageSeconds}s ago"
            })
            appendLine("Version code seen: ${snapshot.lastVersionCode}")
            appendLine("Restart attempts since it: ${snapshot.attempts}")
            appendLine("Supervision: " + if (state.supervisionEnabled) "on" else "off")
            appendLine("Boot here / in heartbeat: ${bootId.take(8)} / ${snapshot.bootId.take(8)}")
            appendLine("Reporter must hold: ${AnchorMessages.PERMISSION_CONTROL}")
            appendLine("")
            state.lastLedgerLines(LEDGER_LINES_SHOWN).forEach { appendLine(it) }
        }
    }

    /** Read on every resume because returning from Settings is when this user-controlled fact changes. */
    private fun renderOverlayCapability() {
        overlayCapability.text = if (Settings.canDrawOverlays(this)) {
            "Display over other apps: allowed. A visible rescue window can make a background restart " +
                "request eligible. Only a later heartbeat can show whether Termux returned."
        } else {
            "Display over other apps: not allowed. Background restart requests are expected to be " +
                "dropped; the anchor can still notify you, and the notification tap still works because " +
                "your tap is exempt."
        }
    }

    /** Read on every resume, for the same reason as the overlay right: the answer changes in Settings. */
    private fun renderBatteryCapability() {
        val exempt = runCatching {
            (getSystemService(POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(packageName)
        }.getOrDefault(false)
        batteryCapability.text = if (exempt) {
            "Ignore battery optimization: allowed. This package may start itself after a reboot instead " +
                "of waiting for Termux to come up and revive it. Neither state changes anything else."
        } else {
            "Ignore battery optimization: not allowed, and on this handset the vendor battery care does " +
                "filter this app's start at boot. Supervision still returns once Termux is running, so " +
                "this is about who starts the anchor, not about whether it works."
        }
    }

    private companion object {
        /** Enough history to see the last thing that happened, and no more: the lines are raw JSON. */
        private const val LEDGER_LINES_SHOWN = 5
    }
}
