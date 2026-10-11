// SPDX-FileCopyrightText: 2026 Edward Kmett <ekmett@gmail.com>
// SPDX-License-Identifier: BSD-2-Clause OR Apache-2.0

import java.util.concurrent.atomic.AtomicReference;
import jam.vm.Weak;
import org.graalvm.nativeimage.CurrentIsolate;
import org.graalvm.nativeimage.ImageInfo;
import org.graalvm.nativeimage.Isolate;
import org.graalvm.nativeimage.IsolateThread;
import org.graalvm.nativeimage.Isolates;
import org.graalvm.nativeimage.c.function.CEntryPoint;
import org.graalvm.nativeimage.c.function.CEntryPointLiteral;
import org.graalvm.nativeimage.c.function.CFunctionPointer;
import org.graalvm.nativeimage.c.function.InvokeCFunctionPointer;

/** Thread-affine isolate entry, nested callbacks, attachment and heap lifetime. */
public final class IsolateSmoke {
    private static final int ORIGINAL = 11;
    private static final int SECOND = 22;
    private static final int WORKER = 33;
    private static State state;
    private static int finalized;
    private static volatile Object sink;

    private static final class Node {
        final int marker;
        final byte[] payload = new byte[1024];
        Node next;

        Node(int marker) {
            this.marker = marker;
            payload[1023] = (byte) marker;
        }
    }

    private static final class Value {
        final Node key;
        Value(Node key) { this.key = key; }
    }

    private static final class Finalizer implements Runnable {
        final Value value;
        Finalizer(Value value) { this.value = value; }

        @Override public void run() {
            verifyGraph(value.key, state.marker);
            collect();
            verifyGraph(value.key, state.marker);
            check(++finalized == 1, "isolate finalizer runs once");
        }
    }

    private static final class State {
        final int marker;
        final long token;
        Node key;

        State(int marker) {
            this.marker = marker;
            key = new Node(marker);
            key.next = new Node(marker + 1);
            key.next.next = key;
            Value value = new Value(key);
            token = Weak.create(key, value, new Finalizer(value));
        }
    }

    private interface ProbeFunction extends CFunctionPointer {
        @InvokeCFunctionPointer
        long invoke(IsolateThread thread, IsolateThread callback, int marker, int callbackMarker);
    }

    private interface RetireFunction extends CFunctionPointer {
        @InvokeCFunctionPointer
        long invoke(IsolateThread thread, int marker);
    }

    // Only these literals need build-time initialization; all guest state is per isolate.
    private static final class EntryPoints {
        static final CEntryPointLiteral<ProbeFunction> PROBE = CEntryPointLiteral.create(IsolateSmoke.class, "probe",
                IsolateThread.class, IsolateThread.class, int.class, int.class);
        static final CEntryPointLiteral<RetireFunction> RETIRE = CEntryPointLiteral.create(IsolateSmoke.class, "retire",
                IsolateThread.class, int.class);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static long marker(int value) { return 0x4a414d0000000000L | value; }

    private static void initialize(int marker) {
        Weak.checkAvailable();
        check(state == null && finalized == 0, "new isolate has independent Java state");
        state = new State(marker);
    }

    private static void verifyGraph(Node node, int marker) {
        check(node != null && node.marker == marker && node.payload[1023] == (byte) marker, "isolate graph payload survives");
        check(node.next != null && node.next.marker == marker + 1 && node.next.next == node, "isolate graph cycle survives");
    }

    private static void verifyLive(int marker) {
        check(state != null && state.marker == marker, "correct isolate state is active");
        verifyGraph(state.key, marker);
        Value value = (Value) Weak.deref(state.token);
        check(value != null && value.key == state.key, "weak association belongs to the current isolate");
        check(finalized == 0 && Weak.pump() == 0, "live isolate key retains its finalizer");
    }

    private static void collect() {
        for (int i = 0; i < 2048; i++) sink = new byte[1024 + (i & 127)];
        System.gc();
    }

    @CEntryPoint(include = CEntryPoint.NotIncludedAutomatically.class, publishAs = CEntryPoint.Publish.NotPublished)
    private static long probe(@CEntryPoint.IsolateThreadContext IsolateThread thread,
                              IsolateThread callback, int marker, int callbackMarker) {
        check(CurrentIsolate.getCurrentThread().equal(thread), "entry selects the current OS thread's isolate context");
        if (state == null) initialize(marker);
        collect();
        verifyLive(marker);
        if (callbackMarker != 0) {
            // Only thread handles and numeric markers cross the C boundary, never Java objects.
            check(EntryPoints.PROBE.getFunctionPointer().invoke(callback, thread, callbackMarker, 0) == marker(callbackMarker),
                    "nested callback entered the original isolate");
            collect();
            verifyLive(marker);
            check(CurrentIsolate.getCurrentThread().equal(thread), "nested callback restores its caller's context");
        }
        return marker(marker);
    }

