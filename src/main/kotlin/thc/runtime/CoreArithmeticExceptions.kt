// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime

import com.oracle.truffle.api.frame.VirtualFrame
import com.oracle.truffle.api.nodes.Node.Child

/** GHC's wired arithmetic raises retain the original ghc-internal SomeException CAFs. */
internal object CoreArithmeticExceptions {
    private const val exceptionType = "ghc-internal:GHC.Internal.Exception.Type."
    private val payloads = mapOf(
        "raiseDivZero#" to exceptionType + "divZeroException",
        "raiseUnderflow#" to exceptionType + "underflowException",
        "raiseOverflow#" to exceptionType + "overflowException"
    )

    fun payload(name: String): String? = payloads[name]

    fun validate(name: String, arguments: List<CoreRepresentation>, flags: List<*>, result: CoreRepresentation) {
        if (payload(name) == null || arguments.size != 1 || flags != listOf(false) || !arguments[0].isEmptyTuple)
            throw RuntimeFault("$name: expected one exact unlifted (# #) argument")
        CoreRepresentations.requireNoSum(result, name)
        CoreRepresentations.requireNoVector(result, name)
        if (result.isTuple) TupleShape.validate(result)
    }
}

/** Evaluate the genuine empty-tuple operand before raising the lazy guest payload. */
internal class RaiseArithmeticException(private val payload: GlobalBinding,
    @field:Child private var empty: Expr, proof: CoreRepresentation) : Expr() {
    init { representation = proof.copy(evaluated = true) }

    override fun execute(frame: VirtualFrame): Nothing {
        empty.executeTuple(frame, IntArray(0), 0)
        throw GuestException(payload.read(), this)
    }

    override fun executeTuple(frame: VirtualFrame, slots: IntArray, offset: Int): Nothing = execute(frame)
}
