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
import thc.Language
import thc.CoreModules
import thc.Json
import thc.executionContext
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
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
                val suspendedCall = (caller.result as ThunkSuspended).thunk
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
