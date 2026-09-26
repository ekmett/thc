// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
@file:Suppress("UNCHECKED_CAST")
package thc.runtime

import com.oracle.truffle.api.TruffleLanguage
import org.graalvm.polyglot.Context
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import thc.CoreModules
import thc.CorePackageManifest
import thc.ContextProfile
import thc.Json
import thc.Language
import thc.withContextProfile
import java.io.File

/** Genuine Hackage Hashable instances, typed retained CAPI stubs and native GHC results. */
@EnabledOnOs(OS.LINUX)
@EnabledIfSystemProperty(named = "os.arch", matches = "amd64|x86_64")
class HashableFfiFullCoreTest {
    private val root = File(System.getProperty("thc.projectRoot"))
    private val prefix = "build/hashable-ffi"
    private val entries = listOf("strictText", "strictBytes", "shortBytes", "lazyText", "lazyBytes")
    private val salts = listOf(0L, 1L, -1L, Long.MIN_VALUE, Long.MAX_VALUE)
    private fun json(path: String) = Json.parse(File(root, path).readText()) as Map<String, Any?>
    private data class Row(val entry: String, val salt: Long, val choice: Long, val result: Long)
    private data class Fixture(val packages: File, val unit: String, val rows: List<Row>)

    private fun fixture(): Fixture {
        val manifest = json("$prefix/manifest.json")
        assertEquals(1L, manifest["schema"])
        assertEquals(true, manifest["strictAccepted"])
        assertEquals(true, manifest["runtimeVerified"])
        assertEquals("1.5.1.0", manifest["hashableVersion"])
        assertEquals(false, manifest["randomInitialSeed"])
        assertEquals(false, manifest["archNative"])
        assertEquals(300L, manifest["nativeRows"])
        OriginalStdioChecks.hashes(root, manifest["inputHashes"], setOf(
            "test/fixtures/run-hashable-ffi/cabal.project", "test/fixtures/run-hashable-ffi/run-hashable-ffi.cabal",
            "test/fixtures/run-hashable-ffi/src/HashableProbe.hs", "test/fixtures/run-hashable-ffi/app/Main.hs",
            "test/haskell-fixtures/HashableFfiFixtures.hs", "compiler/THC/Plugin.hs"))
        OriginalStdioChecks.hashes(root, manifest["artifactHashes"], setOf(
            "$prefix/packages.json", "$prefix/acquired/packages.json", "$prefix/acquired/audit.json",
            "$prefix/acquired/native/cache/plan.json", "$prefix/logs/native-oracle.stdout"), "$prefix/")
        val observations = manifest["observations"] as List<Map<String, Any?>>
        val rows = observations.map { row ->
            val arguments = row["arguments"] as List<String>
            assertEquals(2, arguments.size)
            Row(row["entry"] as String, arguments[0].toLong(), arguments[1].toLong(), (row["result"] as String).toLong())
        }
        assertEquals(entries.flatMap { name -> salts.flatMap { salt -> (0L..11L).map { Triple(name, salt, it) } } },
            rows.map { Triple(it.entry, it.salt, it.choice) }, "complete native input matrix, not selected passing rows")
        val nativeRows = File(root, "$prefix/logs/native-oracle.stdout").readLines().map { line ->
            val cells = line.split('\t')
            assertEquals(4, cells.size)
            Row(cells[0], cells[1].toLong(), cells[2].toLong(), cells[3].toLong())
        }
        assertEquals(nativeRows, rows, "expected results must be actual matching native executable output")
        val packages = File(root, manifest["packages"] as String)
        val modules = mutableListOf<Map<String, Any?>>()
        CorePackageManifest.visitModules(packages.path) { module, _ -> modules.add(module) }
        val hashable = manifest["hashableUnit"] as String
        val original = modules.filter { it["unit"] == hashable }
        assertTrue(original.isNotEmpty(), "original dependency Core must remain in the package manifest")
        val proofs = original.mapNotNull { it["staticForeignImports"] as? Map<String, Any?> }
        val declarations = proofs.flatMap { proof ->
            assertEquals("verified", proof["status"])
            proof["imports"] as List<Map<String, Any?>>
        }.filter { it["header"] == "HsXXHash.h" }
        val required = setOf("XXH3_64bits_withSeed", "hs_XXH3_64bits_withSeed_offset", "hs_XXH3_sizeof_state_s",
            "XXH3_INITSTATE", "XXH3_64bits_reset_withSeed", "XXH3_64bits_digest", "XXH3_64bits_update",
            "hs_XXH3_64bits_update_offset", "hs_XXH3_64bits_update_u64")
        assertTrue(declarations.map { it["symbol"] }.containsAll(required), "all actual XXH3 stages require retained typed declarations")
        val emitted = declarations.associate { it["symbol"] as String to ((it["emitted"] as Map<*, *>)["symbol"] as String) }
        val links = original.mapNotNull { it["packageNativeLink"] as? Map<String, Any?> }
        assertTrue(links.isNotEmpty(), "Hashable must use the general native C-FFI profile")
        for (link in links) {
            assertEquals("thc-package-c-ffi-v1", link["profile"])
            assertEquals(hashable, link["unit"])
        }
        val abi = links.flatMap { it["abi"] as List<Map<String, Any?>> }
        assertTrue(abi.flatMap { it["arguments"] as List<String> }.containsAll(
            setOf("ByteArray#", "MutableByteArray#", "AddrRep")), "exercise all three real memory argument forms")
        assertTrue(abi.any { it["result"] == "void" }, "state initialization/update must not manufacture scalar results")
        val unit = manifest["probeUnit"] as String
        val merged = CoreModules.merge(modules)
        for (name in entries) {
            val linked = CoreModules.reachable(merged, listOf("$unit:HashableProbe.$name"), true)
            val calls = OriginalStdioChecks.foreignCalls(linked).map { app ->
                val descriptor = (app.last() as Map<*, *>)["foreignCall"] as Map<*, *>
                val target = descriptor["target"] as Map<*, *>
                target["unit"] to target["symbol"]
            }.toSet()
            val expected = when (name) {
                "strictText", "shortBytes" -> setOf("hs_XXH3_64bits_withSeed_offset")
                "strictBytes" -> setOf("XXH3_64bits_withSeed")
                else -> setOf("hs_XXH3_sizeof_state_s", "XXH3_INITSTATE", "XXH3_64bits_reset_withSeed",
                    "XXH3_64bits_digest", "hs_XXH3_64bits_update_u64",
                    if (name == "lazyText") "hs_XXH3_64bits_update_offset" else "XXH3_64bits_update")
            }
            for (symbol in expected) assertTrue((hashable to emitted.getValue(symbol)) in calls,
                "$name must really reach $symbol in unchanged dependency Core, not an integer-only stand-in")
        }
        return Fixture(packages, unit, rows)
    }

