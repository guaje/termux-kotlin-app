# Termux Anchor (`com.termux.anchor`)

A second APK that exists for one reason: nothing inside `com.termux` can watch itself. A package replace
kills the whole uid and cgroup (`ProcessList.killProcessGroup(uid)`), not the process tree, so `nohup`,
`setsid`, double-forking and reparenting to init all buy nothing - a `setsid` marker with `ppid=1` was
swept within 1-2 s of an install on the test handset, while the app itself was back about 5 s later. Only
another package, with its own uid, can see the death from outside.

It supervises. It does not install anything, and it does not keep sessions alive.

## Reaching it

There is no launcher icon: this is a component of Termux that lives in its own package for isolation, not an
app a user should be choosing from a drawer.

```sh
termux-anchor --open     # show the screen (grants, ledger)
termux-anchor --status   # what can be known from inside Termux, which is deliberately little
```

The activity is guarded by `com.termux.anchor.permission.CONTROL` at `protectionLevel="signature"`, so only
an APK signed with the same release key as `com.termux` can start it. The anchor's state and ledger live in
its own data directory and are not readable from Termux - that isolation is the point of the split, so
`termux-anchor --status` says "unknown from here" rather than guessing.

**The cost of hiding it**: if `com.termux` is broken or uninstalled, this screen cannot be opened. Settings
offers no "Open" button for a package without a launcher activity. What remains is uninstalling the anchor
from Settings, or `adb shell am start -n com.termux.anchor/.AnchorActivity`.

## What it needs, and why each one is load-bearing

| right | what it buys | what happens without it |
| --- | --- | --- |
| signature `CONTROL` | authentication for the heartbeat and for the service the app starts | the app cannot reach the anchor at all |
| `SYSTEM_ALERT_WINDOW` (user grantable) | a visible non-app window, which is the exemption that lets a *backgrounded* anchor ask for the foreground | restart requests are dropped by the platform, silently; the notification and its tap still work |
| ignore battery optimizations (user grantable) | this package may start itself after a reboot | the vendor battery care filters the self-start, and supervision waits for Termux to come up and revive it |

Grant the first from inside Termux, the others on the anchor's own screen.

## Measured behaviour (motorola razr fold 2026, Android 16)

- Restart from the background, overlay right granted: killed 11:31:02, Termux running again 11:31:32 (**30 s**),
  zero `Background activity launch blocked` lines; the rescue window is dismissed by the next heartbeat.
- Same test with the right **revoked** (the control): nothing returned within 60 s, the block line appeared
  once. The grant is what changes the outcome.
- Untouched after a reboot: supervision was live 95 s after `sys.boot_completed` - the app's beat started the
  service, because the anchor's own `BOOT_COMPLETED` self-start is filtered by `MotoBatteryCareService:
  isBatteryCareAllowedSelfStart restricted com.termux.anchor by mode`. Killing the app then recovered it in
  **45 s** with no intervention.
- After the anchor's own package replace: the anchor cannot restart its own service (every start from its
  heartbeat receiver is refused), and it is **Termux's beat** that revives it, measured 22 s after the app came
  up. That is also why the app, not the anchor, is the one that keeps supervision alive.

## What it cannot do

tmux and any agent attached to it live in the `com.termux` uid and die with it. This is recovery of the app,
not of what was running inside it. Keeping sessions alive across a replace means running them outside that
uid, which on a non-rooted handset means a second full environment - its own `$PREFIX`, its own copies of the
harnesses, their credentials - in another package. That duplication is the reason it is not on by default,
and the reason "resume, not survive" is the current answer: the anchor proves the app came back, and
`termux-reload --scope sessions` is what puts the user back in front of their work.

The two candidate designs for real survival, and why neither is here:

- **Sessions in the anchor package.** A second `$PREFIX`, a second copy of every tool, and a second copy of
  every harness credential, with the user choosing which environment a session belongs to and keeping two
  sets of everything current. It works, and it is a different product.
- **Sessions in the adb shell uid.** Attractive because nothing is duplicated and `com.termux` replacement
  cannot touch that uid, but the sessions then belong to a debug bridge: they die with USB or wireless
  debugging, they cannot see `$PREFIX` or the user's files, they vanish on reboot, and everything in them
  runs in a context the OEM is free to restrict at any moment. Good for a probe, not for a place to keep work.

If sessions must survive, the honest answer today is a host this replace cannot reach - another machine over
ssh, or a container the phone only dials into - and the anchor's job stays what it is: the app comes back,
and the user's sessions are there to be resumed.
