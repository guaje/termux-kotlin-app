package com.termux.terminal;

import android.view.KeyEvent;

import junit.framework.TestCase;

/**
 * Tests for the Kitty keyboard protocol implementation.
 * Covers: modifier encoding, special-key codepoint mappings, CSI-u sequence generation,
 * and representation of common key combinations.
 */
public class KeyHandlerKittyTest extends TestCase {

    // ========== kittyModifier ==========

    public void testKittyModifierNoMods() {
        assertEquals(0, KeyHandler.kittyModifier(0));
    }

    public void testKittyModifierShift() {
        assertEquals(1, KeyHandler.kittyModifier(KeyHandler.KEYMOD_SHIFT));
    }

    public void testKittyModifierAlt() {
        assertEquals(2, KeyHandler.kittyModifier(KeyHandler.KEYMOD_ALT));
    }

    public void testKittyModifierCtrl() {
        assertEquals(4, KeyHandler.kittyModifier(KeyHandler.KEYMOD_CTRL));
    }

    public void testKittyModifierShiftAlt() {
        assertEquals(1 | 2, KeyHandler.kittyModifier(KeyHandler.KEYMOD_SHIFT | KeyHandler.KEYMOD_ALT));
    }

    public void testKittyModifierShiftAltCtrl() {
        assertEquals(1 | 2 | 4, KeyHandler.kittyModifier(
            KeyHandler.KEYMOD_SHIFT | KeyHandler.KEYMOD_ALT | KeyHandler.KEYMOD_CTRL));
    }

    public void testKittyModifierNumLock() {
        assertEquals(128, KeyHandler.kittyModifier(KeyHandler.KEYMOD_NUM_LOCK));
    }

    // ========== kittyKeyCode: Arrow keys ==========

    public void testKittyKeyCodeArrowUp() {
        assertEquals(Integer.valueOf(57352), KeyHandler.kittyKeyCode(KeyEvent.KEYCODE_DPAD_UP));
    }

    public void testKittyKeyCodeArrowDown() {
        assertEquals(Integer.valueOf(57353), KeyHandler.kittyKeyCode(KeyEvent.KEYCODE_DPAD_DOWN));
    }

    public void testKittyKeyCodeArrowLeft() {
        assertEquals(Integer.valueOf(57350), KeyHandler.kittyKeyCode(KeyEvent.KEYCODE_DPAD_LEFT));
    }

    public void testKittyKeyCodeArrowRight() {
        assertEquals(Integer.valueOf(57351), KeyHandler.kittyKeyCode(KeyEvent.KEYCODE_DPAD_RIGHT));
    }

    // ========== kittyKeyCode: Page / Home / End / Insert / Delete ==========

    public void testKittyKeyCodePageUp() {
        assertEquals(Integer.valueOf(57354), KeyHandler.kittyKeyCode(KeyEvent.KEYCODE_PAGE_UP));
    }

    public void testKittyKeyCodePageDown() {
        assertEquals(Integer.valueOf(57355), KeyHandler.kittyKeyCode(KeyEvent.KEYCODE_PAGE_DOWN));
    }

    public void testKittyKeyCodeHome() {
        assertEquals(Integer.valueOf(57356), KeyHandler.kittyKeyCode(KeyEvent.KEYCODE_MOVE_HOME));
    }

    public void testKittyKeyCodeEnd() {
        assertEquals(Integer.valueOf(57357), KeyHandler.kittyKeyCode(KeyEvent.KEYCODE_MOVE_END));
    }

    public void testKittyKeyCodeInsert() {
        assertEquals(Integer.valueOf(57348), KeyHandler.kittyKeyCode(KeyEvent.KEYCODE_INSERT));
    }

    public void testKittyKeyCodeForwardDelete() {
        assertEquals(Integer.valueOf(57349), KeyHandler.kittyKeyCode(KeyEvent.KEYCODE_FORWARD_DEL));
    }

