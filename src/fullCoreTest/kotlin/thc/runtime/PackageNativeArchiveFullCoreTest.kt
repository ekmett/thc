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
import java.security.MessageDigest
import java.util.HexFormat

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
            "src/THC/Driver/PackageNative.hs", "src/THC/Driver/NativeArgumentBridge.hs",
            "src/THC/Driver/NativeLibrarySources.hs", "test/fixtures/run-native-archive/mixed/lifecycle.cpp",
            "test/fixtures/run-native-archive/mixed/src/Lifecycle.hs",
            "test/fixtures/run-native-archive/poisoned/native.c", "test/fixtures/run-native-archive/poisoned/Poisoned.hs",
            "test/fixtures/run-native-archive/provider/native.c", "test/fixtures/run-native-archive/provider/Provider.hs",
            "scripts/core_package_manifest.py", "scripts/audit-core.py"))
        val paths = manifest["modules"] as List<String>
        OriginalStdioChecks.hashes(root, manifest["artifactHashes"], (paths + listOf(
            "build/native-archive/supported-audit.json", "build/native-archive/interruptible.json",
            "build/native-archive/mixed-width-audit.json", "build/native-archive/narrow-conflict.json", "build/native-archive/wide-conflict.json",
            "build/native-archive/mixed-header-audit.json", "build/native-archive/lifecycle-audit.json",
            "build/native-archive/partial-audit.json", "build/native-archive/indirect-unresolved.json", "build/native-archive/constructor-unresolved.json",
            "build/native-archive/provider-container.json",
            "build/native-archive/non-static.json", "build/native-archive/unresolved.json")).toSet(), "build/native-archive/")
        val modules = paths.map { Json.parse(File(root, it).readText()) as Map<String, Any?> }
        val mixed = "native-archive-mixed-0.1.0.0-inplace:Mixed."
        val narrow = "native-archive-mixed-0.1.0.0-inplace:Narrow."
        val mixedHeader = "native-archive-mixed-0.1.0.0-inplace:CapiMix.mixedProbe#"
        val staticPointer = "native-archive-mixed-0.1.0.0-inplace:CapiMix.staticPointerProbe#"
        val wideHeader = "native-archive-mixed-0.1.0.0-inplace:CapiMix.wideProbe#"
        val word16Header = "native-archive-mixed-0.1.0.0-inplace:CapiMix.word16Probe#"
        val lifecycle = "native-archive-mixed-0.1.0.0-inplace:Lifecycle.lifecycleProbe#"
        val partial = "native-archive-unresolved-0.1.0.0-inplace:Unresolved.partialProbe#"
        val observations = manifest["mixedHeaderObservations"] as List<List<Number>>
        assertEquals(36, observations.size)
        val merged = CoreModules.merge(modules)
        val links = merged["packageScalarLinks"] as List<PackageScalarLink>
        assertEquals(2, links.size)
        val mixedLink = links.single { it.unit == "native-archive-mixed-0.1.0.0-inplace" }
        assertEquals(setOf("archive_allowed", "archive_count", "archive_header_mix", "archive_header_mix_wide", "archive_header_mix16", "archive_lifecycle"),
            mixedLink.abi.filter { it.convention == "ccall" }.map { it.symbol }.toSet())
        assertEquals(2, mixedLink.abi.count { it.convention == "capi" })
        assertEquals(listOf("AddrRep", "Word8Rep", "Word32Rep"), mixedLink.abi.single { it.symbol == "archive_header_mix" }.arguments)
        assertEquals(listOf("AddrRep", "IntRep", "IntRep"), mixedLink.abi.single { it.symbol == "archive_header_mix_wide" }.arguments)
        assertEquals(setOf("archive_partial_add", "archive_partial_read"),
            links.single { it.unit == "native-archive-unresolved-0.1.0.0-inplace" }.abi.map { it.symbol }.toSet())
        val nativeProof = modules.first { it["packageNativeLink"] != null }["packageNativeLink"] as Map<String, Any?>
        assertEquals("llvm-embedded-elf", nativeProof["format"])
        val libraries = (nativeProof["buildInputs"] as Map<String, Any?>)["nativeLibraries"] as List<Map<String, Any?>>
        assertEquals("native-libstdcxx-ios-init-v1", libraries.single()["provider"])
        val bridge = ((nativeProof["buildInputs"] as Map<String, Any?>)["argumentBridges"] as List<Map<String, Any?>>).single()
        assertEquals("x86_64-c-integer-argument-truncation-v1", bridge["profile"])
        val bridgeSource = bridge["source"] as String
        assertEquals(bridge["sourceSha256"], HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bridgeSource.toByteArray())))
        assertEquals(2, (bridge["definitions"] as List<*>).size)
        assertTrue(Regex("[0-9a-f]{64}").matches(bridge["inputBitcodeSha256"] as String))
        for (width in listOf(8,16,32)) assertTrue(bridgeSource.contains(" to i$width"))
        val failures = listOf(mixed + "blocked", "native-archive-mixed-0.1.0.0-inplace:Unknown.other",
            narrow + "narrow", "native-archive-mixed-0.1.0.0-inplace:Wide.wide",
            "native-archive-unresolved-0.1.0.0-inplace:Unresolved.process",
            "native-archive-unresolved-0.1.0.0-inplace:Unresolved.throughGlobal",
            "native-archive-poisoned-0.1.0.0-inplace:Poisoned.poisoned",
            "native-archive-provider-0.1.0.0-inplace:Provider.nativeMath")
        for (backend in listOf("ast", "bytecode")) Context.newBuilder("thc").allowNativeAccess(true)
            .withContextProfile(ContextProfile.SYNCHRONOUS_TEST).build().use { context ->
                context.initialize("thc"); context.enter()
                try {
                    val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                    val state = Language.currentState()
                    links.forEach(state.packageCbits::link)
                    val source = CoreModules.reachable(merged,
                        listOf(mixed + "allowed", mixed + "count", narrow + "allowed", mixedHeader, staticPointer, wideHeader, word16Header, lifecycle, partial), true) + ("instrument" to true)
                    val program: ExecutableProgram = if (backend == "ast") Program(language, source, true)
                        else BytecodeProgram(language, source, enableAsync = true)
                    state.threads.enterCurrent()
                    try {
                        val allowed = program.entryTarget(mixed + "allowed")
                        val count = program.entryTarget(mixed + "count")
                        fun effectCount() = Calls.target(count, arrayOf(0L, 0L))
                        assertEquals(40L, Calls.target(allowed, arrayOf(0L, 3L)))
                        assertEquals(42L, Calls.target(program.entryTarget(narrow + "allowed"), arrayOf(0L, 5L)))
                        assertEquals(0L, effectCount())
                        val shared = program.entryTarget(partial)
                        val partialObservations = manifest["partialObservations"] as List<Number>
                        // Exercise distinct positive/negative updates before installing code.
                        // Each call adds through one adapter and reads through the other.
                        for ((index, input) in listOf(3L, 5L, -2L, 1L).withIndex())
                            assertEquals(partialObservations[index].toLong(), Calls.target(shared, arrayOf(0L, input)))
                        shared.javaClass.getMethod("compile", Boolean::class.javaPrimitiveType).invoke(shared, true)
                        assertEquals(true, shared.javaClass.getMethod("isValidLastTier").invoke(shared))
                        val beforeShared = (program.diagnostics().getValue("compiledEntries") as Number).toLong()
                        assertEquals(partialObservations[4].toLong(), Calls.target(shared, arrayOf(0L, 4L)),
                            "$backend partial adapters share one initialized C global")
                        assertEquals(beforeShared + 1, (program.diagnostics().getValue("compiledEntries") as Number).toLong())
                        assertEquals(true, shared.javaClass.getMethod("isValidLastTier").invoke(shared))
                        val initialized = program.entryTarget(lifecycle)
                        assertEquals(47L, Calls.target(initialized, arrayOf(0L, 5L)))
                        initialized.javaClass.getMethod("compile", Boolean::class.javaPrimitiveType).invoke(initialized, true)
                        assertEquals(true, initialized.javaClass.getMethod("isValidLastTier").invoke(initialized))
                        assertEquals(48L, Calls.target(initialized, arrayOf(0L, 6L)), "$backend initialized native C++ state")
                        assertEquals(true, initialized.javaClass.getMethod("isValidLastTier").invoke(initialized))
                        for (entry in failures) {
                            val rejected = assertThrows(IllegalArgumentException::class.java) {
                                CoreModules.reachable(merged, entry, true)
                            }
                            assertTrue(rejected.message!!.contains("archive-only"), rejected.message)
                            assertEquals(0L, effectCount(), "$backend/$entry must not enter native code")
                        }
                        assertEquals(46L, Calls.target(allowed, arrayOf(0L, 9L)))
                        val stateBytes = ByteArray(8).also { it[0] = 7 }
                        val stateAddress = ManagedAddress.fromByteArray(stateBytes)
                        for ((name,column) in listOf(mixedHeader to 2, wideHeader to 3, word16Header to 4, staticPointer to 5)) {
                            val headerEntry = program.entryTarget(name)
                            fun checkHeader(row: List<Number>) {
                                assertEquals(row[2].toLong(), row[3].toLong(), "native GHC typed/wide caller agreement")
                                val inputs = if (name == staticPointer) arrayOf(0L, row[0].toLong(), row[1].toLong())
                                    else arrayOf(0L, stateAddress, row[0].toLong(), row[1].toLong())
                                assertEquals(row[column].toLong(), Calls.target(headerEntry, inputs),
                                    "$backend/$name original native CAPI/ccall declaration boundary $row")
                            }
                            observations.forEach(::checkHeader)
                            headerEntry.javaClass.getMethod("compile", Boolean::class.javaPrimitiveType).invoke(headerEntry, true)
                            assertEquals(true, headerEntry.javaClass.getMethod("isValidLastTier").invoke(headerEntry))
                            observations.asReversed().forEach { row ->
                                val before = (program.diagnostics().getValue("compiledEntries") as Number).toLong()
                                checkHeader(row)
                                assertTrue((program.diagnostics().getValue("compiledEntries") as Number).toLong() > before)
                                assertEquals(true, headerEntry.javaClass.getMethod("isValidLastTier").invoke(headerEntry),
                                    "$backend/$name first-installed mixed-header entry retains code")
                            }
                        }
                        val handoff = language.handoffState.get()
                        assertEquals(0, handoff.arguments.depth); assertEquals(0, handoff.results.depth)
                        assertEquals(0, handoff.arguments.retainedReferences()); assertEquals(0, handoff.results.retainedReferences())
                    } finally { state.threads.leaveCurrent() }
                } finally { context.leave() }
            }
    }
}
