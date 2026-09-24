// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime

/** Exact original GHC declarations only; this table does not enable execution. */
internal enum class OriginalStackInfoOp(val symbol: String, val convention: String,
    val arguments: List<String?>, val results: List<String?>, val tupleResult: Boolean) {
    STACK_INFO("getStackInfoTableAddrzh", "prim", listOf("BoxedRep (Just Unlifted)"), listOf("AddrRep"), false),
    FRAME_INFO("getInfoTableAddrszh", "prim", listOf("BoxedRep (Just Unlifted)", "WordRep"), listOf("AddrRep", "AddrRep"), true),
    STACK_FIELDS("getStackFieldszh", "prim", listOf("BoxedRep (Just Unlifted)"), listOf("Word32Rep"), false),
    SMALL_BITMAP("getSmallBitmapzh", "prim", listOf("BoxedRep (Just Unlifted)", "WordRep"), listOf("WordRep", "WordRep"), true),
    ADVANCE("advanceStackFrameLocationzh", "prim", listOf("BoxedRep (Just Unlifted)", "WordRep"),
        listOf("BoxedRep (Just Unlifted)", "WordRep", "IntRep"), true),
    WORD("getWordzh", "prim", listOf("BoxedRep (Just Unlifted)", "WordRep"), listOf("WordRep"), false),
    CLOSURE("getStackClosurezh", "prim", listOf("BoxedRep (Just Unlifted)", "WordRep"), listOf("BoxedRep (Just Lifted)"), false),
    LARGE_BITMAP("getLargeBitmapzh", "prim", listOf("BoxedRep (Just Unlifted)", "WordRep"), listOf("AddrRep", "WordRep"), true),
    BCO_LARGE_BITMAP("getBCOLargeBitmapzh", "prim", listOf("BoxedRep (Just Unlifted)", "WordRep"), listOf("AddrRep", "WordRep"), true),
    RET_FUN_LARGE_BITMAP("getRetFunLargeBitmapzh", "prim", listOf("BoxedRep (Just Unlifted)", "WordRep"), listOf("AddrRep", "WordRep"), true),
    RET_FUN_SMALL_BITMAP("getRetFunSmallBitmapzh", "prim", listOf("BoxedRep (Just Unlifted)", "WordRep"), listOf("WordRep", "WordRep"), true),
    RET_FUN_BIG("isArgGenBigRetFunTypezh", "prim", listOf("BoxedRep (Just Unlifted)", "WordRep"), listOf("IntRep"), false),
    UNDERFLOW("getUnderflowFrameNextChunkzh", "prim", listOf("BoxedRep (Just Unlifted)", "WordRep"), listOf("BoxedRep (Just Unlifted)"), false),
    LOOKUP_IPE("lookupIPE", "ccall", listOf("AddrRep", "AddrRep", null), listOf(null, "Word8Rep"), true);
}

/** Raw proof validation for the bounded stack-info seam; no generic FFI aliases. */
internal object CoreStackInfoForeign {
    private val scalarKeys = setOf("kind", "primReps", "evaluated")
    private val tupleKeys = scalarKeys + setOf("aggregate", "components")
    private val descriptorKeys = setOf("schema", "target", "convention", "safety", "arity", "suppliedArity", "argumentReps", "resultRep")

    private fun requireProof(condition: Boolean, detail: String) {
        if (!condition) throw RuntimeFault("Invalid original stack info call: $detail")
    }
    private fun exactInteger(value: Any?, expected: Int): Boolean =
        (value is Int || value is Long) && (value as Number).toLong() == expected.toLong()

    fun requireLayout(value: Any?): TargetLayout {
        val layout = value as? TargetLayout ?: fault("Original stack info call requires a typed target layout")
        requireProof(layout.tablesNextToCode, "tables-next-to-code layout required")
        return layout
    }

    /** An occurrence certificate cannot relabel an incompatible stored operand. */
    fun validateOperand(operation: OriginalStackInfoOp, index: Int,
        lowered: CoreRepresentation, stored: CoreRepresentation?) {
        val primitive = operation.arguments[index]
        val kind = when (primitive) {
            null -> CoreKind.VOID
            "AddrRep" -> CoreKind.ADDRESS
            "BoxedRep (Just Unlifted)", "BoxedRep (Just Lifted)" -> CoreKind.OBJECT
            else -> CoreKind.LONG
        }
        val reps = listOfNotNull(primitive)
        requireProof(lowered.present && !lowered.isAggregate && !lowered.isVector &&
            lowered.kind == kind && lowered.primReps == reps, "lowered operand $index")
        if (stored != null && stored.present)
            requireProof(!stored.isAggregate && !stored.isVector &&
                stored.kind in setOf(kind, CoreKind.UNKNOWN) &&
                (stored.primReps == null || stored.primReps == reps), "stored operand $index")
    }

