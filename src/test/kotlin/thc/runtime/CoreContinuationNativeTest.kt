// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime

import com.oracle.truffle.api.RootCallTarget
import com.oracle.truffle.api.TruffleLanguage
import com.oracle.truffle.api.bytecode.BytecodeRootNode
import com.oracle.truffle.api.bytecode.BytecodeConfig
import com.oracle.truffle.api.bytecode.ContinuationResult
import com.oracle.truffle.api.bytecode.LocalAccessor
import com.oracle.truffle.api.frame.FrameSlotKind
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.graalvm.polyglot.Context
import thc.Language
import thc.CoreModules
import thc.Json
import thc.executionContext
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference
import com.oracle.truffle.api.frame.VirtualFrame
import com.oracle.truffle.api.nodes.RootNode

/** A real GHC Core thunk and local demand, with a private deterministic test checkpoint. */
class CoreContinuationNativeTest {
    private val root = File(System.getProperty("thc.projectRoot"))

    private fun compile(target: RootCallTarget) {
        target.javaClass.getMethod("compile", Boolean::class.javaPrimitiveType).invoke(target, true)
        assertEquals(true, target.javaClass.getMethod("isValidLastTier").invoke(target))
    }

    private class Driver : RootNode(null) {
        @Child private var force = Force(Metrics(true))
        override fun execute(frame: VirtualFrame): Any? = force.execute(frame, frame.arguments[0])
        fun force(thunk: Thunk): Any? = Calls.target(callTarget, arrayOf(thunk))
        fun deliver(boundary: Any, child: CallSegment, payload: Any?, afterClaim: (() -> Unit)? = null): Any? =
            force.deliverAtCapturedIOHandler(boundary, child, payload, afterClaim)
    }

    private fun <T> entered(context: Context, action: () -> T): T {
        context.enter()
        try { return action() } finally { context.leave() }
    }

    @Suppress("UNCHECKED_CAST")
    private fun linkedWithPayload(module: Map<String, Any?>, entry: String): Map<String, Any?> {
        val action = CoreModules.reachable(module, entry)
        val payload = CoreModules.reachable(module, "asyncPayload")
        val bindings = ((action["bindings"] as List<Map<String, Any?>>) +
            (payload["bindings"] as List<Map<String, Any?>>)).distinctBy { it["id"] }
        return action + ("bindings" to bindings)
    }

    private fun callSegmentCaller(language: Language, segment: CallSegment): RootCallTarget {
        val suspended = CallSegmentSuspended(segment)
        return BytecodeRootGen.create(language, BytecodeConfig.DEFAULT) { b ->
            b.beginRoot()
            b.beginReturn()
            b.beginResumeApplication()
            b.emitLoadConstant(suspended)
            b.beginYield(); b.emitLoadConstant(suspended); b.endYield()
            b.endResumeApplication()
            b.endReturn()
            b.endRoot()
        }.getNode(0).callTarget
    }

    @Test fun nativeNonTailApplicationResumesCalleeThenCaller() {
        assertEquals("208", File(root, "build/core-continuation/native-output.txt").readLines()[1])
        @Suppress("UNCHECKED_CAST")
        val module = Json.parse(File(root, "build/core-continuation/core/CoreContinuationAudit.json").readText()) as Map<String, Any?>
        executionContext().use { context ->
            context.initialize("thc")
            context.enter()
            try {
                val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                val linked = CoreModules.reachable(module, "applicationAnswer")
                val ast = Program(language, linked)
                val astThunk = ast.entryValue("applicationAnswer") as Thunk
                val astTarget = astThunk.target!!
                val astAnswer = Calls.target(ast.hostEntryTarget(0), arrayOf(astThunk)) as DataValue
                assertEquals(208L, astAnswer.layout.readLong(astAnswer, 0))
                compile(astTarget)
                val compiledAst = Calls.target(astTarget, arrayOf(0L)) as DataValue
                assertEquals(208L, compiledAst.layout.readLong(compiledAst, 0))
                val checkpoint = BytecodeCheckpoint()
                val program = BytecodeProgram(language, linked, checkpoint)
                val parent = program.entryValue("applicationAnswer") as Thunk
                val target = parent.target!!
                val callee = program.entryTarget("delayed")
                assertTrue(Calls.target(callee, arrayOf(0L, 7L)) is DataValue)
                compile(callee)
                assertTrue(Calls.target(target, arrayOf(0L)) is DataValue)
                compile(target)
                val compiledBefore = (program.diagnostics().getValue("compiledEntries") as Number).toLong()
                checkpoint.armed = true
                val driver = Driver()
                assertSame(parent, assertThrows(ThunkSuspended::class.java) { driver.force(parent) }.thunk)
                val caller = parent.value as ContinuationResult
                val suspendedCall = (caller.result as CallSegmentSuspended).segment
                assertNotSame(parent, suspendedCall)
                assertEquals(5, suspendedCall.state)
                val calleeSegment = suspendedCall.value as ContinuationResult
                assertTrue((calleeSegment.continuationRootNode.sourceRootNode as BytecodeRoot).isSelf(callee))
                assertEquals(1, checkpoint.visits.get())
                assertTrue(checkpoint.compiledVisits.get() > 0, "The callee checkpoint ran in installed code")
                assertTrue((program.diagnostics().getValue("compiledEntries") as Number).toLong() > compiledBefore,
                    "The Core caller entered its explicitly compiled target")
                val result = driver.force(parent) as DataValue
                assertEquals(208L, result.layout.readLong(result, 0))
                assertEquals(1, checkpoint.visits.get(), "The callee must not replay its checkpoint")
                assertEquals(2, suspendedCall.state)
                assertEquals(2, parent.state)
            } finally { context.leave() }
        }
    }

    @Test fun applicationRejectsUnownedNestedRootAndPreservesMaskedCarrier() {
        @Suppress("UNCHECKED_CAST")
        val module = Json.parse(File(root, "build/core-continuation/core/CoreContinuationAudit.json").readText()) as Map<String, Any?>
        executionContext().use { context ->
            context.initialize("thc")
            context.enter()
            try {
                val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                fun checked(entry: String): Pair<BytecodeProgram, Thunk> {
                    val program = BytecodeProgram(language, CoreModules.reachable(module, entry),
                        BytecodeCheckpoint().also { it.armed = true })
                    return program to (program.entryValue(entry) as Thunk)
                }
                val driver = Driver()
                val (_, nested) = checked("nestedApplication")
                val wrongRoot = assertThrows(IllegalStateException::class.java) { driver.force(nested) }
                assertTrue(wrongRoot.message!!.contains("unrelated bytecode continuation"))
                assertEquals(4, nested.state, "An unowned nested root cannot be replayed")

                val (_, masked) = checked("applicationAnswer")
                val maskNode = masked.target!!.rootNode
                SynchronousMasking.set(maskNode, MaskingState.MASKED_INTERRUPTIBLE)
                try {
                    assertSame(masked, assertThrows(ThunkSuspended::class.java) { driver.force(masked) }.thunk)
                    assertEquals(MaskingState.MASKED_INTERRUPTIBLE, SynchronousMasking.current(driver))
                    val answer = driver.force(masked) as DataValue
                    assertEquals(208L, answer.layout.readLong(answer, 0))
                    assertEquals(MaskingState.MASKED_INTERRUPTIBLE, SynchronousMasking.current(driver))
                } finally { SynchronousMasking.set(maskNode, MaskingState.UNMASKED) }

                val (_, malformed) = checked("applicationAnswer")
                assertThrows(ThunkSuspended::class.java) { driver.force(malformed) }
                val saved = malformed.value as ContinuationResult
                assertThrows(IllegalStateException::class.java) { saved.continueWith(Unit) }
            } finally { context.leave() }
        }
    }

