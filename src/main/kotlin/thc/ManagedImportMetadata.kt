// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc

/** Retained stock wrappers may be replaced only by separately validated managed
 * call adapters. This token grants no native symbol or callback capability. */
internal class ManagedImportAdmission private constructor(val module: Map<*, *>) {
    companion object {
        private fun requireProof(value: Boolean, detail: String) {
            require(value) { "Invalid managed static-import provenance: $detail" }
        }
        private fun record(value: Any?, keys: String): Map<*, *> {
            requireProof(value is Map<*, *> && value.keys == keys.split(' ').toSet(), "record fields")
            return value as Map<*, *>
        }
        private fun text(value: Any?): String {
            requireProof(value is String && value.isNotEmpty() && '\u0000' !in value, "missing text")
            return value as String
        }
        private fun nullableText(value: Any?) { if (value != null) text(value) }
        private fun identity(value: Any?): Map<*, *> = record(value, "unit module occurrence namespace").also {
            it.values.forEach(::text)
            requireProof(it["namespace"] in setOf("value", "type", "data"), "name namespace")
        }
        private fun type(value: Any?) {
            requireProof(value is Map<*, *>, "type record")
            val raw = value as Map<*, *>
            when (raw["kind"]) {
                "tycon" -> {
                    record(raw, "kind name arguments"); identity(raw["name"])
                    requireProof(raw["arguments"] is List<*>, "type arguments")
                    (raw["arguments"] as List<*>).forEach(::type)
                }
                "application" -> { record(raw, "kind function argument"); type(raw["function"]); type(raw["argument"]) }
                "function" -> { record(raw, "kind multiplicity argument result"); type(raw["multiplicity"]); type(raw["argument"]); type(raw["result"]) }
                else -> requireProof(false, "unknown type")
            }
        }
        private val primitives = setOf("void", "IntRep", "WordRep", "Int8Rep", "Word8Rep", "Int16Rep", "Word16Rep",
            "Int32Rep", "Word32Rep", "Int64Rep", "Word64Rep", "AddrRep", "FloatRep", "DoubleRep")
        private fun scalars(value: Any?): List<*> {
            requireProof(value is List<*> && value.isNotEmpty() && value.all { it in primitives }, "foreign scalar carriers")
            return value as List<*>
        }
        private fun calls(value: Any?): List<Any?> = when (value) {
            is Map<*, *> -> listOfNotNull(value["foreignCall"]) + value.values.flatMap(::calls)
            is List<*> -> value.flatMap(::calls)
            else -> emptyList()
        }
        private fun scalar(primitive: Any?, evaluated: Boolean) = mapOf("kind" to when (primitive) {
            "void" -> "void"; "AddrRep" -> "address"; "FloatRep" -> "float"; "DoubleRep" -> "double"; else -> "long"
        }, "primReps" to if (primitive == "void") emptyList<Any>() else listOf(primitive), "evaluated" to evaluated)
        private fun descriptor(emitted: Map<*, *>): Map<String, Any?> {
            val arguments = emitted["arguments"] as List<*>
            val result = emitted["result"] as List<*>
            return mapOf("schema" to 1L, "target" to mapOf("kind" to "static", "symbol" to emitted["symbol"],
                "unit" to emitted["unit"], "isFunction" to true), "convention" to emitted["convention"], "safety" to emitted["safety"],
                "arity" to arguments.size.toLong(), "suppliedArity" to arguments.size.toLong(),
                "argumentReps" to arguments.map { scalar(it, false) }, "resultRep" to mapOf("kind" to "unknown",
                    "primReps" to result.filter { it != "void" }, "aggregate" to "unboxed-tuple",
                    "components" to result.map { scalar(it, true) }, "evaluated" to false))
        }

        fun read(module: Map<*, *>): ManagedImportAdmission? {
            if (!module.containsKey("staticForeignImportStubs")) return null
            val raw = module["staticForeignImportStubs"]
            requireProof(module["schema"] == 2L || module["schema"] == 2, "archive schema")
            requireProof(!module.containsKey("foreignLink"), "ambiguous native/managed link")
            requireProof(raw is Map<*, *>, "proof record")
            val status = (raw as Map<*, *>)["status"]
            val common = "schema scope execution profile unit module status"
            val proof = record(raw, common + if (status == "verified") " wordBits expectedForeign imports expectedCalls" else " reason")
            requireProof((proof["schema"] == 1L || proof["schema"] == 1) &&
                proof["scope"] == "retained-static-import-products" && proof["execution"] == "not-linked" &&
                proof["profile"] == "ghc-9.14.1-thc-only-static-c-imports-v1" &&
                proof["unit"] == module["unit"] && proof["module"] == module["module"] && module["ghc"] == "9.14.1", "schema/profile/owner")
            text(proof["unit"]); text(proof["module"])
            if (status != "verified") {
                requireProof(status == "unclassified" || status == "rejected", "status")
                text(proof["reason"])
                return null
            }
            requireProof(proof["wordBits"] == 64L || proof["wordBits"] == 64, "word width")
            requireProof(proof["expectedForeign"] == module["foreign"], "retained foreign product differs")
            val foreign = record(module["foreign"], "schema execution stubs files")
            requireProof((foreign["schema"] == 1L || foreign["schema"] == 1) && foreign["execution"] == "not-linked", "foreign schema/execution")
            val stubs = record(foreign["stubs"], "header source initializers finalizers")
            requireProof(stubs["header"] == "" && stubs["initializers"] == emptyList<Any>() &&
                stubs["finalizers"] == emptyList<Any>() && foreign["files"] == emptyList<Any>(), "unclassified native obligations")
            text(stubs["source"])
            val imports = proof["imports"] as? List<*> ?: throw IllegalArgumentException("Missing typed static imports")
            requireProof(imports.isNotEmpty(), "empty import inventory")
            val binders = hashSetOf<Map<*, *>>()
            val generated = linkedMapOf<Pair<Any?, Any?>, Map<String, Any?>>()
            for (entry in imports) {
                val item = record(entry, "binder header symbol unit isFunction convention safety declaredType normalizedType normalizationRole emitted")
                val binder = identity(item["binder"])
                requireProof(binder["unit"] == module["unit"] && binder["module"] == module["module"] &&
                    binder["namespace"] == "value" && binders.add(binder), "duplicate or foreign import binder")
                nullableText(item["header"]); nullableText(item["unit"]); text(item["symbol"])
                requireProof(item["convention"] in setOf("ccall", "capi") && item["safety"] in setOf("safe", "unsafe", "interruptible") &&
                    item["isFunction"] is Boolean && (item["isFunction"] == true || item["convention"] == "capi") &&
                    item["normalizationRole"] == "representational", "static import declaration")
                type(item["declaredType"]); type(item["normalizedType"])
                val emitted = record(item["emitted"], "symbol unit convention safety arguments result")
                text(emitted["symbol"]); nullableText(emitted["unit"])
                requireProof(emitted["convention"] == item["convention"] && emitted["safety"] == item["safety"] &&
                    emitted["unit"] == item["unit"], "emitted call ownership/convention")
                val arguments = scalars(emitted["arguments"]); val result = scalars(emitted["result"])
                requireProof(arguments.last() == "void" && arguments.dropLast(1).none { it == "void" } &&
                    result.first() == "void" && result.size in 1..2 && result.drop(1).none { it == "void" }, "State/result shape")
                if (item["convention"] == "ccall") requireProof(emitted["symbol"] == item["symbol"], "direct C symbol changed")
                else requireProof(generated.put(emitted["unit"] to emitted["symbol"], descriptor(emitted)) == null, "duplicate generated CAPI target")
            }
            requireProof(generated.isNotEmpty(), "no generated CAPI products")
            val actual = calls(module["bindings"])
            requireProof(proof["expectedCalls"] is List<*> && proof["expectedCalls"] == actual, "Core foreign-call inventory differs")
            for (call in actual) {
                val target = (call as? Map<*, *>)?.get("target") as? Map<*, *> ?: continue
                generated[target["unit"] to target["symbol"]]?.let { expected ->
                    requireProof(expected == call, "generated CAPI call ABI differs")
                }
            }
            return ManagedImportAdmission(module)
        }
    }
}
