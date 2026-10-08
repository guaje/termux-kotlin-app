package com.termux.app.reliability

object BatteryOptimizationWarningPolicy {
    fun shouldWarn(
        isIgnoring: Boolean,
        warningEnabled: Boolean,
        alreadyDismissed: Boolean
    ): Boolean = !isIgnoring && warningEnabled && !alreadyDismissed
}
