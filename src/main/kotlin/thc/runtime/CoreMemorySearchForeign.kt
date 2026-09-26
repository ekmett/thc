// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime

import com.oracle.truffle.api.frame.VirtualFrame

internal enum class MemorySearchOp(val symbol: String, val second: String, val result: String) {
    COMPARE("memcmp", "AddrRep", "Int32Rep"), FIND("memchr", "Int32Rep", "AddrRep");
    val arguments = listOf("AddrRep", second, "Word64Rep", null)
}

// Preserve the original FCall's full installed unit ID; the supported ABI is
// tied to this ByteString release, independently of its installation suffix.
private val byteStringUnit = Regex("bytestring-0\\.12\\.2\\.0(?:-[A-Za-z0-9]+)?")
internal fun isOriginalByteStringUnit(unit: Any?): Boolean = unit is String && byteStringUnit.matches(unit)

/** Original installed CInt/CSize declarations; no arbitrary libc ABI inference. */
internal object CoreMemorySearchForeign {
    private val scalarKeys = setOf("kind", "primReps", "evaluated")
    private val tupleKeys = scalarKeys + setOf("aggregate", "components")
    private val descriptorKeys = setOf("schema", "target", "convention", "safety", "arity", "suppliedArity", "argumentReps", "resultRep")
    private fun requireProof(valid: Boolean, detail: String) {
        if (!valid) fault("Invalid original memory search call: $detail")
    }
    private fun exact(value: Any?, n: Int) = (value is Int || value is Long) && (value as Number).toLong() == n.toLong()
    private fun kind(rep: String?) = when (rep) {
        null -> "void"; "AddrRep" -> "address"; "BoxedRep (Just Lifted)" -> "closure"; else -> "long"
    }
    private fun scalar(raw: Any?, rep: String?, evaluated: Boolean? = null): Boolean {
        val proof = raw as? Map<*, *> ?: return false
        return proof.keys == scalarKeys && proof["kind"] == kind(rep) &&
            proof["primReps"] == listOfNotNull(rep) && proof["evaluated"] is Boolean &&
            (evaluated == null || proof["evaluated"] == evaluated)
    }
    private fun result(raw: Any?, rep: String, declared: Boolean = false): Boolean {
        val proof = raw as? Map<*, *> ?: return false
        val fields = proof["components"] as? List<*> ?: return false
        return proof.keys == tupleKeys && proof["kind"] == "unknown" && proof["aggregate"] == "unboxed-tuple" &&
            proof["primReps"] == listOf(rep) && proof["evaluated"] is Boolean &&
            (!declared || proof["evaluated"] == false) && fields.size == 2 &&
            scalar(fields[0], null, true) && scalar(fields[1], rep, true)
    }
    fun validate(metadata: Any?, arguments: List<*>, flags: List<*>, resultProof: Any?): MemorySearchOp? {
        val meta = metadata as? Map<*, *> ?: return null
        val call = meta["foreignCall"] as? Map<*, *> ?: return null
        val target = call["target"] as? Map<*, *> ?: return null
        val unit = target["unit"]
        val operation = MemorySearchOp.entries.firstOrNull { it.symbol == target["symbol"] } ?: return null
        requireProof(call.keys == descriptorKeys && exact(call["schema"], 1), "descriptor schema")
        requireProof(target.keys == setOf("kind", "symbol", "unit", "isFunction") &&
            target["kind"] == "static" && target["isFunction"] == true &&
            (isOriginalByteStringUnit(unit) ||
                operation == MemorySearchOp.COMPARE && unit == "ghc-internal"), "supported installed target")
        requireProof(call["convention"] == "ccall" && call["safety"] == "unsafe" &&
            exact(call["arity"], 4) && exact(call["suppliedArity"], 4), "convention, safety or arity")
        val declared = call["argumentReps"] as? List<*>
        requireProof(declared?.size == 4 && operation.arguments.indices.all {
            scalar(declared[it], operation.arguments[it], false) } && arguments.size == 4 &&
            operation.arguments.indices.all { scalar(arguments[it], operation.arguments[it]) } &&
            flags == listOf(false, false, false, false), "address, CInt/CSize and State# operands")
        requireProof(result(call["resultRep"], operation.result, true) && result(meta["rep"], operation.result) &&
            result(resultProof, operation.result), "State#/result tuple")
        return operation
    }
    fun validateHead(function: List<Any?>, defined: Boolean) {
        requireProof(function.size == 3 && function[0] == "var" && function[1] is String &&
            (function[1] as String).isNotEmpty() && !defined &&
            scalar(CoreRepresentations.metadata(function)?.get("rep"), "BoxedRep (Just Lifted)", true),
            "unresolved original FCallId required")
    }
    fun validateOperand(operation: MemorySearchOp, index: Int, lowered: CoreRepresentation, stored: CoreRepresentation?) {
        val rep = operation.arguments[index]
        requireProof(lowered.present && !lowered.isAggregate && !lowered.isVector &&
            lowered.kind.name.lowercase() == kind(rep) && lowered.primReps == listOfNotNull(rep), "lowered operand $index")
        if (stored != null && stored.present) requireProof(!stored.isAggregate && !stored.isVector &&
            stored.kind.name.lowercase() in setOf(kind(rep), "unknown") &&
            (stored.primReps == null || stored.primReps == listOfNotNull(rep)), "stored operand $index")
    }
}

internal class MemorySearchExpression(private val operation: MemorySearchOp,
    @field:Children private var operands: Array<Expr>, proof: CoreRepresentation) : Expr() {
    init { representation = proof.copy(evaluated = true) }
    override fun execute(frame: VirtualFrame): Nothing = fault("Memory search requires a tuple destination")
    override fun executeTuple(frame: VirtualFrame, slots: IntArray, offset: Int): Any? {
        val first = operands[0].executeRequiredAddress(frame)
        if (operation == MemorySearchOp.COMPARE) {
            val second = operands[1].executeRequiredAddress(frame)
            val count = operands[2].executeRequiredLong(frame)
            requireVoidCarrier(operands[3].execute(frame))
            FrameAccess.writeLong(frame, slots[offset], first.compareBytes(second, count))
        } else {
            val needle = operands[1].executeRequiredLong(frame)
            val count = operands[2].executeRequiredLong(frame)
            requireVoidCarrier(operands[3].execute(frame))
            FrameAccess.writeObject(frame, slots[offset], first.findByte(needle, count))
        }
        return null
    }
}
