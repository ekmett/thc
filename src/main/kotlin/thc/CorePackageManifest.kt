// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc

import java.io.ByteArrayInputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import thc.runtime.TargetLayout

/** Exact post-Tidy Core artifacts from separate, Cabal-planned GHC units. */
object CorePackageManifest {
    private const val boundary = "optimized-Core-after-Tidy-before-CorePrep"
    private val sha256 = Regex("[0-9a-f]{64}")
    private data class BundleContents(val entries: Map<String, ByteArray>, val targetLayout: TargetLayout?)
    private data class VerifiedBundle(val bytes: ByteArray, val centralNames: List<String>)
    private data class OrderedVisit(val targetLayout: TargetLayout?)

    private fun moduleIndex(id: String, module: Map<*, *>): Map<*, *>? {
        if (!module.containsKey("index")) return null
        val index = module["index"] as? Map<*, *> ?: error("Invalid JSON index in GHC unit $id")
        require(index.keys == setOf("path", "sha256") && index["path"] is String &&
            (index["sha256"] as? String)?.matches(sha256) == true) { "Invalid JSON index reference in $id" }
        return index
    }

    private fun inventory(id: String, modules: List<*>): List<String> = modules.flatMap { item ->
        val module = item as? Map<*, *> ?: error("Invalid module record in GHC unit $id")
        val name = module["path"] as? String ?: error("Missing module path in $id")
        val names = listOfNotNull(name, moduleIndex(id, module)?.get("path") as String?)
        require(names.all { safeRelative(it) && it !in setOf("manifest.json", "inplace-manifest.json") }) {
            "Invalid module/index path in $id: $names"
        }
        names
    }

    private fun bundleLayout(id: String, modules: List<*>, centralNames: List<String>, entryNames: List<String>,
                             indexBytes: ByteArray, inputBytes: ByteArray?, verifyArtifacts: Boolean): TargetLayout? {
        val index = Json.parse(indexBytes.toString(Charsets.UTF_8)) as? Map<*, *>
            ?: error("Invalid ZIP manifest in $id")
        require(index["format"] == "thc-core-bundle" && index["schema"] == 1L && index["unit"] == id &&
            (index["buildKey"] as? String)?.matches(sha256) == true &&
            (index["exportKey"] as? String)?.matches(sha256) == true && index["modules"] == modules) {
            "ZIP manifest does not match package unit $id"
        }
        val buildInputs = index["buildInputs"]
        var inputRecord: Map<*, *>? = null
        if (index.containsKey("buildInputs")) {
            val reference = buildInputs as? Map<*, *> ?: error("Invalid build inputs in ZIP manifest for $id")
            require(reference.keys == setOf("path", "sha256") && reference["path"] == "inplace-manifest.json" &&
                (reference["sha256"] as? String)?.matches(sha256) == true) {
                "Invalid build inputs reference in ZIP manifest for $id"
            }
            val supplied = inputBytes ?: error("Missing build inputs in ZIP bundle for $id")
            inputRecord = Json.parse(supplied.toString(Charsets.UTF_8)) as? Map<*, *>
            require((!verifyArtifacts || digest(supplied) == reference["sha256"]) && inputRecord?.get("format") == "thc-core-build-inputs" &&
                inputRecord["schema"] == 1L && inputRecord["unit"] == id &&
                inputRecord["buildKey"] == index["buildKey"] && inputRecord["exportKey"] == index["exportKey"]) {
                "Invalid build inputs record in ZIP bundle for $id"
            }
        }
        val paths = inventory(id, modules)
        val expectedEntries = (paths + "manifest.json" +
            (if (buildInputs == null) emptyList() else listOf("inplace-manifest.json"))).toSet()
        require(paths.distinct().size == paths.size && entryNames.toSet() == expectedEntries &&
            entryNames.size == expectedEntries.size) { "ZIP entries differ from declared modules in $id" }
        require(centralNames == entryNames) { "ZIP central directory differs from entries in $id" }
        require(index["targetLayout"] == null || inputRecord != null) {
            "Wired target layout lacks hashed build inputs in $id"
        }
        return inputRecord?.let { TargetLayout.fromReceipts(index, it) }
    }