    @Test fun suspendedApplicationReturnsLazyThunkWithoutEnteringIt() {
        executionContext().use { context ->
            context.initialize("thc")
            context.enter()
            try {
                val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                val checkpoint = BytecodeCheckpoint().also { it.armed = true }
                val lazyEffects = AtomicInteger()
                val lazy = Thunk(object : RootNode(null) {
                    override fun execute(frame: VirtualFrame): Any {
                        lazyEffects.incrementAndGet()
                        return 7L
                    }
                }.callTarget, null)
                val callee = BytecodeRootGen.create(language, BytecodeConfig.DEFAULT) { b ->
                    b.beginRoot()
                    val visited = b.createLocal("visited", "primitive")
                    b.beginBlock()
                    b.beginStoreLocal(visited); b.emitCheckpointArmed(checkpoint); b.endStoreLocal()
                    b.beginYield(); b.emitLoadConstant(Unit); b.endYield()
                    b.beginYield(); b.emitLoadConstant(Unit); b.endYield()
                    b.beginReturn(); b.emitLoadConstant(lazy); b.endReturn()
                    b.endBlock()
                    b.endRoot()
                }.getNode(0).callTarget
                val continuation = Calls.target(callee, arrayOf(0L)) as ContinuationResult
                val function = Closure(null, NO_PAP_ARGUMENTS, 0, callee)
                val call = assertThrows(CapturedCallSuspension::class.java) {
                    BytecodeRoot.CaptureApplicationResult.capture(0, function, continuation,
                        MaskingState.UNMASKED, Driver())
                }.segment
                assertEquals(5, call.state)
                assertEquals(1, checkpoint.visits.get())
                assertEquals(0, lazy.state, "The returned lazy thunk must not be forced")
                assertEquals(0, lazyEffects.get())
                val suspended = CallSegmentSuspended(call)
                val layout = DataLayout(language, "proof.LazyBox", "LazyBox", arrayOf("LiftedRep"))
                val caller = BytecodeRootGen.create(language, BytecodeConfig.DEFAULT) { b ->
                    b.beginRoot()
                    b.beginReturn()
                    b.beginConstruct(layout)
                    b.beginResumeApplication()
                    b.emitLoadConstant(suspended)
                    b.beginYield(); b.emitLoadConstant(suspended); b.endYield()
                    b.endResumeApplication()
                    b.endConstruct()
                    b.endReturn()
                    b.endRoot()
                }.getNode(0).callTarget
                val parent = Thunk(caller, null)
                val driver = Driver()
                assertSame(parent, assertThrows(ThunkSuspended::class.java) { driver.force(parent) }.thunk)
                assertSame(parent, assertThrows(ThunkSuspended::class.java) { driver.force(parent) }.thunk)
                assertEquals(5, call.state, "A second yield retains the same call segment")
                val box = driver.force(parent) as DataValue
                assertSame(lazy, box.layout.read(box, 0), "The call returns the original lazy value")
                assertEquals(2, call.state)
                assertEquals(2, parent.state)
                assertEquals(1, checkpoint.visits.get(), "The call must not replay its pre-yield work")
                assertEquals(0, lazyEffects.get(), "Constructing the caller result must not enter the thunk")
                assertEquals(7L, driver.force(lazy))
                assertEquals(1, lazyEffects.get(), "A later demand enters the value exactly once")
                assertEquals(7L, driver.force(lazy))
                assertEquals(1, lazyEffects.get())
            } finally { context.leave() }
        }
    }

    @Test fun twoWaitersResumeOneCallSegmentWithoutReplayingItsPrefix() {
        executionContext().use { context ->
            context.initialize("thc")
            val language = entered(context) { TruffleLanguage.LanguageReference.create(Language::class.java).get(null) }
            val effects = AtomicInteger()
            val gate = ThunkYieldProofRoot.Gate().also { it.armed = true }
            val marker = Any()
            val (segment, parent) = entered(context) {
                val callee = ThunkYieldProofRoot.target(language, effects, AtomicInteger(), gate, marker)
                val continuation = Calls.target(callee, arrayOf(0L)) as ContinuationResult
                val segment = CallSegment(continuation)
                segment to Thunk(callSegmentCaller(language, segment), null)
            }
            val driver = entered(context) { Driver() }
            entered(context) {
                assertSame(parent, assertThrows(ThunkSuspended::class.java) { driver.force(parent) }.thunk)
            }
            Executors.newFixedThreadPool(2).use { pool ->
                fun reader() = pool.submit<Any?> { entered(context) {
                    repeat(4) {
                        try { return@entered driver.force(parent) }
                        catch (yielded: ThunkSuspended) { assertSame(parent, yielded.thunk) }
                    }
                    fail<Any>("The same segment did not finish after its two yields")
                } }
                val first = reader()
                assertTrue(gate.entered.await(5, TimeUnit.SECONDS), "The first owner must be inside the segment")
                val second = reader()
                gate.release.countDown()
                val firstAnswer = first.get(5, TimeUnit.SECONDS)
                assertSame(firstAnswer, second.get(5, TimeUnit.SECONDS))
                assertSame(marker, (firstAnswer as ThunkYieldProofRoot.Answer).marker())
            }
            assertEquals(1, effects.get(), "Neither waiter may re-enter the callee prefix")
            assertEquals(2, segment.state)
            assertEquals(2, parent.state)
        }
    }

    @Test fun callSegmentMemoizesGuestFailureButHostUnwindFailsClosed() {
        executionContext().use { context ->
            context.initialize("thc")
            val language = entered(context) { TruffleLanguage.LanguageReference.create(Language::class.java).get(null) }
            val driver = entered(context) { Driver() }
            val payload = Any()
            val effects = AtomicInteger()
            val (segment, parent) = entered(context) {
                val failure = GuestException(payload, driver)
                val callee = ThunkYieldProofRoot.target(language, effects, AtomicInteger(),
                    ThunkYieldProofRoot.Gate(), failure)
                val segment = CallSegment(Calls.target(callee, arrayOf(0L)) as ContinuationResult)
                segment to Thunk(callSegmentCaller(language, segment), null)
            }
            entered(context) {
                repeat(2) { assertSame(parent, assertThrows(ThunkSuspended::class.java) { driver.force(parent) }.thunk) }
                assertSame(payload, assertThrows(GuestException::class.java) { driver.force(parent) }.payload)
                assertSame(payload, assertThrows(GuestException::class.java) { driver.force(parent) }.payload)
            }
            assertEquals(3, segment.state)
            assertEquals(3, parent.state)
            assertEquals(1, effects.get())

            val blocked = ThunkYieldProofRoot.Gate().also { it.armed = true }
            val hostEffects = AtomicInteger()
            val (hostSegment, hostParent) = entered(context) {
                val callee = ThunkYieldProofRoot.target(language, hostEffects, AtomicInteger(), blocked, Any())
                val segment = CallSegment(Calls.target(callee, arrayOf(0L)) as ContinuationResult)
                segment to Thunk(callSegmentCaller(language, segment), null)
            }
            entered(context) {
                assertSame(hostParent, assertThrows(ThunkSuspended::class.java) { driver.force(hostParent) }.thunk)
            }
            Executors.newSingleThreadExecutor().use { pool ->
                val ownerThread = AtomicReference<Thread>()
                val finished = CountDownLatch(1)
                val owner = pool.submit<Throwable?> { entered(context) {
                    ownerThread.set(Thread.currentThread())
                    try { driver.force(hostParent); null }
                    catch (failure: Throwable) { failure }
                    finally { finished.countDown() }
                } }
                assertTrue(blocked.entered.await(5, TimeUnit.SECONDS))
                ownerThread.get().interrupt()
                assertTrue(finished.await(5, TimeUnit.SECONDS))
                blocked.release.countDown()
                assertNotNull(owner.get(5, TimeUnit.SECONDS))
                assertEquals(4, hostSegment.state, "Unknown host unwind cannot replay a call segment")
            }
            entered(context) {
                val fault = assertThrows(RuntimeFault::class.java) { driver.force(hostParent) }
                assertTrue(fault.message!!.contains("no resumable continuation"))
            }
            assertEquals(1, hostEffects.get())
        }
    }

