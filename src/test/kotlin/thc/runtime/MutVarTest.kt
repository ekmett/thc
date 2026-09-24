// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

@file:Suppress("UNCHECKED_CAST")
package thc.runtime

import com.oracle.truffle.api.RootCallTarget
import com.oracle.truffle.api.Truffle
import com.oracle.truffle.api.TruffleLanguage
import com.oracle.truffle.api.frame.FrameDescriptor
import com.oracle.truffle.api.frame.FrameSlotKind
import com.oracle.truffle.api.frame.VirtualFrame
import com.oracle.truffle.api.nodes.DirectCallNode
import com.oracle.truffle.api.nodes.NodeUtil
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import thc.*
import java.io.File
import java.math.BigInteger
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class MutVarTest {
    private val root = File(System.getProperty("thc.projectRoot"))
    private val names = listOf("stRef", "lazyRef", "closureRef", "orderedRef", "unliftedRef", "stLoop")
    private val equalityNames = listOf("stRefEquality", "lazyRefEquality")
    private fun manifest() = Json.parse(File(root, "build/mutvar/manifest.json").readText()) as Map<String, Any?>
    private fun merged(paths: List<String>) = CoreModules.merge(paths.map { Json.parse(File(root, it).readText()) as Map<String, Any?> })
    private fun program(language: Language, module: Map<String, Any?>, backend: String): ExecutableProgram =
        if (backend == "ast") Program(language, module) else BytecodeProgram(language, module)
    private fun mathematical(name: String, seed: Long): Long {
        val x = BigInteger.valueOf(seed)
        return when (name) {
            "stRefEquality", "lazyRefEquality" -> {
                val score = BigInteger.valueOf(if (seed < 0) 5 else 1)
                if (name == "lazyRefEquality") x + score * BigInteger.valueOf(17)
                else {
                    val left = if (seed < 0) x + BigInteger.valueOf(17) else x
                    val right = if (seed < 0) x else x + BigInteger.valueOf(17)
                    left * BigInteger.valueOf(257) + right * BigInteger.valueOf(65537) + score * BigInteger.valueOf(17)
                }
            }
            "lazyRef" -> x + BigInteger.valueOf(5)
            "closureRef" -> x * BigInteger.valueOf(4) + BigInteger.valueOf(11)
            "unliftedRef" -> x * BigInteger.valueOf(258) + BigInteger.ONE
            "stLoop" -> {
                var value = x
                for (n in x.abs().mod(BigInteger.valueOf(33)).toInt() downTo 1)
                    value = value * BigInteger.valueOf(3) + BigInteger.valueOf(n.toLong())
                value
            }
            else -> {
                val last = (if (name == "stRef") x + BigInteger.valueOf(17) else x) * BigInteger.valueOf(3)
                x + (x + BigInteger.valueOf(17)) * BigInteger.valueOf(257) +
                    last * BigInteger.valueOf(65537) + (x + BigInteger.valueOf(71)) * BigInteger.valueOf(16777259)
            }
        }.toLong()
    }
    private fun context(inlining: Boolean) = org.graalvm.polyglot.Context.newBuilder("thc")
        .allowExperimentalOptions(true).option("engine.BackgroundCompilation", "false")
        .option("engine.MultiTier", "false").option("engine.SingleTierCompilationThreshold", "10000")
        .option("engine.CompilationFailureAction", "Throw").option("compiler.CompilationTimeout", "30")
        .option("compiler.MaximumGraalGraphSize", "100000").option("compiler.Inlining", inlining.toString()).build()
    private fun valid(target: RootCallTarget, label: String) = assertEquals(true,
        Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget").getMethod("isValidLastTier").invoke(target), label)

    @Test fun nativeSTRefWithInliningAndBoundedGraalSpeculationWarmup() = native(true,
        recoverLoop = System.getenv("THC_MUTVAR_REQUIRE_INITIAL_STABILITY") != "true")
    @Test fun nativeSTRefAcrossResidualCalls() = native(false)
    @Test fun publicSTRefEqualityWithInlining() = native(true, entryNames = equalityNames)
    @Test fun publicSTRefEqualityAcrossResidualCalls() = native(false, entryNames = equalityNames)
    private fun native(inlining: Boolean, recoverLoop: Boolean = false, entryNames: List<String> = names) {
        val manifest = manifest()
        for (kind in listOf("inputHashes", "artifactHashes")) for ((path, expected) in manifest[kind] as Map<String, String>) {
            val actual = MessageDigest.getInstance("SHA-256").digest(File(root, path).readBytes())
                .joinToString("") { "%02x".format(it.toInt() and 255) }
            assertEquals(expected, actual, "Stale MutVar fixture: $path; rerun prepare-mutvar.py")
        }
        val rows = File(root, "build/mutvar/oracle.tsv").readLines().map { it.split('\t') }.groupBy { it[0] }
        assertEquals((names + equalityNames).toSet(), rows.keys)
        assertEquals((manifest["nativeRows"] as Number).toInt(), rows.values.sumOf { it.size })
        for ((stage, paths) in manifest["stages"] as Map<String, List<String>>) {
            val module = merged(paths)
            for (name in entryNames) {
                val cases = rows.getValue(name).map { it[1].toLong() to it[2].toLong() }
                cases.forEach { (input, native) -> assertEquals(mathematical(name, input), native, "Native $name($input)") }
                for (backend in listOf("ast", "bytecode")) context(inlining).use { context ->
                    context.initialize("thc"); context.enter()
                    try {
                        val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                        val label = "$stage/$backend/$name/inlining=$inlining"
                        val program = program(language, CoreModules.reachable(module, name) + ("instrument" to true), backend)
                        val host = program.hostEntryTarget(1)
                        val function = context.asValue(EntryValue(program, name, 1))
                        fun check(row: Pair<Long, Long>) = assertEquals(row.second, function.execute(row.first).asLong(), "$label(${row.first})")
                        fun compiled() = (program.diagnostics().getValue("compiledEntries") as Number).toLong()
                        cases.forEach(::check)
                        assertTrue(function.invokeMember("compile").asBoolean(), "$label installation")
                        val original = program.entryTarget(name)
                        fun activeTargets() = NodeUtil.findAllNodeInstances(host.rootNode, DirectCallNode::class.java)
                            .filter { it.callTarget === original }.map { it.currentCallTarget as RootCallTarget }
                            .ifEmpty { listOf(original) }.toSet()
                        val active = activeTargets()
                        fun activeUnchanged() = assertEquals(active, activeTargets(), "$label active guest identities remain unchanged")
                        if (recoverLoop && name == "stLoop") {
                            // Graal speculates an initial countdown > 0 (AST),
                            // or > 1 after peeling (bytecode). Interpreter warmup
                            // cannot train a compiler speculation. Probe these
                            // two known boundaries, then recompile at most once.
                            // The environment switch preserves the original raw
                            // first-install stability diagnostic without probes.
                            for (input in listOf(99L, 1_000_000_000_000L)) {
                                val probe = cases.single { it.first == input }
                                val before = compiled()
                                val hostWasValid = host.javaClass.getMethod("isValidLastTier").invoke(host)
                                check(probe)
                                assertTrue(compiled() > before, "$label initial loop probe $input entered compiled guest code")
                                activeUnchanged()
                                valid(original, "$label original guest survives initial loop probe $input")
                                active.forEach { valid(it, "$label active guest survives initial loop probe $input") }
                                System.err.println("MUTVAR_SPECULATION_WARMUP $label input=$input hostWasValid=$hostWasValid " +
                                    "hostIsValid=" + host.javaClass.getMethod("isValidLastTier").invoke(host) +
                                    " compiledGuestEntries=" + (compiled() - before))
                            }
                            if (host.javaClass.getMethod("isValidLastTier").invoke(host) != true) {
                                System.err.println("MUTVAR_RECOVERY $label observed host loop speculation deopt; one explicit recompile")
                                assertTrue(function.invokeMember("compile").asBoolean(), "$label recovery installation")
                            }
                            activeUnchanged()
                            valid(host, "$label host installed after bounded initial loop probes")
                        }
                        for (row in cases.asReversed()) {
                            val before = compiled()
                            check(row)
                            assertTrue(compiled() > before, "$label(${row.first}) must enter installed guest code")
                            activeUnchanged()
                            valid(host, "$label/${row.first} host remains installed")
                            active.forEach { valid(it, "$label/${row.first} active target remains installed") }
                        }
                        for (counter in listOf("unsupportedTraps", "blackholes"))
                            assertEquals(0L, (program.diagnostics().getValue(counter) as Number).toLong(), "$label/$counter")
                        assertEquals(0, language.handoffState.get().results.depth, "$label releases tuple results")
                        if (name == "orderedRef") assertEquals(0L, language.handoffState.get().results.allocations,
                            "$label saturated primitives write directly into locals")
                    } finally { context.leave() }
                }
            }
        }
    }

    @Test fun managedReferencePreservesAliasesAndDoesNotEvaluateOrCacheContents() {
        val first = Any(); val second = Any()
        val reference = ManagedMutVar(first)
        assertSame(first, reference.value)
        val alias = ManagedMutVar.require(reference)
        alias.value = second
        assertSame(second, reference.value)
        assertNotSame(reference, ManagedMutVar(second))
        assertThrows(RuntimeFault::class.java) { ManagedMutVar.require(arrayOf(first)) }
        val field = ManagedMutVar::class.java.getDeclaredField("value")
        assertFalse(java.lang.reflect.Modifier.isFinal(field.modifiers))
        assertTrue(java.lang.reflect.Modifier.isVolatile(field.modifiers), "MutVar writes must publish to other threads")
        assertNull(field.getAnnotation(com.oracle.truffle.api.CompilerDirectives.CompilationFinal::class.java))
    }

    private class PublishedValue {
        var sequence = 0
        var complement = 0
    }

    @Test fun concurrentReadersSeeCompletePublishedValuesInWriteOrder() {
        fun value(sequence: Int) = PublishedValue().apply {
            this.sequence = sequence
            complement = sequence.inv()
        }
        val reference = ManagedMutVar(value(0))
        val done = Any()
        val start = CountDownLatch(1)
        val firstRead = CountDownLatch(2)
        val workers = Executors.newFixedThreadPool(3)
        try {
            val readers = List(2) {
                workers.submit<Int> {
                    start.await()
                    var previous = 0
                    var sawWrite = false
                    while (true) {
                        if (Thread.currentThread().isInterrupted) throw InterruptedException("Reader cancelled")
                        val current = reference.value
                        if (current === done) break
                        val published = current as PublishedValue
                        assertEquals(published.sequence.inv(), published.complement,
                            "Reader observed a partially published value")
                        assertTrue(published.sequence >= previous, "A reader went backward in the write order")
                        previous = published.sequence
                        if (previous > 0 && !sawWrite) {
                            sawWrite = true
                            firstRead.countDown()
                        }
                        Thread.onSpinWait()
                    }
                    assertTrue(sawWrite, "Reader missed every published write")
                    previous
                }
            }
            val writer = workers.submit {
                start.await()
                reference.value = value(1)
                assertTrue(firstRead.await(10, TimeUnit.SECONDS), "Readers did not observe the first write")
                for (sequence in 2..25_000) reference.value = value(sequence)
                reference.value = done
            }
            start.countDown()
            writer.get(10, TimeUnit.SECONDS)
            readers.forEach { it.get(10, TimeUnit.SECONDS) }
        } finally {
            workers.shutdownNow()
            assertTrue(workers.awaitTermination(10, TimeUnit.SECONDS), "MutVar workers did not terminate")
        }
    }

    @Test fun stateFailurePrecedesMutationAndTuplePublication() {
        val builder = FrameDescriptor.newBuilder()
        val slot = builder.addSlot(FrameSlotKind.Object, "destination", null)
        val frame = Truffle.getRuntime().createVirtualFrame(emptyArray(), builder.build())
        val events = mutableListOf<String>()
        fun operand(name: String, action: () -> Any?) = object : Expr() {
            override fun execute(frame: VirtualFrame): Any? { events.add(name); return action() }
        }
        val original = Any(); val replacement = Any(); val cell = ManagedMutVar(original)
        val write = mutVarExpression(MutVarOp.WRITE, CoreRepresentation.UNKNOWN, arrayOf(
            operand("cell") { cell }, operand("value") { replacement },
            operand("state") { assertSame(original, cell.value); Unit }))
        assertSame(Unit, write.execute(frame)); assertSame(replacement, cell.value)
        assertEquals(listOf("cell", "value", "state"), events)
        cell.value = original
        val failingWrite = mutVarExpression(MutVarOp.WRITE, CoreRepresentation.UNKNOWN, arrayOf(
            operand("cell") { cell }, operand("value") { replacement }, operand("state") { 1L }))
        assertThrows(RuntimeFault::class.java) { failingWrite.execute(frame) }
        assertSame(original, cell.value)
        for (operation in listOf(MutVarOp.NEW, MutVarOp.READ)) {
            frame.setObject(slot, replacement)
            val failing = mutVarExpression(operation, CoreRepresentation.UNKNOWN, arrayOf(
                operand("value") { if (operation == MutVarOp.READ) cell else original },
                operand("state") { throw RuntimeFault("state failed") }))
            assertThrows(RuntimeFault::class.java) { failing.executeTuple(frame, intArrayOf(slot), 0) }
            assertSame(replacement, frame.getObject(slot))
        }
    }

    @Test fun bothBackendsRejectBadStateBeforeWritingAndKeepLiftedContentsLazy() {
        val state = mapOf("kind" to "void", "primReps" to emptyList<String>(), "evaluated" to true)
        val reference = mapOf("kind" to "object", "primReps" to listOf("BoxedRep (Just Unlifted)"), "evaluated" to true)
        val lifted = mapOf("kind" to "object", "primReps" to listOf("BoxedRep (Just Lifted)"), "evaluated" to false)
        val closure = mapOf("kind" to "closure", "primReps" to listOf("BoxedRep (Just Lifted)"), "evaluated" to true)
        val parameters = listOf("cell" to reference, "value" to lifted, "state" to state).map { (id, rep) ->
            mapOf("id" to id, "lifted" to (id == "value"), "rep" to rep)
        }
        val body = listOf("app", listOf("prim", "writeMutVar#"), parameters.map {
            listOf("var", it["id"], mapOf("rep" to it["rep"]))
        }, listOf(false, true, false), false, false, mapOf("rep" to state))
        val module = mapOf("bindings" to listOf(mapOf("id" to "write", "name" to "write", "lifted" to true,
            "rep" to closure, "arity" to 3, "expr" to listOf("lam", parameters, body,
                mapOf("rep" to closure, "resultRep" to state)))))
        for (backend in listOf("ast", "bytecode")) executionContext().use { context ->
            context.initialize("thc"); context.enter()
            try {
                val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                val program = program(language, module, backend)
                val original = Any()
                val cell = ManagedMutVar(original)
                // A lifted value may be an unevaluated thunk. A raw sentinel is
                // sufficient here: forcing it as a guest value would fail.
                val replacement = Any()
                fun call(stateValue: Any?) = Calls.target(program.hostEntryTarget(3),
                    arrayOf(program.entryValue("write"), arrayOf(cell, replacement, stateValue)))
                assertThrows(RuntimeFault::class.java, { call(1L) }, backend)
                assertSame(original, cell.value, "$backend must check State before mutation")
                assertSame(Unit, call(Unit), backend)
                assertSame(replacement, cell.value, "$backend must store the lifted operand unchanged")
                assertEquals(0, language.handoffState.get().results.depth)
            } finally { context.leave() }
        }
    }

    private fun applications(value: Any?): List<MutableList<Any?>> = when (value) {
        is List<*> -> (if (value.firstOrNull() == "app") listOf(value as MutableList<Any?>) else emptyList()) + value.flatMap(::applications)
        is Map<*, *> -> value.values.flatMap(::applications)
        else -> emptyList()
    }

    @Test fun exactShapesAritySaturationAndLevityAreRequiredInBothLoadModes() {
        val paths = (manifest()["stages"] as Map<String, List<String>>).getValue("pre")
        for (backend in listOf("ast", "bytecode")) executionContext().use { context ->
            context.initialize("thc"); context.enter()
            try {
                val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                for (operation in MutVarOp.entries) for (mutation in 0..6) for (diagnostic in listOf(false, true)) {
                    val module = CoreModules.reachable(merged(paths), "orderedRef")
                    val app = applications(module).first { (it[1] as List<*>).take(2) == listOf("prim", operation.primitive) }
                    val args = app[2] as MutableList<Any?>
                    val flags = app[3] as MutableList<Any?>
                    val metadata = CoreRepresentations.metadata(app) as MutableMap<String, Any?>
                    when (mutation) {
                        0 -> { args.removeAt(args.lastIndex); flags.removeAt(flags.lastIndex); metadata.remove("callDemand") }
                        1 -> { args.add(args[0]); flags.add(false); metadata.remove("callDemand") }
                        2 -> metadata.remove("rep")
                        3 -> { val proof = metadata["rep"] as MutableMap<String, Any?>
                            proof["primReps"] = listOf("IntRep"); proof["kind"] = "long"
                            proof.remove("aggregate"); proof.remove("components") }
                        4 -> flags[0] = flags[0] != true
                        5 -> { val proof = CoreRepresentations.metadata(args[0] as List<Any?>)!!["rep"] as MutableMap<String, Any?>
                            proof["kind"] = "unknown" }
                        6 -> if (operation.tuple) {
                            val proof = metadata["rep"] as MutableMap<String, Any?>
                            val children = proof["components"] as MutableList<Any?>
                            children[0] = mapOf("kind" to "unknown", "aggregate" to "unboxed-tuple",
                                "components" to emptyList<Any?>(), "primReps" to emptyList<String>(), "evaluated" to true)
                        } else {
                            val proof = CoreRepresentations.metadata(args[0] as List<Any?>)!!["rep"] as MutableMap<String, Any?>
                            proof["primReps"] = listOf("BoxedRep (Just Lifted)")
                        }
                    }
                    assertThrows(RuntimeFault::class.java, {
                        program(language, module + ("diagnosticUnsupported" to diagnostic), backend)
                    }, "$backend/${operation.primitive}/mutation$mutation/$diagnostic")
                }
                for (operation in MutVarOp.entries) {
                    val module = CoreModules.reachable(merged(paths), "orderedRef")
                    val app = applications(module).first { (it[1] as List<*>).take(2) == listOf("prim", operation.primitive) }
                    val primitive = (app[1] as List<*>).toList(); app.clear(); app.addAll(primitive)
                    assertThrows(UnsupportedCore::class.java) { program(language, module, backend) }
                }
            } finally { context.leave() }
        }
    }
}
