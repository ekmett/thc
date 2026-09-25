// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

@file:Suppress("UNCHECKED_CAST")
package thc.runtime

import com.oracle.truffle.api.TruffleLanguage
import com.oracle.truffle.api.RootCallTarget
import com.oracle.truffle.api.bytecode.Instruction
import com.oracle.truffle.api.nodes.DirectCallNode
import com.oracle.truffle.api.nodes.NodeUtil
import org.graalvm.polyglot.Context
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import thc.CoreModules
import thc.EntryValue
import thc.Json
import thc.Language
import java.io.File
import java.security.MessageDigest
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class ManagedWeakTest {
    private fun context() = Context.newBuilder("thc").allowExperimentalOptions(true)
        .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false")
        .option("compiler.Inlining", "false")
        .option("engine.TraceCompilation", System.getProperty("thc.weakTrace", "false"))
        .option("engine.TraceTransferToInterpreter", System.getProperty("thc.weakTrace", "false"))
        .option("engine.TraceAssumptions", System.getProperty("thc.weakTrace", "false"))
        .option("engine.SingleTierCompilationThreshold", "10000")
        .option("engine.CompilationFailureAction", "Throw").option("compiler.CompilationTimeout", "30").build()

    @Test fun registrationsRetainLazyIdentityAndReturnTheRealActionWithoutCallingIt() {
        val registry = ManagedWeaks()
        val key = Any()
        val value = Any()
        var calls = 0
        lateinit var weak: Any
        val action = {
            ++calls
            assertEquals(0L, registry.dereference(weak).flag)
            assertEquals(0L, registry.finalize(weak).flag)
            key // The finalizer captures its key, like stdout's finalizer.
        }
        weak = registry.make(key, value, action)
        val independent = registry.make(key, key, null)
        registry.make(key, value, action) // Dropping a handle does not erase registration.
        assertEquals(3, registry.retainedCount())
        assertSame(value, registry.dereference(weak).value)
        val claimed = registry.finalize(weak)
        assertEquals(1L, claimed.flag)
        assertSame(action, claimed.value)
        assertEquals(0, calls)
        assertEquals(2, registry.retainedCount())
        action()
        assertEquals(1, calls)
        assertEquals(0L, registry.finalize(weak).flag)
        assertNull(registry.finalize(weak).value)
        assertSame(key, registry.dereference(independent).value)
        assertEquals(0L, registry.finalize(independent).flag)
        assertEquals(0L, registry.dereference(independent).flag)
        assertNull(registry.dereference(independent).value)
        registry.close()
        assertEquals(0, registry.retainedCount())
        assertEquals(1, calls, "close must not run the discarded handle's action")
        assertThrows(RuntimeFault::class.java) { registry.dereference(weak) }
    }

    @Test fun finalizationIsLinearizableAndFailureDoesNotReviveRegistration() {
        val registry = ManagedWeaks()
        val failure = IllegalStateException("explicit action failure")
        val action: () -> Unit = { throw failure }
        val weak = registry.make(Any(), Any(), action)
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        val workers = Executors.newFixedThreadPool(2)
        try {
            val futures = (1..2).map { workers.submit<WeakResult> {
                ready.countDown()
                check(start.await(3, TimeUnit.SECONDS))
                registry.finalize(weak)
            } }
            assertTrue(ready.await(3, TimeUnit.SECONDS))
            start.countDown()
            val results = futures.map { it.get(3, TimeUnit.SECONDS) }
            assertEquals(listOf(0L, 1L), results.map { it.flag }.sorted())
            assertSame(action, results.single { it.flag == 1L }.value)
            assertSame(failure, assertThrows(IllegalStateException::class.java) { action() })
            assertEquals(0L, registry.finalize(weak).flag)
            assertEquals(0, registry.retainedCount())
        } finally { start.countDown(); workers.shutdownNow(); registry.close() }
    }

    @Test fun actualContextCloseInvalidatesHandlesWithoutRunningHaskellActions() {
        val first = context()
        first.initialize("thc"); first.enter()
        val owner = Language.currentState().weaks
        var calls = 0
        val handle = owner.make(Any(), Any(), { ++calls })
        first.leave()
        context().use { second ->
            second.initialize("thc"); second.enter()
            try {
                assertThrows(RuntimeFault::class.java) { Language.currentState().weaks.dereference(handle) }
                assertThrows(RuntimeFault::class.java) { Language.currentState().weaks.finalize(handle) }
                assertThrows(RuntimeFault::class.java) { Language.currentState().weaks.dereference(Any()) }
            } finally { second.leave() }
        }
        first.close()
        assertEquals(0, calls)
        assertEquals(0, owner.retainedCount())
        assertThrows(RuntimeFault::class.java) { owner.finalize(handle) }
        assertThrows(RuntimeFault::class.java) { owner.make(Any(), Any(), null) }
    }

    @Test fun mainThreadCapabilityProjectsOnlyLiveContextOwnedThreadKeys() {
        val first = context()
        first.initialize("thc"); first.enter()
        lateinit var capability: MainThreadWeakKey
        lateinit var closing: MainThreadWeakKey
        try {
            val state = Language.currentState()
            val owner = state.weaks
            val threads = state.threads
            threads.enterCurrent()
            val key = threads.currentIdentity()
            val weak = owner.make(key, Any(), null)
            try {
                capability = owner.mainThreadKey(weak, threads)
                assertEquals(Thread.currentThread().threadId(), capability.liveJavaId(), "Native main-thread projection reads KEY, not value")
                val impostor = GuestThreadId(key.javaId, threads, key.capability, Thread.currentThread(), false)
                assertEquals(key, impostor, "Numeric equality alone must not establish canonical identity")
                assertNull(threads.liveJavaId(impostor))
                assertNull(owner.mainThreadKey(owner.make(impostor, Any(), null), threads).liveJavaId())
                closing = owner.mainThreadKey(owner.make(key, Any(), null), threads)
                val wrongKey = owner.make(Any(), key, null)
                assertThrows(RuntimeFault::class.java) { owner.mainThreadKey(wrongKey, threads) }
            } finally { threads.leaveCurrent() }
            assertEquals(key.javaId, capability.liveJavaId(), "A live FOREIGN host carrier is not terminated")
            context().use { second ->
                second.initialize("thc"); second.enter()
                try {
                    val foreign = Language.currentState()
                    foreign.threads.enterCurrent()
                    try {
                        assertThrows(RuntimeFault::class.java) { foreign.weaks.mainThreadKey(weak, foreign.threads) }
                        assertThrows(RuntimeFault::class.java) { owner.mainThreadKey(weak, foreign.threads) }
                        assertThrows(RuntimeFault::class.java) { foreign.threads.liveJavaId(key) }
                        val foreignKey = owner.make(foreign.threads.currentIdentity(), Any(), null)
                        assertThrows(RuntimeFault::class.java) { owner.mainThreadKey(foreignKey, threads) }
                    } finally { foreign.threads.leaveCurrent() }
                } finally { second.leave() }
            }
            assertEquals(0L, owner.finalize(weak).flag)
            assertNull(capability.liveJavaId())
            assertThrows(RuntimeFault::class.java) { owner.mainThreadKey(weak, threads) }
            assertEquals(key.javaId, closing.liveJavaId())
            threads.close()
            assertNull(closing.liveJavaId(), "Thread service close invalidates even a still-live weak registration")
        } finally { first.leave(); first.close() }
        assertNull(closing.liveJavaId())
        assertNull(capability.liveJavaId())
    }

    @Test fun mainThreadCapabilityDoesNotResurrectTerminalIdentitiesOrDeadOriginalCarriers() {
        for ((forked, outcome) in listOf(true to GuestThreadStatus.FINISHED, true to GuestThreadStatus.DIED,
                true to GuestThreadStatus.RUNTIME_FAILURE, false to GuestThreadStatus.FINISHED)) {
            val threads = GuestThreads(ThreadLocal.withInitial { MaskingState.UNMASKED }) { }
            val owner = ManagedWeaks()
            val capability = AtomicReference<MainThreadWeakKey>()
            val weak = AtomicReference<Any>()
            val failure = AtomicReference<Throwable>()
            val ready = CountDownLatch(1); val leave = CountDownLatch(1)
            val left = CountDownLatch(1); val stop = CountDownLatch(1)
            val worker = Thread {
                var entered = false
                try {
                    threads.enterCurrent(forked = forked); entered = true
                    weak.set(owner.make(threads.currentIdentity(), Any(), null))
                    capability.set(owner.mainThreadKey(weak.get(), threads))
                    ready.countDown()
                    check(leave.await(3, TimeUnit.SECONDS))
                    threads.leaveCurrent(outcome); entered = false
                    left.countDown()
                    check(stop.await(3, TimeUnit.SECONDS))
                } catch (error: Throwable) { failure.set(error); ready.countDown(); left.countDown() }
                finally { if (entered) threads.leaveCurrent(outcome) }
            }.apply { isDaemon = true }
            worker.start()
            try {
                assertTrue(ready.await(3, TimeUnit.SECONDS))
                failure.get()?.let { throw AssertionError("thread registration failed", it) }
                assertEquals(worker.threadId(), capability.get().liveJavaId())
                leave.countDown()
                assertTrue(left.await(3, TimeUnit.SECONDS))
                failure.get()?.let { throw AssertionError("thread completion failed", it) }
                assertTrue(worker.isAlive, "Separate terminal guest status from actual Java carrier death")
                if (forked) assertNull(capability.get().liveJavaId())
                else assertEquals(worker.threadId(), capability.get().liveJavaId())
                stop.countDown(); worker.join(3000)
                assertFalse(worker.isAlive)
                failure.get()?.let { throw AssertionError("worker failed", it) }
                assertNull(capability.get().liveJavaId())
                assertEquals(1L, owner.dereference(weak.get()).flag, "Liveness query must not finalize the weak")
                threads.close()
                assertNull(capability.get().liveJavaId())
            } finally {
                leave.countDown(); stop.countDown(); worker.join(3000)
                threads.close(); owner.close()
            }
        }
    }

    private val state = CoreRepresentation(CoreKind.VOID, present = true, primReps = emptyList())
    private val weak = CoreRepresentation(CoreKind.OBJECT, present = true, primReps = listOf("BoxedRep (Just Unlifted)"))
    private val lifted = CoreRepresentation(CoreKind.DATA, present = true, primReps = listOf("BoxedRep (Just Lifted)"))
    private val action = lifted.copy(kind = CoreKind.CLOSURE)
    private val flag = CoreRepresentation(CoreKind.LONG, present = true, primReps = listOf("IntRep"))
    private fun tuple(vararg fields: CoreRepresentation) = CoreRepresentation(CoreKind.UNKNOWN, present = true,
        components = fields.toList(), primReps = fields.flatMap { it.primReps!! })
    private fun inputs(op: WeakOp, value: CoreRepresentation) = when (op) {
        WeakOp.MAKE -> listOf(weak, value, action, state)
        WeakOp.MAKE_PLAIN -> listOf(weak, value, state)
        else -> listOf(weak, state)
    }
    private fun output(op: WeakOp, value: CoreRepresentation) = when (op) {
        WeakOp.MAKE, WeakOp.MAKE_PLAIN -> tuple(state, weak)
        WeakOp.DEREFERENCE -> tuple(state, flag, value)
        WeakOp.FINALIZE -> tuple(state, flag, action)
    }
    private fun flags(args: List<CoreRepresentation>) = args.map { it.primReps == listOf("BoxedRep (Just Lifted)") }

    @Test fun exactFourContractsRejectCFinalizersAndForgedStateRepresentationOrBinding() {
        assertEquals(4, WeakOp.entries.size)
        assertNull(WeakOp.named("addCFinalizerToWeak#"))
        for (op in WeakOp.entries) for (value in listOf(lifted, action, weak)) {
            val args = inputs(op, value)
            val result = output(op, value)
            op.validate(args, flags(args), result)
            assertThrows(RuntimeFault::class.java) { op.validate(args.dropLast(1), flags(args), result) }
            for (index in args.indices) {
                val bad = args.toMutableList().also { it[index] = flag.copy(primReps = listOf("WordRep")) }
                assertThrows(RuntimeFault::class.java) { op.validate(bad, flags(bad), result) }
                val wrongFlags = flags(args).toMutableList().also { it[index] = !it[index] }
                assertThrows(RuntimeFault::class.java) { op.validate(args, wrongFlags, result) }
                val stored = args.map { it as CoreRepresentation? }.toMutableList().also {
                    it[index] = CoreRepresentation(CoreKind.ADDRESS, present = true, primReps = listOf("AddrRep"))
                }
                assertThrows(RuntimeFault::class.java) { op.validateBindings(args, stored) }
            }
            assertThrows(RuntimeFault::class.java) { op.validate(args, flags(args), result.copy(primReps = emptyList())) }
            assertThrows(RuntimeFault::class.java) { op.validate(args, flags(args), tuple(*result.components!!.drop(1).toTypedArray())) }
        }
        WeakOp.MAKE.validateAction(listOf(state) to tuple(state, lifted))
        assertThrows(RuntimeFault::class.java) { WeakOp.MAKE.validateAction(listOf(flag) to tuple(state, lifted)) }
        assertThrows(RuntimeFault::class.java) { WeakOp.MAKE.validateAction(listOf(state) to tuple(state, weak)) }
        assertThrows(RuntimeFault::class.java) { WeakOp.MAKE.validateAction(listOf(state) to state) }
    }

    private val root = File(System.getProperty("thc.projectRoot"))
    private val directory = File(root, "build/weak-explicit")
    private fun json(file: File) = Json.parse(file.readText()) as Map<String, Any?>
    private fun digest(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
        .joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun valid(target: RootCallTarget) = assertEquals(true,
        target.javaClass.getMethod("isValidLastTier").invoke(target), target.rootNode.name)
    private fun activeTargets(entry: RootCallTarget): List<RootCallTarget> {
        val seen = Collections.newSetFromMap(IdentityHashMap<RootCallTarget, Boolean>())
        val found = mutableListOf<RootCallTarget>()
        fun visit(target: RootCallTarget) {
            if (!seen.add(target)) return
            val body = target.rootNode
            val nodes = if (body is BytecodeRoot) listOf(body) + body.bytecodeNode.instructions.flatMap { it.arguments }
                .filter { it.kind == Instruction.Argument.Kind.NODE_PROFILE }.mapNotNull { it.asCachedNode() }
                else listOf(body)
            for (call in nodes.flatMap { NodeUtil.findAllNodeInstances(it, DirectCallNode::class.java) }) {
                val callee = call.currentCallTarget as? RootCallTarget ?: continue
                if (callee.rootNode is GuestRoot) visit(callee)
            }
            found.add(target)
        }
        visit(entry)
        return found
    }
    private fun applications(value: Any?): List<MutableList<Any?>> = when (value) {
        is List<*> -> (if (value.firstOrNull() == "app") listOf(value as MutableList<Any?>) else emptyList()) +
            value.flatMap(::applications)
        is Map<*, *> -> value.values.flatMap(::applications)
        else -> emptyList()
    }

    @Test fun genuineCoreCannotBypassEitherLoaderWithForgedWeakContracts() {
        val manifest = json(File(directory, "manifest.json"))
        for ((stage, paths) in manifest["stages"] as Map<String, List<String>>) {
            val original = CoreModules.reachable(CoreModules.merge(paths.map { json(File(root, it)) }),
                "weakComposite", strictLink = true)
            for (backend in listOf("ast", "bytecode")) context().use { context ->
                context.initialize("thc"); context.enter()
                try {
                    val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                    for (op in WeakOp.entries) for (mutation in 0..2) {
                        val module = Json.parse(Json.stringify(original)) as Map<String, Any?>
                        val app = applications(module).first { (it[1] as? List<*>)?.take(2) == listOf("prim", op.primitive) }
                        val args = app[2] as MutableList<Any?>
                        when (mutation) {
                            0 -> (app[3] as MutableList<Any?>).let { it[0] = !(it[0] as Boolean) }
                            1 -> (CoreRepresentations.metadata(args.last() as List<Any?>) as MutableMap<String, Any?>)["rep"] =
                                mapOf("kind" to "long", "primReps" to listOf("IntRep"))
                            2 -> {
                                val result = CoreRepresentations.metadata(app)!!["rep"] as MutableMap<String, Any?>
                                (result["components"] as MutableList<Any?>).removeAt(0) // Preserve physical reps, forge logical State.
                            }
                        }
                        assertThrows(RuntimeFault::class.java, {
                            if (backend == "ast") Program(language, module) else BytecodeProgram(language, module)
                        }, "$stage/$backend/${op.primitive}/mutation=$mutation")
                    }
                } finally { context.leave() }
            }
        }
    }

    @Test fun genuineNativeCompositeAgreesOnTheFirstInstalledAstAndBytecodeCalls() {
        val manifest = json(File(directory, "manifest.json"))
        assertEquals(1L, manifest["schema"])
        assertEquals("9.14.1", manifest["ghc"])
        assertEquals("weakComposite", manifest["entry"])
        for (kind in listOf("inputHashes", "artifactHashes"))
            for ((path, expected) in manifest[kind] as Map<String, String>)
                assertEquals(expected, digest(File(root, path)), "Stale explicit weak fixture: $path")
        val rows = File(directory, "oracle.tsv").readLines().map { line ->
            line.split('\t').let { it[0].toLong() to it[1].toLong() }
        }
        assertEquals(8, rows.size)
        assertEquals(rows.size.toLong(), manifest["nativeRows"])
        for ((input, expected) in rows) assertEquals(input + 58L, expected)
        for ((stage, paths) in manifest["stages"] as Map<String, List<String>>) {
            val audit = json(File(directory, "$stage/audit.json"))
            assertEquals(true, audit["accepted"])
            assertEquals(emptyList<Any>(), audit["issues"])
            assertEquals(emptyList<Any>(), audit["missingGlobals"])
            val primitives = (audit["primitives"] as List<Map<String, Any?>>).map { it["name"] }.toSet()
            assertTrue(primitives.containsAll(WeakOp.entries.map { it.primitive }))
            assertFalse("addCFinalizerToWeak#" in primitives)
            val merged = CoreModules.merge(paths.map { json(File(root, it)) })
            for (backend in listOf("ast", "bytecode")) context().use { context ->
                context.initialize("thc"); context.enter()
                try {
                    val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                    val module = CoreModules.reachable(merged, "weakComposite", strictLink = true) + ("instrument" to true)
                    val program = if (backend == "ast") Program(language, module) else BytecodeProgram(language, module)
                    val host = program.hostEntryTarget(1)
                    val original = program.entryTarget("weakComposite")
                    assertTrue(original.rootNode is GuestRoot)
                    val function = context.asValue(EntryValue(program, "weakComposite", 1))
                    for ((input, expected) in rows) assertEquals(expected, function.execute(input).asLong())
                    val active = activeTargets(host)
                    assertTrue(active.any { it.rootNode is GuestRoot && (it.rootNode as GuestRoot).isSelf(original) },
                        "$stage/$backend actual weakComposite host linkage")
                    // Include the original identity and every observed split/worker target,
                    // including BytecodeDSL's cached direct calls. Do not settle after install.
                    for (target in (active + original).distinct()) {
                        target.javaClass.getMethod("compile", Boolean::class.javaPrimitiveType).invoke(target, true)
                        valid(target)
                    }
                    for ((input, expected) in rows.asReversed()) {
                        val before = (program.diagnostics().getValue("compiledEntries") as Number).toLong()
                        assertEquals(expected, function.execute(input).asLong(), "$stage/$backend first installed $input")
                        val delta = (program.diagnostics().getValue("compiledEntries") as Number).toLong() - before
                        println("weak-explicit $stage/$backend input=$input compiledGuestEntries=$delta targets=" +
                            (active + original).distinct().map { target -> target.rootNode.name + ":valid=" +
                                target.javaClass.getMethod("isValidLastTier").invoke(target) })
                        // EntryRoot does not increment compiledEntries; this is guest entry evidence.
                        assertEquals(3L, delta, "$stage/$backend compiled input/runRW/returned-action guest entries")
                        assertSame(original, program.entryTarget("weakComposite"))
                        assertEquals(active, activeTargets(host), "$stage/$backend target graph changed")
                        (active + original).distinct().forEach(::valid)
                        assertEquals(0, Language.currentState().weaks.retainedCount())
                        assertEquals(0, language.handoffState.get().arguments.depth)
                        assertEquals(0, language.handoffState.get().arguments.retainedReferences())
                        assertEquals(0, language.handoffState.get().results.depth)
                        assertEquals(0, language.handoffState.get().results.retainedReferences())
                        assertNull(language.handoffState.get().pending)
                        assertEquals(0L, (program.diagnostics().getValue("unsupportedTraps") as Number).toLong())
                    }
                    assertEquals(0L, (program.diagnostics().getValue("unsupportedTraps") as Number).toLong())
                } finally { context.leave() }
            }
        }
    }
}