    @Test fun repeatedCallYieldRetainsActiveMaskAndRejectsUnrestoredCompletion() {
        executionContext().use { context ->
            context.initialize("thc")
            val language = entered(context) { TruffleLanguage.LanguageReference.create(Language::class.java).get(null) }
            val (segment, parent) = entered(context) {
                val target = BytecodeRootGen.create(language, BytecodeConfig.DEFAULT) { b ->
                    b.beginRoot()
                    val prior = b.createLocal("prior mask", "object")
                    b.beginYield(); b.emitLoadConstant(Unit); b.endYield()
                    b.beginStoreLocal(prior); b.emitEnterMask(MaskingState.MASKED_INTERRUPTIBLE); b.endStoreLocal()
                    b.beginYield(); b.emitLoadConstant(Unit); b.endYield()
                    b.beginReturn(); b.emitLoadConstant(1L); b.endReturn()
                    b.endRoot()
                }.getNode(0).callTarget
                val segment = CallSegment(Calls.target(target, arrayOf(0L)) as ContinuationResult)
                segment to Thunk(callSegmentCaller(language, segment), null)
            }
            val driver = entered(context) { Driver() }
            entered(context) {
                assertSame(parent, assertThrows(ThunkSuspended::class.java) { driver.force(parent) }.thunk)
                assertSame(parent, assertThrows(ThunkSuspended::class.java) { driver.force(parent) }.thunk)
                assertEquals(MaskingState.MASKED_INTERRUPTIBLE, segment.logicalMask)
                assertEquals(MaskingState.UNMASKED, SynchronousMasking.current(driver))
                val unsupported = assertThrows(IllegalStateException::class.java) { driver.force(parent) }
                assertTrue(unsupported.message!!.contains("did not restore its caller mask"))
                assertEquals(MaskingState.UNMASKED, SynchronousMasking.current(driver))
            }
            assertEquals(4, segment.state)
            assertEquals(5, parent.state)
        }
    }

    @Test fun wrongMaskCompletionReleasesPooledTupleBeforeFailingClosed() {
        executionContext().use { context ->
            context.initialize("thc")
            val language = entered(context) { TruffleLanguage.LanguageReference.create(Language::class.java).get(null) }
            val marker = Any()
            val tuple = entered(context) { TupleShape(CoreRepresentation(CoreKind.UNKNOWN, true, true,
                listOf("BoxedRep (Just Lifted)"), listOf(
                    CoreRepresentation(CoreKind.VOID, true, true, emptyList()),
                    CoreRepresentation(CoreKind.DATA, true, true, listOf("BoxedRep (Just Lifted)"))
                )), language) }
            val (segment, parent) = entered(context) {
                val target = BytecodeRootGen.create(language, BytecodeConfig.DEFAULT) { b ->
                    b.beginRoot()
                    val field = b.createLocal("tuple reference", "object")
                    val slots = BytecodeTupleSlots(tuple, arrayOf(LocalAccessor.constantOf(field)))
                    val prior = b.createLocal("prior mask", "object")
                    b.beginStoreLocal(field); b.emitLoadConstant(marker); b.endStoreLocal()
                    b.beginYield(); b.emitLoadConstant(Unit); b.endYield()
                    b.beginStoreLocal(prior); b.emitEnterMask(MaskingState.MASKED_INTERRUPTIBLE); b.endStoreLocal()
                    b.beginReturn(); b.emitFinishTuple(slots); b.endReturn()
                    b.endRoot()
                }.getNode(0).callTarget
                val segment = CallSegment(Calls.target(target, arrayOf(0L)) as ContinuationResult,
                    tupleShape = tuple)
                segment to Thunk(callSegmentCaller(language, segment), null)
            }
            val driver = entered(context) { Driver() }
            entered(context) {
                assertSame(parent, assertThrows(ThunkSuspended::class.java) { driver.force(parent) }.thunk)
                val unsupported = assertThrows(IllegalStateException::class.java) { driver.force(parent) }
                assertTrue(unsupported.message!!.contains("did not restore its caller mask"))
                assertEquals(MaskingState.UNMASKED, SynchronousMasking.current(driver))
                assertEquals(0, language.handoffState.get().results.depth,
                    "The completed producer-thread slab is released before wrong-mask rejection")
                assertEquals(0, language.handoffState.get().results.retainedReferences())
                assertThrows(RuntimeFault::class.java) { driver.force(parent) }
            }
            assertEquals(4, segment.state)
        }
    }

    @Test fun forwardedRecursiveCellStillResumesItsCapturedChild() {
        executionContext().use { context ->
            context.initialize("thc")
            context.enter()
            try {
                val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                val effects = AtomicInteger()
                val child = Thunk(ThunkYieldProofRoot.target(language, effects, AtomicInteger(),
                    ThunkYieldProofRoot.Gate(), Any()), null)
                val cell = RecCell().also { it.value = child; it.initialized = true }
                val metrics = Metrics(true)
                val target = BytecodeRootGen.create(language, BytecodeConfig.DEFAULT) { b ->
                    b.beginRoot()
                    val local = b.createLocal("recursive binding", "object")
                    val result = b.createLocal("forced result", "object")
                    val suspended = b.createLocal("suspended child", "object")
                    b.beginBlock()
                    b.beginStoreLocal(local); b.emitLoadConstant(cell); b.endStoreLocal()
                    b.beginReturn()
                    b.beginBlock()
                    b.beginTryCatch()
                    b.beginStoreLocal(result)
                    b.beginForceLocal(metrics, local, true); b.emitLoadLocal(local); b.endForceLocal()
                    b.endStoreLocal()
                    b.beginBlock()
                    b.beginStoreLocal(suspended)
                    b.beginSuspensionOnly(); b.emitLoadException(); b.endSuspensionOnly()
                    b.endStoreLocal()
                    b.beginStoreLocal(result)
                    b.beginResumeForcedLocal(local, true)
                    b.emitLoadLocal(suspended)
                    b.beginYield(); b.emitLoadLocal(suspended); b.endYield()
                    b.endResumeForcedLocal()
                    b.endStoreLocal()
                    b.endBlock()
                    b.endTryCatch()
                    b.emitLoadLocal(result)
                    b.endBlock()
                    b.endReturn()
                    b.endBlock()
                    b.endRoot()
                }.getNode(0).callTarget
                val caller = Thunk(target, null)
                val driver = Driver()
                assertSame(caller, assertThrows(ThunkSuspended::class.java) { driver.force(caller) }.thunk)
                assertSame(child, cell.value)
                assertThrows(ThunkSuspended::class.java) { driver.force(child) }
                val answer = driver.force(child)
                updateForcedCell(cell, child, answer) // A second force publishes through the shared RecCell.
                assertSame(answer, cell.value)
                assertSame(answer, driver.force(caller))
                assertEquals(2, caller.state)
                assertEquals(1, effects.get(), "The child's pre-yield effect must not replay")
            } finally { context.leave() }
        }
    }

