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
    @CompilationFinal private Object preparationLock;
    private volatile boolean prepared;
    private Object preparedValue;
    private Preparation prepare;
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
    public void defer(Object lock, Preparation action) {
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

    /** The shared program lock protects lowering builders, never guest evaluation. */
    @CompilerDirectives.TruffleBoundary
    private Object prepareValue() {
        Object lock = preparationLock;
        if (lock == null) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            throw new RuntimeFault("Uninitialized global binding");
        }
        synchronized (lock) {
            if (prepared) return preparedValue;
            if (preparationFailure != null) return rethrow(preparationFailure);
            if (preparing) throw new IllegalStateException("Recursive Core preparation for " + name);
            preparing = true;
            try {
                if (prepare == null) throw new IllegalStateException("Uninitialized global binding");
                Object result = prepare.get();
                preparedValue = result;
                prepared = true;
                prepare = null;
                return result;
            } catch (CancellationException cancelled) {
                throw cancelled;
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return rethrow(interrupted);
            } catch (Exception failure) {
                preparationFailure = failure;
                prepare = null;
                return rethrow(failure);
            } finally {
                preparing = false;
            }
        }
    }
    @SuppressWarnings("unchecked")
    private static <E extends Throwable> Object rethrow(Throwable failure) throws E {
        throw (E) failure;
    }
}
