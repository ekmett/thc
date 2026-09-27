// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc

import com.oracle.truffle.api.dsl.Cached
import com.oracle.truffle.api.RootCallTarget
import com.oracle.truffle.api.Truffle
import com.oracle.truffle.api.nodes.Node
import com.oracle.truffle.api.nodes.DirectCallNode
import com.oracle.truffle.api.nodes.IndirectCallNode
import com.oracle.truffle.api.nodes.NodeUtil
import thc.runtime.TargetCache
import thc.runtime.Metrics
import thc.runtime.Calls
import com.oracle.truffle.api.CallTarget
import com.oracle.truffle.api.CompilerDirectives
import com.oracle.truffle.api.TruffleLanguage
import com.oracle.truffle.api.TruffleSafepoint
import com.oracle.truffle.api.frame.VirtualFrame
import com.oracle.truffle.api.interop.InteropLibrary
import com.oracle.truffle.api.interop.InvalidArrayIndexException
import com.oracle.truffle.api.interop.TruffleObject
import com.oracle.truffle.api.interop.UnknownIdentifierException
import com.oracle.truffle.api.library.ExportLibrary
import com.oracle.truffle.api.library.ExportMessage
import com.oracle.truffle.api.nodes.RootNode
import thc.runtime.Program
import thc.runtime.BytecodeProgram
import thc.runtime.ExecutableProgram
import thc.runtime.CoreArithmeticExceptions
import thc.runtime.CoreRepresentations
import thc.runtime.CoreRepresentation
import thc.runtime.IoMainRoot
import thc.runtime.TargetLayout
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.atomic.AtomicReference
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Runtime diagnostics retain cumulative admission/work totals, not the owners
 * of unused source snapshots or projections. Reachable spans retain their own
 * source independently; their existing operation monitors publish live counts.
 */
internal class CoreJsonLoadingStatistics : () -> Map<String, Any> {
    private val sources = ArrayList<CoreJsonIndex.Counters>()
    private val adapters = linkedSetOf<CoreJsonBindings.Counters>()
    val isEmpty: Boolean get() = sources.isEmpty()
    fun include(source: CoreJsonIndex, adapter: CoreJsonBindings) {
        sources += source.counters
        adapters += adapter.counters
    }
    override fun invoke(): Map<String, Any> {
        val sourceTotals = sources.map(CoreJsonIndex.Counters::statistics)
        val adapterTotals = adapters.map(CoreJsonBindings.Counters::statistics)
        return mapOf("jsonSourceBytes" to sourceTotals.sumOf { it.sourceByteSize.toLong() },
            "jsonIndexPrimitiveBytes" to sourceTotals.sumOf { it.indexByteSize },
            "jsonSidecarBytes" to sourceTotals.sumOf { it.serializedByteSize ?: 0L },
            "jsonSourceFileBytesRead" to sourceTotals.sumOf { it.sourceFileBytesRead },
            "jsonSourceHashBytesScanned" to sourceTotals.sumOf { it.sourceHashBytesScanned },
            "jsonStructuralBytesScanned" to sourceTotals.sumOf { it.structuralBytesScanned },
            "jsonIndexSourceBytesScanned" to sourceTotals.sumOf { it.indexSourceBytesScanned },
            "jsonDecodedSpanCount" to sourceTotals.sumOf { it.decodedSpanCount },
            "jsonDecodedByteCount" to sourceTotals.sumOf { it.decodedByteCount },
            "jsonNavigationByteReads" to sourceTotals.sumOf { it.navigationByteReads },
            "jsonRegeneratedSourceBytes" to sourceTotals.sumOf { it.regeneratedSourceBytes },
            "jsonBindingHeaders" to adapterTotals.sumOf { it.bindingHeaders },
            "jsonBodyMaterializations" to adapterTotals.sumOf { it.bodyMaterializations },
            "jsonExpressionViews" to adapterTotals.sumOf { it.expressionViews },
            "jsonLinkingExpressionViews" to adapterTotals.sumOf { it.linkingExpressionViews },
            "jsonScalarDecodes" to adapterTotals.sumOf { it.scalarDecodes },
            "jsonLinkingScalarDecodes" to adapterTotals.sumOf { it.linkingScalarDecodes },
            "jsonSummaryExpressionsVisited" to adapterTotals.sumOf { it.summaryExpressionsVisited },
            "jsonCanonicalStrings" to adapterTotals.sumOf { it.canonicalStrings })
    }
}

/**
 * Internal Core assembly and request serialization shared by the launcher and tests.
 * Original unit identities, foreign obligations and strict reachable references
 * remain subject to validation. These map-based structures are not a stable host ABI;
 * embedding callers should normally use [loadEntry].
 */
