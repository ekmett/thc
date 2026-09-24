// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

@file:Suppress("UNCHECKED_CAST")
package thc.runtime

/** Synthetic descriptors, independent of the production operation signature table. */
internal object ManagedFileFixtures {
    val signatures = linkedMapOf(
        "open" to listOf("AddrRep", "IntRep", null),
        "read" to listOf("IntRep", "AddrRep", "IntRep", null),
        "write" to listOf("IntRep", "AddrRep", "IntRep", null),
        "close" to listOf("IntRep", null),
        "error_kind" to listOf(null), "error_message" to listOf(null),
        "seek" to listOf("IntRep", "IntRep", "IntRep", null),
        "size" to listOf("IntRep", null), "set_size" to listOf("IntRep", "IntRep", null),
        "is_terminal" to listOf("IntRep", null), "device_type" to listOf("IntRep", null))

    fun scalar(rep: String?, evaluated: Boolean = true): MutableMap<String, Any?> = mutableMapOf(
        "kind" to when (rep) { null -> "void"; "AddrRep" -> "address"; "BoxedRep (Just Lifted)" -> "closure"; else -> "long" },
        "primReps" to (rep?.let { listOf(it) } ?: emptyList<String>()), "evaluated" to evaluated)
    fun output(name: String) = if (name == "error_message") "AddrRep" else "IntRep"
    fun tuple(name: String, evaluated: Boolean = true): MutableMap<String, Any?> = mutableMapOf(
        "kind" to "unknown", "primReps" to listOf(output(name)), "aggregate" to "unboxed-tuple",
        "components" to mutableListOf(scalar(null), scalar(output(name))), "evaluated" to evaluated)
    fun closure() = scalar("BoxedRep (Just Lifted)")
    fun descriptor(name: String): MutableMap<String, Any?> {
        val reps = signatures.getValue(name)
        return mutableMapOf("schema" to 1L,
            "target" to mutableMapOf("kind" to "static", "symbol" to "thc_io_v1_$name", "unit" to "main", "isFunction" to true),
            "convention" to "prim", "safety" to "safe", "arity" to reps.size.toLong(), "suppliedArity" to reps.size.toLong(),
            "argumentReps" to reps.map { scalar(it, false) }.toMutableList(), "resultRep" to tuple(name, false))
    }
    fun call(name: String): MutableList<Any?> = mutableListOf("app", mutableListOf("var", "foreign-$name", mapOf("rep" to closure())),
        signatures.getValue(name).mapIndexed { index, rep -> mutableListOf("var", "p$index", mapOf("rep" to scalar(rep))) },
        MutableList<Any?>(signatures.getValue(name).size) { false }, false, false,
        mutableMapOf("rep" to tuple(name), "foreignCall" to descriptor(name)))

    fun module(names: Iterable<String> = signatures.keys, mutate: (MutableList<Any?>) -> Unit = {}): Map<String, Any?> = mapOf(
        "instrument" to true,
        "constructors" to listOf(mapOf("id" to "T2", "kind" to "unboxed-tuple", "arity" to 2, "tag" to 1)),
        "bindings" to names.map { name ->
            val reps = signatures.getValue(name)
            val formals = reps.mapIndexed { index, rep -> mapOf("id" to "p$index", "name" to "${name}_$index", "lifted" to false, "rep" to scalar(rep)) }
            val expression = call(name).also(mutate)
            val body = listOf("case", expression, "pair", listOf(listOf("data", "T2", listOf("s", "value"),
                listOf("var", "value", mapOf("rep" to scalar(output(name)))),
                mapOf("binders" to listOf(mapOf("id" to "s", "lifted" to false, "rep" to scalar(null)),
                    mapOf("id" to "value", "lifted" to false, "rep" to scalar(output(name))))))),
                mapOf("rep" to scalar(output(name)), "binder" to mapOf("id" to "pair", "lifted" to false, "rep" to tuple(name))))
            mapOf("id" to name, "name" to name, "arity" to reps.size, "lifted" to true, "rep" to closure(),
                "expr" to listOf("lam", formals, body, mapOf("rep" to closure(), "resultRep" to scalar(output(name)))))
        })
}
