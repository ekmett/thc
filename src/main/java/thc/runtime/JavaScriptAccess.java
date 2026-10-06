// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.exception.AbstractTruffleException;
import com.oracle.truffle.api.interop.InteropException;
import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.oracle.truffle.api.nodes.Node;
import thc.Language;

public final class JavaScriptAccess extends Node {
    private final JavaScriptImport declaration;
    private final boolean reusable;
    private record CachedFunction(Language.State owner, Object function) {}
    @CompilationFinal private volatile CachedFunction cached;
    @Child private InteropLibrary calls;
    @Child private InteropLibrary numbers;
    /** Only the foreign call is opaque; a reentrant THC public entry opens its own guest cut. */
    @Child private ForeignExceptionAccess foreignExceptions = new ForeignExceptionAccess();
    public JavaScriptAccess(JavaScriptImport declaration) { this(declaration, false); }
    public JavaScriptAccess(JavaScriptImport declaration, boolean reusable) {
        this.declaration = declaration; this.reusable = reusable;
        calls = reusable ? InteropLibrary.getUncached() : InteropLibrary.getFactory().createDispatched(3);
        numbers = reusable ? InteropLibrary.getUncached() : InteropLibrary.getFactory().createDispatched(3);
    }
    private Object function() {
        var owner = Language.currentState(this);
        if (reusable) return owner.getJavaScriptImports().resolve(owner, declaration);
        var entry = cached;
        return entry != null && entry.owner == owner ? entry.function : initialize(owner);
    }
    @TruffleBoundary private Object initialize(Language.State owner) {
        var entry = cached;
        if (entry != null && entry.owner == owner) return entry.function;
        var value = owner.getJavaScriptImports().resolve(owner, declaration);
        synchronized (this) {
            entry = cached;
            if (entry != null && entry.owner == owner) return entry.function;
            CompilerDirectives.transferToInterpreterAndInvalidate();
            cached = new CachedFunction(owner, value); return value;
        }
    }
    @ExplodeLoop private Object execute(Object[] arguments, Object state) {
        TupleResults.requireVoidCarrier(state);
        if (arguments.length != declaration.getArguments().length) throw RuntimeFault.fault("JavaScript import argument count mismatch");
        for (int i = 0; i < arguments.length; i++) switch (declaration.getArguments()[i]) {
            case LONG -> {
                if (!(arguments[i] instanceof Long number)) throw RuntimeFault.fault("JavaScript import expected Int#");
                if (number < -9_007_199_254_740_991L || number > 9_007_199_254_740_991L)
                    throw RuntimeFault.fault("JavaScript import input exceeds the exact Number integer range");
            }
            case DOUBLE -> { if (!(arguments[i] instanceof Double)) throw RuntimeFault.fault("JavaScript import expected Double#"); }
            default -> throw RuntimeFault.fault("Invalid JavaScript import argument type");
        }
        try { return Calls.interop(calls, function(), arguments); }
        catch (InteropException error) { throw failure(error); }
    }
    public long executeLong(Object[] arguments, Object state) { return executeLong(arguments, state, null); }
    public long executeLong(Object[] arguments, Object state, Program instance) {
        var threads = Language.currentState(this).getThreads();
        var previous = threads.enterForeign(declaration.getSafety());
        try {
            try {
                var value = execute(arguments, state);
                if (!numbers.fitsInLong(value)) throw RuntimeFault.fault("JavaScript import result is not an exact Int#");
                return numbers.asLong(value);
            } finally { threads.leaveForeign(previous); }
        } catch (AbstractTruffleException error) { throw instance == null ? foreignExceptions.raise(error) : foreignExceptions.raise(error, instance.foreignExceptionBridge()); }
        catch (InteropException error) { throw propagate(error); }
    }
    public double executeDouble(Object[] arguments, Object state) { return executeDouble(arguments, state, null); }
    public double executeDouble(Object[] arguments, Object state, Program instance) {
        var threads = Language.currentState(this).getThreads();
        var previous = threads.enterForeign(declaration.getSafety());
        try {
            try {
                var value = execute(arguments, state);
                if (!numbers.fitsInDouble(value)) throw RuntimeFault.fault("JavaScript import result is not a Double#");
                return numbers.asDouble(value);
            } finally { threads.leaveForeign(previous); }
        } catch (AbstractTruffleException error) { throw instance == null ? foreignExceptions.raise(error) : foreignExceptions.raise(error, instance.foreignExceptionBridge()); }
        catch (InteropException error) { throw propagate(error); }
    }
    public void executeVoid(Object[] arguments, Object state) { executeVoid(arguments, state, null); }
    public void executeVoid(Object[] arguments, Object state, Program instance) {
        var threads = Language.currentState(this).getThreads();
        var previous = threads.enterForeign(declaration.getSafety());
        try { try { execute(arguments, state); } finally { threads.leaveForeign(previous); } }
        catch (AbstractTruffleException error) { throw instance == null ? foreignExceptions.raise(error) : foreignExceptions.raise(error, instance.foreignExceptionBridge()); }
    }
    @TruffleBoundary private RuntimeException failure(InteropException error) { throw new RuntimeFault("JavaScript import: " + error.getMessage()); }
    @SuppressWarnings("unchecked") private static <E extends Throwable> RuntimeException propagate(Throwable failure) throws E { throw (E) failure; }
}
