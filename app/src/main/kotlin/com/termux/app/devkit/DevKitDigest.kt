package com.termux.app.devkit

import java.security.MessageDigest

/**
 * SHA-256 rendered as lowercase hex, without asking the default locale how to spell a digit.
 *
 * The obvious `"%02x".format(byte)` honours the default locale, and a locale whose number system is
 * not ASCII - Arabic with arabic-indic digits is the one anyone will actually hit - renders a
 * certificate fingerprint with non-ASCII digits. The same bytes then produce different strings on
 * two devices, and the two refuse each other.
 *
 * That matters more here than in a log line: these fingerprints decide whether an APK replacement is
 * allowed, and the failure this creates is a wrong-but-plausible string rather than an error.
 */
internal object DevKitDigest {

    private const val HEX_DIGITS = "0123456789abcdef"

    /** Lowercase hex SHA-256 of [bytes], or null if the platform has no SHA-256 (it always does). */
    fun sha256Hex(bytes: ByteArray): String? {
        val digest = runCatching { MessageDigest.getInstance("SHA-256").digest(bytes) }.getOrNull()
            ?: return null
        val out = StringBuilder(digest.size * 2)
        for (byte in digest) {
            val value = byte.toInt().and(0xff)
            out.append(HEX_DIGITS[value shr 4]).append(HEX_DIGITS[value and 0x0f])
        }
        return out.toString()
    }
}
