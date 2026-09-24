// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime

import com.oracle.truffle.api.Truffle
import com.oracle.truffle.api.frame.FrameDescriptor
import com.oracle.truffle.api.frame.FrameSlotKind
import com.oracle.truffle.api.frame.VirtualFrame
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ManagedMVarContractTest {
    private val state = CoreRepresentation(CoreKind.VOID, present = true, primReps = emptyList())
    private val mvar = CoreRepresentation(CoreKind.OBJECT, present = true, primReps = listOf("BoxedRep (Just Unlifted)"))
    private val lifted = CoreRepresentation(CoreKind.OBJECT, present = true, primReps = listOf("BoxedRep (Just Lifted)"))
    private val integer = CoreRepresentation(CoreKind.LONG, present = true, primReps = listOf("IntRep"))
    private fun tuple(vararg fields: CoreRepresentation) = CoreRepresentation(CoreKind.UNKNOWN, present = true,
        components = fields.toList(), primReps = fields.flatMap { it.primReps!! })
    private fun arguments(op: MVarOp, payload: CoreRepresentation) = when (op) {
        MVarOp.NEW -> listOf(state)
        MVarOp.PUT, MVarOp.TRY_PUT -> listOf(mvar, payload, state)
        else -> listOf(mvar, state)
    }
    private fun result(op: MVarOp, payload: CoreRepresentation) = when (op) {
        MVarOp.NEW -> tuple(state, mvar)
        MVarOp.PUT -> state
        MVarOp.TAKE, MVarOp.READ -> tuple(state, payload)
        MVarOp.TRY_TAKE, MVarOp.TRY_READ -> tuple(state, integer, payload)
        MVarOp.TRY_PUT, MVarOp.IS_EMPTY -> tuple(state, integer)
    }
    private fun flags(arguments: List<CoreRepresentation>): List<Boolean> =
        arguments.map { it.primReps == listOf("BoxedRep (Just Lifted)") }

    @Test fun allEightContractsRetainLogicalStateAndBothBoxedLevities() {
        assertEquals(8, MVarOp.entries.size)
        for (op in MVarOp.entries) for (payload in listOf(lifted, mvar,
            lifted.copy(kind = CoreKind.DATA), mvar.copy(kind = CoreKind.DATA),
            lifted.copy(kind = CoreKind.CLOSURE))) {
            val args = arguments(op, payload)
            op.validate(args, flags(args), result(op, payload))
            assertSame(op, MVarOp.named(op.primitive))
        }
        assertNull(MVarOp.named("newMutVar#"))
    }

    @Test fun malformedArgumentProofsAndFlagsAreRejected() {
        val bad = listOf(CoreRepresentation.UNKNOWN, integer,
            mvar.copy(primReps = listOf("BoxedRep Nothing")),
            CoreRepresentation(CoreKind.ADDRESS, present = true, primReps = listOf("AddrRep")),
            tuple(), tuple(state), tuple(integer))
        for (op in MVarOp.entries) {
            val args = arguments(op, lifted)
            for (index in args.indices) {
                for (proof in bad) {
                    val changed = args.toMutableList().also { it[index] = proof }
                    assertThrows(RuntimeFault::class.java, { op.validate(changed, flags(changed), result(op, lifted)) },
                        "$op argument $index: $proof")
                }
                val inverted = flags(args).toMutableList().also { it[index] = !it[index] }
                assertThrows(RuntimeFault::class.java) { op.validate(args, inverted, result(op, lifted)) }
                for (invalid in listOf(null, 0L, "false")) {
                    val forged = flags(args).map { it as Any? }.toMutableList().also { it[index] = invalid }
                    assertThrows(RuntimeFault::class.java) { op.validate(args, forged, result(op, lifted)) }
                }
            }
            assertThrows(RuntimeFault::class.java) { op.validate(args.dropLast(1), flags(args), result(op, lifted)) }
            assertThrows(RuntimeFault::class.java) { op.validate(args + state, flags(args) + false, result(op, lifted)) }
        }
    }

    @Test fun resultTuplesRequireExactLogicalFieldsAndFlattenedRepresentations() {
        for (op in MVarOp.entries) {
            val args = arguments(op, lifted)
            val expected = result(op, lifted)
            for (forged in listOf(CoreRepresentation.UNKNOWN, integer, tuple(),
                expected.copy(primReps = listOf("WordRep"))))
                assertThrows(RuntimeFault::class.java) { op.validate(args, flags(args), forged) }
            if (!op.tuple) continue
            val fields = expected.components!!
            for (forged in listOf(expected.copy(kind = CoreKind.OBJECT),
                tuple(*fields.drop(1).toTypedArray()), tuple(*(fields + state).toTypedArray()),
                expected.copy(alternatives = listOf(state))))
                assertThrows(RuntimeFault::class.java) { op.validate(args, flags(args), forged) }
            for (index in fields.indices) {
                val replacement = when (fields[index]) {
                    state -> tuple()
                    integer -> integer.copy(primReps = listOf("WordRep"))
                    else -> integer
                }
                val changed = fields.toMutableList().also { it[index] = replacement }
                assertThrows(RuntimeFault::class.java) {
                    op.validate(args, flags(args), tuple(*changed.toTypedArray()))
                }
            }
        }
    }

    private fun frame(): Pair<VirtualFrame, IntArray> {
        val builder = FrameDescriptor.newBuilder()
        val first = builder.addSlot(FrameSlotKind.Illegal, "flag", null)
        val second = builder.addSlot(FrameSlotKind.Object, "payload", null)
        return Truffle.getRuntime().createVirtualFrame(emptyArray(), builder.build()) to intArrayOf(first, second)
    }
    private fun operand(value: Any?): Expr = object : Expr() {
        override fun execute(frame: VirtualFrame): Any? = value
    }

    @Test fun invalidStateIsRejectedBeforeMutationOrTuplePublication() {
        val (frame, slots) = frame()
        val original = Any()
        for (op in MVarOp.entries) {
            val cell = ManagedMVar()
            assertTrue(cell.tryPut(original))
            FrameAccess.writeLong(frame, slots[0], 71L)
            FrameAccess.write(frame, slots[1], original)
            val operands = when (op) {
                MVarOp.NEW -> arrayOf(operand(1L))
                MVarOp.PUT, MVarOp.TRY_PUT -> arrayOf(operand(cell), operand(Any()), operand(1L))
                else -> arrayOf(operand(cell), operand(1L))
            }
            val expression = mVarExpression(op, result(op, lifted), operands)
            assertThrows(RuntimeFault::class.java) {
                if (op.tuple) expression.executeTuple(frame, slots, 0) else expression.execute(frame)
            }
            assertSame(original, cell.tryRead().value)
            assertEquals(71L, frame.getLong(slots[0]))
            assertSame(original, frame.getObject(slots[1]))
        }
    }

    @Test fun failedTryReadsClearOldPayloadAndDoNotInventSuccess() {
        val (frame, slots) = frame()
        for (op in listOf(MVarOp.TRY_TAKE, MVarOp.TRY_READ)) {
            val cell = ManagedMVar()
            val expression = mVarExpression(op, result(op, lifted), arrayOf(operand(cell), operand(Unit)))
            FrameAccess.writeLong(frame, slots[0], 1L)
            FrameAccess.write(frame, slots[1], Any())
            expression.executeTuple(frame, slots, 0)
            assertEquals(0L, frame.getLong(slots[0]))
            assertNull(frame.getObject(slots[1]))
            assertTrue(cell.isEmpty())
            // A null is a legitimate stored reference for the cell protocol;
            // fullness is tracked separately, not inferred from the payload.
            assertTrue(cell.tryPut(null))
            expression.executeTuple(frame, slots, 0)
            assertEquals(1L, frame.getLong(slots[0]))
            assertNull(frame.getObject(slots[1]))
            assertEquals(op == MVarOp.TRY_TAKE, cell.isEmpty())
        }
    }
}