    @Test fun nativeCoreThunkResumesThroughForcedLocal() {
        assertEquals(listOf("108", "208", "42", "77", "43", "114", "114", "79"),
            File(root, "build/core-continuation/native-output.txt").readLines())
        @Suppress("UNCHECKED_CAST")
        val module = Json.parse(File(root, "build/core-continuation/core/CoreContinuationAudit.json").readText()) as Map<String, Any?>
        executionContext().use { context ->
            context.initialize("thc")
            context.enter()
            try {
                val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                val checkpoint = BytecodeCheckpoint()
                val program = BytecodeProgram(language, CoreModules.reachable(module, "sharedAnswer"), checkpoint)
                val thunk = program.entryValue("sharedAnswer") as Thunk
                val target = thunk.target!!
                val child = program.entryValue("checkpointValue") as Thunk
                val childTarget = child.target!!
                assertTrue(Calls.target(childTarget, arrayOf(0L)) is DataValue)
                compile(childTarget)
                assertTrue(Calls.target(childTarget, arrayOf(0L)) is DataValue)
                assertTrue(checkpoint.compiledVisits.get() > 0, "Ordinary checkpoint path runs installed code")
                SynchronousMasking.set(target.rootNode, MaskingState.MASKED_INTERRUPTIBLE)
                try {
                    assertTrue(Calls.target(target, arrayOf(0L)) is DataValue)
                } finally { SynchronousMasking.set(target.rootNode, MaskingState.UNMASKED) }
                compile(target)
                checkpoint.armed = true
                val host = program.hostEntryTarget(0)
                fun force(): Any? = Calls.target(host, arrayOf(thunk))
                val compiledBefore = (program.diagnostics().getValue("compiledEntries") as Number).toLong()
                val suspended = assertThrows(ThunkSuspended::class.java) { force() }
                assertSame(thunk, suspended.thunk)
                assertEquals(5, thunk.state)
                val saved = thunk.value as ContinuationResult
                assertTrue(saved.frame.frameDescriptor.numberOfSlots.let { count ->
                    (0 until count).any { saved.frame.isLong(it) }
                }, "The captured Core caller retains a primitive Long local")
                val root = target.rootNode as BytecodeRootNode
                assertTrue(root.bytecodeNode.locals.any { it.typeProfile == FrameSlotKind.Long },
                    "The ordinary Core root profiles an unboxed Long local")
                assertEquals(1, checkpoint.visits.get())
                assertTrue((program.diagnostics().getValue("compiledEntries") as Number).toLong() > compiledBefore,
                    "The suspending Core caller entered its explicitly compiled target")
                val answer = force() as DataValue
                assertEquals(108L, answer.layout.readLong(answer, 0))
                assertEquals(1, checkpoint.visits.get(), "Resume must not re-enter the checkpoint")
                assertEquals(2, thunk.state)
            } finally { context.leave() }
        }
    }

    @Test fun genuineCatchActionResumesOwnedTupleAcrossThreads() {
        assertEquals(listOf("108", "208", "42", "77", "43", "114", "114", "79"),
            File(root, "build/core-continuation/native-output.txt").readLines())
        @Suppress("UNCHECKED_CAST")
        val module = Json.parse(File(root, "build/core-continuation/core/CoreContinuationAudit.json").readText()) as Map<String, Any?>
        executionContext().use { context ->
            context.initialize("thc")
            val language = entered(context) { TruffleLanguage.LanguageReference.create(Language::class.java).get(null) }
            val driver = entered(context) { Driver() }
            fun number(value: Any?): Long = (value as DataValue).layout.readLong(value, 0)
            for ((name, expected, visits) in listOf(
                Triple("catchActionAnswer", 42L, 2), Triple("catchActionFailure", 77L, 1))) {
                val linked = CoreModules.reachable(module, name)
                entered(context) {
                    for (program in listOf(Program(language, linked), BytecodeProgram(language, linked)))
                        assertEquals(expected, number(driver.force(program.entryValue(name) as Thunk)), "$name ordinary")
                }
                val checkpoint = BytecodeCheckpoint()
                val program = entered(context) { BytecodeProgram(language, linked, checkpoint) }
                val thunk = entered(context) { program.entryValue(name) as Thunk }
                val target = thunk.target!!
                entered(context) {
                    assertEquals(expected, number(Calls.target(target, arrayOf(0L))))
                    compile(target)
                    assertEquals(expected, number(Calls.target(target, arrayOf(0L))))
                    assertTrue(checkpoint.compiledVisits.get() > 0,
                        "The ordinary catch action entered its installed bytecode root")
                    checkpoint.armed = true
                    val compiledBefore = (program.diagnostics().getValue("compiledEntries") as Number).toLong()
                    SynchronousMasking.set(driver, MaskingState.MASKED_INTERRUPTIBLE)
                    try {
                        assertSame(thunk, assertThrows(ThunkSuspended::class.java) { driver.force(thunk) }.thunk)
                        assertEquals(MaskingState.MASKED_INTERRUPTIBLE, SynchronousMasking.current(driver),
                            "The initial carrier keeps its ambient mask after the action yields")
                    } finally { SynchronousMasking.set(driver, MaskingState.UNMASKED) }
                    assertTrue((program.diagnostics().getValue("compiledEntries") as Number).toLong() > compiledBefore,
                        "The suspending catch action entered installed guest code")
                    assertEquals(0, language.handoffState.get().results.depth, "No pooled result escapes first yield")
                }
                val continuation = thunk.value as ContinuationResult
                val segment = (continuation.result as CallSegmentSuspended).segment
                val tuple = requireNotNull(segment.tupleShape)
                assertEquals(listOf(CoreKind.VOID, CoreKind.DATA), tuple.proof.components!!.map { it.kind },
                    "GHC catch# retains its recursive State# and lifted Box tuple proof")
                assertEquals(1, tuple.width, "The erased State# has no physical carrier slot")
                val observer = entered(context) { Thunk(callSegmentCaller(language, segment), null) }
                entered(context) {
                    assertSame(observer, assertThrows(ThunkSuspended::class.java) { driver.force(observer) }.thunk)
                }
                Executors.newSingleThreadExecutor().use { pool ->
                    val result = pool.submit<Unit> { entered(context) {
                        SynchronousMasking.set(driver, MaskingState.MASKED_UNINTERRUPTIBLE)
                        try {
                            repeat(visits - 1) {
                                assertSame(observer, assertThrows(ThunkSuspended::class.java) { driver.force(observer) }.thunk)
                                assertEquals(0, language.handoffState.get().results.depth, "No pooled result escapes repeated yield")
                            }
                            if (name == "catchActionFailure")
                                assertThrows(GuestException::class.java) { driver.force(observer) }
                            else assertTrue(driver.force(observer) is HandoffStorage)
                            assertEquals(MaskingState.MASKED_UNINTERRUPTIBLE, SynchronousMasking.current(driver),
                                "The resumer keeps its ambient mask after success or guest failure")
                            assertEquals(0, language.handoffState.get().results.depth, "Result slab released on resume")
                        } finally { SynchronousMasking.set(driver, MaskingState.UNMASKED) }
                    } }
                    result.get(5, TimeUnit.SECONDS)
                }
                entered(context) {
                    if (name == "catchActionAnswer") {
                        assertTrue(segment.value is HandoffStorage, "Published tuple must own its fields, not a thread-local completion token")
                        assertNotSame(TupleComplete, segment.value)
                    }
                    SynchronousMasking.set(driver, MaskingState.MASKED_INTERRUPTIBLE)
                    try {
                        assertEquals(expected, number(driver.force(thunk)), "$name parent on another carrier")
                        assertEquals(MaskingState.MASKED_INTERRUPTIBLE, SynchronousMasking.current(driver),
                            "The catch caller restores its original carrier mask")
                    } finally { SynchronousMasking.set(driver, MaskingState.UNMASKED) }
                    assertEquals(0, language.handoffState.get().results.depth)
                    assertEquals(0, language.handoffState.get().results.retainedReferences())
                }
                assertEquals(visits, checkpoint.visits.get(), "The action prefix must not replay")
                assertEquals(if (name == "catchActionFailure") 3 else 2, segment.state)
                assertEquals(2, thunk.state)
            }
        }
    }

