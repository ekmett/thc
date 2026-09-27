// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime

/** Link-time proof only; no foreign execution or dynamic exception type dispatch. */
internal object CoreForeignExceptionBridge {
    private const val MODULE = "THC.Internal.Exception"
    private const val EXCEPTION = "ghc-internal:GHC.Internal.Exception.Type.SomeException"

    @Suppress("UNCHECKED_CAST")
    fun read(module: Map<String, Any?>): Map<String, Any?>? {
        val raw = module["foreignExceptionBridge"] ?: return null
        val proof = raw as? Map<String, Any?> ?: fault("Malformed foreign exception bridge")
        val unit = module["unit"] as? String ?: fault("Bridge has no GHC unit")
        val prefix = "$unit:$MODULE."
        require(proof.keys == setOf("schema", "unit", "module", "box", "project", "payloadType", "exceptionType") &&
            (proof["schema"] == 1 || proof["schema"] == 1L) && proof["unit"] == unit &&
            module["module"] == MODULE && proof["module"] == MODULE &&
            proof["box"] == prefix + "boxForeign" && proof["project"] == prefix + "projectForeign" &&
            proof["payloadType"] == prefix + "ForeignException" && proof["exceptionType"] == EXCEPTION) {
            "Invalid foreign exception bridge identity"
        }
        val bindings = module["bindings"] as? List<Map<String, Any?>> ?: fault("Bridge has no bindings")
        for (name in listOf("box", "project")) {
            val binding = bindings.singleOrNull { it["id"] == proof[name] }
                ?: fault("Bridge helper is not defined by its genuine module: " + proof[name])
            require(binding["expr"] is List<*>) { "Invalid foreign exception helper body" }
        }
        return proof
    }

    @Suppress("UNCHECKED_CAST")
    fun select(module: Map<String, Any?>): Map<String, Any?> {
        val proofs = module["foreignExceptionBridges"] as? List<Map<String, Any?>>
            ?: read(module)?.let { listOf(it) } ?: emptyList()
        val unit = module["foreignExceptionBridgeUnit"]
        require(unit == null || unit is String && unit.isNotBlank()) { "Invalid foreign exception bridge unit" }
        val selected = if (unit == null) proofs.singleOrNull() else proofs.singleOrNull { it["unit"] == unit }
        return selected ?: fault(if (proofs.isEmpty()) "Foreign execution requires a genuine THC.Exception runtime bundle"
            else "Missing or ambiguous foreign exception bridge unit; select the application's exact runtime unit")
    }

    /** These nodes execute a foreign language. Native errno-returning adapters
     * and constructing descriptors alone do not require an exception bridge. */
    @Suppress("UNCHECKED_CAST")
    fun executes(expr: List<Any?>, defined: Boolean, links: List<thc.PackageScalarLink>): Boolean {
        val metadata = CoreRepresentations.metadata(expr) ?: return false
        val call = metadata["foreignCall"] as? Map<*, *> ?: return false
        val target = call["target"] as? Map<*, *> ?: return false
        val symbol = target["symbol"] as? String ?: return false
        val runtime = CoreRuntimeServices.validate(expr, defined)
        if (runtime != null) return runtime == RuntimeServiceCall.EXCEPTION_TEXT
        if (CoreCpuAffinity.validate(expr, defined) != null) return false
        val arguments = expr.getOrNull(2) as? List<List<Any?>> ?: return false
        val flags = expr.getOrNull(3) as? List<*> ?: return false
        val scalar = CorePackageScalarForeign.validate(metadata,
            arguments.map { CoreRepresentations.metadata(it)?.get("rep") }, flags, metadata["rep"], links)
        if (scalar != null) {
            CoreBoundThreadForeign.validateHead(expr[1] as List<Any?>, defined)
            return true
        }
        if (CoreJavaScript.validate(expr, defined) != null) return true
        if (PolyglotOp.entries.any { it.symbol == symbol }) {
            CorePolyglot.validate(expr, defined)
            return true
        }
        return false
    }
}