    private fun scalar(raw: Any?, primitive: String?, evaluated: Boolean? = null): Boolean {
        val value = raw as? Map<*, *> ?: return false
        val kind = when (primitive) {
            null -> "void"
            "AddrRep" -> "address"
            "BoxedRep (Just Unlifted)", "BoxedRep (Just Lifted)" -> "object"
            else -> "long"
        }
        return value.keys == scalarKeys && value["kind"] == kind &&
            value["primReps"] == (primitive?.let { listOf(it) } ?: emptyList<String>()) &&
            value["evaluated"] is Boolean && (evaluated == null || value["evaluated"] == evaluated)
    }

    private fun result(raw: Any?, operation: OriginalStackInfoOp, declared: Boolean = false): Boolean {
        if (!operation.tupleResult) return scalar(raw, operation.results.single(), if (declared) false else null)
        val value = raw as? Map<*, *> ?: return false
        val fields = value["components"] as? List<*> ?: return false
        return value.keys == tupleKeys && value["kind"] == "unknown" && value["aggregate"] == "unboxed-tuple" &&
            value["primReps"] == operation.results.filterNotNull() && value["evaluated"] is Boolean &&
            (!declared || value["evaluated"] == false) && fields.size == operation.results.size &&
            fields.indices.all { scalar(fields[it], operation.results[it], true) }
    }

    fun validateHead(function: List<Any?>, defined: Boolean) {
        val proof = CoreRepresentations.metadata(function)?.get("rep") as? Map<*, *>
        requireProof(function.size == 3 && function[0] == "var" && function[1] is String &&
            (function[1] as String).isNotEmpty() && !defined && proof?.keys == scalarKeys &&
            proof["kind"] == "closure" && proof["primReps"] == listOf("BoxedRep (Just Lifted)") &&
            proof["evaluated"] == true, "unresolved declared foreign variable required")
    }

    /** Run before generic walkers cast IDs; unknown targets remain unsupported. */
    fun validateHeads(value: Any?) {
        when (value) {
            is Map<*, *> -> value.values.forEach(::validateHeads)
            is List<*> -> {
                if (value.firstOrNull() == "app") {
                    val metadata = value.getOrNull(6) as? Map<*, *>
                    val descriptor = metadata?.get("foreignCall") as? Map<*, *>
                    val target = descriptor?.get("target") as? Map<*, *>
                    if (OriginalStackInfoOp.entries.any { it.symbol == target?.get("symbol") })
                        validateHead(value.getOrNull(1) as? List<Any?>
                            ?: throw RuntimeFault("Invalid original stack info call: missing variable head"), false)
                }
                value.forEach(::validateHeads)
            }
        }
    }

    fun validate(metadata: Any?, argumentReps: List<*>, flags: List<*>, resultRep: Any?): OriginalStackInfoOp? {
        val meta = metadata as? Map<*, *> ?: return null
        val descriptor = meta["foreignCall"] as? Map<*, *> ?: return null
        val target = descriptor["target"] as? Map<*, *> ?: return null
        val symbol = target["symbol"] as? String ?: return null
        val operation = OriginalStackInfoOp.entries.firstOrNull { it.symbol == symbol } ?: return null
        requireProof(descriptor.keys == descriptorKeys && exactInteger(descriptor["schema"], 1), "descriptor schema")
        requireProof(target.keys == setOf("kind", "symbol", "unit", "isFunction") &&
            target["kind"] == "static" && target["unit"] == "ghc-internal" && target["isFunction"] == true,
            "static ghc-internal function target")
        requireProof(descriptor["convention"] == operation.convention && descriptor["safety"] == "safe", "calling convention/safety")
        val expected = operation.arguments
        requireProof(exactInteger(descriptor["arity"], expected.size) && exactInteger(descriptor["suppliedArity"], expected.size),
            "saturated arity")
        val declared = descriptor["argumentReps"] as? List<*>
        requireProof(declared != null && declared.size == expected.size &&
            expected.indices.all { scalar(declared[it], expected[it], false) }, "declared argument representations")
        requireProof(argumentReps.size == expected.size && expected.indices.all { scalar(argumentReps[it], expected[it]) },
            "actual argument representations")
        requireProof(flags.size == expected.size && flags.all { it is Boolean && !it }, "unlifted argument flags")
        requireProof(result(descriptor["resultRep"], operation, true) && result(meta["rep"], operation) &&
            result(resultRep, operation), "exact scalar/tuple result representations")
        return operation
    }
}
