// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

@file:Suppress("UNCHECKED_CAST")
package thc.runtime

import com.oracle.truffle.api.RootCallTarget
import com.oracle.truffle.api.TruffleLanguage
import org.graalvm.polyglot.Context
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import thc.*
import java.io.File
import java.security.MessageDigest

class PinnedPointerCellsTest {
    private val root = File(System.getProperty("thc.projectRoot"))
    private fun context() = Context.newBuilder("thc").allowExperimentalOptions(true)
        .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false")
        .option("engine.CompilationFailureAction", "Throw").build()
    private fun valid(target: RootCallTarget) = assertEquals(true,
        target.javaClass.getMethod("isValidLastTier").invoke(target))

    @Test fun originalPinnedFreezeContentsAndKeepAliveMatchNativeInBothBackends() {
        val manifest = Json.parse(File(root, "build/pinned-pointer-cells/manifest.json").readText()) as Map<String, Any?>
        assertEquals(1L, manifest["schema"])
        assertEquals("9.14.1", manifest["ghc"])
        for (kind in listOf("inputHashes", "artifactHashes"))
            for ((path, expected) in manifest[kind] as Map<String, String>) {
                val actual = MessageDigest.getInstance("SHA-256").digest(File(root, path).readBytes())
                    .joinToString("") { "%02x".format(it.toInt() and 255) }
                assertEquals(expected, actual, "Stale pinned pointer-cell $kind: $path")
            }
        val rows = File(root, "build/pinned-pointer-cells/oracle.tsv").readLines().map { line ->
            val fields = line.split('\t')
            assertEquals(2, fields.size)
            fields[0].toLong() to fields[1].toLong()
        }
        assertEquals(listOf(0L, 1L, 17L, 127L, 255L, 256L, -1L), rows.map { it.first })
        for ((input, expected) in rows) assertEquals(1009L + 17L * (input and 255L), expected)
        for (stage in listOf("pre", "post")) {
            val directory = File(root, "build/pinned-pointer-cells/$stage")
            val audit = Json.parse(File(directory, "audit.json").readText()) as Map<String, Any?>
            assertEquals(true, audit["accepted"])
            assertEquals(emptyList<Any>(), audit["missingGlobals"])
            val primitives = (audit["primitives"] as List<Map<String, Any?>>).map { it["name"] }.toSet()
            assertTrue(primitives.containsAll(setOf("newPinnedByteArray#", "unsafeFreezeByteArray#", "byteArrayContents#",
                "keepAlive#", "writeAddrOffAddr#", "readAddrOffAddr#", "readWord8OffAddr#", "indexWord8Array#")))
            val paths = listOf("PinnedPointerCellsAudit.json", "THC.InterfaceClosure.json")
                .map { File(directory, "core/$it") }
            val merged = CoreModules.merge(paths.map { Json.parse(it.readText()) as Map<String, Any?> })
            for (backend in listOf("ast", "bytecode")) context().use { context ->
                context.initialize("thc"); context.enter()
                try {
                    val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                    val source = CoreModules.reachable(merged, "pointerRoundtrip") + ("instrument" to true)
                    val program: ExecutableProgram = if (backend == "ast") Program(language, source)
                        else BytecodeProgram(language, source)
                    val function = context.asValue(EntryValue(program, "pointerRoundtrip", 1))
                    fun check(input: Long, expected: Long) {
                        assertEquals(expected, function.execute(input).asLong(), "$stage/$backend/$input")
                        assertEquals(0L, (program.diagnostics().getValue("unsupportedTraps") as Number).toLong())
                    }
                    rows.forEach { (input, expected) -> check(input, expected) }
                    assertTrue(function.invokeMember("compile").asBoolean(), "$stage/$backend explicit compilation")
                    for ((input, expected) in rows.asReversed()) {
                        val before = (program.diagnostics().getValue("compiledEntries") as Number).toLong()
                        check(input, expected)
                        assertTrue((program.diagnostics().getValue("compiledEntries") as Number).toLong() > before)
                        valid(program.hostEntryTarget(1)); valid(program.entryTarget("pointerRoundtrip"))
                    }
                } finally { context.leave() }
            }
        }
    }
}
