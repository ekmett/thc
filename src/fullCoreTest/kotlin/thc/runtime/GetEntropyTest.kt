// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
@file:Suppress("UNCHECKED_CAST")
package thc.runtime

import thc.Main.withContextProfile

import com.oracle.truffle.api.TruffleLanguage
import com.oracle.truffle.api.frame.VirtualFrame
import com.oracle.truffle.api.nodes.RootNode
import org.graalvm.polyglot.Context
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import thc.*
import java.io.File
import java.security.MessageDigest
import java.util.HexFormat

class GetEntropyTest {
    private val root = File(System.getProperty("thc.projectRoot"))
    private val directory = File(root, "build/getentropy")
    private fun context() = Context.newBuilder("thc").allowNativeAccess(true)
        .let { withContextProfile(it, ContextProfile.SYNCHRONOUS_TEST) }.build()
    private fun verify() {
        val manifest = Json.parse(File(directory, "manifest.json").readText()) as Map<String, Any?>
        assertEquals(9L, manifest["nativeRows"])
        OriginalStdioChecks.hashes(root, manifest["sourceHashes"], setOf(
            "build/getentropy/sources/splitmix-0.1.3.2/splitmix.cabal",
            "build/getentropy/sources/splitmix-0.1.3.2/cbits-unix/init.c",
            "build/getentropy/sources/splitmix-0.1.3.2/src/System/Random/SplitMix/Init.hs"),
            "build/getentropy/sources/")
        OriginalStdioChecks.hashes(root, manifest["inputHashes"], setOf(
            "compiler/test-fixtures/NativeGetEntropy.c", "compiler/test-fixtures/OriginalSplitmixNative.hs",
            "compiler/test-fixtures/OriginalSplitmixEntry.hs",
            "test/haskell-fixtures/GetEntropyFixtures.hs",
            "src/THC/Driver/PackageNative.hs", "src/THC/Driver/NativeLibrarySources.hs"))
        OriginalStdioChecks.hashes(root, manifest["artifactHashes"], setOf(
            "build/getentropy/System.Random.SplitMix.Init.json", "build/getentropy/System.Random.SplitMix.json",
            "build/getentropy/entry/units/u-original-splitmix-entry/OriginalSplitmixEntry.json", "build/getentropy/audit.json",
            "build/getentropy/native.tsv", "build/getentropy/splitmix-native.tsv",
            "build/getentropy/control.so"), "build/getentropy/")
        assertEquals(true, (Json.parse(File(directory, "audit.json").readText()) as Map<*, *>)["accepted"])
    }
    private fun control(): PackageScalarLink {
        val bytes = File(directory, "control.so").readBytes()
        val sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
        val signatures = listOf(
            PackageScalarSignature("entropy_guard", "entropy_guard", listOf("WordRep"), "Int32Rep"),
            PackageScalarSignature("entropy_null", "entropy_null", listOf("WordRep"), "Int32Rep"),
            PackageScalarSignature("entropy_write", "entropy_write", listOf("AddrRep", "WordRep"), "Int32Rep"))
        return PackageScalarLink("getentropy-controls", "x86_64-unknown-linux-gnu", sha, sha, bytes,
            signatures, "llvm-embedded-elf")
    }
    private class Entry(language: Language, call: PackageScalarCall) : RootNode(language) {
        @Child private var access = PackageScalarAccess(call)
        override fun execute(frame: VirtualFrame): Long = access.executeLong(frame.arguments, Unit)
    }
    private fun released(language: Language) {
        val handoff = language.handoffState.get()
        assertEquals(0, handoff.arguments.depth); assertEquals(0, handoff.results.depth)
        assertEquals(0, handoff.arguments.retainedReferences()); assertEquals(0, handoff.results.retainedReferences())
    }

    @Test fun nativeStackBoundsAndErrorStatusesMatchOriginalLibc() {
        verify()
        val rows = File(directory, "native.tsv").readLines().map { it.split('\t') }
        assertEquals(9, rows.size)
        context().use { context ->
            context.initialize("thc"); context.enter()
            try {
                val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                val link = control()
                Language.currentState().packageCbits.link(link)
                val entries = link.abi.associate { it.symbol to Entry(language, PackageScalarCall(link, it)).callTarget }
                for ((operation, count, result) in rows) {
                    val n = count.toULong()
                    val model = if (operation == "null") { if (n == 0uL) 0L else -1L }
                        else if (n <= 256uL) 3L else 1L
                    assertEquals(model, result.toLong(), "independent status/bounds model")
                    assertEquals(model, entries.getValue("entropy_$operation").call(n.toLong()), "$operation/$count")
                    released(language)
                }
            } finally { context.leave() }
        }
    }

