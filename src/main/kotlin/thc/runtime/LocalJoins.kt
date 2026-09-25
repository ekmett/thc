// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal
import com.oracle.truffle.api.Truffle
import com.oracle.truffle.api.frame.VirtualFrame
import com.oracle.truffle.api.nodes.*

/** Immutable lexical branch identity, shared safely by cloned nodes. No call packet is needed. */
internal class LocalJoinTarget(val group: Any, val index: Int,
    @field:CompilationFinal(dimensions = 1) val slots: IntArray,
    @field:CompilationFinal(dimensions = 1) val proofs: Array<CoreRepresentation>,
    @field:CompilationFinal(dimensions = 1) val entryStrict: BooleanArray = BooleanArray(slots.size),
    val result: CoreRepresentation = CoreRepresentation.UNKNOWN,
    val vectorSlots: Array<IntArray?> = arrayOfNulls(proofs.size)) {
    val jump = LocalJoinJump(this)
}
internal class LocalJoinJump(val target: LocalJoinTarget) : ControlFlowException()

internal class LocalJoinCall(private val target: LocalJoinTarget,
    @field:Children private var arguments: Array<Expr>,
    @field:CompilationFinal(dimensions = 1) private val temporaries: IntArray,
    private val metrics: Metrics,
    private val vectorTemporaries: Array<IntArray?> = arrayOfNulls(target.proofs.size)) : Expr() {
    // This node never returns a value. It must not weaken the WHNF proof
    // contributed by the region's actual returning branches.
    init { representation = target.result.copy(evaluated = true) }
    @field:CompilationFinal(dimensions = 1)
    private val referenceKinds = target.proofs.map { if (it.evaluated) it.kind else CoreKind.UNKNOWN }.toTypedArray()
    // The logical component list is a regular immutable-by-contract List, not a PE constant.
    // Decide emptiness while lowering, so invalid scalar paths for slot -1 never enter the graph.
    @field:CompilationFinal(dimensions = 1) private val emptyInputs = target.proofs.map { it.isEmptyTuple }.toBooleanArray()
    @field:CompilationFinal(dimensions = 1) private val emptySlots = IntArray(0)
    @ExplodeLoop override fun execute(frame: VirtualFrame): Nothing {
        // All operands are read before any formal is overwritten, including recursive swaps.
        for (i in arguments.indices) {
            if (emptyInputs[i]) arguments[i].executeTuple(frame, emptySlots, 0)
            else if (target.proofs[i].isVector) arguments[i].executeTuple(frame,
                vectorTemporaries[i] ?: fault("Missing vector join temporaries"), 0)
            else if (target.proofs[i].isLong) FrameAccess.writeLong(frame, temporaries[i], arguments[i].executeRequiredLong(frame))
            else if (target.proofs[i].isFloat) FrameAccess.writeFloat(frame, temporaries[i], arguments[i].executeRequiredFloat(frame))
            else if (target.proofs[i].isDouble) FrameAccess.writeDouble(frame, temporaries[i], arguments[i].executeRequiredDouble(frame))
            else if (referenceKinds[i] == CoreKind.DATA) FrameAccess.write(frame, temporaries[i], arguments[i].executeRequiredDataValue(frame))
            else if (referenceKinds[i] == CoreKind.CLOSURE) FrameAccess.write(frame, temporaries[i], arguments[i].executeRequiredClosure(frame))
            else if (referenceKinds[i] == CoreKind.ADDRESS) FrameAccess.write(frame, temporaries[i], arguments[i].executeRequiredAddress(frame))
            else FrameAccess.write(frame, temporaries[i], arguments[i].execute(frame))
        }
        for (i in arguments.indices) {
            if (!emptyInputs[i]) {
                if (target.proofs[i].isVector) VectorLayout(target.proofs[i]).copy(frame,
                    vectorTemporaries[i] ?: fault("Missing vector join temporaries"), 0,
                    target.vectorSlots[i] ?: fault("Missing vector join formals"), 0)
                else if (target.proofs[i].isLong) FrameAccess.writeLong(frame, target.slots[i], frame.getLong(temporaries[i]))
                else if (target.proofs[i].isFloat) FrameAccess.writeFloat(frame, target.slots[i], frame.getFloat(temporaries[i]))
                else if (target.proofs[i].isDouble) FrameAccess.writeDouble(frame, target.slots[i], frame.getDouble(temporaries[i]))
                else if (referenceKinds[i] == CoreKind.DATA) FrameAccess.write(frame, target.slots[i],
                    frame.getObject(temporaries[i]) as? DataValue ?: fault("Expected constructor join argument"))
                else if (referenceKinds[i] == CoreKind.CLOSURE) FrameAccess.write(frame, target.slots[i],
                    frame.getObject(temporaries[i]) as? Closure ?: fault("Expected closure join argument"))
                else if (referenceKinds[i] == CoreKind.ADDRESS) FrameAccess.write(frame, target.slots[i],
                    frame.getObject(temporaries[i]) as? ManagedAddress ?: fault("Expected address join argument"))
                else FrameAccess.write(frame, target.slots[i], FrameAccess.read(frame, temporaries[i]))
            }
        }
        // Clear only after every parallel move succeeds; no join body reads
        // these scratch slots, including when control enters a different join.
        for (temporary in temporaries) if (temporary >= 0) frame.clear(temporary)
        for (lanes in vectorTemporaries) lanes?.forEach(frame::clear)
        if (metrics.enabled) metrics.incrementLocalJoinTransfers()
        throw target.jump
    }
    override fun executeLong(frame: VirtualFrame): Long = execute(frame)
    override fun executeFloat(frame: VirtualFrame): Float = execute(frame)
    override fun executeDouble(frame: VirtualFrame): Double = execute(frame)
    override fun executeClosure(frame: VirtualFrame): Closure = execute(frame)
    override fun executeDataValue(frame: VirtualFrame): DataValue = execute(frame)
    override fun executeAddress(frame: VirtualFrame): ManagedAddress = execute(frame)
    override fun executeTuple(frame: VirtualFrame, slots: IntArray, offset: Int): Any? = execute(frame)
}

