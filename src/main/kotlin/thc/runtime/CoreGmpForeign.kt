// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime

internal const val GMP_ARRAY_REP = "BoxedRep (Just Unlifted)"
internal enum class GmpForm { BINARY, WORD, COMPARE, DIVIDE_WORD, MODULO_WORD, DIVIDE, ENCODE_DOUBLE, GET_DOUBLE }

/** Actual primitive FCallId ABIs, including State on the source-pure imports. */
internal enum class GmpForeignOp(val symbol: String, val form: GmpForm,
    val arguments: List<String?>, val result: String?) {
    ADD("__gmpn_add", GmpForm.BINARY,
        listOf(GMP_ARRAY_REP, GMP_ARRAY_REP, "IntRep", GMP_ARRAY_REP, "IntRep", null), "WordRep"),
    ADD_WORD("__gmpn_add_1", GmpForm.WORD,
        listOf(GMP_ARRAY_REP, GMP_ARRAY_REP, "IntRep", "WordRep", null), "WordRep"),
    COMPARE("__gmpn_cmp", GmpForm.COMPARE,
        listOf(GMP_ARRAY_REP, GMP_ARRAY_REP, "IntRep", null), "IntRep"),
    DIVIDE_WORD("__gmpn_divrem_1", GmpForm.DIVIDE_WORD,
        listOf(GMP_ARRAY_REP, "IntRep", GMP_ARRAY_REP, "IntRep", "WordRep", null), "WordRep"),
    MODULO_WORD("__gmpn_mod_1", GmpForm.MODULO_WORD,
        listOf(GMP_ARRAY_REP, "IntRep", "WordRep", null), "WordRep"),
    MULTIPLY("__gmpn_mul", GmpForm.BINARY,
        listOf(GMP_ARRAY_REP, GMP_ARRAY_REP, "IntRep", GMP_ARRAY_REP, "IntRep", null), "WordRep"),
    MULTIPLY_WORD("__gmpn_mul_1", GmpForm.WORD,
        listOf(GMP_ARRAY_REP, GMP_ARRAY_REP, "IntRep", "WordRep", null), "WordRep"),
    SUBTRACT("__gmpn_sub", GmpForm.BINARY,
        listOf(GMP_ARRAY_REP, GMP_ARRAY_REP, "IntRep", GMP_ARRAY_REP, "IntRep", null), "WordRep"),
    DIVIDE("__gmpn_tdiv_qr", GmpForm.DIVIDE,
        listOf(GMP_ARRAY_REP, GMP_ARRAY_REP, "IntRep", GMP_ARRAY_REP, "IntRep", GMP_ARRAY_REP, "IntRep", null), null),
    QUOTIENT("integer_gmp_mpn_tdiv_q", GmpForm.BINARY,
        listOf(GMP_ARRAY_REP, GMP_ARRAY_REP, "IntRep", GMP_ARRAY_REP, "IntRep", null), null),
    REMAINDER("integer_gmp_mpn_tdiv_r", GmpForm.BINARY,
        listOf(GMP_ARRAY_REP, GMP_ARRAY_REP, "IntRep", GMP_ARRAY_REP, "IntRep", null), null),
    SHIFT_RIGHT("integer_gmp_mpn_rshift", GmpForm.WORD,
        listOf(GMP_ARRAY_REP, GMP_ARRAY_REP, "IntRep", "WordRep", null), "WordRep"),
    SHIFT_RIGHT_NEGATIVE("integer_gmp_mpn_rshift_2c", GmpForm.WORD,
        listOf(GMP_ARRAY_REP, GMP_ARRAY_REP, "IntRep", "WordRep", null), "WordRep"),
    GET_DOUBLE("integer_gmp_mpn_get_d", GmpForm.GET_DOUBLE,
        listOf(GMP_ARRAY_REP, "IntRep", "IntRep", null), "DoubleRep"),
    ENCODE_DOUBLE("__int_encodeDouble", GmpForm.ENCODE_DOUBLE,
        listOf("IntRep", "IntRep", null), "DoubleRep");

    val objectIndices = arguments.indices.filter { arguments[it] == GMP_ARRAY_REP }
    val longIndices = arguments.indices.filter { arguments[it] in listOf("IntRep", "WordRep") }
}

internal object CoreGmpForeign {
    private val scalarKeys = setOf("kind", "primReps", "evaluated")
    private val tupleKeys = scalarKeys + setOf("aggregate", "components")
    private val descriptorKeys = setOf("schema", "target", "convention", "safety", "arity", "suppliedArity", "argumentReps", "resultRep")
    private fun requireProof(condition: Boolean, detail: String) {
        if (!condition) fault("Invalid original GMP call: $detail")
    }
    private fun exactInteger(value: Any?, expected: Int): Boolean =
        (value is Int || value is Long) && (value as Number).toLong() == expected.toLong()
    private fun kind(primitive: String?): CoreKind = when (primitive) {
        null -> CoreKind.VOID; GMP_ARRAY_REP -> CoreKind.OBJECT; "DoubleRep" -> CoreKind.DOUBLE; else -> CoreKind.LONG
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
                    if (GmpForeignOp.entries.any { it.symbol == target?.get("symbol") })
                        validateHead(value.getOrNull(1) as? List<Any?>
                            ?: fault("Invalid original GMP call: missing variable head"), false)
                }
                value.forEach(::validateHeads)
            }
        }
    }
    fun validateOperand(operation: GmpForeignOp, index: Int, lowered: CoreRepresentation, stored: CoreRepresentation?) {
        val primitive = operation.arguments[index]
        val kind = kind(primitive)
        val reps = listOfNotNull(primitive)
        requireProof(lowered.present && !lowered.isAggregate && !lowered.isVector &&
            lowered.kind == kind && lowered.primReps == reps, "lowered operand $index")
        if (stored != null && stored.present)
            requireProof(!stored.isAggregate && !stored.isVector && stored.kind in setOf(kind, CoreKind.UNKNOWN) &&
                (stored.primReps == null || stored.primReps == reps), "stored operand $index")
    }
    fun validate(metadata: Any?, argumentReps: List<*>, flags: List<*>, resultRep: Any?): GmpForeignOp? {
        val meta = metadata as? Map<*, *> ?: return null
        val descriptor = meta["foreignCall"] as? Map<*, *> ?: return null
        val target = descriptor["target"] as? Map<*, *> ?: return null
        val operation = GmpForeignOp.entries.firstOrNull { it.symbol == target["symbol"] } ?: return null
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
        requireProof(result(descriptor["resultRep"], operation.result, true) && result(meta["rep"], operation.result) &&
            result(resultRep, operation.result), "exact State/result tuple")
        return operation
    }
}
