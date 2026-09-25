// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime

import com.oracle.truffle.api.frame.VirtualFrame
import com.oracle.truffle.api.nodes.Node
import com.oracle.truffle.api.nodes.RootNode
import thc.Language

private class ManagedExportDestination(shape: TupleShape, private val language: Language) : TupleDestination(shape) {
    override fun consume(frame: VirtualFrame, node: Node, result: Any?) {
        AsyncContinuations.publicResult(result, node)
        val raw = if (result === TupleComplete) {
            val pool = language.handoffState.get().results
            val storage = pool.completed()
            try {
                if (storage.layout !== shape.layout) fault("Managed export returned the wrong IO tuple layout")
                shape.layout.getObject(storage, 0)
            } finally { pool.releaseChecked(storage, shape.layout) }
        } else {
            val storage = result as? HandoffStorage ?: fault("Managed export returned no IO tuple")
            if (storage.layout !== shape.layout) fault("Managed export returned the wrong IO tuple layout")
            shape.layout.getObject(storage, 0)
        }
        // The tuple loan must end before forcing a possibly lazy boxed result.
        frame.setObject(FrameLayout.TAIL_RESULT, raw)
    }
}

internal class ManagedExportIoRoot(language: Language, result: CoreRepresentation) : RootNode(language, FrameLayout().build()) {
    private val shape = TupleShape(result, language)
    @Child private var force = Force(Metrics(false))
    @Child private var resultForce = Force(Metrics(false))
    @Child private var dispatch = TupleDispatch(ManagedExportDestination(shape, language), Metrics(false), 1, false)
    override fun execute(frame: VirtualFrame): Any? {
        frame.setLong(FrameLayout.BLOOM_FILTER, 0L)
        val action = force.execute(frame, frame.arguments[0]) as? Closure
            ?: fault("Managed IO export is not a state transformer")
        dispatch.execute(frame, action, arrayOf(Unit))
        return resultForce.execute(frame, frame.getObject(FrameLayout.TAIL_RESULT))
    }
    override fun getName() = "THC managed export IO"
}
