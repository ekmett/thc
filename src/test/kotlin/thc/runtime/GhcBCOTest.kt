// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime

import com.oracle.truffle.api.TruffleLanguage
import com.oracle.truffle.api.frame.FrameDescriptor
import com.oracle.truffle.api.frame.VirtualFrame
import com.oracle.truffle.api.nodes.Node
import org.graalvm.polyglot.Context
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import thc.CoreModules
import thc.EntryValue
import thc.Json
import thc.Language
import java.io.File
import java.security.MessageDigest

@Timeout(120)
class GhcBCOTest {
    private val root = File(System.getProperty("thc.projectRoot"))
    private val entries = listOf("bcoConstant", "bcoApply", "bcoApplyTwo", "bcoFunction",
        "bcoArithmetic", "bcoBranch", "bcoLargeOperand", "bcoSharing")
    private fun expected(entry: String, n: Long): Long = when (entry) {
        "bcoConstant", "bcoFunction", "bcoLargeOperand" -> n
        "bcoApply" -> n + 7
        "bcoApplyTwo" -> n * 10 + 3
        "bcoArithmetic" -> 100 - n
        "bcoBranch" -> if (n < 0) -1 else 1
        "bcoSharing" -> n * 2 + 1
        else -> error(entry)
    }
    private fun context() = Context.newBuilder("thc").allowExperimentalOptions(true)
        .option("engine.WarnInterpreterOnly", "false").option("compiler.Inlining", "false")
        .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false")
        .option("engine.SingleTierCompilationThreshold", "10000000")
        .option("engine.CompilationFailureAction", "Throw").build()

    @Test fun nativeInstructionsExecuteThroughBothCoreBackends() {
        val manifest = Json.parse(File(root, "build/ghc-bco/manifest.json").readText()) as Map<*, *>
        assertEquals(entries, manifest["entries"])
        for (group in listOf("inputHashes", "artifactHashes")) for ((path, hash) in manifest[group] as Map<*, *>) {
            assertEquals(hash, MessageDigest.getInstance("SHA-256").digest(File(root, path as String).readBytes())
                .joinToString("") { "%02x".format(it) }, path)
        }
        val native = (manifest["native"] as List<*>).map { (it as Number).toLong() }
        assertEquals(listOf(-2L, 0L, 7L).flatMap { n -> entries.map { expected(it, n) } }, native)
        for (stage in listOf("pre", "post")) for (backend in listOf("ast", "bytecode")) for (entry in entries) {
            context().use { context ->
                context.initialize("thc"); context.enter()
                try {
                    @Suppress("UNCHECKED_CAST")
                    val module = Json.parse(File(root, "build/ghc-bco/$stage/core/GhcBCO.json").readText()) as Map<String, Any?>
                    val evidence = ArrayCoreEvidence(module, entry)
                    assertEquals(1, evidence.primitiveCounts["newBCO#"], "Actual primitive, not a synthetic BCO substitute")
                    assertEquals(if (entry in listOf("bcoFunction", "bcoArithmetic")) null else 1,
                        evidence.primitiveCounts["mkApUpd0#"])
                    val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                    val linked = CoreModules.reachable(module, entry)
                    val program: ExecutableProgram = if (backend == "ast") Program(language, linked) else BytecodeProgram(language, linked)
                    val function = context.asValue(EntryValue(program, entry, 1))
                    for ((index, n) in listOf(-2L, 0L, 7L).withIndex()) {
                        assertEquals(native[index * entries.size + entries.indexOf(entry)], function.execute(n).asLong(), "$stage/$backend/$entry/$n")
                        ThreadInventoryCoreEvidence.released(language)
                    }
                    // Compile precisely the genuine public Core root. BCOs are
                    // dynamically created interpreter roots, not invented Core.
                    val target = program.entryTarget(entry)
                    val interpreted = ThreadInventoryCoreEvidence.interpretedCalls(listOf(target))
                    val before = (program.diagnostics().getValue("compiledEntries") as Number).toLong()
                    ThreadInventoryCoreEvidence.install(listOf(target))
                    val pools = language.handoffState.get()
                    val allocations = pools.arguments.allocations to pools.results.allocations
                    assertEquals(expected(entry, 11), function.execute(11L).asLong(), "$stage/$backend/$entry first installed call")
                    assertEquals(1L, (program.diagnostics().getValue("compiledEntries") as Number).toLong() - before)
                    assertEquals(interpreted, ThreadInventoryCoreEvidence.interpretedCalls(listOf(target)), "No settling guest call")
                    ThreadInventoryCoreEvidence.released(language)
                    assertEquals(allocations, pools.arguments.allocations to pools.results.allocations)
                } finally { context.leave() }
            }
        }
    }

    private fun code(vararg values: Int) = ByteArray(values.size * 2).also { bytes ->
        values.forEachIndexed { index, value -> ManagedByteArray.writeInt16(bytes, index.toLong(), value.toLong()) }
    }
    private fun words(vararg values: Long) = ByteArray(values.size * 8).also { bytes ->
        values.forEachIndexed { index, value -> ManagedByteArray.writeInt(bytes, index.toLong(), value) }
    }
    private fun create(language: Language, code: ByteArray, arity: Long = 0, bitmap: ByteArray = words(0),
                       literals: ByteArray = words(), refs: Array<Any?> = emptyArray()): Closure =
        GhcBCO.create(object : Node() {}, language, Metrics(false), code, literals, refs, arity, bitmap, Unit)