    private fun released(context: Context) {
        context.enter()
        try {
            val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
            val state = language.handoffState.get()
            assertEquals(0, state.arguments.depth)
            assertEquals(0, state.results.depth)
            assertEquals(0, state.arguments.retainedReferences())
            assertEquals(0, state.results.retainedReferences())
        } finally { context.leave() }
    }

    @TestFactory fun originalHashableMatchesNativeThroughRealByteBackedFfiAndFirstCompiledEntries(): List<DynamicTest> {
        val fixture = fixture()
        return listOf("ast", "bytecode").flatMap { backend ->
            entries.map { name ->
                DynamicTest.dynamicTest("$backend/$name") {
                    Context.newBuilder("thc").allowNativeAccess(true)
                        .withContextProfile(ContextProfile.SYNCHRONOUS_TEST).build().use { context ->
                            val entry = "${fixture.unit}:HashableProbe.$name"
                            val function = context.eval("thc", CoreModules.request(listOf("@${fixture.packages.path}"), entry, backend = backend))
                            val selected = fixture.rows.filter { it.entry == name }
                            fun diagnostics() = Json.parse(function.getMember("diagnostics").asString()) as Map<String, Any?>
                            fun check(row: Row) {
                                assertEquals(row.result, function.execute(row.salt, row.choice).asLong(), "$backend/$name/${row.salt}/${row.choice}")
                                released(context)
                            }
                            selected.forEach(::check)
                            assertTrue(function.invokeMember("compile").asBoolean(), "$backend/$name installs real guest code")
                            // The very next invocation is checked, with no settling call,
                            // retry, recompilation or discarded first-entry observation.
                            for (row in selected.asReversed()) {
                                val before = diagnostics()["compiledEntries"] as Long
                                check(row)
                                assertTrue((diagnostics()["compiledEntries"] as Long) > before, "$backend/$name entered compiled guest code")
                            }
                            assertEquals(0L, diagnostics()["unsupportedTraps"])
                            println("HashableFfi PASS $backend/$name nativeRows=${selected.size} firstCompiled=true")
                        }
                }
            }
        }
    }
}
