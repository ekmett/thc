// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.interop.InteropException;
import com.oracle.truffle.api.interop.InteropLibrary;
import java.lang.ref.Reference;
import java.util.HashMap;
import java.util.Map;
import thc.Language;
import thc.PackageScalarLink;

/** Canonical labels retain the loaded component, callable and context lifetime. */
public final class PackageFinalizerRegistry {
    private final Map<String, CFinalizerFunction> labels = new HashMap<>();
    private boolean closed;

    private static RuntimeFault fault(String message) {
        CompilerDirectives.transferToInterpreterAndInvalidate();
        return new RuntimeFault(message);
    }

    @TruffleBoundary
    public synchronized void register(PackageScalarLink link, Map<String, PackageScalarFunction> functions,
            SulongCbits owner) {
        if (closed) throw fault("Package finalizer registry is closed");
        Map<String, CFinalizerFunction> additions = new HashMap<>();
        for (String entry : link.getFinalizers()) {
            PackageScalarFunction function = functions.get(entry);
            if (function == null) throw fault("Missing package finalizer entry");
            String symbol = function.getSignature().getSymbol();
            if (labels.containsKey(symbol) || additions.containsKey(symbol))
                throw fault("Ambiguous package C function label: " + symbol);
            additions.put(symbol, new CFinalizerFunction(owner, symbol, function.getReceiver(), function));
        }
        // Validate the whole registration before publishing any names.
        labels.putAll(additions);
    }

    @TruffleBoundary
    public synchronized CFinalizerFunction resolve(String symbol) {
        if (closed) throw fault("Package finalizer registry is closed");
        return labels.get(symbol);
    }

    public synchronized void close() {
        closed = true;
        labels.clear();
    }

    @TruffleBoundary
    public static void requireCurrent(PackageScalarFunction function) {
        if (Language.currentState(null) != function.getOwner())
            throw fault("Package finalizer belongs to another THC context");
        if (!function.getAlive().isValid()) throw fault("Package finalizer library is closed");
        if (!function.getOwner().getEnv().isNativeAccessAllowed())
            throw fault("Package finalizer requires native access");
    }

    @TruffleBoundary
    public static void invoke(PackageScalarFunction function, ManagedAddress address) throws InteropException {
        requireCurrent(function);
        var threads = function.getOwner().getThreads();
        var previous = threads.enterForeign(ForeignSafety.UNSAFE);
        try {
            PackagePointerCells.invoke(function, java.util.List.of(address), projection -> {
                Object pointer = projection.contains(address) ? new PackageNativePointer(projection.address(address), null)
                    : function.getOwner().getPackageCbits().transport(address);
                return () -> {
                    try { return InteropLibrary.getUncached().execute(function.getReceiver(), pointer); }
                    catch (InteropException failure) { throw rethrow(failure); }
                    finally { Reference.reachabilityFence(pointer); }
                };
            }, (projection, result) -> result);
        } finally {
            threads.leaveForeign(previous);
            Reference.reachabilityFence(address);
            Reference.reachabilityFence(function);
        }
    }
    @SuppressWarnings("unchecked") private static <E extends Throwable> RuntimeException rethrow(Throwable failure) throws E { throw (E) failure; }
}
