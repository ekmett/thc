// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import static thc.runtime.RuntimeFault.fault;

/** A context-owned C label; {@code &free} delegates to checked native ownership. */
public final class CFinalizerFunction {
    private final SulongCbits owner;
    private final String symbol;
    private final Object callable;
    private final PackageScalarFunction packageFunction;
    private final ManagedAddress address;
    public CFinalizerFunction(SulongCbits owner, String symbol, Object callable) { this(owner, symbol, callable, null); }
    public CFinalizerFunction(SulongCbits owner, String symbol, Object callable, PackageScalarFunction packageFunction) {
        this.owner = owner; this.symbol = symbol; this.callable = callable; this.packageFunction = packageFunction;
        this.address = ManagedAddress.fromCFinalizer(this);
    }
    public ManagedAddress getAddress() { return address; }
    public PackageScalarFunction getPackageFunction() { return packageFunction; }
    public String getSymbol() { return symbol; }
    public Object getCallable() { return callable; }
    public void requireOwner(SulongCbits provider) {
        if (provider != owner) throw fault("C function label belongs to another THC context");
        if (packageFunction != null) {
            PackageFinalizerRegistry.requireCurrent(packageFunction);
            // A handle may have escaped through a constructor callback before
            // its component finished loading. Reuse its canonical load result.
            if (packageFunction.getOwner().getPackageCbits().finalizer(symbol) != this)
                throw fault("C finalizer is not its component's canonical label");
        }
    }
    public void requireArity(int arity) {
        int expected = packageFunction == null ? 1 : packageFunction.getSignature().getArguments().size();
        if (arity != expected) throw fault("C finalizer argument count differs from its declared ABI");
    }
    public void invoke(ManagedAddress... arguments) { owner.invokeFinalizer(this, arguments); }
}
