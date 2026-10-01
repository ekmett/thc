// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.TruffleSafepoint;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.nodes.Node;
import com.sun.management.HotSpotDiagnosticMXBean;
import org.graalvm.nativeimage.ImageInfo;
import org.graalvm.nativeimage.RuntimeOptions;
import java.lang.management.ManagementFactory;
import java.util.concurrent.CountDownLatch;
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
    private CountDownLatch starting;
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
        if (!program.getCapturesContinuations()) throw fault("Process signal delivery requires continuation capture");
        // Resolve action/layouts only at the first actual installation.
        this.program = program;
    }
    private void current() { if (Language.currentState(null) != owner) throw fault("Process signals belong to another context"); }
    @TruffleBoundary(transferToInterpreterOnException = false) public long install(long signal, long action, ManagedAddress mask) {
        current();
        ProcessSignalTransport acquired = null;
        SignalDispatchRoot selectedRoot = null;
        CountDownLatch startup = null;
        ProcessSignalTransport.Result result;
        synchronized (this) {
        if (!authorized) throw fault("Process signals require explicit NativeIO launcher authority");
        StdioHostAbi abi;
        try { abi = StdioHostAbi.load(); } catch (java.io.IOException failure) { throw propagate(failure); }
        if (!abi.supportedSignal(signal) ||
            (action != -1 && action != -2 && action != -4 && action != -5) || mask != ManagedAddress.nullAddress())
            throw fault("stg_sig_install supports only HUP/INT/QUIT/USR1/USR2/TERM/XCPU/XFSZ, DFL/IGN/HAN/RST and a null mask");
        if (signal != abi.signal("SIGINT") && !reducedVmSignals) throw fault("GHC process signal handlers require JVM -Xrs or Native Image -R:-EnableSignalHandling");
        if (signal == abi.signal("SIGUSR2") && !userSignalAvailable.getAsBoolean()) throw fault("SIGUSR2 requires a verified standalone JVM suspend-signal relocation (_JAVA_SR_SIGNUM=64 on Linux) and native dispositions");
        if (closed || stopping) throw fault("Process signal service is closed");
        var root = binding;
        if (root == null) {
            if (program == null) throw fault("Missing original signal dispatcher");
            root = new SignalDispatchRoot(language, program); binding = root;
        }
        var nativeTransport = transport;
        if (nativeTransport == null) {
            owner.admitGuestConcurrency();
            acquired = factory.get(); transport = nativeTransport = acquired;
            selectedRoot = root; starting = startup = new CountDownLatch(1);
        }
        try { result = nativeTransport.install((int) signal, (int) action); }
        catch (Throwable failure) {
            if (acquired != null) {
                transport = null; closed = true; starting = null;
                try { failure = finishSignalConsumer(failure, acquired::close, () -> {}); }
                finally { startup.countDown(); }
            }
            throw propagate(failure);
        }
        }
        if (acquired != null) {
            var input = acquired; var dispatch = selectedRoot;
            try {
                boolean started = false;
                try {
                    // Construction/readmission may block: never retain the service monitor.
                    var child = owner.getThreads().newThread(owner.getEnv(), () -> consume(input, dispatch), null, null);
                    synchronized (this) {
                        if (!stopping) { worker = child; owner.getThreads().startThread(child); started = true; }
                    }
                } catch (Throwable failure) {
                    synchronized (this) { transport = null; worker = null; closed = true; }
                    throw propagate(finishSignalConsumer(failure, input::close, () -> {}));
                }
                if (!started) {
                    synchronized (this) { transport = null; closed = true; }
                    input.close();
                }
            } finally {
                synchronized (this) { starting = null; }
                startup.countDown();
            }
        }
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
                ProcessSignalTransport.Event event;
                var permission = owner.getThreads().enterForeign(ForeignSafety.SAFE);
                try { event = TruffleSafepoint.getCurrent().setBlockedFunction(null, interrupter, reader, thc.runtime.Unit.INSTANCE, null, null); }
                finally { owner.getThreads().leaveForeign(permission); }
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
        if (failure != null) {
            GuestThreadOps.reportHostFailure(owner, failure);
            throw propagate(failure);
        }
    }
    /** Exit notification only: no guest call, context lookup or safepoint join. */
    public synchronized void requestStop() {
        stopping = true;
        if (transport != null) transport.wake();
        if (worker == null && starting == null) { closed = true; binding = null; program = null; }
    }
    /** Reader owns destruction after its safepoint interrupter is unregistered. */
    public void close() {
        requestStop();
        CountDownLatch startup;
        synchronized (this) { startup = starting; }
        try (var admission = LoomScheduler.suspendCurrentGuest()) {
        if (startup != null) TruffleSafepoint.setBlockedThreadInterruptible(null, CountDownLatch::await, startup);
        Thread child;
        synchronized (this) { child = worker; }
        if (child != null && child != Thread.currentThread() && child.isAlive())
            TruffleSafepoint.setBlockedThreadInterruptibleFunction(null,
                (TruffleSafepoint.InterruptibleFunction<Thread, Object>) thread -> { thread.join(); return thc.runtime.Unit.INSTANCE; }, child);
        }
    }
    /** Check the effective host setting, never a user-supplied marker property. */
    public static boolean hasReducedVmSignals() {
        if (ImageInfo.inImageRuntimeCode())
            return Boolean.FALSE.equals(RuntimeOptions.get("EnableSignalHandling"));
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
