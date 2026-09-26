// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
@file:Suppress("UNCHECKED_CAST")
package thc.runtime

import com.oracle.truffle.api.TruffleLanguage
import com.oracle.truffle.api.dsl.UnsupportedSpecializationException
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
            "test/fixtures/run-native-archive/mixed/src/Narrow.hs", "test/fixtures/run-native-archive/mixed/src/Wide.hs",
            "test/fixtures/run-native-archive/mixed/src/CapiMix.hs", "test/fixtures/run-native-archive/mixed/include/mixed-header.h",
            "test/fixtures/run-native-archive/unresolved/native.c", "test/haskell-fixtures/PackageNativeArchiveFixtures.hs",
            "src/THC/Driver/PackageNative.hs", "scripts/core_package_manifest.py", "scripts/audit-core.py"))
        val paths = manifest["modules"] as List<String>
        OriginalStdioChecks.hashes(root, manifest["artifactHashes"], (paths + listOf(
            "build/native-archive/supported-audit.json", "build/native-archive/interruptible.json",
            "build/native-archive/mixed-width-audit.json", "build/native-archive/narrow-conflict.json", "build/native-archive/wide-conflict.json",
            "build/native-archive/mixed-header-audit.json",
            "build/native-archive/non-static.json", "build/native-archive/unresolved.json")).toSet(), "build/native-archive/")
        val modules = paths.map { Json.parse(File(root, it).readText()) as Map<String, Any?> }
        val mixed = "native-archive-mixed-0.1.0.0-inplace:Mixed."
        val narrow = "native-archive-mixed-0.1.0.0-inplace:Narrow."
        val mixedHeader = "native-archive-mixed-0.1.0.0-inplace:CapiMix.mixedProbe#"
        val staticPointer = "native-archive-mixed-0.1.0.0-inplace:CapiMix.staticPointerProbe#"
        val wideHeader = "native-archive-mixed-0.1.0.0-inplace:CapiMix.wideProbe#"
        val observations = manifest["mixedHeaderObservations"] as List<List<Number>>
        assertEquals(20, observations.size)
        val merged = CoreModules.merge(modules)
        val links = merged["packageScalarLinks"] as List<PackageScalarLink>
        assertEquals(1, links.size)
        assertEquals(setOf("archive_allowed", "archive_count", "archive_header_mix", "archive_header_mix_wide"),
            links.single().abi.filter { it.convention == "ccall" }.map { it.symbol }.toSet())
        assertEquals(2, links.single().abi.count { it.convention == "capi" })
        assertEquals(listOf("AddrRep", "Word8Rep", "Word32Rep"), links.single().abi.single { it.symbol == "archive_header_mix" }.arguments)
        assertEquals(listOf("AddrRep", "IntRep", "IntRep"), links.single().abi.single { it.symbol == "archive_header_mix_wide" }.arguments)
        val failures = listOf(mixed + "blocked", "native-archive-mixed-0.1.0.0-inplace:Unknown.other",
            narrow + "narrow", "native-archive-mixed-0.1.0.0-inplace:Wide.wide",
            "native-archive-unresolved-0.1.0.0-inplace:Unresolved.process")
        for (backend in listOf("ast", "bytecode")) Context.newBuilder("thc").allowNativeAccess(true)
            .withContextProfile(ContextProfile.SYNCHRONOUS_TEST).build().use { context ->
                context.initialize("thc"); context.enter()
                try {
                    val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                    val state = Language.currentState()
                    links.forEach(state.packageCbits::link)
                    val source = CoreModules.reachable(merged,
                        listOf(mixed + "allowed", mixed + "count", narrow + "allowed", mixedHeader, staticPointer, wideHeader), true) + ("instrument" to true)
                    val program: ExecutableProgram = if (backend == "ast") Program(language, source, enableAsync = true)
                        else BytecodeProgram(language, source, enableAsync = true)
                    state.threads.enterCurrent()
                    try {
                        val allowed = program.entryTarget(mixed + "allowed")
                        val count = program.entryTarget(mixed + "count")
                        fun effectCount() = Calls.target(count, arrayOf(0L, 0L))
                        assertEquals(40L, Calls.target(allowed, arrayOf(0L, 3L)))
                        assertEquals(42L, Calls.target(program.entryTarget(narrow + "allowed"), arrayOf(0L, 5L)))
                        assertEquals(0L, effectCount())
                        for (entry in failures) {
                            val rejected = assertThrows(IllegalArgumentException::class.java) {
                                CoreModules.reachable(merged, entry, true)
                            }
                            assertTrue(rejected.message!!.contains("archive-only"), rejected.message)
                            assertEquals(0L, effectCount(), "$backend/$entry must not enter native code")
                        }
                        assertEquals(46L, Calls.target(allowed, arrayOf(0L, 9L)))
                        val staticFailure = assertThrows(RuntimeFault::class.java) {
                            Calls.target(program.entryTarget(staticPointer), arrayOf(0L, 1L, 2L))
                        }
                        assertTrue(staticFailure.message!!.contains("Unowned numeric Addr# is not byte-addressable"))
                        val headerEntry = program.entryTarget(mixedHeader)
                        val stateBytes = ByteArray(8).also { it[0] = 7 }
                        val stateAddress = ManagedAddress.fromByteArray(stateBytes)
                        val widthFailure = assertThrows(UnsupportedSpecializationException::class.java) {
                            Calls.target(program.entryTarget(wideHeader), arrayOf(0L, stateAddress, -1L, -1L))
                        }
                        assertTrue(widthFailure.message!!.contains("LLVMWriteI8Node"))
                        fun checkHeader(row: List<Number>) {
                            assertEquals(row[2].toLong(), row[3].toLong(), "native GHC typed/wide caller agreement")
                            assertEquals(row[2].toLong(), Calls.target(headerEntry, arrayOf(0L, stateAddress, row[0].toLong(), row[1].toLong())),
                                "$backend original native CAPI/ccall declaration boundary $row")
                        }
                        observations.forEach(::checkHeader)
                        headerEntry.javaClass.getMethod("compile", Boolean::class.javaPrimitiveType).invoke(headerEntry, true)
                        assertEquals(true, headerEntry.javaClass.getMethod("isValidLastTier").invoke(headerEntry))
                        observations.asReversed().forEach { row ->
                            val before = (program.diagnostics().getValue("compiledEntries") as Number).toLong()
                            checkHeader(row)
                            assertTrue((program.diagnostics().getValue("compiledEntries") as Number).toLong() > before)
                            assertEquals(true, headerEntry.javaClass.getMethod("isValidLastTier").invoke(headerEntry),
                                "$backend first-installed mixed-header entry retains code")
                        }
                        val handoff = language.handoffState.get()
                        assertEquals(0, handoff.arguments.depth); assertEquals(0, handoff.results.depth)
                        assertEquals(0, handoff.arguments.retainedReferences()); assertEquals(0, handoff.results.retainedReferences())
                    } finally { state.threads.leaveCurrent() }
                } finally { context.leave() }
            }
    }
}
