package com.termux.app.reliability

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class PhantomProcessPolicyTest {
    @Test
    fun `missing maximum is unknown`() {
        assertEquals(
            PhantomProcessWarningLevel.UNKNOWN,
            PhantomProcessPolicy.evaluate(null, 7, true, false)
        )
    }

    @Test
    fun `low maximum with unknown own count reports low limit`() {
        assertEquals(
            PhantomProcessWarningLevel.LOW_LIMIT,
            PhantomProcessPolicy.evaluate(32, null, true, false)
        )
    }

    @Test
    fun `low maximum at seventy five percent reports both conditions`() {
        assertEquals(
            PhantomProcessWarningLevel.BOTH,
            PhantomProcessPolicy.evaluate(32, 24, true, false)
        )
    }

    @Test
    fun `low maximum far from capacity reports low limit`() {
        assertEquals(
            PhantomProcessWarningLevel.LOW_LIMIT,
            PhantomProcessPolicy.evaluate(32, 5, true, false)
        )
    }

    @Test
    fun `high maximum far from capacity reports none`() {
        assertEquals(
            PhantomProcessWarningLevel.NONE,
            PhantomProcessPolicy.evaluate(32768, 7, true, false)
        )
    }

    @Test
    fun `dismissed warning reports none`() {
        assertEquals(
            PhantomProcessWarningLevel.NONE,
            PhantomProcessPolicy.evaluate(32, 24, true, true)
        )
    }

    @Test
    fun `disabled warning reports none`() {
        assertEquals(
            PhantomProcessWarningLevel.NONE,
            PhantomProcessPolicy.evaluate(32, 24, false, false)
        )
    }

    @Test
    fun `unknown level does not warn`() {
        assertFalse(PhantomProcessPolicy.shouldWarn(PhantomProcessWarningLevel.UNKNOWN))
    }
}
