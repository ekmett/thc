// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime

import com.oracle.truffle.api.frame.VirtualFrame

/** Original ghc-internal primFloat.c declarations, not arbitrary libm calls. */
internal enum class FloatForeignOp(val symbol: String, val single: Boolean, val rounding: Boolean = false) {
    FLOAT_NAN("isFloatNaN", true), FLOAT_INFINITE("isFloatInfinite", true),
    FLOAT_FINITE("isFloatFinite", true), FLOAT_DENORMAL("isFloatDenormalized", true),
    FLOAT_NEGATIVE_ZERO("isFloatNegativeZero", true), FLOAT_ROUND("rintFloat", true, true),
    DOUBLE_NAN("isDoubleNaN", false), DOUBLE_INFINITE("isDoubleInfinite", false),
    DOUBLE_FINITE("isDoubleFinite", false), DOUBLE_DENORMAL("isDoubleDenormalized", false),
    DOUBLE_NEGATIVE_ZERO("isDoubleNegativeZero", false), DOUBLE_ROUND("rintDouble", false, true);

    val argumentRep = if (single) "FloatRep" else "DoubleRep"
    val resultRep = if (rounding) argumentRep else "IntRep"

    fun classify(value: Float): Long = if (when (this) {
        FLOAT_NAN -> value.isNaN()
        FLOAT_INFINITE -> value.isInfinite()
        FLOAT_FINITE -> value.isFinite()
        FLOAT_DENORMAL -> (value.toRawBits() and 0x7fffffff) in 1..0x7fffff
        FLOAT_NEGATIVE_ZERO -> value.toRawBits() == Int.MIN_VALUE
        else -> fault("Expected a Float classification")
    }) 1L else 0L

    fun classify(value: Double): Long = if (when (this) {
        DOUBLE_NAN -> value.isNaN()
        DOUBLE_INFINITE -> value.isInfinite()
        DOUBLE_FINITE -> value.isFinite()
        DOUBLE_DENORMAL -> (value.toRawBits() and Long.MAX_VALUE) in 1L..0xfffffffffffffL
        DOUBLE_NEGATIVE_ZERO -> value.toRawBits() == Long.MIN_VALUE
        else -> fault("Expected a Double classification")
    }) 1L else 0L

    companion object {
        fun named(symbol: Any?) = entries.firstOrNull { it.symbol == symbol }
        // GHC deliberately canonicalizes small results to +0 and returns large
        // values/NaNs unchanged; do not quiet a NaN through an arithmetic op.
        @JvmStatic fun round(value: Float): Float = when {
            !value.isFinite() || kotlin.math.abs(value) >= 8388608.0f -> value
            kotlin.math.abs(value) <= 0.5f -> 0.0f
            else -> Math.rint(value.toDouble()).toFloat()
        }
        @JvmStatic fun round(value: Double): Double = when {
            !value.isFinite() || kotlin.math.abs(value) >= 4503599627370496.0 -> value
            kotlin.math.abs(value) <= 0.5 -> 0.0
            else -> Math.rint(value)
        }
    }
}

