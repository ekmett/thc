// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime

import org.graalvm.polyglot.Context
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import thc.Language

class SavedTermiosTest {
    @Test fun slotsPreserveOpaqueAddressesAndOffsetsWithoutReadingMemory() {
        Context.create("thc").use { context ->
            context.initialize("thc"); context.enter()
            try {
                val state = Language.currentState()
                val saved = state.savedTermios
                val bytes = ByteArray(8)
                val offset = ManagedAddress.fromByteArray(bytes).plus(3)
                val onePast = offset.plus(5)
                val stable = state.stablePointers.make("opaque")
                val numeric = state.nativeAddresses.recover(0x12345L)
                for (address in listOf(offset, onePast, stable, numeric, ManagedAddress.nullAddress())) {
                    for (fd in listOf(Int.MIN_VALUE.toLong(), -1L, 0L, 1L, 2L, 3L, Int.MAX_VALUE.toLong())) {
                        saved.set(fd, address)
                        assertSame(if (fd in 0L..2L) address else ManagedAddress.nullAddress(), saved.get(fd))
                        saved.set(fd, ManagedAddress.nullAddress())
                    }
                }
                saved.set(1, offset)
                saved.get(1).writeWord8(0, 91)
                assertEquals(91.toByte(), bytes[3])
                for (fd in listOf(Long.MIN_VALUE, -2147483649L, 2147483648L, Long.MAX_VALUE)) {
                    assertThrows(RuntimeFault::class.java) { saved.get(fd) }
                    assertThrows(RuntimeFault::class.java) { saved.set(fd, onePast) }
                    assertSame(offset, saved.get(1))
                }
                saved.set(1, stable)
                state.stablePointers.free(stable)
                assertSame(stable, saved.get(1), "Pointer retention does not dereference or validate pointee storage")
            } finally { context.leave() }
        }
    }

    @Test fun rootsBelongToTheCurrentContextAndAreDroppedAtDisposal() {
        val first = Context.create("thc")
        val second = Context.create("thc")
        lateinit var saved: SavedTermios
        try {
            first.initialize("thc"); first.enter()
            try {
                saved = Language.currentState().savedTermios
                saved.set(0, ManagedAddress.fromByteArray(ByteArray(4)))
                assertEquals(1, saved.retainedCount())
            } finally { first.leave() }
            second.initialize("thc"); second.enter()
            try {
                assertSame(ManagedAddress.nullAddress(), Language.currentState().savedTermios.get(0))
                assertThrows(RuntimeFault::class.java) { saved.get(0) }
                assertThrows(RuntimeFault::class.java) { saved.set(0, ManagedAddress.nullAddress()) }
                assertEquals(1, saved.retainedCount())
            } finally { second.leave() }
            first.close()
            assertEquals(0, saved.retainedCount())
            assertThrows(RuntimeFault::class.java) { saved.get(0) }
        } finally { first.close(); second.close() }
    }
}
