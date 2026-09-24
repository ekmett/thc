// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime

import com.oracle.truffle.api.frame.VirtualFrame
import com.oracle.truffle.api.nodes.Node
import com.oracle.truffle.api.nodes.RootNode
import thc.Language

private const val UNIT_CONSTRUCTOR_ID = "ghc-internal:GHC.Internal.Tuple.()"

/** Consumes the only supported host IO result after the guest action has run. */
private class IoUnitDestination(shape: TupleShape, private val language: Language) : TupleDestination(shape) {
    override fun consume(frame: VirtualFrame, node: Node, result: Any?) {
        AsyncContinuations.publicResult(result, node)
        val raw = if (result === TupleComplete) {
            val pool = language.handoffState.get().results
            val storage = pool.completed()
            try {
                if (storage.layout !== shape.layout) fault("IO main returned the wrong tuple layout")
                shape.layout.getObject(storage, 0)
            } finally { pool.releaseChecked(storage, shape.layout) }
        } else {
            val storage = result as? HandoffStorage ?: fault("IO main returned no tuple")
            if (storage.layout !== shape.layout) fault("IO main returned the wrong tuple layout")
            shape.layout.getObject(storage, 0)
        }
        // Release the tuple loan before forcing the lifted field. The outer root
        // checks the constructor after any nested guest evaluation completes.
        frame.setObject(FrameLayout.TAIL_RESULT, raw)
    }
}

/** The IO newtype is an erased State# transformer, not a native Haskell process. */
internal class IoMainRoot(language: Language, result: CoreRepresentation) : RootNode(language, FrameLayout().build()) {
    private val shape = TupleShape(result, language)
    @Child private var force = Force(Metrics(false))
    @Child private var unitForce = Force(Metrics(false))
    @Child private var dispatch = TupleDispatch(IoUnitDestination(shape, language), Metrics(false), 1, false)

    override fun execute(frame: VirtualFrame): Any {
        frame.setLong(FrameLayout.BLOOM_FILTER, 0L)
        val action = force.execute(frame, frame.arguments[0]) as? Closure ?: fault("IO main is not a state transformer")
        dispatch.execute(frame, action, arrayOf(Unit))
        val value = unitForce.execute(frame, frame.getObject(FrameLayout.TAIL_RESULT)) as? DataValue
            ?: fault("IO main did not return boxed unit")
        if (value.layout.id != UNIT_CONSTRUCTOR_ID || value.layout.arity != 0)
            fault("IO main did not return boxed unit")
        return Unit
    }

    override fun getName() = "THC IO main"
}
