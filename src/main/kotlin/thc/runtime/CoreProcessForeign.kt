// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime

/** Original process-1.6.26.1 POSIX imports, including the interruptible wait. */
internal enum class ProcessOp(val symbol: String, val safety: String, val arguments: List<String?>) {
    CREATE("runInteractiveProcess", "unsafe", listOf("AddrRep", "AddrRep", "AddrRep", "Int32Rep", "Int32Rep",
        "Int32Rep", "AddrRep", "AddrRep", "AddrRep", "AddrRep", "AddrRep", "Int32Rep", "AddrRep", null)),
    POLL("getProcessExitCode", "unsafe", listOf("Int32Rep", "AddrRep", null)),
    WAIT("waitForProcess", "interruptible", listOf("Int32Rep", "AddrRep", null)),
    TERMINATE("terminateProcess", "unsafe", listOf("Int32Rep", null));
}

internal object CoreProcessForeign {
    private val unit = Regex("process-1\\.6\\.26\\.1-(?:inplace|[0-9a-f]+)")
    private val scalarKeys = setOf("kind", "primReps", "evaluated")
    private val tupleKeys = scalarKeys + setOf("aggregate", "components")
    private val descriptorKeys = setOf("schema", "target", "convention", "safety", "arity", "suppliedArity", "argumentReps", "resultRep")
    private fun check(valid: Boolean, detail: String) {
        if (!valid) fault("Invalid original process call: $detail")
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
    private fun result(raw: Any?, evaluated: Boolean? = null): Boolean {
        val value = raw as? Map<*, *> ?: return false
        val components = value["components"] as? List<*> ?: return false
        return value.keys == tupleKeys && value["kind"] == "unknown" &&
            value["aggregate"] == "unboxed-tuple" && value["primReps"] == listOf("Int32Rep") &&
            value["evaluated"] is Boolean && (evaluated == null || value["evaluated"] == evaluated) &&
            components.size == 2 && scalar(components[0], null, true) && scalar(components[1], "Int32Rep", true)
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
                    if (ProcessOp.entries.any { it.symbol == target?.get("symbol") })
                        validateHead(value.getOrNull(1) as? List<Any?>
                            ?: fault("Invalid original process call: missing head"), false)
                }
                value.forEach(::validateHeads)
            }
        }
    }
    fun validateOperand(operation: ProcessOp, index: Int, lowered: CoreRepresentation, stored: CoreRepresentation?) {
        val primitive = operation.arguments[index]
        val kind = kind(primitive)
        val reps = listOfNotNull(primitive)
        check(lowered.present && !lowered.isAggregate && !lowered.isVector && lowered.kind == kind &&
            lowered.primReps == reps, "lowered operand $index")
        if (stored != null && stored.present)
            check(!stored.isAggregate && !stored.isVector && stored.kind in setOf(kind, CoreKind.UNKNOWN) &&
                (stored.primReps == null || stored.primReps == reps), "stored operand $index")
    }
    fun validate(metadata: Any?, argumentReps: List<*>, flags: List<*>, resultRep: Any?): ProcessOp? {
        val meta = metadata as? Map<*, *> ?: return null
        val descriptor = meta["foreignCall"] as? Map<*, *> ?: return null
        val target = descriptor["target"] as? Map<*, *> ?: return null
        val operation = ProcessOp.entries.firstOrNull { it.symbol == target["symbol"] } ?: return null
        check(descriptor.keys == descriptorKeys && exact(descriptor["schema"], 1), "descriptor schema")
        check(target.keys == setOf("kind", "symbol", "unit", "isFunction") &&
            target["kind"] == "static" && (target["unit"] as? String)?.let(unit::matches) == true &&
            target["isFunction"] == true, "static supported installed-library function target")
        check(descriptor["convention"] == "ccall" && descriptor["safety"] == operation.safety, "convention/safety")
        val count = operation.arguments.size
        check(exact(descriptor["arity"], count) && exact(descriptor["suppliedArity"], count), "saturated arity")
        val declared = descriptor["argumentReps"] as? List<*>
        check(declared?.size == count && operation.arguments.indices.all {
            scalar(declared[it], operation.arguments[it], false) }, "declared argument representations")
        check(argumentReps.size == count && operation.arguments.indices.all {
            scalar(argumentReps[it], operation.arguments[it]) }, "actual argument representations")
        check(flags.size == count && flags.all { it is Boolean && !it }, "unlifted argument flags")
        check(result(descriptor["resultRep"], false) && result(meta["rep"]) && result(resultRep), "State/result tuple")
        return operation
    }
}
