// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.interop.InteropLibrary;
import org.graalvm.polyglot.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import thc.runtime.*;
import static org.junit.jupiter.api.Assertions.*;
import static thc.CoreBackendTestSupport.*;

/** Re-exporting a callable must not replace its own execution policy. */
class HostReferenceProvenanceTest {
    @Test void nestedPolicyCannotUpgradeItsCallerAndRestoresItsAdmission() throws Exception {
        var threads = new GuestThreads(ThreadLocal.withInitial(() -> MaskingState.UNMASKED), ignored -> {});
        threads.enterCurrent(null, false, false, null);
        try {
            var outer = threads.currentIdentity();
            threads.enterCurrent(null, false, true, null);
            try {
                assertSame(outer, threads.currentIdentity());
                assertFalse(threads.pollState(Thread.currentThread()).getCurrent().getExternalAsync());
                threads.setCurrentExternalAsync(true);
                assertFalse(threads.pollState(Thread.currentThread()).getCurrent().getExternalAsync());
                CompletableFuture.runAsync(() -> assertThrows(UnsupportedCore.class,
                    () -> threads.send(outer, "nested nonresumable caller"))).get(5, TimeUnit.SECONDS);
            } finally { threads.leaveCurrent(); }
            assertSame(outer, threads.currentIdentity());
            assertFalse(threads.pollState(Thread.currentThread()).getCurrent().getExternalAsync());
        } finally { threads.leaveCurrent(); }
        assertNull(threads.pollState(Thread.currentThread()).getCurrent());

        threads.enterCurrent(null, false, true, null);
        try {
            var outer = threads.currentIdentity();
            threads.enterCurrent(null, false, false, null);
            try {
                assertFalse(threads.pollState(Thread.currentThread()).getCurrent().getExternalAsync());
                threads.enterCurrent(null, false, true, null);
                try {
                    assertSame(outer, threads.currentIdentity());
                    assertFalse(threads.pollState(Thread.currentThread()).getCurrent().getExternalAsync());
                    threads.setCurrentExternalAsync(true);
                    assertFalse(threads.pollState(Thread.currentThread()).getCurrent().getExternalAsync());
                } finally { threads.leaveCurrent(); }
                assertFalse(threads.pollState(Thread.currentThread()).getCurrent().getExternalAsync());
            } finally { threads.leaveCurrent(); }
            assertTrue(threads.pollState(Thread.currentThread()).getCurrent().getExternalAsync());
        } finally { threads.leaveCurrent(); }
        assertNull(threads.pollState(Thread.currentThread()).getCurrent());
    }
    @Test void queuedDeliveryWaitsForResumablePhaseWithoutLosingSelfDelivery() throws Exception {
        var threads = new GuestThreads(ThreadLocal.withInitial(() -> MaskingState.UNMASKED), ignored -> {});
        threads.enterCurrent(null, false, true, null);
        AsyncRequest queued = null;
        try {
            var identity = threads.currentIdentity();
            queued = CompletableFuture.supplyAsync(() -> threads.send(identity, "queued during force")).get(5, TimeUnit.SECONDS);
            boolean previous = threads.setCurrentExternalAsync(false);
            try {
                assertNull(threads.poll(null, true));
                assertFalse(threads.interruptibleForeignPending());
                assertEquals(AsyncRequestState.PENDING, queued.getState());
                var self = threads.send(identity, "synchronous self delivery");
                assertSame(self, threads.poll(null)); self.acknowledge();
                assertEquals(AsyncRequestState.PENDING, queued.getState());
            } finally { threads.setCurrentExternalAsync(previous); }
            assertSame(identity, threads.currentIdentity());
            assertSame(queued, threads.poll(null)); queued.acknowledge();
        } finally {
            if (queued != null && queued.getState() == AsyncRequestState.PENDING) queued.cancel();
            threads.leaveCurrent();
        }
        assertNull(threads.pollState(Thread.currentThread()).getCurrent());
    }

}
