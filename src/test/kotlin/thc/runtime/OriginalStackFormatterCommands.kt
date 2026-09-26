// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
@file:Suppress("UNCHECKED_CAST")
package thc.runtime

import java.io.File

/** Exact successful, no-stdin receipts from FixtureSupport.runLogged in StackFixtures. */
internal object OriginalStackFormatterCommands {
    val labels = listOf("ghc-version", "plugin-build", "original-source-export", "pre-export", "post-export",
        "native-compile", "native-observations", "pre-audit", "post-audit")
    private val keys = setOf("argv", "environment", "cwd", "exit", "expectedExit", "timeoutSeconds")

    fun checked(raw: Any?): List<Map<String, Any?>> {
        require(raw is List<*> && raw.size == labels.size) { "Original formatter command count" }
        val commands = raw.mapIndexed { index, value ->
            val label = labels[index]
            require(value is Map<*, *> && value.keys == keys) { "Original formatter $label command fields" }
            val argv = value["argv"]
            require(argv is List<*> && argv.isNotEmpty() && argv.all { it is String && '\u0000' !in it } &&
                (argv.first() as String).isNotEmpty()) { "Original formatter $label argv" }
            val environment = value["environment"]
            require(environment is Map<*, *> && environment.all { (key, item) ->
                key is String && key.isNotEmpty() && '\u0000' !in key && item is String && '\u0000' !in item
            }) { "Original formatter $label environment" }
            val cwd = value["cwd"]
            require(cwd is String && '\u0000' !in cwd && File(cwd).isAbsolute) { "Original formatter $label cwd" }
            require(value["exit"] == 0L && value["expectedExit"] == 0L) { "Original formatter $label did not succeed" }
            val timeout = if (label == "original-source-export") 600L else 300L
            require(value["timeoutSeconds"] == timeout) { "Original formatter $label timeout" }
            value as Map<String, Any?>
        }
        // Cached evidence may have been produced in another checkout. Retain that
        // absolute producer cwd and require one origin; do not rewrite its logs.
        require(commands.map { it["cwd"] }.distinct().size == 1) { "Original formatter commands have mixed origins" }
        return commands
    }
}
