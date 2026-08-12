package com.termux.api.handlers

import android.content.Intent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ClipboardHandlerTest {

    @Test
    fun `set request uses the extra emitted by termux-clipboard-set`() {
        val intent = Intent().putExtra("set", true)

        assertTrue(ClipboardHandler.isSetRequest(intent))
    }

    @Test
    fun `clipboard get remains the default operation`() {
        assertFalse(ClipboardHandler.isSetRequest(Intent()))
    }
}
