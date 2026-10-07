package com.termux.app.reliability

enum class PhantomProcessWarningLevel {
    UNKNOWN,
    NONE,
    LOW_LIMIT,
    NEAR_LIMIT,
    BOTH
}

/**
 * Evaluates phantom-process warning conditions. The configured limit is device-wide, while
 * `ownProcessCount` is only Termux's own contribution and therefore a lower bound.
 */
object PhantomProcessPolicy {
    const val LOW_LIMIT_MAX_THRESHOLD = 64
    const val NEAR_LIMIT_NUMERATOR = 3
    const val NEAR_LIMIT_DENOMINATOR = 4

    fun evaluate(
        maxPhantomProcesses: Int?,
        ownProcessCount: Int?,
        warningEnabled: Boolean,
        alreadyDismissed: Boolean
    ): PhantomProcessWarningLevel {
        if (!warningEnabled || alreadyDismissed) return PhantomProcessWarningLevel.NONE
        if (maxPhantomProcesses == null || maxPhantomProcesses <= 0) {
            return PhantomProcessWarningLevel.UNKNOWN
        }

        val lowLimit = maxPhantomProcesses <= LOW_LIMIT_MAX_THRESHOLD
        val nearLimit = ownProcessCount != null &&
            ownProcessCount.toLong() * NEAR_LIMIT_DENOMINATOR >=
            maxPhantomProcesses.toLong() * NEAR_LIMIT_NUMERATOR

        return when {
            lowLimit && nearLimit -> PhantomProcessWarningLevel.BOTH
            lowLimit -> PhantomProcessWarningLevel.LOW_LIMIT
            nearLimit -> PhantomProcessWarningLevel.NEAR_LIMIT
            else -> PhantomProcessWarningLevel.NONE
        }
    }

    fun shouldWarn(level: PhantomProcessWarningLevel): Boolean =
        level != PhantomProcessWarningLevel.NONE && level != PhantomProcessWarningLevel.UNKNOWN
}
