// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
@file:Suppress("UNCHECKED_CAST")
package thc.runtime

import com.oracle.truffle.api.TruffleLanguage
import com.oracle.truffle.api.frame.VirtualFrame
import org.graalvm.polyglot.Context
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import thc.CoreModules
import thc.EntryValue
import thc.Json
import thc.Language
import java.io.File

/** Positive layouts come from genuine GHC exports; only negative controls corrupt them. */
class FourWayAggregateProtocolTest {
    private val root = File(System.getProperty("thc.projectRoot"))
    private fun modules(stage: String): List<Map<String, Any?>> {
        verifyFourWayEvidence(root)
        return File(root, "build/fourway-aggregate/$stage/core").listFiles()!!
            .filter { it.extension == "json" }.map { Json.parse(it.readText()) as Map<String, Any?> }
    }
    private fun originalProof(): CoreRepresentation {
        verifyFourWayEvidence(root)
        val module = Json.parse(File(root, "build/fourway-aggregate/pre/core/FourWayAggregateFields.json").readText()) as Map<String, Any?>
        val original = (module["constructors"] as List<Map<String, Any?>>).single { it["name"] == "VirtualRegWithFormat" }
        return CoreFields(original).logicalProofs[0]
    }
    private fun entered(action: (Context, Language) -> Unit) = Context.newBuilder("thc").allowExperimentalOptions(true)
        .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false")
        .option("engine.CompilationFailureAction", "Throw").build().use { context ->
            context.initialize("thc"); context.enter()
            try { action(context, TruffleLanguage.LanguageReference.create(Language::class.java).get(null)) }
            finally { context.leave() }
        }
    private fun released(language: Language) {
        val handoff = language.handoffState.get()
        assertEquals(0, handoff.results.depth); assertEquals(0, handoff.arguments.depth)
        assertEquals(0, handoff.results.retainedReferences()); assertEquals(0, handoff.arguments.retainedReferences())
    }

    @Test fun malformedPhysicalTagsCannotSelectTheOriginalDefaultArm() = entered { context, language ->
        for (stage in listOf("pre", "post")) for (backend in listOf("ast", "bytecode")) {
            val linked = CoreModules.reachable(CoreModules.merge(modules(stage)), "main:FourWayAggregateFields.defaultArm", true)
            val program: ExecutableProgram = if (backend == "ast") Program(language, linked) else BytecodeProgram(language, linked)
            val layout = program.constructorLayout("ghc-9.14.1-inplace:GHC.CmmToAsm.Format.VirtualRegWithFormat")
            val format = program.constructorLayout("ghc-9.14.1-inplace:GHC.CmmToAsm.Format.II64").allocate()
            val name = "main:FourWayAggregateFields.consumeDefault"
            val target = program.entryTarget(name)
            fun call(tag: Long): Any? = Calls.target(target, arrayOf(0L, layout.create(arrayOf(tag, Long.MIN_VALUE, format))))
            for (tag in 1L..4L) assertEquals(if (tag == 1L) Long.MIN_VALUE else -1L, call(tag))
            assertTrue(context.asValue(EntryValue(program, name, 1)).invokeMember("compile").asBoolean())
            val before = program.diagnostics()["compiledEntries"] as Long
            assertEquals(-1L, call(4))
            assertTrue((program.diagnostics()["compiledEntries"] as Long) > before)
            for (tag in listOf(Long.MIN_VALUE, -1L, 0L, 5L, 0x100000001L, Long.MAX_VALUE)) {
                val failure = assertThrows(RuntimeFault::class.java, { call(tag) }, "$stage/$backend/tag=$tag")
                assertTrue(failure.message.orEmpty().contains("Invalid unboxed sum tag"), failure.message)
                released(language)
            }
        }
    }

    @Test fun genuineFourWayFamilyKeepsExactArityProjectionAndPayloadBoundaries() {
        val proof = originalProof()
        val alternatives = proof.alternatives!!
        for (arity in listOf(2, 3, 5)) for (tag in 1..4)
            assertThrows(RuntimeFault::class.java) {
                SumShape.constructor(proof, mapOf("kind" to "unboxed-sum", "arity" to 1, "sumArity" to arity, "tag" to tag), 1)
            }
        for (tag in listOf(Long.MIN_VALUE, 0L, 5L, 0x100000001L, Long.MAX_VALUE))
            assertThrows(RuntimeFault::class.java) {
                SumShape.constructor(proof, mapOf("kind" to "unboxed-sum", "arity" to 1, "sumArity" to 4, "tag" to tag), 1)
            }
        assertThrows(RuntimeFault::class.java) { SumShape.validate(proof.copy(alternativeSlots = listOf(listOf(0), listOf(1), listOf(1), listOf(1)))) }
        assertThrows(RuntimeFault::class.java) { SumShape.validate(proof.copy(primReps = listOf("WordRep", "WordRep"))) }
        assertThrows(RuntimeFault::class.java) { SumShape.payload(alternatives[0], CoreRepresentation(CoreKind.FLOAT, true, true, listOf("FloatRep"))) }
        assertThrows(RuntimeFault::class.java) { SumShape.payload(alternatives[0], alternatives[0], true) }
        // A nested payload cannot reuse the original family's physical proof unchanged.
        val forgedNested = assertThrows(RuntimeFault::class.java) {
            SumShape.validate(proof.copy(alternatives = listOf(proof) + alternatives.drop(1)))
        }
        assertTrue(forgedNested.message.orEmpty().contains("Sum physical representation or tag slot mismatch"), forgedNested.message)
    }

