// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/** Parser controls usable on Windows; these model receipts are not native ABI evidence. */
class PosixStdioHostAbiModelTest {
    private fun document(system: String, arch: String) = mapOf<String, Any?>(
        "schema" to 1, "system" to system, "architecture" to arch,
        "target" to if (system == "Linux") "$arch-unknown-linux-gnu" else "$arch-apple-darwin25",
        "widths" to mapOf("charBits" to 8, "pointer" to 8, "int" to 4, "bool" to 1, "size" to 8, "ssize" to 8),
        "errno" to listOf("ENOENT", "EACCES", "EEXIST", "EBADF", "EINVAL", "EIO", "ENOTSUP",
            "EBUSY", "EISDIR", "ENOTTY", "ESPIPE", "EMFILE").withIndex().associate { it.value to it.index + 1000 },
        "seek" to mapOf("SEEK_SET" to Int.MIN_VALUE, "SEEK_CUR" to 701, "SEEK_END" to -901),
        "open" to mapOf("modeBytes" to if (system == "Linux") 4 else 2,
            "O_ACCMODE" to 3, "O_RDONLY" to 0, "O_WRONLY" to 1, "O_RDWR" to 2, "O_APPEND" to 8,
            "O_CREAT" to 16, "O_EXCL" to 32, "O_BINARY" to 0, "O_TRUNC" to 64, "O_NOCTTY" to 128,
            "O_NONBLOCK" to 256, "F_GETFL" to 3, "F_SETFL" to 4, "F_SETFD" to 2, "FD_CLOEXEC" to 1),
        "at" to mapOf("AT_FDCWD" to -1000, "AT_REMOVEDIR" to 512, "AT_SYMLINK_NOFOLLOW" to 256,
            "AT_EMPTY_PATH" to if (system == "Linux") 4096 else 0), "siginfoBytes" to 128)

    @Test fun posixModelsPreserveSignedValuesFlagsAndPrivateCategories() {
        for (system in listOf("Linux", "Darwin")) for (arch in listOf("x86_64", "aarch64")) {
            val value = document(system, arch)
            val abi = StdioHostAbi.parse(value, system, if (arch == "x86_64") "amd64" else "arm64")
            assertEquals(-1000L, abi.atFdcwd); assertEquals(512L, abi.atRemoveDir)
            assertEquals(256L, abi.atSymlinkNoFollow); assertEquals(128L, abi.siginfoBytes)
            assertEquals(if (system == "Linux") 4096L else 0L, abi.atEmptyPath)
            if (system == "Linux") assertDoesNotThrow { abi.requireOpenAbi() }
            else assertThrows(RuntimeFault::class.java) { abi.requireOpenAbi() }
            for (flags in listOf(0L, 1L, 2L)) {
                assertEquals(flags != 1L, abi.openReadable(flags))
                assertEquals(flags != 0L, abi.openWritable(flags))
                assertFalse(abi.openAppend(flags)); assertTrue(abi.openAppend(flags or 8L))
            }
            assertEquals(256L, abi.flagConstant(OriginalStdioOp.O_NONBLOCK))
            assertThrows(RuntimeFault::class.java) { abi.flagConstant(OriginalStdioOp.ERRNO) }
            for ((mode, op) in listOf(OriginalStdioOp.SEEK_SET, OriginalStdioOp.SEEK_CUR, OriginalStdioOp.SEEK_END).withIndex())
                assertEquals(mode.toLong(), abi.seekMode(abi.seekConstant(op)))
            assertEquals(Int.MIN_VALUE.toLong(), abi.seekConstant(OriginalStdioOp.SEEK_SET))
            assertNull(abi.seekMode(0)); assertNull(abi.seekMode(Long.MAX_VALUE))
            assertThrows(RuntimeFault::class.java) { abi.seekConstant(OriginalStdioOp.ERRNO) }
            for (kind in 1L..10L) assertEquals(kind, abi.privateErrorKind(abi.error(kind)))
            assertEquals(7L, abi.privateErrorKind(abi.notSeekable()))
            assertEquals(6L, abi.privateErrorKind(-1))
            for (kind in listOf(Long.MIN_VALUE, -1, 0, 11, Long.MAX_VALUE, (1L shl 32) + 1))
                assertEquals(abi.error(6), abi.error(kind))
        }
    }

    @Test fun posixModelsRejectWrongTypesFieldSetsRangesAndAliasedFlags() {
        for (system in listOf("Linux", "Darwin")) {
            val value = document(system, "x86_64")
            fun reject(wrong: Any?) = assertThrows(RuntimeFault::class.java) { StdioHostAbi.parse(wrong, system, "amd64") }
            for (section in listOf("widths", "errno", "seek", "open", "at")) {
                val fields = value[section] as Map<*, *>
                for (field in fields.keys) {
                    for (wrong in listOf(null, true, 1.0, "1", 1.toShort(), 1.toByte(), Long.MAX_VALUE, Long.MIN_VALUE))
                        reject(value + (section to (fields + (field to wrong))))
                    reject(value + (section to (fields - field)))
                }
                reject(value + (section to (fields + ("extra" to 1))))
                reject(value - section)
            }
            for (wrong in listOf(null, false, 128.0, 0, -1, Long.MAX_VALUE)) reject(value + ("siginfoBytes" to wrong))
            val open = value["open"] as Map<*, *>
            for ((key, wrong) in listOf("modeBytes" to 8, "O_ACCMODE" to 0, "O_RDONLY" to 2,
                "O_APPEND" to 0, "O_APPEND" to 1, "F_SETFL" to 3)) reject(value + ("open" to (open + (key to wrong))))
            val at = value["at"] as Map<*, *>
            for (field in listOf("AT_FDCWD", "AT_REMOVEDIR", "AT_SYMLINK_NOFOLLOW")) reject(value + ("at" to (at + (field to 0))))
            if (system == "Linux") reject(value + ("at" to (at + ("AT_EMPTY_PATH" to 0))))
            val seek = value["seek"] as Map<*, *>
            reject(value + ("seek" to (seek + ("SEEK_END" to seek["SEEK_CUR"]))))
            for (wrong in listOf(null, emptyList<Any>(), emptyMap<String, Any>())) reject(wrong)
        }
    }

    @Test fun posixModelsRejectOtherPlatformsAndCompilerTargets() {
        for (system in listOf("Linux", "Darwin")) {
            val value = document(system, "x86_64")
            for (target in listOf("x86_64-unknown-linux-musl", "x86_64-unknown-linux-gnux32", "wasm32-wasi", "aarch64"))
                assertThrows(RuntimeFault::class.java) { StdioHostAbi.parse(value + ("target" to target), system, "amd64") }
            for ((key, wrong) in listOf("schema" to 2, "architecture" to "aarch64", "system" to "Windows"))
                assertThrows(RuntimeFault::class.java) { StdioHostAbi.parse(value + (key to wrong), system, "amd64") }
            assertThrows(RuntimeFault::class.java) { StdioHostAbi.parse(value, "Windows", "amd64") }
            assertThrows(RuntimeFault::class.java) { StdioHostAbi.parse(value, system, "riscv64") }
        }
    }
}
