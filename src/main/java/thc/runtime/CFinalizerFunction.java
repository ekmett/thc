// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import static thc.runtime.RuntimeFault.fault;

/** A context-owned C label; &free delegates to checked native ownership. */
public final class CFinalizerFunction {
    private final SulongCbits owner;
    private final String symbol;
    private final Object callable;
    public CFinalizerFunction(SulongCbits owner, String symbol, Object callable) { this.owner = owner; this.symbol = symbol; this.callable = callable; }
    public String getSymbol() { return symbol; }
    public Object getCallable() { return callable; }
    public void requireOwner(SulongCbits provider) { if (provider != owner) throw fault("C function label belongs to another THC context"); }
    public void invoke(ManagedAddress address) { owner.invokeFinalizer(this, address); }
}
