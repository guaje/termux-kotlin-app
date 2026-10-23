# Hosting sessions in the anchor, with Termux as the client

> **Superseded 2026-10-22, and superseded for a good reason.** The direction that got built into `PLAN.md` is
> the inverse: the **server stays `com.termux`** and a **new thin client** (`com.termux.______`) renders its
> sessions. Two facts the recon turned up made that the cheaper design, and they are worth keeping here because
> they are also the reason the argument below was incomplete: `:terminal-view` and `:terminal-emulator` depend
> on neither `:app` nor `:termux-shared`, so a client is a library consumer rather than a fork of the app; and a
> `TerminalSession` already does not require a viewer (`TermuxTerminalSessionServiceClient` exists, and
> `TermuxService.setTermuxTerminalSessionClient()` already attaches and detaches viewers), so "the viewer may
> die" is true by construction rather than something to build. Hosting the sessions in the anchor would inherit
> this document's central problem for the material that most needs updating; hosting them in `com.termux` keeps
> `$PREFIX`, `$HOME` and every dotfile exactly where they are. The transport question, which this document got
> right to be suspicious about, is answered by AIDL plus a `ParcelFileDescriptor` over an unnamed socketpair -
> permitted between two untrusted apps by AOSP sepolicy and authenticated by the binder call itself, so no
> token and no listening port are needed.

The proposal: stop treating `com.termux.anchor` as a watchdog and make it the **session host** - tmux, the
shells, the agents - while `com.termux` becomes the terminal that attaches to them. Then replacing
`com.termux` to ship a build kills only the viewer, and an agent mid-edit can watch its terminal vanish and
come back, re-attaching to the same session.

That is a real fix for the case this branch was started for, and it is worth taking seriously: the kill a
package replace performs is **uid-scoped** (`ProcessList.killProcessGroup(uid)`, measured in MEMORY.md §16C -
a `setsid` process reparented to PID 1 was swept within 1-2 s), and the package we replace most often is
`com.termux`. Sessions in another uid are outside that sweep by construction.

## What it does not do is escape the rule

The rule is not "com.termux is fragile"; it is **a package that hosts processes cannot be replaced without
killing them**. Move the sessions to the anchor and the anchor inherits the problem - and inherits it for the
material that most needs updating: the bootstrap, tmux, node, the harnesses, the repositories, the toolchain.
So the design works only as a discipline: the host stays boring and rarely updated, and the work of
development happens in the client. That is achievable, but it is a policy someone has to keep, and the first
`apt-get upgrade tmux` inside the host is the first event that can take every session down again.

## Three things it depends on, each of which can kill it

1. **The PTY has to be owned by the host.** If the client holds the pty master, replacing the client closes
   the master and the session leader gets SIGHUP - which is precisely today's failure, relocated. Hosted
   therefore means the anchor owns the pty *and* the tmux server, and the client is a byte pipe that may die
   at any moment (the ssh/mosh model). This is the first thing to measure, not to assume.
2. **The attach channel needs authentication, and loopback provides none.** A bound loopback port or an
   abstract unix socket carries no peer identity, so any app on the device could attach and drive the
   sessions, or impersonate the host. A filesystem unix socket is not reachable across two apps' data
   directories. What does work is bootstrapping from the channel that *is* authenticated: a
   `com.termux.anchor.permission.CONTROL`-guarded binder call hands the client a per-boot random token, and
   the token gates the socket. Skipping this turns "session host" into "control endpoint for anything on the
   handset".
3. **The code and tools have to live in the host's prefix.** Shared storage is not an escape hatch: it is a
   FUSE mount that does not exec app-written files and does not carry the symlink and mode semantics that
   `node_modules` and git rely on - and on this device the app currently has no shared-storage grant at all
   (a symlink and a copy into `Download/` both returned `Permission denied`). So the repository, the
   dependency trees, the harness caches and any build toolchain move into the anchor's `$PREFIX`.

## The cost, stated plainly

The anchor stops being the small module this branch deliberately built - no NDK, no bootstrap, no
dependencies - and gains the PTY native code, the emulator library, session management, a bootstrap and its
own apt feed. Meanwhile `com.termux` loses the session and storage model that the upstream app is built
around, which is a permanent divergence to carry in a fork whose main asset is that it can still track
upstream.

A cheaper shape to try first: **a thin host**. The anchor hosts only what a harness session needs - tmux,
node, the repositories being edited - and `com.termux` stays a complete Termux that gains one extra view:
attach to a hosted session. The duplicated environment is then bounded by what a session needs, the existing
apt feed machinery already knows how to maintain a prefix, and nothing about `com.termux`'s own model has to
be torn out to find out whether the idea is worth its cost.

## Probes before any of this is built

Each is small and each can kill the idea, which is the point of doing them first:

| probe | question | expectation, unverified |
| --- | --- | --- |
| P1 | does a child spawned in the anchor's uid finish its work across a replace of `com.termux`? | yes, by the §16C uid argument - but the inverse direction has never been shown on this handset |
| P2 | with the pty owned by the anchor, does the session survive the client's death (no SIGHUP)? | yes; if no, the design is dead and this document is a curiosity |
| P3 | can the two uids carry a byte stream over loopback, gated by a token handed over the signature-guarded binder call? | yes, and the token is what makes it safe rather than merely possible |
| P4 | what does updating the host cost, once it holds the sessions? | one replace kills everything hosted - the number that has to be small and scheduled |

## Recommendation

Do not gut `com.termux` on the strength of an argument. Run P1-P3 (a day of measurement, not a rewrite); if
they pass, build the thin host for one repository and one harness, live on it for a week, and let that
decide whether the split replaces the current model. Until it exists, the honest summary is unchanged: this
branch gives you an app that comes back by itself and sessions that are resumable, not sessions that survive.
