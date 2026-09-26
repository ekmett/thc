// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime

import com.oracle.truffle.api.frame.VirtualFrame

/** Versioned scalar C declarations, with honest native-GHC compatibility stubs.
 * These are reserved calls, not admission of arbitrary host/native functions. */
internal enum class RuntimeServiceCall(val symbol: String, val arguments: List<String?>) {
    QUERY("thc_runtime_v1_query", listOf("Int32Rep", "Int64Rep", "Int64Rep", null)),
    CONTROL("thc_runtime_v1_control", listOf("Int32Rep", "Int64Rep", null)),
    TRACE("thc_runtime_v1_trace", listOf("Int32Rep", "Int64Rep", "AddrRep", "Int64Rep", null));
}

internal object CoreRuntimeServices {
    private fun scalar(proof: CoreRepresentation, primitive: String?): Boolean =
        proof.present && !proof.isAggregate && !proof.isVector &&
            proof.kind == when (primitive) { null -> CoreKind.VOID; "AddrRep" -> CoreKind.ADDRESS; else -> CoreKind.LONG } &&
            proof.primReps == (primitive?.let { listOf(it) } ?: emptyList<String>())

    private fun result(proof: CoreRepresentation): Boolean {
        val fields = proof.components ?: return false
        return proof.isTuple && !proof.isSum && !proof.isVector && fields.size == 2 &&
            scalar(fields[0], null) && scalar(fields[1], "Int64Rep") && proof.primReps == listOf("Int64Rep")
    }

    fun validate(expr: List<Any?>, defined: Boolean): RuntimeServiceCall? {
        val metadata = CoreRepresentations.metadata(expr) ?: return null
        val call = metadata["foreignCall"] as? Map<*, *> ?: return null
        val target = call["target"] as? Map<*, *> ?: return null
        val operation = RuntimeServiceCall.entries.firstOrNull { it.symbol == target["symbol"] } ?: return null
        fun require(valid: Boolean, detail: String) {
            if (!valid) fault("Invalid THC runtime service declaration: $detail")
        }
        val function = expr.getOrNull(1) as? List<*>
        require(expr.firstOrNull() == "app" && function?.size == 3 && function[0] == "var" &&
            function[1] is String && (function[1] as String).isNotEmpty() && !defined, "unresolved foreign identifier")
        @Suppress("UNCHECKED_CAST")
        CoreBoundThreadForeign.validateHead(function as List<Any?>, defined)
        require(target.keys == setOf("kind", "symbol", "unit", "isFunction") && target["kind"] == "static" &&
            target["isFunction"] == true && (target["unit"] == null || target["unit"] is String), "static function target")
        fun number(value: Any?, expected: Int) = value == expected || value == expected.toLong()
        val arity = operation.arguments.size
        require(call.keys == setOf("schema", "target", "convention", "safety", "arity", "suppliedArity", "argumentReps", "resultRep") &&
            number(call["schema"], 1) && call["convention"] == "ccall" && call["safety"] == "unsafe" &&
            number(call["arity"], arity) && number(call["suppliedArity"], arity), "exact v1 C ABI")
        val arguments = expr.getOrNull(2) as? List<*>
        val declared = call["argumentReps"] as? List<*>
        require(arguments?.size == arity && declared?.size == arity && expr.getOrNull(3) == List(arity) { false },
            "argument count and representation flags")
        operation.arguments.forEachIndexed { index, primitive ->
            @Suppress("UNCHECKED_CAST")
            val expression = arguments!![index] as? List<Any?> ?: emptyList()
            require(scalar(CoreRepresentations.parse(declared!![index]), primitive) &&
                scalar(CoreRepresentations.expression(expression), primitive), "argument $index")
        }
        require(result(CoreRepresentations.parse(call["resultRep"])) && result(CoreRepresentations.expression(expr)),
            "State#/CLLong result")
        return operation
    }

    /** Check actual lowered/binder shapes once at this external ABI boundary. */
    fun validateOperand(operation: RuntimeServiceCall, index: Int, lowered: CoreRepresentation, bound: CoreRepresentation?) {
        val primitive = operation.arguments[index]
        if (lowered.present && !scalar(lowered, primitive) || bound?.present == true && !scalar(bound, primitive))
            fault("Runtime service lowered argument $index contradicts its C ABI")
    }
}

internal class RuntimeQueryExpression(private val backend: Int,
    @field:Child private var selector: Expr, @field:Child private var index: Expr,
    @field:Child private var detail: Expr, @field:Child private var state: Expr) : Expr() {
    override fun execute(frame: VirtualFrame): Nothing = fault("Runtime query requires a tuple destination")
    override fun executeTuple(frame: VirtualFrame, slots: IntArray, offset: Int): Any? {
        val key = selector.executeLong(frame).toInt()
        val item = index.executeLong(frame)
        val field = detail.executeLong(frame)
        requireVoidCarrier(state.execute(frame))
        FrameAccess.writeLong(frame, slots[offset], RuntimeServices.query(this, backend, key, item, field))
        return null
    }
}

internal class RuntimeControlExpression(@field:Child private var selector: Expr,
    @field:Child private var setting: Expr, @field:Child private var state: Expr) : Expr() {
    override fun execute(frame: VirtualFrame): Nothing = fault("Runtime control requires a tuple destination")
    override fun executeTuple(frame: VirtualFrame, slots: IntArray, offset: Int): Any? {
        val key = selector.executeLong(frame).toInt()
        val value = setting.executeLong(frame)
        requireVoidCarrier(state.execute(frame))
        FrameAccess.writeLong(frame, slots[offset], RuntimeServices.control(this, key, value))
        return null
    }
}

internal class RuntimeTraceExpression(@field:Child private var operation: Expr,
    @field:Child private var token: Expr, @field:Child private var address: Expr,
    @field:Child private var length: Expr, @field:Child private var state: Expr) : Expr() {
    override fun execute(frame: VirtualFrame): Nothing = fault("Runtime trace requires a tuple destination")
    override fun executeTuple(frame: VirtualFrame, slots: IntArray, offset: Int): Any? {
        val op = operation.executeLong(frame).toInt()
        val id = token.executeLong(frame)
        val bytes = address.executeAddress(frame)
        val count = length.executeLong(frame)
        requireVoidCarrier(state.execute(frame))
        FrameAccess.writeLong(frame, slots[offset], RuntimeServices.trace(this, op, id, bytes, count))
        return null
    }
}
