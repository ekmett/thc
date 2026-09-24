package thc

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/** Exact post-Tidy Core artifacts from separate, Cabal-planned GHC units. */
object CorePackageManifest {
    private const val boundary = "optimized-Core-after-Tidy-before-CorePrep"

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
            for (item in modules) {
                val module = item as? Map<String, Any?> ?: error("Invalid module record in GHC unit $id")
                val name = module["name"] as? String ?: error("Missing module name in GHC unit $id")
                require(name.isNotEmpty() && seenModules.add(id to name)) { "Duplicate GHC module: $id:$name" }
                require(module["boundary"] == boundary) { "Package module must be post-Tidy: $id:$name" }
                val relative = module["path"] as? String ?: error("Missing path for $id:$name")
                val raw = Path.of(relative)
                require(relative.isNotEmpty() && !raw.isAbsolute && raw.none { it.toString() == ".." }) {
                    "Package module path must stay inside manifest root: $relative"
                }
                val artifact = root.resolve(raw).toRealPath()
                require(artifact.startsWith(root)) { "Package module path escapes manifest root: $relative" }
                val expected = module["sha256"] as? String ?: error("Missing SHA-256 for $id:$name")
                require(expected.matches(Regex("[0-9a-f]{64}"))) { "Invalid SHA-256 for $id:$name" }
                // Hash and embed the same bytes: a concurrent rewrite cannot
                // substitute an unchecked module between validation and load.
                val bytes = Files.readAllBytes(artifact)
                val actual = MessageDigest.getInstance("SHA-256").digest(bytes)
                    .joinToString("") { "%02x".format(it) }
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