    @Test fun malformedBytecodeFailsBeforeExecutionOrPointerBitFabrication() {
        context().use { context ->
            context.initialize("thc"); context.enter()
            try {
                val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                for (bad in listOf(code(), byteArrayOf(11), code(11), code(0x800b, 0, 0), code(0x100b, 0),
                    code(56), code(45, 0, 0), code(55, 1), code(55, 99), code(11, 0, 58), code(25, 0, 1, 61)))
                    assertThrows(RuntimeFault::class.java) { create(language, bad) }
                assertThrows(RuntimeFault::class.java) { create(language, code(60), arity = -1) }
                assertThrows(RuntimeFault::class.java) { create(language, code(60), arity = 1, bitmap = words(1)) }
                val underflow = create(language, code(2, 0, 60))
                assertThrows(RuntimeFault::class.java) { underflow.target.call(0L) }
                val branchIntoOperand = code(25, 0, 1, 46, 0, 1, 61)
                assertThrows(RuntimeFault::class.java) { create(language, branchIntoOperand, literals = words(0)) }
                val pointer = Any()
                val pointerAsWord = create(language, code(11, 0, 61), refs = arrayOf(pointer))
                assertThrows(RuntimeFault::class.java) { pointerAsWord.target.call(0L) }
                val function = create(language, code(58), 1, words(1, 0))
                assertThrows(RuntimeFault::class.java) { GhcBCO.updating(object : Node() {}, function) }
            } finally { context.leave() }
        }
    }

    @Test fun updatingWrapperIsLazySharedAndReleasesItsCodeAfterUpdate() {
        context().use { context ->
            context.initialize("thc"); context.enter()
            try {
                val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                var effects = 0
                val answer = Any()
                val worker = object : GuestRoot(language, FrameDescriptor.newBuilder().build()) {
                    override fun bloom(frame: VirtualFrame) = 0L
                    override fun execute(frame: VirtualFrame): Any { effects++; return answer }
                }
                val bco = create(language, code(11, 1, 31, 11, 0, 58),
                    refs = arrayOf(Closure(null, arity = 1, target = worker.callTarget), Any()))
                val thunk = GhcBCO.updating(object : Node() {}, bco)
                assertEquals(0, effects)
                val probe = object : GuestRoot(language, FrameDescriptor.newBuilder().build()) {
                    @Child private var force = Force(Metrics(false))
                    override fun bloom(frame: VirtualFrame) = 0L
                    override fun execute(frame: VirtualFrame) = force.execute(frame, frame.arguments[0])
                }.callTarget
                assertSame(answer, probe.call(thunk)); assertSame(answer, probe.call(thunk))
                assertEquals(1, effects)
                assertEquals(2, thunk.state); assertNull(thunk.target); assertNull(thunk.environment)
                val second = GhcBCO.updating(object : Node() {}, bco)
                assertSame(answer, probe.call(second)); assertEquals(2, effects)
                ThreadInventoryCoreEvidence.released(language)
            } finally { context.leave() }
        }
    }

    @Test fun boxedApplicationsUseRealPapsAndGuestFailureUpdates() {
        context().use { context ->
            context.initialize("thc"); context.enter()
            try {
                val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                val first = Any(); val second = Any()
                val selector = create(language, code(2, 0, 38, 1, 2, 58), 2, words(2, 0))
                val pap = selector.pap(arrayOf(first))
                val call = object : GuestRoot(language, FrameDescriptor.newBuilder().build()) {
                    @Child private var dispatch: Dispatch = DispatchNodeGen.create(1, false, Metrics(false))
                    override fun bloom(frame: VirtualFrame) = 0L
                    override fun execute(frame: VirtualFrame) = dispatch.execute(frame, pap, arrayOf(second))
                }.callTarget
                assertSame(first, call.call(0L))
                val payload = Any()
                var effects = 0
                val throwing = object : GuestRoot(language, FrameDescriptor.newBuilder().build()) {
                    override fun bloom(frame: VirtualFrame) = 0L
                    override fun execute(frame: VirtualFrame): Nothing { effects++; throw GuestException(payload, this) }
                }
                val bco = create(language, code(11, 1, 31, 11, 0, 58),
                    refs = arrayOf(Closure(null, arity = 1, target = throwing.callTarget), first))
                val thunk = GhcBCO.updating(object : Node() {}, bco)
                val force = object : GuestRoot(language, FrameDescriptor.newBuilder().build()) {
                    @Child private var force = Force(Metrics(false))
                    override fun bloom(frame: VirtualFrame) = 0L
                    override fun execute(frame: VirtualFrame) = force.execute(frame, thunk)
                }.callTarget
                repeat(2) { assertSame(payload, assertThrows(GuestException::class.java) { force.call(0L) }.payload) }
                assertEquals(1, effects); assertEquals(3, thunk.state)
                ThreadInventoryCoreEvidence.released(language)
            } finally { context.leave() }
        }
    }

