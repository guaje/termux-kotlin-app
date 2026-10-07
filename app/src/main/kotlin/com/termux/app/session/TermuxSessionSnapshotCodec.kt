package com.termux.app.session

data class TermuxSessionSnapshot(
    val executable: String?,
    val arguments: List<String>,
    val workingDirectory: String?,
    val sessionName: String?,
    val asDefaultLoginShell: Boolean
)

/**
 * Wire format:
 * - Line 0 must equal [HEADER], otherwise `Failed("bad_header")`.
 * - Each subsequent non-blank line is one record of 5 fields separated by the unit separator
 *   `'\u001F'`.
 * - Field encoding: `"n"` means null; `"v" + escaped` means value. The `arguments` field is `"n"`
 *   or `"v"` followed by items joined with the record separator `'\u001E'`.
 * - Per-token escaping: `\` becomes `\\`, newline becomes `\n`, carriage return becomes `\r`,
 *   `'\u001F'` becomes `\u`, and `'\u001E'` becomes `\v`. An unrecognised escape sequence results
 *   in `Failed("malformed")`.
 * - The last field encodes `asDefaultLoginShell` as the flag string `"L"` (true) / `"n"` (false).
 * - A record whose field count is not 5 results in `Failed("malformed")` (callers clear the
 *   snapshot rather than restore partially).
 * - `decode(null)` / `decode("")` results in `Failed("empty")`.
 * - [encode] keeps only the last [MAX_SNAPSHOT_SESSIONS] entries, in list order.
 */
object TermuxSessionSnapshotCodec {
    const val MAX_SNAPSHOT_SESSIONS = 8
    const val HEADER = "termux-sessions/v1"

    private const val UNIT_SEPARATOR = '\u001F'
    private const val RECORD_SEPARATOR = '\u001E'

    sealed class DecodeResult {
        data class Success(val sessions: List<TermuxSessionSnapshot>) : DecodeResult()
        data class Failed(val reason: String) : DecodeResult()
    }

    fun encode(sessions: List<TermuxSessionSnapshot>): String {
        val records = sessions.takeLast(MAX_SNAPSHOT_SESSIONS).map { session ->
            listOf(
                encodeNullable(session.executable),
                encodeArguments(session.arguments),
                encodeNullable(session.workingDirectory),
                encodeNullable(session.sessionName),
                if (session.asDefaultLoginShell) "L" else "n"
            ).joinToString(UNIT_SEPARATOR.toString())
        }

        return if (records.isEmpty()) HEADER else HEADER + "\n" + records.joinToString("\n")
    }

    fun decode(data: String?): DecodeResult {
        if (data.isNullOrEmpty()) return DecodeResult.Failed("empty")

        val lines = data.split('\n')
        if (lines.first() != HEADER) return DecodeResult.Failed("bad_header")

        val sessions = mutableListOf<TermuxSessionSnapshot>()
        for (line in lines.drop(1)) {
            if (line.isBlank()) continue

            val fields = line.split(UNIT_SEPARATOR)
            if (fields.size != 5) return DecodeResult.Failed("malformed")

            val executable = decodeNullable(fields[0])
            val arguments = decodeArguments(fields[1])
            val workingDirectory = decodeNullable(fields[2])
            val sessionName = decodeNullable(fields[3])
            if (executable is ParsedValue.Failed ||
                arguments is ParsedArguments.Failed ||
                workingDirectory is ParsedValue.Failed ||
                sessionName is ParsedValue.Failed) {
                return DecodeResult.Failed("malformed")
            }

            val asDefaultLoginShell = when (fields[4]) {
                "L" -> true
                "n" -> false
                else -> return DecodeResult.Failed("malformed")
            }

            sessions.add(
                TermuxSessionSnapshot(
                    (executable as ParsedValue.Value).value,
                    (arguments as ParsedArguments.Value).value,
                    (workingDirectory as ParsedValue.Value).value,
                    (sessionName as ParsedValue.Value).value,
                    asDefaultLoginShell
                )
            )
        }

        return DecodeResult.Success(sessions)
    }

    private fun encodeNullable(value: String?): String =
        if (value == null) "n" else "v${escape(value)}"

    private fun encodeArguments(arguments: List<String>): String =
        if (arguments.isEmpty()) {
            "n"
        } else {
            "v" + arguments.joinToString(RECORD_SEPARATOR.toString()) { escape(it) }
        }

    private fun escape(value: String): String = buildString {
        for (character in value) {
            when (character) {
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                UNIT_SEPARATOR -> append("\\u")
                RECORD_SEPARATOR -> append("\\v")
                else -> append(character)
            }
        }
    }

    private fun decodeNullable(field: String): ParsedValue {
        if (field == "n") return ParsedValue.Value(null)
        if (!field.startsWith('v')) return ParsedValue.Failed
        return when (val value = unescape(field.substring(1))) {
            is Unescaped.Value -> ParsedValue.Value(value.value)
            Unescaped.Failed -> ParsedValue.Failed
        }
    }

    private fun decodeArguments(field: String): ParsedArguments {
        if (field == "n") return ParsedArguments.Value(emptyList())
        if (!field.startsWith('v')) return ParsedArguments.Failed

        val arguments = mutableListOf<String>()
        for (item in field.substring(1).split(RECORD_SEPARATOR)) {
            when (val value = unescape(item)) {
                is Unescaped.Value -> arguments.add(value.value)
                Unescaped.Failed -> return ParsedArguments.Failed
            }
        }
        return ParsedArguments.Value(arguments)
    }

    private fun unescape(value: String): Unescaped {
        val decoded = StringBuilder()
        var index = 0
        while (index < value.length) {
            val character = value[index]
            if (character != '\\') {
                decoded.append(character)
                index++
                continue
            }

            if (index + 1 >= value.length) return Unescaped.Failed
            when (value[index + 1]) {
                '\\' -> decoded.append('\\')
                'n' -> decoded.append('\n')
                'r' -> decoded.append('\r')
                'u' -> decoded.append(UNIT_SEPARATOR)
                'v' -> decoded.append(RECORD_SEPARATOR)
                else -> return Unescaped.Failed
            }
            index += 2
        }
        return Unescaped.Value(decoded.toString())
    }

    private sealed class ParsedValue {
        data class Value(val value: String?) : ParsedValue()
        object Failed : ParsedValue()
    }

    private sealed class ParsedArguments {
        data class Value(val value: List<String>) : ParsedArguments()
        object Failed : ParsedArguments()
    }

    private sealed class Unescaped {
        data class Value(val value: String) : Unescaped()
        object Failed : Unescaped()
    }
}