object CoreModules {
    // Only the host-side request builder can authorize a deferred file read.
    // Arbitrary guest Source JSON must never acquire a new host-filesystem API.
    private val packageKey = ByteArray(32).also { SecureRandom().nextBytes(it) }
    private fun packageCapability(path: String, sha256: String, verifyArtifacts: Boolean): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(packageKey, "HmacSHA256"))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
            mac.doFinal((verifyArtifacts.toString() + ":" + path.length + ":" + path + ":" + sha256).toByteArray(Charsets.UTF_8)))
    }

    internal fun admission(module: Map<String, Any?>,
                           binding: ((String) -> Map<String, Any?>)? = null): ManagedExportAdmission? =
        if ((module["schema"] == 2L || module["schema"] == 2) &&
            module.containsKey("staticForeignExportRegistration") &&
            CoreForeignArtifacts.hasRegistrationObligations(module)) ManagedExportAdmission.read(module, binding) else null

    fun merge(modules: List<Map<String, Any?>>): Map<String, Any?> = Merger().also { merger ->
        modules.forEach { merger.add(it) }
    }.finish()

    /** Retain only linked definitions, never the complete raw package request. */
    internal class Merger(private val availableModules: Set<String> = emptySet()) {
        private var count = 0
        private val admissions = arrayListOf<ManagedExportAdmission>()
        private val bindings = linkedMapOf<String, Map<String, Any?>>()
        private val constructors = linkedMapOf<String, Map<String, Any?>>()
        private val sourceFiles = linkedMapOf<String, Map<String, Any?>>()
        private val sourceSpans = linkedMapOf<String, Map<String, Any?>>()
        private val bindingOrigins = linkedMapOf<String, Map<String, String>>()
        private val foreignLinks = linkedMapOf<Pair<String, String>, ForeignBitcode>()
        private val packageScalarLinks = linkedMapOf<String, PackageScalarLink>()
        private val packageScalarProofs = linkedMapOf<String, MutableSet<String>>()
        private val archiveBindings = linkedMapOf<String, String>()
        private val exceptionBridges = linkedMapOf<String, Map<String, Any?>>()
        private var exceptionBridgeUnit: String? = null
        private val moduleKeys = hashSetOf<Pair<String, String>>()
        private val providedModules = hashSetOf<String>()
        private val completeModules = hashSetOf<String>()
        @Suppress("UNCHECKED_CAST")
        fun add(module: Map<String, Any?>, admission: ManagedExportAdmission? = CoreModules.admission(module)) =
            append(module, admission, null)

        fun addSelected(admission: CoreModuleAdmission, bindings: List<Map<String, Any?>>) =
            append(admission.selected(bindings), admission.exports, admission)

        /** Declaration-only modules contribute their original typed ABI
         * provenance, not bindings, constructors, or registration roots. */
        fun addPackageProvenance(admission: PackageScalarAdmission) = packageProvenance(admission)

        private fun packageProvenance(admission: PackageScalarAdmission) {
            val link = admission.link
            require(packageScalarLinks.values.none { it.unit != link.unit && it.componentSha256 == link.componentSha256 }) {
                "Package C entry namespace belongs to another unit: ${link.componentSha256}"
            }
            val previous = packageScalarLinks.putIfAbsent(link.unit, link)
            require(previous == null || previous.same(link)) { "Conflicting package C component: ${link.unit}" }
            packageScalarProofs.getOrPut(link.unit) { linkedSetOf() }.addAll(admission.proved)
        }

        @Suppress("UNCHECKED_CAST")
        private fun append(module: Map<String, Any?>, admission: ManagedExportAdmission?, prepared: CoreModuleAdmission?) {
            require(admission == null || admission.module === (prepared?.module ?: module)) {
                "Managed export admission belongs to a different Core module"
            }
            count++
            (if (prepared == null) thc.runtime.CoreForeignExceptionBridge.read(module) else prepared.bridge)?.let { proof ->
                require(exceptionBridges.putIfAbsent(proof["unit"] as String, proof) == null) { "Duplicate foreign exception bridge unit" }
            }
            (module["foreignExceptionBridgeUnit"] as? String)?.let { unit ->
                require(exceptionBridgeUnit == null || exceptionBridgeUnit == unit) { "Conflicting foreign exception bridge selection" }
                exceptionBridgeUnit = unit
            }
            // Direct-unit startup registration is owned by CoreUnitProgram,
            // not repeated in every selected binding's program.
            if (admission != null && prepared == null) admissions.add(admission)
            if (prepared == null) CoreForeignArtifacts.validateArchive(module)
            val nativeArchive = if (prepared == null) PackageNativeArchives.read(module) else prepared.archive
            val packageLink = if (prepared == null) PackageScalarLinks.read(module) else prepared.packageLink
            packageLink?.let(::packageProvenance)
            val link = if (prepared == null) CoreForeignArtifacts.linked(module) else prepared.foreignLink
            val archiveOnly = module["schema"] == 2L || module["schema"] == 2
            val managedExport = admission != null
            val managedImports = if (prepared == null) packageLink == null && ManagedImportAdmission.read(module) != null
                else prepared.imports != null
            if (archiveOnly && link == null && packageLink == null && !managedExport && !managedImports && CoreForeignArtifacts.hasRegistrationObligations(module))
                CoreForeignArtifacts.requireExecutable(module)
            link?.let {
                require(foreignLinks.putIfAbsent(link.unit to link.module, link) == null) {
                    "Duplicate linked foreign module: ${link.unit}:${link.module}"
                }
            }
            require(module["ghc"] == "9.14.1") { "This adapter requires GHC 9.14.1 exports" }
            val unit = module["unit"] as? String
            val name = module["module"] as? String
            val interfaceFragment = unit == "dependency-closure" && name == "THC.InterfaceClosure" &&
                module["boundary"] == "actual-interface-unfoldings"
            if (unit != null && name != null && !interfaceFragment) {
                require(moduleKeys.add(unit to name)) { "Duplicate GHC module: $unit:$name" }
                if (module["boundary"] == "optimized-Core-after-Tidy-before-CorePrep")
                    completeModules.add("$unit:$name")
            }
            module["providedModules"]?.let { supplied ->
                require(interfaceFragment && supplied is List<*> && supplied.all { it is String && it.isNotBlank() }) {
                    "Invalid provided-module interface closure"
                }
                providedModules.addAll(supplied.filterIsInstance<String>())
            }
            for ((key, table) in listOf("sourceFiles" to sourceFiles, "sourceSpans" to sourceSpans)) {
                val records = module[key] ?: continue
                require(records is List<*>) { "Invalid $key table" }
                for (record in records) {
                    require(record is Map<*, *>) { "Invalid $key record" }
                    val id = record["id"] as? String ?: error("Missing $key identity")
                    val source = record as Map<String, Any?>
                    val old = table.putIfAbsent(id, source)
                    require(old == null || old == source) { "Inconsistent $key record: $id" }
                }
            }
            for (b in module["bindings"] as List<Map<String, Any?>>) {
                val id = b["id"] as String
                require(bindings.putIfAbsent(id, b) == null) { "Duplicate binding: $id" }
                if (nativeArchive?.blocks(b) == true)
                    archiveBindings[id] = nativeArchive.detail
                else if (nativeArchive == null && archiveOnly && link == null && packageLink == null && !managedExport && !managedImports)
                    archiveBindings[id] = "${module["unit"]}:${module["module"]}"
                // The merged bundle has no single unit/module. Preserve the exact
                // exporting module for globally named bindings; synthetic entries
                // and interface fragments must not acquire a guessed owner.
                if (unit != null && name != null && id.startsWith("$unit:$name.") && id.length > "$unit:$name.".length)
                    bindingOrigins[id] = mapOf("unit" to unit, "module" to name)
            }
            for (c in module["constructors"] as List<Map<String, Any?>>) {
                val id = c["id"] as String
                val old = constructors.putIfAbsent(id, c)
                // The pretty-printed type can differ by quantified variable
                // names; all layout and future metadata fields must still agree.
                require(old == null || old.filterKeys { it != "type" } == c.filterKeys { it != "type" }) {
                    "Inconsistent constructor: $id"
                }
            }
        }
        fun finish(): Map<String, Any?> {
            require(count != 0) { "No Core modules supplied" }
            require((completeModules + availableModules).containsAll(providedModules)) {
                "Interface closure lacks its exact complete provided modules: ${providedModules - completeModules - availableModules}"
            }
            packageScalarLinks.forEach { (unit, link) ->
                require(packageScalarProofs[unit] == link.abi.map { it.entry }.toSet()) {
                    "Package C ABI lacks complete typed import provenance: $unit"
                }
            }
            return mapOf("schema" to 1L, "ghc" to "9.14.1", "module" to "THC.Bundle",
                "bindings" to bindings.values.toList(), "constructors" to constructors.values.toList(),
                "bindingOrigins" to bindingOrigins,
                "foreignExceptionBridges" to exceptionBridges.values.toList(),
                "foreignExceptionBridgeUnit" to exceptionBridgeUnit,
                "archiveBindings" to archiveBindings,
                "managedRegistrations" to admissions.toList(),
                "foreignLinks" to foreignLinks.values.toList(),
                "packageScalarLinks" to packageScalarLinks.values.toList(),
                "sourceFiles" to sourceFiles.values.toList(), "sourceSpans" to sourceSpans.values.toList())
        }
    }

    @Suppress("UNCHECKED_CAST")
    fun reachable(module: Map<String, Any?>, entry: String, strictLink: Boolean = false): Map<String, Any?> =
        reachable(module, listOf(entry), strictLink)

    @Suppress("UNCHECKED_CAST")
    fun reachable(module: Map<String, Any?>, entries: List<String>, strictLink: Boolean = false): Map<String, Any?> =
        linkedBindings(module, entries, strictLink, null, null)

    internal fun demanded(module: Map<String, Any?>, entry: String, demand: thc.runtime.CoreDemandBindings,
                          bridge: () -> Map<String, Any?>): Map<String, Any?> =
        linkedBindings(module, listOf(entry), true, demand, bridge)

    @Suppress("UNCHECKED_CAST")
    private fun linkedBindings(module: Map<String, Any?>, entries: List<String>, strictLink: Boolean,
                               demand: thc.runtime.CoreDemandBindings?, bridge: (() -> Map<String, Any?>)?): Map<String, Any?> {
        if (module.containsKey("archiveBindings")) CoreForeignArtifacts.validateArchive(module)
        else CoreForeignArtifacts.requireExecutableInput(module)
        val bindings = module["bindings"] as List<Map<String, Any?>>
        val byId = bindings.associateBy { it["id"] as String }
        val constructorIds = (module["constructors"] as? List<Map<String, Any?>>)
            ?.mapTo(hashSetOf()) { it["id"] as String } ?: emptySet()
        require(entries.isNotEmpty() && entries.distinct().size == entries.size) { "Missing or duplicate Core entries" }
        val reachable = linkedSetOf<String>()
        val pending = ArrayDeque<String>()
        val missing = linkedMapOf<String, MutableSet<String>>()
        val missingConstructors = linkedMapOf<String, MutableSet<String>>()
        var owner = ""
        var exceptionBridge: Map<String, Any?>? = null
        fun constructor(id: String) {
            if (strictLink && id !in constructorIds && demand?.constructors(emptyMap())?.containsKey(id) != true)
                missingConstructors.getOrPut(id) { linkedSetOf() }.add(owner)
        }
        fun reference(id: String, bound: Set<String>) {
            if (id in bound) return
            if (id in byId) {
                if (reachable.add(id)) pending.addLast(id)
            } else if (demand?.contains(id) == true) demand.cell(id)
            else if (strictLink) missing.getOrPut(id) { linkedSetOf() }.add(owner)
        }
        fun visit(expr: List<Any?>, bound: Set<String>) {
            when (expr[0]) {
                "var" -> reference(expr[1] as String, bound)
                "prim" -> {
                    val name = expr[1] as String
                    if (thc.runtime.CoreFileWait.named(name)) reference(thc.runtime.CoreFileWait.badFd, emptySet())
                    CoreArithmeticExceptions.payload(name)?.let { reference(it, emptySet()) }
                    if (thc.runtime.CompactOp.named(name)?.adds == true)
                        thc.runtime.CompactOp.failures.forEach { reference(it, emptySet()) }
                    if (name == "atomically#") reference(thc.runtime.STMOp.NESTED, emptySet())
                }
                "lam" -> {
                    val ids = (expr[1] as List<Map<String, Any?>>).map { it["id"] as String }
                    visit(expr[2] as List<Any?>, bound + ids)
                }
                "app" -> {
                    val function = expr[1] as List<Any?>
                    thc.runtime.CoreExceptionPayload.validate(expr)
                    val foreignDescriptor = CoreRepresentations.metadata(expr)?.get("foreignCall") is Map<*, *>
                    val defined = function.firstOrNull() == "var" && (function.getOrNull(1) in bound ||
                        function.getOrNull(1) in byId || (function.getOrNull(1) as? String)?.let {
                            if (foreignDescriptor) demand?.isDefined(it) else demand?.contains(it)
                        } == true)
                    if (thc.runtime.CoreForeignExceptionBridge.executes(expr, defined,
                        module["packageScalarLinks"] as? List<PackageScalarLink> ?: emptyList())) {
                        val selectedBridge = exceptionBridge ?: (bridge?.invoke() ?: thc.runtime.CoreForeignExceptionBridge.select(module))
                            .also { exceptionBridge = it }
                        reference(selectedBridge["box"] as String, emptySet())
                        reference(selectedBridge["project"] as String, emptySet())
                    }
                    if (thc.runtime.CoreSignalForeign.named(CoreRepresentations.metadata(expr)))
                        reference(thc.runtime.CoreSignalForeign.dispatcher, emptySet())
                    // FCallIds name foreign declarations, not Haskell globals.
                    // Lowering validates the complete ABI and rejects unsupported
                    // targets. Defined heads and all operands still participate
                    // in linking; metadata cannot hide their dependencies.
                    val foreignHead = foreignDescriptor &&
                        function.firstOrNull() == "var" && function.getOrNull(1) is String &&
                        !defined
                    if (!foreignHead) visit(function, bound)
                    (expr[2] as List<List<Any?>>).forEach { visit(it, bound) }
                }
                "let" -> {
                    val group = expr[2] as List<Map<String, Any?>>
                    val ids = group.map { it["id"] as String }
                    val rhsScope = if (expr[1] == true) bound + ids else bound
                    group.forEach { visit(it["expr"] as List<Any?>, rhsScope) }
                    visit(expr[3] as List<Any?>, bound + ids)
                }
                "case" -> {
                    visit(expr[1] as List<Any?>, bound)
                    val alternatives = expr[3] as List<List<Any?>>
                    alternatives.forEach { alt ->
                        if (alt[0] == "data") constructor(alt[1] as String)
                        visit(alt[3] as List<Any?>, bound + (expr[2] as String) + (alt[2] as List<String>))
                    }
                }
                "con" -> constructor(expr[1] as String)
            }
        }
        // Constructing a retained closure still requires a supported body. This
        // validates its dependencies; registration never invokes it.
        val registrations = module["managedRegistrations"] as? List<ManagedExportAdmission> ?: emptyList()
        val roots = (entries + registrations.flatMap { it.exports }.map { it.binder }).distinct()
        for (entry in roots) {
            val exact = byId[entry]
            val matches = if (exact != null) listOf(exact) else bindings.filter { it["name"] == entry }
            require(matches.size == 1) { "Missing or ambiguous entry: $entry" }
            val root = matches.single()["id"] as String
            if (reachable.add(root)) pending.addLast(root)
        }
        while (pending.isNotEmpty()) {
            owner = pending.removeFirst()
            val body = byId.getValue(owner)["expr"] as List<Any?>
            if (body is CoreBindingBody) body.visitForLinking { visit(it, emptySet()) }
            else visit(body, emptySet())
        }
        require(missing.isEmpty()) {
            "Unlinked Core globals: " + missing.entries.joinToString { (id, uses) -> "$id referenced by ${uses.joinToString()}" }
        }
        require(missingConstructors.isEmpty()) {
            "Unlinked Core constructors: " + missingConstructors.entries.joinToString { (id, uses) -> "$id referenced by ${uses.joinToString()}" }
        }
        val archived = module["archiveBindings"] as? Map<String, String> ?: emptyMap()
        for (id in reachable) archived[id]?.let { owner ->
            throw IllegalArgumentException("Unsupported foreign code/registration for $owner: " +
                "Core schema 2 is archive-only; native stubs, initializers, finalizers and callbacks are not linked")
        }
        return (module - "archiveBindings") + ("bindings" to bindings.filter { it["id"] in reachable }) +
            ("selectedForeignExceptionBridge" to exceptionBridge)
    }

    /**
     * Serialize a load request from Core files and at most one `@manifest` path.
     * Manifest loading selects strict linking; this does not execute the entry
     * or bypass binding admission. File reads require process-local capabilities.
     * Normal loading trusts supplied artifacts; explicit [verifyArtifacts] checks
     * complete hashes and source/index agreement before admitting their snapshots.
     */
    fun request(paths: List<String>, entry: String, instrument: Boolean = true, diagnosticUnsupported: Boolean = false,
                backend: String = defaultBackend(), sourceNotesEnabled: Boolean = true, ioMain: Boolean = false,
                shutdownEntry: String? = null, asyncExceptions: Boolean? = null,
                jsonSidecars: Map<String, String>? = null, verifyArtifacts: Boolean = false): String {
        require(shutdownEntry == null || (ioMain && shutdownEntry.isNotBlank() && shutdownEntry != entry)) {
            "Executable shutdown requires a distinct IO entry"
        }
        val hasManifest = paths.any { it.startsWith("@") }
        val settings = linkedMapOf<String, Any>(
            "entry" to entry, "instrument" to instrument,
            "diagnosticUnsupported" to diagnosticUnsupported, "backend" to backend,
            "sourceNotesEnabled" to sourceNotesEnabled, "verifyArtifacts" to verifyArtifacts)
        if (hasManifest) settings["strictLink"] = true
        if (ioMain) settings["ioMain"] = true
        if (shutdownEntry != null) settings["shutdownEntry"] = shutdownEntry
        if (asyncExceptions != null) settings["asyncExceptions"] = asyncExceptions
        return if (jsonSidecars == null) requestDocument(paths, settings)
            else indexedRequestDocument(paths, jsonSidecars, settings)
    }

    internal fun managedExportRequest(paths: List<String>, backend: String, instrument: Boolean,
                                      verifyArtifacts: Boolean = false): String =
        requestDocument(paths, mapOf("mode" to "managed-exports", "backend" to backend,
            "instrument" to instrument, "strictLink" to true, "verifyArtifacts" to verifyArtifacts))

    @Suppress("UNCHECKED_CAST")
    internal fun visitRequestModules(input: Map<String, Any?>, accept: (Map<String, Any?>) -> Unit): TargetLayout? =
        visitRequestModules(input, { _, _ -> }, accept)

    @Suppress("UNCHECKED_CAST")
    internal fun visitRequestModules(input: Map<String, Any?>,
        indexed: (CoreJsonIndex, CoreJsonBindings) -> Unit,
        accept: (Map<String, Any?>) -> Unit): TargetLayout? {
        require(input["verifyArtifacts"] == null || input["verifyArtifacts"] is Boolean) { "verifyArtifacts must be a Boolean" }
        val verifyArtifacts = input["verifyArtifacts"] == true
        val files = input["indexedModuleFiles"]
        if (files != null) {
            require(files is List<*> && files.isNotEmpty() &&
                listOf("modules", "consumerModules", "targetLayout")
                    .all { input[it] == null }) { "Indexed module request must not mix input protocols" }
            require(input["packageManifest"] != null ||
                input["packageManifestSha256"] == null && input["packageCapability"] == null) {
                "Orphan package manifest identity"
            }
            val adapter = CoreJsonBindings(input["sourceNotesEnabled"] != false)
            val opened = ArrayList<CoreJsonIndex>()
            try {
                // Remove this component before replaying the independently
                // authenticated package request. Consumers remain consumers:
                // interface fragments do not acquire package-unit ownership.
                val layout = if (input["packageManifest"] == null) null else
                    visitRequestModules(input - "indexedModuleFiles", { source, projection ->
                        opened += source
                        indexed(source, projection)
                    }, accept)
                val seenPaths = HashSet<String>()
                for (raw in files) {
                    val file = raw as? Map<*, *> ?: error("Invalid indexed Core descriptor")
                    require(file.keys == setOf("path", "sha256", "sidecar", "sidecarSha256", "capability")) {
                        "Invalid indexed Core descriptor fields"
                    }
                    val path = file["path"] as? String ?: error("Missing indexed Core path")
                    require(seenPaths.add(path)) { "Duplicate indexed Core path: $path" }
                    val sha = file["sha256"] as? String ?: error("Missing indexed Core identity")
                    val sidecar = file["sidecar"] as? String ?: error("Missing JSON sidecar path")
                    val sidecarSha = file["sidecarSha256"] as? String ?: error("Missing JSON sidecar identity")
                    val capability = file["capability"] as? String ?: error("Missing indexed Core capability")
                    require((if (verifyArtifacts) sha.matches(Regex("[0-9a-f]{64}")) && sidecarSha.matches(Regex("[0-9a-f]{64}"))
                            else sha.isEmpty() && sidecarSha.isEmpty()) &&
                        MessageDigest.isEqual(capability.toByteArray(Charsets.US_ASCII),
                            indexedCapability(path, sha, sidecar, sidecarSha, verifyArtifacts).toByteArray(Charsets.US_ASCII))) {
                        "Invalid indexed Core capability"
                    }
                    val indexBytes = Files.readAllBytes(Path.of(sidecar))
                    if (verifyArtifacts) require(sha256(indexBytes) == sidecarSha) { "JSON sidecar changed after request: $sidecar" }
                    val source = indexBytes.inputStream().use { CoreJsonIndex.loadSidecar(Path.of(path), it, verifyArtifacts) }
                    opened += source
                    if (verifyArtifacts) require(source.sha256() == sha) { "Core JSON changed after request: $path" }
                    accept(adapter.module(source.root) + ("foreignExceptionBridgeUnit" to input["foreignExceptionBridgeUnit"]))
                    indexed(source, adapter)
                }
                // These are byte snapshots, not open file handles. Parsed Engine
                // roots own their immutable sources; Context disposal must not
                // close another Context's shared parse plan. GC releases them.
                return layout
            } catch (failure: Throwable) {
                opened.forEach(CoreJsonIndex::close)
                throw failure
            }
        }
        val manifest = input["packageManifest"]
        if (manifest != null) {
            require(manifest is String && input["modules"] == null && input["targetLayout"] == null) {
                "Package request must not mix manifest and inline modules"
            }
            val expected = input["packageManifestSha256"] as? String
                ?: error("Missing package manifest identity")
            val supplied = input["packageCapability"] as? String
                ?: error("Missing package request capability")
            require(MessageDigest.isEqual(supplied.toByteArray(Charsets.US_ASCII),
                packageCapability(manifest, expected, verifyArtifacts).toByteArray(Charsets.US_ASCII))) {
                "Invalid package request capability"
            }
            val consumers = input["consumerModules"]
            require(consumers == null || consumers is List<*> && consumers.all { it is Map<*, *> }) {
                "Invalid loose package consumers"
            }
            val result = CorePackageManifest.visitRuntimeModules(manifest, expected,
                input["sourceNotesEnabled"] != false, verifyArtifacts, indexed) { module ->
                accept(module + ("foreignExceptionBridgeUnit" to input["foreignExceptionBridgeUnit"]))
            }
            require(input["foreignExceptionBridgeUnit"] == result.foreignExceptionBridgeUnit) { "Package bridge selection changed after request" }
            (consumers as? List<Map<String, Any?>>)?.forEach {
                accept(it + ("foreignExceptionBridgeUnit" to result.foreignExceptionBridgeUnit))
            }
            return result.targetLayout
        }
        require(input["packageManifestSha256"] == null && input["packageCapability"] == null && input["consumerModules"] == null) {
            "Orphan package manifest identity"
        }
        val modules = input["modules"] as? List<Map<String, Any?>> ?: error("Expected modules array")
        modules.forEach { accept(it + ("foreignExceptionBridgeUnit" to input["foreignExceptionBridgeUnit"])) }
        return input["targetLayout"]?.let(TargetLayout::fromDocument)
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }
    /** Request identity needs every byte, but does not retain a source snapshot.
     * Admission still reads, pins and verifies its own immutable byte snapshot.
     */
    private fun sha256(path: Path): String = Files.newInputStream(path).use { input ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(8192)
        while (true) {
            val size = input.read(buffer)
            if (size < 0) break
            digest.update(buffer, 0, size)
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }
    private fun indexedCapability(path: String, sha: String, sidecar: String, sidecarSha: String,
                                  verifyArtifacts: Boolean): String =
        packageCapability("indexed-json:" + Json.stringify(listOf(path, sidecar, sidecarSha)), sha, verifyArtifacts)

    internal fun unitDirectory(input: Map<String, Any?>): CoreUnitDirectory? {
        val manifest = input["packageManifest"] as? String ?: return null
        require(input["modules"] == null && input["targetLayout"] == null) { "Package request must not mix input protocols" }
        require(input["verifyArtifacts"] == null || input["verifyArtifacts"] is Boolean) { "verifyArtifacts must be a Boolean" }
        val verify = input["verifyArtifacts"] == true
        val expected = input["packageManifestSha256"] as? String ?: error("Missing package manifest identity")
        val supplied = input["packageCapability"] as? String ?: error("Missing package request capability")
        require(MessageDigest.isEqual(supplied.toByteArray(Charsets.US_ASCII),
            packageCapability(manifest, expected, verify).toByteArray(Charsets.US_ASCII))) { "Invalid package request capability" }
        val bytes = Files.readAllBytes(Path.of(manifest))
        if (verify) require(sha256(bytes) == expected) { "Core package manifest changed after request: $manifest" }
        val document = Json.parse(bytes.toString(Charsets.UTF_8)) as? Map<*, *> ?: error("Invalid Core package manifest")
        val directory = CoreUnitDirectory.read(document) ?: return null
        require(input["foreignExceptionBridgeUnit"] == directory.foreignExceptionBridgeUnit) { "Package bridge selection changed after request" }
        return directory
    }

    /** Replay only explicitly supplied consumers. Package definitions stay in
     * their unit directory; an interface closure never becomes a package unit. */
    internal fun visitUnitConsumers(input: Map<String, Any?>,
        indexed: (CoreJsonIndex, CoreJsonBindings) -> Unit, accept: (Map<String, Any?>) -> Unit) {
        val files = input["indexedModuleFiles"]
        val consumers = input["consumerModules"]
        require(files == null || consumers == null) { "Mixed loose consumer protocols" }
        val loose = input - setOf("packageManifest", "packageManifestSha256", "packageCapability", "consumerModules")
        if (files != null) visitRequestModules(loose, indexed, accept)
        else if (consumers != null) {
            require(consumers is List<*> && consumers.all { it is Map<*, *> }) { "Invalid loose package consumers" }
            visitRequestModules(loose + ("modules" to consumers), indexed, accept)
        }
    }

    /** Opt-in host path: exact loose JSON and explicit producer sidecars, without
     * embedding or parsing a module body while assembling the load request.
     * An optional package manifest retains its independently checked identity.
     * Whole-file hashes and source/index agreement scans are explicit opt-in work.
     */
    private fun indexedRequestDocument(paths: List<String>, sidecars: Map<String, String>, settings: Map<String, Any>): String {
        val verifyArtifacts = settings["verifyArtifacts"] == true
        val manifests = paths.filter { it.startsWith("@") }
        val loose = paths.filterNot { it.startsWith("@") }
        require(manifests.size <= 1 && paths.distinct().size == paths.size && loose.isNotEmpty() &&
            sidecars.keys == loose.toSet()) {
            "Explicit JSON sidecars require exactly the listed loose JSON inputs and at most one package manifest"
        }
        val seenPaths = HashSet<String>()
        val files = loose.map { raw ->
            val path = Path.of(raw).toRealPath().toString()
            require(seenPaths.add(path)) { "Duplicate indexed Core path: $path" }
            val sidecar = Path.of(sidecars.getValue(raw)).toRealPath().toString()
            val sha = if (verifyArtifacts) sha256(Path.of(path)) else ""
            val sidecarSha = if (verifyArtifacts) sha256(Path.of(sidecar)) else ""
            mapOf("path" to path, "sha256" to sha, "sidecar" to sidecar, "sidecarSha256" to sidecarSha,
                "capability" to indexedCapability(path, sha, sidecar, sidecarSha, verifyArtifacts))
        }
        val identity = manifests.singleOrNull()?.let {
            checkNotNull(CorePackageManifest.indexedRequestIdentity(it.drop(1), forceDescriptor = true, verifyArtifacts = verifyArtifacts))
        }
        return Json.stringify(settings + mapOf("indexedModuleFiles" to files) +
            (identity?.let { mapOf("packageManifest" to it.manifestPath,
                "packageManifestSha256" to it.manifestSha256,
                "packageCapability" to packageCapability(it.manifestPath, it.manifestSha256, verifyArtifacts),
                "foreignExceptionBridgeUnit" to it.foreignExceptionBridgeUnit) } ?: emptyMap()))
    }

    private fun requestDocument(paths: List<String>, settings: Map<String, Any>): String {
        val verifyArtifacts = settings["verifyArtifacts"] == true
        val manifests = paths.filter { it.startsWith("@") }
        require(manifests.size <= 1) { "A Core request accepts at most one package manifest" }
        val manifest = manifests.singleOrNull()?.drop(1)
        val options = StringBuilder().also { Json.appendObjectDocument(it,
            Json.stringify(settings)) }
        if (manifest != null) {
            val consumers = paths.filterNot { it.startsWith("@") }.map { File(it).readText() }
            CorePackageManifest.indexedRequestIdentity(manifest, verifyArtifacts = verifyArtifacts)?.let { identity ->
                return Json.stringify(settings + mapOf(
                    "packageManifest" to identity.manifestPath,
                    "packageManifestSha256" to identity.manifestSha256,
                    "packageCapability" to packageCapability(identity.manifestPath, identity.manifestSha256, verifyArtifacts),
                    "foreignExceptionBridgeUnit" to identity.foreignExceptionBridgeUnit) +
                    (if (consumers.isEmpty()) emptyMap() else mapOf("consumerModules" to consumers.map(Json::parse))))
            }
            // Small requests retain their established JSON shape. Large package
            // sets carry a content-bound manifest reference, never a combined
            // multi-gigabyte module document or raw modules array.
            val limit = 2 * 1024 * 1024
            var inline: StringBuilder? = StringBuilder()
            var count = 0
            val result = CorePackageManifest.visitModules(manifest, verifyArtifacts = verifyArtifacts) { _, source ->
                val destination = inline
                if (destination != null) {
                    if (destination.length + source.length > limit) inline = null
                    else {
                        if (count++ != 0) destination.append(',')
                        Json.appendObjectDocument(destination, source)
                    }
                }
            }
            if (inline == null) return Json.stringify(settings + mapOf(
                "packageManifest" to result.manifestPath,
                "packageManifestSha256" to result.manifestSha256,
                "packageCapability" to packageCapability(result.manifestPath, result.manifestSha256, verifyArtifacts),
                "foreignExceptionBridgeUnit" to result.foreignExceptionBridgeUnit) +
                (if (consumers.isEmpty()) emptyMap() else mapOf("consumerModules" to consumers.map(Json::parse))))
            return buildString {
                append(options, 0, options.length - 1)
                append(",\"modules\":[").append(inline)
                consumers.forEach { source ->
                    if (count++ != 0) append(',')
                    Json.appendObjectDocument(this, source)
                }
                append(']')
                result.targetLayout?.let { append(",\"targetLayout\":").append(Json.stringify(it.document())) }
                result.foreignExceptionBridgeUnit?.let { append(",\"foreignExceptionBridgeUnit\":").append(Json.stringify(it)) }
                append('}')
            }
        }
        return buildString {
            append(options, 0, options.length - 1)
            append(",\"modules\":[")
            paths.forEachIndexed { index, path ->
                if (index != 0) append(',')
                // Loose files preserve the existing explicit document protocol.
                Json.appendObjectDocument(this, File(path).readText())
            }
            append(']')
            append('}')
        }
    }
}