    // ========== kittyKeyCode: Basic printable keys (unicode fallback) ==========

    public void testKittyKeyCodePrintableAsciiLetter() {
        // When no special keycode exists, printable characters fall back to their Unicode value
        assertEquals(Integer.valueOf(65), KeyHandler.kittyKeyCode(KeyEvent.KEYCODE_UNKNOWN, 65));
    }

    public void testKittyKeyCodePrintableSpace() {
        assertEquals(Integer.valueOf(32), KeyHandler.kittyKeyCode(KeyEvent.KEYCODE_SPACE));
    }

    public void testKittyKeyCodePrintableEnter() {
        assertEquals(Integer.valueOf(13), KeyHandler.kittyKeyCode(KeyEvent.KEYCODE_ENTER));
    }

    // ========== getKittyKeyCode: CSI-u sequence generation ==========

    public void testGetKittyKeyCodePrintableNoModifier() {
        String seq = KeyHandler.getKittyKeyCode(0, 0,
            KeyHandler.KITTY_FLAG_DISAMBIGUATE | KeyHandler.KITTY_FLAG_REPORT_ALL, 'a');
        assertEquals("\u001b[97u", seq);
    }

    public void testGetKittyKeyCodePrintableWithShiftModifier() {
        String seq = KeyHandler.getKittyKeyCode(0, KeyHandler.KEYMOD_SHIFT,
            KeyHandler.KITTY_FLAG_DISAMBIGUATE | KeyHandler.KITTY_FLAG_REPORT_ALL, 'A');
        assertEquals("\u001b[65;2u", seq);
    }

    public void testGetKittyKeyCodePrintableWithAltModifier() {
        String seq = KeyHandler.getKittyKeyCode(0, KeyHandler.KEYMOD_ALT,
            KeyHandler.KITTY_FLAG_DISAMBIGUATE | KeyHandler.KITTY_FLAG_REPORT_ALL, 'b');
        assertEquals("\u001b[98;3u", seq);
    }

    public void testGetKittyKeyCodePrintableWithCtrlModifier() {
        String seq = KeyHandler.getKittyKeyCode(0, KeyHandler.KEYMOD_CTRL,
            KeyHandler.KITTY_FLAG_DISAMBIGUATE | KeyHandler.KITTY_FLAG_REPORT_ALL, 'c');
        assertEquals("\u001b[99;5u", seq);
    }

    public void testGetKittyKeyCodeShiftAltCtrl() {
        int mods = KeyHandler.KEYMOD_SHIFT | KeyHandler.KEYMOD_ALT | KeyHandler.KEYMOD_CTRL;
        String seq = KeyHandler.getKittyKeyCode(0, mods,
            KeyHandler.KITTY_FLAG_DISAMBIGUATE | KeyHandler.KITTY_FLAG_REPORT_ALL, 'x');
        assertEquals("\u001b[120;8u", seq);
    }

    // ========== getKittyKeyCode: legacy functional keys never use CSI-u ==========

    public void testGetKittyKeyCodeArrowUpNoMod() {
        // Kitty spec, "Legacy functional keys": arrows keep the legacy encodings in every
        // mode; presses fall back to the legacy form from getCode().
        assertNull(KeyHandler.getKittyKeyCode(KeyEvent.KEYCODE_DPAD_UP, 0,
            KeyHandler.KITTY_FLAG_DISAMBIGUATE, 0));
    }

    public void testGetKittyKeyCodeArrowDownWithShift() {
        assertNull(KeyHandler.getKittyKeyCode(KeyEvent.KEYCODE_DPAD_DOWN, KeyHandler.KEYMOD_SHIFT,
            KeyHandler.KITTY_FLAG_DISAMBIGUATE, 0));
    }

    public void testGetKittyKeyCodeHomeWithCtrl() {
        assertNull(KeyHandler.getKittyKeyCode(KeyEvent.KEYCODE_MOVE_HOME, KeyHandler.KEYMOD_CTRL,
            KeyHandler.KITTY_FLAG_DISAMBIGUATE, 0));
    }

