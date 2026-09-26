// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime

import org.graalvm.polyglot.Context
import org.graalvm.polyglot.io.IOAccess
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import java.nio.ByteBuffer
import java.nio.ByteOrder
import thc.Language

/** Fixed native-provider controls, not original Haskell FCallId coverage. */
@EnabledOnOs(OS.LINUX)
@EnabledIfSystemProperty(named = "os.arch", matches = "amd64|x86_64")
class SulongLimbProviderTest {
    private fun bytes(vararg values: Long): ByteArray = ByteBuffer.allocate(values.size * 8)
        .order(ByteOrder.nativeOrder()).also { buffer -> values.forEach(buffer::putLong) }.array()
    private fun input(vararg values: Long) = LimbRegion.read(bytes(*values), values.size.toLong(), true)
    private fun output(bytes: ByteArray, count: Long = bytes.size.toLong() / 8) = LimbRegion.write(bytes, count, true)
    private fun provider(action: (LimbProvider) -> Unit) {
        Context.newBuilder("thc").allowNativeAccess(true).allowIO(IOAccess.ALL).build().use { context ->
            context.initialize("thc"); context.enter()
            try { action(SulongLimbProvider(Language.currentState().env)) }
            finally { context.leave() }
        }
    }
    @Test fun fixedControlsExerciseEveryNativeEntryAndUnsignedCarryBorrow() = provider { arithmetic ->
        val two = ByteArray(16)
        assertEquals(1L, arithmetic.add(output(two), input(-1, -1), input(1)))
        assertArrayEquals(bytes(0, 0), two)
        assertEquals(1L, arithmetic.addWord(output(two), input(-1, -1), 1))
        assertArrayEquals(bytes(0, 0), two)
        assertEquals(1L, arithmetic.subtract(output(two), input(0, 0), input(1)))
        assertArrayEquals(bytes(-1, -1), two)
        assertTrue(arithmetic.compare(input(-1), input(1)) > 0)
        assertTrue(arithmetic.compare(input(1), input(-1)) < 0)
        assertEquals(0L, arithmetic.compare(input(-1, 2), input(-1, 2)))
        assertEquals(-2L, arithmetic.multiply(output(two), input(-1), input(-1)))
        assertArrayEquals(bytes(1, -2), two)
        assertEquals(1L, arithmetic.multiplyWord(output(two), input(-1, -1), 2))
        assertArrayEquals(bytes(-2, -1), two)
        assertEquals(0L, arithmetic.divideWord(output(two), 0, input(0, 1), 2))
        assertArrayEquals(bytes(Long.MIN_VALUE, 0), two)
        assertEquals(0L, arithmetic.divideWord(output(two), 1, input(1), 2))
        assertArrayEquals(bytes(Long.MIN_VALUE, 0), two)
        assertEquals(0L, arithmetic.moduloWord(input(5, 1), 3))
        assertEquals(2L, arithmetic.moduloWord(input(5), 3))
        val quotient = ByteArray(8); val remainder = ByteArray(16)
        arithmetic.divide(output(quotient), output(remainder), 0, input(7, 2), input(3, 1))
        // 2*B + 7 = 2*(B + 3) + 1, where B = 2^64.
        assertArrayEquals(bytes(2), quotient); assertArrayEquals(bytes(1, 0), remainder)
        quotient.fill(0); remainder.fill(0)
        arithmetic.quotient(output(quotient), input(7, 2), input(3, 1))
        arithmetic.remainder(output(remainder), input(7, 2), input(3, 1))
        assertArrayEquals(bytes(2), quotient); assertArrayEquals(bytes(1, 0), remainder)
    }
    @Test fun documentedEmptySingleWordDivisionRemainsValid() = provider { arithmetic ->
        assertEquals(0L, arithmetic.moduloWord(input(), 7))
        assertEquals(0L, arithmetic.divideWord(output(ByteArray(0)), 0, input(), 7))
        val fraction = bytes(123, 456)
        assertEquals(0L, arithmetic.divideWord(output(fraction), 2, input(), 7))
        assertArrayEquals(bytes(0, 0), fraction)
        assertThrows(RuntimeFault::class.java) { arithmetic.addWord(output(ByteArray(0)), input(), 1) }
        assertThrows(RuntimeFault::class.java) { arithmetic.compare(input(), input()) }
    }
    @Test fun shiftsAndDoubleConversionKeepRoundingSignsAndPinnedAliases() = provider { arithmetic ->
        val ordinary = bytes(0x5a5a, 0x5a5a)
        assertEquals(1L, arithmetic.shiftRight(output(ordinary, 1), input(1, 1), 64, false))
        assertArrayEquals(bytes(1, 0x5a5a), ordinary)
        assertEquals(0L, arithmetic.shiftRight(output(ordinary, 1), input(1), 1, false))
        assertEquals(1L, arithmetic.shiftRight(output(ordinary, 1), input(1), 1, true))
        val pinned = ManagedAllocation.mutable(24, 8, pinned = true)
        bytes(1, -1, 0x5a5a).forEachIndexed { index, value -> pinned.writeByte(index.toLong(), value.toLong() and 255) }
        assertEquals(1L, arithmetic.shiftRight(LimbRegion.write(pinned, 2), LimbRegion.read(pinned, 2), 64, true))
        assertArrayEquals(bytes(0, 1, 0x5a5a), ByteArray(24) { pinned.readByte(it.toLong()).toByte() })
        assertEquals(0.0.toRawBits(), arithmetic.toDouble(input(), true, 123).toRawBits())
        assertEquals((-0.0).toRawBits(), arithmetic.toDouble(input(1), true, -1075).toRawBits())
        assertEquals(18446744073709551616.0, arithmetic.toDouble(input(1, 1), false, 0))
        assertEquals(-18446744073709551616.0, arithmetic.toDouble(input(1, 1), true, 0))
    }
    @Test fun allowedAliasesSnapshotInputsAndKeepCanaries() = provider { arithmetic ->
        val alias = bytes(-1, -1)
        assertEquals(1L, arithmetic.addWord(output(alias), LimbRegion.read(alias, 2), 1))
        assertArrayEquals(bytes(0, 0), alias)
        val numerator = bytes(7, 2, 0x5a5a)
        arithmetic.remainder(output(numerator, 2), LimbRegion.read(numerator, 2), input(3, 1))
        assertArrayEquals(bytes(1, 0, 0x5a5a), numerator)
        val regionBytes = bytes(0x5a5a, -1, -1, 0x5a5a)
        val base = ManagedAddress.fromByteArray(regionBytes)
        // mpn_mul_1 explicitly permits rp <= sp partial overlap.
        val beforeInput = LimbRegion.region(base.plus(8), 2, false)
        assertEquals(1L, arithmetic.multiplyWord(LimbRegion.region(base, 2, true), beforeInput, 2))
        assertArrayEquals(bytes(-2, -1, -1, 0x5a5a), regionBytes)
        val separate = bytes(0x5a5a, 0, 0, 0x5a5a)
        val middle = LimbRegion.region(ManagedAddress.fromByteArray(separate).plus(8), 2, true)
        arithmetic.addWord(middle, input(4, 5), 7)
        assertArrayEquals(bytes(0x5a5a, 11, 5, 0x5a5a), separate)
    }
    @Test fun gcdAndPopulationCountKeepUnsignedWordsAndOnlyWriteTheResultPrefix() = provider { arithmetic ->
        assertEquals(0L, arithmetic.gcdWords(0, 0))
        assertEquals(-1L, arithmetic.gcdWords(0, -1))
        assertEquals(-1L, arithmetic.gcdWords(-1, 0))
        assertEquals(6L, arithmetic.gcdWords(48, 18))
        assertEquals(2L, arithmetic.gcdWords(Long.MIN_VALUE, 6))
        assertEquals(-1L, arithmetic.gcdWord(input(-1), 0))
        assertEquals(2L, arithmetic.gcdWord(input(0, 1), 6))
        assertEquals(0L, arithmetic.populationCount(input(0)))
        assertEquals(129L, arithmetic.populationCount(input(-1, -1, Long.MIN_VALUE)))
        val destination = bytes(0x5a5a, 0x5a5a, 0x1357)
        assertEquals(1L, arithmetic.gcd(output(destination, 2), input(3, 2), input(3, 1)))
        assertArrayEquals(bytes(1, 0x5a5a, 0x1357), destination)
        assertEquals(1L, arithmetic.gcd(output(destination, 1), input(0), input(0)))
        assertArrayEquals(bytes(0, 0x5a5a, 0x1357), destination)
        assertEquals(2L, arithmetic.gcd(output(destination, 2), input(0, 2), input(0, 1)))
        assertArrayEquals(bytes(0, 1, 0x1357), destination)
    }
    @Test fun leftShiftAndLogicalOperationsPreserveHeapAndPinnedAliases() = provider { arithmetic ->
        for (pinned in listOf(false, true)) {
            fun allocation(vararg words: Long): ManagedAllocation =
                ManagedAllocation.mutable(words.size.toLong() * 8, 8, pinned).also { buffer ->
                    bytes(*words).forEachIndexed { index, value -> buffer.writeByte(index.toLong(), value.toLong() and 255) }
                }
            fun observe(buffer: ManagedAllocation, size: Int) = ByteArray(size) { buffer.readByte(it.toLong()).toByte() }
            val shift = allocation(3, 1, 0x5a5a, 0x5a5a, 0x1357)
            assertEquals(0L, arithmetic.shiftLeft(LimbRegion.write(shift, 4), LimbRegion.read(shift, 2), 65))
            assertArrayEquals(bytes(0, 6, 2, 0, 0x1357), observe(shift, 40))
            val gcd = allocation(3, 2, 0x1357)
            assertEquals(1L, arithmetic.gcd(LimbRegion.write(gcd, 2), LimbRegion.read(gcd, 2), input(3, 1)))
            assertArrayEquals(bytes(1, 2, 0x1357), observe(gcd, 24))
            for ((operation, expected) in listOf(
                LimbBitwise.AND to bytes(0x0a, 0x50, 0x1357),
                LimbBitwise.AND_NOT to bytes(0xf0, 0xa0, 0x1357),
                LimbBitwise.OR to bytes(0xff, 0xf5, 0x1357),
                LimbBitwise.XOR to bytes(0xf5, 0xa5, 0x1357))) {
                val left = allocation(0xfa, 0xf0, 0x1357)
                val right = allocation(0x0f, 0x55, 0x1357)
                arithmetic.bitwise(LimbRegion.write(right, 2), LimbRegion.read(left, 2), LimbRegion.read(right, 2), operation)
                assertArrayEquals(expected, observe(right, 24), "$operation pinned=$pinned")
                assertArrayEquals(bytes(0xfa, 0xf0, 0x1357), observe(left, 24))
            }
        }
    }
    @Test fun rejectedShapesAliasesDivisorsAndPointerCellsHaveNoGuestStores() = provider { arithmetic ->
        val destination = bytes(0x5a5a, 0x5a5a)
        val initial = destination.copyOf()
        val out = output(destination)
        val shared = bytes(1, 2, 3)
        val address = ManagedAddress.fromByteArray(shared)
        val shifted = bytes(1, 2, 3, 4)
        val shiftAddress = ManagedAddress.fromByteArray(shifted)
        val lower = LimbRegion.region(address, 2, false)
        val upper = LimbRegion.region(address.plus(8), 2, true)
        val pointerOwner = ManagedAllocation.mutable(16, 8)
        pointerOwner.writeAddressByteOffset(0, ManagedAddress.fromByteArray(ByteArray(1)))
        val rejected = listOf<() -> Unit>(
            { arithmetic.add(out, input(1), input(1, 2)) },
            { arithmetic.addWord(out, input(1), 1) },
            { arithmetic.addWord(upper, lower, 1) },
            { arithmetic.multiplyWord(upper, lower, 2) },
            { arithmetic.multiply(output(shared, 2), LimbRegion.read(shared, 1), input(2)) },
            { arithmetic.compare(input(1, 2), input(1)) },
            { arithmetic.divideWord(out, -1, input(1), 2) },
            { arithmetic.divideWord(out, 0, input(1, 2), 0) },
            { arithmetic.moduloWord(input(), 0) },
            { arithmetic.shiftRight(out, input(1, 2), 0, false) },
            { arithmetic.shiftRight(out, input(1, 2), 128, false) },
            { arithmetic.shiftRight(out, input(1, 2), -1, false) },
            { arithmetic.shiftRight(out, input(1, 2), 64, false) },
            { arithmetic.shiftRight(upper, lower, 1, false) },
            { arithmetic.toDouble(LimbRegion.read(pointerOwner, 2), false, 0) },
            { arithmetic.gcdWord(input(), 1) },
            { arithmetic.gcdWord(input(1, 1), 0) },
            { arithmetic.gcdWord(input(1, 0), 7) },
            { arithmetic.gcd(out, input(1), input(1, 1)) },
            { arithmetic.gcd(out, input(1, 1), input(1)) },
            { arithmetic.gcd(out, input(1, 1), input(1, 0)) },
            { arithmetic.gcd(output(destination, 1), input(1, 1), input(0)) },
            { arithmetic.gcd(upper, lower, input(1, 1)) },
            { arithmetic.populationCount(input()) },
            { arithmetic.populationCount(LimbRegion.read(pointerOwner, 2)) },
            { arithmetic.shiftLeft(out, input(1), 0) },
            { arithmetic.shiftLeft(out, input(1), -1) },
            { arithmetic.shiftLeft(out, input(1), Long.MAX_VALUE) },
            { arithmetic.shiftLeft(out, input(1, 1), 1) },
            { arithmetic.shiftLeft(LimbRegion.region(shiftAddress.plus(8), 3, true),
                LimbRegion.region(shiftAddress, 2, false), 1) },
            { arithmetic.bitwise(out, input(1, 1), input(1), LimbBitwise.AND) },
            { arithmetic.bitwise(upper, lower, input(1, 1), LimbBitwise.XOR) },
            { arithmetic.bitwise(out, LimbRegion.read(pointerOwner, 2), input(1, 1), LimbBitwise.OR) },
            { arithmetic.quotient(out, input(1, 2), input(0)) },
            { arithmetic.divide(out, out, 0, input(1, 2), input(3)) },
            { arithmetic.divide(out, output(ByteArray(8)), 1, input(1, 2), input(3)) },
            { arithmetic.remainder(output(destination, 1), input(1), input(1, 2)) },
            { arithmetic.addWord(out, LimbRegion.read(pointerOwner, 2), 1) },
            { arithmetic.addWord(LimbRegion.read(destination, 2), input(1, 2), 1) }
        )
        for ((index, call) in rejected.withIndex()) {
            assertThrows(RuntimeFault::class.java, { call() }, "invalid limb request $index")
            assertArrayEquals(initial, destination)
            assertArrayEquals(bytes(1, 2, 3), shared)
            assertArrayEquals(bytes(1, 2, 3, 4), shifted)
        }
        for (count in listOf(-1L, Long.MAX_VALUE, Int.MAX_VALUE.toLong() / 8 + 1))
            assertThrows(RuntimeFault::class.java) { LimbRegion.read(destination, count, true) }
        assertThrows(RuntimeFault::class.java) { LimbRegion.read(destination, 3) }
        val immutable = ManagedAllocation.immutable(bytes(1, 2), 8)
        assertThrows(RuntimeFault::class.java) { LimbRegion.write(immutable, 2) }
    }
    @Test fun managedShrinkIsRecheckedBeforeNativeExecution() = provider { arithmetic ->
        val allocation = ManagedAllocation.mutable(16, 8)
        val saved = LimbRegion.write(allocation, 2)
        allocation.shrink(8)
        assertThrows(RuntimeFault::class.java) { arithmetic.addWord(saved, input(1, 2), 1) }
        assertEquals(0L, allocation.readByte(0))
    }
    @Test fun nativePermissionIsRequiredBeforeLoadingTheAdapter() {
        Context.newBuilder("thc").build().use { context ->
            context.initialize("thc"); context.enter()
            try {
                val failure = assertThrows(RuntimeFault::class.java) { SulongLimbProvider(Language.currentState().env) }
                assertTrue(failure.message.orEmpty().contains("native access"))
            } finally { context.leave() }
        }
    }
}
