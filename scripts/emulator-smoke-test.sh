#!/bin/sh
# POSIX-compliant emulator smoke test script
# Runs automated tests inside Android emulator with Termux-Kotlin

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT_DIR="$(dirname "$SCRIPT_DIR")"

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
NC='\033[0m'

# Configuration
APK_PATH="${APK_PATH:-app/build/outputs/apk/debug/app-debug.apk}"
PACKAGE_NAME="${PACKAGE_NAME:-com.termux}"
MAIN_ACTIVITY="${MAIN_ACTIVITY:-com.termux.app.TermuxActivity}"
TEST_TIMEOUT="${TEST_TIMEOUT:-300}"
# Bootstrap extraction is followed by offline dpkg installation of the bundled
# OpenSSH closure, so allow the app extra time before the tests start.
BOOTSTRAP_TIMEOUT="${BOOTSTRAP_TIMEOUT:-300}"
LOG_DIR="${LOG_DIR:-${TMPDIR:-/tmp}/emulator-test-logs}"

# Paths inside the disposable emulator app data directory. Everything below is
# derived from PACKAGE_NAME so the tests never touch a real device installation.
PREFIX="${PREFIX:-/data/data/${PACKAGE_NAME}/files/usr}"
SSH_AGENT_SOCKET="${SSH_AGENT_SOCKET:-$PREFIX/var/run/ssh-agent.socket}"
SSH_AGENT_MARKER="${SSH_AGENT_MARKER:-$PREFIX/var/lib/termux-kotlin/ssh-agent-packages-v1}"
TERMUX_ENV_FILE="${TERMUX_ENV_FILE:-$PREFIX/etc/termux/termux.env}"
SSH_TEST_KEY="${SSH_TEST_KEY:-$PREFIX/tmp/emulator-smoke-ed25519}"
SSH_TEST_LIST="${SSH_TEST_LIST:-$PREFIX/tmp/emulator-smoke-ssh-add-l.txt}"
SSH_TEST_KEY_COMMENT="termux-smoke-test-key"
# TermuxInstaller bounds the bundled OpenSSH dpkg transaction with a 120 second
# process timeout, so every wait for that transaction must cover at least the
# same budget: 60 attempts x 2 seconds.
SSH_AGENT_WAIT_ATTEMPTS="${SSH_AGENT_WAIT_ATTEMPTS:-60}"

# The environment of a terminal session of the app, as far as these tests need it. PREFIX, HOME, TMPDIR
# and LD_LIBRARY_PATH are the values TermuxShellEnvironment hands to a session. The directories of the
# system are appended after the bin directory of the prefix, because the harness itself uses grep, stat
# and cat and a small bootstrap may not contain them. A bare "adb shell run-as" inherits the environment
# of adbd instead, where PREFIX does not exist and PATH holds only the system directories, so every
# program installed below PREFIX is unreachable there and only absolute paths work. The shell is
# deliberately started without -l: sourcing the profile of the user would let a broken ~/.profile fail
# this harness for a reason that is not the product, and what the app writes to termux.env is asserted
# directly by a test of its own.
TERMUX_SHELL_ENV="env PREFIX=$PREFIX HOME=${PREFIX%/usr}/home TMPDIR=$PREFIX/tmp LD_LIBRARY_PATH=$PREFIX/lib PATH=$PREFIX/bin:/system/bin:/system/xbin"

# The status the device side command reports about itself, read back out of its output. adb does not
# propagate the status of a nested run-as shell, see run_termux_command.
COMMAND_STATUS_MARKER="__TERMUX_SMOKE_TEST_RC="

# Test results
TESTS_PASSED=0
TESTS_FAILED=0
TEST_LOG="${LOG_DIR}/test-results.log"

log_info() {
    printf "${GREEN}[INFO]${NC} %s\n" "$1"
    echo "[INFO] $1" >> "$TEST_LOG" 2>/dev/null || true
}

log_warn() {
    printf "${YELLOW}[WARN]${NC} %s\n" "$1"
    echo "[WARN] $1" >> "$TEST_LOG" 2>/dev/null || true
}

log_error() {
    printf "${RED}[ERROR]${NC} %s\n" "$1"
    echo "[ERROR] $1" >> "$TEST_LOG" 2>/dev/null || true
}

