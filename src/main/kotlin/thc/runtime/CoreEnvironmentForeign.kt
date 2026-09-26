// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime

import com.oracle.truffle.api.frame.VirtualFrame
import thc.Language

internal enum class EnvironmentOp(val symbol: String, val result: String, val arguments: List<String?>) {
    GET("getenv", "AddrRep", listOf("AddrRep", null)),
    PUT("putenv", "Int32Rep", listOf("AddrRep", null)),
    UNSET("__hsbase_unsetenv", "Int32Rep", listOf("AddrRep", null)),
    ENUMERATE("__hscore_environ", "AddrRep", listOf(null));
}

/** Original pinned POSIX imports, with their emitted GHC ABI checked once at
 * admission. This is not an arbitrary libc-symbol dispatcher. */
internal object CoreEnvironmentForeign {
    private val scalarKeys = setOf("kind", "primReps", "evaluated")
    private val tupleKeys = scalarKeys + setOf("aggregate", "components")
    private val descriptorKeys = setOf("schema", "target", "convention", "safety", "arity", "suppliedArity", "argumentReps", "resultRep")
    private fun check(valid: Boolean, detail: String) {
        if (!valid) fault("Invalid original environment call: $detail")
    }
    private fun exact(value: Any?, expected: Int) =
        (value is Int || value is Long) && (value as Number).toLong() == expected.toLong()
    private fun kind(primitive: String?): CoreKind = when (primitive) {
        null -> CoreKind.VOID; "AddrRep" -> CoreKind.ADDRESS
        "BoxedRep (Just Lifted)" -> CoreKind.CLOSURE; else -> CoreKind.LONG
    }
    private fun scalar(raw: Any?, primitive: String?, evaluated: Boolean? = null): Boolean {
        val value = raw as? Map<*, *> ?: return false
        return value.keys == scalarKeys && value["kind"] == kind(primitive).name.lowercase() &&
            value["primReps"] == listOfNotNull(primitive) && value["evaluated"] is Boolean &&
            (evaluated == null || value["evaluated"] == evaluated)
    }
    private fun result(raw: Any?, primitive: String, evaluated: Boolean? = null): Boolean {
        val value = raw as? Map<*, *> ?: return false
        val components = value["components"] as? List<*> ?: return false
        return value.keys == tupleKeys && value["kind"] == "unknown" &&
            value["aggregate"] == "unboxed-tuple" && value["primReps"] == listOf(primitive) &&
            value["evaluated"] is Boolean && (evaluated == null || value["evaluated"] == evaluated) &&
            components.size == 2 && scalar(components[0], null, true) && scalar(components[1], primitive, true)
    }
    fun validateHead(function: List<Any?>, defined: Boolean) {
        check(function.size == 3 && function[0] == "var" && function[1] is String &&
            (function[1] as String).isNotEmpty() && !defined &&
            scalar(CoreRepresentations.metadata(function)?.get("rep"), "BoxedRep (Just Lifted)", true),
            "unresolved declared foreign variable required")
    }
    fun validateHeads(value: Any?) {
        when (value) {
            is Map<*, *> -> value.values.forEach(::validateHeads)
            is List<*> -> {
                if (value.firstOrNull() == "app") {
                    val descriptor = (value.getOrNull(6) as? Map<*, *>)?.get("foreignCall") as? Map<*, *>
                    val target = descriptor?.get("target") as? Map<*, *>
                    if (EnvironmentOp.entries.any { it.symbol == target?.get("symbol") })
                        validateHead(value.getOrNull(1) as? List<Any?>
                            ?: fault("Invalid original environment call: missing head"), false)
                }
                value.forEach(::validateHeads)
            }
        }
    }
    fun validateOperand(operation: EnvironmentOp, index: Int, lowered: CoreRepresentation, stored: CoreRepresentation?) {
        val kind = kind(operation.arguments[index])
        check(lowered.present && !lowered.isAggregate && !lowered.isVector && lowered.kind == kind,
            "lowered operand $index")
        if (stored != null && stored.present)
            check(!stored.isAggregate && !stored.isVector && stored.kind in setOf(kind, CoreKind.UNKNOWN),
                "stored operand $index")
    }
    fun validate(metadata: Any?, argumentReps: List<*>, flags: List<*>, resultRep: Any?): EnvironmentOp? {
        val meta = metadata as? Map<*, *> ?: return null
        val descriptor = meta["foreignCall"] as? Map<*, *> ?: return null
        val target = descriptor["target"] as? Map<*, *> ?: return null
        val operation = EnvironmentOp.entries.firstOrNull { it.symbol == target["symbol"] } ?: return null
        check(descriptor.keys == descriptorKeys && exact(descriptor["schema"], 1), "descriptor schema")
        check(target.keys == setOf("kind", "symbol", "unit", "isFunction") &&
            target["kind"] == "static" && target["unit"] == "ghc-internal" && target["isFunction"] == true,
            "static ghc-internal function target")
        check(descriptor["convention"] == "ccall" && descriptor["safety"] == "unsafe", "convention/safety")
        val count = operation.arguments.size
        check(exact(descriptor["arity"], count) && exact(descriptor["suppliedArity"], count), "saturated arity")
        val declared = descriptor["argumentReps"] as? List<*>
        check(declared?.size == count && operation.arguments.indices.all {
            scalar(declared[it], operation.arguments[it], false) }, "declared argument representations")
        check(argumentReps.size == count && operation.arguments.indices.all {
            scalar(argumentReps[it], operation.arguments[it]) }, "actual argument representations")
        check(flags.size == count && flags.all { it is Boolean && !it }, "unlifted argument flags")
        check(result(descriptor["resultRep"], operation.result, false) &&
            result(meta["rep"], operation.result) && result(resultRep, operation.result), "State/result tuple")
        return operation
    }
}

internal class EnvironmentExpression(private val operation: EnvironmentOp,
    @field:Children private var operands: Array<Expr>, proof: CoreRepresentation) : Expr() {
    init { representation = proof.copy(evaluated = true) }
    override fun execute(frame: VirtualFrame): Nothing = fault("Environment call requires a tuple destination")
    override fun executeTuple(frame: VirtualFrame, slots: IntArray, offset: Int): Any? {
        val address = if (operation == EnvironmentOp.ENUMERATE) null else operands[0].executeRequiredAddress(frame)
        requireVoidCarrier(operands.last().execute(frame))
        val environment = Language.currentState(this).environment
        when (operation) {
            EnvironmentOp.GET -> FrameAccess.writeObject(frame, slots[offset], environment.get(address!!))
            EnvironmentOp.PUT -> FrameAccess.writeLong(frame, slots[offset], environment.put(address!!))
            EnvironmentOp.UNSET -> FrameAccess.writeLong(frame, slots[offset], environment.unset(address!!))
            EnvironmentOp.ENUMERATE -> FrameAccess.writeObject(frame, slots[offset], environment.environ())
        }
        return null
    }
}
