// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

@file:Suppress("UNCHECKED_CAST")
package thc.runtime

/** Synthetic descriptors, independent of the production operation signature table. */
internal object OriginalStdioFixtures {
    val symbols = mapOf(
        "safe_write" to "ghczuwrapperZC20ZCghczminternalZCGHCziInternalziSystemziPosixziInternalsZCwrite",
        "unsafe_write" to "ghczuwrapperZC21ZCghczminternalZCGHCziInternalziSystemziPosixziInternalsZCwrite",
        "errno" to "__hscore_get_errno",
        "seek_set" to "ghczuwrapperZC1ZCghczminternalZCGHCziInternalziSystemziPosixziInternalsZCSEEKzuSET",
        "seek_cur" to "ghczuwrapperZC2ZCghczminternalZCGHCziInternalziSystemziPosixziInternalsZCSEEKzuCUR",
        "seek_end" to "ghczuwrapperZC0ZCghczminternalZCGHCziInternalziSystemziPosixziInternalsZCSEEKzuEND")
    val signatures = linkedMapOf(
        "safe_write" to listOf("Int32Rep", "AddrRep", "Word64Rep", null),
        "unsafe_write" to listOf("Int32Rep", "AddrRep", "Word64Rep", null),
        "errno" to listOf(null), "seek_set" to listOf(null), "seek_cur" to listOf(null), "seek_end" to listOf(null))
    fun convention(name: String) = if (name == "errno") "ccall" else "capi"
    fun safety(name: String) = if (name == "safe_write") "safe" else "unsafe"

    fun scalar(rep: String?, evaluated: Boolean = true): MutableMap<String, Any?> = mutableMapOf(
        "kind" to when (rep) { null -> "void"; "AddrRep" -> "address"; "BoxedRep (Just Lifted)" -> "closure"; else -> "long" },
        "primReps" to (rep?.let { listOf(it) } ?: emptyList<String>()), "evaluated" to evaluated)
    fun output(name: String) = if (signatures.getValue(name).size == 1) "Int32Rep" else "Int64Rep"
    fun tuple(name: String, evaluated: Boolean = true): MutableMap<String, Any?> = mutableMapOf(
        "kind" to "unknown", "primReps" to listOf(output(name)), "aggregate" to "unboxed-tuple",
        "components" to mutableListOf(scalar(null), scalar(output(name))), "evaluated" to evaluated)
    fun closure() = scalar("BoxedRep (Just Lifted)")
    fun descriptor(name: String): MutableMap<String, Any?> {
        val reps = signatures.getValue(name)
        return mutableMapOf("schema" to 1L,
            "target" to mutableMapOf("kind" to "static", "symbol" to symbols.getValue(name), "unit" to "ghc-internal", "isFunction" to true),
            "convention" to convention(name), "safety" to safety(name), "arity" to reps.size.toLong(), "suppliedArity" to reps.size.toLong(),
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
