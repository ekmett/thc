// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime

import com.oracle.truffle.api.Truffle
import com.oracle.truffle.api.TruffleLanguage
import com.oracle.truffle.api.frame.FrameDescriptor
import com.oracle.truffle.api.frame.FrameSlotKind
import com.oracle.truffle.api.frame.VirtualFrame
import org.graalvm.polyglot.Context
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import thc.Language

class TupleJoinLoweringTest {
    private val long = CoreRepresentation(CoreKind.LONG, true, true, listOf("IntRep"))
    private val reference = CoreRepresentation(CoreKind.OBJECT, false, true, listOf("BoxedRep (Just Lifted)"))
    private fun tuple(vararg components: CoreRepresentation) = CoreRepresentation(CoreKind.UNKNOWN, true, true,
        components.flatMap { it.primReps!! }, components.toList())

    @Test fun tupleOperandsMoveInParallelAndReleaseScratchReferencesWithoutForcing() {
        Context.newBuilder("thc").build().use { context ->
            context.initialize("thc"); context.enter()
            try {
                val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                val descriptor = FrameDescriptor.newBuilder().also { builder ->
                    repeat(8) { builder.addSlot(FrameSlotKind.Illegal, "field $it", null) }
                }.build()
                val frame = Truffle.getRuntime().createVirtualFrame(emptyArray(), descriptor)
                val left = Any(); val right = Any(); val events = mutableListOf<Int>()
                FrameAccess.writeLong(frame, 0, Long.MIN_VALUE); FrameAccess.write(frame, 1, left)
                FrameAccess.writeLong(frame, 2, Long.MAX_VALUE); FrameAccess.write(frame, 3, right)
                val shape = tuple(long, reference)
                val target = LocalJoinTarget(Any(), 1, intArrayOf(-1, -1), arrayOf(shape, shape),
                    typedSlots = arrayOf(intArrayOf(0, 1), intArrayOf(2, 3)))
                fun operand(index: Int, source: Int) = object : Expr() {
                    override fun execute(frame: VirtualFrame): Any? = error("No aggregate Object carrier")
                    override fun executeTuple(frame: VirtualFrame, slots: IntArray, offset: Int): Any? {
                        events += index
                        assertSame(left, frame.getObject(1)); assertSame(right, frame.getObject(3))
                        FrameAccess.writeLong(frame, slots[offset], frame.getLong(source))
                        FrameAccess.write(frame, slots[offset + 1], frame.getObject(source + 1))
                        return null
                    }
                }
                val metrics = Metrics(true)
                val call = LocalJoinCall(language, target, arrayOf(operand(0, 2), operand(1, 0)),
                    intArrayOf(-1, -1), metrics, arrayOf(intArrayOf(4, 5), intArrayOf(6, 7)))
                assertSame(target.jump, assertThrows(LocalJoinJump::class.java) { call.execute(frame) })
                assertEquals(listOf(0, 1), events)
                assertEquals(Long.MAX_VALUE, frame.getLong(0)); assertSame(right, frame.getObject(1))
                assertEquals(Long.MIN_VALUE, frame.getLong(2)); assertSame(left, frame.getObject(3))
                val references = frame.javaClass.getDeclaredField("indexedLocals").also { it.isAccessible = true }
                    .get(frame) as Array<*>
                for (slot in 4..7) {
                    assertEquals(FrameSlotKind.Illegal.tag, frame.getTag(slot))
                    assertNull(references[slot], "Completed join retains no scratch root")
                }
                assertEquals(1L, metrics.localJoinTransfers)
                assertEquals(0, language.handoffState.get().arguments.depth)
                assertEquals(0, language.handoffState.get().results.depth)
            } finally { context.leave() }
        }
    }

    @Test fun tupleLogicalShapeIsNotInferredFromEqualPhysicalWidth() {
        val expected = tuple(long, reference)
        CoreRepresentations.requireJoinArgument(expected, tuple(long, reference))
        for (actual in listOf(tuple(tuple(long), reference), tuple(reference, long), reference, CoreRepresentation.UNKNOWN))
            assertThrows(RuntimeFault::class.java) { CoreRepresentations.requireJoinArgument(expected, actual) }
    }

