// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import java.util.List;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;
import thc.Language;
import static org.junit.jupiter.api.Assertions.*;

class SavedTermiosTest {
    @Test void slotsPreserveOpaqueAddressesAndOffsetsWithoutReadingMemory() {
        try (var context = Context.create("thc")) {
            context.initialize("thc"); context.enter();
            try {
                var state = Language.currentState(null); var saved = state.getSavedTermios();
                var bytes = new byte[8]; var offset = ManagedAddress.fromByteArray(bytes).plus(3);
                var onePast = offset.plus(5); var stable = state.getStablePointers().make("opaque");
                var numeric = state.getNativeAddresses().recover(0x12345L);
                for (var address : List.of(offset, onePast, stable, numeric, ManagedAddress.nullAddress())) {
                    for (long fd : new long[] {Integer.MIN_VALUE, -1, 0, 1, 2, 3, Integer.MAX_VALUE}) {
                        saved.set(fd, address);
                        assertSame(fd >= 0 && fd <= 2 ? address : ManagedAddress.nullAddress(), saved.get(fd));
                        saved.set(fd, ManagedAddress.nullAddress());
                    }
                }
                saved.set(1, offset); saved.get(1).writeWord8(0, 91); assertEquals((byte) 91, bytes[3]);
                for (long fd : new long[] {Long.MIN_VALUE, -2147483649L, 2147483648L, Long.MAX_VALUE}) {
                    assertThrows(RuntimeFault.class, () -> saved.get(fd)); assertThrows(RuntimeFault.class, () -> saved.set(fd, onePast));
                    assertSame(offset, saved.get(1));
                }
                saved.set(1, stable); state.getStablePointers().free(stable);
                assertSame(stable, saved.get(1), "Pointer retention does not dereference or validate pointee storage");
            } finally { context.leave(); }
        }
    }
    @Test void rootsBelongToTheCurrentContextAndAreDroppedAtDisposal() {
        var first = Context.create("thc"); var second = Context.create("thc");
        try {
            first.initialize("thc"); first.enter();
            final SavedTermios saved;
            try {
                saved = Language.currentState(null).getSavedTermios();
                saved.set(0, ManagedAddress.fromByteArray(new byte[4])); assertEquals(1, saved.retainedCount());
            } finally { first.leave(); }
            second.initialize("thc"); second.enter();
            try {
                assertSame(ManagedAddress.nullAddress(), Language.currentState(null).getSavedTermios().get(0));
                assertThrows(RuntimeFault.class, () -> saved.get(0));
                assertThrows(RuntimeFault.class, () -> saved.set(0, ManagedAddress.nullAddress()));
                assertEquals(1, saved.retainedCount());
            } finally { second.leave(); }
            first.close(); assertEquals(0, saved.retainedCount()); assertThrows(RuntimeFault.class, () -> saved.get(0));
        } finally { first.close(); second.close(); }
    }
}
