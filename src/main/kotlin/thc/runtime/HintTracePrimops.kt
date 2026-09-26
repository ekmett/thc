// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime

import com.oracle.truffle.api.frame.VirtualFrame

/** GHC's four locality levels are optional performance hints on this target. */
internal val prefetchArities = buildMap {
    for (locality in 0..3) {
        for (kind in listOf("ByteArray", "MutableByteArray", "Addr")) put("prefetch$kind$locality#", 3)
        put("prefetchValue$locality#", 2)
    }
}

internal enum class TraceOp(val primitive: String, val label: String, val arity: Int) {
    EVENT("traceEvent#", "event", 2),
    BINARY("traceBinaryEvent#", "binary", 3),
    MARKER("traceMarker#", "marker", 2);
    companion object { fun named(name: String) = entries.firstOrNull { it.primitive == name } }
}

internal class PrefetchExpression(@field:Child private var value: Expr,
    @field:Child private var offset: Expr?, @field:Child private var state: Expr,
    proof: CoreRepresentation) : Expr() {
    init { representation = proof.copy(evaluated = true) }
    override fun execute(frame: VirtualFrame): Any {
        // Evaluate operands in state order, without forcing a lifted value hint
        // or dereferencing/bounds-checking any ignored memory hint.
        value.execute(frame)
        offset?.executeLong(frame)
        requireVoidCarrier(state.execute(frame))
        return Unit
    }
}

internal class TraceExpression(private val operation: TraceOp,
    @field:Child private var address: Expr, @field:Child private var length: Expr?,
    @field:Child private var state: Expr, proof: CoreRepresentation) : Expr() {
    init { representation = proof.copy(evaluated = true) }
    override fun execute(frame: VirtualFrame): Any {
        val location = address.executeAddress(frame)
        val count = length?.executeLong(frame) ?: 0L
        requireVoidCarrier(state.execute(frame))
        RtsDiagnostics.trace(this, operation, location, count)
        return Unit
    }
}