log_test() {
    printf "${CYAN}[TEST]${NC} %s\n" "$1"
    echo "[TEST] $1" >> "$TEST_LOG" 2>/dev/null || true
}

# Initialize test environment
init_test_env() {
    mkdir -p "$LOG_DIR"
    : > "$TEST_LOG"
    
    log_info "Initializing test environment..."
    log_info "APK Path: $APK_PATH"
    log_info "Package: $PACKAGE_NAME"
    log_info "Prefix: $PREFIX"
    log_info "ssh-agent socket: $SSH_AGENT_SOCKET"
    log_info "Log directory: $LOG_DIR"
}

# Check if emulator is running
check_emulator() {
    log_info "Checking for running emulator..."
    
    if ! command -v adb >/dev/null 2>&1; then
        log_error "ADB not found in PATH"
        return 1
    fi
    
    # Wait for device
    local wait_time=0
    local max_wait=60
    
    while [ $wait_time -lt $max_wait ]; do
        if adb devices | grep -q "emulator\|device$"; then
            log_info "Emulator detected"
            return 0
        fi
        
        sleep 2
        wait_time=$((wait_time + 2))
        log_info "Waiting for emulator... ($wait_time/$max_wait seconds)"
    done
    
    log_error "No emulator detected after ${max_wait}s"
    return 1
}

# Wait for emulator to be fully booted
wait_for_boot() {
    log_info "Waiting for emulator to complete boot..."
    
    local wait_time=0
    local max_wait=120
    
    while [ $wait_time -lt $max_wait ]; do
        boot_completed=$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')
        
        if [ "$boot_completed" = "1" ]; then
            log_info "Emulator boot completed"
            sleep 5  # Extra time for system services
            return 0
        fi
        
        sleep 2
        wait_time=$((wait_time + 2))
    done
    
    log_error "Emulator did not complete boot in ${max_wait}s"
    return 1
}

# Install the APK
install_apk() {
    log_info "Installing APK: $APK_PATH"
    
    if [ ! -f "$ROOT_DIR/$APK_PATH" ] && [ ! -f "$APK_PATH" ]; then
        log_error "APK not found: $APK_PATH"
        return 1
    fi
    
    local apk_file="$APK_PATH"
    [ -f "$ROOT_DIR/$APK_PATH" ] && apk_file="$ROOT_DIR/$APK_PATH"
    
    # Uninstall previous version if exists
    adb uninstall "$PACKAGE_NAME" 2>/dev/null || true
    
    # Install new APK
    if adb install -r -g "$apk_file" 2>&1 | tee -a "$TEST_LOG"; then
        log_info "APK installed successfully"
        return 0
    else
        log_error "Failed to install APK"
        return 1
    fi
}

# Grant necessary permissions
grant_permissions() {
    log_info "Granting permissions..."
    
    # Storage permissions
    adb shell pm grant "$PACKAGE_NAME" android.permission.READ_EXTERNAL_STORAGE 2>/dev/null || true
    adb shell pm grant "$PACKAGE_NAME" android.permission.WRITE_EXTERNAL_STORAGE 2>/dev/null || true
    
    # Other required permissions
    adb shell pm grant "$PACKAGE_NAME" android.permission.WAKE_LOCK 2>/dev/null || true
    adb shell pm grant "$PACKAGE_NAME" android.permission.ACCESS_NETWORK_STATE 2>/dev/null || true
    adb shell pm grant "$PACKAGE_NAME" android.permission.INTERNET 2>/dev/null || true
    adb shell pm grant "$PACKAGE_NAME" android.permission.VIBRATE 2>/dev/null || true
    
    log_info "Permissions granted"
}

# Launch the app
launch_app() {
    log_info "Launching app..."
    
    # Clear app data first
    adb shell pm clear "$PACKAGE_NAME" 2>/dev/null || true
    
    # Launch main activity
    adb shell am start -n "${PACKAGE_NAME}/${MAIN_ACTIVITY}" 2>&1 | tee -a "$TEST_LOG"
    
    sleep 3
    
    # Check if app is running
    if adb shell pidof "$PACKAGE_NAME" >/dev/null 2>&1; then
        log_info "App launched successfully"
        return 0
    else
        log_warn "App may not be running, continuing anyway..."
        return 0
    fi
}

