// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal
import com.oracle.truffle.api.bytecode.BytecodeNode
import com.oracle.truffle.api.bytecode.LocalAccessor
import com.oracle.truffle.api.frame.VirtualFrame
import com.oracle.truffle.api.nodes.ExplodeLoop
import com.oracle.truffle.api.nodes.Node
import thc.Language
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
        else -> CoreKind.LONG
    }
    private fun scalar(value: Any?, rep: String?, declared: Boolean = false): Boolean {
        val raw = value as? Map<*, *> ?: return false
        return raw.keys == setOf("kind", "primReps", "evaluated") &&
            raw["kind"] == kind(rep).name.lowercase() && raw["primReps"] == listOfNotNull(rep) &&
            raw["evaluated"] is Boolean && (!declared || raw["evaluated"] == false)
    }
    private fun result(value: Any?, rep: String, declared: Boolean = false): Boolean {
        val raw = value as? Map<*, *> ?: return false
        val fields = raw["components"] as? List<*> ?: return false
        return raw.keys == setOf("kind", "primReps", "evaluated", "aggregate", "components") &&
            raw["kind"] == "unknown" && raw["aggregate"] == "unboxed-tuple" && raw["primReps"] == listOf(rep) &&
            raw["evaluated"] is Boolean && (!declared || raw["evaluated"] == false) && fields.size == 2 &&
            scalar(fields[0], null) && scalar(fields[1], rep) &&
            (fields[0] as Map<*, *>)["evaluated"] == true && (fields[1] as Map<*, *>)["evaluated"] == true
    }
    fun validate(metadata: Any?, arguments: List<*>, flags: List<*>, output: Any?,
                 links: List<PackageScalarLink>): PackageScalarCall? {
        val meta = metadata as? Map<*, *> ?: return null
        val descriptor = meta["foreignCall"] as? Map<*, *> ?: return null
        val target = descriptor["target"] as? Map<*, *> ?: return null
        val link = links.singleOrNull { it.unit == target["unit"] } ?: return null
        val signature = link.abi.singleOrNull { it.symbol == target["symbol"] }
            ?: fault("Unlinked package C symbol in scalar component: ${target["symbol"]}")
        check(descriptor.keys == setOf("schema", "target", "convention", "safety", "arity", "suppliedArity", "argumentReps", "resultRep") &&
            number(descriptor["schema"], 1) && target.keys == setOf("kind", "symbol", "unit", "isFunction") &&
            target["kind"] == "static" && target["isFunction"] == true &&
            descriptor["convention"] == "ccall" && descriptor["safety"] == "unsafe", "static unsafe declaration")
        val expected = signature.arguments + null
        val declared = descriptor["argumentReps"] as? List<*> ?: fault("Missing package C declared arguments")
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
            lowered.kind == kind(rep) && lowered.primReps == listOfNotNull(rep), "lowered operand $index")
        if (stored?.present == true) check(!stored.isAggregate && !stored.isVector &&
            stored.kind in setOf(kind(rep), CoreKind.UNKNOWN) &&
            (stored.primReps == null || stored.primReps == listOfNotNull(rep)), "stored operand $index")
    }
    @JvmStatic fun invoke(node: Node, call: PackageScalarCall, values: Array<Any?>, state: Any?): Any {
        requireVoidCarrier(state)
        return Language.currentState(node).packageCbits.call(call.link, call.signature, values)
    }
}

internal class PackageScalarExpression(private val call: PackageScalarCall,
    @field:Children private var operands: Array<Expr>, proof: CoreRepresentation) : Expr() {
    init { representation = proof.copy(evaluated = true) }
    override fun execute(frame: VirtualFrame): Nothing = fault("Package C call requires its State/result tuple")
    @ExplodeLoop override fun executeTuple(frame: VirtualFrame, slots: IntArray, offset: Int): Any? {
        val values = arrayOfNulls<Any>(call.arguments.size)
        for (index in values.indices) values[index] = when (call.arguments[index]) {
            "Int32Rep", "Int64Rep" -> operands[index].executeRequiredLong(frame)
            "FloatRep" -> operands[index].executeRequiredFloat(frame)
            "DoubleRep" -> operands[index].executeRequiredDouble(frame)
            else -> fault("Invalid package C operand")
        }
        val result = CorePackageScalarForeign.invoke(this, call, values, operands.last().execute(frame))
        when (call.result) {
            "Int32Rep", "Int64Rep" -> FrameAccess.writeLong(frame, slots[offset], result as Long)
            "FloatRep" -> FrameAccess.writeFloat(frame, slots[offset], result as Float)
            "DoubleRep" -> FrameAccess.writeDouble(frame, slots[offset], result as Double)
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
            "Int32Rep", "Int64Rep" -> slots[index].getLong(bytecode, frame)
            "FloatRep" -> slots[index].getFloat(bytecode, frame)
            "DoubleRep" -> slots[index].getDouble(bytecode, frame)
            else -> fault("Invalid package C argument local")
        }
        return values
    }
    fun state(bytecode: BytecodeNode, frame: VirtualFrame): Any? = state.getObject(bytecode, frame)
}
