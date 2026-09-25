// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime

import com.oracle.truffle.api.frame.VirtualFrame

/** Exact original GHC 9.14.1 Conc.Sync and TopHandler diagnostic declarations. */
internal enum class RtsDiagnosticOp(val symbol: String, val arguments: List<String?>) {
    STACK("reportStackOverflow", listOf("BoxedRep (Just Unlifted)", null)),
    HEAP("reportHeapOverflow", listOf(null)),
    ERROR("errorBelch2", listOf("AddrRep", "AddrRep", null));
}

internal class RtsDiagnosticExpression(private val operation: RtsDiagnosticOp,
    @field:Children private var operands: Array<Expr>, proof: CoreRepresentation) : Expr() {
    init { representation = proof.copy(evaluated = true) }
    override fun execute(frame: VirtualFrame): Nothing = fault("RTS diagnostic requires a tuple destination")
    override fun executeTuple(frame: VirtualFrame, slots: IntArray, offset: Int): Any? {
        val first = if (operands.size > 1) operands[0].execute(frame) else null
        val second = if (operands.size > 2) operands[1].executeRequiredAddress(frame) else null
        requireVoidCarrier(operands.last().execute(frame))
        RtsDiagnostics.report(this, operation, first, second)
        return null
    }
}

internal object CoreRtsDiagnosticForeign {
    private val scalarKeys = setOf("kind", "primReps", "evaluated")
    private val tupleKeys = scalarKeys + setOf("aggregate", "components")
    private val descriptorKeys = setOf("schema", "target", "convention", "safety", "arity", "suppliedArity", "argumentReps", "resultRep")
    private fun requireProof(condition: Boolean, detail: String) {
        if (!condition) fault("Invalid original RTS diagnostic call: $detail")
    }
    private fun exactInteger(value: Any?, expected: Int): Boolean =
        (value is Int || value is Long) && (value as Number).toLong() == expected.toLong()
    private fun kind(primitive: String?): CoreKind = when (primitive) {
        null -> CoreKind.VOID; "AddrRep" -> CoreKind.ADDRESS; else -> CoreKind.OBJECT
    }
    private fun scalar(raw: Any?, primitive: String?, declared: Boolean = false): Boolean {
        val value = raw as? Map<*, *> ?: return false
        return value.keys == scalarKeys && value["kind"] == kind(primitive).name.lowercase() &&
            value["primReps"] == listOfNotNull(primitive) && value["evaluated"] is Boolean &&
            (!declared || value["evaluated"] == false)
    }
    private fun result(raw: Any?, primitive: String?, declared: Boolean = false): Boolean {
        val value = raw as? Map<*, *> ?: return false
        val components = value["components"] as? List<*> ?: return false
        val expected = if (primitive == null) listOf(null) else listOf(null, primitive)
        return value.keys == tupleKeys && value["kind"] == "unknown" && value["aggregate"] == "unboxed-tuple" &&
            value["primReps"] == listOfNotNull(primitive) && value["evaluated"] is Boolean &&
            (!declared || value["evaluated"] == false) && components.size == expected.size &&
            expected.indices.all { scalar(components[it], expected[it]) &&
                (components[it] as Map<*, *>)["evaluated"] == true }
    }
    fun validateHead(function: List<Any?>, defined: Boolean) {
        val proof = CoreRepresentations.metadata(function)?.get("rep") as? Map<*, *>
        requireProof(function.size == 3 && function[0] == "var" && function[1] is String &&
            (function[1] as String).isNotEmpty() && !defined && proof?.keys == scalarKeys &&
            proof["kind"] == "closure" && proof["primReps"] == listOf("BoxedRep (Just Lifted)") &&
            proof["evaluated"] == true, "unresolved declared foreign variable required")
    }
    fun validateHeads(value: Any?) {
        when (value) {
            is Map<*, *> -> value.values.forEach(::validateHeads)
            is List<*> -> {
                if (value.firstOrNull() == "app") {
                    val meta = value.getOrNull(6) as? Map<*, *>
                    val descriptor = meta?.get("foreignCall") as? Map<*, *>
                    val target = descriptor?.get("target") as? Map<*, *>
                    if (RtsDiagnosticOp.entries.any { it.symbol == target?.get("symbol") })
                        validateHead(value.getOrNull(1) as? List<Any?>
                            ?: fault("Invalid original RTS diagnostic call: missing variable head"), false)
                }
                value.forEach(::validateHeads)
            }
        }
    }
    fun validateOperand(operation: RtsDiagnosticOp, index: Int, lowered: CoreRepresentation, stored: CoreRepresentation?) {
        val primitive = operation.arguments[index]
        val kind = kind(primitive)
        val reps = listOfNotNull(primitive)
        requireProof(lowered.present && !lowered.isAggregate && !lowered.isVector &&
            lowered.kind == kind && lowered.primReps == reps, "lowered operand $index")
        if (stored != null && stored.present)
            requireProof(!stored.isAggregate && !stored.isVector && stored.kind in setOf(kind, CoreKind.UNKNOWN) &&
                (stored.primReps == null || stored.primReps == reps), "stored operand $index")
    }
    fun validate(metadata: Any?, argumentReps: List<*>, flags: List<*>, resultRep: Any?): RtsDiagnosticOp? {
        val meta = metadata as? Map<*, *> ?: return null
        val descriptor = meta["foreignCall"] as? Map<*, *> ?: return null
        val target = descriptor["target"] as? Map<*, *> ?: return null
        val operation = RtsDiagnosticOp.entries.firstOrNull { it.symbol == target["symbol"] } ?: return null
        requireProof(descriptor.keys == descriptorKeys && exactInteger(descriptor["schema"], 1), "descriptor schema")
        requireProof(target.keys == setOf("kind", "symbol", "unit", "isFunction") &&
            target["kind"] == "static" && target["unit"] == "ghc-internal" && target["isFunction"] == true,
            "static ghc-internal function target")
        requireProof(descriptor["convention"] == "ccall" && descriptor["safety"] == "unsafe", "calling convention/safety")
        requireProof(exactInteger(descriptor["arity"], operation.arguments.size) &&
            exactInteger(descriptor["suppliedArity"], operation.arguments.size), "saturated arity")
        val declared = descriptor["argumentReps"] as? List<*>
        requireProof(declared != null && declared.size == operation.arguments.size && operation.arguments.indices.all {
            scalar(declared[it], operation.arguments[it], true) }, "declared argument representations")
        requireProof(argumentReps.size == operation.arguments.size && operation.arguments.indices.all {
            scalar(argumentReps[it], operation.arguments[it]) }, "actual argument representations")
        requireProof(flags.size == operation.arguments.size && flags.all { it is Boolean && !it }, "unlifted argument flags")
        requireProof(result(descriptor["resultRep"], null, true) && result(meta["rep"], null) &&
            result(resultRep, null), "exact State/result tuple")
        return operation
    }
}