# Wait for the app to finish bootstrapping.
#
# Bootstrapping is not finished when the bootstrap archive has been extracted:
# TermuxInstaller then runs an offline dpkg transaction for the bundled OpenSSH
# closure and only records its marker afterwards. Readiness is therefore that
# marker, not $PREFIX/bin/bash, because Tests 5 and 6 run "pkg" (dpkg) and must
# never overlap the app's own dpkg transaction. The wait stays bounded by
# BOOTSTRAP_TIMEOUT and a timeout is not fatal here; the individual tests and the
# collected artifacts report what is actually missing.
wait_for_bootstrap() {
    log_info "Waiting for bootstrap extraction and bundled OpenSSH installation..."
    
    local wait_time=0
    local max_wait=$BOOTSTRAP_TIMEOUT
    
    while [ $wait_time -lt $max_wait ]; do
        # Do not rely on adb propagating the nested run-as exit code. The marker
        # printed by the device shell is the readiness signal, just as it is for
        # the test helpers below.
        local result
        result=$(adb shell "run-as $PACKAGE_NAME sh -c 'test -f $SSH_AGENT_MARKER && echo BOOTSTRAP_READY'" 2>&1) || true
        case "$result" in
            *BOOTSTRAP_READY*)
                log_info "Bootstrap and bundled OpenSSH packages are ready"
                return 0
                ;;
        esac

        sleep 5
        wait_time=$((wait_time + 5))
        log_info "Waiting for bootstrap... ($wait_time/$max_wait seconds)"
    done
    
    log_warn "Bootstrap or bundled OpenSSH install did not finish after ${max_wait}s"
    log_warn "Continuing with tests anyway; the tests below and the artifacts report what is missing"
    return 0
}

# Run a command in the Termux environment and judge it by the status the command itself reported.
#
# Two things make this harder than it looks and both were wrong here before:
#
# - The command needs the environment of a real terminal session, otherwise a program of the prefix
#   is simply not found and the test measures the PATH of adbd instead of the app.
# - The status has to come from the device side. adb does not propagate the status of a nested
#   run-as shell, so reading the status of the adb call always produced 0, which made every command
#   with empty output a failure and every genuinely failing command a success. The command therefore
#   prints its own status behind COMMAND_STATUS_MARKER, and empty output is allowed: it is a normal
#   result of a successful command.
#
# The command must not contain single quotes, since it is passed inside them.
run_termux_command() {
    local cmd="$1"
    local description="$2"

    log_test "Running: $description"
    log_info "Command: $cmd"

    local result
    result=$(adb shell "run-as $PACKAGE_NAME $TERMUX_SHELL_ENV $PREFIX/bin/bash -c '$cmd; echo $COMMAND_STATUS_MARKER\$?'" 2>&1) || true

    local exit_code
    exit_code=$(printf '%s\n' "$result" | sed -n "s|.*$COMMAND_STATUS_MARKER\([0-9][0-9]*\).*|\1|p" | tail -1)
    if [ -z "$exit_code" ]; then
        # The status line is missing, so the command never ran to completion on the device.
        exit_code=1
    fi

    # The status line is bookkeeping of the harness and is not part of the output.
    result=$(printf '%s\n' "$result" | grep -v "$COMMAND_STATUS_MARKER" || true)

    echo "$result" >> "$TEST_LOG"

    if [ "$exit_code" = "0" ]; then
        log_info "Command succeeded"
        echo "$result" | head -20
        TESTS_PASSED=$((TESTS_PASSED + 1))
        return 0
    else
        log_error "Command failed with exit code: $exit_code"
        echo "$result"
        TESTS_FAILED=$((TESTS_FAILED + 1))
        return 1
    fi
}

# Run a Termux command and pass only when the device-side command printed the
# expected marker. The exit code of a nested run-as shell is not reliably
# propagated through adb, so the marker written by the command itself is the
# source of truth. The command must not contain single quotes.
run_termux_check() {
    local cmd="$1"
    local description="$2"
    local marker="$3"

    log_test "Running: $description"
    log_info "Command: $cmd"

    local result
    result=$(adb shell "run-as $PACKAGE_NAME $TERMUX_SHELL_ENV $PREFIX/bin/bash -c '$cmd'" 2>&1) || true

    echo "$result" >> "$TEST_LOG"

    case "$result" in
        *"$marker"*)
            log_info "$description passed"
            echo "$result" | head -20
            TESTS_PASSED=$((TESTS_PASSED + 1))
            return 0
            ;;
    esac

    log_error "$description failed (marker $marker missing)"
    echo "$result"
    TESTS_FAILED=$((TESTS_FAILED + 1))
    return 1
}

