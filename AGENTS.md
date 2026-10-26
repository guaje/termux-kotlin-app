# Working agreement

Read `GLOSSARY.md` first. It is the domain language of this project; terms there are used with exactly that
meaning in docs, commit messages and code comments, and the `_Avoid_` lists are load-bearing because the rejected
synonyms are how past bugs were phrased into existence.

## Communications

**`/wait-what` — when a message does not land, re-pitch it.** If the user says they did not follow something, or
asks "where did you get to", stop answering in place: restate the current position with the context they were
missing, in short declarative sentences (ASD-STE100 style: one sentence, one fact, active voice), using the
**GLOSSARY.md** vocabulary, and say which **invariant** or **probe** the claim rests on. Do not treat a confusing
message as the user's problem.

**`/grill-me` — plans are interviewed, not written.** When a plan or design is under discussion, map it as a
**design tree** and ask the whole **frontier** — every decision whose prerequisites are already settled — as one
numbered round, each question carrying a recommended answer, and wait. Finding facts is the agent's job (look
them up, or delegate); making decisions is the user's. A question whose answer depends on an unanswered question
belongs to a later round, not this one. The discussion is finished when the frontier is empty, meaning nothing is
silently assumed. Do not implement until the user confirms the shared understanding.

**New words go in `GLOSSARY.md` before they appear in a commit message.** If a term is being invented in a
sentence, that sentence is in the wrong file.

## Evidence

- A **probe** has a **gate** written down before it runs and a measured verdict after. Say which.
- Prefer "measured on the handset" to "should work"; if it was not run, say so in the same sentence as the claim.
- Device decisions use the device clock. Never compare a laptop timestamp to a handset timestamp.
- After risky work, re-check the **invariants** by hand: bootstrap still possible, live sessions survive an apt
  upgrade, the self-install dialog appears, the goodbye marker is written on `exit` and cleared on start.

## Build and code

- Requires `JAVA_HOME` (JDK 17) and `ANDROID_HOME`; builds are
  `TERMUX_APP_VERSION_NAME=… TERMUX_APK_VERSION_TAG=dev TERMUX_SPLIT_APKS_FOR_DEV_BUILDS=0 ./gradlew :app:assembleDev`.
- detekt runs with `maxIssues=0` for new code. The repo carries pre-existing issue counts (app 384,
  termux-shared 282): the test is *"baseline + 0 new"*, not *"fewer than before"*.
- Shell scripts: `set -euo pipefail`, and a check that cannot be evaluated fails rather than passes.
- Conventional Commits; the body explains why and cites the measurement, not the task.
- No instrumentation tests exist over the notification, widget and install paths. Do not assume the compiler or
  R8 will catch a missing component there.
- Docs live in `docs/UPPER_SNAKE.md`. `PLAN.md` and `MEMORY.md` are working notes excluded from git; anything a
  stranger to this repo would need belongs in a tracked file.
- Before a handset experiment, confirm `adb install -r -d` downgrade works on the **dev build** and that the
  irreplaceable files are off the device. Data loss here means losing **$PREFIX**.
