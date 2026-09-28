// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.exception.AbstractTruffleException;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.interop.InteropException;
import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.nodes.IndirectCallNode;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.source.Source;
import com.oracle.truffle.api.CallTarget;
import thc.Language;
/** Cached interop messages can specialize on the foreign language's actual objects. */
public final class PolyglotAccess extends Node {
    @Child private Force force = new Force(new Metrics(false));
    @Child private IndirectCallNode evalCall = IndirectCallNode.create();
    @Child private InteropLibrary members = InteropLibrary.getFactory().createDispatched(3);
    @Child private InteropLibrary functions = InteropLibrary.getFactory().createDispatched(3);
    @Child private InteropLibrary numbers = InteropLibrary.getFactory().createDispatched(3);
    @Child private ForeignExceptionAccess foreignExceptions = new ForeignExceptionAccess();
    @TruffleBoundary private CallTarget parse(ManagedAddress language, ManagedAddress source, ManagedAddress name) {
        return Language.currentState(this).getEnv().parsePublic(Source.newBuilder(language.utf8(), source.utf8(), name.utf8()).build());
    }
    public ForeignValue eval(ManagedAddress language, ManagedAddress source, ManagedAddress name, Object state) {
        TupleResultsKt.requireVoidCarrier(state);
        var owner = Language.currentState(this);
        var threads = Language.currentState(this).getThreads();
        var previous = threads.enterForeign(ForeignSafety.SAFE);
        try {
            try {
                var value = evalCall.call(parse(language, source, name));
                if (value == null) throw RuntimeFault.fault("Foreign evaluation returned a host null");
                return new ForeignValue(owner, value);
            } finally { threads.leaveForeign(previous); }
        } catch (AbstractTruffleException error) { throw foreignExceptions.raise(error); }
    }
    private Object receiver(VirtualFrame frame, Object value) {
        if (!(force.execute(frame, value) instanceof ForeignValue handle)) throw RuntimeFault.fault("Expected THC.Polyglot.Value");
        if (handle.getOwner() != Language.currentState(this)) throw RuntimeFault.fault("Polyglot value belongs to a different context");
        return handle.getReceiver();
    }
    public ForeignValue readMember(VirtualFrame frame, Object value, ManagedAddress name, Object state) {
        TupleResultsKt.requireVoidCarrier(state);
        var receiver = receiver(frame, value);
        var threads = Language.currentState(this).getThreads();
        var previous = threads.enterForeign(ForeignSafety.SAFE);
        try {
            try {
                try { return new ForeignValue(Language.currentState(this), members.readMember(receiver, name.utf8())); }
                catch (InteropException error) { throw interopFailure("readMember", error); }
            } finally { threads.leaveForeign(previous); }
        } catch (AbstractTruffleException error) { throw foreignExceptions.raise(error); }
    }
    public long executeInt(VirtualFrame frame, Object value, long argument, Object state) {
        TupleResultsKt.requireVoidCarrier(state);
        var receiver = receiver(frame, value);
        // This first scalar bridge uses the integer range shared by Int# and JS Number.
        if (argument < -9_007_199_254_740_991L || argument > 9_007_199_254_740_991L)
            throw RuntimeFault.fault("Polyglot executeInt input exceeds the exact Number integer range");
        var threads = Language.currentState(this).getThreads();
        var previous = threads.enterForeign(ForeignSafety.SAFE);
        try {
            try {
                try {
                    var answer = functions.execute(receiver, argument);
                    if (!numbers.fitsInLong(answer)) throw RuntimeFault.fault("Polyglot executeInt result is not an exact Int#");
                    return numbers.asLong(answer);
                } catch (InteropException error) { throw interopFailure("executeInt", error); }
            } finally { threads.leaveForeign(previous); }
        } catch (AbstractTruffleException error) { throw foreignExceptions.raise(error); }
    }
    @TruffleBoundary private RuntimeException interopFailure(String operation, InteropException error) {
        throw new RuntimeFault("Polyglot " + operation + ": " + error.getMessage());
    }
}