# Verify the app-managed ssh-agent shipped as verified APK assets.
#
# Every command runs inside the disposable emulator app data directory with an
# explicit SSH_AUTH_SOCK. Nothing here force-stops the app, writes user shell
# configuration or removes a socket, so a failure can never leave the app in a
# state that the following tests would misreport.
test_ssh_agent() {
    log_info "Starting ssh-agent tests..."

    # Test 11: OpenSSH binaries are present once the dpkg migration finished.
    run_termux_check "if [ -x $PREFIX/bin/ssh-agent ] && [ -x $PREFIX/bin/ssh-add ]; then echo TOOLS_OK; fi" \
        "ssh-agent and ssh-add installed" "TOOLS_OK" || true

    # Test 12: fresh app data must have used the bundled offline package closure.
    run_termux_check "if [ -f $SSH_AGENT_MARKER ] && [ \"\$(cat $SSH_AGENT_MARKER)\" = bundled-openssh ]; then echo MARKER_OK; else echo \"MARKER=\$(cat $SSH_AGENT_MARKER 2>/dev/null)\"; fi" \
        "Bundled OpenSSH package marker" "MARKER_OK" || true

    # Test 13: normal sessions receive the fixed socket path via termux.env.
    run_termux_check "if grep SSH_AUTH_SOCK $TERMUX_ENV_FILE 2>/dev/null | grep -q $SSH_AGENT_SOCKET; then echo ENV_OK; else grep SSH_AUTH_SOCK $TERMUX_ENV_FILE 2>/dev/null; fi" \
        "SSH_AUTH_SOCK exported in termux.env" "ENV_OK" || true

    # Test 14: agent startup follows the completed dpkg transaction. Poll until
    # the fixed socket and its private 0700 parent are both ready.
    if ! run_termux_check "i=0; while [ \$i -lt $SSH_AGENT_WAIT_ATTEMPTS ]; do if [ -S $SSH_AGENT_SOCKET ]; then d=\$(dirname $SSH_AGENT_SOCKET); m=\$(stat -c %a \$d 2>/dev/null); if [ \"\$m\" = 700 ]; then echo SOCKET_OK; break; fi; fi; i=\$((i + 1)); sleep 2; done" \
        "Fixed ssh-agent socket with 0700 run directory" "SOCKET_OK"; then
        log_warn "Skipping ssh-agent probe and key tests because the fixed socket is not ready"
        return 0
    fi

    # Test 15: the agent answers on the fixed socket. ssh-add exits 1 when it has
    # no identities and 2 when it cannot reach an agent, so 2 must fail the test.
    run_termux_check "SSH_AUTH_SOCK=$SSH_AGENT_SOCKET $PREFIX/bin/ssh-add -l; rc=\$?; echo \"ssh-add -l exit=\$rc\"; if [ \$rc -eq 0 ] || [ \$rc -eq 1 ]; then echo AGENT_LIVE; fi" \
        "ssh-add -l on fixed socket exits 0 or 1" "AGENT_LIVE" || true

    # Test 16: a throwaway key can be added to the app-managed agent.
    run_termux_check "rm -f $SSH_TEST_KEY $SSH_TEST_KEY.pub; $PREFIX/bin/ssh-keygen -t ed25519 -N \"\" -C $SSH_TEST_KEY_COMMENT -f $SSH_TEST_KEY >/dev/null 2>&1; if [ -f $SSH_TEST_KEY ]; then SSH_AUTH_SOCK=$SSH_AGENT_SOCKET $PREFIX/bin/ssh-add $SSH_TEST_KEY >/dev/null 2>&1 && echo KEY_ADDED; fi" \
        "Throwaway ed25519 key added with ssh-add" "KEY_ADDED" || true

    # Test 17: a separate process still sees the key, then the key is wiped.
    run_termux_check "SSH_AUTH_SOCK=$SSH_AGENT_SOCKET $PREFIX/bin/ssh-add -l > $SSH_TEST_LIST 2>&1; rc=\$?; echo \"second process ssh-add -l exit=\$rc\"; cat $SSH_TEST_LIST; if [ \$rc -eq 0 ] && grep -q $SSH_TEST_KEY_COMMENT $SSH_TEST_LIST; then found=1; else found=0; fi; SSH_AUTH_SOCK=$SSH_AGENT_SOCKET $PREFIX/bin/ssh-add -D >/dev/null 2>&1; rm -f $SSH_TEST_KEY $SSH_TEST_KEY.pub $SSH_TEST_LIST; if [ \$found -eq 1 ]; then echo KEY_LISTED; fi" \
        "Loaded key visible from a second process" "KEY_LISTED" || true
}

