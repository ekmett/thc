// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.TruffleSafepoint;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.foreign.SymbolLookup;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import thc.Language;
import static org.junit.jupiter.api.Assertions.*;

@EnabledOnOs(OS.LINUX)
@Timeout(30)
public class LoomForeignTest {
    @TempDir Path directory;
    private Context context() {
        return Context.newBuilder("thc").allowCreateThread(true).allowNativeAccess(true).allowExperimentalOptions(true)
            .option("thc.ThreadHosting", "loom").build();
    }
    private Context nativeContext() {
        String key = "polyglot.thc.ThreadHosting", previous = System.getProperty(key);
        System.setProperty(key, "loom");
        try { return NativeFileProvider.createContext(java.util.Set.of(), thc.ContextProfile.SYNCHRONOUS_TEST); }
        finally { if (previous == null) System.clearProperty(key); else System.setProperty(key, previous); }
    }

    @Test public void originalSafeAndInterruptibleOpenReleaseTheHecDuringNativeAcquisition() throws Exception {
        for (var operation : new OriginalStdioOp[]{OriginalStdioOp.OPEN_SAFE, OriginalStdioOp.OPEN_INTERRUPTIBLE}) {
            var fifo = directory.resolve(operation.name());
            var created = new ProcessBuilder("mkfifo", fifo.toString()).start();
            assertTrue(created.waitFor(5, TimeUnit.SECONDS)); assertEquals(0, created.exitValue());
            var bytes = fifo.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
            var path = ManagedAddress.fromByteArray(java.util.Arrays.copyOf(bytes, bytes.length + 1));
            try (var context = nativeContext()) {
                context.enter();
                try {
                    var state = Language.currentState(); var threads = state.getThreads(); threads.setCapabilityCount(1);
                    var entered = new CompletableFuture<Void>();
                    var reader = task(state, () -> {
                        entered.complete(null);
                        long fd = state.getStdio().open(path, 0, 0, operation, null);
                        assertTrue(fd >= 0); assertEquals(0L, state.getStdio().close(fd)); return 1L;
                    });
                    var writer = task(state, () -> {
                        long fd = state.getStdio().open(path, 1, 0, operation, null);
                        assertTrue(fd >= 0); assertEquals(0L, state.getStdio().close(fd)); return 2L;
                    });
                    threads.startThread(reader.thread());
                    try {
                        await(entered); threads.startThread(writer.thread());
                        assertEquals(1L, await(reader.result())); assertEquals(2L, await(writer.result()));
                    } finally {
                        // Rescue the deliberately failing version without cancelling a native effect mid-acquisition.
                        if (!reader.result().isDone() || !writer.result().isDone())
                            try (var release = new java.io.RandomAccessFile(fifo.toFile(), "rw")) {
                                await(reader.result()); await(writer.result());
                            }
                    }
                } finally { context.leave(); }
            }
        }
    }
    private record Task<T>(Thread thread, CompletableFuture<T> result) { }
    private <T> Task<T> task(Language.State state, Callable<T> action) {
        var result = new CompletableFuture<T>(); var threads = state.getThreads();
        var thread = threads.newThread(state.getEnv(), () -> {
            threads.enterCurrent(MaskingState.MASKED_INTERRUPTIBLE, true, true, 0L);
            try { result.complete(action.call()); }
            catch (Throwable failure) { result.completeExceptionally(failure); }
            finally { threads.leaveCurrent(); }
        }, 0L, null);
        thread.setUncaughtExceptionHandler((_, failure) -> result.completeExceptionally(failure));
        return new Task<>(thread, result);
    }
    private <T> T await(Future<T> future) {
        return TruffleSafepoint.setBlockedThreadInterruptibleFunction(null, waiting -> {
            try { return waiting.get(5, TimeUnit.SECONDS); }
            catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException failure) { throw new AssertionError(failure); }
        }, future);
    }
    /** libc read keeps the actual virtual-thread continuation pinned until write. */
    private static final class NativeGate implements AutoCloseable {
        private static MethodHandle call(String name, FunctionDescriptor signature) {
            var linker = Linker.nativeLinker();
            return linker.downcallHandle(linker.defaultLookup().find(name).orElseThrow(), signature);
        }
        private static final MethodHandle PIPE = call("pipe", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
        private static final MethodHandle READ = call("read", FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG));
        private static final MethodHandle WRITE = call("write", FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG));
        private static final MethodHandle CLOSE = call("close", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
        private static final MethodHandle SELF = call("pthread_self", FunctionDescriptor.of(ValueLayout.JAVA_LONG));
        private static final MethodHandle TLS = call("__errno_location", FunctionDescriptor.of(ValueLayout.ADDRESS));
        private final Arena arena = Arena.ofShared();
        private final MemorySegment input = arena.allocate(1), output = arena.allocate(1);
        private final int read, write;
        NativeGate() throws Throwable {
            var pair = arena.allocate(ValueLayout.JAVA_INT, 2);
            assertEquals(0, (int) PIPE.invokeExact(pair));
            read = pair.getAtIndex(ValueLayout.JAVA_INT, 0); write = pair.getAtIndex(ValueLayout.JAVA_INT, 1);
        }
        void await() throws Throwable { assertEquals(1L, (long) READ.invokeExact(read, input, 1L)); }
        void release() throws Throwable { assertEquals(1L, (long) WRITE.invokeExact(write, output, 1L)); }
        static long self() throws Throwable { return (long) SELF.invokeExact(); }
        static long tls() throws Throwable { return ((MemorySegment) TLS.invokeExact()).address(); }
        @Override public void close() throws Exception {
            try { assertEquals(0, (int) CLOSE.invokeExact(read)); assertEquals(0, (int) CLOSE.invokeExact(write)); }
            catch (Throwable failure) { throw new AssertionError(failure); }
            finally { arena.close(); }
        }
    }

    @Test public void safeNativeCallReleasesOneHecBeforeThePinnedWrapperReturns() throws Throwable {
        for (var safety : new ForeignSafety[]{ForeignSafety.SAFE, ForeignSafety.INTERRUPTIBLE})
        try (var context = context(); var gate = new NativeGate()) {
            context.initialize("thc"); context.enter();
            try {
                var state = Language.currentState(); var threads = state.getThreads(); threads.setCapabilityCount(1);
                var entered = new CompletableFuture<Void>(); var guestExecutors = new AtomicInteger();
                var caller = task(state, () -> {
                    var identity = threads.currentIdentity(); var current = Thread.currentThread();
                    assertEquals(1, guestExecutors.incrementAndGet()); guestExecutors.decrementAndGet();
                    var permission = threads.enterForeign(safety);
                    try {
                        var nested = threads.enterForeign(ForeignSafety.SAFE);
                        try {
                            entered.complete(null);
                            try { gate.await(); } catch (Throwable failure) { throw new AssertionError(failure); }
                        } finally { threads.leaveForeign(nested); }
                    } finally { threads.leaveForeign(permission); }
                    assertEquals(1, guestExecutors.incrementAndGet());
                    try {
                        assertSame(current, Thread.currentThread()); assertSame(identity, threads.currentIdentity());
                        assertEquals(MaskingState.MASKED_INTERRUPTIBLE, state.getMaskingState().get());
                        return 17L;
                    } finally { guestExecutors.decrementAndGet(); }
                });
                var producer = task(state, () -> {
                    assertEquals(1, guestExecutors.incrementAndGet());
                    try {
                        try { gate.release(); } catch (Throwable failure) { throw new AssertionError(failure); }
                        // Native return races a still-executing replacement carrier.
                        long until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(50);
                        while (System.nanoTime() < until) Thread.onSpinWait();
                        return 23L;
                    } finally { guestExecutors.decrementAndGet(); }
                });
                threads.startThread(caller.thread());
                try {
                    await(CompletableFuture.anyOf(entered, caller.result()));
                    threads.startThread(producer.thread());
                    assertEquals(23L, await(producer.result())); assertEquals(17L, await(caller.result()));
                } finally { gate.release(); }
            } finally { context.leave(); }
        }
    }

    @Test public void unsafeNativeCallRetainsItsHecAndForeignFailureRestoresAdmission() throws Throwable {
        try (var context = context(); var gate = new NativeGate()) {
            context.initialize("thc"); context.enter();
            try {
                var state = Language.currentState(); var threads = state.getThreads(); threads.setCapabilityCount(1);
                var entered = new CompletableFuture<Void>();
                var caller = task(state, () -> {
                    var identity = threads.currentIdentity();
                    var permission = threads.enterForeign(ForeignSafety.UNSAFE);
                    try {
                        entered.complete(null);
                        try { gate.await(); } catch (Throwable failure) { throw new AssertionError(failure); }
                    } finally { threads.leaveForeign(permission); }
                    var marker = new RuntimeFault("foreign failure");
                    assertSame(marker, assertThrows(RuntimeFault.class, () -> {
                        var safe = threads.enterForeign(ForeignSafety.SAFE);
                        try { throw marker; } finally { threads.leaveForeign(safe); }
                    }));
                    assertSame(identity, threads.currentIdentity());
                    assertEquals(GuestThreadStatus.RUNNING, identity.getStatus()); return 1L;
                });
                var producer = task(state, () -> 2L);
                threads.startThread(caller.thread());
                try {
                    await(entered); threads.startThread(producer.thread());
                    TruffleSafepoint.setBlockedThreadInterruptible(null, ignored -> {
                        assertThrows(java.util.concurrent.TimeoutException.class, () -> producer.result().get(100, TimeUnit.MILLISECONDS));
                    }, Unit.INSTANCE);
                } finally { gate.release(); }
                assertEquals(1L, await(caller.result())); assertEquals(2L, await(producer.result()));
            } finally { context.leave(); }
        }
    }

    private MethodHandle callbackInvoker(Arena arena) throws Exception {
        var library = directory.resolve("callback.so");
        var compile = new ProcessBuilder("cc", "-shared", "-fPIC", "t/fixtures/compiler/callback-identity.c", "-o", library.toString())
            .redirectErrorStream(true).start();
        assertTrue(compile.waitFor(10, TimeUnit.SECONDS));
        assertEquals(0, compile.exitValue(), new String(compile.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
        return Linker.nativeLinker().downcallHandle(SymbolLookup.libraryLookup(library, arena).find("thc_callback_identity_invoke").orElseThrow(),
            FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));
    }

    @Test public void pinnedCallbackRetainsNativeOriginAndParksItsOwnMVarRequest() throws Throwable {
        try (var context = context(); var arena = Arena.ofShared()) {
            var invoke = callbackInvoker(arena);
            context.initialize("thc"); context.enter();
            var cell = new ManagedMVar();
            var stm = new ManagedSTM(); var signal = stm.newTVar(0L);
            try {
                var state = Language.currentState(); var threads = state.getThreads(); threads.setCapabilityCount(1);
                var entered = new CompletableFuture<Void>(); var callbackFailure = new AtomicReference<Throwable>();
                var caller = task(state, () -> {
                    var parent = threads.currentIdentity(); var javaThread = Thread.currentThread();
                    var origin = new long[2];
                    try { origin[0] = NativeGate.self(); origin[1] = NativeGate.tls(); }
                    catch (Throwable failure) { throw new AssertionError(failure); }
                    Runnable callback = () -> {
                        try {
                            threads.enterCurrent();
                            try {
                                var child = threads.currentIdentity();
                                assertNotSame(parent, child); assertSame(javaThread, Thread.currentThread());
                                assertEquals(MaskingState.UNMASKED, state.getMaskingState().get()); assertTrue(threads.isCurrentBound());
                                assertEquals(origin[0], NativeGate.self()); assertEquals(origin[1], NativeGate.tls());
                                // Construction and registration can park while the native callback pins this mount.
                                var completed = new ManagedMVar();
                                var nested = task(state, () -> { completed.put(29L, null); return 29L; });
                                threads.startThread(nested.thread());
                                assertEquals(29L, completed.take(null));
                                entered.complete(null);
                                assertEquals(37L, stm.atomically(null, () -> fail("nested STM"), () -> {
                                    long value = (long) stm.read(signal);
                                    return value == 0 ? stm.retry() : value;
                                }));
                                assertEquals(31L, cell.take(null));
                                assertSame(child, threads.currentIdentity()); assertSame(javaThread, Thread.currentThread());
                                assertEquals(origin[0], NativeGate.self()); assertEquals(origin[1], NativeGate.tls());
                            } finally { threads.leaveCurrent(); }
                        } catch (Throwable failure) { callbackFailure.set(failure); entered.completeExceptionally(failure); }
                    };
                    try {
                        var target = MethodHandles.lookup().findVirtual(Runnable.class, "run", MethodType.methodType(void.class)).bindTo(callback);
                        var stub = Linker.nativeLinker().upcallStub(target, FunctionDescriptor.ofVoid(), arena);
                        var permission = threads.enterForeign(ForeignSafety.SAFE);
                        try { invoke.invokeExact(stub); }
                        finally { threads.leaveForeign(permission); }
                        if (callbackFailure.get() != null) throw new AssertionError(callbackFailure.get());
                        assertSame(parent, threads.currentIdentity()); assertSame(javaThread, Thread.currentThread());
                        assertEquals(MaskingState.MASKED_INTERRUPTIBLE, state.getMaskingState().get());
                        assertEquals(origin[0], NativeGate.self()); assertEquals(origin[1], NativeGate.tls());
                        return 47L;
                    } catch (Throwable failure) { throw new AssertionError(failure); }
                });
                var producer = task(state, () -> {
                    stm.atomically(null, () -> fail("nested STM"), () -> { stm.write(signal, 37L); return Unit.INSTANCE; });
                    cell.put(31L, null); return 1L;
                });
                threads.startThread(caller.thread());
                try {
                    await(entered);
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                    while (stm.pendingWaiters() == 0 && System.nanoTime() < deadline) Thread.yield();
                    assertEquals(1, stm.pendingWaiters()); threads.startThread(producer.thread());
                    assertEquals(1L, await(producer.result())); assertEquals(47L, await(caller.result()));
                } finally {
                    cell.tryPut(31L);
                    if (!caller.result().isDone()) context.close(true);
                }
            } finally { context.leave(); }
        }
    }

    @Test public void foreignOriginCallbackBorrowsTheDestinationHecWithoutMovingThreads() throws Throwable {
        crossContextCallback(false);
    }
    @Test public void closingCallbackContextWaitsForItsEntryButDoesNotJoinTheForeignOrigin() throws Throwable {
        crossContextCallback(true);
    }
    @Test public void pinnedSameContextCallbackPollHandsTheHecToAQueuedSibling() throws Throwable { pollingCallback(true, true); }
    @Test public void pinnedCrossContextCallbackPollHandsTheHecToAQueuedSibling() throws Throwable { pollingCallback(false, true); }
    @Test public void platformOriginCallbackPollHandsTheHecToAQueuedSibling() throws Throwable { pollingCallback(false, false); }
    private void pollingCallback(boolean sameContext, boolean virtualOrigin) throws Throwable {
        try (var origin = virtualOrigin ? context() : Context.newBuilder("thc").allowCreateThread(true).allowNativeAccess(true).build();
             var arena = Arena.ofShared()) {
            var destination = sameContext ? origin : context();
            try {
                var invoke = callbackInvoker(arena);
                var entered = new CompletableFuture<Void>(); var produced = new java.util.concurrent.atomic.AtomicBoolean();
                var failure = new AtomicReference<Throwable>();
                destination.initialize("thc"); destination.enter();
                Language.State destinationState; Task<Long> producer;
                try {
                    destinationState = Language.currentState(); destinationState.getThreads().setCapabilityCount(1);
                    producer = task(destinationState, () -> { produced.set(true); return 1L; });
                } finally { destination.leave(); }
                origin.initialize("thc"); origin.enter();
                try {
                    var source = Language.currentState(); source.getThreads().setCapabilityCount(1);
                    var caller = task(source, () -> {
                        var javaThread = Thread.currentThread(); var sourceIdentity = source.getThreads().currentIdentity();
                        try {
                            long nativeThread = NativeGate.self(), nativeTls = NativeGate.tls();
                            Runnable callback = () -> {
                                destination.enter();
                                try {
                                    var threads = destinationState.getThreads(); threads.enterCurrent();
                                    try {
                                        var identity = threads.currentIdentity(); entered.complete(null);
                                        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
                                        while (!produced.get() && System.nanoTime() < deadline) assertNull(GuestThreads.pollCurrent(null, false));
                                        assertTrue(produced.get(), "native callback polling must admit its queued sole-HEC sibling");
                                        assertSame(identity, threads.currentIdentity()); assertSame(javaThread, Thread.currentThread());
                                        assertEquals(nativeThread, NativeGate.self()); assertEquals(nativeTls, NativeGate.tls());
                                        assertEquals(MaskingState.UNMASKED, destinationState.getMaskingState().get());
                                    } finally { threads.leaveCurrent(); }
                                } catch (Throwable problem) { failure.set(problem); entered.completeExceptionally(problem); }
                                finally { destination.leave(); }
                            };
                            var target = MethodHandles.lookup().findVirtual(Runnable.class, "run", MethodType.methodType(void.class)).bindTo(callback);
                            var stub = Linker.nativeLinker().upcallStub(target, FunctionDescriptor.ofVoid(), arena);
                            var permission = source.getThreads().enterForeign(ForeignSafety.SAFE);
                            try { invoke.invokeExact(stub); } finally { source.getThreads().leaveForeign(permission); }
                            if (failure.get() != null) throw new AssertionError(failure.get());
                            assertSame(javaThread, Thread.currentThread()); assertSame(sourceIdentity, source.getThreads().currentIdentity());
                            assertEquals(nativeThread, NativeGate.self()); assertEquals(nativeTls, NativeGate.tls()); return 1L;
                        } catch (Throwable problem) { throw new AssertionError(problem); }
                    });
                    source.getThreads().startThread(caller.thread()); await(entered);
                    destinationState.getThreads().startThread(producer.thread());
                    assertEquals(1L, await(caller.result())); assertEquals(1L, await(producer.result()));
                } finally { origin.leave(); }
            } finally { if (!sameContext) destination.close(true); }
        }
    }

    @Test public void parkedPlatformCallbackRejoinsTheSurvivingHecAfterShrink() throws Throwable {
        try (var origin = Context.newBuilder("thc").allowCreateThread(true).allowNativeAccess(true).build();
             var destination = context(); var arena = Arena.ofShared()) {
            var invoke = callbackInvoker(arena); var cell = new ManagedMVar();
            var entered = new CompletableFuture<Void>(); var occupied = new CompletableFuture<Void>();
            var resumed = new CompletableFuture<Long>(); var failure = new AtomicReference<Throwable>();
            var release = new java.util.concurrent.atomic.AtomicBoolean(); var active = new AtomicInteger(); var maximum = new AtomicInteger();
            destination.initialize("thc"); destination.enter();
            Language.State destinationState; Task<Long> blocker;
            try {
                destinationState = Language.currentState(); var threads = destinationState.getThreads(); threads.setCapabilityCount(2);
                // Consume round-robin HEC 0; the external callback will enter HEC 1.
                threads.hostEntry(null, () -> { threads.enterCurrent(); try { return 0L; } finally { threads.leaveCurrent(); } });
                blocker = task(destinationState, () -> {
                    maximum.accumulateAndGet(active.incrementAndGet(), Math::max); occupied.complete(null);
                    try {
                        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                        while (!release.get() && System.nanoTime() < deadline) Thread.onSpinWait();
                        assertTrue(release.get()); return 1L;
                    } finally { active.decrementAndGet(); }
                });
            } finally { destination.leave(); }
            origin.initialize("thc"); origin.enter();
            try {
                var source = Language.currentState();
                var caller = task(source, () -> {
                    var javaThread = Thread.currentThread();
                    try {
                        long nativeThread = NativeGate.self(), nativeTls = NativeGate.tls();
                        Runnable callback = () -> {
                            destination.enter();
                            try {
                                var threads = destinationState.getThreads(); threads.enterCurrent();
                                try {
                                    var identity = threads.currentIdentity(); assertEquals(1L, identity.getCapability()); entered.complete(null);
                                    assertEquals(71L, cell.take(null));
                                    maximum.accumulateAndGet(active.incrementAndGet(), Math::max);
                                    try {
                                        assertSame(identity, threads.currentIdentity()); assertSame(javaThread, Thread.currentThread());
                                        assertEquals(nativeThread, NativeGate.self()); assertEquals(nativeTls, NativeGate.tls());
                                        assertEquals(MaskingState.UNMASKED, destinationState.getMaskingState().get());
                                        resumed.complete(identity.getCapability());
                                    } finally { active.decrementAndGet(); }
                                } finally { threads.leaveCurrent(); }
                            } catch (Throwable problem) { failure.set(problem); entered.completeExceptionally(problem); resumed.completeExceptionally(problem); }
                            finally { destination.leave(); }
                        };
                        var target = MethodHandles.lookup().findVirtual(Runnable.class, "run", MethodType.methodType(void.class)).bindTo(callback);
                        var stub = Linker.nativeLinker().upcallStub(target, FunctionDescriptor.ofVoid(), arena);
                        var permission = source.getThreads().enterForeign(ForeignSafety.SAFE);
                        try { invoke.invokeExact(stub); } finally { source.getThreads().leaveForeign(permission); }
                        if (failure.get() != null) throw new AssertionError(failure.get());
                        assertSame(javaThread, Thread.currentThread()); assertEquals(nativeThread, NativeGate.self()); assertEquals(nativeTls, NativeGate.tls()); return 1L;
                    } catch (Throwable problem) { throw new AssertionError(problem); }
                });
                source.getThreads().startThread(caller.thread());
                try {
                    await(entered);
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                    while (cell.pendingCounts().takers() == 0 && System.nanoTime() < deadline) Thread.yield();
                    assertEquals(1, cell.pendingCounts().takers());
                    destinationState.getThreads().startThread(blocker.thread()); await(occupied);
                    destinationState.getThreads().setCapabilityCount(1); assertTrue(cell.tryPut(71L));
                    assertThrows(java.util.concurrent.TimeoutException.class, () -> resumed.get(100, TimeUnit.MILLISECONDS),
                        "callback must wait for the surviving HEC, not execute on retired HEC 1");
                } finally { release.set(true); cell.tryPut(71L); }
                assertEquals(1L, await(blocker.result())); assertEquals(0L, await(resumed)); assertEquals(1L, await(caller.result()));
                assertEquals(1, maximum.get());
            } finally { origin.leave(); }
        }
    }
    private void crossContextCallback(boolean cancel) throws Throwable {
        for (boolean virtualOrigin : new boolean[]{false, true})
        try (var origin = virtualOrigin ? context() : Context.newBuilder("thc").allowCreateThread(true).allowNativeAccess(true).build();
             var arena = Arena.ofShared()) {
            var destination = context();
            try {
            var invoke = callbackInvoker(arena);
            var cell = new ManagedMVar(); var entered = new CompletableFuture<Void>(); var failure = new AtomicReference<Throwable>();
            var originRelease = new java.util.concurrent.CountDownLatch(1);
            destination.initialize("thc"); destination.enter();
            Language.State destinationState; Task<Long> producer;
            try {
                destinationState = Language.currentState(); destinationState.getThreads().setCapabilityCount(1);
                producer = task(destinationState, () -> { cell.put(53L, null); return 1L; });
            } finally { destination.leave(); }
            origin.initialize("thc"); origin.enter();
            try {
                var source = Language.currentState(); source.getThreads().setCapabilityCount(1);
                var caller = task(source, () -> {
                    var sourceIdentity = source.getThreads().currentIdentity(); var javaThread = Thread.currentThread();
                    Runnable callback = () -> {
                        destination.enter();
                        try {
                            var threads = destinationState.getThreads(); threads.enterCurrent();
                            try {
                                assertSame(javaThread, Thread.currentThread()); assertTrue(threads.isCurrentBound());
                                assertEquals(MaskingState.UNMASKED, destinationState.getMaskingState().get());
                                entered.complete(null); assertEquals(53L, cell.take(null));
                            } finally { threads.leaveCurrent(); }
                        } catch (Throwable problem) { failure.set(problem); entered.completeExceptionally(problem); }
                        finally { destination.leave(); }
                    };
                    try {
                        var target = MethodHandles.lookup().findVirtual(Runnable.class, "run", MethodType.methodType(void.class)).bindTo(callback);
                        var stub = Linker.nativeLinker().upcallStub(target, FunctionDescriptor.ofVoid(), arena);
                        var permission = source.getThreads().enterForeign(ForeignSafety.SAFE);
                        try {
                            invoke.invokeExact(stub);
                            if (cancel) {
                                assertNotNull(failure.get());
                                TruffleSafepoint.setBlockedThreadInterruptible(null, waiting -> assertTrue(waiting.await(5, TimeUnit.SECONDS)), originRelease);
                            } else if (failure.get() != null) throw new AssertionError(failure.get());
                        } finally { source.getThreads().leaveForeign(permission); }
                        assertSame(sourceIdentity, source.getThreads().currentIdentity()); assertSame(javaThread, Thread.currentThread());
                        assertSame(source, Language.currentState()); return 61L;
                    } catch (Throwable problem) { throw new AssertionError(problem); }
                });
                source.getThreads().startThread(caller.thread());
                try {
                    await(entered);
                    if (cancel) {
                        destination.close(true);
                        assertFalse(caller.result().isDone(), "closing a callback context must not join its external origin");
                        originRelease.countDown();
                    } else {
                        destinationState.getThreads().startThread(producer.thread());
                        assertEquals(1L, await(producer.result()));
                    }
                    assertEquals(61L, await(caller.result()));
                } finally { cell.tryPut(53L); originRelease.countDown(); }
            } finally { origin.leave(); }
            } finally { destination.close(true); }
        }
    }
}
