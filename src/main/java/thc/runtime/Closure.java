// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.RootCallTarget;
import jam.vm.Lifted;
import static thc.runtime.RuntimeServiceStatus.fault;

/* Cadenza-derived closure/PAP convention; upstream notices remain in LICENSE.txt and NOTICE.md. */
@CompilerDirectives.ValueType
public final class Closure implements Lifted {
    public static final Object[] NO_PAP_ARGUMENTS = new Object[0];
    public final CapturedFrame environment;
    @CompilerDirectives.CompilationFinal(dimensions = 1) public final Object[] supplied;
    public final int arity;
    public final RootCallTarget target;
    public final int suppliedCount;
    public final HandoffStorage typedSupplied;
    public Closure(CapturedFrame environment, int arity, RootCallTarget target) {
        this(environment, NO_PAP_ARGUMENTS, arity, target, 0, null);
    }
    public Closure(CapturedFrame environment, Object[] supplied, int arity, RootCallTarget target) {
        this(environment, supplied, arity, target, supplied.length, null);
    }
    public Closure(CapturedFrame environment, Object[] supplied, int arity, RootCallTarget target, int suppliedCount) {
        this(environment, supplied, arity, target, suppliedCount, null);
    }
    public Closure(CapturedFrame environment, Object[] supplied, int arity, RootCallTarget target, int suppliedCount, HandoffStorage typedSupplied) {
        if (arity < 0) throw new IllegalArgumentException("Negative closure arity");
        this.environment = environment; this.supplied = supplied; this.arity = arity;
        this.target = target; this.suppliedCount = suppliedCount; this.typedSupplied = typedSupplied;
    }
    public Closure pap(Object[] arguments) { return pap(arguments, 0, arguments.length); }
    @CompilerDirectives.TruffleBoundary public Closure pap(Object[] arguments, int offset, int count) {
        return papCompact(arguments, offset, count, count);
    }
    @CompilerDirectives.TruffleBoundary public Closure papCompact(Object[] arguments, int offset, int count, int logicalCount) {
        if (target.getRootNode() instanceof GuestRoot root && root.getTypedInput() != null)
            throw fault("Tuple-bearing PAP requires typed prefix storage");
        if (offset < 0 || count < 0 || offset + count > arguments.length || logicalCount >= arity)
            throw new IllegalArgumentException("Failed requirement.");
        Object[] combined = new Object[supplied.length + count];
        System.arraycopy(supplied, 0, combined, 0, supplied.length);
        System.arraycopy(arguments, offset, combined, supplied.length, count);
        return new Closure(environment, combined, arity - logicalCount, target, suppliedCount + logicalCount);
    }
    /** This carrier is already a terminal language value. */
    @Override public Lifted resolve() { return null; }
    /** This value exposes no constructor-field projections. */
    @Override public Lifted project(int field) { return null; }
}
