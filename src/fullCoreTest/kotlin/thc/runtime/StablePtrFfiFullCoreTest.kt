// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
@file:Suppress("UNCHECKED_CAST")
package thc.runtime

import com.oracle.truffle.api.TruffleLanguage
import org.graalvm.polyglot.Context
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import thc.CoreModules
import thc.ContextProfile
import thc.Json
import thc.Language
import thc.withContextProfile
import java.io.File

@EnabledOnOs(OS.LINUX)
@EnabledIfSystemProperty(named = "os.arch", matches = "amd64|x86_64")
class StablePtrFfiFullCoreTest {
    private val root = File(System.getProperty("thc.projectRoot"))
    private val prefix = "build/stableptr-ffi"
    private fun json(path: String) = Json.parse(File(root, path).readText()) as Map<String, Any?>

    @Test fun ordinaryForeignStablePtrMatchesNativeWithCcallAndCapiInBothBackends() {
        val manifest = json("$prefix/manifest.json")
        assertEquals(1L, manifest["schema"])
        assertEquals(true, manifest["strictAccepted"])
        assertEquals(true, manifest["runtimeVerified"])
        assertEquals(12L, manifest["nativeRows"])
        OriginalStdioChecks.hashes(root, manifest["inputHashes"], setOf(
            "test/fixtures/run-stableptr-ffi/src/StableForeign.hs", "test/fixtures/run-stableptr-ffi/cbits/stable.c",
            "test/fixtures/run-stableptr-ffi/cbits/stable.h", "test/haskell-fixtures/StablePtrFFIFixtures.hs"))
        OriginalStdioChecks.hashes(root, manifest["artifactHashes"], setOf(
            "$prefix/packages.json", "$prefix/audit.json", "$prefix/logs/native-run.stdout"), "$prefix/")
        val rows = manifest["observations"] as List<Map<String, String>>
        assertEquals(setOf("stableRoundtrip", "stableLazy"), rows.map { it.getValue("entry") }.toSet())
        assertEquals(File(root, "$prefix/logs/native-run.stdout").readLines(), rows.map {
            "${it.getValue("entry")}\t${it.getValue("argument")}\t${it.getValue("result")}" })
        assertEquals(12, rows.size)
        for (backend in listOf("ast", "bytecode")) for (name in listOf("stableRoundtrip", "stableLazy")) {
            Context.newBuilder("thc").allowNativeAccess(true)
                .withContextProfile(ContextProfile.SYNCHRONOUS_TEST).build().use { context ->
                    val entry = "${manifest["unit"]}:StableForeign.$name"
                    val function = context.eval("thc", CoreModules.request(
                        listOf("@${File(root, "$prefix/packages.json").path}"), entry, backend = backend))
                    val selected = rows.filter { it.getValue("entry") == name }
                    fun diagnostics() = Json.parse(function.getMember("diagnostics").asString()) as Map<String, Any?>
                    fun check(row: Map<String, String>) {
                        val input = row.getValue("argument").toLong()
                        val expected = row.getValue("result").toLong()
                        assertEquals(input + if (name == "stableRoundtrip") 35 else 1, expected)
                        assertEquals(expected, function.execute(input).asLong(), "$backend/$name/$input")
                        context.enter()
                        try {
                            val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                            val state = language.handoffState.get()
                            assertEquals(0, state.arguments.depth); assertEquals(0, state.results.depth)
                            assertEquals(0, state.arguments.retainedReferences()); assertEquals(0, state.results.retainedReferences())
                        } finally { context.leave() }
                    }
                    selected.forEach(::check)
                    assertTrue(function.invokeMember("compile").asBoolean())
                    for (row in selected.asReversed()) {
                        val before = diagnostics()["compiledEntries"] as Long
                        check(row)
                        assertTrue((diagnostics()["compiledEntries"] as Long) > before, "$backend/$name entered compiled guest code")
                    }
                    assertEquals(0L, diagnostics()["unsupportedTraps"])
                    println("StablePtrFFI PASS $backend/$name nativeRows=${selected.size} compiled=true")
                }
        }
    }
}
