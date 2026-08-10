# Kitty keyboard protocol

The terminal emulator supports Kitty's progressive keyboard enhancement protocol while preserving the existing VT/xterm behavior when no flags are active.

## Negotiation

Applications should use the Kitty protocol control sequences:

- Query flags: `CSI ? u`
- Set flags without changing the stack: `CSI = flags ; mode u`
- Push current flags and set new flags: `CSI > flags ; mode u`
- Pop flags: `CSI < count u`

`mode` is `1` to replace flags, `2` to set bits, or `3` to clear bits. A terminal reset clears the flag stack.

Supported flag values are:

| Bit | Meaning |
|---:|---|
| 1 | Disambiguate escape codes |
| 2 | Report repeat/release event types |
| 4 | Report alternate keys when the Android key event provides them |
| 8 | Report all keys as escape codes |
| 16 | Reserve associated-text reporting for compatible input events |

DECSET 2017 is not used; Kitty applications negotiate with the `CSI ... u` sequences above.

## Encoding

Enhanced key events use `CSI key ; modifiers:event u`. Kitty's modifier value is one plus the modifier bit mask:

- no modifiers: 1
- Shift: 2
- Alt: 3
- Ctrl: 5
- Ctrl+Shift: 6

The event value is 1 for press, 2 for repeat, and 3 for release. The default press event may omit the event field. Navigation and function keys use Kitty's private-use key numbers when enhancement is active.

Printable unmodified input remains ordinary text unless flag 8 is enabled. Ctrl/Alt combinations are disambiguated when flag 1 is enabled. If no Kitty flags are active, input follows the pre-existing Termux VT/xterm mappings.

## Implementation

- `terminal-emulator/src/main/kotlin/com/termux/terminal/TerminalEmulator.kt` parses and stores the flag stack.
- `terminal-emulator/src/main/kotlin/com/termux/terminal/KeyHandler.kt` produces Kitty key numbers and CSI-u sequences.
- `terminal-view/src/main/kotlin/com/termux/view/TerminalView.kt` reports physical press, repeat, and release events.
- `terminal-emulator/src/test/java/com/termux/terminal/KeyHandlerKittyTest.java` covers modifiers, special keys, fallback, and event encoding.

Specification: <https://sw.kovidgoyal.net/kitty/keyboard-protocol/>
