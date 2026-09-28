// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc;

import com.oracle.truffle.api.interop.*;
import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.library.ExportLibrary;
import com.oracle.truffle.api.library.ExportMessage;
import thc.runtime.*;
import com.oracle.truffle.api.utilities.TriState;

/** Opaque, context-owned reference. No native pointer bits or mutable fields escape. */
@ExportLibrary(InteropLibrary.class)
public final class HostReference implements TruffleObject {
    final Language.State owner;
    final Object value;
    final CoreRepresentation proof;
    private final ExecutableProgram program;
    private final Language language;
    private volatile RootCallTarget callTarget;
    HostReference(Language.State owner, Object value, CoreRepresentation proof, ExecutableProgram program) {
        this.owner = owner; this.value = value; this.proof = proof; this.program = program;
        language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
    }
    @ExportMessage public boolean isNull() { return value == null || value == ManagedAddress.nullAddress(); }
    @ExportMessage public boolean isExecutable() { return value instanceof Closure || proof.getKind() == CoreKind.CLOSURE; }
    @ExportMessage public int identityHashCode() { return System.identityHashCode(value); }
    @ExportMessage public TriState isIdenticalOrUndefined(Object other) {
        return other instanceof HostReference reference ? TriState.valueOf(owner == reference.owner && value == reference.value) : TriState.UNDEFINED;
    }
    @ExportMessage @TruffleBoundary public Object execute(Object[] arguments,
            @Cached(value = "create()", uncached = "create()", neverDefault = true) HostDispatch dispatch) throws UnsupportedMessageException {
        if (Language.currentState(dispatch) != owner) throw new IllegalArgumentException("Host function belongs to another context");
        if (!isExecutable()) throw UnsupportedMessageException.create();
        var threads = owner.getThreads();
        if (threads.needsHosting()) return threads.hostEntry(dispatch, () -> execute(arguments, dispatch));
        threads.enterCurrent(null, false, program.getAsynchronousExceptions(), null);
        var outcome = GuestThreadStatus.FINISHED;
        try {
            try {
                Object forced = value instanceof Closure ? value : AsyncContinuations.publicResult(
                    dispatch.executePublic(program.hostEntryTarget(0), new Object[]{value, new Object[0]}), dispatch);
                if (!(forced instanceof Closure function) || !(function.target.getRootNode() instanceof GuestRoot root))
                    throw new RuntimeFault("Host function has no guest signature");
                var complete = root.getInputProofs();
                if (complete == null) throw new RuntimeFault("Host function is missing its logical input signature");
                var inputs = complete.subList(function.suppliedCount, complete.size());
                if (arguments.length != inputs.size()) throw new IllegalArgumentException("Host function arity mismatch");
                var result = root.getTupleResult() == null ? root.getScalarResultProof() : root.getTupleResult().getProof();
                var normalized = HostAbi.arguments(owner, inputs, arguments);
                RootCallTarget target = callTarget;
                if (target == null) {
                    synchronized (this) {
                        target = callTarget;
                        if (target == null) callTarget = target = new EntryRoot(language, inputs, result, new Metrics(false)).getCallTarget();
                    }
                }
                Object answer = AsyncContinuations.publicResult(dispatch.executePublic(target, new Object[]{function, normalized}), dispatch);
                return HostAbi.result(owner, result, answer, program);
            } catch (ThunkSuspended suspended) { throw AsyncContinuations.publicSuspension(suspended, dispatch); }
            catch (CallSegmentSuspended suspended) { throw AsyncContinuations.publicSuspension(suspended, dispatch); }
            catch (AsyncDelivery delivered) { throw AsyncContinuations.uncaught(delivered.getRequest(), dispatch); }
        } catch (Throwable failure) {
            outcome = GuestThreadStatus.uncaught(failure);
            if (failure instanceof GuestException guest) dispatch.escaping(guest);
            throw failure;
        } finally { threads.leaveCurrent(outcome); }
    }
}
