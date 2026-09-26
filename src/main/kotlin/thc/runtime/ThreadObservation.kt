// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime

import com.oracle.truffle.api.frame.VirtualFrame

/** Carrier/aggregate checks, not a second GHC type checker. */
internal object CoreThreadObservation {
    fun named(name: String) = name == "listThreads#" || name == "isCurrentThreadBound#"
    fun validate(name: String, arguments: List<CoreRepresentation>, flags: List<*>, result: CoreRepresentation) {
        fun scalar(rep: CoreRepresentation, kind: CoreKind) =
            !rep.isAggregate && !rep.isVector && rep.kind == kind
        val fields = result.components
        val kind = if (name == "listThreads#") CoreKind.OBJECT else CoreKind.LONG
        if (!named(name) || arguments.size != 1 || flags != listOf(false) ||
            !scalar(arguments[0], CoreKind.VOID) || !result.isTuple || result.isSum || result.isVector ||
            result.kind != CoreKind.UNKNOWN || fields?.size != 2 ||
            !scalar(fields[0], CoreKind.VOID) || !scalar(fields[1], kind))
            fault("$name requires State# and a State#/observation tuple")
    }
}

internal class ThreadObservation(private val listing: Boolean, @field:Child private var state: Expr,
                                 proof: CoreRepresentation) : Expr() {
    init { representation = proof.copy(evaluated = true) }
    override fun execute(frame: VirtualFrame): Nothing = fault("Thread observation requires a tuple destination")
    override fun executeTuple(frame: VirtualFrame, slots: IntArray, offset: Int): Any? {
        requireVoidCarrier(state.execute(frame))
        val threads = GuestThreads.current(this)
        if (listing) FrameAccess.write(frame, slots[offset], threads.snapshot())
        else FrameAccess.writeLong(frame, slots[offset], if (threads.isCurrentBound()) 1L else 0L)
        return null
    }
}
