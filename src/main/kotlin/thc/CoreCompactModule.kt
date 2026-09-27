// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc

import thc.runtime.CoreForeignExceptionBridge
import thc.runtime.TargetLayout

/** Context-owned selected records over a shared immutable file lease. Neither
 * construction nor a cold global reference opens the container. */
internal class CoreCompactModule(private val module: CoreUnitDirectory.ModuleRecord,
    private val targetLayout: TargetLayout?, private val verifyArtifacts: Boolean) : AutoCloseable {
    private val artifact = (module.storage as CoreUnitDirectory.CompactStorage).artifact
    private val file = CoreCompactFile(artifact.path, artifact.sha256, verifyArtifacts)
    private val records = CoreCompactRecords(file, artifact.sha256)
    val counters get() = file.counters
    private var metadata: Map<String, Any?>? = null
    private val selected = HashMap<Long, Map<String, Any?>>()
    private var verified = false

    fun metadata(): Map<String, Any?> {
        metadata?.let { return it }
        val header = file.header()
        require(header.containsDelimitedControl == module.containsDelimitedControl &&
            header.registrationObligations == module.registrationObligations && header.mainAlias == module.mainAlias &&
            header.packageScalarDeclarations == module.packageScalarDeclarations) {
            "Compact Core summaries differ from package directory: ${module.prefix}"
        }
        val facts = records.header()
        require(facts["ghc"] == "9.14.1" && facts["unit"] == module.unit && facts["module"] == module.name &&
            facts["boundary"] == "optimized-Core-after-Tidy-before-CorePrep") {
            "Compact Core identity differs from package directory: ${module.prefix}"
        }
        facts["targetLayout"]?.let {
            require(TargetLayout.fromDocument(it) == targetLayout) {
                "Compact Core target layout differs from package directory"
            }
        }
        return (facts - "targetLayout" + ("bindings" to emptyList<Any>())).also {
            synchronized(counters) { counters.decodedModules++ }
            metadata = it
        }
    }

    fun containsSymbol(id: String): Boolean = file.lookup(id) != null
    fun binding(id: String): Map<String, Any?>? = file.lookup(id)?.let(::bindingAt)
    private fun bindingAt(offset: Long): Map<String, Any?> = selected.getOrPut(offset) {
        records.binding(offset).also { binding ->
            val id = binding["id"] as String
            require(id.startsWith(module.prefix) || module.mainAlias && id == "main::${module.name}.main") {
                "Compact Core binding has a different module owner: $id"
            }
            synchronized(counters) { counters.decodedBindings++ }
        }
    }

    fun verify() {
        if (!verifyArtifacts || verified) return
        val facts = metadata()
        val bindings = ArrayList<Map<String, Any?>>()
        file.verifyBindingOffsets { bindings += bindingAt(it) }
        val original = facts + ("bindings" to bindings)
        CoreForeignArtifacts.validateArchive(original)
        CoreModules.admission(original)
        CoreForeignExceptionBridge.read(original)
        verified = true
    }

    override fun close() { selected.clear(); metadata = null; file.close() }
}
