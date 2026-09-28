// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.io.IOAccess;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.function.Executable;
import thc.Language;
import static org.junit.jupiter.api.Assertions.*;

/** Fixed native-provider controls, not original Haskell FCallId coverage. */
@EnabledOnOs(OS.LINUX)
@EnabledIfSystemProperty(named = "os.arch", matches = "amd64|x86_64")
public class SulongLimbProviderTest {
    private byte[] bytes(long... values) {
        var buffer = ByteBuffer.allocate(values.length * 8).order(ByteOrder.nativeOrder());
        for (long value : values) buffer.putLong(value); return buffer.array();
    }
    private LimbRegion input(long... values) { return LimbRegion.read(bytes(values), values.length, true); }
    private LimbRegion output(byte[] bytes) { return output(bytes, bytes.length / 8L); }
    private LimbRegion output(byte[] bytes, long count) { return LimbRegion.write(bytes, count, true); }
    private void provider(Consumer<LimbProvider> action) {
        try (var context = Context.newBuilder("thc").allowNativeAccess(true).allowIO(IOAccess.ALL).build()) {
            context.initialize("thc"); context.enter();
            try { action.accept(new SulongLimbProvider(Language.currentState().getEnv())); }
            finally { context.leave(); }
        }
    }
    @Test public void fixedControlsExerciseEveryNativeEntryAndUnsignedCarryBorrow() { provider(arithmetic -> {
        var two = new byte[16];
        assertEquals(1L, arithmetic.add(output(two), input(-1, -1), input(1))); assertArrayEquals(bytes(0, 0), two);
        assertEquals(1L, arithmetic.addWord(output(two), input(-1, -1), 1)); assertArrayEquals(bytes(0, 0), two);
        assertEquals(1L, arithmetic.subtract(output(two), input(0, 0), input(1))); assertArrayEquals(bytes(-1, -1), two);
        assertTrue(arithmetic.compare(input(-1), input(1)) > 0); assertTrue(arithmetic.compare(input(1), input(-1)) < 0);
        assertEquals(0L, arithmetic.compare(input(-1, 2), input(-1, 2)));
        assertEquals(-2L, arithmetic.multiply(output(two), input(-1), input(-1))); assertArrayEquals(bytes(1, -2), two);
        assertEquals(1L, arithmetic.multiplyWord(output(two), input(-1, -1), 2)); assertArrayEquals(bytes(-2, -1), two);
        assertEquals(0L, arithmetic.divideWord(output(two), 0, input(0, 1), 2)); assertArrayEquals(bytes(Long.MIN_VALUE, 0), two);
        assertEquals(0L, arithmetic.divideWord(output(two), 1, input(1), 2)); assertArrayEquals(bytes(Long.MIN_VALUE, 0), two);
        assertEquals(0L, arithmetic.moduloWord(input(5, 1), 3)); assertEquals(2L, arithmetic.moduloWord(input(5), 3));
        var quotient = new byte[8]; var remainder = new byte[16];
        arithmetic.divide(output(quotient), output(remainder), 0, input(7, 2), input(3, 1));
        // 2*B + 7 = 2*(B + 3) + 1, where B = 2^64.
        assertArrayEquals(bytes(2), quotient); assertArrayEquals(bytes(1, 0), remainder);
        Arrays.fill(quotient, (byte) 0); Arrays.fill(remainder, (byte) 0);
        arithmetic.quotient(output(quotient), input(7, 2), input(3, 1)); arithmetic.remainder(output(remainder), input(7, 2), input(3, 1));
        assertArrayEquals(bytes(2), quotient); assertArrayEquals(bytes(1, 0), remainder);
    }); }
    @Test public void documentedEmptySingleWordDivisionRemainsValid() { provider(arithmetic -> {
        assertEquals(0L, arithmetic.moduloWord(input(), 7));
        assertEquals(0L, arithmetic.divideWord(output(new byte[0]), 0, input(), 7));
        var fraction = bytes(123, 456);
        assertEquals(0L, arithmetic.divideWord(output(fraction), 2, input(), 7)); assertArrayEquals(bytes(0, 0), fraction);
        assertThrows(RuntimeFault.class, () -> arithmetic.addWord(output(new byte[0]), input(), 1));
        assertThrows(RuntimeFault.class, () -> arithmetic.compare(input(), input()));
    }); }
    @Test public void shiftsAndDoubleConversionKeepRoundingSignsAndPinnedAliases() { provider(arithmetic -> {
        var ordinary = bytes(0x5a5a, 0x5a5a);
        assertEquals(1L, arithmetic.shiftRight(output(ordinary, 1), input(1, 1), 64, false)); assertArrayEquals(bytes(1, 0x5a5a), ordinary);
        assertEquals(0L, arithmetic.shiftRight(output(ordinary, 1), input(1), 1, false));
        assertEquals(1L, arithmetic.shiftRight(output(ordinary, 1), input(1), 1, true));
        var pinned = ManagedAllocation.mutable(24, 8, true); var source = bytes(1, -1, 0x5a5a);
        for (int i = 0; i < source.length; i++) pinned.writeByte(i, source[i] & 255L);
        assertEquals(1L, arithmetic.shiftRight(LimbRegion.write(pinned, 2), LimbRegion.read(pinned, 2), 64, true));
        assertArrayEquals(bytes(0, 1, 0x5a5a), observe(pinned, 24));
        assertEquals(Double.doubleToRawLongBits(0.0), Double.doubleToRawLongBits(arithmetic.toDouble(input(), true, 123)));
        assertEquals(Double.doubleToRawLongBits(-0.0), Double.doubleToRawLongBits(arithmetic.toDouble(input(1), true, -1075)));
        assertEquals(18446744073709551616.0, arithmetic.toDouble(input(1, 1), false, 0));
        assertEquals(-18446744073709551616.0, arithmetic.toDouble(input(1, 1), true, 0));
    }); }
    @Test public void allowedAliasesSnapshotInputsAndKeepCanaries() { provider(arithmetic -> {
        var alias = bytes(-1, -1);
        assertEquals(1L, arithmetic.addWord(output(alias), LimbRegion.read(alias, 2), 1)); assertArrayEquals(bytes(0, 0), alias);
        var numerator = bytes(7, 2, 0x5a5a);
        arithmetic.remainder(output(numerator, 2), LimbRegion.read(numerator, 2), input(3, 1)); assertArrayEquals(bytes(1, 0, 0x5a5a), numerator);
        var regionBytes = bytes(0x5a5a, -1, -1, 0x5a5a); var base = ManagedAddress.fromByteArray(regionBytes);
        // mpn_mul_1 explicitly permits rp <= sp partial overlap.
        var beforeInput = LimbRegion.region(base.plus(8), 2, false);
        assertEquals(1L, arithmetic.multiplyWord(LimbRegion.region(base, 2, true), beforeInput, 2));
        assertArrayEquals(bytes(-2, -1, -1, 0x5a5a), regionBytes);
        var separate = bytes(0x5a5a, 0, 0, 0x5a5a); var middle = LimbRegion.region(ManagedAddress.fromByteArray(separate).plus(8), 2, true);
        arithmetic.addWord(middle, input(4, 5), 7); assertArrayEquals(bytes(0x5a5a, 11, 5, 0x5a5a), separate);
    }); }
    @Test public void gcdAndPopulationCountKeepUnsignedWordsAndOnlyWriteTheResultPrefix() { provider(arithmetic -> {
        assertEquals(0L, arithmetic.gcdWords(0, 0)); assertEquals(-1L, arithmetic.gcdWords(0, -1));
        assertEquals(-1L, arithmetic.gcdWords(-1, 0)); assertEquals(6L, arithmetic.gcdWords(48, 18));
        assertEquals(2L, arithmetic.gcdWords(Long.MIN_VALUE, 6)); assertEquals(-1L, arithmetic.gcdWord(input(-1), 0));
        assertEquals(2L, arithmetic.gcdWord(input(0, 1), 6)); assertEquals(0L, arithmetic.populationCount(input(0)));
        assertEquals(129L, arithmetic.populationCount(input(-1, -1, Long.MIN_VALUE)));
        var destination = bytes(0x5a5a, 0x5a5a, 0x1357);
        assertEquals(1L, arithmetic.gcd(output(destination, 2), input(3, 2), input(3, 1))); assertArrayEquals(bytes(1, 0x5a5a, 0x1357), destination);
        assertEquals(1L, arithmetic.gcd(output(destination, 1), input(0), input(0))); assertArrayEquals(bytes(0, 0x5a5a, 0x1357), destination);
        assertEquals(2L, arithmetic.gcd(output(destination, 2), input(0, 2), input(0, 1))); assertArrayEquals(bytes(0, 1, 0x1357), destination);
    }); }
    private ManagedAllocation allocation(boolean pinned, long... words) {
        var buffer = ManagedAllocation.mutable(words.length * 8L, 8, pinned); var values = bytes(words);
        for (int i = 0; i < values.length; i++) buffer.writeByte(i, values[i] & 255L); return buffer;
    }
    private byte[] observe(ManagedAllocation buffer, int size) {
        var values = new byte[size]; for (int i = 0; i < size; i++) values[i] = (byte) buffer.readByte(i); return values;
    }
    private record BitwiseCase(LimbBitwise operation, byte[] expected) {}
    @Test public void leftShiftAndLogicalOperationsPreserveHeapAndPinnedAliases() { provider(arithmetic -> {
        for (boolean pinned : new boolean[]{false, true}) {
            var shift = allocation(pinned, 3, 1, 0x5a5a, 0x5a5a, 0x1357);
            assertEquals(0L, arithmetic.shiftLeft(LimbRegion.write(shift, 4), LimbRegion.read(shift, 2), 65));
            assertArrayEquals(bytes(0, 6, 2, 0, 0x1357), observe(shift, 40));
            var gcd = allocation(pinned, 3, 2, 0x1357);
            assertEquals(1L, arithmetic.gcd(LimbRegion.write(gcd, 2), LimbRegion.read(gcd, 2), input(3, 1)));
            assertArrayEquals(bytes(1, 2, 0x1357), observe(gcd, 24));
            for (var row : List.of(new BitwiseCase(LimbBitwise.AND, bytes(0x0a, 0x50, 0x1357)),
                    new BitwiseCase(LimbBitwise.AND_NOT, bytes(0xf0, 0xa0, 0x1357)), new BitwiseCase(LimbBitwise.OR, bytes(0xff, 0xf5, 0x1357)),
                    new BitwiseCase(LimbBitwise.XOR, bytes(0xf5, 0xa5, 0x1357)))) {
                var left = allocation(pinned, 0xfa, 0xf0, 0x1357); var right = allocation(pinned, 0x0f, 0x55, 0x1357);
                arithmetic.bitwise(LimbRegion.write(right, 2), LimbRegion.read(left, 2), LimbRegion.read(right, 2), row.operation());
                assertArrayEquals(row.expected(), observe(right, 24), row.operation() + " pinned=" + pinned);
                assertArrayEquals(bytes(0xfa, 0xf0, 0x1357), observe(left, 24));
            }
        }
    }); }
    @Test public void rejectedShapesAliasesDivisorsAndPointerCellsHaveNoGuestStores() { provider(arithmetic -> {
        var destination = bytes(0x5a5a, 0x5a5a); var initial = destination.clone(); var out = output(destination);
        var shared = bytes(1, 2, 3); var address = ManagedAddress.fromByteArray(shared);
        var shifted = bytes(1, 2, 3, 4); var shiftAddress = ManagedAddress.fromByteArray(shifted);
        var lower = LimbRegion.region(address, 2, false); var upper = LimbRegion.region(address.plus(8), 2, true);
        var pointerOwner = ManagedAllocation.mutable(16, 8); pointerOwner.writeAddressByteOffset(0, ManagedAddress.fromByteArray(new byte[1]));
        var rejected = List.<Executable>of(
            () -> arithmetic.add(out, input(1), input(1, 2)),
            () -> arithmetic.addWord(out, input(1), 1),
            () -> arithmetic.addWord(upper, lower, 1),
            () -> arithmetic.multiplyWord(upper, lower, 2),
            () -> arithmetic.multiply(output(shared, 2), LimbRegion.read(shared, 1), input(2)),
            () -> arithmetic.compare(input(1, 2), input(1)),
            () -> arithmetic.divideWord(out, -1, input(1), 2),
            () -> arithmetic.divideWord(out, 0, input(1, 2), 0),
            () -> arithmetic.moduloWord(input(), 0),
            () -> arithmetic.shiftRight(out, input(1, 2), 0, false),
            () -> arithmetic.shiftRight(out, input(1, 2), 128, false),
            () -> arithmetic.shiftRight(out, input(1, 2), -1, false),
            () -> arithmetic.shiftRight(out, input(1, 2), 64, false),
            () -> arithmetic.shiftRight(upper, lower, 1, false),
            () -> arithmetic.toDouble(LimbRegion.read(pointerOwner, 2), false, 0),
            () -> arithmetic.gcdWord(input(), 1),
            () -> arithmetic.gcdWord(input(1, 1), 0),
            () -> arithmetic.gcdWord(input(1, 0), 7),
            () -> arithmetic.gcd(out, input(1), input(1, 1)),
            () -> arithmetic.gcd(out, input(1, 1), input(1)),
            () -> arithmetic.gcd(out, input(1, 1), input(1, 0)),
            () -> arithmetic.gcd(output(destination, 1), input(1, 1), input(0)),
            () -> arithmetic.gcd(upper, lower, input(1, 1)),
            () -> arithmetic.populationCount(input()),
            () -> arithmetic.populationCount(LimbRegion.read(pointerOwner, 2)),
            () -> arithmetic.shiftLeft(out, input(1), 0),
            () -> arithmetic.shiftLeft(out, input(1), -1),
            () -> arithmetic.shiftLeft(out, input(1), Long.MAX_VALUE),
            () -> arithmetic.shiftLeft(out, input(1, 1), 1),
            () -> arithmetic.shiftLeft(LimbRegion.region(shiftAddress.plus(8), 3, true), LimbRegion.region(shiftAddress, 2, false), 1),
            () -> arithmetic.bitwise(out, input(1, 1), input(1), LimbBitwise.AND),
            () -> arithmetic.bitwise(upper, lower, input(1, 1), LimbBitwise.XOR),
            () -> arithmetic.bitwise(out, LimbRegion.read(pointerOwner, 2), input(1, 1), LimbBitwise.OR),
            () -> arithmetic.quotient(out, input(1, 2), input(0)),
            () -> arithmetic.divide(out, out, 0, input(1, 2), input(3)),
            () -> arithmetic.divide(out, output(new byte[8]), 1, input(1, 2), input(3)),
            () -> arithmetic.remainder(output(destination, 1), input(1), input(1, 2)),
            () -> arithmetic.addWord(out, LimbRegion.read(pointerOwner, 2), 1),
            () -> arithmetic.addWord(LimbRegion.read(destination, 2), input(1, 2), 1));
        for (int i = 0; i < rejected.size(); i++) {
            assertThrows(RuntimeFault.class, rejected.get(i), "invalid limb request " + i);
            assertArrayEquals(initial, destination); assertArrayEquals(bytes(1, 2, 3), shared); assertArrayEquals(bytes(1, 2, 3, 4), shifted);
        }
        for (long count : new long[]{-1L, Long.MAX_VALUE, Integer.MAX_VALUE / 8L + 1}) assertThrows(RuntimeFault.class, () -> LimbRegion.read(destination, count, true));
        assertThrows(RuntimeFault.class, () -> LimbRegion.read(destination, 3));
        var immutable = ManagedAllocation.immutable(bytes(1, 2), 8); assertThrows(RuntimeFault.class, () -> LimbRegion.write(immutable, 2));
    }); }
    @Test public void managedShrinkIsRecheckedBeforeNativeExecution() { provider(arithmetic -> {
        var allocation = ManagedAllocation.mutable(16, 8); var saved = LimbRegion.write(allocation, 2); allocation.shrink(8);
        assertThrows(RuntimeFault.class, () -> arithmetic.addWord(saved, input(1, 2), 1)); assertEquals(0L, allocation.readByte(0));
    }); }
    @Test public void nativePermissionIsRequiredBeforeLoadingTheAdapter() {
        try (var context = Context.newBuilder("thc").build()) {
            context.initialize("thc"); context.enter();
            try {
                var failure = assertThrows(RuntimeFault.class, () -> new SulongLimbProvider(Language.currentState().getEnv()));
                assertTrue(failure.getMessage() != null && failure.getMessage().contains("native access"));
            } finally { context.leave(); }
        }
    }
}
