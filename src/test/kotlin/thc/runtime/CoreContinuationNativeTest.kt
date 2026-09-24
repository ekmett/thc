// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime

import com.oracle.truffle.api.RootCallTarget
import com.oracle.truffle.api.TruffleLanguage
import com.oracle.truffle.api.bytecode.BytecodeRootNode
import com.oracle.truffle.api.bytecode.ContinuationResult
import com.oracle.truffle.api.frame.FrameSlotKind
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import thc.Language
import thc.CoreModules
import thc.Json
import thc.executionContext
import java.io.File

/** A real GHC Core thunk and local demand, with a private deterministic test checkpoint. */
class CoreContinuationNativeTest {
    private val root = File(System.getProperty("thc.projectRoot"))

    private fun compile(target: RootCallTarget) {
        target.javaClass.getMethod("compile", Boolean::class.javaPrimitiveType).invoke(target, true)
        assertEquals(true, target.javaClass.getMethod("isValidLastTier").invoke(target))
    }

    @Test fun nativeCoreThunkResumesThroughForcedLocal() {
        assertEquals("108", File(root, "build/core-continuation/native-output.txt").readText().trim())
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
            } finally { context.leave() }
        }
    }
}