private class LocalJoinRepeater(private val group: Any, private val selector: Int, private val result: Int,
    @field:Children private var bodies: Array<Expr>, proof: CoreRepresentation,
    @field:CompilationFinal(dimensions = 1) private val tupleSlots: IntArray) : Node(), RepeatingNode {
    private val tuple = proof.isTypedTransport
    private val exactLong = proof.isLong
    private val exactFloat = proof.isFloat
    private val exactDouble = proof.isDouble
    private val referenceKind = if (proof.evaluated) proof.kind else CoreKind.UNKNOWN
    private fun executeBody(frame: VirtualFrame, body: Expr) {
        if (tuple) body.executeTuple(frame, tupleSlots, 0)
        else if (exactLong) FrameAccess.writeLong(frame, result, body.executeRequiredLong(frame))
        else if (exactFloat) FrameAccess.writeFloat(frame, result, body.executeRequiredFloat(frame))
        else if (exactDouble) FrameAccess.writeDouble(frame, result, body.executeRequiredDouble(frame))
        else if (referenceKind == CoreKind.DATA) FrameAccess.write(frame, result, body.executeRequiredDataValue(frame))
        else if (referenceKind == CoreKind.CLOSURE) FrameAccess.write(frame, result, body.executeRequiredClosure(frame))
        else if (referenceKind == CoreKind.ADDRESS) FrameAccess.write(frame, result, body.executeRequiredAddress(frame))
        else FrameAccess.write(frame, result, body.execute(frame))
    }
    @ExplodeLoop private fun executeTarget(frame: VirtualFrame, selected: Long) {
        // Structurally exclude entry even when PE cannot resolve a caught jump's
        // target. Otherwise nested entry regions can be expanded a second time.
        for (index in 1 until bodies.size) if (selected == index.toLong()) {
            executeBody(frame, bodies[index])
            return
        }
        fault("Invalid local join selector")
    }
    fun executeOnce(frame: VirtualFrame) {
        try {
            executeBody(frame, bodies[0])
        } catch (jump: LocalJoinJump) {
            if (jump.target.group !== group) throw jump
            // A nonrecursive RHS has only the outer join scope. It cannot jump
            // to this group again; any ancestor transfer escapes this catch.
            executeTarget(frame, jump.target.index.toLong())
        }
    }
    override fun executeRepeating(frame: VirtualFrame): Boolean {
        try {
            val selected = frame.getLong(selector)
            if (selected == 0L) executeBody(frame, bodies[0]) else executeTarget(frame, selected)
            return false
        } catch (jump: LocalJoinJump) {
            if (jump.target.group !== group) throw jump
            frame.setLong(selector, jump.target.index.toLong())
            return true
        }
    }
}

