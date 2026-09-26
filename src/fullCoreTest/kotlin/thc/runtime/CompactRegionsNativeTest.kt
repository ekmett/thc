// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
@file:Suppress("UNCHECKED_CAST")
package thc.runtime

import com.oracle.truffle.api.TruffleLanguage
import org.graalvm.polyglot.Context
import org.graalvm.polyglot.io.IOAccess
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import thc.CoreModules
import thc.CorePackageManifest
import thc.EntryValue
import thc.Json
import thc.Language
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile

private class CompactLibraryFixture(private val directory: String, private val entries: List<String>,
    private val expected: (String, Long) -> Long, private val compiledEntry: String) {
    private val root = File(System.getProperty("thc.projectRoot"))
    private fun read(path: String) = Json.parse(File(root, path).readText()) as Map<String, Any?>
    fun run() {
        val manifest = read("$directory/manifest.json")
        assertEquals(entries, manifest["entries"])
        for (kind in listOf("inputHashes", "artifactHashes")) for ((path, hash) in manifest[kind] as Map<String, String>) {
            val actual = MessageDigest.getInstance("SHA-256").digest(File(root, path).readBytes())
                .joinToString("") { "%02x".format(it.toInt() and 255) }
            assertEquals(hash, actual, "Stale compact fixture: $path")
        }
        val rows = File(root, "$directory/oracle.tsv").readLines().map { it.split('\t') }
        assertEquals(entries.flatMap { name -> listOf(-31L, 0L, 17L, 4097L).map { name to it } },
            rows.map { it[0] to it[1].toLong() })
        for (row in rows) assertEquals(expected(row[0], row[1].toLong()), row[2].toLong())
        val stages = manifest["stages"] as Map<String, List<String>>
        assertEquals(setOf("pre", "post"), stages.keys)
        for (stage in stages.keys) {
            val selection = read("$directory/$stage/original-selection.json") as Map<String, String>
            for ((path, origin) in selection) {
                val (archive, member) = origin.split("!/", limit = 2)
                ZipFile(File(root, archive)).use { zip ->
                    assertArrayEquals(zip.getInputStream(zip.getEntry(member)).use { it.readBytes() },
                        File(root, path).readBytes(), "Original library module changed: $path")
                }
            }
        }
        val targetLayout = checkNotNull(CorePackageManifest.visitModules(File(root, "$directory/installed/packages.json").path)
            { _, _ -> }.targetLayout)
        for ((stage, paths) in stages) for (backend in listOf("ast", "bytecode")) {
            val module = CoreModules.merge(paths.map(::read)) + ("targetLayout" to targetLayout)
            val bindings = module["bindings"] as List<Map<String, Any?>>
            assertTrue(bindings.any { (it["id"] as String).contains(":GHC.Compact.") }, "Original library retained")
            Context.newBuilder("thc", "llvm").allowNativeAccess(true).allowIO(IOAccess.ALL).allowExperimentalOptions(true)
                .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false")
                .option("engine.SingleTierCompilationThreshold", "10000000")
                .option("engine.CompilationFailureAction", "Throw").build().use { context ->
                    context.initialize("thc"); context.enter()
                    try {
                        val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                        for (entry in entries) {
                            val linked = CoreModules.reachable(module, entry, strictLink = true) + ("instrument" to true)
                            val program: ExecutableProgram = if (backend == "ast") Program(language, linked) else BytecodeProgram(language, linked)
                            val function = context.asValue(EntryValue(program, entry, 1))
                            for (input in listOf(-31L, 0L, 17L, 4097L))
                                assertEquals(expected(entry, input), function.execute(input).asLong(), "$stage/$backend/$entry/$input")
                            if (entry == compiledEntry) {
                                assertTrue(function.invokeMember("compile").asBoolean())
                                val before = (program.diagnostics().getValue("compiledEntries") as Number).toLong()
                                assertEquals(expected(entry, 43), function.execute(43L).asLong())
                                assertTrue((program.diagnostics().getValue("compiledEntries") as Number).toLong() > before)
                            }
                            val pools = language.handoffState.get()
                            assertEquals(0, pools.arguments.depth); assertEquals(0, pools.results.depth)
                            assertEquals(0, pools.arguments.retainedReferences()); assertEquals(0, pools.results.retainedReferences())
                            assertEquals(0L, program.diagnostics()["unsupportedTraps"])
                        }
                    } finally { context.leave() }
                }
        }
    }
}

class CompactRegionsNativeTest {
    @Test fun originalCompactLibraryMatchesNativeForGraphsCyclesArraysAndExceptionsOnBothBackends() {
        CompactLibraryFixture("build/compact-regions",
            listOf("ordinary", "sharing", "cycleCase", "rejectedObjects", "frozenArray"), { entry, input ->
                when (entry) {
                    "ordinary", "sharing" -> 4 * input + 106
                    "cycleCase" -> input + 100
                    "rejectedObjects" -> input + 1111
                    "frozenArray" -> 2 * input
                    else -> error(entry)
                }
            }, "ordinary").run()
    }
}

class CompactSerializedNativeTest {
    @Test fun originalSerializedApiRoundTripsCopiedBlocksSharingCyclesAndStaticRoots() {
        CompactLibraryFixture("build/compact-serialization",
            listOf("roundTrip", "cycleRoundTrip", "multipleBlocks", "emptyRoundTrip"), { entry, input ->
                when (entry) {
                    "roundTrip" -> 4 * input + 1006
                    "cycleRoundTrip", "emptyRoundTrip" -> input + 100
                    "multipleBlocks" -> 8192 * input + 33550436
                    else -> error(entry)
                }
            }, "roundTrip").run()
    }
}
