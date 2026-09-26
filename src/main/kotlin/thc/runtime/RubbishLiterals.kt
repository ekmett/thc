// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime

import com.oracle.truffle.api.TruffleLanguage
import com.oracle.truffle.api.nodes.RootNode

/** GHC's LitRubbish is a non-bottom, same-representation absent filler.
 * Its payload must never be observed. Constants are allocated at load time;
 * neither lowering nor forcing a filler performs guest work or raises bottom.
 * Aggregate/vector rubbish needs GHC's unarising/layout rules, not this ABI. */
internal class RubbishLiterals(private val language: TruffleLanguage<*>?) {
    private val boxed by lazy {
        DataLayout(language ?: fault("Boxed rubbish requires a guest language"),
            "THC.Rubbish", "<absent>", emptyArray()).create(emptyArray())
    }
    // A known function type still uses the runtime's checked closure carrier.
    // Calling an absent function is outside LitRubbish's contract; this inert
    // target merely gives it a well-formed carrier, without a guest call now.
    private val closure by lazy { Closure(null, arity = 1, target = RootNode.createConstantNode(boxed).callTarget) }

    fun decode(proof: CoreRepresentation): Any = when (proof.kind) {
        CoreKind.LONG -> 0L
        CoreKind.FLOAT -> 0.0f
        CoreKind.DOUBLE -> 0.0
        CoreKind.ADDRESS -> ManagedAddress.nullAddress()
        CoreKind.CLOSURE -> closure
        CoreKind.DATA, CoreKind.OBJECT -> boxed
        else -> throw UnsupportedCore("Unsupported rubbish representation")
    }

    companion object {
        private val kinds = mapOf(
            "IntRep" to CoreKind.LONG, "Int8Rep" to CoreKind.LONG, "Int16Rep" to CoreKind.LONG,
            "Int32Rep" to CoreKind.LONG, "Int64Rep" to CoreKind.LONG,
            "WordRep" to CoreKind.LONG, "Word8Rep" to CoreKind.LONG, "Word16Rep" to CoreKind.LONG,
            "Word32Rep" to CoreKind.LONG, "Word64Rep" to CoreKind.LONG,
            "FloatRep" to CoreKind.FLOAT, "DoubleRep" to CoreKind.DOUBLE, "AddrRep" to CoreKind.ADDRESS,
            "BoxedRep (Just Lifted)" to CoreKind.OBJECT, "BoxedRep (Just Unlifted)" to CoreKind.OBJECT)

        fun proof(expression: List<Any?>): CoreRepresentation {
            val expected = kinds[expression.getOrNull(2)]
                ?: throw UnsupportedCore("Unsupported rubbish representation: ${expression.getOrNull(2)}")
            val actual = CoreRepresentations.parse(CoreRepresentations.metadata(expression)?.get("rep"))
            val matchingKind = actual.kind == expected || expected == CoreKind.OBJECT &&
                actual.kind in setOf(CoreKind.DATA, CoreKind.CLOSURE)
            if (!actual.present || !actual.evaluated || !matchingKind || actual.isAggregate || actual.isVector ||
                actual.primReps != listOf(expression[2]))
                throw RuntimeFault("Rubbish literal requires explicit exact evaluated scalar representation")
            return actual
        }
    }
}