/** Only recursive groups need loops; all regions preserve their typed result through a frame slot. */
internal class LocalJoinRegion(group: Any, private val selector: Int, private val result: Int,
    bodies: Array<Expr>, proof: CoreRepresentation, recursive: Boolean,
    private val tuple: TupleShape? = null,
    @field:CompilationFinal(dimensions = 1) private val tupleSlots: IntArray = intArrayOf()) : Expr() {
    init { representation = proof }
    @Child private var single: LocalJoinRepeater? =
        if (recursive) null else LocalJoinRepeater(group, selector, result, bodies, proof, tupleSlots)
    @Child private var loop: LoopNode? = if (recursive) Truffle.getRuntime().createLoopNode(
        LocalJoinRepeater(group, selector, result, bodies, proof, tupleSlots)) else null
    private fun run(frame: VirtualFrame) {
        val once = single
        if (once != null) once.executeOnce(frame)
        else {
            FrameAccess.writeLong(frame, selector, 0L)
            loop!!.execute(frame)
        }
    }
    private fun resultValue(frame: VirtualFrame): Any? =
        if (representation.isVector) VectorLayout(representation).read(frame, tupleSlots, 0)
        else if (representation.isEvaluatedReference) frame.getObject(result) else FrameAccess.read(frame, result)
    override fun execute(frame: VirtualFrame): Any? { run(frame); return resultValue(frame) }
    override fun executeLong(frame: VirtualFrame): Long {
        run(frame)
        return if (representation.isLong) frame.getLong(result) else RuntimeTypesGen.expectLong(FrameAccess.read(frame, result))
    }
    override fun executeClosure(frame: VirtualFrame): Closure { run(frame); return RuntimeTypesGen.expectClosure(resultValue(frame)) }
    override fun executeFloat(frame: VirtualFrame): Float {
        run(frame)
        return if (representation.isFloat) frame.getFloat(result) else RuntimeTypesGen.expectFloat(FrameAccess.read(frame, result))
    }
    override fun executeDouble(frame: VirtualFrame): Double {
        run(frame)
        return if (representation.isDouble) frame.getDouble(result) else RuntimeTypesGen.expectDouble(FrameAccess.read(frame, result))
    }
    override fun executeDataValue(frame: VirtualFrame): DataValue { run(frame); return RuntimeTypesGen.expectDataValue(resultValue(frame)) }
    override fun executeAddress(frame: VirtualFrame): ManagedAddress { run(frame); return RuntimeTypesGen.expectManagedAddress(resultValue(frame)) }
    @ExplodeLoop override fun executeTuple(frame: VirtualFrame, slots: IntArray, offset: Int): Any? {
        val shape = tuple ?: fault("Scalar join region cannot write a tuple")
        run(frame)
        for (index in tupleSlots.indices) {
            if (shape.layout.isLong(index)) FrameAccess.writeLong(frame, slots[offset + index], frame.getLong(tupleSlots[index]))
            else if (shape.layout.isFloat(index)) FrameAccess.writeFloat(frame, slots[offset + index], frame.getFloat(tupleSlots[index]))
            else if (shape.layout.isDouble(index)) FrameAccess.writeDouble(frame, slots[offset + index], frame.getDouble(tupleSlots[index]))
            else FrameAccess.write(frame, slots[offset + index], frame.getObject(tupleSlots[index]))
        }
        // Compiler-created region temporaries are distinct from enclosing
        // destinations. Preserve an aliased destination if invoked directly.
        // Clear only after every copy succeeds, as with join argument moves.
        for (source in tupleSlots) {
            var destination = false
            for (index in tupleSlots.indices) if (slots[offset + index] == source) destination = true
            if (!destination) frame.clear(source)
        }
        return null
    }
}
