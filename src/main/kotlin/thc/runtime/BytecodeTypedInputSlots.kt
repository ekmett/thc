// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal
import com.oracle.truffle.api.bytecode.BytecodeNode
import com.oracle.truffle.api.bytecode.LocalAccessor
import com.oracle.truffle.api.frame.VirtualFrame
import com.oracle.truffle.api.nodes.ExplodeLoop

/** Exact vector metadata and one replay-local slot; no activation or payload is retained here. */
internal class BytecodeVectorSlots(proof: CoreRepresentation,
    @field:CompilationFinal(dimensions = 1) private val slots: Array<LocalAccessor>) {
    private val layout = VectorLayout(proof)
    init { require(slots.size == TupleShape.flatten(proof).size) }
    fun write(frame: VirtualFrame, root: BytecodeRoot, value: Any) =
        layout.write(root.bytecodeNode, frame, slots, 0, value)
    fun read(frame: VirtualFrame, root: BytecodeRoot): Any = layout.read(root.bytecodeNode, frame, slots, 0)
}

/** Replay-local destinations for a typed input loan; never retains an activation. */
internal class BytecodeTypedInputSlots(
    private val entry: TypedInputLayout,
    private val bloom: LocalAccessor,
    @field:CompilationFinal(dimensions = 1) private val arguments: Array<LocalAccessor>,
    @field:CompilationFinal(dimensions = 1) private val indices: IntArray,
    @field:CompilationFinal(dimensions = 1) private val proofs: Array<CoreRepresentation>,
    private val captureLayout: CaptureLayout?,
    @field:CompilationFinal(dimensions = 1) private val captures: Array<LocalAccessor>,
    @field:CompilationFinal(dimensions = 1) private val captureProofs: Array<CoreRepresentation>,
    @field:CompilationFinal(dimensions = 1) private val vectorCaptures: Array<BytecodeRoot.VectorCaptureSlots> = emptyArray()
) {
    @field:CompilationFinal(dimensions = 1)
    private val references = proofs.map { it.referenceCarrier() }.toTypedArray()
    @field:CompilationFinal(dimensions = 1)
    private val captureReferences = captureProofs.map { it.referenceCarrier() }.toTypedArray()

    fun enter(frame: VirtualFrame, root: BytecodeRoot) {
        restore(frame, root.bytecodeNode, entry.take(frame.arguments), true, root.mask)
    }

    fun tail(frame: VirtualFrame, root: BytecodeRoot, transfer: TailCall) {
        val input = transfer.input ?: fault("Typed input target received a scalar packet")
        restore(frame, root.bytecodeNode, input, false, root.mask)
    }

    /** All source slots are disjoint snapshots: copying formals and captures is a parallel move. */
    @ExplodeLoop fun self(frame: VirtualFrame, root: BytecodeRoot, source: BytecodeInputSource, function: Closure) {
        try {
            entry.validateSelfSource(source, frame, root)
            val bytecode = root.bytecodeNode
            for (i in arguments.indices) {
                val from = indices[i]
                if (entry.packet.isLong(entry.header + from)) arguments[i].setLong(bytecode, frame, source.long(frame, root, null, from))
                else if (entry.packet.isFloat(entry.header + from)) arguments[i].setFloat(bytecode, frame, source.float(frame, root, null, from))
                else if (entry.packet.isDouble(entry.header + from)) arguments[i].setDouble(bytecode, frame, source.double(frame, root, null, from))
                else {
                    val value = source.reference(frame, root, null, from)
                    val expected = references[i]
                    arguments[i].setObject(bytecode, frame, if (expected == null) value else requireReferenceCarrier(value, expected))
                }
            }
            if (captureLayout != null) restoreCaptured(frame, bytecode, function.environment ?: fault("Invalid captured frame"))
            // Neither bloom nor the current result destination changes on a self reentry.
        } finally { clearBytecodeInputSource(source, frame, root) }
    }

    @ExplodeLoop private fun restoreCaptured(frame: VirtualFrame, bytecode: BytecodeNode, environment: CapturedFrame) {
        val layout = captureLayout ?: fault("Missing capture layout")
        for (i in captures.indices) {
            val proof = captureProofs[i]
            if (proof.isLong && proof.evaluated) captures[i].setLong(bytecode, frame, layout.readLong(environment, i))
            else if (proof.isFloat && proof.evaluated) captures[i].setFloat(bytecode, frame, layout.readFloat(environment, i))
            else if (proof.isDouble && proof.evaluated) captures[i].setDouble(bytecode, frame, layout.readDouble(environment, i))
            else {
                val value = layout.read(environment, i)
                val expected = captureReferences[i]
                captures[i].setObject(bytecode, frame, if (expected == null) value else requireReferenceCarrier(value, expected))
            }
        }
        for (vector in vectorCaptures) vector.restore(frame, bytecode, environment)
    }

    @ExplodeLoop private fun restore(frame: VirtualFrame, bytecode: BytecodeNode,
        input: HandoffStorage, initial: Boolean, mask: Long) {
        try {
            if (initial) entry.validate(input) else entry.validateTail(input)
            if (initial) bloom.setLong(bytecode, frame, entry.packet.getLong(input, 0) or mask)
            for (i in arguments.indices) {
                val from = indices[i] + entry.header
                if (entry.packet.isLong(from)) arguments[i].setLong(bytecode, frame, entry.packet.getLong(input, from))
                else if (entry.packet.isFloat(from)) arguments[i].setFloat(bytecode, frame, entry.packet.getFloat(input, from))
                else if (entry.packet.isDouble(from)) arguments[i].setDouble(bytecode, frame, entry.packet.getDouble(input, from))
                else {
                    val value = entry.packet.getObject(input, from)
                    val expected = references[i]
                    arguments[i].setObject(bytecode, frame, if (expected == null) value else requireReferenceCarrier(value, expected))
                }
            }
            if (captureLayout != null) {
                val environment = entry.packet.getObject(input, 1) as? CapturedFrame ?: fault("Invalid captured frame")
                restoreCaptured(frame, bytecode, environment)
            }
        } finally {
            // A failed checked reference restore still relinquishes the loan.
            // This pool is independent of any outstanding tuple-result storage.
            entry.releaseChecked(input)
        }
    }
}

/** Operand temporaries cease to own references after dispatch, including a tail bounce. */
@ExplodeLoop
internal fun clearBytecodeInputSource(source: BytecodeInputSource, frame: VirtualFrame, root: BytecodeRoot) {
    for (slot in source.slots) slot.clear(root.bytecodeNode, frame)
}
