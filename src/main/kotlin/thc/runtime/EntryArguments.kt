package thc.runtime

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal
import com.oracle.truffle.api.RootCallTarget
import com.oracle.truffle.api.frame.VirtualFrame
import com.oracle.truffle.api.nodes.ExplodeLoop
import com.oracle.truffle.api.nodes.Node

/** Force only a saturated call's marked arguments. PAP construction never reaches this node. */
internal class EntryArguments(target: RootCallTarget, metrics: Metrics,
                              knownEvaluated: BooleanArray = booleanArrayOf(), prefixSize: Int = 0) : Node() {
    @field:CompilationFinal(dimensions = 1)
    private val positions: IntArray = (target.rootNode as? GuestRoot)?.let { root ->
        root.entryStrict.indices.filter { root.entryStrict[it] && root.inputLayout?.isTuple(it) != true &&
            (it < prefixSize || knownEvaluated.getOrNull(it - prefixSize) != true)
        }.map { ArgumentLayout.offset(root.inputLayout, it) + root.entryArgumentOffset }.toIntArray()
    } ?: intArrayOf()
    @Children private var forces = Array(positions.size) { Force(metrics) }

    @ExplodeLoop fun execute(frame: VirtualFrame, packet: Array<Any?>) {
        for (index in positions.indices) {
            val position = positions[index]
            packet[position] = forces[index].execute(frame, packet[position])
        }
    }
}

/** The uncommon megamorphic path uses the same contract without assuming a constant target. */
internal class IndirectEntryArguments(metrics: Metrics) : Node() {
    @Child private var force = Force(metrics)
    fun execute(frame: VirtualFrame, target: RootCallTarget, packet: Array<Any?>) {
        val root = target.rootNode as? GuestRoot ?: return
        // These positions are physical and already exclude zero-storage logical
        // inputs. Iterating logical arity here also invites speculative range
        // checks against a shorter compact packet before any marked operand runs.
        val positions = root.strictArgumentPositions
        for (position in positions) packet[position] = force.execute(frame, packet[position])
    }
}
