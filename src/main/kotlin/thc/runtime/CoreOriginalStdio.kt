// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime

import com.oracle.truffle.api.nodes.Node
import thc.Language

/** Exact pinned GHC declarations, not aliases for arbitrary POSIX imports. */
internal enum class OriginalStdioOp(val symbol: String, val convention: String, val safety: String,
    val arguments: List<String?>, val result: String) {
    READ_SAFE("ghczuwrapperZC22ZCghczminternalZCGHCziInternalziSystemziPosixziInternalsZCread", "capi", "safe",
        listOf("Int32Rep", "AddrRep", "Word64Rep", null), "Int64Rep"),
    READ_UNSAFE("ghczuwrapperZC23ZCghczminternalZCGHCziInternalziSystemziPosixziInternalsZCread", "capi", "unsafe",
        listOf("Int32Rep", "AddrRep", "Word64Rep", null), "Int64Rep"),
    WRITE_SAFE("ghczuwrapperZC20ZCghczminternalZCGHCziInternalziSystemziPosixziInternalsZCwrite", "capi", "safe",
        listOf("Int32Rep", "AddrRep", "Word64Rep", null), "Int64Rep"),
    WRITE_UNSAFE("ghczuwrapperZC21ZCghczminternalZCGHCziInternalziSystemziPosixziInternalsZCwrite", "capi", "unsafe",
        listOf("Int32Rep", "AddrRep", "Word64Rep", null), "Int64Rep"),
    ERRNO("__hscore_get_errno", "ccall", "unsafe", listOf(null), "Int32Rep"),
    SEEK_SET("ghczuwrapperZC1ZCghczminternalZCGHCziInternalziSystemziPosixziInternalsZCSEEKzuSET", "capi", "unsafe", listOf(null), "Int32Rep"),
    SEEK_CUR("ghczuwrapperZC2ZCghczminternalZCGHCziInternalziSystemziPosixziInternalsZCSEEKzuCUR", "capi", "unsafe", listOf(null), "Int32Rep"),
    SEEK_END("ghczuwrapperZC0ZCghczminternalZCGHCziInternalziSystemziPosixziInternalsZCSEEKzuEND", "capi", "unsafe", listOf(null), "Int32Rep"),
    CLOSE("close", "ccall", "unsafe", listOf("Int32Rep", null), "Int32Rep"),
    SEEK("ghczuwrapperZC19ZCghczminternalZCGHCziInternalziSystemziPosixziInternalsZClseek", "capi", "unsafe",
        listOf("Int32Rep", "Int64Rep", "Int32Rep", null), "Int64Rep"),
    TRUNCATE("__hscore_ftruncate", "ccall", "unsafe", listOf("Int32Rep", "Int64Rep", null), "Int32Rep"),
    ISATTY("isatty", "ccall", "unsafe", listOf("Int32Rep", null), "Int32Rep"),
    READY_SAFE("fdReady", "ccall", "safe", listOf("Int32Rep", "Word8Rep", "Int64Rep", "Word8Rep", null), "Int32Rep"),
    READY_UNSAFE("fdReady", "ccall", "unsafe", listOf("Int32Rep", "Word8Rep", "Int64Rep", "Word8Rep", null), "Int32Rep");

    val readiness: Boolean get() = this == READY_SAFE || this == READY_UNSAFE
    val seekConstant: Boolean get() = this == SEEK_SET || this == SEEK_CUR || this == SEEK_END
}

internal object CoreOriginalStdio {
    @JvmStatic fun current(node: Node): ManagedStdio = Language.currentState(node).stdio

    private val scalarKeys = setOf("kind", "primReps", "evaluated")
    private val tupleKeys = scalarKeys + setOf("aggregate", "components")
    private val descriptorKeys = setOf("schema", "target", "convention", "safety", "arity", "suppliedArity", "argumentReps", "resultRep")

    private fun requireProof(condition: Boolean, detail: String) {
        if (!condition) throw RuntimeFault("Invalid original stdio call: $detail")
    }
    private fun exactInteger(value: Any?, expected: Int): Boolean =
        (value is Int || value is Long) && (value as Number).toLong() == expected.toLong()

    /** An occurrence certificate cannot relabel a stored scalar/State operand. */
    fun validateScalarOperand(operation: OriginalStdioOp, index: Int,
        lowered: CoreRepresentation, stored: CoreRepresentation?) {
        requireProof(operation.readiness || operation.seekConstant, "readiness/constant operand operation")
        val primitive = operation.arguments[index]
        val kind = if (primitive == null) CoreKind.VOID else CoreKind.LONG
        val reps = listOfNotNull(primitive)
        requireProof(lowered.present && !lowered.isAggregate && !lowered.isVector &&
            lowered.kind == kind && lowered.primReps == reps, "lowered scalar operand $index")
        if (stored != null && stored.present)
            requireProof(!stored.isAggregate && !stored.isVector && stored.kind in setOf(kind, CoreKind.UNKNOWN) &&
                (stored.primReps == null || stored.primReps == reps), "stored scalar operand $index")
    }

