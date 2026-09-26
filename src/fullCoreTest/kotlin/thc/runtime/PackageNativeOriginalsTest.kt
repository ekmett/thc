// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
@file:Suppress("UNCHECKED_CAST")
package thc.runtime

import com.oracle.truffle.api.TruffleLanguage
import com.oracle.truffle.api.frame.VirtualFrame
import com.oracle.truffle.api.nodes.RootNode
import org.graalvm.polyglot.Context
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import thc.*
import java.io.File

/** Original package source, typed retained imports, and an independent native
 * Haskell oracle. This checks common foreign adapters, not whole Pandoc Core. */
class PackageNativeOriginalsTest {
    private val root = File(System.getProperty("thc.projectRoot"))
    private val directory = File(root, "build/original-native")
    private class Entry(language: Language, private val call: PackageScalarCall) : RootNode(language) {
        @Child private var access = PackageScalarAccess(call)
        override fun execute(frame: VirtualFrame): Any = access.executeLong(frame.arguments, Unit)
    }

    @Test fun originalDigestCxxAndZlibMatchNativeAcrossOffsetsAndLoopBoundaries() {
        val manifest = Json.parse(File(directory, "manifest.json").readText()) as Map<String, Any?>
        assertEquals(1L, manifest["schema"])
        assertEquals("original-package-foreign-adapters", manifest["scope"])
        assertEquals(270L, manifest["nativeRows"])
        assertEquals(true, manifest["rejectedChangedSource"])
        assertEquals(true, manifest["rejectedHeaderMismatch"])
        OriginalStdioChecks.hashes(root, manifest["sourceHashes"], setOf(
            "build/original-native/sources/digest-0.0.2.1/digest.cabal",
            "build/original-native/sources/digest-0.0.2.1/Data/Digest/CRC32C.hs",
            "build/original-native/sources/digest-0.0.2.1/external/crc32c/src/crc32c.cc",
            "build/original-native/sources/digest-0.0.2.1/external/crc32c/src/crc32c_portable.cc"),
            "build/original-native/sources/digest-0.0.2.1/")
        OriginalStdioChecks.hashes(root, manifest["inputHashes"], setOf(
            "compiler/test-fixtures/OriginalDigestNative.hs", "test/haskell-fixtures/PackageNativeOriginalsFixtures.hs",
            "src/THC/Driver/PackageNative.hs", "src/THC/Driver/NativeLibrarySources.hs"))
        OriginalStdioChecks.hashes(root, manifest["artifactHashes"], setOf(
            "build/original-native/digest-native.tsv", "build/original-native/linked/digest-0.0.2.1-inplace/Data.Digest.Adler32.json",
            "build/original-native/linked/digest-0.0.2.1-inplace/Data.Digest.CRC32.json",
            "build/original-native/linked/digest-0.0.2.1-inplace/Data.Digest.CRC32C.json"), "build/original-native/")
        val modules = File(directory, "linked/digest-0.0.2.1-inplace").listFiles()!!.sortedBy { it.name }
            .map { Json.parse(it.readText()) as Map<String, Any?> }
        assertEquals(setOf("Data.Digest.Adler32", "Data.Digest.CRC32", "Data.Digest.CRC32C"), modules.map { it["module"] }.toSet())
        val merged = CoreModules.merge(modules)
        val link = (merged["packageScalarLinks"] as List<PackageScalarLink>).single()
        assertEquals(6, link.abi.size)
        val rows = File(directory, "digest-native.tsv").readLines().map { it.split('\t') }
        assertEquals(270, rows.size)
        val profile = modules.first()["packageNativeLink"] as Map<*, *>
        val inputs = profile["buildInputs"] as Map<*, *>
        assertEquals(emptyList<Any>(), inputs["unresolved"])
        assertEquals(2, (inputs["providers"] as List<*>).size)
        Context.newBuilder("thc").allowNativeAccess(true).withContextProfile(ContextProfile.SYNCHRONOUS_TEST)
            .build().use { context ->
                context.initialize("thc"); context.enter()
                try {
                    val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                    Language.currentState().packageCbits.link(link)
                    val entries = link.abi.associateWith { Entry(language, PackageScalarCall(link, it)).callTarget }
                    fun check(row: List<String>) {
                        val (symbol, carrier) = row
                        val offset = row[2].toInt(); val count = row[3].toInt(); val seed = row[4].toLong()
                        val bytes = ByteArray(count + offset) { (it * 37 + 11).toByte() }
                        val pointer: Any = if (carrier == "AddrRep") ManagedAddress.fromByteArray(bytes).plus(offset.toLong())
                            else bytes.copyOfRange(offset, bytes.size)
                        val signature = link.abi.single { it.symbol == symbol && carrier in it.arguments }
                        val arguments: Array<Any?> = when (symbol) {
                            "adler32", "crc32" -> arrayOf(seed, pointer, count)
                            "crc32c_extend" -> arrayOf(seed.toInt(), pointer, count.toLong())
                            "crc32c_value" -> arrayOf(pointer, count.toLong())
                            else -> error("Unexpected original digest symbol")
                        }
                        assertEquals(row[5].toLong(), entries.getValue(signature).call(*arguments), row.toString())
                        assertArrayEquals(ByteArray(count + offset) { (it * 37 + 11).toByte() }, bytes,
                            "original checksums must not mutate or replace their heap input")
                    }
                    rows.forEach(::check)
                    entries.values.forEach { it.javaClass.getMethod("compile", Boolean::class.javaPrimitiveType).invoke(it, true) }
                    entries.values.forEach { assertEquals(true, it.javaClass.getMethod("isValidLastTier").invoke(it)) }
                    rows.forEach { row ->
                        check(row)
                        entries.values.forEach { assertEquals(true, it.javaClass.getMethod("isValidLastTier").invoke(it),
                            "each first installed call and subsequent comparison retains code") }
                    }
                } finally { context.leave() }
            }
    }
}
