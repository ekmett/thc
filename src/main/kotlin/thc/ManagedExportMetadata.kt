// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
@file:Suppress("UNCHECKED_CAST")
package thc

import thc.runtime.CoreKind
import thc.runtime.CoreRepresentation
import thc.runtime.CoreRepresentations
import thc.runtime.TargetLayout
import thc.runtime.TupleShape
import java.util.Collections

internal data class ManagedExportSignature(val unit: String, val module: String, val symbol: String,
    val binder: String, val arguments: List<Map<String, Any?>>, val result: Map<String, Any?>,
    val io: Boolean, val wordBits: Int, val ioResult: CoreRepresentation? = null)

/** A ticket authorizes only the immutable module object that was checked. */
internal class ManagedExportAdmission private constructor(val module: Map<String, Any?>,
    val exports: List<ManagedExportSignature>) {
    companion object {
        fun read(module: Map<String, Any?>): ManagedExportAdmission {
            CoreForeignArtifacts.validateArchive(module)
            require(module["schema"] == 2L && !module.containsKey("foreignLink")) {
                "Managed exports require original, unlinked static-export products"
            }
            val unit = text(module["unit"])
            val name = text(module["module"])
            val inventory = record(module["staticForeignExports"], setOf("schema", "producer", "scope",
                "execution", "unit", "module", "exports"))
            require(inventory["schema"] == 1L && inventory["producer"] == "THC.Plugin/typeCheckResultAction" &&
                inventory["scope"] == "static-export-associations" && inventory["execution"] == "not-linked" &&
                inventory["unit"] == unit && inventory["module"] == name) { "Invalid managed export inventory owner/schema" }
            val provenance = record(module["staticForeignExportRegistration"], setOf("schema", "scope", "execution",
                "profile", "status", "roots", "wordBits", "expectedForeign", "expectedExports"))
            require(provenance["schema"] == 2L && provenance["scope"] == "retained-foreign-products" &&
                provenance["execution"] == "not-linked" && provenance["status"] == "verified" &&
                provenance["profile"] in setOf("ghc-9.14.1-thc-only-native-static-ccall-v1",
                    "ghc-9.14.1-thc-only-native-static-ccall-imports-v2") && provenance["wordBits"] == 64L) {
                "Managed exports require verified retained static-export registration"
            }
            require(provenance["expectedForeign"] == module["foreign"]) { "Managed export foreign product changed after verification" }
            require(provenance["expectedExports"] == inventory) { "Managed export inventory changed after verification" }
            val foreign = module["foreign"] as Map<String, Any?>
            val stubs = foreign["stubs"] as? Map<*, *> ?: error("Managed export registration lacks original stubs")
            require(foreign["files"] == emptyList<Any>() && stubs["finalizers"] == emptyList<Any>()) {
                "Additional foreign files/finalizers are not managed export registration"
            }
            val initializers = stubs["initializers"] as List<Map<String, Any?>>
            require(initializers.size == 1 && initializers.single()["unit"] == unit &&
                initializers.single()["module"] == name) { "Managed export registration owner changed" }
            val originals = inventory["exports"] as? List<*> ?: error("Missing static export declarations")
            require(originals.isNotEmpty()) { "No declared static exports" }
            val names = hashSetOf<String>()
            val roots = arrayListOf<Map<String, Any?>>()
            val bindings = module["bindings"] as? List<Map<String, Any?>> ?: error("Missing exported Core bindings")
            val exports = originals.map { original ->
                val export = record(original, setOf("binder", "symbol", "convention", "declaredType", "normalizedType",
                    "normalizationRole", "arguments", "result", "effect"))
                val binder = identity(export["binder"])
                require(binder["unit"] == unit && binder["module"] == name && binder["namespace"] == "value") {
                    "Static export binder has a different owner"
                }
                roots.add(binder)
                val id = "$unit:$name.${binder["occurrence"]}"
                require(bindings.count { it["id"] == id } == 1) { "Static export does not resolve to one exact Core binder: $id" }
                val symbol = text(export["symbol"])
                require(names.add(symbol)) { "Duplicate static export symbol: $unit:$name/$symbol" }
                require(export["convention"] == "ccall" && export["normalizationRole"] == "representational") {
                    "Unsupported static export convention/normalization"
                }
                type(export["declaredType"])
                var remaining = type(export["normalizedType"])
                val arguments = arrayListOf<Map<String, Any?>>()
                while (remaining["kind"] == "function") {
                    val multiplicity = remaining["multiplicity"] as Map<String, Any?>
                    require(nominal(multiplicity, "GHC.Internal.Types", "Many", "data")) {
                        "Managed export requires unrestricted arguments"
                    }
                    arguments.add(remaining["argument"] as Map<String, Any?>)
                    remaining = remaining["result"] as Map<String, Any?>
                }
                val io = export["effect"] == "io"
                require(io || export["effect"] == "pure") { "Unknown static export effect" }
                if (io) {
                    require(nominal(remaining, "GHC.Internal.Types", "IO", "type", 1)) { "IO export normalized type is not IO" }
                    remaining = (remaining["arguments"] as List<Map<String, Any?>>).single()
                } else require(!nominal(remaining, "GHC.Internal.Types", "IO", "type", 1)) { "IO export cannot claim a pure effect" }
                require(arguments == export["arguments"] && remaining == export["result"]) { "Static export signature projections disagree" }
                ManagedExportSignature(unit, name, symbol, id, arguments, remaining, io, 64)
            }
            require(provenance["roots"] == roots) { "Static export registration roots differ from declarations" }
            return ManagedExportAdmission(module, exports)
        }
        private fun text(value: Any?): String = (value as? String)?.takeIf { it.isNotEmpty() && '\u0000' !in it }
            ?: error("Invalid static export text")
        private fun record(value: Any?, keys: Set<String>): Map<String, Any?> {
            require(value is Map<*, *> && value.keys == keys) { "Missing or invalid static export metadata record" }
            return value as Map<String, Any?>
        }
        private fun identity(value: Any?): Map<String, Any?> = record(value,
            setOf("unit", "module", "occurrence", "namespace")).also { it.values.forEach(::text) }
        private fun type(value: Any?): Map<String, Any?> {
            val fields = value as? Map<String, Any?> ?: error("Invalid static export type")
            when (fields["kind"]) {
                "tycon" -> {
                    record(fields, setOf("kind", "name", "arguments")); identity(fields["name"])
                    (fields["arguments"] as? List<*> ?: error("Invalid type arguments")).forEach(::type)
                }
                "application" -> { record(fields, setOf("kind", "function", "argument")); type(fields["function"]); type(fields["argument"]) }
                "function" -> { record(fields, setOf("kind", "multiplicity", "argument", "result"))
                    type(fields["multiplicity"]); type(fields["argument"]); type(fields["result"]) }
                else -> error("Unsupported static export type structure")
            }
            return fields
        }
        private fun nominal(type: Map<String, Any?>, module: String, occurrence: String,
            namespace: String, arguments: Int = 0): Boolean = type["kind"] == "tycon" &&
            type["name"] == mapOf("unit" to "ghc-internal", "module" to module,
                "occurrence" to occurrence, "namespace" to namespace) &&
            (type["arguments"] as? List<*>)?.size == arguments
    }
}