    public void testGetKittyKeyCodeEndWithShiftAlt() {
        int mods = KeyHandler.KEYMOD_SHIFT | KeyHandler.KEYMOD_ALT;
        assertNull(KeyHandler.getKittyKeyCode(KeyEvent.KEYCODE_MOVE_END, mods,
            KeyHandler.KITTY_FLAG_DISAMBIGUATE, 0));
    }

    public void testGetKittyKeyCodePageUpWithCtrlShift() {
        int mods = KeyHandler.KEYMOD_CTRL | KeyHandler.KEYMOD_SHIFT;
        assertNull(KeyHandler.getKittyKeyCode(KeyEvent.KEYCODE_PAGE_UP, mods,
            KeyHandler.KITTY_FLAG_DISAMBIGUATE, 0));
    }

    public void testGetKittyKeyCodePageDownWithAltCtrl() {
        int mods = KeyHandler.KEYMOD_ALT | KeyHandler.KEYMOD_CTRL;
        assertNull(KeyHandler.getKittyKeyCode(KeyEvent.KEYCODE_PAGE_DOWN, mods,
            KeyHandler.KITTY_FLAG_DISAMBIGUATE, 0));
    }

    public void testLegacyFunctionalRepeatAndReleaseUseLegacyFormWithEvent() {
        // Event-type augmented legacy functional form (kitty spec, "Event types"):
        // repeat and release get the event as a sub-field of the modifiers field.
        int flags = KeyHandler.KITTY_FLAG_DISAMBIGUATE | KeyHandler.KITTY_FLAG_REPORT_EVENT_TYPES;
        assertEquals("\u001b[1;1:2A", KeyHandler.getKittyKeyCode(
            KeyEvent.KEYCODE_DPAD_UP, 0, flags, 0, 2));
        assertEquals("\u001b[1;1:3D", KeyHandler.getKittyKeyCode(
            KeyEvent.KEYCODE_DPAD_LEFT, 0, flags, 0, 3));
        assertEquals("\u001b[1;5:3D", KeyHandler.getKittyKeyCode(
            KeyEvent.KEYCODE_DPAD_LEFT, KeyHandler.KEYMOD_CTRL, flags, 0, 3));
        assertEquals("\u001b[3;1:3~", KeyHandler.getKittyKeyCode(
            KeyEvent.KEYCODE_FORWARD_DEL, 0, flags, 0, 3));
        assertEquals("\u001b[5;1:2~", KeyHandler.getKittyKeyCode(
            KeyEvent.KEYCODE_PAGE_UP, 0, flags, 0, 2));
        assertEquals("\u001b[1;1:3P", KeyHandler.getKittyKeyCode(
            KeyEvent.KEYCODE_F1, 0, flags, 0, 3));
    }

    public void testLegacyFunctionalRepeatIsPressWithoutEventTypes() {
        // Without the report-event-types flag, repeats fall back to the legacy press form.
        assertNull(KeyHandler.getKittyKeyCode(KeyEvent.KEYCODE_DPAD_UP, 0,
            KeyHandler.KITTY_FLAG_DISAMBIGUATE, 0, 2));
        assertNull(KeyHandler.getKittyKeyCode(KeyEvent.KEYCODE_DPAD_UP, 0,
            KeyHandler.KITTY_FLAG_DISAMBIGUATE, 0, 3));
    }

    // ========== REPORT_ALTERNATES (level 2) ==========

    public void testGetKittyKeyCodeWithAlternateForm() {
        String seq = KeyHandler.getKittyKeyCode(0, 0,
            KeyHandler.KITTY_FLAG_DISAMBIGUATE | KeyHandler.KITTY_FLAG_REPORT_ALTERNATES | KeyHandler.KITTY_FLAG_REPORT_ALL,
            'B');
        // base key code=66 (unicode 'B'), alternate=66 (same as code), so no alternate inserted
        assertEquals("\u001b[66u", seq);
    }

