// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc

import com.sun.management.ThreadMXBean
import java.lang.management.ManagementFactory
import java.nio.file.Files
import java.nio.file.Path
import org.graalvm.polyglot.Context
import org.graalvm.polyglot.Value

/** Cold lifecycle diagnostics, deliberately separate from Probe's throughput runs. */
private class PhaseMeasurements {
    private val threads = ManagementFactory.getThreadMXBean() as? ThreadMXBean
    private val memory = ManagementFactory.getMemoryMXBean()
    private val collectors = ManagementFactory.getGarbageCollectorMXBeans()

    private fun allocated(): Long = threads?.takeIf { it.isThreadAllocatedMemorySupported }
        ?.getThreadAllocatedBytes(Thread.currentThread().threadId()) ?: -1L

    fun <T> measure(name: String, action: () -> T): T {
        val allocatedBefore = allocated()
        val collectionsBefore = collectors.sumOf { it.collectionCount }
        val collectionMsBefore = collectors.sumOf { it.collectionTime }
        val start = System.nanoTime()
        var failure: Throwable? = null
        try {
            return action()
        } catch (error: Throwable) {
            failure = error
            throw error
        } finally {
            val elapsed = System.nanoTime() - start
            val allocatedAfter = allocated()
            println(Json.stringify(linkedMapOf(
                "phase" to name, "elapsedNs" to elapsed,
                "callingThreadAllocatedBytes" to if (allocatedBefore < 0 || allocatedAfter < 0) null
                    else allocatedAfter - allocatedBefore,
                "heapUsedBytes" to memory.heapMemoryUsage.used,
                "gcCount" to collectors.sumOf { it.collectionCount } - collectionsBefore,
                "gcTimeMs" to collectors.sumOf { it.collectionTime } - collectionMsBefore,
                "failure" to failure?.toString())))
        }
    }

    /** Whole-process observations, not retained Core-only object sizes. */
    fun memoryCheckpoint(name: String) {
        val before = collectors.sumOf { it.collectionCount }
        System.gc()
        check(collectors.sumOf { it.collectionCount } > before) {
            "Explicit GC did not complete at $name; no post-GC measurement available"
        }
        val status = Path.of("/proc/self/status")
        val rss = if (Files.isRegularFile(status)) Files.readAllLines(status)
            .filter { it.startsWith("VmRSS:") || it.startsWith("VmHWM:") }
            .associate { line ->
                val fields = line.trim().split(Regex("\\s+"))
                check(fields.size == 3 && fields[2] == "kB") { "Unexpected Linux RSS units: $line" }
                fields[0].removeSuffix(":") to fields[1].toLong()
            } else emptyMap()
        println(Json.stringify(linkedMapOf("checkpoint" to name, "explicitGc" to true,
            "heapUsedBytes" to memory.heapMemoryUsage.used,
            "processRssKiB" to rss["VmRSS"], "processHighWaterRssKiB" to rss["VmHWM"],
            "scope" to "whole-process, not Core-only retained memory")))
    }
}

/** Loading experiment only: no training, compilation or throughput claim.
 * Eager and indexed variants use identical JSON and settings in fresh JVMs.
 * The original compiled-call probe below remains a separate strict control.
 */
private fun jsonLoadProbe(args: Array<String>) {
    require(args.size == 5 || args.size == 7) {
        "Usage: phase-probe --load-json MODULE ENTRY INPUT EXPECTED [NEXT_INPUT NEXT_EXPECTED]; optional -Dthc.jsonIndex=PATH"
    }
    val phases = PhaseMeasurements()
    val backend = System.getProperty("thc.backend", "bytecode")
    val async = System.getProperty("thc.asyncExceptions", "true").toBooleanStrict()
    val notes = System.getProperty("thc.sourceNotesEnabled", "false").toBooleanStrict()
    val index = System.getProperty("thc.jsonIndex")
    println(Json.stringify(mapOf("mode" to "load-only", "indexed" to (index != null),
        "backend" to backend, "asyncExceptions" to async, "sourceNotesEnabled" to notes)))
    val context = phases.measure("context") { executionContext() }
    try {
        phases.memoryCheckpoint("preLoad")
        val function = run {
            val request = phases.measure("request") {
                CoreModules.request(listOf(args[1]), args[2], instrument = true, backend = backend,
                    sourceNotesEnabled = notes, asyncExceptions = async,
                    jsonSidecars = index?.let { mapOf(args[1] to it) })
            }
            phases.measure("eval") { context.eval("thc", request) }
        }
        println(function.getMember("diagnostics").asString())
        phases.memoryCheckpoint("postLoadPreEntry")
        for (offset in 3 until args.size step 2) {
            val input = args[offset].toLong()
            val expected = args[offset + 1].toLong()
            phases.measure("demand${(offset - 3) / 2 + 1}") {
                val actual = function.execute(input).asLong()
                check(actual == expected) { "${args[2]}($input): $actual != expected $expected" }
            }
            println(function.getMember("diagnostics").asString())
            phases.memoryCheckpoint("postDemand${(offset - 3) / 2 + 1}")
        }
    } finally {
        phases.measure("close") { context.close() }
    }
}

/**
 * Uses the runtime's actual launcher and stable Java overload, so this one tools
 * JAR can measure older frozen distributions without changing their runtime code.
 * Allocation counts cover this calling thread only, not Graal compiler workers.
 */
fun main(args: Array<String>) {
    if (args.firstOrNull() == "--load-json") return jsonLoadProbe(args)
    require(args.size == 4) { "Usage: phase-probe MODULES ENTRY INPUT NATIVE_EXPECTED" }
    val modules = args[0].split(',')
    val entry = args[1]
    val input = args[2].toLong()
    val expected = args[3].toLong()
    val phases = PhaseMeasurements()
    val launcher = Class.forName("thc.MainKt")
    val context = phases.measure("context") {
        // Select the stable public signature, not internal launcher overloads.
        val factories = launcher.methods.filter {
            it.name == "executionContext" && it.parameterCount == 1 &&
                it.parameterTypes[0] == Boolean::class.javaPrimitiveType
        }
        val factory = if (factories.isEmpty()) launcher.getMethod("executionContext") else factories.single()
        (if (factory.parameterCount == 0) factory.invoke(null)
            else factory.invoke(null, false)) as Context
    }
    try {
        val function = phases.measure("load") {
            launcher.getMethod("loadEntry", Context::class.java, List::class.java,
                String::class.java, Boolean::class.javaPrimitiveType, String::class.java)
                .invoke(null, context, modules, entry, true,
                    System.getProperty("thc.backend", "bytecode")) as Value
        }
        fun checkResult(): Long = function.execute(input).asLong().also {
            check(it == expected) { "$entry($input): $it != native $expected" }
        }
        phases.measure("firstResult") { checkResult() }
        phases.measure("training200") { repeat(200) { checkResult() } }
        phases.measure("compile") { check(function.invokeMember("compile").asBoolean()) }
        fun compiledEntries(): Long =
            ((Json.parse(function.getMember("diagnostics").asString()) as Map<*, *>)["compiledEntries"] as Number).toLong()
        val before = compiledEntries()
        phases.measure("firstCompiledResult") { checkResult() }
        check(compiledEntries() > before) { "First call after compilation did not enter installed guest code" }
        println(function.getMember("diagnostics").asString())
    } finally {
        phases.measure("close") { context.close() }
    }
}