/** Contains immutable code/metadata only. Programs and CAFs are created in State at execution. */
internal class ManagedExportPlan(val linked: Map<String, Any?>, val exports: List<ManagedExportSignature>, val backend: String) {
    companion object {
        fun read(input: Map<String, Any?>): ManagedExportPlan {
            require(input.keys.all { it in setOf("mode", "modules", "backend", "instrument", "sourceNotesEnabled", "strictLink", "targetLayout",
                "packageManifest", "packageManifestSha256") } &&
                input["mode"] == "managed-exports" && input["strictLink"] == true) { "Managed export loading requires its explicit strict request" }
            val merger = CoreModules.Merger()
            val admissions = arrayListOf<ManagedExportAdmission>()
            val layout = CoreModules.visitRequestModules(input) { original ->
                val module = immutable(original) as Map<String, Any?>
                require(!module.containsKey("foreignLink")) { "Native linked products are not managed export registration" }
                val admission = if (module["schema"] == 2L) ManagedExportAdmission.read(module) else null
                if (admission != null) admissions.add(admission)
                merger.add(module, admission)
            }
            val exports = admissions.flatMap { it.exports }
            require(exports.isNotEmpty()) { "No verified static foreign exports supplied" }
            require(layout == null || layout.wordBytes * 8 == 64) { "Managed export target word width differs" }
            val linked = CoreModules.reachable(merger.finish(),
                exports.map { it.binder }.distinct(), true) + mapOf("instrument" to (input["instrument"] != false),
                "diagnosticUnsupported" to false, "sourceNotesEnabled" to (input["sourceNotesEnabled"] != false)) +
                (if (layout == null) emptyMap() else mapOf("targetLayout" to layout))
            val bindings = linked["bindings"] as List<Map<String, Any?>>
            val checked = exports.map { export ->
                val binding = bindings.single { it["id"] == export.binder }
                val expression = binding["expr"] as List<Any?>
                val (inputs, result) = CoreRepresentations.knownFunctionSignature(expression, bindings)
                    ?: if (export.arguments.isEmpty() && !export.io)
                        emptyList<CoreRepresentation>() to CoreRepresentations.expression(expression)
                    else error("Managed export lacks a known Core function signature: ${export.binder}")
                fun boxed(proof: CoreRepresentation) = !proof.isAggregate && !proof.isVector &&
                    proof.primReps == listOf("BoxedRep (Just Lifted)") && proof.kind in setOf(CoreKind.DATA, CoreKind.OBJECT)
                require(inputs.size == export.arguments.size + (if (export.io) 1 else 0)) { "Managed export Core input count differs" }
                require(inputs.take(export.arguments.size).all(::boxed)) { "Managed export arguments must retain boxed scalar representation" }
                if (export.io) {
                    val state = inputs.last()
                    val fields = result.components
                    require(state.kind == CoreKind.VOID && state.primReps == emptyList<String>() && !state.isAggregate &&
                        result.isTuple && fields?.size == 2 && fields[0].kind == CoreKind.VOID &&
                        fields[0].primReps == emptyList<String>() && !fields[0].isAggregate && boxed(fields[1]) &&
                        result.primReps == listOf("BoxedRep (Just Lifted)")) { "Managed IO export requires its exact state/boxed-result tuple" }
                    TupleShape.validate(result)
                    export.copy(ioResult = result)
                } else { require(boxed(result)) { "Managed export result must retain boxed scalar representation" }; export }
            }
            val backend = input["backend"] as? String ?: defaultBackend()
            require(backend == "ast" || backend == "bytecode") { "Unknown THC backend: $backend" }
            return ManagedExportPlan(linked, checked, backend)
        }
        private fun immutable(value: Any?): Any? = when (value) {
            is Map<*, *> -> Collections.unmodifiableMap(value.entries.associate { it.key to immutable(it.value) })
            is List<*> -> Collections.unmodifiableList(value.map(::immutable))
            else -> value
        }
    }
}
