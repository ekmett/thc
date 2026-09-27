// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime

import com.oracle.truffle.api.TruffleLanguage
import com.oracle.truffle.api.frame.VirtualFrame
import com.oracle.truffle.api.nodes.RootNode
import org.graalvm.polyglot.Context
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import thc.Language

class SumInputLayoutTest {
    private val integer = CoreRepresentation(CoreKind.LONG, true, true, listOf("IntRep"))
    private val word = integer.copy(primReps = listOf("WordRep"))
    private val reference = CoreRepresentation(CoreKind.OBJECT, false, true, listOf("BoxedRep (Just Lifted)"))
    private val sum = CoreRepresentation(CoreKind.UNKNOWN, true, true,
        listOf("WordRep", "BoxedRep (Just Lifted)", "WordRep"),
        alternatives = listOf(reference, integer), tagSlot = 0, alternativeSlots = listOf(listOf(1), listOf(2)))
    private fun withLanguage(action: (Language) -> Unit) = Context.newBuilder("thc").build().use { context ->
        context.initialize("thc"); context.enter()
        try { action(TruffleLanguage.LanguageReference.create(Language::class.java).get(null)) }
        finally { context.leave() }
    }

    @Test fun resumedScalarSumArmsUpdateTheirOriginalProfilesWithoutReplay() = resumedArmProfiles(false)
    @Test fun resumedTupleSumArmsUpdateTheirOriginalProfilesWithoutReplay() = resumedArmProfiles(true)

    private fun resumedArmProfiles(tuple: Boolean) = withLanguage { language ->
        val layout = FrameLayout()
        val tag = layout.bind("sum tag")
        val destination = layout.bind("narrow tuple result")
        val narrow = CoreRepresentation(CoreKind.LONG, true, true, listOf("Int32Rep"))
        val events = ArrayList<String>()
        val scrutinee = object : Expr() {
            override fun execute(frame: VirtualFrame): Nothing = error("sum scrutinee needs a destination")
            override fun executeTuple(frame: VirtualFrame, slots: IntArray, offset: Int): Any? {
                events += "prefix"
                if (frame.arguments[2] == false) {
                    FrameAccess.writeLong(frame, slots[offset], frame.arguments[1] as Long)
                    return null
                }
                throw AstCapture(Unit, MaskingState.UNMASKED).append(object : AstResumeStep {
                    override fun resume(frame: VirtualFrame, input: Any?): Any? {
                        assertSame(Unit, input)
                        events += "suffix"
                        FrameAccess.writeLong(frame, slots[offset], frame.arguments[1] as Long)
                        return null
                    }
                })
            }
        }
        val arms = Array<Expr>(2) { index -> object : Expr() {
            override fun execute(frame: VirtualFrame): Nothing = error("sum branch lost its narrow result route")
            override fun executeInt(frame: VirtualFrame): Int {
                events += "arm$index"
                return if (index == 0) Int.MIN_VALUE else Int.MAX_VALUE
            }
            override fun executeTuple(frame: VirtualFrame, slots: IntArray, offset: Int): Any? {
                FrameAccess.writeInt(frame, slots[offset], executeInt(frame))
                return null
            }
        } }
        val choice = SumCase(scrutinee, intArrayOf(tag), arms, intArrayOf(0, 1, 0, -1),
            if (tuple) CoreRepresentation(CoreKind.UNKNOWN, true, true, narrow.primReps, listOf(narrow)) else narrow)
        val root = object : GuestRoot(language, layout.build()) {
            @field:Child private var branch = choice
            override fun bloom(frame: VirtualFrame): Long = 0L
            override fun execute(frame: VirtualFrame): Any? = try {
                if (tuple) {
                    branch.executeTuple(frame, intArrayOf(destination), 0)
                    frame.getInt(destination)
                } else branch.executeInt(frame)
            } catch (cut: AstCapture) {
                if (tuple) cut.append(object : AstResumeStep {
                    override fun resume(frame: VirtualFrame, input: Any?): Any {
                        assertNull(input)
                        return frame.getInt(destination)
                    }
                })
                cut.freeze(this, frame.materialize())
            }
        }
        // Inspect the existing pinned profiles, not a second test-only seen-state.
        val profiles = SumCase::class.java.getDeclaredField("armProfiles").also { it.isAccessible = true }
            .get(choice) as Array<*>
        fun counts(): List<Pair<Int, Int>> = profiles.map { profile ->
            fun count(name: String) = profile!!.javaClass.getDeclaredMethod(name).also { it.isAccessible = true }
                .invoke(profile) as Int
            count("getTrueCount") to count("getFalseCount")
        }
        val expectedEvents = ArrayList<String>()
        val observations = listOf(listOf(1 to 0, 0 to 0), listOf(1 to 1, 1 to 0), listOf(2 to 1, 1 to 0))
        for (selected in 1L..3L) {
            val before = counts()
            val saved = Calls.target(root.callTarget, arrayOf<Any?>(0L, selected, true)) as AstContinuation
            assertEquals(before, counts(), "No arm is observed before the scrutinee resumes")
            val expected = if (selected == 2L) Int.MAX_VALUE else Int.MIN_VALUE
            assertEquals(expected, saved.continueWith(Unit))
            assertEquals(observations[(selected - 1).toInt()], counts())
            assertThrows(RuntimeFault::class.java) { saved.continueWith(Unit) }
            expectedEvents += listOf("prefix", "suffix", "arm${if (selected == 2L) 1 else 0}")
            assertEquals(expectedEvents, events)
        }
        assertEquals(Int.MAX_VALUE, Calls.target(root.callTarget, arrayOf<Any?>(0L, 2L, false)))
        assertEquals(listOf(2 to 2, 2 to 0), counts(), "Ordinary and resumed arms share the same profiles")
        expectedEvents += listOf("prefix", "arm1")
        val missing = Calls.target(root.callTarget, arrayOf<Any?>(0L, 4L, true)) as AstContinuation
        assertEquals("Non-exhaustive unboxed sum case", assertThrows(RuntimeFault::class.java) {
            missing.continueWith(Unit)
        }.message)
        assertEquals(listOf(2 to 3, 2 to 1), counts())
        expectedEvents += listOf("prefix", "suffix")
        assertEquals(expectedEvents, events)
        assertEquals(MaskingState.UNMASKED, SynchronousMasking.current(root))
        assertEquals(0, language.handoffState.get().results.depth)
    }

