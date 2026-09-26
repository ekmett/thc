// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime

import com.oracle.truffle.api.TruffleLanguage
import com.oracle.truffle.api.frame.VirtualFrame
import com.oracle.truffle.api.nodes.RootNode
import org.graalvm.polyglot.Context
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import thc.Language
import thc.PackageScalarLink
import thc.PackageScalarSignature
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat

/** Actual C execution controls, separate from the original-package Hashable oracle. */
class PackageNativeForeignTest {
    @TempDir lateinit var directory: Path

    private fun library(): PackageScalarLink {
        val source = directory.resolve("native.c")
        val bitcode = directory.resolve("native.bc")
        Files.writeString(source, """
            #include <stdint.h>
            uint64_t sum_bytes(const unsigned char *p, uint64_t n) {
              uint64_t result = 0;
              for (uint64_t i = 0; i < n; ++i) result += p[i];
              return result;
            }
            void update(unsigned char *state, const unsigned char *p, uint64_t n) {
              for (uint64_t i = 0; i < n; ++i) state[0] += p[i];
            }
            uint32_t alias(unsigned char *a, unsigned char *b) {
              a[1] = 197;
              return b[0];
            }
            uint32_t complement32(uint32_t value) { return ~value; }
            uint8_t complement8(uint8_t value) { return (uint8_t) ~value; }
        """.trimIndent())
        val target = if (System.getProperty("os.name") == "Linux") listOf("--target=" +
            (if (System.getProperty("os.arch") == "amd64") "x86_64" else System.getProperty("os.arch")) + "-unknown-linux-gnu") else emptyList()
        val process = ProcessBuilder(listOf(System.getenv("THC_CLANG") ?: "clang") + target + listOf("-O1", "-emit-llvm", "-c",
            source.toString(), "-o", bitcode.toString())).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        assertEquals(0, process.waitFor(), output)
        val bytes = Files.readAllBytes(bitcode)
        val sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
        val abi = listOf(
            PackageScalarSignature("sum_bytes", "sum_bytes", listOf("ByteArray#", "Word64Rep"), "Word64Rep", "capi"),
            PackageScalarSignature("update", "update", listOf("MutableByteArray#", "ByteArray#", "Word64Rep"), "void", "capi"),
            PackageScalarSignature("alias", "alias", listOf("AddrRep", "AddrRep"), "Word32Rep"),
            PackageScalarSignature("complement32", "complement32", listOf("Word32Rep"), "Word32Rep"),
            PackageScalarSignature("complement8", "complement8", listOf("Word8Rep"), "Word8Rep"))
        return PackageScalarLink("native-ffi-control", "test-host", sha, sha, bytes, abi)
    }

    private class Entry(language: Language, private val operation: PackageScalarCall) : RootNode(language) {
        @Child private var access = PackageScalarAccess(operation)
        override fun execute(frame: VirtualFrame): Any {
            val arguments = frame.arguments
            return if (operation.result == "void") {
                access.executeVoid(arguments, Unit)
                Unit
            } else access.executeLong(arguments, Unit)
        }
    }

    @Test fun realCReadsAndMutatesAliasedPersistentByteStorage() {
        val link = library()
        Context.newBuilder("thc").allowNativeAccess(true).allowExperimentalOptions(true)
            .option("engine.BackgroundCompilation", "false").build().use { context ->
                context.initialize("thc"); context.enter()
                try {
                    val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                    Language.currentState().packageCbits.link(link)
                    val functions = link.abi.associate { signature ->
                        signature.symbol to Entry(language, PackageScalarCall(link, signature)).callTarget
                    }
                    val source = byteArrayOf(1, 2, 127, -1)
                    assertEquals(385L, functions.getValue("sum_bytes").call(source, 4L))
                    val state = ManagedAllocation.mutable(8, 8, true)
                    functions.getValue("update").call(state, source, 4L)
                    assertEquals(129L, state.readByte(0))
                    functions.getValue("update").call(state, byteArrayOf(3, 5), 2L)
                    assertEquals(137L, state.readByte(0), "foreign mutations survive separate calls")
                    val address = ManagedAddress.fromAllocation(state)
                    assertEquals(197L, functions.getValue("alias").call(address, address.plus(1)))
                    assertEquals(197L, state.readByte(1), "overlapping arguments share the original allocation")
                    assertEquals(0xffff_ffffL, functions.getValue("complement32").call(0))
                    assertEquals(0L, functions.getValue("complement32").call(-1))
                    assertEquals(255L, functions.getValue("complement8").call(0.toByte()))
                    val frozen = ManagedAllocation.immutable(byteArrayOf(0), 8)
                    assertThrows(Exception::class.java) { functions.getValue("update").call(frozen, source, 4L) }
                    assertArrayEquals(byteArrayOf(1, 2, 127, -1), source)
                } finally { context.leave() }
            }
    }

    @Test fun integerConversionsRetainUnsignedBitsAndRejectOutOfRangeInputs() {
        assertEquals((-1).toByte(), packageCInteger("Word8Rep", 255))
        assertEquals((-1).toShort(), packageCInteger("Word16Rep", 65535))
        assertEquals(-1, packageCInteger("Word32Rep", 0xffff_ffffL))
        for ((rep, value) in listOf("Word8Rep" to -1L, "Word8Rep" to 256L, "Word16Rep" to 65536L,
            "Word32Rep" to 0x1_0000_0000L, "Int8Rep" to 128L, "Int16Rep" to 32768L))
            assertThrows(RuntimeFault::class.java) { packageCInteger(rep, value) }
    }
}