    private fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }

    private fun safeRelative(path: String): Boolean = path.isNotEmpty() && !path.startsWith('/') &&
        !path.matches(Regex("^[A-Za-z]:.*")) && !path.contains('\\') && path.none { it.code < 32 } &&
        path.split('/').all { it.isNotEmpty() && it != "." && it != ".." }

    private fun artifact(root: Path, relative: String): ByteArray {
        val raw = Path.of(relative)
        require(relative.isNotEmpty() && !raw.isAbsolute && raw.none { it.toString() == ".." }) {
            "Package module path must stay inside manifest root: $relative"
        }
        val file = root.resolve(raw).toRealPath()
        require(file.startsWith(root)) { "Package module path escapes manifest root: $relative" }
        return Files.readAllBytes(file)
    }

    private fun verifiedBundle(id: String, record: Map<String, Any?>, verifyArtifacts: Boolean): VerifiedBundle {
        val location = record["path"] as? String ?: error("Missing ZIP bundle path for $id")
        val expected = record["sha256"] as? String ?: error("Missing ZIP bundle SHA-256 for $id")
        require(record.keys == setOf("path", "sha256") && expected.matches(sha256)) {
            "Invalid ZIP bundle reference for $id"
        }
        val path = Path.of(location)
        require(path.isAbsolute) { "ZIP bundle path must be absolute for $id: $location" }
        val file = path.toRealPath()
        // Pin bytes for this load; explicit verification hashes that same snapshot.
        // Normal loading does not scan it separately to authenticate the archive.
        val bytes = Files.readAllBytes(file)
        if (verifyArtifacts) require(digest(bytes) == expected) { "Core ZIP bundle hash mismatch: $id at $location" }
        val centralNames = try {
            ZipFile(file.toFile()).use { archive -> archive.entries().asSequence().map { it.name }.toList() }
        } catch (failure: IOException) {
            throw IllegalArgumentException("Invalid ZIP bundle for $id at $location", failure)
        }
        return VerifiedBundle(bytes, centralNames)
    }

    private fun bundle(id: String, verified: VerifiedBundle, modules: List<*>, verifyArtifacts: Boolean): BundleContents {
        val entries = linkedMapOf<String, ByteArray>()
        // ZipInputStream validates each member's CRC, but does not inspect the
        // central directory. Check it too so truncated indexes fail closed.
        try {
            ZipInputStream(ByteArrayInputStream(verified.bytes)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    require(!entry.isDirectory && safeRelative(entry.name)) { "Invalid ZIP entry in $id: ${entry.name}" }
                    require(!entries.containsKey(entry.name)) { "Duplicate ZIP entry in $id: ${entry.name}" }
                    entries[entry.name] = zip.readBytes()
                    zip.closeEntry()
                }
            }
        } catch (failure: IOException) {
            throw IllegalArgumentException("Invalid ZIP bundle for $id", failure)
        }
        return BundleContents(entries, bundleLayout(id, modules, verified.centralNames, entries.keys.toList(),
            entries.getValue("manifest.json"), entries["inplace-manifest.json"], verifyArtifacts))
    }

    private fun orderedBundle(id: String, verified: VerifiedBundle, modules: List<*>, verifyArtifacts: Boolean,
                              accept: (Any?, ByteArray, ByteArray?) -> Unit): OrderedVisit? {
        val paths = inventory(id, modules)
        val withoutInputs = listOf("manifest.json") + paths
        val withInputs = listOf("manifest.json", "inplace-manifest.json") + paths
        val hasInputs = when (verified.centralNames) {
            withoutInputs -> false
            withInputs -> true
            else -> return null // Valid reordered archives keep the original materialized path.
        }
        return try {
            ZipInputStream(ByteArrayInputStream(verified.bytes)).use { zip ->
                fun member(expected: String): ByteArray {
                    val entry = zip.nextEntry ?: error("Missing ZIP entry in $id: $expected")
                    require(!entry.isDirectory && entry.name == expected && safeRelative(entry.name)) {
                        "ZIP central directory differs from entries in $id"
                    }
                    val bytes = zip.readBytes() // EOF and closeEntry validate the member CRC.
                    zip.closeEntry()
                    return bytes
                }
                val index = member("manifest.json")
                val inputs = if (hasInputs) member("inplace-manifest.json") else null
                val layout = bundleLayout(id, modules, verified.centralNames, verified.centralNames, index, inputs, verifyArtifacts)
                modules.forEach { item ->
                    val module = item as Map<*, *>
                    val bytes = member(module["path"] as String)
                    val sidecar = moduleIndex(id, module)?.let { member(it["path"] as String) }
                    accept(item, bytes, sidecar)
                }
                require(zip.nextEntry == null) { "ZIP entries differ from declared modules in $id" }
                OrderedVisit(layout)
            }
        } catch (failure: IOException) {
            throw IllegalArgumentException("Invalid ZIP bundle for $id", failure)
        }
    }

    internal data class VisitResult(val targetLayout: TargetLayout?, val manifestSha256: String,
                                    val manifestPath: String, val foreignExceptionBridgeUnit: String?)

    /** Read only the package directory when indexed/compact storage is declared, or when a
     * mixed indexed-consumer request requires a descriptor. Artifact hashes and
     * source/index agreement are checked at replay only with explicit verification.
     */
    internal fun indexedRequestIdentity(manifestPath: String, forceDescriptor: Boolean = false,
                                       verifyArtifacts: Boolean = false): VisitResult? {
        val path = Path.of(manifestPath).toRealPath()
        val bytes = Files.readAllBytes(path)
        val document = Json.parse(bytes.toString(Charsets.UTF_8)) as? Map<*, *>
            ?: error("Invalid Core package manifest: $path")
        require(document["format"] == "thc-core-packages" && document["schema"] == 1L &&
            document["ghc"] == "9.14.1") { "Core package manifest requires schema 1 / GHC 9.14.1: $path" }
        val units = document["units"] as? List<*> ?: error("Missing package units: $path")
        val indexed = units.any { unit -> (unit as? Map<*, *>)?.let { record ->
            record.containsKey("json") || record.containsKey("symbols") ||
                (record["modules"] as? List<*>)?.any {
                    it is Map<*, *> && (it.containsKey("index") || it.containsKey("compact"))
                } == true } == true }
        if (!indexed && !forceDescriptor) return null
        val bridge = document["foreignExceptionBridgeUnit"]
        require(bridge == null || bridge is String && bridge.isNotBlank()) { "Invalid foreign exception bridge unit" }
        return VisitResult(null, if (verifyArtifacts) digest(bytes) else "", path.toString(), bridge as String?)
    }

    internal fun visitModules(manifestPath: String, expectedSha256: String? = null, verifyArtifacts: Boolean = true,
                              accept: (Map<String, Any?>, String) -> Unit): VisitResult =
        visitModules(manifestPath, expectedSha256, true, true, verifyArtifacts, { _, _ -> }, accept)

    internal fun visitRuntimeModules(manifestPath: String, expectedSha256: String,
        sourceNotesEnabled: Boolean, verifyArtifacts: Boolean, indexed: (CoreJsonIndex, CoreJsonBindings) -> Unit,
        accept: (Map<String, Any?>) -> Unit): VisitResult =
        visitModules(manifestPath, expectedSha256, sourceNotesEnabled, false, verifyArtifacts, indexed) { module, _ -> accept(module) }

    @Suppress("UNCHECKED_CAST")
    private fun visitModules(manifestPath: String, expectedSha256: String?, sourceNotesEnabled: Boolean,
                              materializeText: Boolean, verifyArtifacts: Boolean, indexed: (CoreJsonIndex, CoreJsonBindings) -> Unit,
                              accept: (Map<String, Any?>, String) -> Unit): VisitResult {
        val manifest = Path.of(manifestPath).toRealPath()
        val root = manifest.parent
        val manifestBytes = Files.readAllBytes(manifest)
        val manifestSha256 = if (verifyArtifacts) digest(manifestBytes) else ""
        require(!verifyArtifacts || expectedSha256 == null || expectedSha256.matches(sha256) && expectedSha256 == manifestSha256) {
            "Core package manifest changed after request: $manifest"
        }
        val document = Json.parse(manifestBytes.toString(Charsets.UTF_8)) as? Map<String, Any?>
            ?: error("Invalid Core package manifest: $manifest")
        require(document["format"] == "thc-core-packages" &&
            document["schema"] == 1L && document["ghc"] == "9.14.1") {
            "Core package manifest requires schema 1 / GHC 9.14.1: $manifest"
        }
        val bridgeUnit = document["foreignExceptionBridgeUnit"]
        require(bridgeUnit == null || bridgeUnit is String && bridgeUnit.isNotBlank()) { "Invalid foreign exception bridge unit" }
        val units = document["units"] as? List<*> ?: error("Missing package units: $manifest")
        val seenUnits = hashSetOf<String>()
        val seenModules = hashSetOf<Pair<String, String>>()
        var count = 0
        var targetLayout: TargetLayout? = null
        val adapter = CoreJsonBindings(sourceNotesEnabled)
        val opened = ArrayList<CoreJsonIndex>()
        try {
            for (record in units) {
                val unit = record as? Map<String, Any?> ?: error("Invalid package unit: $manifest")
                val id = unit["id"] as? String ?: error("Missing GHC unit ID: $manifest")
                require(id.isNotEmpty() && seenUnits.add(id)) { "Invalid or duplicate GHC unit ID: $id" }
                val depends = unit["depends"] as? List<*> ?: error("Missing dependencies for GHC unit $id")
                require(depends.all { it is String && it.isNotEmpty() } && depends.distinct().size == depends.size) {
                    "Invalid dependencies for GHC unit $id"
                }
                val modules = unit["modules"] as? List<*> ?: error("Missing module list for GHC unit $id")
                // Preserve legacy loose-file path acceptance. Indexed packages and
                // ZIPs additionally require an unambiguous paired inventory.
                val paths = if (unit.containsKey("bundle") || modules.any { (it as? Map<*, *>)?.containsKey("index") == true })
                    inventory(id, modules) else emptyList()
                require(paths.distinct().size == paths.size) { "Duplicate module/index path in $id" }
                fun consume(item: Any?, bytes: ByteArray, indexBytes: ByteArray?, bundled: Boolean) {
                    val module = item as? Map<String, Any?> ?: error("Invalid module record in GHC unit $id")
                    val name = module["name"] as? String ?: error("Missing module name in GHC unit $id")
                    require(name.isNotEmpty() && seenModules.add(id to name)) { "Duplicate GHC module: $id:$name" }
                    require(module["boundary"] == boundary) { "Package module must be post-Tidy: $id:$name" }
                    val relative = module["path"] as? String ?: error("Missing path for $id:$name")
                    if (bundled) require(safeRelative(relative)) { "Invalid ZIP module path: $relative" }
                    val expected = module["sha256"] as? String ?: error("Missing SHA-256 for $id:$name")
                    require(expected.matches(sha256)) { "Invalid SHA-256 for $id:$name" }
                    // Admission owns these bytes. When verification is requested,
                    // hash this snapshot, not a second read of a mutable pathname.
                    if (verifyArtifacts) require(digest(bytes) == expected) { "Core package artifact hash mismatch: $id:$name at $relative" }
                    val reference = moduleIndex(id, module)
                    val sourceIndex = reference?.let {
                        require(indexBytes != null && (!verifyArtifacts || digest(indexBytes) == it["sha256"])) {
                            "Core JSON index hash mismatch: $id:$name at ${it["path"]}"
                        }
                        indexBytes.inputStream().use { stream -> CoreJsonIndex.loadSidecar(bytes, stream, verifyArtifacts) }.also(opened::add)
                    }
                    val text = if (sourceIndex == null || materializeText) bytes.toString(Charsets.UTF_8) else ""
                    val source = if (sourceIndex != null && !materializeText) adapter.module(sourceIndex.root)
                        else Json.parse(text) as? Map<String, Any?> ?: error("Invalid Core package artifact: $relative")
                    CoreForeignArtifacts.validateArchive(source)
                    require(source["ghc"] == "9.14.1" &&
                        source["unit"] == id && source["module"] == name && source["boundary"] == boundary) {
                        "Core package unit/module/boundary mismatch: $id:$name at $relative"
                    }
                    val prefix = "$id:$name."
                    val alias = "main::$name.main"
                    val bindings = source["bindings"] as? List<*> ?: error("Missing bindings in $id:$name")
                    for (binding in bindings) {
                        val key = (binding as? Map<*, *>)?.get("id") as? String
                        require(key != null && (key.startsWith(prefix) || key == alias)) {
                            "Foreign binding owner in $id:$name at $relative: $key"
                        }
                    }
                    count++
                    accept(source, text)
                    sourceIndex?.let { indexed(it, adapter) }
                }
                val unitLayout = if (unit.containsKey("bundle")) {
                    val record = unit["bundle"] as? Map<String, Any?> ?: error("Invalid ZIP bundle for $id")
                    val verified = verifiedBundle(id, record, verifyArtifacts)
                    val ordered = orderedBundle(id, verified, modules, verifyArtifacts) { item, bytes, sidecar -> consume(item, bytes, sidecar, true) }
                    if (ordered != null) ordered.targetLayout else {
                        val fallback = bundle(id, verified, modules, verifyArtifacts)
                        for (item in modules) {
                            val relative = (item as? Map<*, *>)?.get("path") as? String
                                ?: error("Missing module path in $id")
                            val sidecar = moduleIndex(id, item as Map<*, *>)?.let {
                                fallback.entries[it["path"]] ?: error("Missing ZIP JSON index in $id: ${it["path"]}")
                            }
                            consume(item, fallback.entries[relative] ?: error("Missing ZIP module in $id: $relative"), sidecar, true)
                        }
                        fallback.targetLayout
                    }
                } else {
                    for (item in modules) {
                        val relative = (item as? Map<*, *>)?.get("path") as? String
                            ?: error("Missing module path in $id")
                        val sidecar = moduleIndex(id, item as Map<*, *>)?.let { artifact(root, it["path"] as String) }
                        consume(item, artifact(root, relative), sidecar, false)
                    }
                    null
                }
                unitLayout?.let { layout ->
                    require(targetLayout == null || targetLayout == layout) {
                        "Conflicting GHC target layouts across package bundles"
                    }
                    targetLayout = layout
                }
            }
            require(count != 0) { "Core package manifest has no executable modules: $manifest" }
            return VisitResult(targetLayout, manifestSha256, manifest.toString(), bridgeUnit as String?)
        } catch (failure: Throwable) {
            opened.forEach(CoreJsonIndex::close)
            throw failure
        }
    }

    internal fun appendModules(destination: StringBuilder, manifestPath: String): TargetLayout? {
        var count = 0
        val result = visitModules(manifestPath) { _, text ->
            if (count++ != 0) destination.append(',')
            Json.appendObjectDocument(destination, text)
        }
        return result.targetLayout
    }
}
