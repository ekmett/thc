// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

@file:Suppress("UNCHECKED_CAST")
package thc.runtime

import com.oracle.truffle.api.RootCallTarget
import com.oracle.truffle.api.Truffle
import com.oracle.truffle.api.TruffleLanguage
import com.oracle.truffle.api.CompilerDirectives
import com.oracle.truffle.api.frame.FrameDescriptor
import com.oracle.truffle.api.frame.FrameSlotKind
import com.oracle.truffle.api.frame.VirtualFrame
import com.oracle.truffle.api.nodes.DirectCallNode
import com.oracle.truffle.api.nodes.NodeUtil
import com.oracle.truffle.api.nodes.Node.Child
import com.oracle.truffle.api.nodes.RootNode
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import thc.*
import java.io.File
import java.math.BigInteger
import java.security.MessageDigest
import java.util.IdentityHashMap
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class MutVarTest {
    private val root = File(System.getProperty("thc.projectRoot"))
    private val names = listOf("stRef", "lazyRef", "closureRef", "orderedRef", "unliftedRef", "stLoop")
    private val equalityNames = listOf("stRefEquality", "lazyRefEquality")
    private val lazyIONames = listOf("lazyIORef")
    private val swapNames = listOf("swapRef", "lazySwapRef")
    private val modifyNames = listOf("modifyRef", "lazyModifyRef", "lazyBottomModifierRef")
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
            "lazyIORef" -> x + BigInteger.valueOf(17)
            "closureRef" -> x * BigInteger.valueOf(4) + BigInteger.valueOf(11)
            "swapRef" -> x + (x + BigInteger.valueOf(17)) * BigInteger.valueOf(257) +
                (x * BigInteger.valueOf(3)) * BigInteger.valueOf(65537)
            "modifyRef" -> x + (x + BigInteger.valueOf(17)) * BigInteger.valueOf(257) +
                (x * BigInteger.valueOf(3)) * BigInteger.valueOf(65537) +
                (x + BigInteger.valueOf(17)) * BigInteger.valueOf(16777259)
            "lazyModifyRef", "lazyBottomModifierRef" -> x
            "lazySwapRef" -> x * BigInteger.valueOf(258) + BigInteger.valueOf(7)
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
    @Test fun publicLazyIORefWithInlining() = native(true, entryNames = lazyIONames)
    @Test fun publicLazyIORefAcrossResidualCalls() = native(false, entryNames = lazyIONames)
    @Test fun nativeAtomicSwapWithInlining() = native(true, entryNames = swapNames)
    @Test fun nativeAtomicSwapAcrossResidualCalls() = native(false, entryNames = swapNames)
    @Test fun nativeLazyAtomicModifyWithInlining() = native(true, entryNames = modifyNames)
    @Test fun nativeLazyAtomicModifyAcrossResidualCalls() = native(false, entryNames = modifyNames)

    @Test fun genuineLazyReturnedActionsKeepExactMutVarProofs() {
        fun nodes(value: Any?): List<List<Any?>> = when (value) {
            is Map<*, *> -> value.values.flatMap(::nodes)
            is List<*> -> listOf(value) + value.flatMap(::nodes)
            else -> emptyList()
        }
        for ((stage, paths) in manifest()["stages"] as Map<String, List<String>>) {
            val modules = paths.map { Json.parse(File(root, it).readText()) as Map<String, Any?> }
            val original = modules.single { it["module"] == "MutVarAudit" }
            assertTrue(Regex("\\blazy\\s+@").containsMatchIn(original["sourceCore"] as String),
                "$stage must retain the genuine typed lazy identity before export erasure")
            val helper = (original["bindings"] as List<Map<String, Any?>>).single { it["name"] == "freshActions" }
            val calls = nodes(helper["expr"]).filter { node ->
                node.firstOrNull() == "app" && (node[1] as? List<*>)?.let {
                    it.firstOrNull() == "prim" && MutVarOp.named(it.getOrNull(1) as? String ?: "") != null
                } == true
            }
            assertEquals(setOf("newMutVar#", "readMutVar#", "writeMutVar#"),
                calls.map { (it[1] as List<*>)[1] }.toSet())
            for (call in calls) {
                val name = (call[1] as List<*>)[1] as String
                MutVarOp.named(name)!!.validate(
                    (call[2] as List<List<Any?>>).map(CoreRepresentations::expression),
                    call[3] as List<*>, CoreRepresentations.expression(call))
            }
            val equalityCalls = nodes(original).filter { node ->
                node.firstOrNull() == "app" && (node[1] as? List<*>)?.take(2) ==
                    listOf("prim", "reallyUnsafePtrEquality#")
            }
            assertTrue(equalityCalls.isNotEmpty(), "$stage retains genuine STRef identity comparison")
            for (call in equalityCalls) {
                assertEquals(listOf(false, false), call[3])
                val arguments = call[2] as List<List<Any?>>
                assertEquals(2, arguments.size)
                for (argument in arguments) {
                    val rep = CoreRepresentations.expression(argument)
                    assertEquals(CoreKind.OBJECT, rep.kind)
                    assertEquals(listOf("BoxedRep (Just Unlifted)"), rep.primReps)
                }
                val result = CoreRepresentations.expression(call)
                assertEquals(CoreKind.LONG, result.kind)
                assertEquals(listOf("IntRep"), result.primReps)
            }
            val audit = Json.parse(File(root, "build/mutvar/$stage/lazyIORef.audit.json").readText()) as Map<String, Any?>
            assertEquals(true, audit["accepted"])
            assertEquals(emptyList<Any>(), audit["missingGlobals"])
            assertEquals(emptyList<Any>(), audit["issues"])
        }
    }

    private fun native(inlining: Boolean, recoverLoop: Boolean = false, entryNames: List<String> = names) {
        val manifest = manifest()
        for (kind in listOf("inputHashes", "artifactHashes")) for ((path, expected) in manifest[kind] as Map<String, String>) {
            val actual = MessageDigest.getInstance("SHA-256").digest(File(root, path).readBytes())
                .joinToString("") { "%02x".format(it.toInt() and 255) }
            assertEquals(expected, actual, "Stale MutVar fixture: $path; rerun thc-fixtures mutvar")
        }
        val rows = File(root, "build/mutvar/oracle.tsv").readLines().map { it.split('\t') }.groupBy { it[0] }
        assertEquals((names + equalityNames + lazyIONames + swapNames + modifyNames).toSet(), rows.keys)
        assertEquals((manifest["nativeRows"] as Number).toInt(), rows.values.sumOf { it.size })
        for ((stage, paths) in manifest["stages"] as Map<String, List<String>>) {
            val module = merged(paths)
            for (name in entryNames) {
                val audit = Json.parse(File(root, "build/mutvar/$stage/$name.audit.json").readText()) as Map<String, Any?>
                assertEquals(true, audit["accepted"], "$stage/$name strict Core audit")
                assertEquals(emptyList<Any>(), audit["issues"], "$stage/$name audit issues")
                assertEquals(emptyList<Any>(), audit["missingGlobals"], "$stage/$name missing globals")
                val primitives = (audit["primitives"] as List<Map<String, Any?>>).map { it["name"] }.toSet()
                val required = when (name) {
                    "swapRef", "lazySwapRef" -> setOf("newMutVar#", "readMutVar#", "atomicSwapMutVar#")
                    "modifyRef" -> setOf("newMutVar#", "readMutVar#", "atomicModifyMutVar2#")
                    "lazyModifyRef", "lazyBottomModifierRef" -> setOf("newMutVar#", "atomicModifyMutVar2#")
                    "lazyRefEquality" -> setOf("newMutVar#", "writeMutVar#", "reallyUnsafePtrEquality#")
                    "stRefEquality" -> setOf("newMutVar#", "readMutVar#", "writeMutVar#", "reallyUnsafePtrEquality#")
                    else -> setOf("newMutVar#", "readMutVar#", "writeMutVar#")
                }
                assertTrue(primitives.containsAll(required), "$stage/$name lost primitive evidence: $required")
                val reachable = (audit["reachableBindings"] as List<Map<String, Any?>>).map { it["id"] as String }
                if (name == "stRef") assertTrue(reachable.any { it.startsWith("main:MutVarAudit.bump") })
                if (name in equalityNames) for (helper in listOf("sameRef", "writeAndScore"))
                    assertTrue(reachable.any { it.startsWith("main:MutVarAudit.$helper") }, "$stage/$name/$helper")
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

    @Test fun atomicModifyReturnsOneSharedLazyApplicationAndSelector() {
        executionContext().use { context ->
            context.initialize("thc"); context.enter()
            try {
                val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                val layout = DataLayout(language, "main:ModifyPair", "ModifyPair", arrayOf("LiftedRep", "IntRep"))
                val calls = java.util.concurrent.atomic.AtomicInteger()
                val old = Any(); val replacement = Any()
                val modifier = object : GuestRoot(language, FrameDescriptor.newBuilder().build()) {
                    override fun bloom(frame: VirtualFrame): Long = frame.arguments[0] as Long
                    override fun execute(frame: VirtualFrame): Any? {
                        assertSame(old, frame.arguments[1]); calls.incrementAndGet()
                        return layout.create(arrayOf(replacement, 7L))
                    }
                }
                val metrics = Metrics(false)
                val force = object : RootNode(language, FrameDescriptor.newBuilder().build()) {
                    @Child private var evaluator = Force(metrics)
                    override fun execute(frame: VirtualFrame): Any? = evaluator.execute(frame, frame.arguments[0])
                }.callTarget
                val cell = ManagedMutVar(old)
                val function = Closure(null, arity = 1, target = modifier.callTarget)
                fun constant(value: Any?) = object : Expr() {
                    override fun execute(frame: VirtualFrame): Any? = value
                }
                val slots = FrameDescriptor.newBuilder().let { builder ->
                    intArrayOf(builder.addSlot(FrameSlotKind.Object, "old", null),
                        builder.addSlot(FrameSlotKind.Object, "result", null)) to builder.build()
                }
                val frame = Truffle.getRuntime().createVirtualFrame(emptyArray(), slots.second)
                val invalidState = mutVarExpression(MutVarOp.MODIFY2, CoreRepresentation.UNKNOWN,
                    arrayOf(constant(cell), constant(function), constant(1L)), language, metrics)
                assertThrows(RuntimeFault::class.java) { invalidState.executeTuple(frame, slots.first, 0) }
                assertSame(old, cell.value)
                assertEquals(0, calls.get())
                val site = MutVarModifySite(language, metrics, false)
                val modified = cell.modify(function, site)
                assertSame(old, modified.old)
                assertEquals(0, calls.get())
                val selected = cell.value
                assertTrue(selected is Thunk)
                val second = ManagedMutVar(old)
                val secondModification = second.modify(function, site)
                assertSame(modified.result.target, secondModification.result.target,
                    "atomic updates at one primitive site share the application call target")
                assertSame((selected as Thunk).target, (second.value as Thunk).target,
                    "atomic updates at one primitive site share the selector call target")
                assertSame(replacement, Calls.target(force, arrayOf(selected)))
                assertEquals(1, calls.get())
                assertSame(layout, (Calls.target(force, arrayOf(modified.result)) as DataValue).layout)
                assertEquals(1, calls.get(), "selector and returned record share the modifier application")
            } finally { context.leave() }
        }
    }

    @Test fun atomicModifySharesTheModifierFailureWithoutReplayingIt() {
        executionContext().use { context ->
            context.initialize("thc"); context.enter()
            try {
                val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                val calls = java.util.concurrent.atomic.AtomicInteger()
                val payload = Any(); val old = Any()
                val modifier = object : GuestRoot(language, FrameDescriptor.newBuilder().build()) {
                    override fun bloom(frame: VirtualFrame): Long = frame.arguments[0] as Long
                    override fun execute(frame: VirtualFrame): Nothing {
                        calls.incrementAndGet(); throw GuestException(payload, this)
                    }
                }
                val metrics = Metrics(false)
                val force = object : RootNode(language, FrameDescriptor.newBuilder().build()) {
                    @Child private var evaluator = Force(metrics)
                    override fun execute(frame: VirtualFrame): Any? = evaluator.execute(frame, frame.arguments[0])
                }.callTarget
                val cell = ManagedMutVar(old)
                val modified = cell.modify(Closure(null, arity = 1, target = modifier.callTarget),
                    MutVarModifySite(language, metrics, false))
                assertSame(old, modified.old)
                assertEquals(0, calls.get())
                for (value in listOf(modified.result, cell.value, modified.result, cell.value)) {
                    val failure = assertThrows(GuestException::class.java) { Calls.target(force, arrayOf(value)) }
                    assertSame(payload, failure.payload)
                }
                assertEquals(1, calls.get(), "a failed modifier is memoized by the shared application thunk")
            } finally { context.leave() }
        }
    }

    @Test fun atomicModifyDoesNotEnterABottomModifierBeforeReturningOld() {
        executionContext().use { context ->
            context.initialize("thc"); context.enter()
            try {
                val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                val calls = java.util.concurrent.atomic.AtomicInteger()
                val payload = Any(); val old = Any()
                val bottom = object : GuestRoot(language, FrameDescriptor.newBuilder().build()) {
                    override fun bloom(frame: VirtualFrame): Long = frame.arguments[0] as Long
                    override fun execute(frame: VirtualFrame): Nothing {
                        calls.incrementAndGet(); throw GuestException(payload, this)
                    }
                }
                val metrics = Metrics(false)
                val modifier = Thunk(bottom.callTarget, null)
                val cell = ManagedMutVar(old)
                val modified = cell.modify(modifier, MutVarModifySite(language, metrics, false))
                assertSame(old, modified.old)
                assertEquals(0, calls.get(), "atomicModifyMutVar2# must not force f before the swap")
                val force = object : RootNode(language, FrameDescriptor.newBuilder().build()) {
                    @Child private var evaluator = Force(metrics)
                    override fun execute(frame: VirtualFrame): Any? = evaluator.execute(frame, frame.arguments[0])
                }.callTarget
                for (value in listOf(modified.result, cell.value)) {
                    val failure = assertThrows(GuestException::class.java) { Calls.target(force, arrayOf(value)) }
                    assertSame(payload, failure.payload)
                }
                assertEquals(1, calls.get())
            } finally { context.leave() }
        }
    }

    @Test fun atomicModifyResumesModifierCallAndSelectedFieldWithoutRepeatingTheSwap() {
        executionContext().use { context ->
            context.initialize("thc"); context.enter()
            val cell: ManagedMutVar
            val selected: Thunk
            val modified: ModifiedMutVar
            val force: RootCallTarget
            val replacement = Any()
            val counts = Array(3) { java.util.concurrent.atomic.AtomicInteger() }
            try {
                val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                val metrics = Metrics(false)
                val old = Any()
                val layout = DataLayout(language, "main:PausedModifyPair", "PausedModifyPair",
                    arrayOf("LiftedRep", "IntRep"))
                fun leaf(value: Any?) = Thunk(object : GuestRoot(language, FrameDescriptor.newBuilder().build()) {
                    override fun bloom(frame: VirtualFrame): Long = frame.arguments[0] as Long
                    override fun execute(frame: VirtualFrame): Any? = value
                }.callTarget, null)
                fun pausing(child: Thunk, counter: java.util.concurrent.atomic.AtomicInteger,
                            expected: Any? = null) = object : GuestRoot(language, FrameDescriptor.newBuilder().build()) {
                    override fun bloom(frame: VirtualFrame): Long = frame.arguments[0] as Long
                    override fun execute(frame: VirtualFrame): Any? {
                        if (expected != null) assertSame(expected, frame.arguments[1])
                        counter.incrementAndGet()
                        CompilerDirectives.transferToInterpreter()
                        return AstCapture(ThunkSuspended(child), SynchronousMasking.current(this))
                            .append(object : AstResumeStep {
                                override fun resume(frame: VirtualFrame, input: Any?): Any? {
                                    val answer = input as? ChildResume ?: fault("Missing child resume")
                                    answer.failure?.let { throw it }
                                    return answer.value
                                }
                            }).freeze(this, frame.materialize())
                    }
                }
                val field = Thunk(pausing(leaf(replacement), counts[2]).callTarget, null)
                val record = layout.create(arrayOf(field, 7L))
                val body = pausing(leaf(record), counts[1], old)
                val closure = Closure(null, arity = 1, target = body.callTarget)
                val modifier = Thunk(pausing(leaf(closure), counts[0]).callTarget, null)
                cell = ManagedMutVar(old)
                modified = cell.modify(modifier, MutVarModifySite(language, metrics, true))
                selected = cell.value as Thunk
                assertSame(old, modified.old)
                assertEquals(listOf(0, 0, 0), counts.map { it.get() })
                force = object : RootNode(language, FrameDescriptor.newBuilder().build()) {
                    @Child private var evaluator = Force(metrics, true)
                    override fun execute(frame: VirtualFrame): Any? = evaluator.execute(frame, frame.arguments[0])
                }.callTarget
            } finally { context.leave() }

            // Each cut changes the Java carrier thread. The selector remains the
            // sole published MutVar value while all three continuations resume.
            fun step(): Any? {
                val answer = CompletableFuture<Any?>()
                val thread = Thread {
                    context.enter()
                    try { answer.complete(Calls.target(force, arrayOf(selected))) }
                    catch (suspension: ThunkSuspended) { answer.complete(suspension) }
                    catch (failure: Throwable) { answer.completeExceptionally(failure) }
                    finally { context.leave() }
                }
                thread.start()
                try { return answer.get(10, TimeUnit.SECONDS) }
                finally { thread.join(5000); assertFalse(thread.isAlive) }
            }
            for (round in 0..2) {
                val suspension = step() as? ThunkSuspended
                    ?: throw AssertionError("Expected owned selector cut $round")
                assertSame(selected, suspension.thunk)
                assertSame(selected, cell.value, "CAS must not replay while the selector is suspended")
                assertEquals((0..2).map { if (it <= round) 1 else 0 }, counts.map { it.get() })
            }
            assertSame(replacement, step())
            assertSame(selected, cell.value)
            assertEquals(listOf(1, 1, 1), counts.map { it.get() })
            context.enter()
            try {
                val record = Calls.target(force, arrayOf(modified.result)) as DataValue
                assertSame(replacement, Calls.target(force,
                    arrayOf(record.layout.readFirstLifted(record))))
            }
            finally { context.leave() }
        }
    }

    @Test fun concurrentAtomicModifyBuildsOneLazyUpdateChain() {
        executionContext().use { context ->
            context.initialize("thc"); context.enter()
            try {
                val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                val counter = DataLayout(language, "main:AtomicCounter", "AtomicCounter", arrayOf("IntRep"))
                val pair = DataLayout(language, "main:AtomicPair", "AtomicPair", arrayOf("LiftedRep", "IntRep"))
                val metrics = Metrics(false)
                val modifier = object : GuestRoot(language, FrameDescriptor.newBuilder().build()) {
                    @Child private var force = Force(metrics)
                    override fun bloom(frame: VirtualFrame): Long = frame.arguments[0] as Long
                    override fun execute(frame: VirtualFrame): DataValue {
                        val previous = force.execute(frame, frame.arguments[1]) as DataValue
                        val n = counter.readLong(previous, 0)
                        return pair.create(arrayOf(counter.createLong(n + 1), n))
                    }
                }
                val closure = Closure(null, arity = 1, target = modifier.callTarget)
                val cell = ManagedMutVar(counter.createLong(0))
                val site = MutVarModifySite(language, metrics, false)
                val start = CountDownLatch(1)
                val workers = Executors.newFixedThreadPool(4)
                try {
                    val futures = List(4) { workers.submit {
                        start.await()
                        repeat(8) { cell.modify(closure, site) }
                    } }
                    start.countDown()
                    futures.forEach { it.get(10, TimeUnit.SECONDS) }
                } finally {
                    workers.shutdownNow()
                    assertTrue(workers.awaitTermination(10, TimeUnit.SECONDS))
                }
                val force = object : RootNode(language, FrameDescriptor.newBuilder().build()) {
                    @Child private var evaluator = Force(metrics)
                    override fun execute(frame: VirtualFrame): Any? = evaluator.execute(frame, frame.arguments[0])
                }.callTarget
                val final = Calls.target(force, arrayOf(cell.value)) as DataValue
                assertEquals(32L, counter.readLong(final, 0), "each CAS publishes one selector")
            } finally { context.leave() }
        }
    }

    @Test fun exactContractsAcceptLiftedAndUnliftedBoxedPayloadCarriers() {
        val state = CoreRepresentation(CoreKind.VOID, present = true, primReps = emptyList())
        val reference = CoreRepresentation(CoreKind.OBJECT, present = true,
            primReps = listOf("BoxedRep (Just Unlifted)"))
        for (kind in listOf(CoreKind.DATA, CoreKind.CLOSURE, CoreKind.OBJECT))
            for (levity in listOf("Lifted", "Unlifted")) {
                val payload = CoreRepresentation(kind, present = true,
                    primReps = listOf("BoxedRep (Just $levity)"))
                for (operation in MutVarOp.entries.filterNot { it == MutVarOp.MODIFY2 }) {
                    val arguments = when (operation) {
                        MutVarOp.NEW -> listOf(payload, state)
                        MutVarOp.READ -> listOf(reference, state)
                        MutVarOp.SWAP, MutVarOp.WRITE -> listOf(reference, payload, state)
                        MutVarOp.MODIFY2 -> error("Separate lifted function contract")
                    }
                    val returned = if (operation == MutVarOp.NEW) reference else payload
                    val result = if (operation.tuple) CoreRepresentation(CoreKind.UNKNOWN, present = true,
                        primReps = returned.primReps, components = listOf(state, returned)) else state
                    val flags = arguments.map { it.primReps == listOf("BoxedRep (Just Lifted)") }
                    operation.validate(arguments, flags, result)
                    val scalar = CoreRepresentation(CoreKind.LONG, present = true, primReps = listOf("IntRep"))
                    if (operation == MutVarOp.READ) {
                        val wrongResult = result.copy(primReps = scalar.primReps, components = listOf(state, scalar))
                        assertThrows(RuntimeFault::class.java) { operation.validate(arguments, flags, wrongResult) }
                    } else {
                        val payloadIndex = if (operation == MutVarOp.NEW) 0 else 1
                        val wrongArguments = arguments.toMutableList().apply { this[payloadIndex] = scalar }
                        assertThrows(RuntimeFault::class.java) { operation.validate(wrongArguments, flags, result) }
                    }
                }
            }
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

    @Test fun atomicModifyRequiresLiftedFunctionAndTwoLiftedResults() {
        val state = CoreRepresentation(CoreKind.VOID, present = true, primReps = emptyList())
        val reference = CoreRepresentation(CoreKind.OBJECT, present = true,
            primReps = listOf("BoxedRep (Just Unlifted)"))
        val lifted = listOf("BoxedRep (Just Lifted)")
        val function = CoreRepresentation(CoreKind.CLOSURE, present = true, primReps = lifted)
        val old = CoreRepresentation(CoreKind.DATA, present = true, primReps = lifted)
        val record = CoreRepresentation(CoreKind.DATA, present = true, primReps = lifted)
        val tuple = CoreRepresentation(CoreKind.UNKNOWN, present = true,
            primReps = lifted + lifted, components = listOf(state, old, record))
        val args = listOf(reference, function, state)
        MutVarOp.MODIFY2.validate(args, listOf(false, true, false), tuple)
        assertThrows(RuntimeFault::class.java) {
            MutVarOp.MODIFY2.validate(args, listOf(false, false, false), tuple)
        }
        assertThrows(RuntimeFault::class.java) {
            MutVarOp.MODIFY2.validate(args, listOf(false, true, false), tuple.copy(components = listOf(state, old)))
        }
    }

    @Test fun concurrentAtomicExchangesNeitherLoseNorDuplicateReferences() {
        val initial = Any()
        val replacements = Array(8_000) { Any() }
        val reference = ManagedMutVar(initial)
        val displaced = ConcurrentLinkedQueue<Any>()
        val start = CountDownLatch(1)
        val workers = Executors.newFixedThreadPool(4)
        try {
            val tasks = (0..3).map { worker -> workers.submit {
                start.await()
                for (index in worker until replacements.size step 4)
                    displaced.add(reference.exchange(replacements[index])!!)
            } }
            start.countDown()
            tasks.forEach { it.get(10, TimeUnit.SECONDS) }
            assertEquals(replacements.size, displaced.size)
            val counts = IdentityHashMap<Any, Int>()
            for (value in displaced) counts[value] = (counts[value] ?: 0) + 1
            val last = reference.value!!
            counts[last] = (counts[last] ?: 0) + 1
            assertEquals(replacements.size + 1, counts.size)
            for (value in listOf(initial) + replacements.toList()) assertEquals(1, counts[value])
        } finally {
            workers.shutdownNow()
            assertTrue(workers.awaitTermination(10, TimeUnit.SECONDS), "MutVar exchange workers did not terminate")
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
        frame.setObject(slot, replacement)
        val failingSwap = mutVarExpression(MutVarOp.SWAP, CoreRepresentation.UNKNOWN, arrayOf(
            operand("cell") { cell }, operand("value") { replacement }, operand("state") { 1L }))
        assertThrows(RuntimeFault::class.java) { failingSwap.executeTuple(frame, intArrayOf(slot), 0) }
        assertSame(original, cell.value)
        assertSame(replacement, frame.getObject(slot))
        val swap = mutVarExpression(MutVarOp.SWAP, CoreRepresentation.UNKNOWN, arrayOf(
            operand("cell") { cell }, operand("value") { replacement }, operand("state") { Unit }))
        assertNull(swap.executeTuple(frame, intArrayOf(slot), 0))
        assertSame(original, frame.getObject(slot))
        assertSame(replacement, cell.value)
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
                    val module = CoreModules.reachable(merged(paths), when (operation) {
                        MutVarOp.SWAP -> "swapRef"
                        MutVarOp.MODIFY2 -> "modifyRef"
                        else -> "orderedRef"
                    })
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
                    val module = CoreModules.reachable(merged(paths), when (operation) {
                        MutVarOp.SWAP -> "swapRef"
                        MutVarOp.MODIFY2 -> "modifyRef"
                        else -> "orderedRef"
                    })
                    val app = applications(module).first { (it[1] as List<*>).take(2) == listOf("prim", operation.primitive) }
                    val primitive = (app[1] as List<*>).toList(); app.clear(); app.addAll(primitive)
                    assertThrows(UnsupportedCore::class.java) { program(language, module, backend) }
                }
            } finally { context.leave() }
        }
    }
}