    @Test fun genuineCatchHandlerResumesItsOriginalTupleAndLogicalMask() {
        assertEquals("79", File(root, "build/core-continuation/native-output.txt").readLines()[7])
        @Suppress("UNCHECKED_CAST")
        val module = Json.parse(File(root, "build/core-continuation/core/CoreContinuationAudit.json").readText()) as Map<String, Any?>
        executionContext().use { context ->
            context.initialize("thc")
            val language = entered(context) { TruffleLanguage.LanguageReference.create(Language::class.java).get(null) }
            val driver = entered(context) { Driver() }
            val linked = CoreModules.reachable(module, "catchHandlerAnswer", strictLink = true)
            fun number(value: Any?): Long = (value as DataValue).layout.readLong(value, 0)
            entered(context) {
                for (program in listOf(Program(language, linked), BytecodeProgram(language, linked)))
                    assertEquals(79L, number(driver.force(program.entryValue("catchHandlerAnswer") as Thunk)))
            }
            val checkpoint = BytecodeCheckpoint()
            val program = entered(context) { BytecodeProgram(language, linked, checkpoint) }
            entered(context) {
                val target = program.entryTarget("catchHandlerAnswer")
                assertEquals(79L, number(Calls.target(target, arrayOf(0L))))
                compile(target)
                assertEquals(79L, number(Calls.target(target, arrayOf(0L))))
                assertTrue(checkpoint.compiledVisits.get() > 0)
            }
            checkpoint.armed = true
            val parent = entered(context) { program.entryValue("catchHandlerAnswer") as Thunk }
            val compiledBefore = (program.diagnostics().getValue("compiledEntries") as Number).toLong()
            entered(context) {
                assertSame(parent, assertThrows(ThunkSuspended::class.java) { driver.force(parent) }.thunk)
                assertEquals(1, checkpoint.visits.get(), "The action prefix ran exactly once")
                assertEquals(0, language.handoffState.get().results.depth)
                assertSame(parent, assertThrows(ThunkSuspended::class.java) { driver.force(parent) }.thunk)
                assertEquals(2, checkpoint.visits.get(), "The original handler reached its first checkpoint")
                assertEquals(MaskingState.UNMASKED, SynchronousMasking.current(driver),
                    "A parked handler restores its carrier ambient mask")
                assertEquals(0, language.handoffState.get().results.depth)
            }
            val continuation = parent.value as ContinuationResult
            val handler = (continuation.result as CallSegmentSuspended).segment
            assertFalse(handler.caughtIOAction, "The handler result is not the interrupted action")
            assertEquals(listOf(CoreKind.VOID, CoreKind.DATA),
                requireNotNull(handler.tupleShape).proof.components!!.map { it.kind })
            assertTrue(((handler.value as ContinuationResult).continuationRootNode.sourceRootNode) is BytecodeRoot,
                "The saved continuation belongs to the original GHC handler root")
            assertTrue((program.diagnostics().getValue("compiledEntries") as Number).toLong() > compiledBefore,
                "The suspended catch caller entered installed guest bytecode")
            assertEquals(MaskingState.MASKED_INTERRUPTIBLE, handler.logicalMask)
            assertEquals(5, handler.state)
            Executors.newSingleThreadExecutor().use { pool ->
                val resumed = pool.submit<Long> { entered(context) {
                    SynchronousMasking.set(driver, MaskingState.MASKED_UNINTERRUPTIBLE)
                    try {
                        assertSame(parent, assertThrows(ThunkSuspended::class.java) { driver.force(parent) }.thunk)
                        assertEquals(3, checkpoint.visits.get(), "The handler reached its second checkpoint")
                        assertEquals(5, handler.state)
                        assertEquals(MaskingState.MASKED_UNINTERRUPTIBLE, SynchronousMasking.current(driver))
                        assertEquals(0, language.handoffState.get().results.depth)
                        val result = number(driver.force(parent))
                        assertEquals(MaskingState.MASKED_UNINTERRUPTIBLE, SynchronousMasking.current(driver),
                            "Completion restores the second carrier's ambient mask")
                        assertEquals(0, language.handoffState.get().results.depth)
                        assertEquals(0, language.handoffState.get().results.retainedReferences())
                        result
                    } finally { SynchronousMasking.set(driver, MaskingState.UNMASKED) }
                } }
                assertEquals(79L, resumed.get(5, TimeUnit.SECONDS))
            }
            assertEquals(2, handler.state)
            assertEquals(2, parent.state)
            assertEquals(3, checkpoint.visits.get(), "Neither the action nor the handler prefix replays")
        }
    }

    @Test fun privateDeliveryCutsOriginalCatchAndLeavesItsActionShared() {
        assertEquals("42", File(root, "build/core-continuation/native-output.txt").readLines()[2])
        @Suppress("UNCHECKED_CAST")
        val module = Json.parse(File(root, "build/core-continuation/core/CoreContinuationAudit.json").readText()) as Map<String, Any?>
        executionContext().use { context ->
            context.initialize("thc")
            val language = entered(context) { TruffleLanguage.LanguageReference.create(Language::class.java).get(null) }
            val driver = entered(context) { Driver() }
            val checkpoint = BytecodeCheckpoint()
            val program = entered(context) { BytecodeProgram(language, linkedWithPayload(module, "catchActionAnswer"), checkpoint) }
            val payload = entered(context) { driver.force(program.entryValue("asyncPayload") as Thunk) as DataValue }
            assertEquals(7L, payload.layout.readLong(payload, 0))
            entered(context) {
                val target = program.entryTarget("catchActionAnswer")
                assertEquals(42L, (Calls.target(target, arrayOf(0L)) as DataValue).let { it.layout.readLong(it, 0) })
                compile(target)
                assertEquals(42L, (Calls.target(target, arrayOf(0L)) as DataValue).let { it.layout.readLong(it, 0) })
            }
            checkpoint.armed = true
            val parent = entered(context) { program.entryValue("catchActionAnswer") as Thunk }
            val compiledBefore = (program.diagnostics().getValue("compiledEntries") as Number).toLong()
            val child = entered(context) {
                assertSame(parent, assertThrows(ThunkSuspended::class.java) { driver.force(parent) }.thunk)
                ((parent.value as ContinuationResult).result as CallSegmentSuspended).segment
            }
            assertTrue((program.diagnostics().getValue("compiledEntries") as Number).toLong() > compiledBefore,
                "The captured original catch caller entered installed bytecode")
            assertTrue(child.caughtIOAction)
            assertEquals(5, child.state)
            Executors.newSingleThreadExecutor().use { pool ->
                val delivered = pool.submit<Long> { entered(context) {
                    SynchronousMasking.set(driver, MaskingState.MASKED_UNINTERRUPTIBLE)
                    try {
                        val answer = driver.deliver(parent, child, payload) as DataValue
                        assertEquals(MaskingState.MASKED_UNINTERRUPTIBLE, SynchronousMasking.current(driver))
                        answer.layout.readLong(answer, 0)
                    } finally { SynchronousMasking.set(driver, MaskingState.UNMASKED) }
                } }
                assertEquals(107L, delivered.get(5, TimeUnit.SECONDS), "The original catch# handler sees the lazy async payload")
            }
            assertEquals(2, parent.state)
            assertEquals(5, child.state, "Delivery must leave the shared action continuation parked")
            entered(context) {
                assertEquals(107L, (driver.force(parent) as DataValue).let { it.layout.readLong(it, 0) })
                val observer = Thunk(callSegmentCaller(language, child), null)
                assertSame(observer, assertThrows(ThunkSuspended::class.java) { driver.force(observer) }.thunk)
                assertSame(observer, assertThrows(ThunkSuspended::class.java) { driver.force(observer) }.thunk)
                assertTrue(driver.force(observer) is HandoffStorage)
                assertEquals(0, language.handoffState.get().results.depth)
                assertEquals(0, language.handoffState.get().results.retainedReferences())
            }
            assertEquals(2, child.state)
            assertEquals(2, checkpoint.visits.get(), "The action's two checkpoints execute once each")
        }
    }

