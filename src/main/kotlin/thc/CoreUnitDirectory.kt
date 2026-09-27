// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc

import java.nio.file.Path
import thc.runtime.TargetLayout

/** The small package directory owns names of modules, never names of bindings.
 * Unit files remain unopened until a binding or that module's metadata is used. */
internal class CoreUnitDirectory private constructor(val units: List<UnitRecord>,
    val foreignExceptionBridgeUnit: String?, val targetLayout: TargetLayout?) {
    data class Artifact(val path: Path, val sha256: String)
    data class UnitRecord(val id: String, val json: Artifact, val symbols: Artifact,
        val modules: List<ModuleRecord>)
    data class ModuleRecord(val unit: String, val name: String, val span: CoreJsonSymbols.ModuleSpan,
        val metadata: CoreJsonSymbols.ValueSpan, val sourceMetadata: CoreJsonSymbols.ValueSpan?,
        val containsDelimitedControl: Boolean, val registrationObligations: Boolean, val mainAlias: Boolean,
        val packageScalarDeclarations: Boolean) {
        val prefix = "$unit:$name."
    }
    val modules = units.flatMap { it.modules }
    private val owners = modules.associateBy { it.prefix }
    private val aliases = modules.filter { it.mainAlias }.groupBy { "main::${it.name}.main" }
    fun owner(id: String): ModuleRecord? {
        aliases[id]?.let { matches ->
            require(matches.size == 1) { "Ambiguous Core entry alias: $id" }
            return matches.single()
        }
        var end = id.lastIndexOf('.')
        while (end >= 0) {
            owners[id.substring(0, end + 1)]?.let { if (end + 1 < id.length) return it }
            end = id.lastIndexOf('.', end - 1)
        }
        return null
    }

    fun open(verifyArtifacts: Boolean, sourceNotes: Boolean = true,
             admitted: (CoreJsonSymbols.Counters) -> Unit = {}) = Sources(this, verifyArtifacts, sourceNotes, admitted)

    class Sources(private val directory: CoreUnitDirectory, private val verifyArtifacts: Boolean,
                  private val sourceNotes: Boolean, private val admitted: (CoreJsonSymbols.Counters) -> Unit) : AutoCloseable {
        private var closed = false
        private val readers = HashMap<String, CoreJsonSymbols>()
        private val metadata = HashMap<ModuleRecord, Map<String, Any?>>()
        private fun reader(unit: String): CoreJsonSymbols {
            check(!closed) { "Core unit sources are closed" }
            return readers.getOrPut(unit) {
                val record = directory.units.single { it.id == unit }
                CoreJsonSymbols(record.json.path, record.symbols.path, verifyArtifacts,
                    record.json.sha256, record.symbols.sha256).also { admitted(it.counters) }
            }
        }
        @Synchronized fun metadata(module: ModuleRecord): Map<String, Any?> {
            check(!closed) { "Core unit sources are closed" }
            return metadata.getOrPut(module) {
                val reader = reader(module.unit)
                val required = reader.metadata(module.metadata, true)
                require(required.keys.all { it in METADATA_FIELDS }) { "Unexpected Core admission metadata field" }
                val notes = if (sourceNotes) module.sourceMetadata?.let {
                    reader.metadata(it, false).also { source ->
                        require(source.keys.all { it in setOf("sourceFiles", "sourceSpans") }) { "Unexpected Core source metadata field" }
                    }
                } ?: emptyMap() else emptyMap()
                (required + notes + ("bindings" to emptyList<Any>())).also {
                    require(it["unit"] == module.unit && it["module"] == module.name &&
                        it["ghc"] == "9.14.1" && it["boundary"] == BOUNDARY) {
                        "Core module identity differs from package directory: ${module.prefix}"
                    }
                }
            }
        }
        @Synchronized fun binding(id: String): Map<String, Any?>? {
            check(!closed) { "Core unit sources are closed" }
            val module = directory.owner(id) ?: return null
            return reader(module.unit).binding(id, module.span)
        }
        @Synchronized fun counters(): List<CoreJsonSymbols.Counters> = readers.values.map { it.counters }
        @Synchronized override fun close() {
            if (closed) return
            closed = true
            metadata.clear()
            try { readers.values.forEach { it.close() } } finally { readers.clear() }
        }
    }

    companion object {
        private const val BOUNDARY = "optimized-Core-after-Tidy-before-CorePrep"
        private val METADATA_FIELDS = setOf("schema", "ghc", "unit", "module", "boundary", "providedModules", "constructors",
            "foreign", "foreignLink", "staticForeignImportStubs", "staticForeignImports", "staticForeignExports",
            "staticForeignExportRegistration", "packageScalarLink", "packageNativeLink", "packageNativeArchive",
            "foreignExceptionBridge", "foreignExceptionBridgeUnit")
        private val SHA = Regex("[0-9a-f]{64}")
        /** Null selects the unchanged legacy package protocol, not a silent
         * sidecar discovery or conversion. New unit pairs are explicit. */
        fun read(document: Map<*, *>): CoreUnitDirectory? {
            val rawUnits = document["units"] as? List<*> ?: error("Missing package units")
            if (rawUnits.none { it is Map<*, *> && (it.containsKey("json") || it.containsKey("symbols")) }) return null
            require(document["format"] == "thc-core-packages" && document["schema"] == 1L &&
                document["ghc"] == "9.14.1") { "Core package manifest requires schema 1 / GHC 9.14.1" }
            val unitIds = HashSet<String>()
            val artifactPaths = HashSet<Path>()
            var layout: TargetLayout? = null
            fun artifact(raw: Any?): Artifact {
                val record = raw as? Map<*, *> ?: error("Missing unit artifact")
                val path = Path.of(record["path"] as? String ?: error("Missing unit artifact path"))
                val hash = record["sha256"] as? String ?: error("Missing unit artifact identity")
                require(record.keys == setOf("path", "sha256") && path.isAbsolute && hash.matches(SHA) &&
                    artifactPaths.add(path.normalize())) { "Invalid or duplicate unit artifact reference" }
                // No toRealPath/stat/open: a cold unit need not exist yet.
                return Artifact(path.normalize(), hash)
            }
            val units = rawUnits.map { raw ->
                val unit = raw as? Map<*, *> ?: error("Invalid package unit")
                val id = unit["id"] as? String ?: error("Missing GHC unit ID")
                require(id.isNotEmpty() && unitIds.add(id)) { "Invalid or duplicate GHC unit ID: $id" }
                val depends = unit["depends"] as? List<*> ?: error("Missing GHC unit dependencies")
                require(depends.all { it is String && it.isNotEmpty() } && depends.distinct().size == depends.size) {
                    "Invalid dependencies for GHC unit $id"
                }
                require(!unit.containsKey("bundle")) { "Unit pair must not also select a ZIP bundle: $id" }
                val names = HashSet<String>()
                var previousEnd = 0L
                val modules = (unit["modules"] as? List<*> ?: error("Missing unit modules")).map { item ->
                    val module = item as? Map<*, *> ?: error("Invalid module record")
                    val name = module["name"] as? String ?: error("Missing module name")
                    require(name.isNotEmpty() && names.add(name) && module["boundary"] == BOUNDARY &&
                        (module["sha256"] as? String)?.matches(SHA) == true) { "Invalid module directory record: $id:$name" }
                    fun offset(field: String) = (module[field] as? Long)?.also {
                        require(it >= 0) { "Negative module byte offset: $field" }
                    } ?: error("Missing exact module byte offset: $field")
                    val span = CoreJsonSymbols.ModuleSpan(offset("start"), offset("end"),
                        offset("bindingsStart"), offset("bindingsEnd"))
                    require(span.start >= previousEnd && span.start < span.bindingsStart &&
                        span.bindingsStart < span.bindingsEnd && span.bindingsEnd < span.end) { "Invalid module extents" }
                    val metadata = CoreJsonSymbols.ValueSpan(offset("metadataStart"), offset("metadataEnd"))
                    require(metadata.start >= span.end && metadata.end > metadata.start) { "Invalid Core admission metadata extent" }
                    require(module.containsKey("sourceMetadataStart") == module.containsKey("sourceMetadataEnd")) { "Incomplete Core source metadata extent" }
                    val notes = if (module.containsKey("sourceMetadataStart"))
                        CoreJsonSymbols.ValueSpan(offset("sourceMetadataStart"), offset("sourceMetadataEnd")).also {
                            require(it.start >= metadata.end && it.end > it.start) { "Invalid Core source metadata extent" }
                        } else null
                    previousEnd = notes?.end ?: metadata.end
                    ModuleRecord(id, name, span, metadata, notes,
                        module["containsDelimitedControl"] as? Boolean ?: error("Missing delimited-control summary"),
                        module["registrationObligations"] as? Boolean ?: error("Missing registration summary"),
                        module["mainAlias"] as? Boolean ?: error("Missing main-alias summary"),
                        module["packageScalarDeclarations"] as? Boolean ?: error("Missing package declaration summary"))
                }
                unit["targetLayout"]?.let { rawLayout ->
                    val candidate = TargetLayout.fromDocument(rawLayout)
                    require(layout == null || layout == candidate) { "Conflicting GHC target layouts" }
                    layout = candidate
                }
                UnitRecord(id, artifact(unit["json"]), artifact(unit["symbols"]), modules)
            }
            val bridge = document["foreignExceptionBridgeUnit"]
            require(bridge == null || bridge is String && bridge.isNotBlank()) { "Invalid foreign exception bridge unit" }
            return CoreUnitDirectory(units, bridge as String?, layout)
        }
    }
}
