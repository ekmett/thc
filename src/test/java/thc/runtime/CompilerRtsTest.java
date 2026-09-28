// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.LongStream;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;
import thc.Language;
import static org.junit.jupiter.api.Assertions.*;

class CompilerRtsTest {
    private Context context() {
        var context = Context.newBuilder("thc").build(); context.initialize("thc"); return context;
    }
    private final CoreRepresentation proof = new CoreRepresentation(CoreKind.ADDRESS, true, true, List.of("AddrRep"), null, null, null, null, null);
    @Test void uniqueCellsRetainAliasesAndRejectForeignOrDisposedContexts() {
        var first = context(); var second = context();
        ManagedAddress counter, alias;
        first.enter();
        try {
            counter = CoreDataLabels.INSTANCE.fromCore("ghc_unique_counter64", proof, null);
            alias = counter.plus(8).plus(-8);
            var step = CoreDataLabels.INSTANCE.fromCore("ghc_unique_inc", proof, null);
            assertEquals(0L, AtomicAddressOp.READ.numeric(counter, 0, 0));
            assertEquals(1L, AtomicAddressOp.READ.numeric(step, 0, 0));
            assertTrue(counter.sameLocation(alias)); assertFalse(counter.sameLocation(step));
            AtomicAddressOp.WRITE.numeric(counter, -2, 0);
            assertEquals(-2L, AtomicAddressOp.ADD.numeric(alias, 1, 0));
            assertEquals(-1L, AtomicAddressOp.ADD.numeric(counter, 1, 0));
            assertEquals(0L, AtomicAddressOp.CAS.numeric(alias, 0, 73));
            assertEquals(73L, AtomicAddressOp.READ.numeric(counter, 0, 0));
            assertThrows(RuntimeFault.class, () -> AtomicAddressOp.READ.numeric(counter.plus(1), 0, 0));
            var unevaluated = new CoreRepresentation(CoreKind.ADDRESS, false, true, List.of("AddrRep"), null, null, null, null, null);
            assertThrows(RuntimeFault.class, () -> CoreDataLabels.INSTANCE.fromCore("ghc_unique_counter64", unevaluated, null));
        } finally { first.leave(); }
        second.enter();
        try {
            assertEquals(0L, AtomicAddressOp.READ.numeric(CoreDataLabels.INSTANCE.fromCore("ghc_unique_counter64", proof, null), 0, 0));
            assertThrows(RuntimeFault.class, () -> AtomicAddressOp.READ.numeric(counter, 0, 0));
            assertThrows(RuntimeFault.class, () -> alias.readWord8(0));
            assertThrows(RuntimeFault.class, () -> counter.sameLocation(counter));
        } finally { second.leave(); second.close(); first.close(); }
        assertThrows(RuntimeFault.class, () -> AtomicAddressOp.READ.numeric(alias, 0, 0));
    }
    @Test void originalUniqueCellFetchAddIsAtomicAcrossGuestCarriers() throws Exception {
        try (var context = context()) {
            context.enter();
            ManagedAddress address;
            try { address = CoreDataLabels.INSTANCE.fromCore("ghc_unique_counter64", proof, null); }
            finally { context.leave(); }
            var pool = Executors.newFixedThreadPool(4);
            try {
                var tasks = new ArrayList<Callable<List<Long>>>();
                for (int i = 0; i < 4; i++) tasks.add(() -> {
                    context.enter();
                    try {
                        var values = new ArrayList<Long>();
                        for (int j = 0; j < 1000; j++) values.add(AtomicAddressOp.ADD.numeric(address, 1, 0));
                        return values;
                    } finally { context.leave(); }
                });
                var values = new ArrayList<Long>();
                for (var task : pool.invokeAll(tasks)) values.addAll(task.get(20, TimeUnit.SECONDS));
                assertEquals(new HashSet<>(LongStream.range(0, 4000).boxed().toList()), new HashSet<>(values));
                context.enter();
                try { assertEquals(4000L, AtomicAddressOp.READ.numeric(address, 0, 0)); }
                finally { context.leave(); }
            } finally { pool.shutdownNow(); }
        }
    }
    @Test void fastStringSlotKeepsWinnerAliveAndSeparateFromOtherRtsSlots() {
        var registry = new StablePointers(); var foreign = new StablePointers();
        var value = new Object(); var winner = registry.make(value); var loser = registry.make(new Object());
        var none = ManagedAddress.Companion.nullAddress(); var slot = SharedCAFStore.FAST_STRING;
        assertSame(none, registry.getOrSetSharedCAF(slot, none));
        assertSame(winner, registry.getOrSetSharedCAF(slot, winner));
        assertTrue(registry.equal(winner, registry.getOrSetSharedCAF(slot, loser)));
        registry.free(loser);
        assertSame(value, registry.dereference(registry.getOrSetSharedCAF(slot, none)));
        assertSame(none, registry.getOrSetSharedCAF(SharedCAFStore.EVENT_MANAGER, none));
        assertThrows(RuntimeFault.class, () -> registry.free(winner));
        assertThrows(RuntimeFault.class, () -> registry.getOrSetSharedCAF(slot, foreign.make(new Object())));
        registry.close();
        assertThrows(RuntimeFault.class, () -> registry.dereference(winner));
        foreign.close();
    }
    @Test void fastStringNativeTokenRemainsRootedUntilContextDisposal() {
        var context = Context.newBuilder("thc").allowNativeAccess(true).build();
        StablePointerToken token;
        context.initialize("thc"); context.enter();
        try {
            var registry = Language.currentState(null).getStablePointers$org_intelligence_thc();
            var winner = registry.make(new Object());
            registry.getOrSetSharedCAF(SharedCAFStore.FAST_STRING, winner);
            token = registry.nativeTransport(winner);
            assertTrue(registry.equal(winner, registry.recoverToken(token.getBits())));
            assertThrows(RuntimeFault.class, () -> registry.free(winner));
            assertTrue(token.isPointer());
            assertSame(token, registry.nativeTransport(winner));
        } finally { context.leave(); context.close(); }
        assertFalse(token.isPointer());
    }
    @Test void everySharedSlotHasOneConcurrentWinnerAndIndependentLifetime() throws Exception {
        var registry = new StablePointers(); var foreign = new StablePointers();
        var pool = Executors.newFixedThreadPool(4);
        var none = ManagedAddress.Companion.nullAddress(); var winners = new ArrayList<ManagedAddress>();
        try {
            for (var slot : SharedCAFStore.values()) {
                assertSame(none, registry.getOrSetSharedCAF(slot, none));
                var candidates = new ArrayList<ManagedAddress>();
                for (int i = 0; i < 16; i++) candidates.add(registry.make(new Object()));
                var tasks = new ArrayList<Callable<ManagedAddress>>();
                for (var candidate : candidates) tasks.add(() -> registry.getOrSetSharedCAF(slot, candidate));
                var values = new ArrayList<ManagedAddress>();
                for (var task : pool.invokeAll(tasks)) values.add(task.get(20, TimeUnit.SECONDS));
                var winner = values.getFirst();
                assertTrue(values.stream().allMatch(value -> registry.equal(winner, value)));
                assertTrue(winners.stream().noneMatch(value -> registry.equal(winner, value)));
                winners.add(winner);
                for (var candidate : candidates) {
                    if (registry.equal(candidate, winner)) assertThrows(RuntimeFault.class, () -> registry.free(candidate));
                    else registry.free(candidate);
                }
                assertNotNull(registry.dereference(registry.getOrSetSharedCAF(slot, none)));
                assertSame(none, foreign.getOrSetSharedCAF(slot, none));
                assertThrows(RuntimeFault.class, () -> foreign.getOrSetSharedCAF(slot, winner));
                assertThrows(RuntimeFault.class, () -> registry.getOrSetSharedCAF(slot, foreign.make(new Object())));
            }
            registry.close();
            for (var winner : winners) assertThrows(RuntimeFault.class, () -> registry.dereference(winner));
            for (var slot : SharedCAFStore.values()) assertThrows(RuntimeFault.class, () -> registry.getOrSetSharedCAF(slot, none));
        } finally { pool.shutdownNow(); registry.close(); foreign.close(); }
    }
}
