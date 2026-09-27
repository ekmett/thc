// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

@file:Suppress("UNCHECKED_CAST")
package thc

import com.oracle.truffle.api.RootCallTarget
import thc.runtime.*

/** One context's admitted definitions. A compiled reference to another module
 * holds a cold GlobalBinding, not that module's parsed body or header. */
internal class CoreUnitProgram(private val language: Language, private val directory: CoreUnitDirectory,
    private val input: Map<String, Any?>, private val entry: String, private val backend: String,
    private val async: Boolean, private val owner: Language.State) : ExecutableProgram, AutoCloseable {
    override val asynchronousExceptions get() = async
    override val hasBytecode get() = backend == "bytecode"
    override fun bytecodeDump(): String = demand.preparedPrograms().joinToString("\n\n") { it.bytecodeDump() }
    private val totals = ArrayList<CoreJsonSymbols.Counters>()
    private val sources = directory.open(input["verifyArtifacts"] == true, input["sourceNotesEnabled"] != false, totals::add)
    private val constructors = HashMap<String, Map<String, Any?>>()
    private val admittedModules = HashMap<CoreUnitDirectory.ModuleRecord, Map<String, Any?>>()
    private val admissions = HashMap<CoreUnitDirectory.ModuleRecord, CoreModuleAdmission>()
    private val packageProvenance = HashMap<String, List<PackageScalarAdmission>>()
    private val linkedModules = HashSet<CoreUnitDirectory.ModuleRecord>()
    private var selectedBridge: Map<String, Any?>? = null
    private val demand = CoreDemandBindings({ directory.owner(it) != null }, ::binding, ::constructor,
        ::prepare, input["instrument"] != false)

    private fun metadata(module: CoreUnitDirectory.ModuleRecord): Map<String, Any?> =
        admittedModules.getOrPut(module) {
            sources.metadata(module).also { data ->
                for (raw in data["constructors"] as? List<Map<String, Any?>> ?: emptyList()) {
                    val id = raw["id"] as? String ?: error("Missing constructor identity")
                    val previous = constructors.putIfAbsent(id, raw)
                    require(previous == null || previous.filterKeys { it != "type" } == raw.filterKeys { it != "type" }) {
                        "Inconsistent constructor: $id"
                    }
                }
            }
        }
    private fun constructor(id: String): Map<String, Any?>? {
        constructors[id]?.let { return it }
        directory.owner(id)?.let(::metadata)
        return constructors[id]
    }
    fun binding(id: String): Map<String, Any?> = sources.binding(id)
        ?: throw UnsupportedCore("Unresolved external binding $id")

    private fun admission(module: CoreUnitDirectory.ModuleRecord): CoreModuleAdmission = admissions.getOrPut(module) {
        sources.verifyModule(module)
        CoreModuleAdmission(metadata(module), ::binding)
    }

    private fun packageProvenance(unit: String): List<PackageScalarAdmission> = packageProvenance.getOrPut(unit) {
        directory.modules.filter { it.unit == unit && it.packageScalarDeclarations }.mapNotNull {
            admission(it).packageLink
        }
    }

    private fun bridge(): Map<String, Any?> = selectedBridge ?: run {
        val candidates = directory.modules.filter { it.name == "THC.Internal.Exception" &&
            (directory.foreignExceptionBridgeUnit == null || it.unit == directory.foreignExceptionBridgeUnit) }
        require(candidates.size == 1) { "Missing or ambiguous foreign exception bridge unit" }
        val module = candidates.single()
        checkNotNull(admission(module).bridge) {
            "Foreign execution requires a genuine THC.Exception runtime bundle"
        }.also { selectedBridge = it }
    }
    private fun prepare(id: String, binding: Map<String, Any?>): ExecutableProgram {
        val module = directory.owner(id) ?: error("Missing Core module owner: $id")
        val admitted = admission(module)
        val merged = CoreModules.Merger().also { merger ->
            merger.addSelected(admitted, listOf(binding))
            admitted.packageLink?.let {
                packageProvenance(module.unit).forEach(merger::addPackageProvenance)
            }
        }.finish()
        val linked = CoreModules.demanded(merged, id, demand, ::bridge) + mapOf(
            "instrument" to (input["instrument"] != false), "diagnosticUnsupported" to (input["diagnosticUnsupported"] == true),
            "sourceNotesEnabled" to (input["sourceNotesEnabled"] != false), "demandBindings" to demand) +
            (directory.targetLayout?.let { mapOf("targetLayout" to it) } ?: emptyMap())
        if (linkedModules.add(module)) {
            (linked["foreignLinks"] as List<ForeignBitcode>).forEach { owner.cbits().link(it) }
            (linked["packageScalarLinks"] as List<PackageScalarLink>).forEach { owner.packageCbits.link(it) }
        }
        return if (backend == "ast") Program(language, linked, async) else BytecodeProgram(language, linked, async)
    }
    fun registerStartup(): List<ManagedExportAdmission> {
        val registrations = ArrayList<ManagedExportAdmission>()
        for (module in directory.modules.filter { it.registrationObligations }) {
            val admitted = admission(module)
            // This also rejects unclassified native initializers before any
            // entry executes, without pretending a selected body is complete.
            CoreModules.Merger().also { it.addSelected(admitted, emptyList()) }.finish()
            admitted.exports?.let { exports ->
                registrations += exports
                exports.exports.forEach { entryValue(it.binder) }
            }
        }
        return registrations
    }
    /** Host entry signature resolution follows only the selected alias/PAP
     * spine, never other references in that function's body. */
    fun signatureBindings(id: String): List<Map<String, Any?>> {
        val selected = LinkedHashMap<String, Map<String, Any?>>()
        fun follow(expression: List<Any?>) {
            when (expression.firstOrNull()) {
                "var" -> {
                    val next = expression[1] as String
                    if (next !in selected) {
                        val binding = binding(next)
                        selected[next] = binding
                        follow(binding["expr"] as List<Any?>)
                    }
                }
                "app" -> follow(expression[1] as List<Any?>)
            }
        }
        val binding = binding(id)
        selected[id] = binding
        follow(binding["expr"] as List<Any?>)
        return selected.values.toList()
    }
    override fun hostEntryTarget(arity: Int): RootCallTarget = demand.program(entry).hostEntryTarget(arity)
    override fun entryValue(name: String): Any? = (demand.cell(name)
        ?: throw UnsupportedCore("Unresolved external binding $name")).read()
    override fun entryTarget(name: String): RootCallTarget = demand.program(name).entryTarget(name)
    override fun constructorLayout(id: String): DataLayout = demand.program(entry).constructorLayout(id)
    override fun diagnostics(): Map<String, Any> {
        val programs = demand.preparedPrograms().map { it.diagnostics() }
        val result = programs.first().toMutableMap()
        for (field in listOf("loweredRootCount", "bytecodeRootCount", "sourceRootCount", "hostEntryRootCount", "initializedBindingCount")) {
            if (programs.any { field in it }) result[field] = programs.sumOf { (it[field] as? Number)?.toLong() ?: 0L }
        }
        val counters = totals.map { it.statistics() }
        result["unsupportedPolicy"] = "reject-at-binding-admission"
        result["coreUnitSourceOpens"] = counters.sumOf { it.sourceOpens }
        result["coreUnitDirectoryOpens"] = counters.sumOf { it.directoryOpens }
        result["coreUnitSourceMappedBytes"] = counters.sumOf { it.sourceMappedBytes }
        result["coreUnitDirectoryMappedBytes"] = counters.sumOf { it.directoryMappedBytes }
        result["coreUnitSourceByteReads"] = counters.sumOf { it.sourceByteReads }
        result["coreUnitDirectoryByteReads"] = counters.sumOf { it.directoryByteReads }
        result["coreUnitHashBytesScanned"] = counters.sumOf { it.hashBytesScanned }
        result["coreUnitDecodedBindings"] = counters.sumOf { it.decodedBindings }
        result["coreUnitDecodedModules"] = counters.sumOf { it.decodedModules }
        result["coreUnitDecodedBytes"] = counters.sumOf { it.decodedBytes }
        result["coreUnitMetadataBytes"] = counters.sumOf { it.metadataBytes }
        result["coreUnitVerifiedModuleBytes"] = counters.sumOf { it.verifiedModuleBytes }
        return result
    }
    override fun close() = sources.close()
}
