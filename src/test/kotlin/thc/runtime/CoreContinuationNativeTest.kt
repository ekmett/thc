// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime

import com.oracle.truffle.api.RootCallTarget
import com.oracle.truffle.api.TruffleLanguage
import com.oracle.truffle.api.bytecode.BytecodeRootNode
import com.oracle.truffle.api.bytecode.BytecodeConfig
import com.oracle.truffle.api.bytecode.ContinuationResult
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
    }

    private fun <T> entered(context: Context, action: () -> T): T {
        context.enter()
        try { return action() } finally { context.leave() }
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

    @Test fun applicationRejectsUnownedNestedRootAndActiveMask() {
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
                SynchronousMasking.set(masked.target!!.rootNode, MaskingState.MASKED_INTERRUPTIBLE)
                try {
                    val unsupported = assertThrows(IllegalStateException::class.java) { driver.force(masked) }
                    assertTrue(unsupported.message!!.contains("Masked application continuation"))
                    assertEquals(4, masked.state, "Masked suspension remains fail-closed")
                } finally { SynchronousMasking.set(masked.target!!.rootNode, MaskingState.UNMASKED) }

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
                    BytecodeRoot.CaptureApplicationResult.capture(0, function, continuation, Driver())
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
        assertEquals(listOf("108", "208"), File(root, "build/core-continuation/native-output.txt").readLines())
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
            } finally { context.leave() }
        }
    }
}