@TruffleLanguage.Registration(id = "thc", name = "Turbo Haskell Compiler", version = "0.1-experiment",
    characterMimeTypes = ["application/x-thc-core"], defaultMimeType = "application/x-thc-core",
    dependentLanguages = ["llvm"], contextPolicy = TruffleLanguage.ContextPolicy.EXCLUSIVE)
class Language : TruffleLanguage<Language.State>() {
    // Layout interning belongs to a context even when the language instance is shared.
    internal val handoffLayouts: thc.runtime.HandoffLayouts get() = currentState(null).handoffLayouts
    internal val handoffState = locals.createContextThreadLocal { _, _ -> thc.runtime.HandoffState() }
    private val threadPollState = locals.createContextThreadLocal { context, thread -> context.threads.pollState(thread) }
    private val threadMaskingState = locals.createContextThreadLocal { context, thread -> context.maskingState.cell(thread) }
    private val threadAnnotations = locals.createContextThreadLocal { context, thread -> context.stackAnnotations.cell(thread) }
    class State(val env: Env, language: Language) {
        internal val shutdown = java.util.concurrent.atomic.AtomicReference<thc.runtime.GuestShutdown>()
        internal val managedExports = ManagedExportRegistry(this, language)
        internal val foreignRoots = ManagedForeignRoots(this)
        internal val handoffLayouts = thc.runtime.HandoffLayouts(language)
        internal val javaScriptImports = thc.runtime.JavaScriptImports()
        internal val packageCbits = thc.runtime.PackageScalarLibraries(env)
        internal val maskingState = thc.runtime.CarrierLocal(thc.runtime.MaskingState.UNMASKED)
        internal val stackAnnotations = thc.runtime.CarrierLocal(thc.runtime.StackAnnotationState.EMPTY)
        internal val threads = thc.runtime.GuestThreads(env, maskingState)
        internal val threadPollState = language.threadPollState
        internal val threadMaskingState = language.threadMaskingState
        internal val threadAnnotations = language.threadAnnotations
        internal val runtimeTrace = thc.runtime.RuntimeTraceServices(env.err())
        internal val runtimeJit = thc.runtime.RuntimeJitServices(language)
        @JvmField internal val stm = thc.runtime.ManagedSTM()
        internal val files = thc.runtime.ManagedFiles(env, threads)
        internal val rtsFileLocks = thc.runtime.RtsFileLocks()
        // Installed only by the explicit fixed-filesystem NativeIO factory.
        // Ordinary/custom Context builders retain the embedding file service;
        // the CLI and explicit NativeIO factory install the fixed native provider.
        internal var nativeFiles: thc.runtime.NativeFileProvider? = null
        internal var windowsDirectories: thc.runtime.WindowsDirectoryStreams? = null
        internal val windowsCodePages = thc.runtime.WindowsCodePages(this)
        internal val stdio = thc.runtime.ManagedStdio(files)
        internal val signalMask = thc.runtime.ManagedSignalMask(this)
        internal val signals = thc.runtime.ManagedSignals(this, language)
        internal val savedTermios = thc.runtime.SavedTermios(this)
        internal val iconv = thc.runtime.ManagedIconv({ cbits() }, stdio, threads)
        internal val strerror = thc.runtime.ManagedStrerror({ cbits() }, threads)
        internal val stackSnapshots = thc.runtime.ManagedStackRegistry()
        @JvmField internal val closureInfo = thc.runtime.ClosureInfoTables()
        internal val capturedAsyncRequests = thc.runtime.CapturedAsyncRequests()
        internal val foreignExceptionRegistry = thc.runtime.ForeignExceptionRegistry()
        internal val foreignExceptionNormalization = ThreadLocal.withInitial { false }
        internal val coreUnitPrograms = java.util.concurrent.CopyOnWriteArrayList<CoreUnitProgram>()
        internal val stablePointers = thc.runtime.StablePointers()
        internal val compilerRts = thc.runtime.CompilerRts()
        internal val stableNames = thc.runtime.StableNames()
        @JvmField internal val compactRegions = thc.runtime.ManagedCompacts()
        @JvmField internal val heapAddresses = thc.runtime.HeapAddresses()
        @JvmField internal val compactImages = thc.runtime.CompactImages(compactRegions, heapAddresses)
        internal val nativeAddresses = thc.runtime.NativeAddresses(env)
        internal val nativeAllocations = thc.runtime.ManagedNativeAllocations(env)
        internal val arguments = thc.runtime.GuestArguments(env)
        internal val environment = thc.runtime.GuestEnvironment(env)
        internal val weaks = thc.runtime.ManagedWeaks()
        // A future SHARED policy may keep the lockless thunk path while this is valid.
        // The transition is one-way and belongs to this context, not to Language.
        internal val singleThreadedAssumption = Truffle.getRuntime().createAssumption("THC single-threaded context")
        private var firstThread: Thread? = null
        @Synchronized internal fun noteThread(thread: Thread) {
            if (firstThread == null) firstThread = thread
            else if (firstThread !== thread) markMultithreaded()
        }
        internal fun markMultithreaded() {
            singleThreadedAssumption.invalidate("A second guest thread entered the context")
        }
        private val nativeCbits = AtomicReference<FutureTask<thc.runtime.SulongCbits>?>()
        private val nativeLimbs = AtomicReference<FutureTask<thc.runtime.LimbProvider>?>()
        @CompilerDirectives.TruffleBoundary
        internal fun limbs(): thc.runtime.LimbProvider {
            if (!env.isNativeAccessAllowed)
                throw thc.runtime.RuntimeFault("Native GMP arithmetic requires native access")
            var task = nativeLimbs.get()
            if (task == null) {
                val candidate = FutureTask<thc.runtime.LimbProvider> { thc.runtime.SulongLimbProvider(env) }
                if (nativeLimbs.compareAndSet(null, candidate)) {
                    task = candidate
                    candidate.run() // Parse LLVM without holding a monitor across guest code.
                } else task = nativeLimbs.get()
            }
            return try {
                val selected = task!!
                if (selected.isDone) selected.get()
                else TruffleSafepoint.setBlockedThreadInterruptibleFunction(null,
                    TruffleSafepoint.InterruptibleFunction<FutureTask<thc.runtime.LimbProvider>, thc.runtime.LimbProvider> {
                        waiting -> waiting.get()
                    }, selected)
            } catch (failure: ExecutionException) {
                nativeLimbs.compareAndSet(task, null)
                throw (failure.cause ?: failure)
            }
        }
        @CompilerDirectives.TruffleBoundary
        internal fun cbits(): thc.runtime.SulongCbits {
            if (!env.isNativeAccessAllowed)
                throw thc.runtime.RuntimeFault("C bitcode requires native access for the Sulong runtime")
            var task = nativeCbits.get()
            if (task == null) {
                val candidate = FutureTask { thc.runtime.SulongCbits(env) }
                if (nativeCbits.compareAndSet(null, candidate)) {
                    task = candidate
                    candidate.run() // Parsing LLVM can execute guest code; never hold a cache lock here.
                } else task = nativeCbits.get()
            }
            return try {
                val selected = task!!
                if (selected.isDone) selected.get()
                else TruffleSafepoint.setBlockedThreadInterruptibleFunction(null,
                    TruffleSafepoint.InterruptibleFunction<FutureTask<thc.runtime.SulongCbits>, thc.runtime.SulongCbits> {
                        waiting -> waiting.get()
                    }, selected)
            } catch (failure: ExecutionException) {
                nativeCbits.compareAndSet(task, null)
                throw (failure.cause ?: failure)
            }
        }
    }
    override fun createContext(env: Env): State = State(env, this)
    override fun getScope(context: State): Any = context.managedExports.scope
    override fun isThreadAccessAllowed(thread: Thread, singleThreaded: Boolean): Boolean = true
    override fun exitContext(context: State, exitMode: ExitMode, exitCode: Int) {
        try { context.files.shutdownEventManagers() } finally {
            context.signals.requestStop()
            // LLVM calls are still permitted during exit notification, before hard
            // exit starts unwinding all contexts. dispose() is idempotent.
            context.iconv.dispose()
        }
    }
    override fun finalizeContext(context: State) {
        try { context.files.shutdownEventManagers() }
        finally { try { context.signals.close() } finally { context.iconv.dispose() } }
    }
    override fun disposeContext(context: State) {
        context.compilerRts.close()
        try { context.runtimeJit.close() } finally { context.runtimeTrace.close() }
        context.compactImages.close()
        context.heapAddresses.close()
        context.managedExports.close()
        context.packageCbits.close()
        context.foreignRoots.close()
        context.savedTermios.close()
        try {
            try { try { context.stm.close() } finally { context.threads.close() } } finally {
                try { context.capturedAsyncRequests.close() } finally {
                    try { context.files.dispose() } finally {
                        try { context.stdio.dispose() } finally {
                            try { context.rtsFileLocks.dispose() } finally { context.stackSnapshots.dispose() }
                        }
                    }
                }
            }
        } finally {
            try { try { context.weaks.close() } finally { context.stableNames.close() } } finally {
                try { context.stablePointers.close() } finally {
                    try { context.nativeAddresses.close() } finally {
                        try { context.nativeAllocations.close() } finally {
                            try { context.coreUnitPrograms.forEach { it.close() } }
                            finally { context.coreUnitPrograms.clear() }
                        }
                    }
                }
            }
        }
    }
    override fun initializeThread(context: State, thread: Thread) = context.noteThread(thread)
    override fun initializeMultiThreading(context: State) = context.markMultithreaded()
    companion object {
        private val contexts = ContextReference.create(Language::class.java)
        @JvmStatic fun currentState(node: Node? = null): State = contexts.get(node)
    }
    @Suppress("UNCHECKED_CAST")
    override fun parse(request: ParsingRequest): CallTarget {
        val input = Json.parse(request.source.characters.toString()) as Map<String, Any?>
        if (input["mode"] == "managed-exports") {
            val backend = ManagedExportPlan.backend(input)
            CoreModules.unitDirectory(input)?.let { directory ->
                return object : RootNode(this) {
                    override fun execute(frame: VirtualFrame): Any =
                        currentState(this).managedExports.load(input, directory, backend)
                    override fun getName() = "THC load managed exports from unit directory"
                }.callTarget
            }
            val plan = ManagedExportPlan.read(input)
            return object : RootNode(this) {
                override fun execute(frame: VirtualFrame): Any = currentState(this).managedExports.load(plan)
                override fun getName() = "THC load managed exports"
            }.callTarget
        }
        CoreModules.unitDirectory(input)?.let { directory ->
            return unitRoot(input, directory)
        }
        val merger = CoreModules.Merger()
        val entry = input["entry"] as? String ?: error("Expected entry name")
        val shutdownEntry = input["shutdownEntry"] as? String
        require(input["shutdownEntry"] == null ||
            (input["ioMain"] == true && !shutdownEntry.isNullOrBlank() && shutdownEntry != entry)) {
            "Executable shutdown requires a distinct IO entry"
        }
        require(input["ioMain"] != true || input["diagnosticUnsupported"] != true) {
            "IO main requires strict unsupported-Core rejection"
        }
        val loadingStatistics = CoreJsonLoadingStatistics()
        val layout = CoreModules.visitRequestModules(input, indexed = loadingStatistics::include) { merger.add(it) }
        val linked = CoreModules.reachable(merger.finish(),
            if (shutdownEntry == null) listOf(entry) else listOf(entry, shutdownEntry),
            input["strictLink"] == true) + mapOf("instrument" to (input["instrument"] != false),
            "diagnosticUnsupported" to (input["diagnosticUnsupported"] == true),
            "sourceNotesEnabled" to (input["sourceNotesEnabled"] != false)) +
            (if (layout == null) emptyMap() else mapOf("targetLayout" to layout)) +
            (if (loadingStatistics.isEmpty) emptyMap() else mapOf("coreLoadingStatistics" to loadingStatistics))
        val bindings = linked["bindings"] as List<Map<String, Any?>>
        val selected = bindings.singleOrNull { it["id"] == entry } ?: bindings.single { it["name"] == entry }
        val selectedExpression = selected["expr"] as List<Any?>
        val ioResult = if (input["ioMain"] == true) CoreRepresentations.ioUnitMainResult(selected, bindings) else null
        val shutdownResult = shutdownEntry?.let { name ->
            val shutdown = bindings.singleOrNull { it["id"] == name }
                ?: throw IllegalArgumentException("Missing exact executable shutdown entry: $name")
            CoreRepresentations.ioUnitMainResult(shutdown, bindings)
        }
        val hostResultFault = if (ioResult != null) null else try {
            thc.runtime.CoreRepresentations.knownFunctionSignature(selectedExpression, bindings)?.let { (inputs, result) ->
                inputs.forEach { thc.runtime.CoreRepresentations.requireScalar(it, "host argument") }
                thc.runtime.CoreRepresentations.requireScalar(result, "host result")
            }
            if (selectedExpression.firstOrNull() == "lam") {
                for (parameter in selectedExpression[1] as List<Map<String, Any?>>)
                    thc.runtime.CoreRepresentations.requireScalar(thc.runtime.CoreRepresentations.binder(parameter), "host argument")
                thc.runtime.CoreRepresentations.requireScalar(
                    thc.runtime.CoreRepresentations.lambdaResult(selectedExpression), "host result")
            }
            null
        } catch (gap: thc.runtime.UnsupportedCore) {
            if (input["diagnosticUnsupported"] != true) throw gap
            gap.message
        }
        val backend = input["backend"] ?: defaultBackend()
        require(backend == "ast" || backend == "bytecode") { "Unknown THC backend: $backend" }
        require(!input.containsKey("asyncExceptions") || input["asyncExceptions"] is Boolean) {
            "asyncExceptions must be a Boolean"
        }
        val asyncExceptions = input["asyncExceptions"] as? Boolean ?: (backend == "bytecode")
        val registrations = linked["managedRegistrations"] as List<ManagedExportAdmission>
        return object : RootNode(this) {
            override fun execute(frame: VirtualFrame): Any {
                // Parsed roots can be shared by an Engine. Programs, CAFs and
                // registration roots belong to the Context executing the load.
                val owner = currentState(this)
                (linked["foreignLinks"] as List<ForeignBitcode>).forEach { owner.cbits().link(it) }
                (linked["packageScalarLinks"] as List<PackageScalarLink>).forEach { owner.packageCbits.link(it) }
                val program = if (backend == "ast") Program(this@Language, linked, asyncExceptions)
                    else BytecodeProgram(this@Language, linked, asyncExceptions)
                val value = EntryValue(program, entry, (selected["arity"] as Number).toInt(), hostResultFault,
                    ioResult, this@Language, shutdownEntry, shutdownResult,
                    bindings.any { it["id"] == thc.runtime.CoreSignalForeign.dispatcher })
                owner.foreignRoots.retain(program, registrations)
                return value
            }
            override fun getName(): String = "THC load $entry"
        }.callTarget
    }