    public void testGetKittyKeyCodeAlternateOnlyWhenDifferent() {
        // Shifted 'a' vs unshifted 'a' — ASCII 65 vs 97
        String seq = KeyHandler.getKittyKeyCode(0, 0,
            KeyHandler.KITTY_FLAG_DISAMBIGUATE | KeyHandler.KITTY_FLAG_REPORT_ALTERNATES | KeyHandler.KITTY_FLAG_REPORT_ALL,
            97); // 'a'
        // code = 97, since keyCode=0, unicodeCodePoint=97
        assertEquals("\u001b[97u", seq);
    }

    public void testRepeatAndReleaseEventEncoding() {
        // Text keys with modifiers report repeat/release via the event sub-field.
        int flags = KeyHandler.KITTY_FLAG_DISAMBIGUATE | KeyHandler.KITTY_FLAG_REPORT_EVENT_TYPES;
        assertEquals("\u001b[98;5:2u", KeyHandler.getKittyKeyCode(
            0, KeyHandler.KEYMOD_CTRL, flags, 'b', 2));
        assertEquals("\u001b[98;5:3u", KeyHandler.getKittyKeyCode(
            0, KeyHandler.KEYMOD_CTRL, flags, 'b', 3));
    }

    public void testPrintableWithoutModifierNeedsReportAll() {
        assertNull(KeyHandler.getKittyKeyCode(KeyEvent.KEYCODE_UNKNOWN, 0,
            KeyHandler.KITTY_FLAG_DISAMBIGUATE, 'a'));
    }

    public void testKittyProtocolFlagValues() {
        assertEquals(1, KeyHandler.KITTY_FLAG_DISAMBIGUATE);
        assertEquals(2, KeyHandler.KITTY_FLAG_REPORT_EVENT_TYPES);
        assertEquals(4, KeyHandler.KITTY_FLAG_REPORT_ALTERNATES);
        assertEquals(8, KeyHandler.KITTY_FLAG_REPORT_ALL);
        assertEquals(16, KeyHandler.KITTY_FLAG_REPORT_ASSOCIATED);
    }

    // ========== No-op when DISAMBIGUATE is absent ==========

    public void testGetKittyKeyCodeReturnsNullWhenDisambiguateOff() {
        assertNull(KeyHandler.getKittyKeyCode(KeyEvent.KEYCODE_DPAD_UP, 0,
            KeyHandler.KITTY_FLAG_NONE, 0));
    }

    public void testReportAllImpliesDisambiguation() {
        // Report-all implies disambiguation (kitty spec), so flags without bit 1 still encode.
        assertEquals("\u001b[97u", KeyHandler.getKittyKeyCode(KeyEvent.KEYCODE_UNKNOWN, 0,
            KeyHandler.KITTY_FLAG_REPORT_ALL, 'a'));
    }

    public void testGetKittyKeyCodeReturnsNullForUnknownKeyWithoutReportAll() {
        assertNull(KeyHandler.getKittyKeyCode(KeyEvent.KEYCODE_UNKNOWN, 0,
            KeyHandler.KITTY_FLAG_DISAMBIGUATE, 0));
    }

    public void testGetKittyKeyCodeUnknownWithReportAllFallsBackToUnicode() {
        String seq = KeyHandler.getKittyKeyCode(KeyEvent.KEYCODE_UNKNOWN, 0,
            KeyHandler.KITTY_FLAG_DISAMBIGUATE | KeyHandler.KITTY_FLAG_REPORT_ALL, 'z');
        assertEquals("\u001b[122u", seq);
    }

    // ========== Special keys: F1-F12 ==========

    public void testKittyKeyCodeF1() {
        assertEquals(Integer.valueOf(57364), KeyHandler.kittyKeyCode(KeyEvent.KEYCODE_F1));
    }