    @Test fun pinnedBufferIsWrittenInPlaceAndManagedHeapIsNotCopied() {
        verify()
        context().use { context ->
            context.initialize("thc"); context.enter()
            try {
                val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                val link = control()
                Language.currentState().packageCbits.link(link)
                val signature = link.abi.single { it.symbol == "entropy_write" }
                val target = Entry(language, PackageScalarCall(link, signature)).callTarget
                val pinned = PinnedMemory.allocate(258, 64)
                val bits = pinned.nativeSegment()!!.address()
                for (i in 0L..257L) pinned.writeByte(i, 0xa5)
                val address = ManagedAddress.fromAllocation(pinned).plus(1)
                assertEquals(0L, target.call(address, 256L))
                assertEquals(bits, pinned.nativeSegment()!!.address())
                assertEquals(0xa5L, pinned.readByte(0)); assertEquals(0xa5L, pinned.readByte(257))
                val actual = (1L..256L).map { pinned.readByte(it) }
                assertTrue(actual.any { it != 0xa5L }, "real entropy overwrites the same persistent native storage")
                assertEquals(-1L, target.call(address, 257L))
                assertEquals(actual, (1L..256L).map { pinned.readByte(it) }, "oversized request leaves bytes untouched")
                val heap = ManagedAllocation.mutable(8, 8)
                assertThrows(Exception::class.java) { target.call(ManagedAddress.fromAllocation(heap), 8L) }
                assertNull(heap.nativeSegment(), "ordinary heap storage must not gain a native copy")
                assertTrue((0L..7L).all { heap.readByte(it) == 0L })
                released(language)
            } finally { context.leave() }
        }
    }

    @Test fun originalSplitmixSafeInitializerExecutesInBothFirstInstalledBackends() {
        verify()
        val native = File(directory, "splitmix-native.tsv").readLines().map { it.toULong() }
        assertEquals(8, native.size)
        assertTrue(native.toSet().size > 1, "original native public API observes fresh seeds")
        val original = Json.parse(File(directory, "System.Random.SplitMix.Init.json").readText()) as Map<String, Any?>
        val proof = original["packageNativeLink"] as Map<*, *>
        assertEquals("llvm-embedded-elf", proof["format"])
        val libraries = (proof["buildInputs"] as Map<*, *>)["nativeLibraries"] as List<Map<*, *>>
        assertEquals(listOf("native-libc-getentropy-v1"), libraries.map { it["provider"] })
        val merged = CoreModules.merge(listOf(original) + listOf("System.Random.SplitMix.json", "System.Random.SplitMix32.json",
            "entry/units/u-original-splitmix-entry/OriginalSplitmixEntry.json").map {
            Json.parse(File(directory, it).readText()) as Map<String, Any?> })
        val link = (merged["packageScalarLinks"] as List<PackageScalarLink>).single()
        assertEquals(listOf("splitmix_init"), link.abi.map { it.symbol })
        assertTrue(link.abi.all { it.safety == "safe" && it.arguments.isEmpty() && it.result == "Word64Rep" })
        val entry = "original-splitmix-entry:OriginalSplitmixEntry.sample"
        val source = CoreModules.reachable(merged, entry) + ("instrument" to true)
        for (backend in listOf("ast", "bytecode")) context().use { context ->
            context.initialize("thc"); context.enter()
            try {
                val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                val owner = Language.currentState()
                owner.packageCbits.link(link)
                val program: ExecutableProgram = if (backend == "ast") Program(language, source, enableAsync = true)
                    else BytecodeProgram(language, source, enableAsync = true)
                val target = program.entryTarget(entry)
                owner.threads.enterCurrent()
                try {
                    fun call(): Long {
                        val seed = Calls.target(target, arrayOf(0L, Unit)) as Long
                        released(language)
                        return seed
                    }
                    val observations = mutableSetOf<Long>()
                    repeat(4) { observations.add(call()) }
                    target.javaClass.getMethod("compile", Boolean::class.javaPrimitiveType).invoke(target, true)
                    assertEquals(true, target.javaClass.getMethod("isValidLastTier").invoke(target))
                    repeat(4) {
                        val before = (program.diagnostics().getValue("compiledEntries") as Number).toLong()
                        observations.add(call())
                        assertTrue((program.diagnostics().getValue("compiledEntries") as Number).toLong() > before, backend)
                        assertEquals(true, target.javaClass.getMethod("isValidLastTier").invoke(target), backend)
                    }
                    assertTrue(observations.size > 1, "original initializer is not replaced by deterministic entropy")
                } finally { owner.threads.leaveCurrent() }
            } finally { context.leave() }
        }
    }
}