    @Test fun committedCatchCutSurvivesIndependentChildCompletionBeforeHandlerResume() {
        @Suppress("UNCHECKED_CAST")
        val module = Json.parse(File(root, "build/core-continuation/core/CoreContinuationAudit.json").readText()) as Map<String, Any?>
        executionContext().use { context ->
            context.initialize("thc")
            val language = entered(context) { TruffleLanguage.LanguageReference.create(Language::class.java).get(null) }
            val driver = entered(context) { Driver() }
            val checkpoint = BytecodeCheckpoint().also { it.armed = true }
            val program = entered(context) { BytecodeProgram(language, linkedWithPayload(module, "catchActionAnswer"), checkpoint) }
            val payload = entered(context) { driver.force(program.entryValue("asyncPayload") as Thunk) as DataValue }
            val parent = entered(context) { program.entryValue("catchActionAnswer") as Thunk }
            val child = entered(context) {
                assertSame(parent, assertThrows(ThunkSuspended::class.java) { driver.force(parent) }.thunk)
                ((parent.value as ContinuationResult).result as CallSegmentSuspended).segment
            }
            val observer = entered(context) { Thunk(callSegmentCaller(language, child), null) }
            Executors.newSingleThreadExecutor().use { deliveryPool ->
                val delivered = deliveryPool.submit<Long> { entered(context) {
                    val answer = driver.deliver(parent, child, payload) {
                        assertEquals(1, parent.state, "The parent cut is committed before the observer runs")
                        Executors.newSingleThreadExecutor().use { observerPool ->
                            val finished = observerPool.submit<Unit> { entered(context) {
                                repeat(2) {
                                    assertSame(observer, assertThrows(ThunkSuspended::class.java) { driver.force(observer) }.thunk)
                                }
                                assertTrue(driver.force(observer) is HandoffStorage)
                                assertEquals(0, language.handoffState.get().results.depth)
                            } }
                            finished.get(5, TimeUnit.SECONDS)
                        }
                        assertEquals(2, child.state, "The shared child completed before handler continuation")
                    } as DataValue
                    answer.layout.readLong(answer, 0)
                } }
                assertEquals(107L, delivered.get(5, TimeUnit.SECONDS))
            }
            assertEquals(2, parent.state)
            assertEquals(2, child.state)
            assertEquals(2, checkpoint.visits.get(), "The shared action prefix is never replayed")
        }
    }

    @Test fun originalNestedTupleApplicationResumesTypedFieldsAcrossCarriers() {
        assertEquals("114", File(root, "build/core-continuation/native-output.txt").readLines()[5])
        @Suppress("UNCHECKED_CAST")
        val module = Json.parse(File(root, "build/core-continuation/core/CoreContinuationAudit.json").readText()) as Map<String, Any?>
        executionContext().use { context ->
            context.initialize("thc")
            val language = entered(context) { TruffleLanguage.LanguageReference.create(Language::class.java).get(null) }
            val driver = entered(context) { Driver() }
            val linked = CoreModules.reachable(module, "tupleApplicationAnswer", strictLink = true)
            entered(context) {
                for (program in listOf(Program(language, linked), BytecodeProgram(language, linked))) {
                    val answer = driver.force(program.entryValue("tupleApplicationAnswer") as Thunk) as DataValue
                    assertEquals(114L, answer.layout.readLong(answer, 0))
                }
            }
            val checkpoint = BytecodeCheckpoint()
            val program = entered(context) { BytecodeProgram(language, linked, checkpoint) }
            entered(context) {
                val target = program.entryTarget("tupleApplicationAnswer")
                assertEquals(114L, (Calls.target(target, arrayOf(0L)) as DataValue).let { it.layout.readLong(it, 0) })
                compile(target)
                assertEquals(114L, (Calls.target(target, arrayOf(0L)) as DataValue).let { it.layout.readLong(it, 0) })
                compile((program.entryValue("tupleDelayed") as Closure).target)
            }
            val compiledVisitsBefore = checkpoint.compiledVisits.get()
            checkpoint.armed = true
            val parent = entered(context) { program.entryValue("tupleApplicationAnswer") as Thunk }
            entered(context) {
                assertSame(parent, assertThrows(ThunkSuspended::class.java) { driver.force(parent) }.thunk)
                assertEquals(5, parent.state, "The caller's prefix checkpoint is itself resumable")
                assertSame(parent, assertThrows(ThunkSuspended::class.java) { driver.force(parent) }.thunk)
            }
            val continuation = parent.value as ContinuationResult
            val child = (continuation.result as CallSegmentSuspended).segment
            val shape = requireNotNull(child.tupleShape)
            assertEquals(listOf(CoreKind.VOID, CoreKind.LONG, CoreKind.UNKNOWN), shape.components.map { it.kind })
            assertEquals(listOf(CoreKind.LONG, CoreKind.DATA), shape.leaves.map { it.kind })
            assertEquals(2, shape.width, "Both erased State# fields occupy zero physical slots")
            assertTrue(checkpoint.compiledVisits.get() >= compiledVisitsBefore + 2,
                "Both installed caller and exact tuple callee reached their suspension checkpoints")
            Executors.newSingleThreadExecutor().use { pool ->
                val resumed = pool.submit<Long> { entered(context) {
                    SynchronousMasking.set(driver, MaskingState.MASKED_INTERRUPTIBLE)
                    try {
                        assertSame(parent, assertThrows(ThunkSuspended::class.java) { driver.force(parent) }.thunk)
                        assertEquals(5, child.state, "The exact child segment re-yields")
                        val answer = driver.force(parent) as DataValue
                        assertEquals(MaskingState.MASKED_INTERRUPTIBLE, SynchronousMasking.current(driver))
                        assertEquals(0, language.handoffState.get().results.depth)
                        assertEquals(0, language.handoffState.get().results.retainedReferences())
                        answer.layout.readLong(answer, 0)
                    } finally { SynchronousMasking.set(driver, MaskingState.UNMASKED) }
                } }
                assertEquals(114L, resumed.get(5, TimeUnit.SECONDS))
            }
            assertEquals(2, parent.state)
            assertEquals(2, child.state)
            assertEquals(3, checkpoint.visits.get(), "Caller prefix and two child checkpoints execute once each")
        }
    }

    @Test fun nestedTupleResultIsReleasedBeforePostCallGuestFailure() {
        @Suppress("UNCHECKED_CAST")
        val module = Json.parse(File(root, "build/core-continuation/core/CoreContinuationAudit.json").readText()) as Map<String, Any?>
        executionContext().use { context ->
            context.initialize("thc")
            val language = entered(context) { TruffleLanguage.LanguageReference.create(Language::class.java).get(null) }
            val driver = entered(context) { Driver() }
            val linked = CoreModules.reachable(module, "tupleApplicationFailure", strictLink = true)
            val checkpoint = BytecodeCheckpoint().also { it.armed = true }
            val program = entered(context) { BytecodeProgram(language, linked, checkpoint) }
            val parent = entered(context) { program.entryValue("tupleApplicationFailure") as Thunk }
            val child = entered(context) {
                assertSame(parent, assertThrows(ThunkSuspended::class.java) { driver.force(parent) }.thunk)
                ((parent.value as ContinuationResult).result as CallSegmentSuspended).segment
            }
            Executors.newSingleThreadExecutor().use { pool ->
                val failed = pool.submit<Long> { entered(context) {
                    SynchronousMasking.set(driver, MaskingState.MASKED_UNINTERRUPTIBLE)
                    try {
                        assertSame(parent, assertThrows(ThunkSuspended::class.java) { driver.force(parent) }.thunk)
                        val failure = assertThrows(GuestException::class.java) { driver.force(parent) }
                        val payload = driver.force(failure.payload as Thunk) as DataValue
                        assertEquals(MaskingState.MASKED_UNINTERRUPTIBLE, SynchronousMasking.current(driver))
                        assertEquals(0, language.handoffState.get().results.depth)
                        assertEquals(0, language.handoffState.get().results.retainedReferences())
                        payload.layout.readLong(payload, 0)
                    } finally { SynchronousMasking.set(driver, MaskingState.UNMASKED) }
                } }
                assertEquals(9L, failed.get(5, TimeUnit.SECONDS))
            }
            assertEquals(3, parent.state, "The ordinary guest failure is memoized after tuple consumption")
            assertEquals(2, child.state)
            assertEquals(2, checkpoint.visits.get(), "The child's pre-failure work is never replayed")
            entered(context) { assertThrows(GuestException::class.java) { driver.force(parent) } }
        }
    }

