// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.RootNode;
import java.util.function.Consumer;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;
import thc.Language;
import static org.junit.jupiter.api.Assertions.*;

class ManagedCompactsTest {
    private static class Copier extends RootNode {
        @Child private CompactCopyNode copy;
        Copier(Language language) {
            super(language);
            var failures = new GlobalBinding[3];
            for (int index = 0; index < failures.length; index++) {
                failures[index] = new GlobalBinding("failure" + index);
                failures[index].initialize((long) index);
            }
            copy = new CompactCopyNode(new Metrics(false), failures);
        }
        @Override
        public Object execute(VirtualFrame frame) {
            return copy.execute(frame, (ManagedCompact) frame.getArguments()[0], frame.getArguments()[1],
                (Boolean) frame.getArguments()[2]);
        }
    }
    private void withLanguage(Consumer<Language> action) {
        try (var context = Context.newBuilder("thc").build()) {
            context.initialize("thc");
            context.enter();
            try {
                action.accept(TruffleLanguage.LanguageReference.create(Language.class).get(null));
            } finally {
                context.leave();
            }
        }
    }
    @Test
    void graphCopiesSeparateSourcesPreserveRequestedSharingAndReuseRegionMembers() {
        withLanguage(language -> {
            var registry = Language.currentState().compactRegions;
            var leaf = new DataLayout(language, "test:Leaf", "Leaf", new String[] {"IntRep"});
            var pair = new DataLayout(language, "test:Pair", "Pair", new String[] {"LiftedRep", "LiftedRep"});
            var child = leaf.create(new Object[] {17L});
            var source = pair.create(new Object[] {child, child});
            var target = new Copier(language).getCallTarget();
            for (boolean sharing : new boolean[] {false, true}) {
                var region = new ManagedCompact(registry, 32);
                var copied = (DataValue) target.call(region, source, sharing);
                assertNotSame(source, copied);
                var left = (DataValue) pair.read(copied, 0);
                var right = (DataValue) pair.read(copied, 1);
                assertEquals(sharing, left == right);
                assertNotSame(child, left);
                assertEquals(17L, leaf.readLong(left, 0));
                assertTrue(registry.contains(region, copied));
                assertTrue(registry.contains(region, left));
                assertFalse(registry.contains(region, source));
                assertFalse(registry.containsAny(child));
                assertSame(copied, target.call(region, copied, sharing));
                var other = new ManagedCompact(registry, 32);
                var second = target.call(other, copied, sharing);
                assertNotSame(copied, second);
                assertFalse(registry.contains(other, copied));
            }
        });
    }
    @Test
    void cyclesAndLongListsUsePrivateShellsAndAnExplicitWorkStack() {
        withLanguage(language -> {
            var registry = Language.currentState().compactRegions;
            var node = new DataLayout(language, "test:Node", "Node", new String[] {"IntRep", "LiftedRep"});
            var end = new DataLayout(language, "test:End", "End", new String[0]).create(new Object[0]);
            var source = node.allocate();
            node.initializeLong(source, 0, 91);
            node.initialize(source, 1, source);
            var target = new Copier(language).getCallTarget();
            var region = new ManagedCompact(registry, 4096);
            var copied = (DataValue) target.call(region, source, true);
            assertNotSame(source, copied);
            assertSame(copied, node.read(copied, 1));
            assertEquals(91L, node.readLong(copied, 0));
            assertThrows(RuntimeFault.class, () -> target.call(region, source, false));
            DataValue list = end;
            for (int index = 0; index < 20_000; index++) list = node.create(new Object[] {(long) index, list});
            var result = (DataValue) target.call(region, list, false);
            for (int index = 0; index < 20_000; index++) {
                assertEquals(19_999L - index, node.readLong(result, 0));
                result = (DataValue) node.read(result, 1);
            }
            assertSame(end, result);
            assertTrue(region.size() > 4096);
        });
    }
    private void rejected(
        com.oracle.truffle.api.RootCallTarget target, ManagedCompact region, Object value, long reason) {
        assertEquals(reason, assertThrows(GuestException.class, () -> target.call(region, value, true)).getPayload());
    }
    @Test
    void frozenArraysAndBytesCopyWhileMutablePointersAndPinnedStorageReject() {
        withLanguage(language -> {
            var registry = Language.currentState().compactRegions;
            var target = new Copier(language).getCallTarget();
            var leaf = new DataLayout(language, "test:Leaf", "Leaf", new String[] {"IntRep"});
            var value = leaf.create(new Object[] {7L});
            var region = new ManagedCompact(registry, 4096);
            var array = ManagedArray.allocate(2, value);
            rejected(target, region, array, 2);
            ManagedArray.freeze(array);
            var copy = (Object[]) target.call(region, array, true);
            assertNotSame(array, copy);
            assertSame(copy[0], copy[1]);
            assertNotSame(value, copy[0]);
            ManagedArray.thaw(array);
            rejected(target, region, array, 2);
            var small = ManagedSmallArray.allocate(2, value);
            rejected(target, region, small, 2);
            ManagedSmallArray.freeze(small);
            var smallCopy = (SmallArrayStorage) target.call(region, small, true);
            assertNotSame(small, smallCopy);
            assertTrue(smallCopy.getFrozen());
            ManagedSmallArray.thaw(small);
            rejected(target, region, small, 2);
            rejected(target, region, new ManagedMutVar(value), 2);
            rejected(target, region, ManagedAllocation.mutable(8, 8, true), 1);
            var bytes = ManagedAllocation.mutable(8, 8);
            bytes.writeByte(0, 123);
            var compactBytes = (ManagedAllocation) target.call(region, bytes, true);
            bytes.writeByte(0, 17);
            assertEquals(123L, compactBytes.readByte(0));
            assertFalse(compactBytes.isWritable());
        });
    }
    @Test
    void membershipSurvivesAnEscapedValueAndRegionsRejectForeignContextsAndReentry() {
        withLanguage(language -> {
            var registry = Language.currentState().compactRegions;
            var layout = new DataLayout(language, "test:Leaf", "Leaf", new String[] {"IntRep"});
            var target = new Copier(language).getCallTarget();
            DataValue escaped;
            {
                var region = new ManagedCompact(registry, 1);
                escaped = (DataValue) target.call(region, layout.create(new Object[] {111L}), true);
            }
            assertEquals(111L, layout.readLong(escaped, 0));
            assertTrue(registry.containsAny(escaped));
            var region = new ManagedCompact(registry, 1);
            long before = region.size();
            region.resize(8192);
            assertTrue(region.size() > before);
            region.begin();
            try {
                assertThrows(RuntimeFault.class, () -> region.resize(1));
                assertThrows(RuntimeFault.class, () -> target.call(region, escaped, true));
            } finally {
                region.end();
            }
            assertThrows(RuntimeFault.class, () -> new ManagedCompacts().require(region));
            assertThrows(RuntimeFault.class, () -> new ManagedCompact(registry, -1));
        });
    }
}