    @Test fun sumSwapsMoveTagsAndInactiveReferencesTogetherAndClearScratchRoots() {
        Context.newBuilder("thc").build().use { context ->
            context.initialize("thc"); context.enter()
            try {
                val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                val descriptor = FrameDescriptor.newBuilder().also { builder ->
                    repeat(12) { builder.addSlot(FrameSlotKind.Illegal, "sum field $it", null) }
                }.build()
                val frame = Truffle.getRuntime().createVirtualFrame(emptyArray(), descriptor)
                val marker = Any()
                val shape = CoreRepresentation(CoreKind.UNKNOWN, true, true,
                    listOf("WordRep", "BoxedRep (Just Lifted)", "WordRep"),
                    alternatives = listOf(reference, long), tagSlot = 0, alternativeSlots = listOf(listOf(1), listOf(2)))
                FrameAccess.writeLong(frame, 0, 1); FrameAccess.write(frame, 1, marker); FrameAccess.writeLong(frame, 2, 0)
                FrameAccess.writeLong(frame, 3, 2); FrameAccess.write(frame, 4, null); FrameAccess.writeLong(frame, 5, Long.MIN_VALUE)
                val target = LocalJoinTarget(Any(), 1, intArrayOf(-1, -1), arrayOf(shape, shape),
                    typedSlots = arrayOf(intArrayOf(0, 1, 2), intArrayOf(3, 4, 5)))
                fun operand(source: Int) = object : Expr() {
                    override fun execute(frame: VirtualFrame): Any? = error("No sum Object carrier")
                    override fun executeTuple(frame: VirtualFrame, slots: IntArray, offset: Int): Any? {
                        assertEquals(1L, frame.getLong(0)); assertSame(marker, frame.getObject(1))
                        assertEquals(2L, frame.getLong(3)); assertNull(frame.getObject(4))
                        FrameAccess.writeLong(frame, slots[offset], frame.getLong(source))
                        FrameAccess.write(frame, slots[offset + 1], frame.getObject(source + 1))
                        FrameAccess.writeLong(frame, slots[offset + 2], frame.getLong(source + 2))
                        return null
                    }
                }
                val metrics = Metrics(true)
                val call = LocalJoinCall(language, target, arrayOf(operand(3), operand(0)),
                    intArrayOf(-1, -1), metrics, arrayOf(intArrayOf(6, 7, 8), intArrayOf(9, 10, 11)))
                assertSame(target.jump, assertThrows(LocalJoinJump::class.java) { call.execute(frame) })
                assertEquals(2L, frame.getLong(0)); assertNull(frame.getObject(1)); assertEquals(Long.MIN_VALUE, frame.getLong(2))
                assertEquals(1L, frame.getLong(3)); assertSame(marker, frame.getObject(4)); assertEquals(0L, frame.getLong(5))
                val references = frame.javaClass.getDeclaredField("indexedLocals").also { it.isAccessible = true }.get(frame) as Array<*>
                for (slot in 6..11) {
                    assertEquals(FrameSlotKind.Illegal.tag, frame.getTag(slot)); assertNull(references[slot])
                }
                assertEquals(1L, metrics.localJoinTransfers)
                assertEquals(0, language.handoffState.get().arguments.depth)
                assertEquals(0, language.handoffState.get().results.depth)
            } finally { context.leave() }
        }
    }

    @Test fun emptyTupleCasesRunTheScrutineeAndTrapIfMalformedCoreReturns() {
        val scalar = mapOf("kind" to "long", "primReps" to listOf("IntRep"), "evaluated" to true)
        val closure = mapOf("kind" to "closure", "primReps" to listOf("BoxedRep (Just Lifted)"), "evaluated" to true)
        val tuple = mapOf("kind" to "unknown", "aggregate" to "unboxed-tuple", "components" to listOf(scalar),
            "primReps" to listOf("IntRep"), "evaluated" to true)
        val value = listOf("app", listOf("con", "Tuple", 1), listOf(listOf("var", "x", mapOf("rep" to scalar))),
            listOf(false), true, true, mapOf("rep" to tuple))
        val body = listOf("case", value, "dead", emptyList<Any>(), mapOf("rep" to scalar,
            "binder" to mapOf("id" to "dead", "lifted" to false, "rep" to tuple)))
        val function = listOf("lam", listOf(mapOf("id" to "x", "name" to "x", "lifted" to false, "rep" to scalar)),
            body, mapOf("rep" to closure, "resultRep" to scalar))
        val module = mapOf("constructors" to listOf(mapOf("id" to "Tuple", "kind" to "unboxed-tuple", "arity" to 1,
            "fieldReps" to listOf(listOf("IntRep")), "fieldLifted" to listOf(false), "strictFields" to listOf(false))),
            "bindings" to listOf(mapOf("id" to "entry", "name" to "entry", "rep" to closure, "lifted" to true, "expr" to function)))
        for (backend in listOf("ast", "bytecode")) Context.newBuilder("thc").build().use { context ->
            context.initialize("thc"); context.enter()
            try {
                val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                val program: ExecutableProgram = if (backend == "ast") Program(language, module) else BytecodeProgram(language, module)
                val failure = assertThrows(RuntimeFault::class.java) { Calls.target(program.entryTarget("entry"), arrayOf(0L, 42L)) }
                assertTrue(failure.message!!.contains("Non-exhaustive"), failure.message)
                assertEquals(0, language.handoffState.get().results.depth)
            } finally { context.leave() }
        }
        val frame = Truffle.getRuntime().createVirtualFrame(emptyArray(), FrameDescriptor.newBuilder().build())
        var evaluated = 0
        val bottom = RuntimeFault("original scrutinee failure")
        val scrutinee = object : Expr() {
            override fun execute(frame: VirtualFrame): Any? = error("No scalar tuple execution")
            override fun executeTuple(frame: VirtualFrame, slots: IntArray, offset: Int): Any? {
                evaluated++; throw bottom
            }
        }
        assertSame(bottom, assertThrows(RuntimeFault::class.java) {
            TupleCase(scrutinee, intArrayOf(), EmptyCaseResult(long)).executeLong(frame)
        })
        assertEquals(1, evaluated)
    }
}