    private fun unitRoot(input: Map<String, Any?>, directory: CoreUnitDirectory): CallTarget {
        val entry = input["entry"] as? String ?: error("Expected entry name")
        val shutdown = input["shutdownEntry"] as? String
        require(shutdown == null || input["ioMain"] == true && shutdown.isNotBlank() && shutdown != entry) {
            "Executable shutdown requires a distinct IO entry"
        }
        val backend = input["backend"] ?: defaultBackend()
        require(backend == "ast" || backend == "bytecode") { "Unknown THC backend: $backend" }
        require(input["asyncExceptions"] == null || input["asyncExceptions"] is Boolean) { "asyncExceptions must be a Boolean" }
        val async = input["asyncExceptions"] as? Boolean ?: (backend == "bytecode")
        require(input["ioMain"] != true || input["diagnosticUnsupported"] != true) { "IO main requires strict unsupported-Core rejection" }
        return object : RootNode(this) {
            override fun execute(frame: VirtualFrame): Any {
                val owner = currentState(this)
                val program = CoreUnitProgram(this@Language, directory, input, entry, backend as String, async, owner)
                try {
                    val bindings = program.signatureBindings(entry)
                    val selected = bindings.single { it["id"] == entry }
                    val expression = selected["expr"] as List<Any?>
                    val io = if (input["ioMain"] == true) CoreRepresentations.ioUnitMainResult(selected, bindings) else null
                    val shutdownResult = shutdown?.let { id ->
                        val definitions = program.signatureBindings(id)
                        CoreRepresentations.ioUnitMainResult(definitions.single { it["id"] == id }, definitions)
                    }
                    if (io == null) CoreRepresentations.knownFunctionSignature(expression, bindings)?.let { (inputs, result) ->
                        inputs.forEach { CoreRepresentations.requireScalar(it, "host argument") }
                        CoreRepresentations.requireScalar(result, "host result")
                    }
                    val registrations = program.registerStartup()
                    val value = EntryValue(program, entry, (selected["arity"] as Number).toInt(), null,
                        io, this@Language, shutdown, shutdownResult,
                        async && program.contains(thc.runtime.CoreSignalForeign.dispatcher))
                    owner.coreUnitPrograms += program
                    owner.foreignRoots.retain(program, registrations)
                    return value
                } catch (failure: Throwable) { program.close(); throw failure }
            }
            override fun getName() = "THC load $entry from unit directory"
        }.callTarget
    }
}

