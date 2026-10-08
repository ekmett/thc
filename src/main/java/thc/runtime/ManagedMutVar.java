// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.HashMap;
import static thc.runtime.RuntimeServiceStatus.fault;

/** One mutable guest reference. Reading storage never enters its thunk. */
public final class ManagedMutVar {
    private volatile Object value;
    // These values are rooted by this key, not by the context's weak registry.
    // A value -> key cycle therefore has no external strong root of its own.
    private HashMap<Object, Object> weakValues;
    private static final VarHandle VALUE_HANDLE;
    static {
        try { VALUE_HANDLE = MethodHandles.lookup().findVarHandle(ManagedMutVar.class, "value", Object.class); }
        catch (ReflectiveOperationException failure) { throw new ExceptionInInitializerError(failure); }
    }
    public ManagedMutVar(Object value) { this.value = value; }
    public Object getValue() { return value; }
    public void setValue(Object value) { this.value = value; }
    synchronized void retainWeakValue(Object registration, Object value) {
        if (weakValues == null) weakValues = new HashMap<>();
        weakValues.put(registration, value);
    }
    synchronized Object weakValue(Object registration) { return weakValues.get(registration); }
    synchronized void releaseWeakValue(Object registration) {
        weakValues.remove(registration);
        if (weakValues.isEmpty()) weakValues = null;
    }
    /** Completed updates are CAS indirections. All other states stay opaque. */
    public static Object completedBoxedIdentity(Object value) {
        return value instanceof Thunk thunk && thunk.getState() == 2 ? thunk.getValue() : value;
    }
    public Object exchange(Object replacement) { return VALUE_HANDLE.getAndSet(this, replacement); }
    public Object compareExchange(Object expected, Object replacement) {
        Object witness = VALUE_HANDLE.compareAndExchange(this, expected, replacement);
        if (witness == expected) return expected;
        while (completedBoxedIdentity(witness) == completedBoxedIdentity(expected)) {
            Object prior = witness;
            witness = VALUE_HANDLE.compareAndExchange(this, prior, replacement);
            if (witness == prior) return expected;
        }
        return witness;
    }
    public ModifiedMutVar modify(Object function, MutVarModifySite site) { return modify(function, site, null); }
    @TruffleBoundary ModifiedMutVar modify(Object function, MutVarModifySite site, Program program) {
        while (true) {
            Object old = value;
            var result = site.application(function, old, program);
            var selected = site.stored(result, program);
            if (VALUE_HANDLE.compareAndSet(this, old, selected)) return new ModifiedMutVar(old, result);
        }
    }
    public static ManagedMutVar require(Object value) {
        if (value instanceof ManagedMutVar cell) return cell;
        throw fault("Expected a managed MutVar#");
    }
}