    public void testKittyKeyCodeF12() {
        assertEquals(Integer.valueOf(57375), KeyHandler.kittyKeyCode(KeyEvent.KEYCODE_F12));
    }

    public void testGetKittyKeyCodeF1WithShift() {
        assertNull(KeyHandler.getKittyKeyCode(KeyEvent.KEYCODE_F1, KeyHandler.KEYMOD_SHIFT,
            KeyHandler.KITTY_FLAG_DISAMBIGUATE, 0));
    }

    public void testGetKittyKeyCodeF12WithAltCtrl() {
        int mods = KeyHandler.KEYMOD_ALT | KeyHandler.KEYMOD_CTRL;
        assertNull(KeyHandler.getKittyKeyCode(KeyEvent.KEYCODE_F12, mods,
            KeyHandler.KITTY_FLAG_DISAMBIGUATE, 0));
    }

    // ========== Enter / Tab / Backspace / Space recovery exceptions ==========

    public void testUnmodifiedEnterTabBackspaceKeepLegacyBytes() {
        // Kitty spec: unmodified Enter/Tab/Backspace keep their legacy bytes unless
        // report-all is set, so a crashed kitty-mode program can be recovered at a shell.
        int flags = KeyHandler.KITTY_FLAG_DISAMBIGUATE | KeyHandler.KITTY_FLAG_REPORT_EVENT_TYPES;
        assertNull(KeyHandler.getKittyKeyCode(KeyEvent.KEYCODE_ENTER, 0, flags, 0));
        assertNull(KeyHandler.getKittyKeyCode(KeyEvent.KEYCODE_TAB, 0, flags, 0));
        assertNull(KeyHandler.getKittyKeyCode(KeyEvent.KEYCODE_DEL, 0, flags, 0));
        assertNull(KeyHandler.getKittyKeyCode(KeyEvent.KEYCODE_ENTER, 0, flags, 0, 3));
    }

    public void testUnmodifiedEnterTabBackspaceReportAll() {
        int flags = KeyHandler.KITTY_FLAG_REPORT_ALL;
        assertEquals("\u001b[13u", KeyHandler.getKittyKeyCode(KeyEvent.KEYCODE_ENTER, 0, flags, 0));
        assertEquals("\u001b[9u", KeyHandler.getKittyKeyCode(KeyEvent.KEYCODE_TAB, 0, flags, 0));
        assertEquals("\u001b[127u", KeyHandler.getKittyKeyCode(KeyEvent.KEYCODE_DEL, 0, flags, 0));
    }

    public void testShiftEnterUsesCsiU() {
        // Shift+Enter has no legacy representation; apps such as pi rely on `CSI 13;2 u`.
        int flags = KeyHandler.KITTY_FLAG_DISAMBIGUATE;
        assertEquals("\u001b[13;2u", KeyHandler.getKittyKeyCode(
            KeyEvent.KEYCODE_ENTER, KeyHandler.KEYMOD_SHIFT, flags, 0));
        assertEquals("\u001b[13;2:3u", KeyHandler.getKittyKeyCode(
            KeyEvent.KEYCODE_ENTER, KeyHandler.KEYMOD_SHIFT,
            flags | KeyHandler.KITTY_FLAG_REPORT_EVENT_TYPES, 0, 3));
    }

    public void testUnmodifiedSpaceKeepsLegacyText() {
        assertNull(KeyHandler.getKittyKeyCode(KeyEvent.KEYCODE_SPACE, 0,
            KeyHandler.KITTY_FLAG_DISAMBIGUATE, 0));
        assertEquals("\u001b[32;5u", KeyHandler.getKittyKeyCode(
            KeyEvent.KEYCODE_SPACE, KeyHandler.KEYMOD_CTRL, KeyHandler.KITTY_FLAG_DISAMBIGUATE, 0));
    }

