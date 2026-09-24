// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc

import java.io.File

/** Differential check of an ordinary containers program against native GHC. */
fun main(args: Array<String>) {
    require(args.size == 2) { "Usage: map-check MODULES_FILE ORACLE_TSV" }
    val modules = File(args[0]).readLines().filter { it.isNotBlank() }
    val rows = File(args[1]).readLines().filter { it.isNotBlank() }.map { line ->
        val fields = line.split('\t')
        require(fields.size == 3 && fields[0] == "mapAggregate")
        fields[1].toLong() to fields[2].toLong()
    }
    require(rows.isNotEmpty())
    executionContext().use { context ->
        val function = loadEntry(context, modules, "mapAggregate")
        fun checkRows(phase: String) {
            for ((input, expected) in rows) {
                val actual = function.execute(input).asLong()
                check(actual == expected) { "$phase mapAggregate($input): $actual != native $expected" }
                println("VERIFIED_MAP\t$phase\t$input\t$actual")
            }
        }
        checkRows("before-requested-compilation")
        repeat(40) { function.execute(256L + (it and 15)).asLong() }
        check(function.invokeMember("compile").asBoolean())
        fun compiledEntries(): Long {
            val diagnostics = Json.parse(function.getMember("diagnostics").asString()) as Map<*, *>
            return (diagnostics["compiledEntries"] as Number).toLong()
        }
        val before = compiledEntries()
        checkRows("after-requested-compilation")
        check(compiledEntries() > before) { "No installed guest code was executed" }
        val finalDiagnostics = Json.parse(function.getMember("diagnostics").asString()) as Map<*, *>
        check((finalDiagnostics["unsupportedTraps"] as Number).toLong() == 0L) { "An unsupported path was entered" }
        println("MAP_DIAGNOSTICS ${function.getMember("diagnostics").asString()}")
    }
}