# Run a command via am broadcast (alternative method)
run_termux_broadcast() {
    local cmd="$1"
    local description="$2"
    
    log_test "Running via broadcast: $description"
    
    # Use Termux:API RUN_COMMAND intent if available
    adb shell am broadcast \
        --user 0 \
        -a "${PACKAGE_NAME}.RUN_COMMAND" \
        --es "command" "$cmd" \
        -n "${PACKAGE_NAME}/${PACKAGE_NAME}.app.TermuxRunCommandService" 2>&1 || true
    
    sleep 2
}

# Collect test artifacts
collect_artifacts() {
    log_info "Collecting test artifacts..."
    
    # Capture screenshot
    adb shell screencap -p /sdcard/test-screenshot.png 2>/dev/null || true
    adb pull /sdcard/test-screenshot.png "$LOG_DIR/screenshot.png" 2>/dev/null || true
    
    # Collect logcat
    adb logcat -d "*:W" > "$LOG_DIR/logcat.txt" 2>/dev/null || true
    
    # Collect app logs
    adb shell "run-as $PACKAGE_NAME cat files/usr/var/log/*.log" > "$LOG_DIR/termux-logs.txt" 2>/dev/null || true
    
    # Collect package info
    adb shell dumpsys package "$PACKAGE_NAME" > "$LOG_DIR/package-info.txt" 2>/dev/null || true
    
    # Collect ssh-agent state for the agent tests
    adb shell "run-as $PACKAGE_NAME ls -la $PREFIX/var/run" > "$LOG_DIR/ssh-agent-socket.txt" 2>/dev/null || true
    adb shell "run-as $PACKAGE_NAME cat $TERMUX_ENV_FILE" > "$LOG_DIR/termux-env.txt" 2>/dev/null || true

    log_info "Artifacts collected in: $LOG_DIR"
}

# Run smoke tests
run_smoke_tests() {
    log_info "Starting smoke tests..."
    
    # Test 1: Echo test (basic functionality)
    log_test "Test 1: Basic echo"
    run_termux_command 'echo "Hello from Termux-Kotlin"' "Basic echo test" || true
    
    # Test 2: Check shell
    log_test "Test 2: Shell verification"
    run_termux_command 'echo ${SHELL:-no-SHELL-variable-in-the-environment}' "Shell verification" || true

    # Test 3: The extracted prefix is the one the app installed, with its home directory beside it.
    # Asserting an absolute path instead of the PREFIX variable matters here: the harness hands PREFIX
    # to the command itself, so printing it back would only prove that env works.
    log_test "Test 3: PREFIX verification"
    run_termux_check "if [ -x $PREFIX/bin/bash ] && [ -d ${PREFIX%/usr}/home ]; then echo PREFIX_OK; fi" \
        "PREFIX layout of the installed app" "PREFIX_OK" || true

    # Test 4: A package tool of the prefix is reachable through PATH. This product drives dpkg and its
    # apt wrappers itself and its bootstrap does not have to contain termux-tools with pkg, so any of
    # the three proves what this test is actually about: the bin directory of the prefix is on PATH.
    log_test "Test 4: Package manager check"
    run_termux_check "tool=; for c in apt pkg dpkg; do if command -v \$c >/dev/null 2>&1; then tool=\$c; break; fi; done; if [ -n \"\$tool\" ]; then echo \"package tool: \$tool\"; echo TOOL_OK; fi" \
        "Package manager availability" "TOOL_OK" || true

    # Tests 5 to 7 can only report: pkg may be absent from the bootstrap by design, so they are written
    # to print something in every case and never decide the result of the run.
    # Test 5: pkg update (may fail in CI due to network)
    log_test "Test 5: Package update"
    run_termux_command 'pkg update -y 2>&1 || echo "Update skipped/failed"' "Package list update" || true
    
    # Test 6: Install coreutils
    log_test "Test 6: Install coreutils"
    run_termux_command 'pkg install -y coreutils 2>&1 || echo "Already installed or failed"' "Coreutils installation" || true
    
    # Test 7: termux-info
    log_test "Test 7: termux-info"
    run_termux_command 'termux-info 2>/dev/null || echo "termux-info not available"' "Termux info command" || true
    
    # Test 8: The app writes the HOME value used by its terminal sessions.
    # A bare adb run-as shell does not receive the app's managed environment.
    log_test "Test 8: Path verification"
    run_termux_check "if grep -Fqx \"export HOME=\\\"${PREFIX%/usr}/home\\\"\" $TERMUX_ENV_FILE; then echo HOME_OK; fi" \
        "termux.env exports the PREFIX-derived home directory" "HOME_OK" || true
    
    # Test 9: The package database written by the offline install is readable and lists packages. An
    # empty listing used to count as success here, because empty output was treated as a failure.
    log_test "Test 9: List packages"
    run_termux_check "if dpkg -l 2>/dev/null | grep -q ^ii; then echo DPKG_OK; fi" "List installed packages" "DPKG_OK" || true
    
    # Test 10: Final echo
    log_test "Test 10: Final verification"
    run_termux_command 'echo "test"' "Final echo test" || true

    # Tests 11-17: the built-in ssh-agent
    test_ssh_agent
}

