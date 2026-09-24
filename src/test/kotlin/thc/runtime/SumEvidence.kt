// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

@file:Suppress("UNCHECKED_CAST")
package thc.runtime

import org.junit.jupiter.api.Assertions.*
import thc.Json
import java.io.File
import java.security.MessageDigest

/** Both suites reject stale source, exporter, auditor, native and Core evidence. */
internal fun verifySumEvidence(root: File) {
    for (directory in listOf("sum-layout", "sum-result")) {
        val manifest = Json.parse(File(root, "build/$directory/provenance.json").readText()) as Map<String, Any?>
        val sources = manifest["sources"] as List<Map<String, String>>
        val artifacts = manifest["artifacts"] as List<Map<String, String>>
        val toolchain = manifest["toolchain"] as Map<String, Any?>
        for (item in sources + artifacts + listOf(toolchain["ghc"], toolchain["ghcPkg"]).map { it as Map<String, String> }) {
            val relative = File(item.getValue("path"))
            val path = if (relative.isAbsolute) relative else File(root, relative.path)
            val hash = MessageDigest.getInstance("SHA-256").digest(path.readBytes()).joinToString("") { "%02x".format(it) }
            assertEquals(item["sha256"], hash, "Stale sum evidence: $path")
        }
        assertTrue(sources.map { it["path"] }.containsAll(listOf("scripts/audit-core.py", "scripts/core_sums.py",
            "scripts/core-capabilities.json", "src/main/resources/thc/scalar-primop-signatures.json")))
    }
    for (stage in listOf("pre", "post")) {
        val audit = Json.parse(File(root, "build/sum-result/$stage-audit.json").readText()) as Map<String, Any?>
        assertEquals(true, audit["accepted"], "Strict sum result audit: $stage")
    }
}
