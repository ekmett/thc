// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc

import com.sun.management.ThreadMXBean
import java.lang.management.ManagementFactory
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
}

/**
 * Uses the runtime's actual launcher and stable Java overload, so this one tools
 * JAR can measure older frozen distributions without changing their runtime code.
 * Allocation counts cover this calling thread only, not Graal compiler workers.
 */
fun main(args: Array<String>) {
    require(args.size == 4) { "Usage: phase-probe MODULES ENTRY INPUT NATIVE_EXPECTED" }
    val modules = args[0].split(',')
    val entry = args[1]
    val input = args[2].toLong()
    val expected = args[3].toLong()
    val phases = PhaseMeasurements()
    val launcher = Class.forName("thc.MainKt")
    val context = phases.measure("context") {
        val factory = launcher.methods.single { it.name == "executionContext" }
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