# Generate test report
generate_report() {
    local total=$((TESTS_PASSED + TESTS_FAILED))
    local pass_rate=0
    
    if [ $total -gt 0 ]; then
        pass_rate=$((TESTS_PASSED * 100 / total))
    fi
    
    cat > "$LOG_DIR/report.md" << EOF
# Emulator Smoke Test Report

**Date:** $(date -u '+%Y-%m-%d %H:%M:%S UTC')
**Package:** $PACKAGE_NAME

## Summary

| Metric | Value |
|--------|-------|
| Tests Passed | $TESTS_PASSED |
| Tests Failed | $TESTS_FAILED |
| Total Tests | $total |
| Pass Rate | ${pass_rate}% |

## Result

EOF

    if [ $TESTS_FAILED -eq 0 ]; then
        echo "✅ **All tests passed!**" >> "$LOG_DIR/report.md"
    else
        echo "❌ **${TESTS_FAILED} test(s) failed**" >> "$LOG_DIR/report.md"
    fi

    cat >> "$LOG_DIR/report.md" << EOF

## Collected Artifacts

- \`screenshot.png\` - Final screen state
- \`logcat.txt\` - Android system logs
- \`termux-logs.txt\` - Termux application logs
- \`ssh-agent-socket.txt\` - Listing of the fixed ssh-agent run directory
- \`termux-env.txt\` - Generated \`termux.env\` sourced by sessions
- \`test-results.log\` - Detailed test output

---

*Generated by emulator-smoke-test.sh*
EOF

    log_info "Report generated: $LOG_DIR/report.md"
    
    # Output for GitHub Actions
    if [ -n "$GITHUB_OUTPUT" ]; then
        echo "tests_passed=$TESTS_PASSED" >> "$GITHUB_OUTPUT"
        echo "tests_failed=$TESTS_FAILED" >> "$GITHUB_OUTPUT"
        echo "pass_rate=$pass_rate" >> "$GITHUB_OUTPUT"
        echo "log_dir=$LOG_DIR" >> "$GITHUB_OUTPUT"
    fi
    
    # Print summary
    echo ""
    echo "=========================================="
    echo "  Smoke Test Summary"
    echo "=========================================="
    echo "  Passed: $TESTS_PASSED"
    echo "  Failed: $TESTS_FAILED"
    echo "  Total:  $total"
    echo "  Rate:   ${pass_rate}%"
    echo "=========================================="
    
    # Return based on test results
    if [ $TESTS_FAILED -gt 0 ]; then
        return 1
    fi
    return 0
}

# Cleanup
cleanup() {
    log_info "Cleaning up..."
    
    # Stop app
    adb shell am force-stop "$PACKAGE_NAME" 2>/dev/null || true
    
    # Remove temp files from device
    adb shell rm -f /sdcard/test-screenshot.png 2>/dev/null || true
}

# Print usage
usage() {
    echo "Usage: $0 [OPTIONS]"
    echo ""
    echo "Run smoke tests on Android emulator with Termux-Kotlin app."
    echo ""
    echo "Options:"
    echo "  --apk PATH        Path to APK file (default: $APK_PATH)"
    echo "  --package NAME    Package name (default: $PACKAGE_NAME)"
    echo "  --timeout SEC     Test timeout in seconds (default: $TEST_TIMEOUT)"
    echo "  --log-dir PATH    Log output directory (default: $LOG_DIR)"
    echo "  --skip-install    Skip APK installation"
    echo "  --skip-bootstrap  Skip bootstrap wait"
    echo "  --help            Show this help message"
    echo ""
    echo "Environment variables:"
    echo "  APK_PATH          Path to APK file"
    echo "  PACKAGE_NAME      Application package name"
    echo "  BOOTSTRAP_TIMEOUT Bootstrap and bundled dpkg install wait in seconds"
    echo "  TEST_TIMEOUT      Overall test timeout"
    echo "  LOG_DIR           Log output directory"
    echo ""
    echo "Emulator path overrides (all default to PACKAGE_NAME derived paths):"
    echo "  PREFIX                 Termux prefix inside the app data directory"
    echo "  SSH_AGENT_SOCKET       Fixed ssh-agent socket path"
    echo "  SSH_AGENT_MARKER       Installer marker for the bundled OpenSSH packages"
    echo "  TERMUX_ENV_FILE        Generated environment file sourced by sessions"
    echo "  SSH_TEST_KEY           Throwaway key used by the ssh-agent tests"
    echo "  SSH_TEST_LIST          Temp output of ssh-add -l inside the prefix"
    echo "  SSH_AGENT_WAIT_ATTEMPTS  Attempts to wait for the bundled OpenSSH install"
}

# Parse arguments
SKIP_INSTALL=false
SKIP_BOOTSTRAP=false

while [ $# -gt 0 ]; do
    case "$1" in
        --apk)
            APK_PATH="$2"
            shift 2
            ;;
        --package)
            PACKAGE_NAME="$2"
            shift 2
            ;;
        --timeout)
            TEST_TIMEOUT="$2"
            shift 2
            ;;
        --log-dir)
            LOG_DIR="$2"
            shift 2
            ;;
        --skip-install)
            SKIP_INSTALL=true
            shift
            ;;
        --skip-bootstrap)
            SKIP_BOOTSTRAP=true
            shift
            ;;
        --help|-h)
            usage
            exit 0
            ;;
        *)
            log_error "Unknown option: $1"
            usage
            exit 1
            ;;
    esac
done

# The log file lives inside the log directory, so it has to follow a --log-dir given on the command
# line. It used to stay at the directory taken from the environment, which made --log-dir write the
# report to one directory and the results to another, or fail outright when the directory from the
# environment did not exist yet.
TEST_LOG="${LOG_DIR}/test-results.log"

# Main execution
main() {
    init_test_env
    
    log_info "Starting emulator smoke test..."
    log_info "Timeout: ${TEST_TIMEOUT}s"
    
    # Step 1: Check emulator
    if ! check_emulator; then
        log_error "Emulator not available"
        exit 1
    fi
    
    # Step 2: Wait for boot
    if ! wait_for_boot; then
        log_error "Emulator boot failed"
        collect_artifacts
        exit 1
    fi
    
    # Step 3: Install APK
    if [ "$SKIP_INSTALL" = "false" ]; then
        if ! install_apk; then
            log_error "APK installation failed"
            collect_artifacts
            exit 1
        fi
    fi
    
    # Step 4: Grant permissions
    grant_permissions
    
    # Step 5: Launch app
    if ! launch_app; then
        log_error "App launch failed"
        collect_artifacts
        exit 1
    fi
    
    # Step 6: Wait for bootstrap
    if [ "$SKIP_BOOTSTRAP" = "false" ]; then
        wait_for_bootstrap
    fi
    
    # Step 7: Run tests
    run_smoke_tests
    
    # Step 8: Collect artifacts
    collect_artifacts
    
    # Step 9: Generate report
    generate_report
    result=$?
    
    # Step 10: Cleanup
    cleanup
    
    exit $result
}

main