internal object CoreFloatForeign {
    private val scalarKeys = setOf("kind", "primReps", "evaluated")
    private val tupleKeys = scalarKeys + setOf("aggregate", "components")
    private val descriptorKeys = setOf("schema", "target", "convention", "safety", "arity", "suppliedArity", "argumentReps", "resultRep")
    private fun requireProof(valid: Boolean, detail: String) {
        if (!valid) fault("Invalid original floating call: $detail")
    }
    private fun exact(value: Any?, n: Int) = (value is Int || value is Long) && (value as Number).toLong() == n.toLong()
    private fun kind(rep: String?) = when (rep) {
        null -> "void"; "FloatRep" -> "float"; "DoubleRep" -> "double"
        "BoxedRep (Just Lifted)" -> "closure"; else -> "long"
    }
    private fun scalar(raw: Any?, rep: String?, evaluated: Boolean? = null): Boolean {
        val proof = raw as? Map<*, *> ?: return false
        return proof.keys == scalarKeys && proof["kind"] == kind(rep) &&
            proof["primReps"] == listOfNotNull(rep) && proof["evaluated"] is Boolean &&
            (evaluated == null || proof["evaluated"] == evaluated)
    }
    private fun result(raw: Any?, rep: String, declared: Boolean = false): Boolean {
        val proof = raw as? Map<*, *> ?: return false
        val components = proof["components"] as? List<*> ?: return false
        return proof.keys == tupleKeys && proof["kind"] == "unknown" && proof["aggregate"] == "unboxed-tuple" &&
            proof["primReps"] == listOf(rep) && proof["evaluated"] is Boolean &&
            (!declared || proof["evaluated"] == false) && components.size == 2 &&
            scalar(components[0], null, true) && scalar(components[1], rep, true)
    }
    fun validate(metadata: Any?, arguments: List<*>, flags: List<*>, resultProof: Any?): FloatForeignOp? {
        val meta = metadata as? Map<*, *> ?: return null
        val call = meta["foreignCall"] as? Map<*, *> ?: return null
        val target = call["target"] as? Map<*, *> ?: return null
        val operation = FloatForeignOp.named(target["symbol"]) ?: return null
        requireProof(call.keys == descriptorKeys && exact(call["schema"], 1), "descriptor schema")
        requireProof(target.keys == setOf("kind", "symbol", "unit", "isFunction") &&
            target["kind"] == "static" && target["unit"] == "ghc-internal" && target["isFunction"] == true,
            "exact original installed target")
        requireProof(call["convention"] == "ccall" && call["safety"] == "unsafe" &&
            exact(call["arity"], 2) && exact(call["suppliedArity"], 2), "convention, safety or arity")
        val declared = call["argumentReps"] as? List<*>
        requireProof(declared?.size == 2 && scalar(declared[0], operation.argumentRep, false) &&
            scalar(declared[1], null, false) && arguments.size == 2 &&
            scalar(arguments[0], operation.argumentRep) && scalar(arguments[1], null) && flags == listOf(false, false),
            "floating and State# arguments")
        requireProof(result(call["resultRep"], operation.resultRep, true) && result(meta["rep"], operation.resultRep) &&
            result(resultProof, operation.resultRep), "State#/scalar tuple result")
        return operation
    }
    fun validateHead(function: List<Any?>, defined: Boolean) {
        requireProof(function.size == 3 && function[0] == "var" && function[1] is String &&
            (function[1] as String).isNotEmpty() && !defined &&
            scalar(CoreRepresentations.metadata(function)?.get("rep"), "BoxedRep (Just Lifted)", true),
            "unresolved original FCallId required")
    }
    fun validateOperand(operation: FloatForeignOp, index: Int, lowered: CoreRepresentation, stored: CoreRepresentation?) {
        val rep = if (index == 0) operation.argumentRep else null
        requireProof(index in 0..1 && lowered.present && !lowered.isAggregate && !lowered.isVector &&
            lowered.kind.name.lowercase() == kind(rep) && lowered.primReps == listOfNotNull(rep), "lowered operand $index")
        if (stored != null && stored.present) requireProof(!stored.isAggregate && !stored.isVector &&
            stored.kind.name.lowercase() in setOf(kind(rep), "unknown") &&
            (stored.primReps == null || stored.primReps == listOfNotNull(rep)), "stored operand $index")
    }
}

internal class FloatForeignExpression(private val operation: FloatForeignOp,
    @field:Child private var value: Expr, @field:Child private var state: Expr, proof: CoreRepresentation) : Expr() {
    init { representation = proof.copy(evaluated = true) }
    override fun execute(frame: VirtualFrame): Nothing = fault("Floating foreign call requires a tuple destination")
    override fun executeTuple(frame: VirtualFrame, slots: IntArray, offset: Int): Any? {
        if (operation.single) {
            val argument = value.executeRequiredFloat(frame)
            requireVoidCarrier(state.execute(frame))
            if (operation.rounding) FrameAccess.writeFloat(frame, slots[offset], FloatForeignOp.round(argument))
            else FrameAccess.writeLong(frame, slots[offset], operation.classify(argument))
        } else {
            val argument = value.executeRequiredDouble(frame)
            requireVoidCarrier(state.execute(frame))
            if (operation.rounding) FrameAccess.writeDouble(frame, slots[offset], FloatForeignOp.round(argument))
            else FrameAccess.writeLong(frame, slots[offset], operation.classify(argument))
        }
        return null
    }
}
