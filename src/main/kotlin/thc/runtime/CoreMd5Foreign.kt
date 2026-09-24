package thc.runtime

/** Closed managed-memory ABI only; this enum is not a general FFI dispatch table. */
internal enum class Md5ForeignOp(val symbol: String, val arity: Int) {
    INIT("__hsbase_MD5Init", 2),
    UPDATE("__hsbase_MD5Update", 4),
    FINAL("__hsbase_MD5Final", 3);
}

internal object CoreMd5Foreign {
    private val scalarKeys = setOf("kind", "primReps", "evaluated")
    private val tupleKeys = scalarKeys + setOf("aggregate", "components")
    private val descriptorKeys = setOf("schema", "target", "convention", "safety", "arity", "suppliedArity", "argumentReps", "resultRep")

    private fun requireProof(condition: Boolean, detail: String) {
        if (!condition) throw RuntimeFault("Invalid MD5 foreign call: $detail")
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

    /** Run before generic call/capture analysis casts ordinary variable IDs. */
    fun validateHeads(value: Any?) {
        when (value) {
            is Map<*, *> -> value.values.forEach(::validateHeads)
            is List<*> -> {
                if (value.firstOrNull() == "app") {
                    val meta = value.getOrNull(6) as? Map<*, *>
                    val descriptor = meta?.get("foreignCall") as? Map<*, *>
                    val target = descriptor?.get("target") as? Map<*, *>
                    if (Md5ForeignOp.entries.any { it.symbol == target?.get("symbol") })
                        validateHead(value.getOrNull(1) as? List<Any?>
                            ?: throw RuntimeFault("Invalid MD5 foreign call: missing variable head"), false)
                }
                value.forEach(::validateHeads)
            }
        }
    }

    private fun scalar(raw: Any?, primitive: String?, declared: Boolean = false): Boolean {
        val value = raw as? Map<*, *> ?: return false
        val kind = when (primitive) { "AddrRep" -> "address"; "Int32Rep" -> "long"; else -> "void" }
        return value.keys == scalarKeys && value["kind"] == kind &&
            value["primReps"] == (if (primitive == null) emptyList<String>() else listOf(primitive)) &&
            value["evaluated"] is Boolean && (!declared || value["evaluated"] == false)
    }

    private fun result(raw: Any?, declared: Boolean = false): Boolean {
        val value = raw as? Map<*, *> ?: return false
        val components = value["components"] as? List<*> ?: return false
        return value.keys == tupleKeys && value["kind"] == "unknown" && value["aggregate"] == "unboxed-tuple" &&
            value["primReps"] == emptyList<String>() && value["evaluated"] is Boolean &&
            (!declared || value["evaluated"] == false) && components.size == 1 &&
            scalar(components[0], null) && (components[0] as Map<*, *>)["evaluated"] == true
    }

    /** Raw maps are intentional: generic parsing must not erase contradictory fields.
     * The caller must first establish an application with an ordinary variable head.
     * Missing/other symbols retain ordinary unresolved-global handling; a recognized
     * MD5 symbol with any incomplete/unsupported contract is always a hard fault. */
    fun validate(metadata: Any?, argumentReps: List<*>, flags: List<*>, resultRep: Any?): Md5ForeignOp? {
        val meta = metadata as? Map<*, *> ?: return null
        val descriptor = meta["foreignCall"] as? Map<*, *> ?: return null
        val target = descriptor["target"] as? Map<*, *> ?: return null
        val symbol = target["symbol"] as? String ?: return null
        val operation = Md5ForeignOp.entries.firstOrNull { it.symbol == symbol } ?: return null
        requireProof(descriptor.keys == descriptorKeys && exactInteger(descriptor["schema"], 1), "descriptor schema")
        requireProof(target.keys == setOf("kind", "symbol", "unit", "isFunction") &&
            target["kind"] == "static" && target["unit"] == "ghc-internal" && target["isFunction"] == true,
            "static ghc-internal function target")
        requireProof(descriptor["convention"] == "ccall" && descriptor["safety"] == "unsafe", "calling convention/safety")
        requireProof(exactInteger(descriptor["arity"], operation.arity) &&
            exactInteger(descriptor["suppliedArity"], operation.arity), "saturated arity")
        val expected = when (operation) {
            Md5ForeignOp.INIT -> listOf("AddrRep", null)
            Md5ForeignOp.UPDATE -> listOf("AddrRep", "AddrRep", "Int32Rep", null)
            Md5ForeignOp.FINAL -> listOf("AddrRep", "AddrRep", null)
        }
        val declared = descriptor["argumentReps"] as? List<*>
        requireProof(declared != null && declared.size == expected.size && expected.indices.all { scalar(declared[it], expected[it], true) },
            "declared argument representations")
        requireProof(argumentReps.size == expected.size && expected.indices.all { scalar(argumentReps[it], expected[it]) },
            "actual argument representations")
        requireProof(flags.size == expected.size && flags.all { it is Boolean && !it }, "unlifted argument flags")
        requireProof(result(descriptor["resultRep"], true) && result(meta["rep"]) && result(resultRep),
            "singleton State tuple result")
        return operation
    }
}
