// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

@file:Suppress("UNCHECKED_CAST")
package thc

import com.oracle.truffle.api.RootCallTarget
import java.util.Collections
import java.util.IdentityHashMap
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
    private val linkedAdmissions = Collections.newSetFromMap(IdentityHashMap<CoreModuleAdmission, Boolean>())
    private val consumerSources = ArrayList<CoreJsonIndex>()
    private val consumerStatistics = CoreJsonLoadingStatistics()
    private val consumers = ArrayList<Map<String, Any?>>()
    private val consumerBindings = HashMap<String, Map<String, Any?>>()
    private val consumerOwners = HashMap<String, Map<String, Any?>>()
    private val consumerAdmissions = IdentityHashMap<Map<String, Any?>, CoreModuleAdmission>()
    private val availableModules = directory.modules.mapTo(hashSetOf()) { "${it.unit}:${it.name}" }
    private var selectedBridge: Map<String, Any?>? = null
    init {
        val modules = HashSet<String>()
        try {
            CoreModules.visitUnitConsumers(input, { source, adapter ->
                consumerSources += source
                consumerStatistics.include(source, adapter)
            }) { module ->
                val unit = module["unit"] as? String ?: error("Missing loose consumer unit")
                val name = module["module"] as? String ?: error("Missing loose consumer module")
                val fragment = unit == "dependency-closure" && name == "THC.InterfaceClosure" &&
                    module["boundary"] == "actual-interface-unfoldings"
                if (!fragment) require(modules.add("$unit:$name") && "$unit:$name" !in availableModules) {
                    "Duplicate GHC module: $unit:$name"
                }
                if (module["boundary"] == "optimized-Core-after-Tidy-before-CorePrep") availableModules += "$unit:$name"
                for (binding in module["bindings"] as List<Map<String, Any?>>) {
                    val id = binding["id"] as String
                    require(consumerBindings.putIfAbsent(id, binding) == null) { "Duplicate binding: $id" }
                    consumerOwners[id] = module
                }
                addConstructors(module)
                consumers += module
            }
            for (module in consumers) module["providedModules"]?.let { provided ->
                require(module["unit"] == "dependency-closure" && module["module"] == "THC.InterfaceClosure" &&
                    module["boundary"] == "actual-interface-unfoldings" && provided is List<*> &&
                    provided.all { it is String && it in availableModules }) { "Invalid or missing provided-module interface closure" }
            }
        } catch (failure: Throwable) { consumerSources.forEach(CoreJsonIndex::close); sources.close(); throw failure }
    }
    private val demand = CoreDemandBindings({ it in consumerBindings || directory.owner(it) != null }, ::binding, ::constructor,
        ::prepare, input["instrument"] != false)
    // Cold summaries choose a calling convention, not an admission verdict.
    // AST asynchronous execution cannot capture explicit delimited control; its
    // actual selected binding is rejected by Program, not by this directory.
    private val captureDelimited = (backend != "ast" || !async) &&
        (directory.modules.any { it.containsDelimitedControl } || consumerBindings.values.any { binding ->
            val body = binding["expr"]
            if (body is CoreBindingBody) body.header.containsDelimitedControl else DelimitedControl.contains(body)
        })
    fun contains(id: String) = id in consumerBindings || directory.owner(id) != null

    private fun addConstructors(data: Map<String, Any?>) {
        for (raw in data["constructors"] as? List<Map<String, Any?>> ?: emptyList()) {
            val id = raw["id"] as? String ?: error("Missing constructor identity")
            val previous = constructors.putIfAbsent(id, raw)
            require(previous == null || previous.filterKeys { it != "type" } == raw.filterKeys { it != "type" }) {
                "Inconsistent constructor: $id"
            }
        }
    }

    private fun metadata(module: CoreUnitDirectory.ModuleRecord): Map<String, Any?> =
        admittedModules.getOrPut(module) {
            sources.metadata(module).also(::addConstructors)
        }
    private fun constructor(id: String): Map<String, Any?>? {
        constructors[id]?.let { return it }
        directory.owner(id)?.let(::metadata)
        return constructors[id]
    }
    fun binding(id: String): Map<String, Any?> {
        consumerBindings[id]?.let { binding ->
            require(directory.owner(id) == null || sources.binding(id) == null) { "Duplicate binding: $id" }
            return binding
        }
        return sources.binding(id) ?: throw UnsupportedCore("Unresolved external binding $id")
    }

    private fun consumerAdmission(module: Map<String, Any?>): CoreModuleAdmission = consumerAdmissions.getOrPut(module) {
        if (input["verifyArtifacts"] == true) {
            CoreForeignArtifacts.validateArchive(module)
            CoreModules.admission(module)
            CoreForeignExceptionBridge.read(module)
        }
        CoreModuleAdmission(module + ("bindings" to emptyList<Any>()), ::binding)
    }

    private fun admission(module: CoreUnitDirectory.ModuleRecord): CoreModuleAdmission = admissions.getOrPut(module) {
        sources.verifyModule(module)
        CoreModuleAdmission(metadata(module), ::binding)
    }

    private fun packageProvenance(unit: String): List<PackageScalarAdmission> = packageProvenance.getOrPut(unit) {
        directory.modules.filter { it.unit == unit && it.packageScalarDeclarations }.mapNotNull {
            admission(it).packageLink
        } + consumers.filter { it["unit"] == unit && (it["staticForeignImports"] as? Map<*, *>)
            ?.get("imports").let { imports -> imports is List<*> && imports.isNotEmpty() } }
            .mapNotNull { consumerAdmission(it).packageLink }
    }

    private fun bridge(): Map<String, Any?> = selectedBridge ?: run {
        val candidates = directory.modules.filter { it.name == "THC.Internal.Exception" &&
            (directory.foreignExceptionBridgeUnit == null || it.unit == directory.foreignExceptionBridgeUnit) }
        val loose = consumers.filter { it["module"] == "THC.Internal.Exception" &&
            (directory.foreignExceptionBridgeUnit == null || it["unit"] == directory.foreignExceptionBridgeUnit) }
        require(candidates.size + loose.size == 1) { "Missing or ambiguous foreign exception bridge unit" }
        val selected = if (loose.isEmpty()) admission(candidates.single()) else consumerAdmission(loose.single())
        checkNotNull(selected.bridge) {
            "Foreign execution requires a genuine THC.Exception runtime bundle"
        }.also { selectedBridge = it }
    }
    private fun prepare(id: String, binding: Map<String, Any?>): ExecutableProgram {
        val admitted = consumerOwners[id]?.let(::consumerAdmission) ?:
            admission(directory.owner(id) ?: error("Missing Core module owner: $id"))
        val merged = CoreModules.Merger(availableModules).also { merger ->
            merger.addSelected(admitted, listOf(binding))
            admitted.packageLink?.let {
                packageProvenance(admitted.module["unit"] as String).forEach(merger::addPackageProvenance)
            }
        }.finish()
        val linked = CoreModules.demanded(merged, id, demand, ::bridge) + mapOf(
            "instrument" to (input["instrument"] != false), "diagnosticUnsupported" to (input["diagnosticUnsupported"] == true),
            "sourceNotesEnabled" to (input["sourceNotesEnabled"] != false), "demandBindings" to demand,
            "captureDelimited" to captureDelimited) +
            (directory.targetLayout?.let { mapOf("targetLayout" to it) } ?: emptyMap())
        if (linkedAdmissions.add(admitted)) {
            (linked["foreignLinks"] as List<ForeignBitcode>).forEach { owner.cbits().link(it) }
            (linked["packageScalarLinks"] as List<PackageScalarLink>).forEach { owner.packageCbits.link(it) }
        }
        return if (backend == "ast") Program(language, linked, async) else BytecodeProgram(language, linked, async)
    }
    fun registerStartup(): List<ManagedExportAdmission> {
        val registrations = ArrayList<ManagedExportAdmission>()
        val pending = directory.modules.filter { it.registrationObligations }.map(::admission) +
            consumers.filter(CoreForeignArtifacts::hasRegistrationObligations).map(::consumerAdmission)
        for (admitted in pending) {
            // This also rejects unclassified native initializers before any
            // entry executes, without pretending a selected body is complete.
            CoreModules.Merger(availableModules).also { it.addSelected(admitted, emptyList()) }.finish()
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
        result["looseConsumerBindingHeaders"] = consumerBindings.size
        result.putAll(consumerStatistics())
        return result
    }
    override fun close() { try { sources.close() } finally { consumerSources.forEach(CoreJsonIndex::close) } }
}
