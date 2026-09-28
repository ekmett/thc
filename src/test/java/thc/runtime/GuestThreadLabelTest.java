// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import kotlin.Unit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(20)
class GuestThreadLabelTest {
    private static GuestThreads registry() {
        return new GuestThreads(ThreadLocal.withInitial(() -> MaskingState.UNMASKED),
            CpuAffinity.discover(false), ignored -> {});
    }

    @Test void exactArrayIdentityLogicalSizeAndHostNameSurviveReentry() {
        var threads = registry();
        var name = Thread.currentThread().getName();
        threads.enterCurrent(null, false, true, null);
        var id = threads.currentIdentity();
        try {
            assertNull(threads.label(id));
            var raw = "λ\u0000x".getBytes(StandardCharsets.UTF_8);
            threads.label(id, raw);
            assertSame(raw, threads.label(id));
            var owned = ManagedByteArray.allocateGuest(8);
            ManagedByteArray.writeGuest(owned, 0, 65);
            ManagedByteArray.shrinkGuest(owned, 1);
            var frozen = ManagedByteArray.freezeGuest(owned);
            threads.label(id, frozen);
            assertSame(frozen, threads.label(id));
            assertEquals(1L, ManagedByteArray.sizeGuest(threads.label(id)));
            assertEquals(65L, (long) ManagedByteArray.readGuest(threads.label(id), 0, true));
            assertThrows(RuntimeFault.class, () -> threads.label(id, "wrong carrier"));
            assertSame(frozen, threads.label(id), "Failed labels leave the old value intact");
        } finally { threads.leaveCurrent(GuestThreadStatus.FINISHED); }
        threads.enterCurrent(null, false, true, null);
        try {
            assertSame(id, threads.currentIdentity());
            assertNotNull(threads.label(id));
            assertEquals(Thread.currentThread().threadId(), id.getJavaId());
            assertEquals(name, Thread.currentThread().getName());
            var empty = new byte[0];
            threads.label(id, empty);
            assertSame(empty, threads.label(id), "Empty is present, not absent");
        } finally { threads.leaveCurrent(GuestThreadStatus.FINISHED); threads.close(); }
        assertThrows(RuntimeFault.class, () -> threads.label(id));
        assertThrows(RuntimeFault.class, () -> threads.label(id, new byte[0]));
    }

    @Test void labelsRemainOnDeadIdentitiesAndOtherContextsCannotReadOrOverwriteThem() throws InterruptedException {
        var threads = registry();
        for (var outcome : List.of(GuestThreadStatus.FINISHED, GuestThreadStatus.DIED)) {
            var retained = new AtomicReference<GuestThreadId>();
            var bytes = new byte[]{65, 0, 66};
            var child = new Thread(() -> {
                threads.enterCurrent(null, true, true, null);
                var id = threads.currentIdentity();
                retained.set(id);
                try { threads.label(id, bytes); } finally { threads.leaveCurrent(outcome); }
            });
            child.start(); child.join(5000);
            assertFalse(child.isAlive());
            var id = retained.get();
            assertEquals(outcome, threads.status(id));
            assertSame(bytes, threads.label(id));
            var other = registry();
            assertThrows(RuntimeFault.class, () -> other.label(id));
            assertThrows(RuntimeFault.class, () -> other.label(id, new byte[0]));
            assertSame(bytes, threads.label(id));
            var replacement = new byte[]{67};
            threads.label(id, replacement);
            assertSame(replacement, threads.label(id));
            other.close();
        }
        threads.close();
    }

    @Test void exactProofRejectsLiftedBytesFlagsAndIncorrectGetterTuple() {
        var state = new CoreRepresentation(CoreKind.VOID, false, false, List.of(), null, null, null, null, null);
        var opaque = new CoreRepresentation(CoreKind.OBJECT, false, false,
            List.of("BoxedRep (Just Unlifted)"), null, null, null, null, null);
        var integer = new CoreRepresentation(CoreKind.LONG, false, false, List.of("IntRep"), null, null, null, null, null);
        var tuple = new CoreRepresentation(CoreKind.UNKNOWN, false, false,
            List.of("IntRep", "BoxedRep (Just Unlifted)"), List.of(state, integer, opaque), null, null, null, null);
        CoreGuestThreads.validate("labelThread#", List.of(opaque, opaque, state), List.of(false, false, false), state);
        CoreGuestThreads.validate("threadLabel#", List.of(opaque, state), List.of(false, false), tuple);
        assertThrows(RuntimeFault.class, () -> CoreGuestThreads.validate("labelThread#", List.of(opaque,
            opaque.copy(opaque.getKind(), opaque.getEvaluated(), opaque.getPresent(), List.of("BoxedRep (Just Lifted)"),
                opaque.getComponents(), opaque.getVector(), opaque.getAlternatives(), opaque.getTagSlot(), opaque.getAlternativeSlots()),
            state), List.of(false, false, false), state));
        assertThrows(RuntimeFault.class, () -> CoreGuestThreads.validate(
            "labelThread#", List.of(opaque, opaque, state), List.of(false, true, false), state));
        for (var bad : List.of(
                tuple.copy(tuple.getKind(), tuple.getEvaluated(), tuple.getPresent(), List.of("IntRep"),
                    tuple.getComponents(), tuple.getVector(), tuple.getAlternatives(), tuple.getTagSlot(), tuple.getAlternativeSlots()),
                tuple.copy(tuple.getKind(), tuple.getEvaluated(), tuple.getPresent(), tuple.getPrimReps(),
                    List.of(state, opaque, integer), tuple.getVector(), tuple.getAlternatives(), tuple.getTagSlot(), tuple.getAlternativeSlots()))) {
            assertThrows(RuntimeFault.class, () -> CoreGuestThreads.validate(
                "threadLabel#", List.of(opaque, state), List.of(false, false), bad));
        }
    }
}
