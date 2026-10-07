package com.termux.app.reliability

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BatteryOptimizationWarningPolicyTest {
    @Test
    fun `exempt app never warns`() {
        assertFalse(BatteryOptimizationWarningPolicy.shouldWarn(true, true, false))
    }

    @Test
    fun `non-exempt enabled and undismissed warning is shown`() {
        assertTrue(BatteryOptimizationWarningPolicy.shouldWarn(false, true, false))
    }

    @Test
    fun `disabled warning is never shown`() {
        assertFalse(BatteryOptimizationWarningPolicy.shouldWarn(false, false, false))
    }

    @Test
    fun `dismissed warning is never shown`() {
        assertFalse(BatteryOptimizationWarningPolicy.shouldWarn(false, true, true))
    }
}
