// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc

import com.oracle.truffle.api.RootCallTarget
import com.oracle.truffle.api.nodes.DirectCallNode
import com.oracle.truffle.api.nodes.NodeUtil
import com.sun.management.ThreadMXBean
import java.io.File
import java.lang.management.ManagementFactory
import org.graalvm.polyglot.Context
import org.graalvm.polyglot.Value

/** Target-state inspection only; its checked calls and reflection are not a timing benchmark. */
fun main(args: Array<String>) {
    require(args.size == 3) { "Usage: retention-probe MODULES_MANIFEST ENTRY NATIVE_CYCLE_TSV" }
    val rows = File(args[2]).readLines().filter { it.isNotBlank() }.map { it.split('\t') }
    require(rows.size == 16 && rows.all { it.size == 3 && it[0] == args[1] })
    val inputs = rows.map { it[1].toLong() }
    val expected = rows.map { it[2].toLong() }
    require(inputs.distinct().size == 16)
    val instrument = java.lang.Boolean.getBoolean("thc.retentionProbe.instrument")
    val sampleTargets = java.lang.Boolean.getBoolean("thc.retentionProbe.sampleTargets")
    val allocations = ManagementFactory.getThreadMXBean() as? ThreadMXBean
    val collectors = ManagementFactory.getGarbageCollectorMXBeans()
    val launcher = Class.forName("thc.MainKt")
    val factory = launcher.methods.single { it.name == "executionContext" }
    val created = (if (factory.parameterCount == 0) factory.invoke(null)
        else factory.invoke(null, false)) as Context
    created.use { context ->
        val function = launcher.getMethod("loadEntry", Context::class.java, List::class.java,
            String::class.java, Boolean::class.javaPrimitiveType, String::class.java)
            .invoke(null, context, File(args[0]).readLines().filter { it.isNotBlank() },
                args[1], instrument, System.getProperty("thc.backend", "bytecode")) as Value
        // Diagnostic-only inspection of the pinned Polyglot wrapper. Production
        // code and the guest API do not expose or depend on these private fields.
        val receiver = Value::class.java.superclass.getDeclaredField("receiver")
            .apply { isAccessible = true }.get(function)
        val program = receiver.javaClass.getDeclaredField("program")
            .apply { isAccessible = true }.get(receiver)
        val host = receiver.javaClass.getDeclaredField("guestTarget")
            .apply { isAccessible = true }.get(receiver) as RootCallTarget
        val original = program.javaClass.getMethod("entryTarget", String::class.java)
            .invoke(program, args[1]) as RootCallTarget
        val targetType = Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget")
        fun targetState(target: RootCallTarget) = linkedMapOf<String, Any?>(
            "name" to target.toString(), "identity" to System.identityHashCode(target),
            "host" to (target === host), "original" to (target === original)).also { state ->
            for (method in listOf("isValidLastTier", "getCodeAddress", "getCallCount", "getCallAndLoopCount",
                "getSuccessfulCompilationCount")) state[method] = targetType.getMethod(method).invoke(target)
            state["invalidationReason"] = target.javaClass.getDeclaredMethod("getInvalidationReason")
                .apply { isAccessible = true }.invoke(target)
        }
        fun snapshot(phase: String, calls: Int) {
            val active = NodeUtil.findAllNodeInstances(host.rootNode, DirectCallNode::class.java)
                .filter { it.callTarget === original }.map { it.currentCallTarget as RootCallTarget }
            println(Json.stringify(mapOf("phase" to phase, "calls" to calls,
                "callingThreadAllocatedBytes" to allocations?.takeIf { it.isThreadAllocatedMemorySupported }
                    ?.getThreadAllocatedBytes(Thread.currentThread().threadId()),
                "gcCount" to collectors.sumOf { it.collectionCount },
                "gcTimeMs" to collectors.sumOf { it.collectionTime },
                "targets" to (listOf(host, original) + active).distinct().map(::targetState),
                "diagnostics" to Json.parse(function.getMember("diagnostics").asString()))))
        }
        fun checkedCall(index: Int): Long = function.execute(inputs[index and 15]).asLong().also {
            check(it == expected[index and 15]) { "Native mismatch on input ${inputs[index and 15]}: $it" }
        }
        repeat(200) { checkedCall(it) }
        check(function.invokeMember("compile").asBoolean())
        snapshot("installed", 0)
        var checksum = 0L
        repeat(12032) { index ->
            checksum += checkedCall(index)
            // HotSpot target inspection calls updateHotSpotNmethod. Keep it out
            // of the warm interval by default: observing validity can affect
            // cold-code reclamation. Sampled runs are separately labeled controls.
            if (index == 0 || (sampleTargets && (index + 1) % 256 == 0)) snapshot("checked", index + 1)
        }
        check(checksum == expected.sum() * (12032 / 16))
        snapshot("before-retention-check", 12032)
        try {
            // Preserve the existing harness's assertion; no retry or settling call.
            check(function.invokeMember("compile").asBoolean())
        } finally { snapshot("after-retention-check", 12032) }
    }
}
