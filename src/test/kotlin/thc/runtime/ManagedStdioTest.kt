// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime

import org.graalvm.polyglot.Context
import org.graalvm.polyglot.io.IOAccess
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import thc.Language
import java.io.ByteArrayOutputStream
import java.io.IOException

class ManagedStdioTest {
    private fun bytes(vararg value: Byte) = ManagedAddress.fromByteArray(value)
    private fun context(output: ByteArrayOutputStream, errors: ByteArrayOutputStream = ByteArrayOutputStream()) =
        Context.newBuilder("thc").allowIO(IOAccess.NONE).out(output).err(errors).build()
    private fun <T> entered(context: Context, action: (ManagedStdio) -> T): T {
        context.initialize("thc"); context.enter()
        return try { action(Language.currentState().stdio) } finally { context.leave() }
    }

    @Test fun originalWritesUseContextStreamsBinaryRangesAndActualStickyErrno() {
        val output = ByteArrayOutputStream(); val errors = ByteArrayOutputStream()
        val badDescriptor = StdioHostAbi.load().error(4)
        context(output, errors).use { context -> entered(context) { stdio ->
            val address = bytes(99, 0, 127, -128, -1, 98)
            assertEquals(0L, stdio.errno())
            assertEquals(4L, stdio.write(1, address.plus(1), 4))
            assertArrayEquals(byteArrayOf(0, 127, -128, -1), output.toByteArray())
            assertEquals(-1L, stdio.write(-1, address, 1)); assertEquals(badDescriptor, stdio.errno())
            assertEquals(2L, stdio.write(2, address.plus(3), 2))
            assertArrayEquals(byteArrayOf(-128, -1), errors.toByteArray())
            assertEquals(badDescriptor, stdio.errno())
            assertEquals(0L, stdio.write(1, address.plus(6), 0))
            assertEquals(-1L, stdio.write(0, address.plus(6), 0))
            assertEquals(badDescriptor, stdio.errno()); assertEquals(4, output.size())
        } }
    }

    @Test fun ordinaryIoFailureSetsHostEioButMalformedValuesFaultBeforeEffects() {
        val output = object : ByteArrayOutputStream() {
            override fun write(value: ByteArray, offset: Int, length: Int) { throw IOException("host stream failed") }
        }
        context(output).use { context -> entered(context) { stdio ->
            val address = bytes(7)
            val ioError = StdioHostAbi.load().error(6)
            assertEquals(-1L, stdio.write(1, address, 1)); assertEquals(ioError, stdio.errno())
            for (count in listOf(-1L, 2L, Long.MIN_VALUE, Long.MAX_VALUE))
                assertThrows(RuntimeFault::class.java) { stdio.write(1, address, count) }
            for (fd in listOf(Int.MAX_VALUE.toLong() + 1, Int.MIN_VALUE.toLong() - 1))
                assertThrows(RuntimeFault::class.java) { stdio.write(fd, address, 1) }
            assertEquals(ioError, stdio.errno()); assertEquals(0, output.size())
        } }
    }

    @Test fun errnoAndStreamsDoNotLeakBetweenContexts() {
        val one = ByteArrayOutputStream(); val two = ByteArrayOutputStream()
        context(one).use { first -> context(two).use { second ->
            entered(first) { assertEquals(-1L, it.write(-1, bytes(1), 1)) }
            entered(second) {
                assertEquals(0L, it.errno()); assertEquals(1L, it.write(1, bytes(2), 1))
            }
            entered(first) {
                assertEquals(StdioHostAbi.load().error(4), it.errno())
                assertEquals(1L, it.write(1, bytes(3), 1))
            }
        } }
        assertArrayEquals(byteArrayOf(3), one.toByteArray()); assertArrayEquals(byteArrayOf(2), two.toByteArray())
    }
}