    @Test fun logicalSumIdentitySurvivesEqualPhysicalInputAndPrefixLayouts() = withLanguage { language ->
        val formal = ArgumentLayout.fromProofs(listOf(integer, sum, integer))!!
        assertEquals(3, formal.logicalArity); assertEquals(5, formal.physicalArity)
        assertEquals(listOf(0, 1, 4, 5), (0..3).map(formal::offset))
        assertEquals(listOf(CoreKind.LONG, CoreKind.LONG, CoreKind.OBJECT, CoreKind.LONG, CoreKind.LONG),
            formal.physicalProofs.map { it.kind })
        val changed = ArgumentLayout.fromProofs(listOf(integer, sum.copy(alternatives = listOf(reference, word)), integer))!!
        val input = TypedInputLayout.create(language, formal, false)!!
        val other = TypedInputLayout.create(language, changed, false)!!
        assertSame(input.packet, other.packet)
        assertThrows(RuntimeFault::class.java) { ArgumentLayout.validate(formal, 0, changed, 0, 3) }
        assertThrows(RuntimeFault::class.java) { ArgumentLayout.validate(formal, 1, null, 0, 1) }
        CoreRepresentations.requireInput(sum)
        CoreRepresentations.requireJoinArgument(sum, sum)
        assertThrows(RuntimeFault::class.java) { CoreRepresentations.requireJoinArgument(sum, sum.copy(alternatives = listOf(reference, word))) }
        assertThrows(RuntimeFault::class.java) { CoreRepresentations.requireJoinArgument(sum, integer) }
        assertThrows(RuntimeFault::class.java) { CoreRepresentations.requireJoinArgument(integer, sum) }
        ArgumentLayout.validate(formal, 1, formal.suffix(1), 0, 2)
        assertEquals(4, input.prefix(2).reps.size)
        assertThrows(RuntimeFault::class.java) { CoreRepresentations.requireInput(sum.copy(tagSlot = 1)) }
    }

    @Test fun durablePrefixesAndOwnedCapturesDoNotBorrowInputsOrRetainNullRoots() = withLanguage { language ->
        val logical = ArgumentLayout.fromProofs(listOf(sum, integer))!!
        val input = TypedInputLayout.create(language, logical, false)!!
        val prefix = input.prefix(1)
        val retained = prefix.create()
        val marker = Any()
        prefix.setLong(retained, 0, 1); prefix.setObject(retained, 1, marker); prefix.setLong(retained, 2, 0)
        val loan = input.state().arguments.acquire(input.packet)
        loan.inputMode = 1
        input.packet.setObject(loan, input.header + 1, Any())
        input.release(loan)
        assertSame(marker, prefix.getObject(retained, 1)); assertFalse(retained.live)
        assertEquals(0, retained.inputMode); assertEquals(0, input.state().arguments.retainedReferences())

        val target = object : RootNode(language) { override fun execute(frame: VirtualFrame): Any = Unit }.callTarget
        val captureLayout = CaptureLayout(language, booleanArrayOf(true, false, true),
            booleanArrayOf(true, false, true))
        val environment = captureLayout.captureValues(arrayOf(2L, null, 37L))
        val closure = Closure(environment, arity = 1, target = target)
        val image = ClosureInspection.image(closure)
        assertEquals(32, image.bytes.size); assertTrue(image.pointers.isEmpty())
        assertNull(environment.getObject(1)); assertEquals(37L, environment.getLong(2))
        val liveEnvironment = captureLayout.captureValues(arrayOf(1L, marker, 0L))
        assertArrayEquals(arrayOf(marker), ClosureInspection.image(Closure(liveEnvironment, arity = 1, target = target)).pointers)
        val emptyPrefix = prefix.create()
        prefix.setLong(emptyPrefix, 0, 2); prefix.setObject(emptyPrefix, 1, null); prefix.setLong(emptyPrefix, 2, 41)
        val pap = Closure(null, arity = 1, target = target, typedSupplied = emptyPrefix, suppliedCount = 1)
        assertTrue(ClosureInspection.image(pap).pointers.isEmpty())
    }
}
