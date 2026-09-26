// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import thc.Json

class OriginalStackFormatterCommandTest {
    @Suppress("UNCHECKED_CAST")
    private fun records() = Json.parse(Json.stringify(OriginalStackFormatterCommands.labels.map { label ->
        mapOf("argv" to listOf("tool", "--flag"), "environment" to mapOf("KEY" to "value"),
            "cwd" to "/producer/checkout", "exit" to 0L, "expectedExit" to 0L,
            "timeoutSeconds" to if (label == "original-source-export") 600L else 300L)
    })) as List<Map<String, Any?>>

    @Test fun currentSuccessfulReceiptsRetainTheirProducerOrigin() {
        val records = records()
        assertEquals(records, OriginalStackFormatterCommands.checked(records))
    }

    @Test fun incompleteFailedOrUnexpectedReceiptsReject() {
        val records = records()
        for (index in records.indices) {
            fun reject(command: Map<String, Any?>) {
                assertThrows(IllegalArgumentException::class.java) {
                    OriginalStackFormatterCommands.checked(records.toMutableList().also { it[index] = command })
                }
            }
            val command = records[index]
            for (key in command.keys) reject(command - key)
            for ((key, bad) in listOf("exit" to 1L, "expectedExit" to 1L, "exit" to null,
                "exit" to 0.0, "expectedExit" to 0.0, "timeoutSeconds" to 0L,
                "timeoutSeconds" to 300.0, "cwd" to "relative", "cwd" to "/other/checkout",
                "argv" to emptyList<String>(), "argv" to listOf(1L), "environment" to mapOf("KEY" to 1L),
                "stdin" to "input", "timedOut" to false)) reject(command + (key to bad))
            reject(command + ("timeoutSeconds" to if (index == 2) 300L else 600L))
        }
        assertThrows(IllegalArgumentException::class.java) { OriginalStackFormatterCommands.checked(records.dropLast(1)) }
        assertThrows(IllegalArgumentException::class.java) { OriginalStackFormatterCommands.checked(null) }
    }
}
