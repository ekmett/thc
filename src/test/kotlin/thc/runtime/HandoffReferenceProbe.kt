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

private fun referenceProbeCalls(target: RootCallTarget, packet: Array<Any?>, count: Int): Long {
    var sink = 0L
    repeat(count) { sink += Calls.target(target, packet) as Long }
    return sink
}

/** Bounded standalone diagnostic, not a timing assertion in the semantic suite. */
fun referenceProbeMain(arguments: Array<String>) {
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
            val dataRep = mapOf("kind" to "data", "primReps" to listOf("BoxedRep (Just Lifted)"), "evaluated" to false)
            fun box(value: List<Any?>): List<Any?> = listOf("app", listOf("con", "Box", 1), listOf(value), listOf(false), true, true)
            fun unbox(value: List<Any?>, id: String): List<Any?> = listOf("case", value, id,
                listOf(listOf("data", "Box", listOf(id + "Payload"), v(id + "Payload"), mapOf("binders" to listOf(
                    mapOf("id" to id + "Payload", "name" to id + "Payload", "lifted" to false, "coercion" to false, "rep" to longRep))))),
                mapOf("rep" to longRep, "binder" to mapOf("id" to id, "name" to id, "lifted" to true, "coercion" to false, "rep" to dataRep)))
            val names = listOf("a", "b", "c", "d")
            val sum = names.map { unbox(v(it), "read" + it) }.reduce { a, b -> prim("+#", a, b) }
            val shared = workload == "reference-shared"
            require(shared || workload == "reference-fresh")
            val workerBody = if (shared) listOf("case", prim("andI#", sum, n(1)), "parity", listOf(
                listOf("lit", listOf("int", "0"), emptyList<String>(), v("a")), listOf("default", null, emptyList<String>(), v("b"))))
                else box(sum)
            val workerBinding = mapOf("id" to "worker", "name" to "worker", "lifted" to true,
                "expr" to listOf("lam", names.map { mapOf("id" to it, "name" to it, "lifted" to true, "coercion" to false, "rep" to dataRep) },
                    workerBody, mapOf("rep" to closureRep, "resultRep" to dataRep, "entryStrict" to List(4) { false })))
            val application = call("worker", *names.indices.map { box(prim("+#", v("n"), n(it.toLong()))) }.toTypedArray())
            val program = Program(language, mapOf("instrument" to false, "constructors" to listOf(
                mapOf("id" to "Box", "name" to "Box", "arity" to 1, "kind" to "boxed", "fieldReps" to listOf(listOf("IntRep")), "strictFields" to listOf(false), "fieldLifted" to listOf(false))),
                "bindings" to listOf(workerBinding, binding("entry", listOf("n"), unbox(application, "answer")))))
            val entry = program.entryTarget("entry")
            val worker = program.entryTarget("worker")
            val packet = arrayOf<Any?>(0L, 3_000_000_017L) // Immutable across calls: no pooled/exposed argument alias mutation.
            val expected = if (shared) 3_000_000_017L else 12_000_000_074L
            fun invoke(): Long = Calls.target(entry, packet) as Long
            repeat(2_000) { check(invoke() == expected) }
            val targetClass = Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget")
            fun compile(target: RootCallTarget) {
                targetClass.getMethod("compile", Boolean::class.javaPrimitiveType).invoke(target, true)
                check(targetClass.getMethod("isValidLastTier").invoke(target) == true)
            }
            compile(worker); compile(entry)
            val gcBefore = ManagementFactory.getGarbageCollectorMXBeans().sumOf { maxOf(0, it.collectionCount) }
            System.gc() // Retained input carrier survives this full collection before long warmup.
            val agingCollections = ManagementFactory.getGarbageCollectorMXBeans().sumOf { maxOf(0, it.collectionCount) } - gcBefore
            val warmSeconds = System.getenv("THC_PROBE_WARM_SECONDS")?.toLong() ?: 45L
            val sampleMillis = System.getenv("THC_PROBE_SAMPLE_MILLIS")?.toLong() ?: 1000L
            val sampleCount = System.getenv("THC_PROBE_SAMPLES")?.toInt() ?: 5
            require(warmSeconds >= 1 && sampleMillis >= 100 && sampleCount >= 1)
            val compilation = ManagementFactory.getCompilationMXBean()
            var sink = 0L
            val warmStart = System.nanoTime()
            var warmCalls = 0L
            do {
                val batchSum = referenceProbeCalls(entry, packet, 4096)
                check(batchSum == expected * 4096L)
                sink += batchSum; warmCalls += 4096
            }
            while (System.nanoTime() - warmStart < warmSeconds * 1_000_000_000L)
            check(sink == expected * warmCalls)
            val allocation = ManagementFactory.getThreadMXBean() as ThreadMXBean
            check(allocation.isThreadAllocatedMemorySupported)
            allocation.isThreadAllocatedMemoryEnabled = true
            val thread = Thread.currentThread().threadId()
            val bytes = ArrayList<Double>(); val nanos = ArrayList<Double>()
            val samples = ArrayList<Map<String, Any>>()
            val measurementStartMillis = System.currentTimeMillis()
            repeat(sampleCount) {
                val compileBefore = compilation.totalCompilationTime
                val beforeBytes = allocation.getThreadAllocatedBytes(thread)
                val beforeTime = System.nanoTime()
                var count = 0L
                var sampleSum = 0L
                do {
                    val batchSum = referenceProbeCalls(entry, packet, 4096)
                    check(batchSum == expected * 4096L)
                    sampleSum += batchSum; count += 4096
                }
                while (System.nanoTime() - beforeTime < sampleMillis * 1_000_000L)
                val elapsed = System.nanoTime() - beforeTime
                val allocated = allocation.getThreadAllocatedBytes(thread) - beforeBytes
                bytes.add(allocated.toDouble() / count)
                nanos.add(elapsed.toDouble() / count)
                val entryValid = targetClass.getMethod("isValidLastTier").invoke(entry) == true
                val workerValid = targetClass.getMethod("isValidLastTier").invoke(worker) == true
                check(entryValid && workerValid)
                samples.add(mapOf("calls" to count, "elapsedNs" to elapsed,
                    "hostCompilationMillisDelta" to (compilation.totalCompilationTime - compileBefore),
                    "entryCompiled" to entryValid, "workerCompiled" to workerValid, "checksum" to sampleSum,
                    "expectedChecksum" to (expected * count), "checksumValid" to (sampleSum == expected * count)))
                check(sampleSum == expected * count)
            }
            val measurementEndMillis = System.currentTimeMillis()
            val state = language.handoffState.get()
            check(state.arguments.depth == 0 && state.pending == null)
            check(state.arguments.retainedReferences() == 0)
            check(invoke() == expected && sink == expected * warmCalls)
            println(Json.stringify(mapOf("handoff" to enabled, "inlining" to inlining, "workload" to workload,
                "warmSeconds" to warmSeconds, "warmCalls" to warmCalls, "sampleMillis" to sampleMillis,
                "measurementStartMillis" to measurementStartMillis, "measurementEndMillis" to measurementEndMillis,
                "samples" to samples, "bytesPerCall" to bytes, "nsPerCall" to nanos,
                "argumentCarriers" to state.arguments.allocations, "resultCarriers" to 0, "resultProtocol" to "natural-object", "agingCollections" to agingCollections,
                "entryCompiled" to targetClass.getMethod("isValidLastTier").invoke(entry),
                "workerCompiled" to targetClass.getMethod("isValidLastTier").invoke(worker),
                "expected" to expected)))
        } finally { context.leave() }
    }
}

fun main(arguments: Array<String>) = referenceProbeMain(arguments)
