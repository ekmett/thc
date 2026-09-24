// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc

import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/** Exact post-Tidy Core artifacts from separate, Cabal-planned GHC units. */
object CorePackageManifest {
    private const val boundary = "optimized-Core-after-Tidy-before-CorePrep"
    private val sha256 = Regex("[0-9a-f]{64}")

    private fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }

    private fun safeRelative(path: String): Boolean = path.isNotEmpty() && !path.startsWith('/') &&
        !path.contains('\\') && path.split('/').all { it.isNotEmpty() && it != "." && it != ".." }

    private fun artifact(root: Path, relative: String): ByteArray {
        val raw = Path.of(relative)
        require(relative.isNotEmpty() && !raw.isAbsolute && raw.none { it.toString() == ".." }) {
            "Package module path must stay inside manifest root: $relative"
        }
        val file = root.resolve(raw).toRealPath()
        require(file.startsWith(root)) { "Package module path escapes manifest root: $relative" }
        return Files.readAllBytes(file)
    }

    private fun bundle(root: Path, id: String, record: Map<String, Any?>,
                       modules: List<*>): Map<String, ByteArray> {
        val location = record["path"] as? String ?: error("Missing ZIP bundle path for $id")
        val expected = record["sha256"] as? String ?: error("Missing ZIP bundle SHA-256 for $id")
        require(expected.matches(sha256)) { "Invalid ZIP bundle SHA-256 for $id" }
        val path = Path.of(location)
        require(path.isAbsolute || safeRelative(location)) { "Invalid ZIP bundle path for $id: $location" }
        val file = (if (path.isAbsolute) path else root.resolve(path)).toRealPath()
        if (!path.isAbsolute) require(file.startsWith(root)) { "ZIP bundle escapes manifest root: $location" }
        // Verify and unzip the same bytes. No race with a concurrent cache rewrite
        // can substitute documents after the archive hash has been checked.
        val bytes = Files.readAllBytes(file)
        require(digest(bytes) == expected) { "Core ZIP bundle hash mismatch: $id at $location" }
        val entries = linkedMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                require(!entry.isDirectory && safeRelative(entry.name)) { "Invalid ZIP entry in $id: ${entry.name}" }
                require(!entries.containsKey(entry.name)) { "Duplicate ZIP entry in $id: ${entry.name}" }
                entries[entry.name] = zip.readBytes()
                zip.closeEntry()
            }
        }
        val inventory = modules.map { item ->
            val module = item as? Map<*, *> ?: error("Invalid module record in GHC unit $id")
            val name = module["path"] as? String ?: error("Missing module path in $id")
            require(safeRelative(name) && name != "manifest.json") { "Invalid ZIP module path in $id: $name" }
            name
        }
        require(inventory.distinct().size == inventory.size && entries.keys == (inventory + "manifest.json").toSet()) {
            "ZIP entries differ from declared modules in $id"
        }
        val index = Json.parse(entries.getValue("manifest.json").toString(Charsets.UTF_8)) as? Map<*, *>
            ?: error("Invalid ZIP manifest in $id")
        require(index["format"] == "thc-core-bundle" && index["schema"] == 1L && index["unit"] == id &&
            (index["buildKey"] as? String)?.matches(sha256) == true &&
            (index["exportKey"] as? String)?.matches(sha256) == true && index["modules"] == modules) {
            "ZIP manifest does not match package unit $id"
        }
        return entries
    }

    @Suppress("UNCHECKED_CAST")
    internal fun appendModules(destination: StringBuilder, manifestPath: String) {
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
        for (record in units) {
            val unit = record as? Map<String, Any?> ?: error("Invalid package unit: $manifest")
            val id = unit["id"] as? String ?: error("Missing GHC unit ID: $manifest")
            require(id.isNotEmpty() && seenUnits.add(id)) { "Invalid or duplicate GHC unit ID: $id" }
            val depends = unit["depends"] as? List<*> ?: error("Missing dependencies for GHC unit $id")
            require(depends.all { it is String && it.isNotEmpty() } && depends.distinct().size == depends.size) {
                "Invalid dependencies for GHC unit $id"
            }
            val modules = unit["modules"] as? List<*> ?: error("Missing module list for GHC unit $id")
            val bundle = unit["bundle"]?.let {
                this.bundle(root, id, it as? Map<String, Any?> ?: error("Invalid ZIP bundle for $id"), modules)
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
                    else bundle[relative] ?: error("Missing ZIP module in $id: $relative")
                val actual = digest(bytes)
                require(actual == expected) { "Core package artifact hash mismatch: $id:$name at $relative" }
                val text = bytes.toString(Charsets.UTF_8)
                val source = Json.parse(text) as? Map<String, Any?>
                    ?: error("Invalid Core package artifact: $relative")
                require(source["schema"] == 1L && source["ghc"] == "9.14.1" &&
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
    }
}
