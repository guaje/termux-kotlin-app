# Built-in SSH agent

The app ships OpenSSH and its runtime dependencies as checksum-verified APK assets. During bootstrap it installs them with `dpkg`, so a new installation does not require `pkg install openssh`, `termux-services`, or edits to `~/.ssh/config`, `.profile`, or `.bashrc`.

## Behaviour

- `TermuxService` starts one empty `ssh-agent` at the fixed socket `$PREFIX/var/run/ssh-agent.socket`.
- Every normal terminal session receives that path as `SSH_AUTH_SOCK`. Background tasks use the same app-managed lifecycle. A tmux server and its panes therefore keep the same socket path instead of inheriting a random agent path from the shell that created them.
- Add a key interactively as usual:

  ```sh
  ssh-add ~/.ssh/id_ed25519
  ```

- The service checks the fixed socket before starting. A reachable existing agent is reused; an unreachable socket is removed before starting a new agent.
- When the Termux foreground service is stopped, an agent started by the app is stopped too. Android force-stop, process death, and reboot also discard all loaded keys. The next app/session start creates a fresh **empty** agent.

This is the same session-level persistence model used by a Linux user service or macOS launch agent, subject to Android's process-lifecycle limits. It intentionally does not store key passphrases, load keys with `ssh-add`, or use an askpass helper. It probes the agent with `ssh-add -l` only to determine whether the fixed socket is live.

## Security

The socket directory is mode `0700`; OpenSSH creates the socket as user-only. Any process that runs under the Termux app UID can ask the agent to use a loaded key, so only install packages you trust and avoid `ForwardAgent` unless it is necessary. Use passphrase-protected keys and choose an `AddKeysToAgent` policy in your own SSH configuration if desired.

## Opt out

To prevent the app from starting or exporting its agent, add this optional setting and restart Termux:

```properties
# ~/.termux/termux.properties
disable-ssh-agent=true
```

This opt-out is only for users who deliberately manage another agent. It is not required for the default setup.

## Testing

The supervisor state machine (disabled, missing OpenSSH, live agent reuse, stale socket recovery,
failed cleanup) is covered by the unit tests in `app/src/test/kotlin/com/termux/app/ssh/`, and the
bundled package closure by `./scripts/validate-bundled-assets.sh`.

The end-to-end behaviour is covered by tests 11-17 of `scripts/emulator-smoke-test.sh`, which run in
the `Emulator Tests` CI job after the APK is installed and the app has bootstrapped:

| Check | Assertion |
|-------|-----------|
| Binaries | `$PREFIX/bin/ssh-agent` and `$PREFIX/bin/ssh-add` are executable once the offline dpkg migration finished |
| Package marker | `$PREFIX/var/lib/termux-kotlin/ssh-agent-packages-v1` exists |
| Socket | `$PREFIX/var/run/ssh-agent.socket` is a socket and its parent directory is mode `0700` |
| Environment | `SSH_AUTH_SOCK` in `$PREFIX/etc/termux/termux.env` points at that exact socket |
| Live probe | `ssh-add -l` with an explicit socket exits `0` or `1`, never `2` |
| Key handling | A throwaway `ed25519` key is added with `ssh-add` |
| Cross process | A second process lists that key, after which the key and its identity are deleted |

Run them against a local emulator with:

```sh
export APK_PATH=app/build/outputs/apk/debug/app-debug.apk
./scripts/emulator-smoke-test.sh            # full install + bootstrap
./scripts/emulator-smoke-test.sh --skip-install --skip-bootstrap   # reuse a booted emulator
```

Every path is derived from `PACKAGE_NAME` (default `com.termux`) and can be overridden with `PREFIX`,
`SSH_AGENT_SOCKET`, `SSH_AGENT_MARKER`, `TERMUX_ENV_FILE` or `SSH_TEST_KEY`. The agent tests are
side-effect free apart from the throwaway key under `$PREFIX/tmp`, which the same test removes: they
never force-stop the app, never delete a socket, and never write `~/.ssh`, `~/.termux/termux.properties`
or any shell startup file. The opt-out (`disable-ssh-agent=true`) and stale socket recovery are
deliberately **not** exercised on the emulator because both need a force-stop or a hand-made stale
socket, which would leave later smoke tests measuring an app state they do not expect; they stay in
unit test coverage. `BOOTSTRAP_TIMEOUT` is generous (300s) because a fresh install also runs `dpkg`
over the bundled OpenSSH closure without network access.

### Why the harness cannot use a renamed test applicationId

The debug APK keeps `applicationId "com.termux"`, so the emulator job tests the same package name
that ships. A renamed test applicationId (an `applicationIdSuffix`, which is the usual isolation trick
on Android) is not available here: the data directory - and therefore `PREFIX` - is derived from the
package name, and the bootstrap plus every bundled `.deb` has `/data/data/com.termux/files/usr`
compiled in (ELF interpreters and `RUNPATH`, the apt methods directory, the `termux-exec` `LD_PRELOAD`
path, shebangs and dpkg maintainer scripts). Renaming the id would move the prefix and require a
rebuilt or path-rewritten bootstrap - see [`CUSTOM_BOOTSTRAP_BUILD.md`](CUSTOM_BOOTSTRAP_BUILD.md) for
the cost of that - and the test would then exercise an installation layout no user has, including a
socket path different from the fixed one asserted above. Isolation instead comes from the harness: the
CI job runs on a throwaway emulator image, `pm clear` wipes the app data before launch and
`adb uninstall` runs before each install. Never point this harness at a physical device that already
holds a Termux installation.

## Refreshing bundled packages

Maintainers refresh the pinned offline package closure with:

```sh
python3 scripts/download-ssh-agent-packages.py --repository https://gnlug.org/pub/termux/termux-main
./scripts/validate-bundled-assets.sh
```

The generated `.deb` files under `app/src/main/assets/bootstrap-packages/` are intentional,
checksum-verified application assets and must be committed with the manifest and checksum file.