    private class ResumeProbe(language: Language, proof: CoreRepresentation, private val mapping: IntArray,
                              private val layout: FrameLayout = FrameLayout()) : GuestRoot(language, layout.also {
        it.bind("tag"); it.bind("payload")
    }.build()) {
        var scrutineeVisits = 0
        var branchVisits = 0
        private val slots = intArrayOf(layout.slot("tag"), layout.slot("payload"))
        @Child private var body: Expr = SumCase(object : Expr() {
            override fun execute(frame: VirtualFrame): Nothing = error("Typed scrutinee expected")
            override fun executeTuple(frame: VirtualFrame, slots: IntArray, offset: Int): Any? {
                scrutineeVisits++
                val tag = frame.arguments[0] as Long
                throw AstCapture(Unit, SynchronousMasking.current(this)).append(object : AstResumeStep {
                    override fun resume(frame: VirtualFrame, input: Any?): Any? {
                        assertSame(Unit, input)
                        FrameAccess.writeLong(frame, slots[offset], tag)
                        FrameAccess.writeLong(frame, slots[offset + 1], Long.MIN_VALUE)
                        return null
                    }
                })
            }
        }, slots, Array(2) { index -> object : Expr() {
            override fun execute(frame: VirtualFrame): Any = executeLong(frame)
            override fun executeLong(frame: VirtualFrame): Long {
                branchVisits++
                val answer = if (index == 0) 41L else 77L
                if (frame.arguments[1] == true)
                    throw AstCapture(Unit, SynchronousMasking.current(this)).append(object : AstResumeStep {
                        override fun resume(frame: VirtualFrame, input: Any?): Long { assertSame(Unit, input); return answer }
                    })
                return answer
            }
        } }, mapping, proof.alternatives!![0])
        override fun execute(frame: VirtualFrame): Any = try { body.executeLong(frame) }
        catch (cut: AstCapture) { cut.freeze(this, frame.materialize()) }
        override fun bloom(frame: VirtualFrame) = 0L
    }

    @Test fun resumedThirdAndFourthTagsRetainDefaultRouteAndDoNotReplayAcrossASecondCut() = entered { _, language ->
        val proof = originalProof()
        for (tag in listOf(3L, 4L)) for (branchCut in listOf(false, true)) {
            val root = ResumeProbe(language, proof, intArrayOf(0, 1, 1, 1))
            val cut = root.callTarget.call(tag, branchCut) as AstContinuation
            val result = cut.continueWith(Unit)
            if (branchCut) assertEquals(77L, (result as AstContinuation).continueWith(Unit)) else assertEquals(77L, result)
            assertEquals(1, root.scrutineeVisits); assertEquals(1, root.branchVisits)
            assertThrows(RuntimeFault::class.java) { cut.continueWith(Unit) }
            released(language)
        }
        for (tag in listOf(0L, 5L, 0x100000001L, Long.MIN_VALUE)) {
            val root = ResumeProbe(language, proof, intArrayOf(0, 1, 1, 1))
            val cut = root.callTarget.call(tag, false) as AstContinuation
            assertThrows(RuntimeFault::class.java) { cut.continueWith(Unit) }
            assertEquals(1, root.scrutineeVisits); assertEquals(0, root.branchVisits)
        }
        for (tag in listOf(3L, 4L)) {
            val root = ResumeProbe(language, proof, intArrayOf(0, -1, -1, -1))
            val cut = root.callTarget.call(tag, false) as AstContinuation
            val failure = assertThrows(RuntimeFault::class.java) { cut.continueWith(Unit) }
            assertTrue(failure.message.orEmpty().contains("Non-exhaustive"), failure.message)
            assertEquals(1, root.scrutineeVisits); assertEquals(0, root.branchVisits)
        }
    }
}
