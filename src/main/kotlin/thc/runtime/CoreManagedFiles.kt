// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime

import com.oracle.truffle.api.nodes.Node
import thc.Language

/** Versioned JVM file operations, deliberately independent of any platform C ABI. */
internal enum class ManagedFileOp(val symbol: String, val arguments: List<String?>, val result: String = "IntRep") {
    OPEN("thc_io_v1_open", listOf("AddrRep", "IntRep", null)),
    READ("thc_io_v1_read", listOf("IntRep", "AddrRep", "IntRep", null)),
    WRITE("thc_io_v1_write", listOf("IntRep", "AddrRep", "IntRep", null)),
    CLOSE("thc_io_v1_close", listOf("IntRep", null)),
    ERROR_KIND("thc_io_v1_error_kind", listOf(null)),
    ERROR_MESSAGE("thc_io_v1_error_message", listOf(null), "AddrRep"),
    SEEK("thc_io_v1_seek", listOf("IntRep", "IntRep", "IntRep", null)),
    SIZE("thc_io_v1_size", listOf("IntRep", null)),
    SET_SIZE("thc_io_v1_set_size", listOf("IntRep", "IntRep", null)),
    IS_TERMINAL("thc_io_v1_is_terminal", listOf("IntRep", null)),
    DEVICE_TYPE("thc_io_v1_device_type", listOf("IntRep", null));
}

internal object CoreManagedFiles {
    /** Stable Java accessor without exposing a Kotlin-internal mangled getter. */
    @JvmStatic fun current(node: Node): ManagedFiles = Language.currentState(node).files

    private val scalarKeys = setOf("kind", "primReps", "evaluated")
    private val tupleKeys = scalarKeys + setOf("aggregate", "components")
    private val descriptorKeys = setOf("schema", "target", "convention", "safety", "arity", "suppliedArity", "argumentReps", "resultRep")

    private fun requireProof(condition: Boolean, detail: String) {
        if (!condition) throw RuntimeFault("Invalid managed file call: $detail")
    }
    private fun exactInteger(value: Any?, expected: Int): Boolean =
        (value is Int || value is Long) && (value as Number).toLong() == expected.toLong()

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
                    if ((target?.get("symbol") as? String)?.startsWith("thc_io_v1_") == true)
                        validateHead(value.getOrNull(1) as? List<Any?>
                            ?: throw RuntimeFault("Invalid managed file call: missing variable head"), false)
                }
                value.forEach(::validateHeads)
            }
        }
    }

    private fun scalar(raw: Any?, primitive: String?, declared: Boolean = false): Boolean {
        val value = raw as? Map<*, *> ?: return false
        val kind = when (primitive) { "AddrRep" -> "address"; "IntRep" -> "long"; else -> "void" }
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

    /** Keep raw proof maps: normalization must not erase contradictory extra fields.
     * Imports may originate in a library or the main unit; the explicit versioned
     * symbol and exact prim/safe signature, not the importing unit, define this ABI. */
    fun validate(metadata: Any?, argumentReps: List<*>, flags: List<*>, resultRep: Any?): ManagedFileOp? {
        val meta = metadata as? Map<*, *> ?: return null
        val descriptor = meta["foreignCall"] as? Map<*, *> ?: return null
        val target = descriptor["target"] as? Map<*, *> ?: return null
        val symbol = target["symbol"] as? String ?: return null
        if (!symbol.startsWith("thc_io_v1_")) return null
        val operation = ManagedFileOp.entries.firstOrNull { it.symbol == symbol }
            ?: throw RuntimeFault("Invalid managed file call: unknown version 1 symbol $symbol")
        requireProof(descriptor.keys == descriptorKeys && exactInteger(descriptor["schema"], 1), "descriptor schema")
        val unit = target["unit"]
        requireProof(target.keys == setOf("kind", "symbol", "unit", "isFunction") &&
            target["kind"] == "static" && target["isFunction"] == true &&
            (unit == null || unit is String && unit.isNotEmpty()), "static function target")
        requireProof(descriptor["convention"] == "prim" && descriptor["safety"] == "safe", "calling convention/safety")
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
