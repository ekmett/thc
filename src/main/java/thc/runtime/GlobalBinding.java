// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import java.util.concurrent.CancellationException;

/** Linkage is fixed before execution; code and CAF contents may be prepared lazily. */
public final class GlobalBinding {
    private final String name;
    @CompilationFinal private boolean initialized;
    @CompilationFinal private Object value;
    @CompilationFinal private PreparationLock preparationLock;
    // A prepared cell never changes again. Compilations after publication may
    // fold its identity; earlier cold compilations keep the idempotent boundary
    // below, so publication need not invalidate their still-correct code.
    @CompilationFinal private volatile boolean prepared;
    @CompilationFinal private Object preparedValue;
    private Preparation prepare;
    private Initializer initializer;
    private boolean preparing;
    private Exception preparationFailure;

    /** Preparation may propagate interruption without memoizing it. */
    @FunctionalInterface public interface Preparation { Object get() throws Exception; }

    public GlobalBinding(String name) { this.name = name; }
    public String getName() { return name; }
    public void initialize(Object value) {
        if (initialized || preparationLock != null) throw new IllegalStateException("Check failed.");
        this.value = value;
        initialized = true;
    }
    public void defer(PreparationLock lock, Preparation action) {
        if (initialized || preparationLock != null) throw new IllegalStateException("Check failed.");
        preparationLock = java.util.Objects.requireNonNull(lock);
        prepare = java.util.Objects.requireNonNull(action);
    }
    public Object read() {
        if (preparationLock != null) return prepared ? preparedValue : prepareValue();
        if (!initialized) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            throw new RuntimeFault("Uninitialized global binding");
        }
        return value;
    }

    /** Observe publication without looking up, preparing, or evaluating this binding. */
    Object peek() {
        if (preparationLock != null) return prepared ? preparedValue : peekUnprepared();
        return initialized ? value : null;
    }
    // An already compiled cold caller must still observe later publication.
    @CompilerDirectives.TruffleBoundary
    private Object peekUnprepared() { return prepared ? preparedValue : null; }

    /** Complete inert lowering without executing a staged guest initializer. */
    @CompilerDirectives.TruffleBoundary
    public void prepareCode() {
        PreparationLock lock = preparationLock;
        if (lock == null) {
            if (!initialized) throw new RuntimeFault("Uninitialized global binding");
            return;
        }
        try (var ownership = lock.acquire()) { prepareCodeLocked(); }
    }

    private void prepareCodeLocked() {
        if (prepared || initializer != null) return;
        if (preparationFailure != null) rethrow(preparationFailure);
        if (preparing) throw new IllegalStateException("Recursive Core preparation for " + name);
        preparing = true;
        try {
            if (prepare == null) throw new IllegalStateException("Uninitialized global binding");
            Object result = prepare.get();
            prepare = null;
            if (result instanceof Initializer code) initializer = code;
            else { preparedValue = result; prepared = true; }
        } catch (CancellationException cancelled) { throw cancelled; }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); rethrow(interrupted); }
        catch (Exception failure) { preparationFailure = failure; prepare = null; rethrow(failure); }
        finally { preparing = false; }
    }

    /** The shared program lock protects lowering builders, never guest evaluation. */
    @CompilerDirectives.TruffleBoundary
    private Object prepareValue() {
        PreparationLock lock = preparationLock;
        if (lock == null) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            throw new RuntimeFault("Uninitialized global binding");
        }
        Initializer pending;
        try (var ownership = lock.acquire()) {
            prepareCodeLocked();
            if (prepared) return preparedValue;
            pending = initializer;
        }
        // Guest code, foreign calls and waits must never run under the lowering lock.
        Object result = pending.execute();
        try (var ownership = lock.acquire()) {
            if (!prepared) {
                preparedValue = result;
                prepared = true;
                pending.published.run();
                initializer = null;
            }
            return preparedValue;
        }
    }

    /** Staged execution outside lowering ownership. CallSegment owns guest initialization;
     * native provider tasks and nested cells own demanded linkage and entry execution. */
    static final class Initializer {
        private final Force force;
        private final CallSegment segment;
        private final Runnable published;
        private final ExecutableProgram demanded;
        private final thc.Language.State owner;
        private final String binding;
        Initializer(com.oracle.truffle.api.RootCallTarget target, Object[] arguments, Metrics metrics, Runnable published) {
            demanded = null; owner = null; binding = null;
            force = new Force(metrics, true);
            SavedGuestContinuation entry = new SavedGuestContinuation() {
                @Override public Object getIdentity() { return this; }
                @Override public Object getYielded() { return Unit.INSTANCE; }
                @Override public Object getSourceRoot() { return target.getRootNode(); }
                @Override public Object continueWith(Object input) { return Calls.target(target, arguments); }
            };
            segment = CallSegment.initial(entry,
                target.getRootNode() instanceof GuestRoot root ? root.getTupleResult() : null);
            this.published = published;
        }
        Initializer(ExecutableProgram demanded, thc.Language.State owner, String binding, Runnable published) {
            this.demanded = demanded; this.owner = owner; this.binding = binding; this.published = published;
            force = null; segment = null;
        }
        Object execute() {
            if (demanded == null) {
                Object result = force.executeInitialization(segment);
                TupleShape shape = segment.getTupleShape();
                // Call results use typed transport; a vector global stores the raw vector.
                return shape != null && shape.getProof().isVector()
                    ? shape.getLayout().getObject((HandoffStorage) result, 0) : result;
            }
            // Native provider tasks and the program's own cells retain execution sharing.
            demanded.initializeNative(owner);
            demanded.initializeGlobals();
            return demanded.entryValue(binding);
        }
    }
    @SuppressWarnings("unchecked")
    private static <E extends Throwable> Object rethrow(Throwable failure) throws E {
        throw (E) failure;
    }
}
