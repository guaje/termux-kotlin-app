package com.termux.app.ssh

import com.termux.shared.termux.settings.properties.TermuxPropertyConstants
import com.termux.shared.termux.settings.properties.TermuxSharedProperties
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SshAgentPropertiesTest {
    @Test
    fun `disable ssh agent property is registered and defaults to false`() {
        assertTrue(TermuxPropertyConstants.TERMUX_APP_PROPERTIES_LIST.contains(TermuxPropertyConstants.KEY_DISABLE_SSH_AGENT))
        assertFalse(
            TermuxSharedProperties.getInternalTermuxPropertyValueFromValue(
                null,
                TermuxPropertyConstants.KEY_DISABLE_SSH_AGENT,
                null
            ) as Boolean
        )
        assertTrue(
            TermuxSharedProperties.getInternalTermuxPropertyValueFromValue(
                null,
                TermuxPropertyConstants.KEY_DISABLE_SSH_AGENT,
                "true"
            ) as Boolean
        )
    }
}
