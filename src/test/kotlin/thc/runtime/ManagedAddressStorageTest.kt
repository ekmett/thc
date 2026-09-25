// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal
import java.lang.reflect.Modifier
import java.math.BigInteger
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ManagedAddressStorageTest {
    private fun position(size: Int, base: Int, displacement: Long, onePast: Boolean): Int? {
        val target = BigInteger.valueOf(base.toLong()).add(BigInteger.valueOf(displacement))
        val limit = BigInteger.valueOf(size.toLong())
        return if (target.signum() < 0 || target > limit || (!onePast && target == limit)) null
            else target.intValueExact()
    }

    @Test fun completeRangesRejectFullWidthOverflowBeforeEffects() {
        for (size in listOf(0, 1, 4, 16)) for (base in 0..size) {
            val bytes = ByteArray(size) { (it + 0x80).toByte() }
            val before = bytes.copyOf()
            val address = ManagedAddress.fromByteArray(bytes).plus(base.toLong())
            val displacements = listOf(Long.MIN_VALUE, Long.MAX_VALUE, -base.toLong() - 1,
                -base.toLong(), -1L, 0L, 1L, size.toLong() - base - 1, size.toLong() - base, size.toLong() - base + 1)
            val counts = listOf(Long.MIN_VALUE, Long.MAX_VALUE, Int.MAX_VALUE.toLong() + 1,
                -1L, 0L, 1L, 2L, size.toLong(), size.toLong() + 1)
            for (displacement in displacements) for (count in counts) {
                val start = BigInteger.valueOf(base.toLong()).add(BigInteger.valueOf(displacement))
                val end = start.add(BigInteger.valueOf(count))
                val valid = start.signum() >= 0 && start <= BigInteger.valueOf(size.toLong()) &&
                    count >= 0 && end <= BigInteger.valueOf(size.toLong())
                val label = "size=$size/base=$base/displacement=$displacement/count=$count"
                for (writable in listOf(false, true)) {
                    if (valid) address.requireRange(displacement, count, writable)
                    else assertThrows(RuntimeFault::class.java,
                        { address.requireRange(displacement, count, writable) }, label)
                    assertArrayEquals(before, bytes, label)
                }
            }
        }
        val literal = ManagedAddress.fromHex("41")
        literal.requireRange(0, 2)
        literal.requireRange(2, 0)
        assertThrows(RuntimeFault::class.java) { literal.requireRange(0, 0, true) }
        assertThrows(RuntimeFault::class.java) { literal.requireRange(2, 0, true) }
    }

    @Test fun overlapUsesBackingIdentityAndExactNonemptyRegions() {
        val bytes = ByteArray(16)
        val first = ManagedAddress.fromByteArray(bytes)
        val alias = ManagedAddress.fromByteArray(ManagedByteArray.freeze(bytes)).plus(8)
        val distinct = ManagedAddress.fromByteArray(bytes.copyOf())
        assertTrue(first.overlaps(0, 16, alias, -8, 16))
        assertTrue(first.overlaps(4, 5, alias, 0, 1))
        assertFalse(first.overlaps(4, 4, alias, 0, 1))
        assertFalse(first.overlaps(0, 16, alias, 8, 0))
        assertFalse(first.overlaps(16, 0, alias, -8, 16))
        assertFalse(first.overlaps(0, 16, distinct, 0, 16))
        assertThrows(RuntimeFault::class.java) { first.overlaps(0, Long.MAX_VALUE, alias, 0, 1) }
        assertThrows(RuntimeFault::class.java) { first.overlaps(0, 1, alias, Long.MIN_VALUE, 0) }
        val literal = ManagedAddress.fromHex("41ff")
        assertTrue(literal.overlaps(1, 2, literal.plus(2), -1, 2))
        assertFalse(literal.overlaps(0, 3, ManagedAddress.fromHex("41ff"), 0, 3))
        assertFalse(first.overlaps(0, 3, literal, 0, 3))
    }

    @Test fun fullWidthBoundsMatchIndependentArithmeticAndFailedWritesHaveNoEffects() {
        val displacements = (-34L..34L).toList() + listOf(
            Long.MIN_VALUE, Long.MIN_VALUE + 1, Long.MAX_VALUE - 1, Long.MAX_VALUE,
            Int.MIN_VALUE.toLong() - 1, Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong(),
            Int.MAX_VALUE.toLong() + 1, -(1L shl 32), (1L shl 32) - 1, 1L shl 32,
            (1L shl 32) + 1)
        for (size in 0..32) for (base in 0..size) for (displacement in displacements) {
            val bytes = ByteArray(size) { (it * 73 + size * 17 + 129).toByte() }
            val before = bytes.copyOf()
            val address = ManagedAddress.fromByteArray(bytes).plus(base.toLong())
            val label = "size=$size/base=$base/displacement=$displacement"
            val arithmetic = position(size, base, displacement, true)
            if (arithmetic == null) {
                assertThrows(RuntimeFault::class.java, { address.plus(displacement) }, label)
            } else {
                val shifted = address.plus(displacement)
                if (displacement == 0L) assertSame(address, shifted, label)
                if (arithmetic < size)
                    assertEquals(before[arithmetic].toLong() and 255L, shifted.readWord8(0), label)
                else assertThrows(RuntimeFault::class.java, { shifted.readWord8(0) }, "one past $label")
                if (size > 0) assertEquals(before[0].toLong() and 255L, shifted.readWord8(-arithmetic.toLong()), label)
            }
            assertArrayEquals(before, bytes, "arithmetic preserved backing $label")
            val access = position(size, base, displacement, false)
            if (access == null) {
                assertThrows(RuntimeFault::class.java, { address.indexChar(displacement) }, label)
                assertThrows(RuntimeFault::class.java, { address.readWord8(displacement) }, label)
                assertThrows(RuntimeFault::class.java, { address.writeWord8(displacement, -1L) }, label)
                assertArrayEquals(before, bytes, "invalid access preserved every byte $label")
            } else {
                val expected = before[access].toLong() and 255L
                assertEquals(expected, address.indexChar(displacement), label)
                assertEquals(expected, address.readWord8(displacement), label)
                assertArrayEquals(before, bytes, "read preserved backing $label")
                val after = before.copyOf()
                after[access] = 0xa5.toByte()
                address.writeWord8(displacement, 0x1234_56a5L)
                assertArrayEquals(after, bytes, "only the selected byte changed $label")
            }
        }
    }

    @Test fun aliasesIncludingUnsafeFrozenArraysSeeEveryWriteAndUnsignedByte() {
        val bytes = ByteArray(258) { 0x5a.toByte() }
        val original = ManagedAddress.fromByteArray(bytes)
        val frozen = ManagedByteArray.freeze(bytes)
        assertSame(bytes, frozen)
        val frozenAddress = ManagedAddress.fromByteArray(frozen)
        val middle = original.plus(129)
        val end = frozenAddress.plus(bytes.size.toLong())
        for (value in 0..255) {
            original.writeWord8(value.toLong() + 1, value.toLong())
            assertEquals(value.toByte(), bytes[value + 1])
            assertEquals(value.toLong(), frozenAddress.readWord8(value.toLong() + 1))
            assertEquals(value.toLong(), middle.indexChar(value.toLong() - 128))
            assertEquals(value.toLong(), end.readWord8(value.toLong() - 257))
        }
        assertEquals(0x5a.toByte(), bytes.first())
        assertEquals(0x5a.toByte(), bytes.last())
        for (value in listOf(Long.MIN_VALUE, Long.MAX_VALUE, -257L, -256L, -129L, -128L, -1L, 0L, 255L, 256L, 511L)) {
            val before = bytes.copyOf()
            frozenAddress.writeWord8(128, value)
            val expected = before.copyOf()
            expected[128] = value.toByte()
            assertArrayEquals(expected, bytes)
            assertEquals(value and 255L, original.readWord8(128))
            assertEquals(value and 255L, middle.readWord8(-1))
            bytes[128] = value.inv().toByte()
            assertEquals(value.inv() and 255L, frozenAddress.indexChar(128), "array writes remain visible")
        }
    }

    @Test fun literalRawBytesAndTrailingNulStayImmutableThroughEveryDerivedAddress() {
        val literal = ManagedAddress.fromHex("00807fFf41")
        val expected = longArrayOf(0, 128, 127, 255, 65, 0)
        for (base in 0..expected.size) {
            val address = literal.plus(base.toLong())
            for (index in expected.indices) {
                val displacement = index.toLong() - base
                assertEquals(expected[index], address.indexChar(displacement))
                assertEquals(expected[index], address.readWord8(displacement))
                val failure = assertThrows(RuntimeFault::class.java) { address.writeWord8(displacement, 0xaa) }
                assertTrue(failure.message!!.contains("immutable literal"))
                assertEquals(expected[index], literal.readWord8(index.toLong()))
            }
            for (displacement in listOf(-base.toLong() - 1, expected.size.toLong() - base,
                Long.MIN_VALUE, Long.MAX_VALUE)) {
                assertThrows(RuntimeFault::class.java) { address.readWord8(displacement) }
                assertThrows(RuntimeFault::class.java) { address.indexChar(displacement) }
                assertThrows(RuntimeFault::class.java) { address.writeWord8(displacement, -1) }
            }
        }
        assertThrows(RuntimeFault::class.java) { literal.plus(-1) }
        assertThrows(RuntimeFault::class.java) { literal.plus(expected.size.toLong() + 1) }
        for (malformed in listOf("0", "000", "xz", "0G", "-1", " 0", "é0"))
            assertThrows(RuntimeFault::class.java, { ManagedAddress.fromHex(malformed) }, malformed)
        val emptyLiteral = ManagedAddress.fromHex("")
        assertEquals(0L, emptyLiteral.readWord8(0), "even an empty literal owns a NUL")
        assertThrows(RuntimeFault::class.java) { emptyLiteral.plus(1).readWord8(0) }
        val emptyArray = ManagedAddress.fromByteArray(ByteArray(0))
        assertSame(emptyArray, emptyArray.plus(0))
        assertThrows(RuntimeFault::class.java) { emptyArray.readWord8(0) }
        assertThrows(RuntimeFault::class.java) { emptyArray.writeWord8(0, 0) }
        assertThrows(RuntimeFault::class.java) { emptyArray.plus(1) }
        assertThrows(RuntimeFault::class.java) { emptyArray.plus(-1) }
    }

    @Test fun finalBackingReferencesSeparateMutableStorageFromLiteralCompilationConstants() {
        val type = ManagedAddress::class.java
        assertTrue(Modifier.isFinal(type.modifiers))
        val fields = type.declaredFields.filterNot { Modifier.isStatic(it.modifiers) }
        assertTrue(fields.all { Modifier.isPrivate(it.modifiers) && Modifier.isFinal(it.modifiers) })
        // New address capabilities may add final ownership fields. Only literal
        // contents may be treated as compilation constants.
        assertEquals(setOf("literalBytes"), fields.filter {
            it.isAnnotationPresent(CompilationFinal::class.java)
        }.map { it.name }.toSet())
        val literalField = fields.single { it.name == "literalBytes" }.also { it.isAccessible = true }
        val mutableField = fields.single { it.name == "mutableBytes" }.also { it.isAccessible = true }
        val ownerField = fields.single { it.name == "owner" }.also { it.isAccessible = true }
        assertEquals(ByteArray::class.java, literalField.type)
        assertEquals(ByteArray::class.java, mutableField.type)
        assertEquals(ManagedAllocation::class.java, ownerField.type)
        assertEquals(1, literalField.getAnnotation(CompilationFinal::class.java).dimensions)
        assertNull(mutableField.getAnnotation(CompilationFinal::class.java))
        assertNull(ownerField.getAnnotation(CompilationFinal::class.java))
        val bytes = ByteArray(8)
        // No root-address reference is retained here: the derived address owns the lifetime.
        val derived = ManagedAddress.fromByteArray(bytes).plus(8).plus(-3)
        assertSame(bytes, mutableField.get(derived))
        assertNull(literalField.get(derived))
        val literal = ManagedAddress.fromHex("ff")
        val shiftedLiteral = literal.plus(1)
        assertNull(mutableField.get(literal))
        assertNull(mutableField.get(shiftedLiteral))
        assertSame(literalField.get(literal), literalField.get(shiftedLiteral))
        assertNotSame(bytes, literalField.get(literal))
        val allocation = ManagedAllocation.mutable(16, 8)
        val pinned = ManagedAddress.fromAllocation(allocation).plus(8)
        assertSame(allocation, ownerField.get(pinned))
        assertNull(literalField.get(pinned)); assertNull(mutableField.get(pinned))
    }
}
