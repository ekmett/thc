// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime

import com.oracle.truffle.api.frame.VirtualFrame

/** Copy signatures after lowering: Int# offsets/counts use Long, State# is
 * scalar VOID, and storage/address operands remain distinct physical carriers. */
internal enum class AddressArrayCopyOp(val primitive: String, val toArray: Boolean) {
    TO_ARRAY("copyAddrToByteArray#", true),
    FROM_ARRAY("copyByteArrayToAddr#", false),
    FROM_MUTABLE_ARRAY("copyMutableByteArrayToAddr#", false);

    fun validate(arguments: List<CoreRepresentation>, flags: List<*>, result: CoreRepresentation) {
        val kinds = if (toArray) listOf(CoreKind.ADDRESS, CoreKind.OBJECT, CoreKind.LONG, CoreKind.LONG, CoreKind.VOID)
            else listOf(CoreKind.OBJECT, CoreKind.LONG, CoreKind.ADDRESS, CoreKind.LONG, CoreKind.VOID)
        if (arguments.size != kinds.size || flags != List(kinds.size) { false } ||
            arguments.indices.any { arguments[it].kind != kinds[it] || arguments[it].isAggregate || arguments[it].isVector })
            fault("Array/address copy operand carrier or arity mismatch: $primitive")
        if (result.kind != CoreKind.VOID || result.isAggregate || result.isVector)
            fault("Array/address copy requires scalar State#: $primitive")
    }

    companion object { fun named(name: String): AddressArrayCopyOp? = entries.firstOrNull { it.primitive == name } }
}

/** Fixed adopted children preserve evaluation order, including State before
 * validation or effects. The storage layer owns range, identity and lifetime. */
internal class AddressToByteArrayExpression(proof: CoreRepresentation,
    @field:Child private var source: Expr, @field:Child private var destination: Expr,
    @field:Child private var offset: Expr, @field:Child private var count: Expr,
    @field:Child private var state: Expr) : Expr() {
    init { representation = proof.copy(evaluated = true) }
    override fun execute(frame: VirtualFrame): Any {
        val from = source.executeRequiredAddress(frame)
        val to = destination.execute(frame)
        val start = offset.executeRequiredLong(frame)
        val length = count.executeRequiredLong(frame)
        requireVoidCarrier(state.execute(frame))
        from.copyToByteArray(to, start, length)
        return Unit
    }
}

internal class ByteArrayToAddressExpression(proof: CoreRepresentation,
    @field:Child private var source: Expr, @field:Child private var offset: Expr,
    @field:Child private var destination: Expr, @field:Child private var count: Expr,
    @field:Child private var state: Expr) : Expr() {
    init { representation = proof.copy(evaluated = true) }
    override fun execute(frame: VirtualFrame): Any {
        val from = source.execute(frame)
        val start = offset.executeRequiredLong(frame)
        val to = destination.executeRequiredAddress(frame)
        val length = count.executeRequiredLong(frame)
        requireVoidCarrier(state.execute(frame))
        to.copyFromByteArray(from, start, length)
        return Unit
    }
}
