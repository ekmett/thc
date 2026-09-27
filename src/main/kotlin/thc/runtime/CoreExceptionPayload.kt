// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime

/** Exact producer type provenance, never inferred from runtime values. Legacy
 * captures without this marker keep primitive exception payloads opaque/lazy. */
internal object CoreExceptionPayload {
    const val TYPE = "ghc-internal:GHC.Internal.Exception.Type.SomeException"
    fun validate(expression: List<Any?>): Boolean {
        val proof = CoreRepresentations.metadata(expression)?.get("exceptionPayload") ?: return false
        val record = proof as? Map<*, *> ?: fault("Invalid exception payload provenance")
        val head = expression.getOrNull(1) as? List<*>
        if (expression.firstOrNull() != "app" || head?.firstOrNull() != "prim" ||
            head.getOrNull(1) !in listOf("raise#", "raiseIO#") ||
            record.keys != setOf("schema", "type") || record["schema"] !in listOf(1, 1L) ||
            record["type"] != TYPE) fault("Invalid SomeException raise provenance")
        return true
    }
}
