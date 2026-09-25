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

    private fun bundle(id: String, record: Map<String, Any?>,
                       modules: List<*>): BundleContents {
        val location = record["path"] as? String ?: error("Missing ZIP bundle path for $id")
        val expected = record["sha256"] as? String ?: error("Missing ZIP bundle SHA-256 for $id")
        require(record.keys == setOf("path", "sha256") && expected.matches(sha256)) {
            "Invalid ZIP bundle reference for $id"
        }
        val path = Path.of(location)
        require(path.isAbsolute) { "ZIP bundle path must be absolute for $id: $location" }
        val file = path.toRealPath()
        // Verify and unzip the same bytes. No race with a concurrent cache rewrite
        // can substitute documents after the archive hash has been checked.
        val bytes = Files.readAllBytes(file)
        require(digest(bytes) == expected) { "Core ZIP bundle hash mismatch: $id at $location" }
        val entries = linkedMapOf<String, ByteArray>()
        // ZipInputStream validates each member's CRC, but does not inspect the
        // central directory. Check it too so truncated indexes fail closed.
        val centralNames = try {
            val names = ZipFile(file.toFile()).use { archive ->
                archive.entries().asSequence().map { it.name }.toList()
            }
            ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    require(!entry.isDirectory && safeRelative(entry.name)) { "Invalid ZIP entry in $id: ${entry.name}" }
                    require(!entries.containsKey(entry.name)) { "Duplicate ZIP entry in $id: ${entry.name}" }
                    entries[entry.name] = zip.readBytes()
                    zip.closeEntry()
                }
            }
            names
        } catch (failure: IOException) {
            throw IllegalArgumentException("Invalid ZIP bundle for $id at $location", failure)
        }
        val inventory = modules.map { item ->
            val module = item as? Map<*, *> ?: error("Invalid module record in GHC unit $id")
            val name = module["path"] as? String ?: error("Missing module path in $id")
            require(safeRelative(name) && name != "manifest.json") { "Invalid ZIP module path in $id: $name" }
            name
        }
        val index = Json.parse(entries.getValue("manifest.json").toString(Charsets.UTF_8)) as? Map<*, *>
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
            val inputBytes = entries["inplace-manifest.json"] ?: error("Missing build inputs in ZIP bundle for $id")
            inputRecord = Json.parse(inputBytes.toString(Charsets.UTF_8)) as? Map<*, *>
            require(digest(inputBytes) == reference["sha256"] && inputRecord?.get("format") == "thc-core-build-inputs" &&
                inputRecord["schema"] == 1L && inputRecord["unit"] == id &&
                inputRecord["buildKey"] == index["buildKey"] && inputRecord["exportKey"] == index["exportKey"]) {
                "Invalid build inputs record in ZIP bundle for $id"
            }
        }
        val expectedEntries = (inventory + "manifest.json" +
            (if (buildInputs == null) emptyList() else listOf("inplace-manifest.json"))).toSet()
        require(inventory.distinct().size == inventory.size && entries.keys == expectedEntries) {
            "ZIP entries differ from declared modules in $id"
        }
        require(centralNames == entries.keys.toList()) { "ZIP central directory differs from entries in $id" }
        require(index["targetLayout"] == null || inputRecord != null) {
            "Wired target layout lacks hashed build inputs in $id"
        }
        return BundleContents(entries, inputRecord?.let { TargetLayout.fromReceipts(index, it) })
    }

    @Suppress("UNCHECKED_CAST")
    internal fun appendModules(destination: StringBuilder, manifestPath: String): TargetLayout? {
        val manifest = Path.of(manifestPath).toRealPath()
        val root = manifest.parent
        val document = Json.parse(Files.readString(manifest)) as? Map<String, Any?>
            ?: error("Invalid Core package manifest: $manifest")
        require(document["format"] == "thc-core-packages" &&
            document["schema"] == 1L && document["ghc"] == "9.14.1") {
            "Core package manifest requires schema 1 / GHC 9.14.1: $manifest"
        }
        val units = document["units"] as? List<*> ?: error("Missing package units: $manifest")
        val seenUnits = hashSetOf<String>()
        val seenModules = hashSetOf<Pair<String, String>>()
        var count = 0
        var targetLayout: TargetLayout? = null
        for (record in units) {
            val unit = record as? Map<String, Any?> ?: error("Invalid package unit: $manifest")
            val id = unit["id"] as? String ?: error("Missing GHC unit ID: $manifest")
            require(id.isNotEmpty() && seenUnits.add(id)) { "Invalid or duplicate GHC unit ID: $id" }
            val depends = unit["depends"] as? List<*> ?: error("Missing dependencies for GHC unit $id")
            require(depends.all { it is String && it.isNotEmpty() } && depends.distinct().size == depends.size) {
                "Invalid dependencies for GHC unit $id"
            }
            val modules = unit["modules"] as? List<*> ?: error("Missing module list for GHC unit $id")
            val bundle = if (unit.containsKey("bundle"))
                this.bundle(id, unit["bundle"] as? Map<String, Any?> ?: error("Invalid ZIP bundle for $id"), modules)
                else null
            bundle?.targetLayout?.let { layout ->
                require(targetLayout == null || targetLayout == layout) {
                    "Conflicting GHC target layouts across package bundles"
                }
                targetLayout = layout
            }
            for (item in modules) {
                val module = item as? Map<String, Any?> ?: error("Invalid module record in GHC unit $id")
                val name = module["name"] as? String ?: error("Missing module name in GHC unit $id")
                require(name.isNotEmpty() && seenModules.add(id to name)) { "Duplicate GHC module: $id:$name" }
                require(module["boundary"] == boundary) { "Package module must be post-Tidy: $id:$name" }
                val relative = module["path"] as? String ?: error("Missing path for $id:$name")
                if (bundle != null) require(safeRelative(relative)) { "Invalid ZIP module path: $relative" }
                val expected = module["sha256"] as? String ?: error("Missing SHA-256 for $id:$name")
                require(expected.matches(sha256)) { "Invalid SHA-256 for $id:$name" }
                // Hash and embed the same bytes: a concurrent rewrite cannot
                // substitute an unchecked module between validation and load.
                val bytes = if (bundle == null) artifact(root, relative)
                    else bundle.entries[relative] ?: error("Missing ZIP module in $id: $relative")
                val actual = digest(bytes)
                require(actual == expected) { "Core package artifact hash mismatch: $id:$name at $relative" }
                val text = bytes.toString(Charsets.UTF_8)
                val source = Json.parse(text) as? Map<String, Any?>
                    ?: error("Invalid Core package artifact: $relative")
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
                if (count++ != 0) destination.append(',')
                Json.appendObjectDocument(destination, text)
            }
        }
        require(count != 0) { "Core package manifest has no executable modules: $manifest" }
        return targetLayout
    }
}