    public void testEscapeIsDisambiguatedToCsiU() {
        int flags = KeyHandler.KITTY_FLAG_DISAMBIGUATE;
        assertEquals("\u001b[27u", KeyHandler.getKittyKeyCode(
            KeyEvent.KEYCODE_ESCAPE, 0, flags, 0));
        assertEquals("\u001b[27;1:3u", KeyHandler.getKittyKeyCode(
            KeyEvent.KEYCODE_ESCAPE, 0, flags | KeyHandler.KITTY_FLAG_REPORT_EVENT_TYPES, 0, 3));
        assertEquals("\u001b[27;5u", KeyHandler.getKittyKeyCode(
            KeyEvent.KEYCODE_ESCAPE, KeyHandler.KEYMOD_CTRL, flags, 0));
    }

    // ========== Keypad keys under disambiguation ==========

    public void testNonTextKeypadKeysUseDedicatedCsiUCodes() {
        int flags = KeyHandler.KITTY_FLAG_DISAMBIGUATE;
        assertEquals("\u001b[57417u", KeyHandler.getKittyKeyCode(
            KeyEvent.KEYCODE_NUMPAD_4, 0, flags, 0));
        assertEquals("\u001b[57420;1:3u", KeyHandler.getKittyKeyCode(
            KeyEvent.KEYCODE_NUMPAD_2, 0, flags | KeyHandler.KITTY_FLAG_REPORT_EVENT_TYPES, 0, 3));
        assertEquals("\u001b[57414u", KeyHandler.getKittyKeyCode(
            KeyEvent.KEYCODE_NUMPAD_ENTER, 0, flags, 0));
        assertEquals("\u001b[57426u", KeyHandler.getKittyKeyCode(
            KeyEvent.KEYCODE_NUMPAD_DOT, 0, flags, 0));
    }

    public void testNumLockDigitsRemainTextKeys() {
        assertNull(KeyHandler.getKittyKeyCode(KeyEvent.KEYCODE_NUMPAD_4,
            KeyHandler.KEYMOD_NUM_LOCK, KeyHandler.KITTY_FLAG_DISAMBIGUATE, 0));
        assertNull(KeyHandler.getKittyKeyCode(KeyEvent.KEYCODE_NUMPAD_DOT,
            KeyHandler.KEYMOD_NUM_LOCK, KeyHandler.KITTY_FLAG_DISAMBIGUATE, 0));
    }

    // ========== SysRq / Break ==========

    public void testKittyKeyCodeSysRq() {
        assertEquals(Integer.valueOf(57361), KeyHandler.kittyKeyCode(KeyEvent.KEYCODE_SYSRQ));
    }

    public void testKittyKeyCodeBreak() {
        assertEquals(Integer.valueOf(57362), KeyHandler.kittyKeyCode(KeyEvent.KEYCODE_BREAK));
    }

    // ========== Tab / Enter / Escape ==========

    public void testKittyKeyCodeTab() {
        assertEquals(Integer.valueOf(9), KeyHandler.kittyKeyCode(KeyEvent.KEYCODE_TAB));
    }

    public void testKittyKeyCodeEnter() {
        assertEquals(Integer.valueOf(13), KeyHandler.kittyKeyCode(KeyEvent.KEYCODE_ENTER));
    }

    public void testKittyKeyCodeEscape() {
        assertEquals(Integer.valueOf(27), KeyHandler.kittyKeyCode(KeyEvent.KEYCODE_ESCAPE));
    }

    public void testKittyKeyCodeBack() {
        assertEquals(Integer.valueOf(27), KeyHandler.kittyKeyCode(KeyEvent.KEYCODE_BACK));
    }

    // ========== Backspace / Delete ==========

    public void testKittyKeyCodeDel() {
        assertEquals(Integer.valueOf(127), KeyHandler.kittyKeyCode(KeyEvent.KEYCODE_DEL));
    }
}
