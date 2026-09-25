// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
import com.oracle.truffle.api.frame.VirtualFrame
import com.oracle.truffle.api.nodes.Node
import thc.Language

/** Exact safe, non-returning GHC.Internal.TopHandler shutdown declarations. */
internal enum class RtsShutdownOp(val symbol: String, val arguments: List<String?>, val result: String?) {
    EXIT("shutdownHaskellAndExit", listOf("Int32Rep", "Int32Rep", null), null),
    SIGNAL("shutdownHaskellAndSignal", listOf("Int32Rep", "Int32Rep", null), null);
}

/** The first exit request wins, as it does for Truffle's hard exit. Host-owned
 * resources close in both modes; fast mode must never run guest exit hooks. */
internal data class GuestShutdown(val status: Int, val fast: Boolean)

internal object CoreRtsShutdown {
    private val scalarKeys = setOf("kind", "primReps", "evaluated")
    private val tupleKeys = scalarKeys + setOf("aggregate", "components")
    private val descriptorKeys = setOf("schema", "target", "convention", "safety", "arity", "suppliedArity", "argumentReps", "resultRep")
    private fun requireProof(condition: Boolean, detail: String) {
        if (!condition) fault("Invalid original shutdown call: $detail")
    }
    private fun exactInteger(value: Any?, expected: Int): Boolean =
        (value is Int || value is Long) && (value as Number).toLong() == expected.toLong()
    private fun kind(primitive: String?): CoreKind = when (primitive) {
        null -> CoreKind.VOID; "AddrRep" -> CoreKind.ADDRESS; else -> CoreKind.LONG
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
                    if (RtsShutdownOp.entries.any { it.symbol == target?.get("symbol") })
                        validateHead(value.getOrNull(1) as? List<Any?>
                            ?: fault("Invalid original shutdown call: missing variable head"), false)
                }
                value.forEach(::validateHeads)
            }
        }
    }
    fun validateOperand(operation: RtsShutdownOp, index: Int, lowered: CoreRepresentation, stored: CoreRepresentation?) {
        val primitive = operation.arguments[index]
        val kind = kind(primitive)
        val reps = listOfNotNull(primitive)
        requireProof(lowered.present && !lowered.isAggregate && !lowered.isVector &&
            lowered.kind == kind && lowered.primReps == reps, "lowered operand $index")
        if (stored != null && stored.present)
            requireProof(!stored.isAggregate && !stored.isVector && stored.kind in setOf(kind, CoreKind.UNKNOWN) &&
                (stored.primReps == null || stored.primReps == reps), "stored operand $index")
    }
    fun validate(metadata: Any?, argumentReps: List<*>, flags: List<*>, resultRep: Any?): RtsShutdownOp? {
        val meta = metadata as? Map<*, *> ?: return null
        val descriptor = meta["foreignCall"] as? Map<*, *> ?: return null
        val target = descriptor["target"] as? Map<*, *> ?: return null
        val operation = RtsShutdownOp.entries.firstOrNull { it.symbol == target["symbol"] } ?: return null
        requireProof(descriptor.keys == descriptorKeys && exactInteger(descriptor["schema"], 1), "descriptor schema")
        requireProof(target.keys == setOf("kind", "symbol", "unit", "isFunction") &&
            target["kind"] == "static" && target["unit"] == "ghc-internal" && target["isFunction"] == true,
            "static ghc-internal function target")
        requireProof(descriptor["convention"] == "ccall" && descriptor["safety"] == "safe", "calling convention/safety")
        requireProof(exactInteger(descriptor["arity"], operation.arguments.size) &&
            exactInteger(descriptor["suppliedArity"], operation.arguments.size), "saturated arity")
        val declared = descriptor["argumentReps"] as? List<*>
        requireProof(declared != null && declared.size == operation.arguments.size && operation.arguments.indices.all {
            scalar(declared[it], operation.arguments[it], true) }, "declared argument representations")
        requireProof(argumentReps.size == operation.arguments.size && operation.arguments.indices.all {
            scalar(argumentReps[it], operation.arguments[it]) }, "actual argument representations")
        requireProof(flags.size == operation.arguments.size && flags.all { it is Boolean && !it }, "unlifted argument flags")
        requireProof(result(descriptor["resultRep"], operation.result, true) && result(meta["rep"], operation.result) &&
            result(resultRep, operation.result), "exact State/result tuple")
        return operation
    }
    /** Hard exit closes only the guest context. Negative codes retain GHC's
     * Unix signal encoding for an explicitly owning launcher to act on later. */
    @JvmStatic @TruffleBoundary(transferToInterpreterOnException = false)
    fun shutdown(node: Node, operation: RtsShutdownOp, code: Long, fast: Long, state: Any?): Nothing {
        requireVoidCarrier(state)
        if (code != code.toInt().toLong() || fast != fast.toInt().toLong())
            fault("Shutdown arguments require signed CInt carriers")
        val status = when (operation) {
            RtsShutdownOp.EXIT -> code.toInt() and 255
            RtsShutdownOp.SIGNAL -> if (code in 1L..64L) -code.toInt() else 255
        }
        val context = Language.currentState(node)
        context.shutdown.compareAndSet(null, GuestShutdown(status, fast != 0L))
        context.env.context.closeExited(node, context.shutdown.get().status)
        // closeExited can return only during an already active close/unwind.
        throw ThreadDeath()
    }

}

internal class ShutdownRuntime(private val operation: RtsShutdownOp,
    @field:Child private var code: Expr, @field:Child private var fast: Expr,
    @field:Child private var state: Expr) : Expr() {
    override fun execute(frame: VirtualFrame): Nothing = fault("Shutdown requires its declared tuple convention")
    override fun executeTuple(frame: VirtualFrame, slots: IntArray, offset: Int): Nothing {
        val status = code.executeRequiredLong(frame)
        val mode = fast.executeRequiredLong(frame)
        CoreRtsShutdown.shutdown(this, operation, status, mode, state.execute(frame))
    }
}