@ExportLibrary(InteropLibrary::class)
internal class EntryValue(private val program: ExecutableProgram, private val entry: String, private val argumentCount: Int,
                 private val hostResultFault: String? = null, ioResult: CoreRepresentation? = null,
                 language: Language? = null, shutdownEntry: String? = null,
                 shutdownResult: CoreRepresentation? = null, private val processSignals: Boolean = false) : TruffleObject {
    private val guestTarget = program.hostEntryTarget(argumentCount)
    private val guestEntry = program.entryValue(entry)
    private val ioTarget = ioResult?.let { IoMainRoot(language ?: error("Missing IO language"), it).callTarget }
    private val shutdownValue = shutdownEntry?.let(program::entryValue)
    private val shutdownTarget = shutdownResult?.let { IoMainRoot(language ?: error("Missing IO language"), it).callTarget }
    private val lifecycleStarted = if (shutdownTarget == null) null else java.util.concurrent.atomic.AtomicBoolean()
    @Volatile private var installedCompilation: Pair<RootCallTarget, List<CallTarget>>? = null
    init { require((shutdownValue == null) == (shutdownTarget == null)) }
    @ExportMessage fun isExecutable() = ioTarget == null
    @ExportMessage fun execute(arguments: Array<Any?>,
                               @Cached(value = "create()", uncached = "create()", neverDefault = true) dispatch: HostDispatch): Any? {
        if (ioTarget != null) throw thc.runtime.RuntimeFault("IO main must be invoked through runIO")
        if (hostResultFault != null) throw thc.runtime.RuntimeFault("Diagnostic unsupported path reached: $hostResultFault")
        if (arguments.size != argumentCount) {
            CompilerDirectives.transferToInterpreterAndInvalidate()
            throw IllegalArgumentException("Host kernel $entry expects $argumentCount arguments")
        }
        val closure = guestEntry as? thc.runtime.Closure
        val signature = closure?.target?.rootNode as? thc.runtime.GuestRoot
        val normalized = Array<Any?>(arguments.size) { index ->
            val value = when (val value = arguments[index]) {
                is Long -> value
                is Int -> value.toLong()
                is Short -> value.toLong()
                is Byte -> value.toLong()
                else -> {
                    CompilerDirectives.transferToInterpreterAndInvalidate()
                    error("The prototype host ABI accepts signed 64-bit integer arguments only")
                }
            }
            val narrow = signature?.inputLayout?.proof(index + (closure?.suppliedCount ?: 0))?.narrowInteger
            if (narrow == null) value else narrow.fromHost(value)
        }
        val threads = Language.currentState(dispatch).threads
        threads.enterCurrent(externalAsync = program.asynchronousExceptions)
        var outcome = thc.runtime.GuestThreadStatus.FINISHED
        try {
            try {
                val result = thc.runtime.AsyncContinuations.publicResult(
                    dispatch.executePublic(guestTarget, arrayOf(guestEntry, normalized)), dispatch)
                val narrow = signature?.scalarResultProof?.narrowInteger
                return if (narrow == null) result else narrow.widen(result as? Int
                    ?: throw thc.runtime.RuntimeFault("Expected narrow integer result at public boundary"))
            } catch (suspended: thc.runtime.ThunkSuspended) {
                thc.runtime.AsyncContinuations.publicSuspension(suspended, dispatch)
            } catch (suspended: thc.runtime.CallSegmentSuspended) {
                thc.runtime.AsyncContinuations.publicSuspension(suspended, dispatch)
            } catch (delivered: thc.runtime.AsyncDelivery) {
                thc.runtime.AsyncContinuations.uncaught(delivered.request, dispatch)
            }
        } catch (failure: Throwable) {
            outcome = thc.runtime.GuestThreadStatus.uncaught(failure)
            if (failure is thc.runtime.GuestException) dispatch.escaping(failure)
            throw failure
        } finally { threads.leaveCurrent(outcome) }
    }
    @ExportMessage fun hasMembers() = true
    @ExportMessage fun getMembers(includeInternal: Boolean): Any = MemberNames(
        if (ioTarget != null) {
            if (program.hasBytecode) arrayOf("diagnostics", "runIO", "bytecode") else arrayOf("diagnostics", "runIO")
        } else if (program.hasBytecode) arrayOf("diagnostics", "compile", "bytecode") else arrayOf("diagnostics", "compile"))
    @ExportMessage fun isMemberReadable(member: String) = member == "diagnostics" || (member == "bytecode" && program.hasBytecode)
    @ExportMessage @CompilerDirectives.TruffleBoundary
    fun readMember(member: String): Any {
        return when {
            member == "diagnostics" -> {
                val installed = installedCompilation
                Json.stringify(if (installed == null) program.diagnostics() else
                    program.diagnostics() + ("explicitCompilation" to compilationObservation(installed)))
            }
            member == "bytecode" && program.hasBytecode -> program.bytecodeDump()
            else -> throw UnknownIdentifierException.create(member)
        }
    }
    private fun compilationTargets(original: RootCallTarget): List<CallTarget> =
        (NodeUtil.findAllNodeInstances(guestTarget.rootNode, DirectCallNode::class.java)
            .filter { it.callTarget === original }.map { it.currentCallTarget }.distinct()
            .ifEmpty { listOf(original) } + guestTarget).distinct()

    /** Observe the installation without executing, compiling or repairing any target. */
    private fun compilationObservation(installed: Pair<RootCallTarget, List<CallTarget>>): Map<String, Any> {
        val current = compilationTargets(installed.first)
        val targets = installed.second
        val validity = Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget").getMethod("isValidLastTier")
        return mapOf("targetCount" to targets.size,
            "sameTargets" to (current.size == targets.size && targets.all { saved -> current.any { it === saved } }),
            "validLastTier" to targets.all { validity.invoke(it) == true })
    }
    @ExportMessage fun isMemberInvocable(member: String) = if (ioTarget != null) member == "runIO" else member == "compile"
    @ExportMessage @CompilerDirectives.TruffleBoundary
    fun invokeMember(member: String, arguments: Array<Any?>,
                     @Cached(value = "create()", uncached = "create()", neverDefault = true) dispatch: HostDispatch): Any {
        if (member == "runIO" && ioTarget != null) {
            require(arguments.isEmpty()) { "runIO takes no arguments" }
            if (lifecycleStarted != null && !lifecycleStarted.compareAndSet(false, true))
                throw thc.runtime.RuntimeFault("Executable IO lifecycle already started")
            val owner = Language.currentState(dispatch)
            val threads = owner.threads
            threads.enterCurrent(externalAsync = program.asynchronousExceptions)
            var outcome = thc.runtime.GuestThreadStatus.FINISHED
            try {
                if (processSignals) owner.signals.bind(program)
                try {
                    dispatch.execute(ioTarget, arrayOf(guestEntry))
                    // Run the original Handle action over this program's CAFs.
                    // TopHandler itself flushes on its exceptional path.
                    if (shutdownTarget != null) dispatch.execute(shutdownTarget, arrayOf(shutdownValue))
                }
                catch (suspended: thc.runtime.ThunkSuspended) {
                    thc.runtime.AsyncContinuations.publicSuspension(suspended, dispatch)
                } catch (suspended: thc.runtime.CallSegmentSuspended) {
                    thc.runtime.AsyncContinuations.publicSuspension(suspended, dispatch)
                } catch (delivered: thc.runtime.AsyncDelivery) {
                    thc.runtime.AsyncContinuations.uncaught(delivered.request, dispatch)
                }
            } catch (failure: Throwable) {
                outcome = thc.runtime.GuestThreadStatus.uncaught(failure)
                if (failure is thc.runtime.GuestException) dispatch.escaping(failure)
                throw failure
            } finally {
                try { if (processSignals) owner.signals.close() }
                finally { threads.leaveCurrent(outcome) }
            }
            return true
        }
        if (member != "compile") throw UnknownIdentifierException.create(member)
        if (ioTarget != null) throw UnknownIdentifierException.create(member)
        require(arguments.isEmpty()) { "compile takes no arguments" }
        installedCompilation = null
        val original = program.entryTarget(entry)
        // The host root is not cloned, but its guest direct call may be split.
        // Compile the targets this stable dispatch tree actually invokes, not
        // only the original target retained by the Haskell closure identity.
        val targets = compilationTargets(original)
        val cls = Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget")
        // The executable value enters through this stable bridge. Install it
        // as well as its active guest callees so an explicit host compilation
        // request covers the actual public call path.
        for (target in targets) {
            require(cls.isInstance(target)) { "Graal optimizing Truffle runtime required" }
            cls.getMethod("compile", Boolean::class.javaPrimitiveType).invoke(target, true)
            check(cls.getMethod("isValidLastTier").invoke(target) == true) { "Guest code was not installed" }
        }
        // HotSpot can retire the shared call-boundary stub while these guest
        // targets remain valid. The pinned runtime hook restores that entry
        // prerequisite without executing guest code or settling a public call.
        val runtime = Truffle.getRuntime()
        runtime.javaClass.getMethod("bypassedInstalledCode", cls).invoke(runtime, guestTarget)
        installedCompilation = original to targets
        return true
    }
}

