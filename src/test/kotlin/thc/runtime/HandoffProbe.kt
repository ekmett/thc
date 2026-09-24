// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime

import com.oracle.truffle.api.RootCallTarget
import com.oracle.truffle.api.TruffleLanguage
import com.sun.management.ThreadMXBean
import org.graalvm.polyglot.Context
import thc.Json
import thc.Language
import java.lang.management.ManagementFactory

private fun probeCalls(target: RootCallTarget, packet: Array<Any?>, count: Int): Long {
    var sink = 0L
    repeat(count) { sink = sink xor (Calls.target(target, packet) as Long) }
    return sink
}

/** Bounded standalone diagnostic, not a timing assertion in the semantic suite. */
fun main(arguments: Array<String>) {
    val enabled = arguments[0].toBooleanStrict()
    val inlining = arguments[1].toBooleanStrict()
    val workload = arguments[2]
    System.setProperty(HANDOFF_PROPERTY, enabled.toString())
    val builder = Context.newBuilder("thc").allowExperimentalOptions(true)
        .option("compiler.Inlining", inlining.toString()).option("engine.BackgroundCompilation", "false")
        .option("engine.MultiTier", "false").option("engine.CompilationFailureAction", "Throw")
        .option("engine.SingleTierCompilationThreshold", "10000000")
        .option("compiler.CompilationTimeout", "30").option("compiler.MaximumGraalGraphSize", "200000")
    if (arguments.size > 3) builder.option("compiler.Dump", "Truffle:2").option("compiler.DumpPath", arguments[3])
    builder.build().use { context ->
        context.initialize("thc"); context.enter()
        try {
            val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
            val longRep = mapOf("kind" to "long", "primReps" to listOf("IntRep"), "evaluated" to true)
            val closureRep = mapOf("kind" to "closure", "primReps" to listOf("BoxedRep (Just Lifted)"), "evaluated" to true)
            fun v(id: String): List<Any?> = listOf("var", id)
            fun n(value: Long): List<Any?> = listOf("lit", "int", value.toString())
            fun app(fn: List<Any?>, vararg values: List<Any?>): List<Any?> = listOf("app", fn, values.toList(), List(values.size) { false })
            fun call(fn: String, vararg values: List<Any?>) = app(v(fn), *values)
            fun prim(fn: String, vararg values: List<Any?>) = app(listOf("prim", fn), *values)
            fun binding(id: String, args: List<String>, body: List<Any?>): Map<String, Any?> = mapOf("id" to id, "name" to id,
                "lifted" to true, "expr" to listOf("lam", args.map { mapOf("id" to it, "name" to it, "lifted" to false, "coercion" to false, "rep" to longRep) },
                    body, mapOf("rep" to closureRep, "resultRep" to longRep, "entryStrict" to List(args.size) { false })))
            val updatePayloads = workload.startsWith("update")
            val wideArity = if (workload.startsWith("wide") || updatePayloads) workload.dropWhile { !it.isDigit() }.toInt() else 0
            val payloadNames = (1 until wideArity).map { "x$it" }
            val workerBody = if (wideArity > 0) listOf("case", prim("<=#", v("d"), n(4_000_000_000L)), "test", listOf(
                listOf("lit", listOf("int", "1"), emptyList<String>(), payloadNames.map(::v).reduce { a, b -> prim("+#", a, b) }),
                listOf("default", null, emptyList<String>(), prim("+#", call("worker", *(listOf(prim("-#", v("d"), n(1))) +
                    payloadNames.map { if (updatePayloads) prim("+#", v(it), n(1)) else v(it) }).toTypedArray()), n(1)))))
            else if (workload == "recursive") listOf("case", prim("<=#", v("d"), n(0)), "test", listOf(
                listOf("lit", listOf("int", "1"), emptyList<String>(), v("n")),
                listOf("default", null, emptyList<String>(), prim("+#", call("worker", prim("-#", v("d"), n(1)), v("n")), v("d")))))
            else prim("+#", prim("*#", v("n"), n(3)), n(1))
            val application = if (wideArity > 0) call("worker", *(listOf(n(4_000_000_012L)) + payloadNames.indices.map { prim("+#", v("n"), n(it.toLong())) }).toTypedArray())
                else if (workload == "recursive") call("worker", n(12), v("n")) else call("worker", v("n"))
            val program = Program(language, mapOf("instrument" to false, "bindings" to listOf(
                binding("worker", if (wideArity > 0) listOf("d") + payloadNames else if (workload == "recursive") listOf("d", "n") else listOf("n"), workerBody),
                binding("entry", listOf("n"), prim("+#", application, n(7))))))
            val entry = program.entryTarget("entry")
            val worker = program.entryTarget("worker")
            val packet = arrayOf<Any?>(0L, 3_000_000_017L) // Immutable across calls: no pooled/exposed argument alias mutation.
            val expected = if (wideArity > 0) payloadNames.indices.sumOf { 3_000_000_017L + it + if (updatePayloads) 12L else 0L } + 19L
                else if (workload == "recursive") 3_000_000_102L else 9_000_000_059L
            fun invoke(): Long = Calls.target(entry, packet) as Long
            repeat(2_000) { check(invoke() == expected) }
            val targetClass = Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget")
            fun compile(target: RootCallTarget) {
                targetClass.getMethod("compile", Boolean::class.javaPrimitiveType).invoke(target, true)
                check(targetClass.getMethod("isValidLastTier").invoke(target) == true)
            }
            compile(worker); compile(entry)
            var sink = probeCalls(entry, packet, 1_000_000)
            val allocation = ManagementFactory.getThreadMXBean() as ThreadMXBean
            check(allocation.isThreadAllocatedMemorySupported)
            allocation.isThreadAllocatedMemoryEnabled = true
            val thread = Thread.currentThread().threadId()
            val bytes = ArrayList<Double>(); val nanos = ArrayList<Double>()
            val count = 50_000
            repeat(9) {
                val beforeBytes = allocation.getThreadAllocatedBytes(thread)
                val beforeTime = System.nanoTime()
                sink = sink xor probeCalls(entry, packet, count)
                val elapsed = System.nanoTime() - beforeTime
                val allocated = allocation.getThreadAllocatedBytes(thread) - beforeBytes
                bytes.add(allocated.toDouble() / count)
                nanos.add(elapsed.toDouble() / count)
            }
            val state = language.handoffState.get()
            check(state.arguments.depth == 0 && state.pending == null)
            check(state.arguments.retainedReferences() == 0)
            check(invoke() == expected && sink == 0L)
            println(Json.stringify(mapOf("handoff" to enabled, "inlining" to inlining, "workload" to workload,
                "callsPerSample" to count, "bytesPerCall" to bytes, "nsPerCall" to nanos,
                "argumentCarriers" to state.arguments.allocations, "resultCarriers" to 0, "resultProtocol" to "scalar-register",
                "entryCompiled" to targetClass.getMethod("isValidLastTier").invoke(entry),
                "workerCompiled" to targetClass.getMethod("isValidLastTier").invoke(worker),
                "expected" to expected)))
        } finally { context.leave() }
    }
}
