// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.UnsupportedMessageException;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Engine;
import org.junit.jupiter.api.Test;
import thc.Language;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import static org.junit.jupiter.api.Assertions.*;

class ContextOwnershipTest {
    @Test void closingOneContextRetiresWeakStableAndNativeOwnersTogether() throws Exception {
        record Roots(Language.State state, Object weak, ManagedAddress stable,
                     ManagedAddress address, long bits, NativeReadOnlyPointer pointer) {}
        try (var engine = Engine.create()) {
            class Checks {
                int finalizerCalls;
                final InteropLibrary interop = InteropLibrary.getUncached();
                Roots capture(Context context) {
                    context.initialize("thc"); context.enter();
                    try {
                        var state = Language.currentState();
                        var address = ManagedAddress.fromHex("616263");
                        var stable = state.getStablePointers().make(address);
                        state.getStablePointers().getOrSetSharedCAF(SharedCAFStore.EVENT_MANAGER, stable);
                        var weak = state.getWeaks().make(address, stable, (Supplier<Integer>) () -> ++finalizerCalls);
                        long bits = state.getNativeAddresses().project(address);
                        return new Roots(state, weak, stable, address, bits, Objects.requireNonNull(state.getNativeAddresses().transport(address)));
                    } finally { context.leave(); }
                }
                void live(Context context, Roots roots) throws Exception {
                    context.enter();
                    try {
                        assertEquals(1, roots.state.getWeaks().retainedCount());
                        assertSame(roots.stable, roots.state.getWeaks().dereference(roots.weak).getValue());
                        assertSame(roots.address, roots.state.getStablePointers().dereference(roots.stable));
                        assertTrue(interop.isPointer(roots.pointer));
                        assertEquals(roots.bits, interop.asPointer(roots.pointer));
                        assertEquals(97L, roots.state.getNativeAddresses().recover(roots.bits).readWord8(0));
                    } finally { context.leave(); }
                }
                void retired(Roots roots) {
                    assertEquals(0, roots.state.getWeaks().retainedCount());
                    assertThrows(RuntimeFault.class, () -> roots.state.getWeaks().dereference(roots.weak));
                    assertThrows(RuntimeFault.class, () -> roots.state.getStablePointers().dereference(roots.stable));
                    assertThrows(RuntimeFault.class, () -> roots.state.getStablePointers().getOrSetSharedCAF(
                        SharedCAFStore.EVENT_MANAGER, ManagedAddress.nullAddress()));
                    assertThrows(RuntimeFault.class, () -> roots.state.getNativeAddresses().recover(roots.bits));
                    assertFalse(interop.isPointer(roots.pointer));
                    assertThrows(UnsupportedMessageException.class, () -> interop.asPointer(roots.pointer));
                    assertEquals(0, finalizerCalls);
                }
            }
            var checks = new Checks();
            try (var first = Context.newBuilder("thc").engine(engine).allowNativeAccess(true).build();
                 var second = Context.newBuilder("thc").engine(engine).allowNativeAccess(true).build()) {
                var firstRoots = checks.capture(first);
                var secondRoots = checks.capture(second);
                checks.live(first, firstRoots); checks.live(second, secondRoots);
                first.close();
                checks.retired(firstRoots);
                checks.live(second, secondRoots);
                second.close();
                checks.retired(secondRoots); checks.retired(firstRoots);
            }
        }
    }

    @Test void handoffLayoutsAndThreadUpgradeAreOwnedByEachContext() throws Exception {
        try (var engine = Engine.create()) {
            record Captured(Language.State state, HandoffLayout layout) {}
            java.util.function.Function<Context, Captured> capture = context -> {
                context.initialize("thc"); context.enter();
                try {
                    var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                    var state = Language.currentState(null);
                    assertSame(state.getHandoffLayouts(), language.getHandoffLayouts());
                    return new Captured(state, state.getHandoffLayouts().intern(List.of("IntRep", "BoxedRep (Just Lifted)")));
                } finally { context.leave(); }
            };
            try (var firstContext = Context.newBuilder("thc").engine(engine).build();
                 var secondContext = Context.newBuilder("thc").engine(engine).build()) {
                var firstCaptured = capture.apply(firstContext);
                var secondCaptured = capture.apply(secondContext);
                var first = firstCaptured.state();
                var second = secondCaptured.state();
                assertNotSame(first, second);
                assertNotSame(first.getHandoffLayouts(), second.getHandoffLayouts());
                assertNotSame(first.getJavaScriptImports(), second.getJavaScriptImports());
                assertNotSame(firstCaptured.layout(), secondCaptured.layout());
                assertTrue(first.getSingleThreadedAssumption().isValid());
                assertTrue(second.getSingleThreadedAssumption().isValid());
                // Sequential access by a new thread is enough to leave the
                // lockless phase, before that thread executes guest code.
                var workerError = new AtomicReference<Throwable>();
                var worker = new Thread(() -> {
                    try {
                        firstContext.enter();
                        try { assertSame(first, Language.currentState(null)); }
                        finally { firstContext.leave(); }
                    } catch (Throwable error) { workerError.set(error); }
                });
                worker.setDaemon(true); worker.start(); worker.join(10_000);
                assertFalse(worker.isAlive(), "Second context thread did not leave");
                if (workerError.get() != null) throw new AssertionError("Second context thread failed", workerError.get());
                assertFalse(first.getSingleThreadedAssumption().isValid());
                assertTrue(second.getSingleThreadedAssumption().isValid());
                firstContext.enter();
                try { assertFalse(first.getSingleThreadedAssumption().isValid()); }
                finally { firstContext.leave(); }
            }
        }
    }
}
