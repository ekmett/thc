// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
@file:Suppress("UNCHECKED_CAST")
package thc.runtime

import com.oracle.truffle.api.TruffleLanguage
import org.graalvm.polyglot.Context
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import thc.*
import java.io.File

/** Actual producer output; unsupported source effects run only in the native oracle. */
class PackageNativeArchiveFullCoreTest {
    @Test fun supportedMixedImportRunsWhileArchivedImportsFailBeforeEffects() {
        val root = File(System.getProperty("thc.projectRoot"))
        val directory = File(root, "build/native-archive")
        val manifest = Json.parse(File(directory, "manifest.json").readText()) as Map<String, Any?>
        OriginalStdioChecks.hashes(root, manifest["inputHashes"], setOf(
            "test/fixtures/run-native-archive/mixed/src/Mixed.hs", "test/fixtures/run-native-archive/mixed/native.c",
            "test/fixtures/run-native-archive/mixed/src/Unknown.hs", "test/fixtures/run-native-archive/unresolved/Unresolved.hs",
            "test/fixtures/run-native-archive/unresolved/native.c", "test/haskell-fixtures/PackageNativeArchiveFixtures.hs",
            "src/THC/Driver/PackageNative.hs", "scripts/core_package_manifest.py", "scripts/audit-core.py"))
        val paths = manifest["modules"] as List<String>
        OriginalStdioChecks.hashes(root, manifest["artifactHashes"], (paths + listOf(
            "build/native-archive/supported-audit.json", "build/native-archive/interruptible.json",
            "build/native-archive/non-static.json", "build/native-archive/unresolved.json")).toSet(), "build/native-archive/")
        val modules = paths.map { Json.parse(File(root, it).readText()) as Map<String, Any?> }
        val mixed = "native-archive-mixed-0.1.0.0-inplace:Mixed."
        val merged = CoreModules.merge(modules)
        val links = merged["packageScalarLinks"] as List<PackageScalarLink>
        assertEquals(1, links.size)
        assertEquals(setOf("archive_allowed", "archive_count"), links.single().abi.map { it.symbol }.toSet())
        val failures = listOf(mixed + "blocked", "native-archive-mixed-0.1.0.0-inplace:Unknown.other",
            "native-archive-unresolved-0.1.0.0-inplace:Unresolved.process")
        for (backend in listOf("ast", "bytecode")) Context.newBuilder("thc").allowNativeAccess(true)
            .withContextProfile(ContextProfile.SYNCHRONOUS_TEST).build().use { context ->
                context.initialize("thc"); context.enter()
                try {
                    val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                    val state = Language.currentState()
                    links.forEach(state.packageCbits::link)
                    val source = CoreModules.reachable(merged, listOf(mixed + "allowed", mixed + "count"), true)
                    val program: ExecutableProgram = if (backend == "ast") Program(language, source, enableAsync = true)
                        else BytecodeProgram(language, source, enableAsync = true)
                    state.threads.enterCurrent()
                    try {
                        val allowed = program.entryTarget(mixed + "allowed")
                        val count = program.entryTarget(mixed + "count")
                        fun effectCount() = Calls.target(count, arrayOf(0L, 0L))
                        assertEquals(40L, Calls.target(allowed, arrayOf(0L, 3L)))
                        assertEquals(0L, effectCount())
                        for (entry in failures) {
                            val rejected = assertThrows(IllegalArgumentException::class.java) {
                                CoreModules.reachable(merged, entry, true)
                            }
                            assertTrue(rejected.message!!.contains("archive-only"), rejected.message)
                            assertEquals(0L, effectCount(), "$backend/$entry must not enter native code")
                        }
                        assertEquals(46L, Calls.target(allowed, arrayOf(0L, 9L)))
                        val handoff = language.handoffState.get()
                        assertEquals(0, handoff.arguments.depth); assertEquals(0, handoff.results.depth)
                        assertEquals(0, handoff.arguments.retainedReferences()); assertEquals(0, handoff.results.retainedReferences())
                    } finally { state.threads.leaveCurrent() }
                } finally { context.leave() }
            }
    }
}