    @Test fun originalCompactTupleArgumentKeepsLogicalArityOnResume() {
        assertEquals("114", File(root, "build/core-continuation/native-output.txt").readLines()[6])
        @Suppress("UNCHECKED_CAST")
        val module = Json.parse(File(root, "build/core-continuation/core/CoreContinuationAudit.json").readText()) as Map<String, Any?>
        executionContext().use { context ->
            context.initialize("thc")
            val language = entered(context) { TruffleLanguage.LanguageReference.create(Language::class.java).get(null) }
            val driver = entered(context) { Driver() }
            val linked = CoreModules.reachable(module, "tupleCompactAnswer", strictLink = true)
            entered(context) {
                for (program in listOf(Program(language, linked), BytecodeProgram(language, linked))) {
                    val answer = driver.force(program.entryValue("tupleCompactAnswer") as Thunk) as DataValue
                    assertEquals(114L, answer.layout.readLong(answer, 0))
                }
            }
            val checkpoint = BytecodeCheckpoint().also { it.armed = true }
            val program = entered(context) { BytecodeProgram(language, linked, checkpoint) }
            val parent = entered(context) { program.entryValue("tupleCompactAnswer") as Thunk }
            val child = entered(context) {
                assertSame(parent, assertThrows(ThunkSuspended::class.java) { driver.force(parent) }.thunk)
                ((parent.value as ContinuationResult).result as CallSegmentSuspended).segment
            }
            assertEquals(5, child.state)
            entered(context) {
                val result = driver.force(parent) as DataValue
                assertEquals(114L, result.layout.readLong(result, 0))
                assertEquals(0, language.handoffState.get().results.depth)
                assertEquals(0, language.handoffState.get().results.retainedReferences())
            }
            assertEquals(2, parent.state)
            assertEquals(2, child.state)
            assertEquals(1, checkpoint.visits.get())
        }
    }

    @Test fun originalTupleCalleeGuestFailurePropagatesWithoutPublishingAResult() {
        @Suppress("UNCHECKED_CAST")
        val module = Json.parse(File(root, "build/core-continuation/core/CoreContinuationAudit.json").readText()) as Map<String, Any?>
        executionContext().use { context ->
            context.initialize("thc")
            val language = entered(context) { TruffleLanguage.LanguageReference.create(Language::class.java).get(null) }
            val driver = entered(context) { Driver() }
            val linked = CoreModules.reachable(module, "tupleRaiseAnswer", strictLink = true)
            val checkpoint = BytecodeCheckpoint().also { it.armed = true }
            val program = entered(context) { BytecodeProgram(language, linked, checkpoint) }
            val parent = entered(context) { program.entryValue("tupleRaiseAnswer") as Thunk }
            val child = entered(context) {
                assertSame(parent, assertThrows(ThunkSuspended::class.java) { driver.force(parent) }.thunk)
                ((parent.value as ContinuationResult).result as CallSegmentSuspended).segment
            }
            Executors.newSingleThreadExecutor().use { pool ->
                val failed = pool.submit<Long> { entered(context) {
                    SynchronousMasking.set(driver, MaskingState.MASKED_INTERRUPTIBLE)
                    try {
                        val failure = assertThrows(GuestException::class.java) { driver.force(parent) }
                        val payload = driver.force(failure.payload as Thunk) as DataValue
                        assertEquals(MaskingState.MASKED_INTERRUPTIBLE, SynchronousMasking.current(driver))
                        assertEquals(0, language.handoffState.get().results.depth)
                        assertEquals(0, language.handoffState.get().results.retainedReferences())
                        payload.layout.readLong(payload, 0)
                    } finally { SynchronousMasking.set(driver, MaskingState.UNMASKED) }
                } }
                assertEquals(9L, failed.get(5, TimeUnit.SECONDS))
            }
            assertEquals(3, child.state, "The child memoizes an ordinary guest failure")
            assertEquals(3, parent.state, "The caller propagates the same failure without a tuple update")
            assertEquals(1, checkpoint.visits.get(), "The child prefix is not replayed")
            entered(context) { assertThrows(GuestException::class.java) { driver.force(parent) } }
        }
    }

    @Test fun nestedOriginalCatchCutsNearestHandlerAndKeepsInnerActionResumable() {
        assertEquals("43", File(root, "build/core-continuation/native-output.txt").readLines()[4])
        @Suppress("UNCHECKED_CAST")
        val module = Json.parse(File(root, "build/core-continuation/core/CoreContinuationAudit.json").readText()) as Map<String, Any?>
        executionContext().use { context ->
            context.initialize("thc")
            val language = entered(context) { TruffleLanguage.LanguageReference.create(Language::class.java).get(null) }
            val driver = entered(context) { Driver() }
            entered(context) {
                val linked = CoreModules.reachable(module, "nestedCatchAction")
                for (program in listOf(Program(language, linked), BytecodeProgram(language, linked))) {
                    val answer = driver.force(program.entryValue("nestedCatchAction") as Thunk) as DataValue
                    assertEquals(43L, answer.layout.readLong(answer, 0), "Native and ordinary guest catch agree")
                }
            }
            val checkpoint = BytecodeCheckpoint().also { it.armed = true }
            val program = entered(context) { BytecodeProgram(language, linkedWithPayload(module, "nestedCatchAction"), checkpoint) }
            val payload = entered(context) { driver.force(program.entryValue("asyncPayload") as Thunk) as DataValue }
            val parent = entered(context) { program.entryValue("nestedCatchAction") as Thunk }
            val (outer, inner) = entered(context) {
                SynchronousMasking.set(driver, MaskingState.MASKED_INTERRUPTIBLE)
                try {
                    assertSame(parent, assertThrows(ThunkSuspended::class.java) { driver.force(parent) }.thunk)
                    assertEquals(MaskingState.MASKED_INTERRUPTIBLE, SynchronousMasking.current(driver))
                    val outer = ((parent.value as ContinuationResult).result as CallSegmentSuspended).segment
                    val inner = ((outer.value as ContinuationResult).result as CallSegmentSuspended).segment
                    assertTrue(outer.caughtIOAction && inner.caughtIOAction)
                    assertThrows(RuntimeFault::class.java) { driver.deliver(parent, inner, payload) }
                    assertEquals(5, parent.state, "The outer handler cannot steal the inner action")
                    outer to inner
                } finally { SynchronousMasking.set(driver, MaskingState.UNMASKED) }
            }
            Executors.newSingleThreadExecutor().use { pool ->
                val delivered = pool.submit<Any?> { entered(context) {
                    SynchronousMasking.set(driver, MaskingState.MASKED_UNINTERRUPTIBLE)
                    try {
                        val answer = driver.deliver(outer, inner, payload)
                        assertEquals(MaskingState.MASKED_UNINTERRUPTIBLE, SynchronousMasking.current(driver))
                        answer
                    } finally { SynchronousMasking.set(driver, MaskingState.UNMASKED) }
                } }
                assertTrue(delivered.get(5, TimeUnit.SECONDS) is HandoffStorage)
            }
            assertEquals(2, outer.state)
            assertEquals(5, inner.state)
            entered(context) {
                SynchronousMasking.set(driver, MaskingState.MASKED_INTERRUPTIBLE)
                try {
                    val answer = driver.force(parent) as DataValue
                    assertEquals(78L, answer.layout.readLong(answer, 0),
                        "The inner handler adds 70 and outer action adds 1; outer handler would add 1000")
                    assertEquals(MaskingState.MASKED_INTERRUPTIBLE, SynchronousMasking.current(driver))
                } finally { SynchronousMasking.set(driver, MaskingState.UNMASKED) }
                val observer = Thunk(callSegmentCaller(language, inner), null)
                assertSame(observer, assertThrows(ThunkSuspended::class.java) { driver.force(observer) }.thunk)
                assertTrue(driver.force(observer) is HandoffStorage)
                assertEquals(0, language.handoffState.get().results.depth)
                assertEquals(0, language.handoffState.get().results.retainedReferences())
            }
            assertEquals(2, inner.state)
            assertEquals(2, parent.state)
            assertEquals(1, checkpoint.visits.get(), "The inner action checkpoint executes only once")
        }
    }

