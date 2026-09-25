// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
@file:Suppress("UNCHECKED_CAST")
package thc.runtime

import com.oracle.truffle.api.RootCallTarget
import com.oracle.truffle.api.TruffleLanguage
import org.graalvm.polyglot.Context
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import thc.CoreModules
import thc.CorePackageManifest
import thc.Json
import thc.Language
import java.io.File
import java.security.MessageDigest

/** Full unchanged installed decoder/formatter; native and managed frame counts differ. */
class OriginalStackDecoderTest {
    private val root = File(System.getProperty("thc.projectRoot"))
    private val directory = "build/original-stack-decoder"
    private val entries = listOf("captureNamed", "observeSnapshot")
    private fun json(path: String) = Json.parse(File(root, path).readText()) as Map<String, Any?>
    private fun manifest(): Map<String, Any?> {
        val value = json("$directory/manifest.json")
        assertEquals("thc-original-stack-decoder-fixture", value["format"])
        assertEquals(1L, value["schema"])
        assertEquals("9.14.1", value["ghc"])
        assertEquals(entries, value["entries"])
        val inputs = value["inputHashes"] as Map<String, String>
        assertTrue(inputs.keys.containsAll(listOf("compiler/test-fixtures/OriginalStackDecoder.hs",
            "compiler/test-fixtures/OriginalStackDecoderNative.hs", "test/haskell-fixtures/InstalledCoreFixtures.hs",
            "test/haskell-fixtures/StackDecoderFixtures.hs", "scripts/core-capabilities.json")))
        for (group in listOf("inputHashes", "artifactHashes")) {
            val records = value[group] as Map<String, String>
            assertTrue(records.isNotEmpty())
            for ((path, expected) in records) {
                assertFalse(File(path).isAbsolute)
                assertFalse(path.split('/').any { it in setOf("", ".", "..") })
                val file = File(root, path).canonicalFile
                assertTrue(file.toPath().startsWith(root.canonicalFile.toPath()))
                if (group == "artifactHashes") assertTrue(path.startsWith("$directory/"))
                val digest = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
                    .joinToString("") { "%02x".format(it.toInt() and 255) }
                assertEquals(expected, digest, "Stale original stack fixture: $path")
            }
        }
        val audits = value["audits"] as List<String>
        assertEquals(listOf("pre", "post").flatMap { stage -> entries.map { "$directory/$stage/$it-audit.json" } }, audits)
        for (path in audits) {
            val audit = json(path)
            assertEquals(true, audit["accepted"], path)
            assertEquals(emptyList<Any>(), audit["missingGlobals"], path)
        }
        return value
    }

    private fun context(inlining: Boolean) = Context.newBuilder("thc").allowNativeAccess(true)
        .allowExperimentalOptions(true).option("compiler.Inlining", inlining.toString())
        .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false")
        .option("engine.CompilationFailureAction", "Throw")
        .option("engine.SingleTierCompilationThreshold", "10000000").build()
    private fun valid(target: RootCallTarget) = target.javaClass.getMethod("isValidLastTier").invoke(target) == true

    @Test fun originalDecoderAndFormatterConsumeCapturedFramesBeforeAndAfterCompilation() {
        val manifest = manifest()
        val native = File(root, manifest["nativeOutput"] as String).readText().trim().split(' ').map(String::toLong)
        assertEquals(6, native.size)
        assertTrue(native[0] > 0)
        assertEquals(1L, native[1]); assertEquals(1L, native[5])
        assertTrue(native[2] in 0..native[0]); assertTrue(native[3] in 0..native[0]); assertTrue(native[4] >= 0)
        val originalsText = StringBuilder()
        val targetLayout = CorePackageManifest.appendModules(originalsText,
            File(root, manifest["packageManifest"] as String).path)
        assertNotNull(targetLayout, "Original selected-toolchain target layout required")
        val originals = Json.parse("[$originalsText]") as List<Map<String, Any?>>
        val stages = manifest["stages"] as Map<String, String>
        assertEquals(setOf("pre", "post"), stages.keys)
        for ((stage, consumer) in stages) for (backend in listOf("ast", "bytecode"))
            for (inlining in listOf(false, true)) context(inlining).use { context ->
                val combined = CoreModules.merge(originals + json(consumer)) + ("targetLayout" to targetLayout!!)
                // Link both real fixture roots without allowing unavailable
                // globals, replacing originals or discarding their cold branches.
                val reached = entries.map { CoreModules.reachable(combined, it, true) }
                val bindings = reached.flatMap { it["bindings"] as List<Map<String, Any?>> }.distinctBy { it["id"] }
                val module = combined + mapOf("bindings" to bindings, "instrument" to true)
                assertTrue(bindings.any { (it["id"] as String).startsWith("ghc-internal:GHC.Internal.Stack.Decode.") })
                context.initialize("thc"); context.enter()
                try {
                    val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                    val program: ExecutableProgram = if (backend == "ast") Program(language, module) else BytecodeProgram(language, module)
                    val capture = program.entryTarget("captureNamed")
                    val observe = program.entryTarget("observeSnapshot")
                    fun capture() = Calls.target(capture, arrayOf(0L, 1L))
                    fun observe(snapshot: Any?, probe: Long): Long =
                        Calls.target(observe, arrayOf(0L, snapshot, probe)) as Long
                    val label = "$stage/$backend/inlining=$inlining"
                    fun inspect(snapshot: Any?): List<Long> {
                        val count = observe(snapshot, -1)
                        assertTrue(count > 0, label)
                        assertEquals(native[1], observe(snapshot, -2), "$label repeated decode")
                        assertEquals(count, observe(snapshot, -3), "$label provenance for each authentic guest frame")
                        assertEquals(count, observe(snapshot, -4), "$label original formatter renders each managed IPE")
                        val length = observe(snapshot, -5)
                        assertTrue(length in 1..100000, label)
                        val namedSources = observe(snapshot, -6)
                        assertTrue(namedSources in 1..count, "$label original formatter includes captureLeaf and its source in one frame")
                        return listOf(count, length, namedSources)
                    }
                    val snapshot = capture()
                    val observation = inspect(snapshot)
                    // Warm each entry, then assert its very first installed entry;
                    // no settling loop after installation is allowed.
                    repeat(3) { observe(snapshot, -2); capture() }
                    for (target in listOf(capture, observe)) {
                        target.javaClass.getMethod("compile", Boolean::class.javaPrimitiveType).invoke(target, true)
                        assertTrue(valid(target), "$label installed ${target.rootNode.name}")
                    }
                    val before = (program.diagnostics()["compiledEntries"] as Number).toLong()
                    val fresh = capture()
                    assertTrue(valid(capture), "$label first installed capture")
                    assertEquals(native[1], observe(snapshot, -2), "$label first installed original decode")
                    assertTrue(valid(observe), "$label first installed decode")
                    assertTrue((program.diagnostics()["compiledEntries"] as Number).toLong() >= before + 2, label)
                    assertEquals(observation, inspect(snapshot), "$label retained detached snapshot")
                    inspect(fresh)
                    assertEquals(0L, program.diagnostics()["unsupportedTraps"], label)
                    val loans = language.handoffState.get()
                    assertEquals(0, loans.arguments.depth); assertEquals(0, loans.results.depth)
                    assertEquals(0, loans.arguments.retainedReferences()); assertEquals(0, loans.results.retainedReferences())
                } finally { context.leave() }
            }
    }
}
