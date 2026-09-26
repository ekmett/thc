// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal
import com.oracle.truffle.api.bytecode.BytecodeNode
import com.oracle.truffle.api.bytecode.LocalAccessor
import com.oracle.truffle.api.frame.VirtualFrame
import com.oracle.truffle.api.nodes.ExplodeLoop
import thc.PackageScalarLink
import thc.PackageScalarSignature

internal class PackageScalarCall(val link: PackageScalarLink, val signature: PackageScalarSignature) {
    @field:CompilationFinal(dimensions = 1) val arguments = signature.arguments.toTypedArray()
    val result = signature.result
}

internal object CorePackageScalarForeign {
    private fun check(value: Boolean, detail: String) {
        if (!value) fault("Invalid package scalar call: $detail")
    }
    private fun number(value: Any?, wanted: Int) = value == wanted || value == wanted.toLong()
    private fun kind(rep: String?): CoreKind = when (rep) {
        null -> CoreKind.VOID
        "FloatRep" -> CoreKind.FLOAT
        "DoubleRep" -> CoreKind.DOUBLE
        "AddrRep" -> CoreKind.ADDRESS
        "ByteArray#", "MutableByteArray#" -> CoreKind.OBJECT
        else -> CoreKind.LONG
    }
    private fun primReps(rep: String?): List<String> = when (rep) {
        null -> emptyList()
        "ByteArray#", "MutableByteArray#" -> listOf("BoxedRep (Just Unlifted)")
        else -> listOf(rep)
    }
    private fun scalar(value: Any?, rep: String?, declared: Boolean = false): Boolean {
        val raw = value as? Map<*, *> ?: return false
        return raw.keys == setOf("kind", "primReps", "evaluated") &&
            raw["kind"] == kind(rep).name.lowercase() && raw["primReps"] == primReps(rep) &&
            raw["evaluated"] is Boolean && (!declared || raw["evaluated"] == false)
    }
    private fun result(value: Any?, rep: String, declared: Boolean = false): Boolean {
        val raw = value as? Map<*, *> ?: return false
        val fields = raw["components"] as? List<*> ?: return false
        val reps = if (rep == "void") listOf(null) else listOf(null, rep)
        return raw.keys == setOf("kind", "primReps", "evaluated", "aggregate", "components") &&
            raw["kind"] == "unknown" && raw["aggregate"] == "unboxed-tuple" && raw["primReps"] == reps.filterNotNull() &&
            raw["evaluated"] is Boolean && (!declared || raw["evaluated"] == false) && fields.size == reps.size &&
            fields.indices.all { scalar(fields[it], reps[it]) && (fields[it] as Map<*, *>)["evaluated"] == true }
    }
    fun validate(metadata: Any?, arguments: List<*>, flags: List<*>, output: Any?,
                 links: List<PackageScalarLink>): PackageScalarCall? {
        val meta = metadata as? Map<*, *> ?: return null
        val descriptor = meta["foreignCall"] as? Map<*, *> ?: return null
        val target = descriptor["target"] as? Map<*, *> ?: return null
        val link = links.singleOrNull { it.unit == target["unit"] } ?: return null
        val declared = descriptor["argumentReps"] as? List<*> ?: fault("Missing package C declared arguments")
        val signature = link.abi.singleOrNull { candidate ->
            val expected = candidate.arguments + null
            candidate.symbol == target["symbol"] && candidate.convention == descriptor["convention"] &&
                declared.size == expected.size && expected.indices.all { scalar(declared[it], expected[it], true) } &&
                result(descriptor["resultRep"], candidate.result, true)
        } ?: fault("Unlinked or ambiguous package C signature in scalar component: ${target["symbol"]}")
        check(descriptor.keys == setOf("schema", "target", "convention", "safety", "arity", "suppliedArity", "argumentReps", "resultRep") &&
            number(descriptor["schema"], 1) && target.keys == setOf("kind", "symbol", "unit", "isFunction") &&
            target["kind"] == "static" && target["isFunction"] == true &&
            descriptor["convention"] == signature.convention && descriptor["safety"] == "unsafe", "static unsafe declaration")
        val expected = signature.arguments + null
        check(number(descriptor["arity"], expected.size) && number(descriptor["suppliedArity"], expected.size) &&
            arguments.size == expected.size && declared.size == expected.size && flags == List(expected.size) { false } &&
            expected.indices.all { scalar(declared[it], expected[it], true) && scalar(arguments[it], expected[it]) } &&
            result(descriptor["resultRep"], signature.result, true) && result(meta["rep"], signature.result) &&
            result(output, signature.result), "exact scalar arguments and State/result tuple")
        return PackageScalarCall(link, signature)
    }
    fun validateOperand(call: PackageScalarCall, index: Int, lowered: CoreRepresentation, stored: CoreRepresentation?) {
        val rep = call.arguments.getOrNull(index)
        check(lowered.present && !lowered.isAggregate && !lowered.isVector &&
            lowered.kind == kind(rep) && lowered.primReps == primReps(rep), "lowered operand $index")
        if (stored?.present == true) check(!stored.isAggregate && !stored.isVector &&
            stored.kind in setOf(kind(rep), CoreKind.UNKNOWN) &&
            (stored.primReps == null || stored.primReps == primReps(rep)), "stored operand $index")
    }
}

