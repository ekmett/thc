package thc.runtime

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal
import com.oracle.truffle.api.Truffle
import com.oracle.truffle.api.frame.VirtualFrame
import com.oracle.truffle.api.nodes.*

/** Immutable lexical branch identity, shared safely by cloned nodes. No call packet is needed. */
internal class LocalJoinTarget(val group: Any, val index: Int,
    @field:CompilationFinal(dimensions = 1) val slots: IntArray,
    @field:CompilationFinal(dimensions = 1) val proofs: Array<CoreRepresentation>) {
    val jump = LocalJoinJump(this)
}
internal class LocalJoinJump(val target: LocalJoinTarget) : ControlFlowException()

internal class LocalJoinCall(private val target: LocalJoinTarget,
    @field:Children private var arguments: Array<Expr>,
    @field:CompilationFinal(dimensions = 1) private val temporaries: IntArray,
    private val metrics: Metrics) : Expr() {
    @ExplodeLoop override fun execute(frame: VirtualFrame): Nothing {
        // All operands are read before any formal is overwritten, including recursive swaps.
        for (i in arguments.indices) {
            if (target.proofs[i].isLong) FrameAccess.writeLong(frame, temporaries[i], arguments[i].executeRequiredLong(frame))
            else FrameAccess.write(frame, temporaries[i], arguments[i].execute(frame))
        }
        for (i in arguments.indices) {
            if (target.proofs[i].isLong) FrameAccess.writeLong(frame, target.slots[i], frame.getLong(temporaries[i]))
            else FrameAccess.write(frame, target.slots[i], FrameAccess.read(frame, temporaries[i]))
        }
        if (metrics.enabled) metrics.localJoinTransfers++
        throw target.jump
    }
    override fun executeLong(frame: VirtualFrame): Long = execute(frame)
    override fun executeClosure(frame: VirtualFrame): Closure = execute(frame)
    override fun executeDataValue(frame: VirtualFrame): DataValue = execute(frame)
    override fun executeAddress(frame: VirtualFrame): LiteralAddress = execute(frame)
}

private class LocalJoinRepeater(private val group: Any, private val selector: Int, private val result: Int,
    @field:Children private var bodies: Array<Expr>, private val exactLong: Boolean) : Node(), RepeatingNode {
    @ExplodeLoop override fun executeRepeating(frame: VirtualFrame): Boolean {
        try {
            val selected = frame.getLong(selector)
            for (index in bodies.indices) if (selected == index.toLong()) {
                if (exactLong) FrameAccess.writeLong(frame, result, bodies[index].executeRequiredLong(frame))
                else FrameAccess.write(frame, result, bodies[index].execute(frame))
                return false
            }
            fault("Invalid local join selector")
        } catch (jump: LocalJoinJump) {
            if (jump.target.group !== group) throw jump
            frame.setLong(selector, jump.target.index.toLong())
            return true
        }
    }
}

/** A local region returns through a frame slot, keeping primitive results off LoopNode's Object ABI. */
internal class LocalJoinRegion(group: Any, private val selector: Int, private val result: Int,
    bodies: Array<Expr>, proof: CoreRepresentation) : Expr() {
    init { representation = proof }
    @Child private var loop: LoopNode = Truffle.getRuntime().createLoopNode(
        LocalJoinRepeater(group, selector, result, bodies, proof.isLong))
    private fun run(frame: VirtualFrame) {
        FrameAccess.writeLong(frame, selector, 0L)
        loop.execute(frame)
    }
    override fun execute(frame: VirtualFrame): Any? { run(frame); return FrameAccess.read(frame, result) }
    override fun executeLong(frame: VirtualFrame): Long {
        run(frame)
        return if (representation.isLong) frame.getLong(result) else RuntimeTypesGen.expectLong(FrameAccess.read(frame, result))
    }
    override fun executeClosure(frame: VirtualFrame): Closure { run(frame); return RuntimeTypesGen.expectClosure(FrameAccess.read(frame, result)) }
    override fun executeDataValue(frame: VirtualFrame): DataValue { run(frame); return RuntimeTypesGen.expectDataValue(FrameAccess.read(frame, result)) }
    override fun executeAddress(frame: VirtualFrame): LiteralAddress { run(frame); return RuntimeTypesGen.expectLiteralAddress(FrameAccess.read(frame, result)) }
}
