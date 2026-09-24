// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime

import com.oracle.truffle.api.RootCallTarget

/** The original GHC identity of a globally named Core function or thunk. */
internal data class CoreFunctionIdentity(
    val bindingId: String,
    val unitId: String,
    val moduleName: String,
    val occurrence: String
) {
    companion object {
        fun from(module: Map<String, Any?>, binding: Map<String, Any?>): CoreFunctionIdentity? {
            val id = binding["id"] as? String ?: return null
            val owner = (module["bindingOrigins"] as? Map<*, *>)?.get(id) as? Map<*, *>
            val unit = (owner?.get("unit") ?: module["unit"]) as? String ?: return null
            val moduleName = (owner?.get("module") ?: module["module"]) as? String ?: return null
            val prefix = "$unit:$moduleName."
            if (!id.startsWith(prefix) || id.length == prefix.length) return null
            val occurrence = id.removePrefix(prefix)
            return CoreFunctionIdentity(id, unit, moduleName, occurrence)
        }

        /** Assign only the target created for this binding, never an aliased target. */
        fun install(module: Map<String, Any?>, binding: Map<String, Any?>, value: Any?) {
            val identity = from(module, binding) ?: return
            val rhs = binding["expr"] as? List<*> ?: return
            val head = rhs.firstOrNull()
            val certifiedApplication = head == "app" &&
                ((rhs.getOrNull(5) as? Boolean) ?: (rhs.getOrNull(4) == true))
            val target: RootCallTarget = when {
                head == "lam" && value is Closure -> value.target
                binding["lifted"] == true && !certifiedApplication &&
                    head !in listOf("var", "lit", "lam", "con", "prim", "void") && value is Thunk ->
                    value.target ?: return
                else -> return
            }
            (target.rootNode as? GuestRoot)?.configureCoreIdentity(identity)
        }
    }
}
