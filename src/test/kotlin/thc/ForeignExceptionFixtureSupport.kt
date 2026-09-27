// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
@file:Suppress("UNCHECKED_CAST")
package thc

import java.io.File
import java.security.MessageDigest

/** Immutable compiler-produced support for public foreign-program controls.
 * No exception constructors, dictionaries, or proof records are synthesized. */
object ForeignExceptionFixtureSupport {
    private val root = File(System.getProperty("thc.projectRoot"))
    private val manifest by lazy {
        val value = Json.parse(File(root, "build/foreign-exceptions/manifest.json").readText()) as Map<String, Any?>
        for (field in listOf("inputHashes", "artifactHashes"))
            for ((path, expected) in value[field] as Map<String, String>) {
                val actual = MessageDigest.getInstance("SHA-256").digest(File(root, path).readBytes())
                    .joinToString("") { "%02x".format(it.toInt() and 255) }
                check(expected == actual) { "Stale foreign-exception fixture: $path" }
            }
        value
    }
    private val originals by lazy {
        val text = StringBuilder()
        val target = CorePackageManifest.appendModules(text, File(root, manifest["packageManifest"] as String).path)
        (Json.parse("[$text]") as List<Map<String, Any?>>) to checkNotNull(target)
    }
    private fun stageModules(stage: String) =
        ((manifest["stages"] as Map<String, List<String>>).getValue(stage)).map {
            Json.parse(File(root, it).readText()) as Map<String, Any?>
        }
    fun source(stage: String): Map<String, Any?> =
        CoreModules.merge(originals.first + stageModules(stage)) + ("targetLayout" to originals.second)

    /** The supplied small protocol program stays synthetic; all exception support
     * comes unchanged from genuine GHC exports and the validated package receipt. */
    fun link(module: Map<String, Any?>, entry: String): Map<String, Any?> {
        val support = stageModules("post").filter { it["module"] != "ForeignExceptionAudit" }
        val protocol = mapOf("schema" to 1L, "ghc" to "9.14.1", "constructors" to emptyList<Any>()) + module
        val merged = CoreModules.merge(originals.first + support + protocol).toMutableMap()
        merged["targetLayout"] = originals.second
        for (key in listOf("foreignLinks", "packageScalarLinks"))
            if (module[key] != null) merged[key] = (merged[key] as? List<*> ?: emptyList<Any>()) + (module[key] as List<*>)
        if (module.containsKey("instrument")) merged["instrument"] = module["instrument"]
        return CoreModules.reachable(merged, entry, true)
    }
}
