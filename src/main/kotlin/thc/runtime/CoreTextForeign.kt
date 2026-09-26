// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime

import com.oracle.truffle.api.frame.VirtualFrame

/** Exact original text-2.1.3 declarations, including their hidden State argument. */
internal enum class TextForeignOp(val symbol: String, val lastRep: String) {
    MEMCHR("_hs_text_memchr", "Word8Rep"),
    MEASURE("_hs_text_measure_off", "Word64Rep");
    val arguments: List<String?> get() = listOf("BoxedRep (Just Unlifted)", "Word64Rep", "Word64Rep", lastRep, null)
}

internal object CoreTextForeign {
    private val scalarKeys = setOf("kind", "primReps", "evaluated")
    private val tupleKeys = scalarKeys + setOf("aggregate", "components")
    private val descriptorKeys = setOf("schema", "target", "convention", "safety", "arity", "suppliedArity", "argumentReps", "resultRep")
    private fun requireProof(valid: Boolean, detail: String) {
        if (!valid) fault("Invalid original text call: $detail")
    }
    private fun exact(value: Any?, n: Int) = (value is Int || value is Long) && (value as Number).toLong() == n.toLong()
    private fun kind(rep: String?) = when (rep) {
        null -> "void"; "BoxedRep (Just Unlifted)" -> "object"; "BoxedRep (Just Lifted)" -> "closure"; else -> "long"
    }
    private fun scalar(raw: Any?, rep: String?, evaluated: Boolean? = null): Boolean {
        val proof = raw as? Map<*, *> ?: return false
        return proof.keys == scalarKeys && proof["kind"] == kind(rep) &&
            proof["primReps"] == listOfNotNull(rep) && proof["evaluated"] is Boolean &&
            (evaluated == null || proof["evaluated"] == evaluated)
    }
    private fun result(raw: Any?, declared: Boolean = false): Boolean {
        val proof = raw as? Map<*, *> ?: return false
        val components = proof["components"] as? List<*> ?: return false
        return proof.keys == tupleKeys && proof["kind"] == "unknown" && proof["aggregate"] == "unboxed-tuple" &&
            proof["primReps"] == listOf("Int64Rep") && proof["evaluated"] is Boolean &&
            (!declared || proof["evaluated"] == false) && components.size == 2 &&
            scalar(components[0], null, true) && scalar(components[1], "Int64Rep", true)
    }
    fun validate(metadata: Any?, arguments: List<*>, flags: List<*>, resultProof: Any?): TextForeignOp? {
        val meta = metadata as? Map<*, *> ?: return null
        val call = meta["foreignCall"] as? Map<*, *> ?: return null
        val target = call["target"] as? Map<*, *> ?: return null
        val operation = TextForeignOp.entries.firstOrNull { it.symbol == target["symbol"] } ?: return null
        requireProof(call.keys == descriptorKeys && exact(call["schema"], 1), "descriptor schema")
        requireProof(target.keys == setOf("kind", "symbol", "unit", "isFunction") &&
            target["kind"] == "static" && target["unit"] == "text-2.1.3-inplace" && target["isFunction"] == true,
            "exact original installed text target")
        requireProof(call["convention"] == "ccall" && call["safety"] == "unsafe" &&
            exact(call["arity"], 5) && exact(call["suppliedArity"], 5), "convention, safety or arity")
        val declared = call["argumentReps"] as? List<*>
        requireProof(declared?.size == 5 && arguments.size == 5 && flags == List(5) { false } &&
            operation.arguments.indices.all { scalar(declared[it], operation.arguments[it], false) &&
                scalar(arguments[it], operation.arguments[it]) }, "ByteArray#/size/byte/State arguments")
        requireProof(result(call["resultRep"], true) && result(meta["rep"]) && result(resultProof), "State#/Int64 tuple result")
        return operation
    }
    fun validateHead(function: List<Any?>, defined: Boolean) {
        requireProof(function.size == 3 && function[0] == "var" && function[1] is String &&
            (function[1] as String).isNotEmpty() && !defined &&
            scalar(CoreRepresentations.metadata(function)?.get("rep"), "BoxedRep (Just Lifted)", true),
            "unresolved original FCallId required")
    }
    fun validateOperand(operation: TextForeignOp, index: Int, lowered: CoreRepresentation, stored: CoreRepresentation?) {
        val rep = operation.arguments[index]
        requireProof(lowered.present && !lowered.isAggregate && !lowered.isVector &&
            lowered.kind.name.lowercase() == kind(rep) && lowered.primReps == listOfNotNull(rep), "lowered operand $index")
        if (stored != null && stored.present) requireProof(!stored.isAggregate && !stored.isVector &&
            stored.kind.name.lowercase() in setOf(kind(rep), "unknown") &&
            (stored.primReps == null || stored.primReps == listOfNotNull(rep)), "stored operand $index")
    }
}

internal class TextForeignExpression(private val operation: TextForeignOp,
    @field:Children private var operands: Array<Expr>, proof: CoreRepresentation) : Expr() {
    init { representation = proof.copy(evaluated = true) }
    override fun execute(frame: VirtualFrame): Nothing = fault("Text foreign call requires a tuple destination")
    override fun executeTuple(frame: VirtualFrame, slots: IntArray, offset: Int): Any? {
        val bytes = operands[0].execute(frame)
        val start = operands[1].executeRequiredLong(frame)
        val length = operands[2].executeRequiredLong(frame)
        val count = operands[3].executeRequiredLong(frame)
        requireVoidCarrier(operands[4].execute(frame))
        FrameAccess.writeLong(frame, slots[offset], ManagedText.invoke(operation, bytes, start, length, count))
        return null
    }
}
