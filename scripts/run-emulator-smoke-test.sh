#!/bin/sh
# POSIX entry point for the emulator smoke test.
#
# reactivecircus/android-emulator-runner runs its "script" input one line at a time, each line in
# its own shell, which is visible in the job log as one "[command]/usr/bin/sh -c <line>" per line.
# A multi line shell construct written into that input is therefore split apart and the second line
# already fails with "Syntax error: end of file unexpected (expecting fi)", so no test ever runs.
# Everything that needs more than one line lives here and the workflow only calls this file.
set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT_DIR="$(dirname "$SCRIPT_DIR")"

# The APKs of the "Build APK" job are downloaded to ./apks by the workflow.
APK_DIR="${APK_DIR:-$ROOT_DIR/apks}"

# The AVD is x86_64, so that ABI is preferred. Do not depend on artifact enumeration order, and
# accept a path handed in from outside as long as the file is really there.
if [ -z "${APK_PATH:-}" ] || [ ! -f "${APK_PATH:-}" ]; then
    APK_PATH=$(find "$APK_DIR" -name "*x86_64.apk" -type f -print -quit 2>/dev/null || true)
fi

if [ -z "${APK_PATH:-}" ] || [ ! -f "${APK_PATH:-}" ]; then
    APK_PATH=$(find "$APK_DIR" -name "*universal.apk" -type f -print -quit 2>/dev/null || true)
fi

if [ -z "${APK_PATH:-}" ] || [ ! -f "${APK_PATH:-}" ]; then
    echo "No x86_64 or universal APK found in $APK_DIR, its contents are:"
    ls -R "$APK_DIR" 2>/dev/null || echo "(no such directory)"
    exit 1
fi

echo "Using APK: $APK_PATH"
export APK_PATH

exec "$SCRIPT_DIR/emulator-smoke-test.sh"
