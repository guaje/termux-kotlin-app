# termux-kotlin (project name: Termux Anchor)

A headless terminal **server** that outlives the app that shows it. One repo today; a second, separate **client**
repo is planned. This file is the domain language both sides use. New terms go here before they appear in a
commit message; `PLAN.md` argues, this file names.

## Language

### The two programs

**Server**:
This repo's APK, `com.termux`. Hosts **sessions**, owns `$PREFIX`, the feed, tmux and every file descriptor.
Expected to change rarely, because replacing it means replacing a running process.
_Avoid_: backend, host app, daemon, Termux proper

**Client** (planned, working name **Shelm**):
A separate APK with its own applicationId and signing identity that lists, opens and renders **sessions** over
the **transport**. Expected to change constantly, because that is the part the user looks at.
_Avoid_: frontend, UI, app (ambiguous with **server**), GUI

**Viewer**:
The one **session** a **client** is currently rendering, plus its input path. A client can hold many sessions
attached and one viewer.
_Avoid_: terminal window, pane, tab (a tab is a client-side label over a session)

**Supervisor**:
Whatever decides whether the **server** should be running when it is not. Today: nothing (see **supervision
gap**). Next: the **client**, through a bound lifecycle and `linkToDeath`.
_Avoid_: watchdog, monitor, keeper, **anchor** (retired, see Flagged ambiguities)

### Sessions and what renders them

**Session**:
One PTY plus the process tree hanging off it, created and owned by the **server**. Always qualified when there is
any chance of confusion: *shell session*, *tmux session*, *TerminalSession*.
_Avoid_: terminal, task, connection

**Cell grid**:
The rectangular model of characters and attributes a terminal emulator maintains — what is *on screen*, as
opposed to the bytes that produced it.
_Avoid_: buffer, framebuffer, screen dump

**Byte stream**:
The raw PTY output, escape sequences intact. One of the two candidate **transport payloads**; choosing it makes
the **client** a second emulator.
_Avoid_: raw mode, passthrough

**Transport**:
The channel carrying one session's bytes or **cell grid** from server to client: an AIDL call that hands over a
`ParcelFileDescriptor` socketpair, authenticated by the binder call that carried it.
_Avoid_: pipe, socket layer, IPC

**Fidelity**:
Whether the **client** shows what the **server** believes is on screen — vim, tmux, true colour, wide glyphs, IME
composition included. Measured by the **fidelity gate**, never asserted.
_Avoid_: accuracy, correctness, "works"

**Backpressure**:
The condition where a slow viewer makes its producer wait. It must never cross the **transport** into the PTY
reader: a stalled viewer may cost pixels, never the session (`TerminalSession` currently kills the session when
its 4 KiB queue fills).
_Avoid_: flow control, throttling (throttling is deliberate; backpressure is accidental and lethal)

**Attach / Detach**:
A **client** taking over or releasing the output of an existing session. Detach is not death: the session keeps
running with no viewer.
_Avoid_: connect, login, open (open means "create or attach", which is exactly the ambiguity)

### The prefix and the feed

**$PREFIX**:
`/data/data/com.termux/files/usr` — the installed userspace. Its existence is what makes an install usable;
it lives inside the app's data directory, so uninstalling deletes it. The reason the package name is frozen.
_Avoid_: rootfs, sysroot, environment

**Bootstrap**:
Unpacking a first userspace into an empty **$PREFIX**. Requires a visible activity today (the **bootstrap
seam**), which is the main obstacle to a headless server.
_Avoid_: install (means the APK), init, first run

**Feed**:
The apt repository this project publishes (the `gh-pages` branch). Shipping a feature through the feed does not
restart a running process, which is the whole architecture thesis; verified, not assumed (see P0b).
_Avoid_: repo (the git repo), store, catalog

**Ship**:
Replacing the installed APK from inside the running app, through the platform installer, with the gates below and
a **recovery** route recorded first. Verb and noun.
_Avoid_: deploy, push, hot reload, sideload

**Preflight gate**:
A check that must pass before a **ship** begins. A gate that cannot be evaluated is a failed gate, never a passed
one.
_Avoid_: validation, safety check, pre-hook

**Ledger**:
Append-only JSONL history of ship and supervision decisions, one line each, so a verdict can be re-derived after
the fact instead of re-argued from memory.
_Avoid_: log (logs are expendable; a ledger is read back), journal, audit trail

**Host facts**:
`$PREFIX/var/lib/termux-kotlin/devkit/host.json` — what the installed app actually is (version code, signer
fingerprint, abi, boot id), written on **service start**. Needed because a script in $PREFIX cannot ask the
package manager anything.
_Avoid_: state file, metadata, status

**Goodbye (user stop)**:
The marker the **server** writes when it stops because the user closed it: boot id plus timestamp, deleted by the
next service start. It exists because absence is not evidence — silence looks the same whether the user typed
`exit` or the ROM killed a shell.
_Avoid_: heartbeat (retired, and the opposite: a beat proves presence, a goodbye explains an absence), intent flag