internal class PackageScalarExpression(private val call: PackageScalarCall,
    @field:Children private var operands: Array<Expr>, proof: CoreRepresentation) : Expr() {
    @Child private var access = PackageScalarAccess(call)
    init { representation = proof.copy(evaluated = true) }
    override fun execute(frame: VirtualFrame): Nothing = fault("Package C call requires its State/result tuple")
    @ExplodeLoop override fun executeTuple(frame: VirtualFrame, slots: IntArray, offset: Int): Any? {
        val values = arrayOfNulls<Any>(call.arguments.size)
        for (index in values.indices) values[index] = when (call.arguments[index]) {
            "Int8Rep", "Word8Rep", "Int16Rep", "Word16Rep", "Int32Rep", "Word32Rep" ->
                packageCInteger(call.arguments[index], operands[index].executeRequiredLong(frame))
            "IntRep", "WordRep", "Int64Rep", "Word64Rep" -> operands[index].executeRequiredLong(frame)
            "FloatRep" -> operands[index].executeRequiredFloat(frame)
            "DoubleRep" -> operands[index].executeRequiredDouble(frame)
            "AddrRep", "ByteArray#", "MutableByteArray#" -> operands[index].execute(frame)
            else -> fault("Invalid package C operand")
        }
        val state = operands.last().execute(frame)
        when (call.result) {
            "IntRep", "WordRep", "Int8Rep", "Word8Rep", "Int16Rep", "Word16Rep", "Int32Rep", "Word32Rep", "Int64Rep", "Word64Rep" ->
                FrameAccess.writeLong(frame, slots[offset], access.executeLong(values, state))
            "FloatRep" -> FrameAccess.writeFloat(frame, slots[offset], access.executeFloat(values, state))
            "DoubleRep" -> FrameAccess.writeDouble(frame, slots[offset], access.executeDouble(values, state))
            "AddrRep" -> FrameAccess.write(frame, slots[offset], access.executeAddress(values, state))
            "void" -> access.executeVoid(values, state)
            else -> fault("Invalid package C result")
        }
        return null
    }
}

/** Keep every input in its exact primitive local until the interop call. */
internal class BytecodePackageScalarArguments(val call: PackageScalarCall,
    @field:CompilationFinal(dimensions = 1) private val slots: Array<LocalAccessor>, private val state: LocalAccessor) {
    @ExplodeLoop fun read(bytecode: BytecodeNode, frame: VirtualFrame): Array<Any?> {
        val values = arrayOfNulls<Any>(slots.size)
        for (index in slots.indices) values[index] = when (call.arguments[index]) {
            "Int8Rep", "Word8Rep", "Int16Rep", "Word16Rep", "Int32Rep", "Word32Rep" ->
                packageCInteger(call.arguments[index], slots[index].getLong(bytecode, frame))
            "IntRep", "WordRep", "Int64Rep", "Word64Rep" -> slots[index].getLong(bytecode, frame)
            "FloatRep" -> slots[index].getFloat(bytecode, frame)
            "DoubleRep" -> slots[index].getDouble(bytecode, frame)
            "AddrRep", "ByteArray#", "MutableByteArray#" -> slots[index].getObject(bytecode, frame)
            else -> fault("Invalid package C argument local")
        }
        return values
    }
    fun state(bytecode: BytecodeNode, frame: VirtualFrame): Any? = state.getObject(bytecode, frame)
}