    @CEntryPoint(include = CEntryPoint.NotIncludedAutomatically.class, publishAs = CEntryPoint.Publish.NotPublished)
    private static long retire(IsolateThread thread, int marker) {
        check(CurrentIsolate.getCurrentThread().equal(thread), "retirement enters the intended isolate");
        check(state.marker == marker, "retirement uses local state");
        state.key = null;
        for (int i = 0; i < 4 && finalized == 0; i++) {
            collect();
            Weak.pump();
        }
        check(finalized == 1 && Weak.deref(state.token) == null, "dead isolate key finalizes despite return edges");
        check(Weak.pump() == 0, "isolate finalizer claim is completed");
        return marker(marker) | 0x8000;
    }

    private static IsolateThread createIsolate() {
        return Isolates.createIsolate(new Isolates.CreateIsolateParameters.Builder()
                .appendArgument("-Xmx128m").appendArgument("-Xmn32m").build());
    }

    private static void rejectOversizedNursery() {
        try {
            // Usable cells also need their guard and copy reserve inside the 16 GiB domain.
            Isolates.createIsolate(new Isolates.CreateIsolateParameters.Builder()
                    .appendArgument("-Xmx16640m").appendArgument("-Xmn16384m").build());
            throw new AssertionError("An oversized nursery must fail before constructing Jam");
        } catch (Isolates.IsolateException expected) {
            collect();
            verifyLive(ORIGINAL);
        }
    }

    private static void visit(IsolateThread thread, IsolateThread callback, int marker, int callbackMarker) {
        check(EntryPoints.PROBE.getFunctionPointer().invoke(thread, callback, marker, callbackMarker) == marker(marker),
                "entry returns its isolate's numeric marker");
    }

    private static void finish(IsolateThread thread, int marker) {
        check(EntryPoints.RETIRE.getFunctionPointer().invoke(thread, marker) == (marker(marker) | 0x8000),
                "retirement returns its isolate's numeric marker");
    }

    private static void worker() {
        // An IsolateThread belongs to one OS thread: acquire the worker's own A handle here.
        IsolateThread original = CurrentIsolate.getCurrentThread();
        IsolateThread isolated = createIsolate();
        try {
            visit(isolated, original, WORKER, ORIGINAL);
            visit(original, original, ORIGINAL, 0);
            visit(isolated, original, WORKER, ORIGINAL);
            finish(isolated, WORKER);
        } finally {
            Isolates.tearDownIsolate(isolated);
        }
        collect();
        verifyLive(ORIGINAL);
        check(CurrentIsolate.getCurrentThread().equal(original), "worker restores A after tearing down C");
    }

    public static void main(String[] args) throws Exception {
        check(ImageInfo.inImageRuntimeCode(), "run this probe as a native executable");
        IsolateThread original = CurrentIsolate.getCurrentThread();
        Isolate originalIsolate = CurrentIsolate.getIsolate();
        initialize(ORIGINAL);
        rejectOversizedNursery();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            try { worker(); }
            catch (Throwable problem) { failure.set(problem); }
        }, "jam-isolate-worker");
        worker.setDaemon(true);
        worker.start();

        IsolateThread second = createIsolate();
        Isolate secondIsolate = Isolates.getIsolate(second);
        boolean attached = true;
        try {
            check(!secondIsolate.equal(originalIsolate), "created isolate has a distinct heap");
            check(Isolates.attachCurrentThread(secondIsolate).equal(second), "repeated attachment reuses the thread descriptor");
            visit(second, original, SECOND, ORIGINAL);
            visit(original, original, ORIGINAL, 0);
            visit(second, original, SECOND, ORIGINAL);

            Isolates.detachThread(second);
            attached = false;
            check(Isolates.getCurrentThread(secondIsolate).isNull(), "explicit detach removes B's thread descriptor");
            visit(original, original, ORIGINAL, 0);
            second = Isolates.attachCurrentThread(secondIsolate);
            attached = true;
            visit(second, original, SECOND, ORIGINAL);
            finish(second, SECOND);
        } finally {
            if (!attached) second = Isolates.attachCurrentThread(secondIsolate);
            Isolates.tearDownIsolate(second);
        }

        collect();
        verifyLive(ORIGINAL);
        check(CurrentIsolate.getCurrentThread().equal(original), "main restores A after tearing down B");
        worker.join(60_000);
        check(!worker.isAlive(), "independent isolate worker finishes");
        if (failure.get() != null) throw new AssertionError("Independent isolate worker failed", failure.get());
        finish(original, ORIGINAL);
        System.out.println("Jam Native Image isolate lifecycle passed");
    }
}
