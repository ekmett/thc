// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime

import com.oracle.truffle.api.frame.VirtualFrame
import thc.Language

/** Original GHC.Internal.System.Environment declarations, not arbitrary C calls. */
internal enum class RtsArgumentsOp(val symbol: String, val arguments: List<String?>) {
    GET("getProgArgv", listOf("AddrRep", "AddrRep", null)),
    SET("setProgArgv", listOf("Int32Rep", "AddrRep", null));
}

internal object CoreRtsArgumentsForeign {
    private val scalarKeys = setOf("kind", "primReps", "evaluated")
    private val tupleKeys = scalarKeys + setOf("aggregate", "components")
    private val descriptorKeys = setOf("schema", "target", "convention", "safety", "arity", "suppliedArity", "argumentReps", "resultRep")
    private fun check(valid: Boolean, detail: String) {
        if (!valid) fault("Invalid original program-arguments call: $detail")
    }
    private fun exact(value: Any?, expected: Int) =
        (value is Int || value is Long) && (value as Number).toLong() == expected.toLong()
    private fun kind(primitive: String?): CoreKind = when (primitive) {
        null -> CoreKind.VOID; "AddrRep" -> CoreKind.ADDRESS; else -> CoreKind.LONG
    }
    private fun scalar(raw: Any?, primitive: String?, evaluated: Boolean? = null): Boolean {
        val value = raw as? Map<*, *> ?: return false
        return value.keys == scalarKeys && value["kind"] == kind(primitive).name.lowercase() &&
            value["primReps"] == listOfNotNull(primitive) && value["evaluated"] is Boolean &&
            (evaluated == null || value["evaluated"] == evaluated)
    }
    private fun result(raw: Any?, evaluated: Boolean? = null): Boolean {
        val value = raw as? Map<*, *> ?: return false
        val components = value["components"] as? List<*> ?: return false
        return value.keys == tupleKeys && value["kind"] == "unknown" &&
            value["aggregate"] == "unboxed-tuple" && value["primReps"] == emptyList<String>() &&
            value["evaluated"] is Boolean && (evaluated == null || value["evaluated"] == evaluated) &&
            components.size == 1 && scalar(components[0], null, true)
    }
    fun validateHead(function: List<Any?>, defined: Boolean) {
        val proof = CoreRepresentations.metadata(function)?.get("rep") as? Map<*, *>
        check(function.size == 3 && function[0] == "var" && function[1] is String &&
            (function[1] as String).isNotEmpty() && !defined && proof?.keys == scalarKeys &&
            proof["kind"] == "closure" && proof["primReps"] == listOf("BoxedRep (Just Lifted)") &&
            proof["evaluated"] == true, "unresolved declared foreign variable required")
    }
    fun validateHeads(value: Any?) {
        when (value) {
            is Map<*, *> -> value.values.forEach(::validateHeads)
            is List<*> -> {
                if (value.firstOrNull() == "app") {
                    val descriptor = (value.getOrNull(6) as? Map<*, *>)?.get("foreignCall") as? Map<*, *>
                    val target = descriptor?.get("target") as? Map<*, *>
                    if (RtsArgumentsOp.entries.any { it.symbol == target?.get("symbol") })
                        validateHead(value.getOrNull(1) as? List<Any?>
                            ?: fault("Invalid original program-arguments call: missing head"), false)
                }
                value.forEach(::validateHeads)
            }
        }
    }
    fun validateOperand(operation: RtsArgumentsOp, index: Int, lowered: CoreRepresentation, stored: CoreRepresentation?) {
        val primitive = operation.arguments[index]
        val kind = kind(primitive)
        check(lowered.present && !lowered.isAggregate && !lowered.isVector && lowered.kind == kind,
            "lowered operand $index")
        if (stored != null && stored.present)
            check(!stored.isAggregate && !stored.isVector && stored.kind in setOf(kind, CoreKind.UNKNOWN),
                "stored operand $index")
    }
    fun validate(metadata: Any?, argumentReps: List<*>, flags: List<*>, resultRep: Any?): RtsArgumentsOp? {
        val meta = metadata as? Map<*, *> ?: return null
        val descriptor = meta["foreignCall"] as? Map<*, *> ?: return null
        val target = descriptor["target"] as? Map<*, *> ?: return null
        val operation = RtsArgumentsOp.entries.firstOrNull { it.symbol == target["symbol"] } ?: return null
        check(descriptor.keys == descriptorKeys && exact(descriptor["schema"], 1), "descriptor schema")
        check(target.keys == setOf("kind", "symbol", "unit", "isFunction") &&
            target["kind"] == "static" && target["unit"] == "ghc-internal" && target["isFunction"] == true,
            "static ghc-internal function target")
        check(descriptor["convention"] == "ccall" && descriptor["safety"] == "unsafe", "convention/safety")
        check(exact(descriptor["arity"], 3) && exact(descriptor["suppliedArity"], 3), "saturated arity")
        val declared = descriptor["argumentReps"] as? List<*>
        check(declared?.size == 3 && operation.arguments.indices.all {
            scalar(declared[it], operation.arguments[it], false) }, "declared argument representations")
        check(argumentReps.size == 3 && operation.arguments.indices.all {
            scalar(argumentReps[it], operation.arguments[it]) }, "actual argument representations")
        check(flags.size == 3 && flags.all { it is Boolean && !it }, "unlifted argument flags")
        check(result(descriptor["resultRep"], false) && result(meta["rep"]) && result(resultRep), "State tuple")
        return operation
    }
}

internal class RtsArgumentsExpression(private val operation: RtsArgumentsOp,
    @field:Children private var operands: Array<Expr>, proof: CoreRepresentation) : Expr() {
    init { representation = proof.copy(evaluated = true) }
    override fun execute(frame: VirtualFrame): Nothing = fault("Program arguments require a tuple destination")
    override fun executeTuple(frame: VirtualFrame, slots: IntArray, offset: Int): Any? {
        if (operation == RtsArgumentsOp.GET) {
            val argc = operands[0].executeRequiredAddress(frame)
            val argv = operands[1].executeRequiredAddress(frame)
            requireVoidCarrier(operands[2].execute(frame))
            Language.currentState(this).arguments.get(argc, argv)
        } else {
            val argc = operands[0].executeRequiredLong(frame)
            val argv = operands[1].executeRequiredAddress(frame)
            requireVoidCarrier(operands[2].execute(frame))
            Language.currentState(this).arguments.set(argc, argv)
        }
        return null
    }
}