    @Test fun ordinaryCatchCannotAcceptPrivateAsyncOrigin() {
        val payload = Any()
        val delivered = CapturedAsyncDelivery(payload)
        assertSame(delivered, assertThrows(CapturedAsyncDelivery::class.java) {
            BytecodeRoot.RequireGuestFailure.payload(delivered)
        })
        assertSame(payload, BytecodeRoot.RequireCaughtIOFailure.payload(delivered))
        @Suppress("UNCHECKED_CAST")
        val module = Json.parse(File(root, "build/core-continuation/core/CoreContinuationAudit.json").readText()) as Map<String, Any?>
        executionContext().use { context ->
            context.initialize("thc")
            entered(context) {
                val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                val linked = CoreModules.reachable(module, "nestedCatchAction")
                val ordinary = BytecodeProgram(language, linked).bytecodeDump()
                val private = BytecodeProgram(language, linked, BytecodeCheckpoint()).bytecodeDump()
                assertTrue(ordinary.contains("RequireGuestFailure"))
                assertFalse(ordinary.contains("RequireCaughtIOFailure"))
                assertTrue(private.contains("RequireCaughtIOFailure"))
            }
        }
    }

    @Test fun asyncMarkerDuringTupleSegmentResumeFailsClosedWithoutReplayingOrLeaking() {
        executionContext().use { context ->
            context.initialize("thc")
            val language = entered(context) { TruffleLanguage.LanguageReference.create(Language::class.java).get(null) }
            val driver = entered(context) { Driver() }
            val effects = AtomicInteger()
            val marker = AsyncThunkUnwind(Any())
            val tuple = entered(context) { TupleShape(CoreRepresentation(CoreKind.UNKNOWN, true, true,
                listOf("BoxedRep (Just Lifted)"), listOf(
                    CoreRepresentation(CoreKind.VOID, true, true, emptyList()),
                    CoreRepresentation(CoreKind.DATA, true, true, listOf("BoxedRep (Just Lifted)"))
                )), language) }
            val (segment, parent) = entered(context) {
                val target = ThunkYieldProofRoot.target(language, effects, AtomicInteger(),
                    ThunkYieldProofRoot.Gate(), marker)
                val segment = CallSegment(Calls.target(target, arrayOf(0L)) as ContinuationResult,
                    tupleShape = tuple)
                segment to Thunk(callSegmentCaller(language, segment), null)
            }
            entered(context) {
                assertSame(parent, assertThrows(ThunkSuspended::class.java) { driver.force(parent) }.thunk)
                assertSame(parent, assertThrows(ThunkSuspended::class.java) { driver.force(parent) }.thunk)
                SynchronousMasking.set(driver, MaskingState.MASKED_INTERRUPTIBLE)
                try {
                    assertSame(marker, assertThrows(AsyncThunkUnwind::class.java) { driver.force(parent) })
                    assertEquals(MaskingState.MASKED_INTERRUPTIBLE, SynchronousMasking.current(driver),
                        "The host carrier retains its ambient mask after unsupported async unwind")
                } finally { SynchronousMasking.set(driver, MaskingState.UNMASKED) }
                assertEquals(0, language.handoffState.get().results.depth)
                assertEquals(0, language.handoffState.get().results.retainedReferences())
                assertThrows(RuntimeFault::class.java) { driver.force(parent) }
            }
            assertEquals(4, segment.state, "Async origin must not become a memoized guest failure")
            assertEquals(5, parent.state, "The parked parent retains its captured frame but cannot replay the closed child")
            assertEquals(1, effects.get(), "An unsupported unwind must never replay the prefix")
        }
    }

    @Test fun malformedResumeAndUncapturedCallStayClosed() {
        @Suppress("UNCHECKED_CAST")
        val module = Json.parse(File(root, "build/core-continuation/core/CoreContinuationAudit.json").readText()) as Map<String, Any?>
        executionContext().use { context ->
            context.initialize("thc")
            context.enter()
            try {
                val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                fun malformed(resume: Any) {
                    val checkpoint = BytecodeCheckpoint().also { it.armed = true }
                    val program = BytecodeProgram(language, CoreModules.reachable(module, "sharedAnswer"), checkpoint)
                    val parent = program.entryValue("sharedAnswer") as Thunk
                    assertThrows(ThunkSuspended::class.java) {
                        Calls.target(program.hostEntryTarget(0), arrayOf(parent))
                    }
                    val saved = parent.value as ContinuationResult
                    assertThrows(IllegalStateException::class.java) { saved.continueWith(resume) }
                }
                malformed(Unit)
                malformed(ChildResume(Any(), null))
                val other = BytecodeProgram(language, CoreModules.reachable(module, "uncaptured"),
                    BytecodeCheckpoint().also { it.armed = true })
                val uncaptured = other.entryValue("uncaptured") as Thunk
                assertThrows(IllegalStateException::class.java) {
                    Calls.target(other.hostEntryTarget(0), arrayOf(uncaptured))
                }
                assertEquals(4, uncaptured.state)
            } finally { context.leave() }
        }
    }

    @Test fun ordinaryCoreHasNoYieldInstruction() {
        @Suppress("UNCHECKED_CAST")
        val module = Json.parse(File(root, "build/core-continuation/core/CoreContinuationAudit.json").readText()) as Map<String, Any?>
        executionContext().use { context ->
            context.initialize("thc")
            context.enter()
            try {
                val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                val linked = CoreModules.reachable(module, "sharedAnswer")
                val ast = Program(language, linked)
                val astAnswer = Calls.target(ast.hostEntryTarget(0), arrayOf(ast.entryValue("sharedAnswer"))) as DataValue
                assertEquals(108L, astAnswer.layout.readLong(astAnswer, 0))
                val ordinary = BytecodeProgram(language, linked)
                assertFalse(ordinary.bytecodeDump().contains("yield"))
                val answer = Calls.target(ordinary.hostEntryTarget(0), arrayOf(ordinary.entryValue("sharedAnswer"))) as DataValue
                assertEquals(108L, answer.layout.readLong(answer, 0))
                val normalCall = BytecodeProgram(language, CoreModules.reachable(module, "applicationAnswer"))
                val normalDump = normalCall.bytecodeDump()
                assertFalse(normalDump.contains("yield"))
                assertFalse(normalDump.contains("CaptureApplicationResult"))
                val callAnswer = Calls.target(normalCall.hostEntryTarget(0),
                    arrayOf(normalCall.entryValue("applicationAnswer"))) as DataValue
                assertEquals(208L, callAnswer.layout.readLong(callAnswer, 0))
                for ((name, expected) in listOf("catchActionAnswer" to 42L, "catchActionFailure" to 77L)) {
                    val action = BytecodeProgram(language, CoreModules.reachable(module, name))
                    val dump = action.bytecodeDump()
                    assertFalse(dump.contains("yield"), "$name ordinary bytecode has no Yield")
                    assertFalse(dump.contains("InvokeIOActionCheckpoint"), "$name ordinary bytecode has no private checkpoint")
                    val caught = Calls.target(action.hostEntryTarget(0), arrayOf(action.entryValue(name))) as DataValue
                    assertEquals(expected, caught.layout.readLong(caught, 0))
                }
            } finally { context.leave() }
        }
    }
}