    fun validateHead(function: List<Any?>, defined: Boolean) {
        val proof = CoreRepresentations.metadata(function)?.get("rep") as? Map<*, *>
        requireProof(function.size == 3 && function[0] == "var" && function[1] is String &&
            (function[1] as String).isNotEmpty() && !defined && proof?.keys == scalarKeys &&
            proof["kind"] == "closure" && proof["primReps"] == listOf("BoxedRep (Just Lifted)") &&
            proof["evaluated"] == true, "unresolved declared foreign variable required")
    }

    /** Reject malformed heads before generic call/capture analysis casts their IDs. */
    fun validateHeads(value: Any?) {
        when (value) {
            is Map<*, *> -> value.values.forEach(::validateHeads)
            is List<*> -> {
                if (value.firstOrNull() == "app") {
                    val meta = value.getOrNull(6) as? Map<*, *>
                    val descriptor = meta?.get("foreignCall") as? Map<*, *>
                    val target = descriptor?.get("target") as? Map<*, *>
                    if (OriginalStdioOp.entries.any { it.symbol == target?.get("symbol") })
                        validateHead(value.getOrNull(1) as? List<Any?>
                            ?: throw RuntimeFault("Invalid original stdio call: missing variable head"), false)
                }
                value.forEach(::validateHeads)
            }
        }
    }

    private fun scalar(raw: Any?, primitive: String?, declared: Boolean = false): Boolean {
        val value = raw as? Map<*, *> ?: return false
        val kind = when (primitive) { null -> "void"; "AddrRep" -> "address"; else -> "long" }
        return value.keys == scalarKeys && value["kind"] == kind &&
            value["primReps"] == (primitive?.let { listOf(it) } ?: emptyList<String>()) &&
            value["evaluated"] is Boolean && (!declared || value["evaluated"] == false)
    }

    private fun result(raw: Any?, primitive: String, declared: Boolean = false): Boolean {
        val value = raw as? Map<*, *> ?: return false
        val components = value["components"] as? List<*> ?: return false
        return value.keys == tupleKeys && value["kind"] == "unknown" && value["aggregate"] == "unboxed-tuple" &&
            value["primReps"] == listOf(primitive) && value["evaluated"] is Boolean &&
            (!declared || value["evaluated"] == false) && components.size == 2 &&
            scalar(components[0], null) && (components[0] as Map<*, *>)["evaluated"] == true &&
            scalar(components[1], primitive) && (components[1] as Map<*, *>)["evaluated"] == true
    }

    /** Caller binding names are irrelevant; raw FCallId proof must match exactly.
     * Unrecognized symbols retain ordinary unsupported-foreign handling. */
    fun validate(metadata: Any?, argumentReps: List<*>, flags: List<*>, resultRep: Any?): OriginalStdioOp? {
        val meta = metadata as? Map<*, *> ?: return null
        val descriptor = meta["foreignCall"] as? Map<*, *> ?: return null
        val target = descriptor["target"] as? Map<*, *> ?: return null
        val symbol = target["symbol"] as? String ?: return null
        val candidates = OriginalStdioOp.entries.filter { it.symbol == symbol }
        if (candidates.isEmpty()) return null
        val operation = candidates.firstOrNull { it.convention == descriptor["convention"] && it.safety == descriptor["safety"] }
            ?: throw RuntimeFault("Invalid original stdio call: calling convention/safety")
        requireProof(descriptor.keys == descriptorKeys && exactInteger(descriptor["schema"], 1), "descriptor schema")
        requireProof(target.keys == setOf("kind", "symbol", "unit", "isFunction") &&
            target["kind"] == "static" && target["unit"] == "ghc-internal" && target["isFunction"] == true,
            "static ghc-internal function target")
        requireProof(descriptor["convention"] == operation.convention && descriptor["safety"] == operation.safety,
            "calling convention/safety")
        val expected = operation.arguments
        requireProof(exactInteger(descriptor["arity"], expected.size) &&
            exactInteger(descriptor["suppliedArity"], expected.size), "saturated arity")
        val declared = descriptor["argumentReps"] as? List<*>
        requireProof(declared != null && declared.size == expected.size &&
            expected.indices.all { scalar(declared[it], expected[it], true) }, "declared argument representations")
        requireProof(argumentReps.size == expected.size && expected.indices.all { scalar(argumentReps[it], expected[it]) },
            "actual argument representations")
        requireProof(flags.size == expected.size && flags.all { it is Boolean && !it }, "unlifted argument flags")
        requireProof(result(descriptor["resultRep"], operation.result, true) && result(meta["rep"], operation.result) &&
            result(resultRep, operation.result), "exact State/result tuple")
        return operation
    }
}