**Recovery**:
The route back to a working app after a **ship** that did not work, reachable without a laptop where possible.
_Avoid_: rollback (a step of recovery), rescue (retired anchor word)

### Trust and identity

**Shared uid** (`sharedUserId="com.termux"`):
Two packages living in one Linux user: same files, same process group, and a signal to one reaches both. Kept on
the **server** forever, because leaving it means uninstalling, and uninstalling eats **$PREFIX**. The **client**
must never join it — that separation is what lets it update without killing anything.
_Avoid_: sandbox, same process, shared context

**Signature permission**:
A gate granted only to a package signed with the same certificate. The **transport**'s authentication: by the time
the fd arrives, the caller is already known. Requires one signing key across both apps.
_Avoid_: token, secret, auth, ACL

**Cull**:
Death caused by the platform or OEM power management rather than by a crash or the user. Indistinguishable from a
crash from outside, and therefore treated as one.
_Avoid_: force stop (a *user* action that also looks identical), kill

**Supervision gap**:
The current state: nothing watches the server, so a crash with nothing open stays a crash. Accepted deliberately
in exchange for the rule that nothing comes back unless the user opened it.
_Avoid_: downtime, outage, "the anchor will handle it"

### Building and measuring

**Probe** (`P0x`):
An experiment on the handset whose only job is to be able to prove the plan wrong. Has a stated **gate** before it
runs and a measured verdict after; a probe that cannot fail the plan was a task, not a probe.
_Avoid_: spike (no artifact expected), test (unit tests assert; probes discover)

**Gate**:
The specific observable outcome that lets a stage proceed. Absence of a gate is why some "research" quietly
becomes assumption.
_Avoid_: criteria, DoD, milestone

**Invariant**:
A property that must survive every change and is re-checked by hand on the handset after risky work: bootstrap,
live sessions across an apt upgrade, the self-install dialog, the goodbye marker.
_Avoid_: regression test (there are none on these paths), smoke test

**Dev build**:
The `dev` variant: debuggable, downgradable with `adb install -r -d`, carries the devkit endpoints. The ability to
downgrade is what makes handset experiments survivable; it is verified before an experiment, never assumed.
_Avoid_: debug build, nightly, local build

## Relationships

- A **server** hosts many **sessions**; a **client** **attach**es to any number of them and **view**s one.
- A **session** produces a **byte stream**; the two candidate **transport** payloads are that stream or a **cell grid**.
- **Backpressure** across the **transport** is a defect, not a cost.
- A **ship** passes the **preflight gates**, is recorded in the **ledger**, and carries a **recovery** route.
- A **ship** reads **host facts**; host facts are written on service start, not on request.
- A **goodbye** is written when a stop is a user action, and only then; a **supervisor** reads it before it restarts anything.
- A **cull** and a crash produce no **goodbye**, which is precisely why they still get rescued.
- The **shared uid** separates the **server**'s data from the **client**'s process; the **signature permission** joins them again at one call.
- A **probe** answers one question and can end a stage; a **gate** is the form that answer takes.

## Flagged ambiguities

- **"anchor"** named both a second APK (`com.termux.anchor`, deleted in #51) and the *project*. Resolved: the
  project is Termux Anchor; the APK no longer exists. Never write "the anchor" meaning a live component.
- **"session"** named four things: a tmux session, `TerminalSession` (library), `TermuxSession` (server wrapper),
  and a client tab. Resolved: say **session** for the PTY and qualify the rest by class name.
- **"terminal"** named the view, the emulator, the session and the app. Resolved: pick one — **viewer**, **cell
  grid** / emulator, **session**, **server**.
- **"heartbeat"** was the 15 s presence broadcast to the deleted supervisor. Retired; do not reintroduce it as a
  generic term for any periodic write.
- **"reload"** (`termux-reload`) meant restarting the app to pick up a new APK. Superseded by **ship**; the word
  survives only in the endpoint name.
- **"hot reload"** in user-facing text (and in Obtainium's vocabulary) means *update without losing the running
  sessions* — not the frontend meaning. **apt upgrade** achieves it; **ship** does not, and that difference is the
  architecture.
- **"restart"** meant service restart, **ship**-induced process replacement, resurrection by a supervisor, and
  device reboot. Resolved: use *stop*, *ship*, *resurrect*, *reboot* respectively.
- **"server"** and **"client"** pre-existed inside the code as `TermuxService` and its bound activity. The
  architecture meaning is cross-APK. Say `TermuxService` when you mean the class.
- **"targetSdk ceiling"** was asserted in this project for months as a known blocker. P0g measured it and the
  first failure was a PendingIntent flag, not the W^X exec ban. Treat "we can't raise targetSdk because…"
  sentences as unproven until they cite a probe.