/** Direct IO dispatch and a bounded polyglot-to-guest entry boundary. */
class HostDispatch : Node() {
    @Child private var foreignExceptions = thc.runtime.ForeignExceptionAccess()
    internal fun escaping(failure: thc.runtime.GuestException): Nothing = foreignExceptions.escaping(failure)
    @Child private var calls = TargetCache(Metrics(false))
    // The polyglot Value.execute root is shared across unrelated guest entries.
    // Keep its compiled graph bounded while leaving direct guest-to-guest calls
    // and explicit compilation of the stable guest entry target unchanged.
    @Child private var publicCall = IndirectCallNode.create()
    fun execute(target: RootCallTarget, arguments: Array<Any?>): Any? = calls.call(target, arguments)
    fun executePublic(target: RootCallTarget, arguments: Array<Any?>): Any? = Calls.indirect(publicCall, target, arguments)
    companion object { @JvmStatic fun create() = HostDispatch() }
}

@ExportLibrary(InteropLibrary::class)
class MemberNames(private val names: Array<String>) : TruffleObject {
    @ExportMessage fun hasArrayElements() = true
    @ExportMessage fun getArraySize(): Long = names.size.toLong()
    @ExportMessage fun isArrayElementReadable(index: Long): Boolean = index >= 0 && index < names.size
    @ExportMessage fun readArrayElement(index: Long): Any {
        if (!isArrayElementReadable(index)) throw InvalidArrayIndexException.create(index)
        return names[index.toInt()]
    }
}
