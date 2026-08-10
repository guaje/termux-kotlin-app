package com.termux.app.pkg.cli.commands.device

import com.termux.app.core.api.Result
import com.termux.app.core.deviceapi.actions.BatteryAction
import com.termux.app.core.deviceapi.actions.ClipboardAction
import com.termux.app.core.deviceapi.actions.ToastAction
import com.termux.app.core.deviceapi.actions.TorchAction
import com.termux.app.core.deviceapi.actions.VibrateAction
import com.termux.app.core.deviceapi.models.DeviceApiAction
import com.termux.app.core.logging.TermuxLogger
import kotlinx.coroutines.runBlocking
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Device API CLI commands for termuxctl.
 *
 * Usage:
 * ```bash
 * termuxctl device battery         # Get battery status
 * termuxctl device battery --json  # Get battery status as JSON
 * termuxctl device clipboard-set --text "hello"
 * termuxctl device toast --text "Hello!"
 * termuxctl device torch --enabled true
 * termuxctl device vibrate --duration 200
 * termuxctl device list            # List available device APIs
 * termuxctl device --help          # Show help
 * ```
 */
@Singleton
class DeviceCommands @Inject constructor(
    private val batteryAction: BatteryAction,
    private val clipboardAction: ClipboardAction,
    private val vibrateAction: VibrateAction,
    private val toastAction: ToastAction,
    private val torchAction: TorchAction,
    private val logger: TermuxLogger
) {
    companion object {
        private const val RESET = "\u001B[0m"
        private const val RED = "\u001B[31m"
        private const val GREEN = "\u001B[32m"
        private const val YELLOW = "\u001B[33m"
        private const val CYAN = "\u001B[36m"
        private const val BOLD = "\u001B[1m"
    }

    private val log = logger.forTag("DeviceCommands")

    fun execute(args: List<String>): Int = runBlocking {
        if (args.isEmpty()) {
            printUsage()
            return@runBlocking 1
        }

        when (args[0]) {
            "battery" -> handleBattery(args.drop(1))
            "clipboard-get" -> handleClipboardGet(args.drop(1))
            "clipboard-set" -> handleClipboardSet(args.drop(1))
            "toast" -> handleToast(args.drop(1))
            "torch" -> handleTorch(args.drop(1))
            "vibrate" -> handleVibrate(args.drop(1))
            "list" -> handleList()
            "--help", "-h", "help" -> { printUsage(); 0 }
            else -> {
                printError("Unknown device command: ${args[0]}")
                printUsage()
                1
            }
        }
    }

    // ========== Battery ==========

    private suspend fun handleBattery(args: List<String>): Int {
        val useJson = "--json" in args || "-j" in args
        val extended = "--extended" in args || "-e" in args
        log.d("battery", mapOf("json" to useJson, "extended" to extended))
        return when (val result = batteryAction.execute()) {
            is Result.Success -> {
                if (useJson) println(result.data.toJsonOutput())
                else {
                    println(result.data.toTerminalOutput())
                    if (extended) batteryAction.getExtendedBatteryInfo()?.let { println(it.toTerminalOutput()) }
                }
                0
            }
            is Result.Error -> { printError(result.error.message); 1 }
            is Result.Loading -> { printError("Unexpected loading"); 1 }
        }
    }

    // ========== Clipboard ==========

    private suspend fun handleClipboardGet(args: List<String>): Int {
        return when (val result = clipboardAction.execute(emptyMap())) {
            is Result.Success -> { println(result.data); 0 }
            is Result.Error -> { printError(result.error.message); 1 }
            is Result.Loading -> { printError("Unexpected loading"); 1 }
        }
    }

    private suspend fun handleClipboardSet(args: List<String>): Int {
        val textIndex = args.indexOf("--text").takeIf { it >= 0 } ?: args.indexOf("-t")
        val text = if (textIndex >= 0 && textIndex + 1 < args.size) args[textIndex + 1] else null
        if (text == null) {
            printError("Missing --text value")
            return 1
        }
        return when (val result = clipboardAction.execute(mapOf("text" to text))) {
            is Result.Success -> { 0 }
            is Result.Error -> { printError(result.error.message); 1 }
            is Result.Loading -> { printError("Unexpected loading"); 1 }
        }
    }

    // ========== Toast ==========

    private suspend fun handleToast(args: List<String>): Int {
        val textIndex = args.indexOf("--text").takeIf { it >= 0 } ?: args.indexOf("-t")
        val text = if (textIndex >= 0 && textIndex + 1 < args.size) args[textIndex + 1] else null
        if (text == null) {
            printError("Missing --text value")
            return 1
        }
        return when (val result = toastAction.execute(mapOf("text" to text))) {
            is Result.Success -> 0
            is Result.Error -> { printError(result.error.message); 1 }
            is Result.Loading -> { printError("Unexpected loading"); 1 }
        }
    }

    // ========== Torch ==========

    private suspend fun handleTorch(args: List<String>): Int {
        val enabledIndex = args.indexOf("--enabled").takeIf { it >= 0 } ?: args.indexOf("-e")
        val enabled = if (enabledIndex >= 0 && enabledIndex + 1 < args.size)
            args[enabledIndex + 1].toBooleanStrictOrNull() else null
        if (enabled == null) {
            printError("Missing --enabled true|false")
            return 1
        }
        return when (val result = torchAction.execute(mapOf("enabled" to enabled.toString()))) {
            is Result.Success -> { println("Torch ${if (result.data) "ON" else "OFF"}"); 0 }
            is Result.Error -> { printError(result.error.message); 1 }
            is Result.Loading -> { printError("Unexpected loading"); 1 }
        }
    }

    // ========== Vibrate ==========

    private suspend fun handleVibrate(args: List<String>): Int {
        val duration = parseDurationArg(args)
            ?: run { printError("Invalid duration value"); return 1 }
        val force = ("--force" in args)
        return when (val result = vibrateAction.execute(mapOf("duration" to duration.toString(), "force" to force.toString()))) {
            is Result.Success -> 0
            is Result.Error -> { printError(result.error.message); 1 }
            is Result.Loading -> { printError("Unexpected loading"); 1 }
        }
    }

    /**
     * Parse --duration flag in both `--duration <ms>` and `--duration=<ms>` forms.
     * Returns the duration in ms, or null if the value is malformed.
     * Defaults to 300L if the flag is absent entirely.
     */
    private fun parseDurationArg(args: List<String>): Long? {
        val equalsForm = args.find { it.startsWith("--duration=") }
        if (equalsForm != null) {
            val raw = equalsForm.substringAfter("=", missingDelimiterValue = "")
            return raw.toLongOrNull()
        }

        // Flag-value form: --duration NNN
        val flagIdx = args.indexOf("--duration")
        if (flagIdx >= 0) {
            if (flagIdx + 1 >= args.size) return null
            val raw = args[flagIdx + 1]
            return raw.toLongOrNull()
        }

        return 300L
    }

    // ========== List ==========

    private fun handleList(): Int {
        println("${BOLD}Available Device APIs:${RESET}")
        println()
        DeviceApiAction.entries.groupBy { it.actionName.substringBefore("-") }
            .forEach { (category, actions) ->
                println("${CYAN}${category.replaceFirstChar { it.uppercase() }}${RESET}")
                actions.forEach { action ->
                    val status = if (action.actionName in implementedActions) {
                        "${GREEN}✓${RESET}"
                    } else {
                        "${YELLOW}○${RESET}"
                    }
                    println("  $status ${action.actionName.padEnd(20)} ${action.description}")
                }
                println()
            }
        println("${GREEN}✓${RESET} = Implemented  ${YELLOW}○${RESET} = Planned")
        return 0
    }

    private val implementedActions = setOf(
        "battery",
        "clipboard-get", "clipboard-set",
        "toast",
        "torch",
        "vibrate"
    )

    // ========== Usage ==========

    private fun printUsage() {
        println(
            """
            ${BOLD}termuxctl device${RESET} - Access device APIs

            ${BOLD}Usage:${RESET} termuxctl device <command> [options]

            ${BOLD}Commands:${RESET}
              battery          Get battery status information
              clipboard-get    Read system clipboard
              clipboard-set    Write to system clipboard (--text <value>)
              toast            Show a toast (--text <value>)
              torch            Toggle flashlight (--enabled true|false)
              vibrate          Vibrate device (--duration <ms>)
              list             List available device APIs

            ${BOLD}Examples:${RESET}
              ${CYAN}termuxctl device battery --json${RESET}
              ${CYAN}termuxctl device clipboard-set --text "hello world"${RESET}
              ${CYAN}termuxctl device torch --enabled true${RESET}
              ${CYAN}termuxctl device vibrate --duration=500${RESET}
            """.trimIndent()
        )
    }

    private fun printError(message: String) {
        System.err.println("${RED}Error:${RESET} $message")
    }
}
