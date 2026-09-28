// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.Assumption;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.Truffle;

/** Per-layout proof that Java class equality implies constructor identity. */
public final class ConstructorClassIdentity {
    public static final String CONSTRUCTOR_CLASS_IDENTITY_PROPERTY = "thc.constructorClassIdentity";
    private static final class Owners {
        private Object owner;
        private final Assumption exclusive = Truffle.getRuntime().createAssumption("exclusive constructor carrier");
        synchronized Assumption register(Object token) {
            if (owner == null) owner = token;
            else if (owner != token) exclusive.invalidate();
            return exclusive;
        }
    }
    // Neither the token nor registry entry retains the class or its layout.
    private static final ClassValue<Owners> registry = new ClassValue<>() {
        @Override protected Owners computeValue(Class<?> type) { return new Owners(); }
    };
    @TruffleBoundary private static Assumption register(Class<?> type, Object owner) { return registry.get(type).register(owner); }
    private final Class<?> carrier;
    private final Object owner = new Object();
    private final Assumption exclusive;
    private final Assumption uniform = Truffle.getRuntime().createAssumption("uniform constructor factory class");
    public ConstructorClassIdentity(Class<?> carrier) { this.carrier = carrier; exclusive = register(carrier, owner); }
    public Class<?> getCarrier() { return carrier; }
    public boolean isExclusive() { return uniform.isValid() && exclusive.isValid(); }
    public void observe(Class<?> actual) { if (actual != carrier) registerAlternative(actual); }
    @TruffleBoundary private void registerAlternative(Class<?> actual) {
        uniform.invalidate();
        register(actual, owner);
    }
}
