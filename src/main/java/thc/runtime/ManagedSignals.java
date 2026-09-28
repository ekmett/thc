// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.TruffleSafepoint;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.nodes.Node;
import com.sun.management.HotSpotDiagnosticMXBean;
import java.lang.management.ManagementFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import thc.Language;
import static thc.runtime.RuntimeFault.fault;

/** One explicit CLI context owns supported GHC handlers. Native events contain
 * real siginfo bytes; GHC chooses the current Haskell handler. The native slot
 * is never reused in this JVM, preventing late old handlers targeting a new context. */
public final class ManagedSignals {
    private final Language.State owner;
    private final Language language;
    private final boolean reducedVmSignals;
    private final BooleanSupplier userSignalAvailable;
    private final Supplier<ProcessSignalTransport> factory;
    private boolean authorized;
    private ExecutableProgram program;
    private SignalDispatchRoot binding;
    private ProcessSignalTransport transport;
    private Thread worker;
    private volatile boolean stopping;
    private boolean closed;
    public ManagedSignals(Language.State owner, Language language) {
        this(owner, language, hasReducedVmSignals(), NativeSignalTransport::userSignalAvailable, NativeSignalTransport::new);
    }
    public ManagedSignals(Language.State owner, Language language, boolean reducedVmSignals) {
        this(owner, language, reducedVmSignals, NativeSignalTransport::userSignalAvailable, NativeSignalTransport::new);
    }
    public ManagedSignals(Language.State owner, Language language, boolean reducedVmSignals, BooleanSupplier userSignalAvailable) {
        this(owner, language, reducedVmSignals, userSignalAvailable, NativeSignalTransport::new);
    }
    public ManagedSignals(Language.State owner, Language language, boolean reducedVmSignals, BooleanSupplier userSignalAvailable,
                          Supplier<ProcessSignalTransport> factory) {
        this.owner = owner; this.language = language; this.reducedVmSignals = reducedVmSignals;
        this.userSignalAvailable = userSignalAvailable; this.factory = factory;
    }
    public void authorizeLauncher() { authorized = true; }
    public synchronized void bind(ExecutableProgram program) {
        current();
        if (closed || this.program != null) throw fault("Process signal dispatcher already bound");
        if (!program.getAsynchronousExceptions()) throw fault("Process signal delivery requires asyncExceptions=true");
        // Resolve action/layouts only at the first actual installation.
        this.program = program;
    }
    private void current() { if (Language.currentState(null) != owner) throw fault("Process signals belong to another context"); }
    @TruffleBoundary(transferToInterpreterOnException = false) public synchronized long install(long signal, long action, ManagedAddress mask) {
        current();
        if (owner.getThreads().isLoom()) throw new UnsupportedCore("Loom hosting does not yet support the native process signal dispatcher; use platform hosting");
        if (!authorized) throw fault("Process signals require explicit NativeIO launcher authority");
        if ((signal != 1 && signal != 2 && signal != 3 && signal != 10 && signal != 12 && signal != 15 && signal != 24 && signal != 25) ||
            (action != -1 && action != -2 && action != -4 && action != -5) || mask != ManagedAddress.nullAddress())
            throw fault("stg_sig_install supports only HUP/INT/QUIT/USR1/USR2/TERM/XCPU/XFSZ, DFL/IGN/HAN/RST and a null mask");
        if (signal != 2 && !reducedVmSignals) throw fault("GHC process signal handlers require the standalone JVM launcher with -Xrs");
        if (signal == 12 && !userSignalAvailable.getAsBoolean()) throw fault("SIGUSR2 requires the standalone JVM launcher with _JAVA_SR_SIGNUM=64 and verified native dispositions");
        if (closed || stopping) throw fault("Process signal service is closed");
        var root = binding;
        if (root == null) {
            if (program == null) throw fault("Missing original signal dispatcher");
            root = new SignalDispatchRoot(language, program); binding = root;
        }
        var nativeTransport = transport;
        if (nativeTransport == null) {
            var acquired = factory.get(); transport = acquired;
            var selectedRoot = root;
            var child = owner.getEnv().newTruffleThreadBuilder(() -> consume(acquired, selectedRoot)).build();
            worker = child;
            try { child.start(); }
            catch (Throwable failure) { transport = null; worker = null; closed = true; acquired.close(); throw propagate(failure); }
            nativeTransport = acquired;
        }
        var result = nativeTransport.install((int) signal, (int) action);
        if (result.action() == -3) owner.getStdio().nativeError(result.errno());
        return result.action();
    }
    private void consume(ProcessSignalTransport nativeTransport, SignalDispatchRoot root) {
        boolean registered = false;
        Throwable failure = null;
        var outcome = GuestThreadStatus.FINISHED;
        var interrupted = new AtomicBoolean();
        var interrupter = new TruffleSafepoint.Interrupter() {
            @Override public void interrupt(Thread thread) { interrupted.set(true); nativeTransport.wake(); }
            @Override public void resetInterrupted() { nativeTransport.resetWake(); interrupted.set(false); }
        };
        // One reader-owned cell retains a taken event across safepoint interruption.
        var reader = new TruffleSafepoint.InterruptibleFunction<Object, ProcessSignalTransport.Event>() {
            ProcessSignalTransport.Event pending;
            @Override public ProcessSignalTransport.Event apply(Object ignored) throws InterruptedException {
                if (interrupted.get()) throw new InterruptedException();
                if (stopping) return null;
                if (pending == null) pending = nativeTransport.take();
                var event = pending;
                if (interrupted.get()) throw new InterruptedException();
                return event;
            }
        };
        try {
            owner.getThreads().enterCurrent(MaskingState.UNMASKED, true);
            registered = true;
            while (!stopping) {
                var event = TruffleSafepoint.getCurrent().setBlockedFunction(null, interrupter, reader, thc.runtime.Unit.INSTANCE, null, null);
                if (event != null && !stopping) {
                    reader.pending = null;
                    var image = event.info();
                    var address = owner.getNativeAllocations().malloc(image.length);
                    if (address == ManagedAddress.nullAddress()) throw fault("Cannot allocate signal information");
                    for (int i = 0; i < image.length; i++) address.writeWord8(i, image[i] & 255L);
                    // Ownership transfers to original newForeignPtr(&free).
                    root.getCallTarget().call(address, (long) event.signal());
                }
            }
        } catch (Throwable caught) {
            outcome = GuestThreadStatus.uncaught(caught);
            // A stop request never makes hard-exit ThreadDeath ignorable.
            if (caught instanceof ThreadDeath || !stopping) failure = caught;
        } finally {
            boolean wasRegistered = registered;
            var terminalOutcome = outcome;
            failure = finishSignalConsumer(failure, () -> {
                synchronized (this) {
                    closed = true; stopping = true;
                    try { nativeTransport.close(); } finally { transport = null; }
                }
            }, () -> { if (wasRegistered) owner.getThreads().leaveCurrent(terminalOutcome); });
        }
        if (failure != null) owner.getEnv().getContext().closeCancelled(null, "Process signal dispatcher failed: " + failure.getClass().getSimpleName());
    }
    /** Exit notification only: no guest call, context lookup or safepoint join. */
    public synchronized void requestStop() {
        stopping = true;
        if (transport != null) transport.wake();
        if (worker == null) { closed = true; binding = null; program = null; }
    }
    /** Reader owns destruction after its safepoint interrupter is unregistered. */
    public void close() {
        requestStop();
        Thread child;
        synchronized (this) { child = worker; }
        if (child != null && child != Thread.currentThread() && child.isAlive())
            TruffleSafepoint.setBlockedThreadInterruptibleFunction(null,
                (TruffleSafepoint.InterruptibleFunction<Thread, Object>) thread -> { thread.join(); return thc.runtime.Unit.INSTANCE; }, child);
    }
    /** Check the effective VM setting, including a later override of launcher -Xrs. */
    public static boolean hasReducedVmSignals() {
        try {
            var bean = ManagementFactory.getPlatformMXBean(HotSpotDiagnosticMXBean.class);
            return bean != null && "true".equals(bean.getVMOption("ReduceSignalUsage").getValue());
        } catch (IllegalArgumentException | SecurityException failure) { return false; }
    }
    public static long install(Node node, long signal, long action, ManagedAddress mask) {
        return Language.currentState(node).getSignals().install(signal, action, mask);
    }
    @FunctionalInterface public interface Cleanup { void run() throws Throwable; }
    /** Preserve hard Truffle control flow even if restoration or bookkeeping fails. */
    public static Throwable finishSignalConsumer(Throwable initial, Cleanup restore, Cleanup unregister) {
        Throwable failure = initial;
        for (var cleanup : new Cleanup[]{restore, unregister}) try { cleanup.run(); }
        catch (Throwable caught) {
            var previous = failure;
            if (previous == caught) continue;
            if (previous == null) failure = caught;
            else if (caught instanceof ThreadDeath && !(previous instanceof ThreadDeath)) {
                caught.addSuppressed(previous); failure = caught;
            } else previous.addSuppressed(caught);
        }
        if (failure instanceof ThreadDeath death) throw death;
        return failure;
    }
    @SuppressWarnings("unchecked") private static <E extends Throwable> RuntimeException propagate(Throwable failure) throws E { throw (E) failure; }
}
