// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc

/** Original module metadata is admitted once. A selected binding is not a
 * replacement for the original module's complete foreign-call inventory. */
internal class CoreModuleAdmission(val module: Map<String, Any?>,
    binding: (String) -> Map<String, Any?>) {
    val exports = CoreModules.admission(module, binding)
    init { CoreForeignArtifacts.validateArchive(module, completeBindings = false) }
    val archive = PackageNativeArchives.read(module, completeBindings = false)
    val packageLink = PackageScalarLinks.read(module, completeBindings = false)
    val foreignLink = CoreForeignArtifacts.linked(module, completeBindings = false)
    val imports = if (packageLink == null) ManagedImportAdmission.read(module, completeBindings = false) else null
    val bridge = if (module.containsKey("foreignExceptionBridge")) {
        val prefix = "${module["unit"]}:THC.Internal.Exception."
        thc.runtime.CoreForeignExceptionBridge.read(module + ("bindings" to
            listOf(binding(prefix + "boxForeign"), binding(prefix + "projectForeign"))))
    } else null
    private val inventories = listOf("staticForeignImports", "staticForeignImportStubs").mapNotNull { key ->
        (module[key] as? Map<*, *>)?.takeIf { it["status"] == "verified" }?.let {
            (it["expectedCalls"] as? List<*> ?: error("Missing original Core foreign-call inventory"))
                .groupingBy { call -> call }.eachCount()
        }
    }

    fun selected(bindings: List<Map<String, Any?>>): Map<String, Any?> {
        val actual = PackageNativeArchive.calls(bindings)
        val counts = actual.groupingBy { it }.eachCount()
        for (inventory in inventories) {
            require(counts.all { (call, count) -> count <= (inventory[call] ?: 0) }) {
                "Demanded Core foreign call is absent from original inventory"
            }
        }
        foreignLink?.let { CoreForeignArtifacts.validateCalls(bindings, it, complete = false) }
        imports?.validateCalls(actual)
        return module + ("bindings" to bindings)
    }
}

/** Complete comparison belongs to exhaustive admission. A demanded subset
 * must still account for every actual descriptor, including multiplicity. */
internal object CoreCallInventory {
    fun check(expected: Any?, actual: List<*>, complete: Boolean) {
        require(expected is List<*>) { "Missing original Core foreign-call inventory" }
        if (complete) require(expected == actual) { "Retained Core foreign-call inventory differs" }
        else {
            val remaining = expected.groupingBy { it }.eachCount().toMutableMap()
            for (call in actual) {
                val count = remaining[call] ?: 0
                require(count > 0) { "Demanded Core foreign call is absent from original inventory" }
                remaining[call] = count - 1
            }
        }
    }
}
