# Background reliability

Android background limits can stop terminal sessions even while Termux is using a foreground service. The **Settings > Termux > Background reliability** screen shows the two platform controls that most directly affect long-running sessions and provides opt-in recovery behavior.

## Battery optimization and Doze

A foreground service makes Termux visible to Android and lowers the chance that it will be reclaimed, but it is not an absolute keep-alive guarantee. When Termux is subject to battery optimization, Doze and app standby can defer work and restrict background activity while the screen is off. Android may eventually stop the app process and its sessions.

The battery-optimization exemption is an explicit user choice. Termux never opens the modal exemption request merely because its service started or automatically reacquired a wake lock. Use the **Battery optimization exemption** row to request or review the public Android setting.

A wake lock keeps the CPU awake; it does not itself exempt Termux from battery optimization and cannot override other platform process limits.

## Phantom processes

Android 12 and later track app child processes that are not directly represented by an Android component as phantom processes. Terminal shells, their child jobs, background tasks, and plugin commands can contribute to this accounting.

`max_phantom_processes` is a **device-wide** limit. Processes from other apps contribute, so the count that Termux can obtain for its own UID is only a lower bound on total use. Termux itself commonly uses roughly 2–3 processes per active session before additional commands and jobs are considered. Detection is informational only: Termux does not kill, trim, or refuse to start processes based on the detected value.

A regular app install often cannot read the currently enforced limit because that query requires privileged access. Termux reads the values in this order and uses the first one that is set:

1. the `max_phantom_processes` value in `Settings.Global`, which is the value the recovery command below writes, so the warning disappears once the user applied it;
2. the `device_config_activity_manager_max_phantom_processes` copy that `DeviceConfig` keeps in the same table;
3. the currently enforced value from `dumpsys activity settings`, which needs the privileged `DUMP` and `PACKAGE_USAGE_STATS` permissions and runs a shell, so it is only asked for when the two settings above say nothing.

Whether phantom processes are accounted for at all is decided before any of that is read: devices older than Android 12, and devices where phantom-process monitoring was switched off, are reported as unknown and never warn.

When nothing can be read, Termux assumes the Android Open Source Project default of 32, because an unreadable limit does not mean that phantom processes are unlimited. A read limit is remembered for a few minutes so that the warning does not ask for it again on every check; opening the **Background reliability** screen reads it again immediately.

## Settings rows

### Service

- **Remember wake lock** — records whether the Termux service should hold its CPU and Wi-Fi locks. When enabled, the choice is remembered and the locks are reacquired after service restarts. A wake lock can increase battery use.
- **Restart sessions after app process death** — stores enough session launch information to recreate eligible terminal sessions after the app process is killed. This is off by default. The snapshot is written when a session is created, renamed or closed and whenever the service is started, so enabling this while sessions are already running captures them at the next of those moments.

### Warnings

- **Battery optimization warning** — enables or disables the notification shown when Termux is not exempt from battery optimization. Swiping the warning away dismisses that warning persistently.
- **Battery optimization exemption** — shows the current exemption state. Tap it to request the exemption or open the public Android settings screen.
- **Phantom-process warning** — enables or disables warnings for a low device-wide limit or when Termux's own process count is near the readable limit. Only devices where no limit applies at all are reported as unknown and stay silent.
- **Phantom-process limit** — shows the readable device-wide limit and Termux UID count. Tap it to copy the recommended ADB command.

## Recovery commands

Run these commands from a computer with [ADB](https://developer.android.com/tools/adb) connected to the device.

Exclude the standard Termux package from device-idle battery optimization:

```shell
adb shell dumpsys deviceidle whitelist +com.termux
```

Increase the device-wide phantom-process limit to a high but bounded value:

```shell
adb shell settings put global max_phantom_processes 32768
```

As a last resort, disable phantom-process monitoring:

```shell
adb shell settings put global settings_enable_monitor_phantom_procs false
```

**Warning:** the last command disables a platform safety limit. Prefer increasing the limit instead. Platform settings can reset after an OS update or reboot, and availability can vary by Android version.

## When the service stops by itself

The Termux service stops when nothing is left for it to do: no terminal session, no background task, no plugin command waiting for a result, no wake lock wanted by the user and no session restore in progress. That is checked whenever any of those change, when the wake locks are released, when a plugin command could not be started at all, and when Android restarted the service without an intent. It is deliberately not checked when the app is opened, since the activity creates its first session a moment after starting the service and stopping in between would take the service out of foreground mode while the app is in front of the user.

Only an explicit exit of Termux kills the terminal sessions. A service that is destroyed for any other reason leaves the shells of the user running, keeps the ssh agent and `$PREFIX/var/tmp` intact, and answers plugin commands that are still waiting for a result with a cancelled result so that the plugin does not hang. When the service starts again it adopts the sessions that are still alive, so their input, output and closing keep working.

## Restored sessions are restarted, not resumed

Session restoration does not checkpoint a process. After Termux's app process and terminal processes have been killed, an eligible session is launched again from its saved executable, arguments, working directory, and name.

Consequences:

- terminal scrollback is not restored;
- running programs and jobs are gone;
- shell startup files such as `.profile` run again;
- background tasks, `termux-*` plugin commands, and failsafe sessions are never restored;
- restoration is off by default; and
- each restored session typically costs roughly 2–3 processes against a device-wide limit, plus any programs launched by the restored shell.

Do not enable restoration for shell startup files that are unsafe to execute again after an unexpected process death.

## Remembered wake lock

The wake-lock toggle is now persisted separately from the lifetime of the service object. If the service is torn down without an explicit user exit, Android releases the old lock with that process. On the next service start, Termux enters foreground mode first and then reacquires the CPU and Wi-Fi locks when the remembered setting is enabled. Toggling the lock off, from the notification or from the settings screen, updates the remembered choice, even when no lock was held at that moment.
