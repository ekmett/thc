// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime

import org.graalvm.polyglot.Context
import org.graalvm.polyglot.io.IOAccess
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import thc.Language
import thc.NativeIO

class NativeEventDescriptorsTest {
    private fun native(action: (ManagedStdio) -> Unit) {
        assumeTrue(NativeIO.supportedHost())
        NativeIO.createContext().use { context ->
            context.enter()
            try { action(Language.currentState().stdio) } finally { context.leave() }
        }
    }
    private fun integer(address: ManagedAddress, offset: Int, bytes: Int): Long =
        (0 until bytes).fold(0L) { value, index -> value or (address.readWord8((offset + index).toLong()) shl (index * 8)) }

    @Test fun actualEventfdCounterReadWriteAndNativeErrors() = native { stdio ->
        val flags = stdio.flagConstant(OriginalStdioOp.O_NONBLOCK)
        val fd = stdio.eventfd(3, flags)
        assertEquals(3L, fd, "Only the context namespace is exposed")
        val output = ManagedAddress.fromByteArray(ByteArray(8))
        try {
            assertEquals(0L, stdio.eventfdWrite(fd, 5))
            assertEquals(8L, stdio.read(fd, output, 8))
            assertEquals(8L, integer(output, 0, 8))
            assertEquals(-1L, stdio.read(fd, output, 8))
            assertEquals(11L, stdio.errno()) // Linux EAGAIN.
            assertEquals(-1L, stdio.eventfdWrite(fd, -1))
            assertEquals(22L, stdio.errno()) // UINT64_MAX is not an eventfd counter increment.
        } finally { assertEquals(0L, stdio.close(fd)) }
        assertEquals(-1L, stdio.eventfdWrite(fd, 1))
        assertEquals(9L, stdio.errno())
        assertEquals(-1L, stdio.eventfd(0, -1))
        assertEquals(22L, stdio.errno())
    }

    @Test fun actualPipeTransfersAndCloseOnExecUseTheSharedRegistry() = native { stdio ->
        val descriptors = ManagedAddress.fromByteArray(ByteArray(8))
        assertEquals(0L, stdio.pipe(descriptors))
        val reader = integer(descriptors, 0, 4)
        val writer = integer(descriptors, 4, 4)
        assertEquals(3L, reader); assertEquals(4L, writer)
        val payload = ManagedAddress.fromByteArray(byteArrayOf(3, 1, 4, 1, 5))
        val output = ManagedAddress.fromByteArray(ByteArray(5))
        try {
            for (fd in listOf(reader, writer)) assertEquals(0L, stdio.fcntl(fd,
                stdio.flagConstant(OriginalStdioOp.F_SETFD), stdio.flagConstant(OriginalStdioOp.FD_CLOEXEC), true))
            assertEquals(5L, stdio.write(writer, payload, 5))
            assertEquals(1L, stdio.ready(reader, 0, 0, 0))
            assertEquals(5L, stdio.read(reader, output, 5))
            assertEquals((0L..4L).map(payload::readWord8), (0L..4L).map(output::readWord8))
            assertEquals(0L, stdio.close(writer))
            assertEquals(0L, stdio.read(reader, output, 5), "Closed writer produces real pipe EOF")
        } finally { stdio.close(writer); stdio.close(reader) }
    }

    @Test fun malformedOutputAndAbsentAuthorityDoNotAcquireDescriptors() {
        native { stdio ->
            assertThrows(RuntimeFault::class.java) { stdio.pipe(ManagedAddress.fromByteArray(ByteArray(7))) }
            assertThrows(RuntimeFault::class.java) { stdio.eventfd(1L shl 32, 0) }
            val fd = stdio.eventfd(0, 0)
            assertEquals(3L, fd)
            assertEquals(0L, stdio.close(fd))
        }
        Context.newBuilder("thc").allowNativeAccess(true).allowIO(IOAccess.ALL).build().use { context ->
            context.initialize("thc"); context.enter()
            try {
                val stdio = Language.currentState().stdio
                assertEquals(-1L, stdio.eventfd(0, 0))
                assertEquals(-1L, stdio.pipe(ManagedAddress.fromByteArray(ByteArray(8))))
            } finally { context.leave() }
        }
    }

    @Test fun failedPairReservationReleasesTheFirstClaim() = native {
        val state = Language.currentState()
        val limited = ManagedFiles(state.env, state.threads, 4)
        limited.installNative(checkNotNull(state.nativeFiles), emptySet())
        try {
            assertEquals(-1L, limited.pipe(ManagedAddress.fromByteArray(ByteArray(8))))
            assertEquals(10L, limited.errorKind())
            val fd = limited.eventfd(0, 0)
            assertEquals(3L, fd)
            assertEquals(0L, limited.close(fd))
        } finally { limited.dispose() }
    }

    @Test fun eventfdAliasesShareTheRealCounterUntilLastClose() = native { stdio ->
        val fd = stdio.eventfd(2, stdio.flagConstant(OriginalStdioOp.O_NONBLOCK))
        val alias = stdio.duplicate(fd)
        assertEquals(4L, alias)
        val output = ManagedAddress.fromByteArray(ByteArray(8))
        try {
            assertEquals(0L, stdio.close(fd))
            assertEquals(0L, stdio.eventfdWrite(alias, 7))
            assertEquals(8L, stdio.read(alias, output, 8))
            assertEquals(9L, integer(output, 0, 8))
            val reused = stdio.eventfd(19, stdio.flagConstant(OriginalStdioOp.O_NONBLOCK))
            try {
                assertEquals(fd, reused)
                assertEquals(-1L, stdio.read(alias, output, 8), "Reused logical fd is an independent counter")
                assertEquals(11L, stdio.errno())
                assertEquals(8L, stdio.read(reused, output, 8))
                assertEquals(19L, integer(output, 0, 8))
            } finally { stdio.close(reused) }
        } finally { stdio.close(alias) }
        assertEquals(-1L, stdio.eventfdWrite(alias, 1))
        assertEquals(9L, stdio.errno())
    }
}