    @Test fun unsupportedSuspensionsCannotDiscardPendingBcoStackWork() {
        context().use { context ->
            context.initialize("thc"); context.enter()
            try {
                val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                val state = CoreRepresentation(CoreKind.VOID, primReps = emptyList())
                val boxed = CoreRepresentation(CoreKind.OBJECT, primReps = listOf("BoxedRep (Just Lifted)"))
                val shape = TupleShape(CoreRepresentation(CoreKind.UNKNOWN, primReps = boxed.primReps,
                    components = listOf(state, boxed)), language)
                var resumed = 0
                for (capture in listOf(false, true)) {
                    val worker = object : GuestRoot(language, FrameDescriptor.newBuilder().build()) {
                        override fun bloom(frame: VirtualFrame) = 0L
                        override fun execute(frame: VirtualFrame): Any {
                            if (capture) throw DelimitedCut(PromptTag(Language.currentState(null)), null, shape,
                                MaskingState.UNMASKED, this)
                            return object : SavedGuestContinuation {
                                override val identity = Any()
                                override val yielded = Any()
                                override val sourceRoot = Any()
                                override fun continueWith(input: Any?): Any { resumed++; return Any() }
                            }
                        }
                    }
                    val bco = create(language, code(11, 1, 31, 11, 0, 58),
                        refs = arrayOf(Closure(null, arity = 1, target = worker.callTarget), Any()))
                    val failure = assertThrows(RuntimeFault::class.java) { bco.target.call(0L) }
                    assertTrue(failure.message!!.contains(if (capture) "Delimited capture" else "asynchronous continuation"))
                }
                assertEquals(0, resumed)
                ThreadInventoryCoreEvidence.released(language)
            } finally { context.leave() }
        }
    }

    @Test fun loweringRejectsWrongPhysicalCarriersAndTupleOrder() {
        val state = CoreRepresentation(CoreKind.VOID, primReps = emptyList())
        val pointer = CoreRepresentation(CoreKind.OBJECT, primReps = listOf("BoxedRep (Just Unlifted)"))
        val bco = CoreRepresentation(CoreKind.OBJECT, primReps = listOf("BoxedRep (Just Lifted)"))
        val number = CoreRepresentation(CoreKind.LONG, primReps = listOf("IntRep"))
        fun tuple(vararg fields: CoreRepresentation) = CoreRepresentation(CoreKind.UNKNOWN,
            primReps = fields.flatMap { it.primReps!! }, components = fields.toList())
        val args = listOf(pointer, pointer, pointer, number, pointer, state)
        GhcBCO.validate("newBCO#", args, List(6) { false }, tuple(state, bco))
        GhcBCO.validate("mkApUpd0#", listOf(bco), listOf(true), tuple(bco))
        for (index in args.indices) {
            val bad = args.toMutableList().also { it[index] = if (index == 3) pointer else number }
            assertThrows(RuntimeFault::class.java) { GhcBCO.validate("newBCO#", bad, List(6) { false }, tuple(state, bco)) }
        }
        assertThrows(RuntimeFault::class.java) { GhcBCO.validate("newBCO#", args, List(6) { false }, tuple(bco, state)) }
        assertThrows(RuntimeFault::class.java) { GhcBCO.validate("mkApUpd0#", listOf(bco), listOf(true), tuple(number)) }
        assertThrows(RuntimeFault::class.java) { GhcBCO.validate("mkApUpd0#", listOf(bco), listOf(false), tuple(bco)) }
    }

    @Test fun scalarStackOperationsPreserveOrderBitsAndContextOwnership() {
        lateinit var foreign: Closure
        context().use { context ->
            context.initialize("thc"); context.enter()
            try {
                val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                val arithmetic = create(language, code(25, 0, 1, 91, 61), 1, words(1, 1), words(100))
                for (n in listOf(Long.MIN_VALUE, -1L, 0L, 1L, Long.MAX_VALUE))
                    assertEquals(100L - n, arithmetic.target.call(0L, n))
                val reverse = create(language, code(3, 0, 1, 38, 2, 2, 91, 61), 2, words(2, 3))
                assertEquals(9L, reverse.target.call(0L, 3L, 12L))
                val nan = 0x7ff8000000000042L
                val floating = create(language, code(25, 0, 1, 63), literals = words(nan))
                assertEquals(nan, (floating.target.call(0L) as Double).toRawBits())
                val shift = create(language, code(99, 61), 2, words(2, 3))
                assertEquals(1L, shift.target.call(0L, -1L, 63L))
                assertThrows(RuntimeFault::class.java) { shift.target.call(0L, 1L, 64L) }
                foreign = arithmetic
            } finally { context.leave() }
        }
        context().use { context ->
            context.initialize("thc"); context.enter()
            try {
                assertThrows(RuntimeFault::class.java) { foreign.target.call(0L, 7L) }
                assertThrows(RuntimeFault::class.java) { GhcBCO.updating(object : Node() {}, foreign) }
            } finally { context.leave() }
        }
    }
}
