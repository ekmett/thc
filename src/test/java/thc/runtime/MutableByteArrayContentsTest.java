// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.VirtualFrame;
import java.lang.ref.WeakReference;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import thc.runtime.Unit;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Model/contract controls; authentic compiled consumers live in PinnedPointerCellsTest. */
class MutableByteArrayContentsTest {
    private final CoreRepresentation reference = proof(CoreKind.OBJECT, List.of("BoxedRep (Just Unlifted)"), null);
    private final CoreRepresentation address = proof(CoreKind.ADDRESS, List.of("AddrRep"), null);
    private final PinnedMemoryOp operation = PinnedMemoryOp.MUTABLE_CONTENTS;
    private CoreRepresentation proof(CoreKind kind, List<String> reps, List<CoreRepresentation> components) {
        return new CoreRepresentation(kind, true, true, reps, components, null, null, null, null);
    }
    @Test
    void exactUnliftedInputAndAddressResultDoNotAdmitOtherLogicalCarriers() {
        assertSame(operation, PinnedMemoryOp.named("mutableByteArrayContents#"));
        assertDoesNotThrow(() -> operation.validate(List.of(reference), List.of(false), address));
        for (var bad : List.of(CoreRepresentation.UNKNOWN, proof(CoreKind.DATA, reference.getPrimReps(), null),
                 proof(CoreKind.OBJECT, List.of("BoxedRep (Just Lifted)"), null),
                 proof(CoreKind.OBJECT, List.of("BoxedRep Nothing"), null),
                 proof(CoreKind.OBJECT, reference.getPrimReps(), List.of()), address))
            assertThrows(RuntimeFault.class, () -> operation.validate(List.of(bad), List.of(false), address));
        for (var bad : List.of(CoreRepresentation.UNKNOWN, reference,
                 proof(CoreKind.OBJECT, address.getPrimReps(), null), proof(CoreKind.ADDRESS, List.of("WordRep"), null),
                 proof(CoreKind.ADDRESS, address.getPrimReps(), List.of(address))))
            assertThrows(RuntimeFault.class, () -> operation.validate(List.of(reference), List.of(false), bad));
        for (var flags : List.of(List.of(), List.of(true), List.of(0L), List.of(false, false)))
            assertThrows(RuntimeFault.class, () -> operation.validate(List.of(reference), flags, address));
        for (var args : List.of(List.<CoreRepresentation>of(), List.of(reference, reference)))
            assertThrows(
                RuntimeFault.class, () -> operation.validate(args, Collections.nCopies(args.size(), false), address));
    }
    private ManagedAddress contents(Object value) {
        int[] reads = {0};
        var operand = new Expr() {
            {
                setRepresentation(reference);
            }
            @Override
            public Object execute(VirtualFrame frame) {
                reads[0]++;
                return value;
            }
        };
        var expression = new PinnedByteArrayContents(address, operand);
        var frame = Truffle.getRuntime().createVirtualFrame(new Object[0], FrameDescriptor.newBuilder().build());
        var result = expression.executeAddress(frame);
        assertEquals(1, reads[0]);
        assertTrue(result.sameLocation((ManagedAddress) expression.execute(frame)));
        assertEquals(2, reads[0]);
        return result;
    }
    @Test
    void mutableContentsKeepsBackingIdentityAndBidirectionalWritesWithoutFreezing() {
        for (var array : List.of(new byte[24], PinnedMemory.allocate(24, 8))) {
            var first = contents(array);
            var alias = contents(array);
            assertTrue(first.sameLocation(alias));
            assertFalse(first.sameLocation(contents(new byte[24])));
            ManagedByteArray.writeGuest(array, 0, 0x1e9);
            assertEquals(0xe9L, alias.readWord8(0));
            first.plus(7).writeWord8(0, 0x1ff);
            assertEquals(255L, (long) ManagedByteArray.readGuest(array, 7, true));
            var frozen = ManagedByteArray.freezeGuest(array);
            assertSame(array, frozen);
            assertTrue(first.sameLocation(ManagedAddress.fromGuestByteArray(frozen)));
            // Unsafe freeze does not copy or change physical backing identity.
            assertEquals(255L, alias.readWord8(7));
            // Address arithmetic may leave the allocation; dereferences still
            // check its bounds, and overflowing offset arithmetic still fails.
            assertThrows(RuntimeFault.class, () -> first.plus(-1).readWord8(0));
            assertThrows(RuntimeFault.class, () -> first.plus(Long.MAX_VALUE).readWord8(0));
            assertThrows(RuntimeFault.class, () -> first.plus(1).plus(Long.MAX_VALUE));
            assertThrows(RuntimeFault.class, () -> first.plus(24).readWord8(0));
        }
        var empty = contents(PinnedMemory.allocate(0, 1));
        assertTrue(empty.sameLocation(empty.plus(0)));
        assertThrows(RuntimeFault.class, () -> empty.readWord8(0));
        for (var value : Arrays.asList(null, Unit.INSTANCE, 0L, ManagedAddress.nullAddress()))
            assertThrows(RuntimeFault.class, () -> contents(value));
    }
    private record SurvivingAddress(WeakReference<ManagedAllocation> owner, ManagedAddress survivor) {}
    private SurvivingAddress allocate() {
        var array = PinnedMemory.allocate(24, 8);
        var base = contents(array);
        var target = ManagedAddress.fromByteArray(new byte[] {73});
        PinnedMemory.writeAddressArray(array, 0, target);
        assertSame(target, base.readAddressElementIndex(0));
        assertThrows(RuntimeFault.class, () -> base.readWord8(0));
        assertThrows(RuntimeFault.class, () -> base.writeWord8(0, 1));
        assertSame(target, base.readAddressElementIndex(0));
        base.writeWord8(16, 91);
        return new SurvivingAddress(new WeakReference<>(array), base.plus(16));
    }
    @Test
    void contentsRetainsPointerOwnershipWithoutExposingPointerBits() {
        var result = allocate();
        var owner = result.owner();
        var survivor = result.survivor();
        System.gc();
        assertNotNull(owner.get(), "A surviving Addr# strongly retains its original allocation");
        assertEquals(91L, survivor.readWord8(0));
        assertEquals(73L, survivor.plus(-16).readAddressElementIndex(0).readWord8(0));
    }
}
