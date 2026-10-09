// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.TruffleSafepoint;
import com.oracle.truffle.api.frame.VirtualFrame;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import thc.Language;
import static java.lang.foreign.ValueLayout.*;
import static thc.runtime.RuntimeFault.fault;

/** The pinned nonthreaded StablePtr console protocol. Native callbacks only
 * queue identities; existing GuestThreads execute the original CInt -> IO ()
 * closure. Only explicit launcher authority may install a process handler. */
public final class WindowsConsoleEvents implements AutoCloseable {
    private final Language.State owner;
    private final boolean authorized;
    private final CarrierLocal<Event> dispatching = new CarrierLocal<>(null);
    private final Map<Long, Handler> handlers = new LinkedHashMap<>();
    private final Map<Long, Event> events = new LinkedHashMap<>();
    private final Map<Read, Long> reads = new LinkedHashMap<>();
    private ExecutableProgram program;
    private Language language;
    private MemorySegment nativeOwner;
    private Thread reader;
    private Handler installed;
    private long generation;
    private int action = -1;
    private volatile boolean stopping;
    private Throwable failure;
    private static final class Handler {
        final ManagedAddress pointer;
        final Object closure;
        int executing;
        Handler(ManagedAddress pointer, Object closure) { this.pointer = pointer; this.closure = closure; }
    }
    private static final class Event {
        final long sequence;
        final int code;
        final Handler handler;
        boolean done;
        Event(long sequence, int code, Handler handler) { this.sequence = sequence; this.code = code; this.handler = handler; }
    }
    WindowsConsoleEvents(Language.State owner, boolean authorized) { this.owner = owner; this.authorized = authorized; }
    private void current() {
        if (Language.currentState(null) != owner) throw fault("Console events belong to another context");
        if (!owner.getEnv().isNativeAccessAllowed()) throw new SecurityException("Console events require native access");
    }
    public synchronized void bind(ExecutableProgram program) {
        current();
        if (stopping || this.program != null) throw fault("Console dispatcher already bound or stopping");
        if (!program.getCapturesContinuations()) throw fault("Console delivery requires continuation capture");
        this.program = program;
        language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
    }
    /** Swap only this context's installation. The original Haskell wrapper
     * dereferences/frees the returned old StablePtr; pending events retain the
     * old referent independently until their actual dispatch completes. */
    @TruffleBoundary public int install(int nextAction, ManagedAddress cell) {
        current();
        if (!authorized) throw new SecurityException("Console installation requires NativeIO launcher authority");
        if (nextAction != -1 && nextAction != -2 && nextAction != -4) throw fault("Invalid GHC console action");
        Handler next = null;
        synchronized (this) {
            if (stopping) throw fault("Console service is stopping");
            if (nextAction == -4 || installed != null) cell.requireAddressCell();
            if (nextAction == -4) {
                if (program == null) throw fault("Console installation requires the original executable program");
                var pointer = cell.readAddressElementIndex(0);
                next = new Handler(pointer, owner.getStablePointers().dereference(pointer));
                // Validate original boxing and continuation contract before the
                // native installation changes process-visible behavior.
                new DispatchRoot(language, program);
            }
            if (nativeOwner == null) open();
            long id = next == null ? 0 : ++generation;
            try {
                int error = (int) WindowsNativeIo.Api.consoleInstall.invokeExact(nativeOwner, nextAction, id);
                if (error != 0) throw fault("Console installation failed: " + Integer.toUnsignedLong(error));
            } catch (Throwable caught) { throw propagate(caught); }
            int previous = action;
            if (installed != null) cell.writeAddressElementIndex(0, installed.pointer);
            installed = next; action = nextAction;
            if (next != null) handlers.put(id, next);
            prune();
            return previous;
        }
    }
    private void open() {
        owner.admitGuestConcurrency();
        try (var arena = Arena.ofConfined()) {
            var error = arena.allocate(JAVA_INT);
            nativeOwner = (MemorySegment) WindowsNativeIo.Api.consoleOpen.invokeExact(error);
            if (nativeOwner.address() == 0) {
                nativeOwner = null;
                throw fault("Console ownership unavailable: " + Integer.toUnsignedLong(error.get(JAVA_INT, 0)));
            }
            reader = owner.getEnv().newTruffleThreadBuilder(this::consume).virtual(false).build();
            try { reader.start(); }
            catch (Throwable caught) {
                int stopped = (int) WindowsNativeIo.Api.consoleStop.invokeExact(nativeOwner);
                WindowsNativeIo.Api.consoleClose.invokeExact(nativeOwner);
                nativeOwner = null; reader = null;
                if (stopped != 0) caught.addSuppressed(fault("Console restoration failed: " + stopped));
                throw caught;
            }
        } catch (Throwable caught) { throw propagate(caught); }
    }
    private void consume() {
        try (var arena = Arena.ofConfined()) {
            var image = arena.allocate(24, 8);
            while (!stopping) {
                int error = (int) WindowsNativeIo.Api.consoleWait.invokeExact(nativeOwner);
                if (error != 0) throw fault("Console wait failed: " + Integer.toUnsignedLong(error));
                Event event;
                synchronized (this) {
                    if (stopping) break;
                    int taken = (int) WindowsNativeIo.Api.consoleTake.invokeExact(nativeOwner, image);
                    if (taken < 0) throw fault("Console queue failed: " + Integer.toUnsignedLong(-taken));
                    if (taken == 0) continue;
                    long id = image.get(JAVA_LONG, 8);
                    var handler = id == 0 ? null : handlers.get(id);
                    if (id != 0 && handler == null) throw fault("Console event lost its installed handler");
                    event = new Event(image.get(JAVA_LONG, 0), image.get(JAVA_INT, 16), handler);
                    event.done = handler == null;
                    events.put(event.sequence, event);
                    if (handler != null) handler.executing++;
                    notifyAll();
                }
                if (event.handler != null) {
                    var root = new DispatchRoot(language, program).getCallTarget();
                    var child = owner.getThreads().newThread(owner.getEnv(), () -> dispatch(event, root), null, null);
                    synchronized (this) {
                        if (!stopping) owner.getThreads().startThread(child);
                        else { event.handler.executing--; prune(); }
                    }
                } else synchronized (this) { prune(); }
            }
        } catch (Throwable caught) {
            synchronized (this) { failure = caught; notifyAll(); }
            if (!stopping || caught instanceof ThreadDeath) GuestThreadOps.reportHostFailure(owner, caught);
            if (caught instanceof ThreadDeath death) throw death;
        }
    }
    private void dispatch(Event event, com.oracle.truffle.api.RootCallTarget root) {
        boolean registered = false;
        var outcome = GuestThreadStatus.FINISHED;
        try {
            owner.getThreads().enterCurrent(MaskingState.UNMASKED, true, true, null);
            registered = true; dispatching.set(event);
            root.call(event.handler.closure, (long) event.code);
        } catch (Throwable caught) {
            outcome = GuestThreadStatus.uncaught(caught);
            GuestThreadOps.reportHostFailure(owner, caught);
            if (caught instanceof ThreadDeath death) throw death;
        } finally {
            dispatching.remove();
            synchronized (this) {
                event.handler.executing--; prune(); notifyAll();
                // A thrown handler is not Done. Like the pinned nonthreaded
                // protocol, its aborted readers remain waiting until shutdown.
            }
            if (registered) owner.getThreads().leaveCurrent(outcome);
        }
    }
    @TruffleBoundary public synchronized void done(int code) {
        current();
        var event = dispatching.get();
        // A returned old Catch handler may be called directly by Haskell.
        // Its Done must not acknowledge a different in-flight OS event.
        if (event == null) return;
        if (event.code != code) throw fault("Console completion has the wrong event identity");
        event.done = true; notifyAll(); prune();
    }
    private void prune() {
        try {
            if (nativeOwner != null) for (var iterator = handlers.entrySet().iterator(); iterator.hasNext();) {
                var entry = iterator.next();
                if (entry.getValue() != installed && entry.getValue().executing == 0 &&
                        (long) WindowsNativeIo.Api.consolePending.invokeExact(nativeOwner, (long) entry.getKey()) == 0) iterator.remove();
            }
            long oldest = reads.values().stream().mapToLong(Long::longValue).min().orElse(Long.MAX_VALUE);
            // No active read can observe an event at/before its starting
            // sequence. A handler that omitted Done must not retain historical
            // roots after the last affected read retires.
            events.entrySet().removeIf(entry -> entry.getKey() <= oldest);
        } catch (Throwable caught) { throw propagate(caught); }
    }
    final class Read implements AutoCloseable {
        private final boolean consoleRead;
        Read(boolean consoleRead) { this.consoleRead = consoleRead; }
        long sequence() {
            synchronized (WindowsConsoleEvents.this) {
                try {
                    long sequence = nativeOwner == null ? 0 : (long) WindowsNativeIo.Api.consoleSequence.invokeExact(nativeOwner);
                    if (consoleRead) reads.put(this, sequence);
                    return sequence;
                } catch (Throwable caught) { throw propagate(caught); }
            }
        }
        boolean awaitCompletion(long before, BooleanSupplier abandoned, BooleanSupplier suspended) throws InterruptedException {
            synchronized (WindowsConsoleEvents.this) {
                if (!consoleRead || nativeOwner == null) throw fault("Aborted console read has no owned console completion service");
                for (;;) {
                    if (stopping || abandoned.getAsBoolean()) return false;
                    if (failure != null) throw propagate(failure);
                    Event relevant = null;
                    for (var event : events.values()) if (event.sequence > before && (event.code == 0 || event.code == 1)) { relevant = event; break; }
                    if (relevant != null && relevant.done && !suspended.getAsBoolean()) return true;
                    WindowsConsoleEvents.this.wait();
                }
            }
        }
        @Override public void close() { synchronized (WindowsConsoleEvents.this) { reads.remove(this); prune(); } }
    }
    Read observeRead(boolean consoleRead) { return new Read(consoleRead); }
    synchronized void wakeCompletion() { notifyAll(); }
    public synchronized void requestStop() {
        if (stopping) return;
        stopping = true;
        if (nativeOwner != null) try {
            int error = (int) WindowsNativeIo.Api.consoleStop.invokeExact(nativeOwner);
            if (error != 0) failure = fault("Console restoration failed: " + Integer.toUnsignedLong(error));
        } catch (Throwable caught) { failure = caught; }
        notifyAll();
    }
    @Override public void close() {
        requestStop();
        Thread child;
        synchronized (this) { child = reader; }
        if (child != null && child != Thread.currentThread()) try (var admission = LoomScheduler.suspendCurrentGuest()) {
            TruffleSafepoint.setBlockedThreadInterruptibleFunction(null,
                (TruffleSafepoint.InterruptibleFunction<Thread, Object>) thread -> { thread.join(); return Unit.INSTANCE; }, child);
        }
        synchronized (this) {
            if (nativeOwner != null) try { WindowsNativeIo.Api.consoleClose.invokeExact(nativeOwner); }
            catch (Throwable caught) { throw propagate(caught); }
            finally { nativeOwner = null; reader = null; handlers.clear(); events.clear(); installed = null; program = null; }
            if (failure != null) throw propagate(failure);
        }
    }
    /** Exact original CInt boxing, with the existing continuation-capable IO
     * destination. Every dispatched guest owns its own Truffle children. */
    private static final class DispatchRoot extends ContextRoot {
        private final DataLayout number;
        private final TupleShape shape;
        @Child private Force force = new Force(new Metrics(false), true);
        @Child private TupleDispatch dispatch;
        DispatchRoot(Language language, ExecutableProgram program) {
            super(language, new FrameLayout().build());
            number = program.constructorLayout("ghc-internal:GHC.Internal.Int.I32#");
            if (number.getArity() != 1 || !number.hasFieldRepresentation(0, "Int32Rep")) throw fault("Console dispatcher requires the original CInt layout");
            var proof = new CoreRepresentation(CoreKind.UNKNOWN, false, true, List.of("BoxedRep (Just Lifted)"), List.of(
                new CoreRepresentation(CoreKind.VOID, true, true, List.of(), null, null, null, null, null),
                new CoreRepresentation(CoreKind.DATA, false, true, List.of("BoxedRep (Just Lifted)"), null, null, null, null, null)), null, null, null, null);
            shape = new TupleShape(proof, language);
            dispatch = new TupleDispatch(new IoResultDestination(shape, language), new Metrics(false), 2, false);
        }
        @Override public Object execute(VirtualFrame frame) {
            frame.setLong(FrameLayout.BLOOM_FILTER, 0L);
            var closure = Applications.requireClosure(force.execute(frame, frame.getArguments()[0]));
            GuestThreadOps.actionResult(closure, true);
            var root = (GuestRoot) closure.target.getRootNode();
            if (!root.getTupleResult().matches(shape)) throw fault("Console handler requires the original IO unit tuple");
            dispatch.execute(frame, closure, new Object[]{number.createInt(((Long) frame.getArguments()[1]).intValue()), Unit.INSTANCE});
            return Unit.INSTANCE;
        }
        @Override public String getName() { return "THC original Windows console handler"; }
    }
    @SuppressWarnings("unchecked") private static <E extends Throwable> RuntimeException propagate(Throwable failure) throws E { throw (E) failure; }
}
