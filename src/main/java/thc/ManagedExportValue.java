// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.interop.*;
import com.oracle.truffle.api.library.ExportLibrary;
import com.oracle.truffle.api.library.ExportMessage;
import java.math.BigInteger;
import java.util.List;
import java.util.ArrayList;
import java.util.Collections;
import thc.runtime.*;

/** A declaration alias uses the same program-owned binder, layouts and CAFs as its peers. */
@ExportLibrary(InteropLibrary.class)
public final class ManagedExportValue implements TruffleObject {
    private final ManagedExportRegistry registry;
    private final Language.State owner;
    private final ExecutableProgram program;
    private final ManagedExportSignature signature;
    private final List<ManagedExportScalar> arguments;
    private final ManagedExportScalar result;
    private final RootCallTarget guestTarget, ioTarget;
    private final Object guestEntry;
    public ManagedExportValue(ManagedExportRegistry registry, Language.State owner, Language language, ExecutableProgram program, ManagedExportSignature signature) {
        this.registry = registry; this.owner = owner; this.program = program; this.signature = signature;
        var argumentTypes = new ArrayList<ManagedExportScalar>();
        for (var type : signature.arguments()) argumentTypes.add(ManagedExportScalar.Companion.fromNormalizedType(type, ManagedExportScalar.Role.ARGUMENT, signature.wordBits(), program::constructorLayout));
        arguments = Collections.unmodifiableList(argumentTypes);
        result = ManagedExportScalar.Companion.fromNormalizedType(signature.result(), ManagedExportScalar.Role.RESULT, signature.wordBits(), program::constructorLayout);
        guestTarget = program.hostEntryTarget(arguments.size()); guestEntry = program.entryValue(signature.binder());
        ioTarget = signature.ioResult() == null ? null : new ManagedExportIoRoot(language, signature.ioResult()).getCallTarget();
    }
    public ExecutableProgram getProgram() { return program; }
    public RootCallTarget getGuestTarget() { return guestTarget; }
    public RootCallTarget getIoTarget() { return ioTarget; }
    @ExportMessage public boolean isExecutable() { registry.checkOwner(); return true; }
    @ExportMessage public Object execute(Object[] values, @Cached(value = "create()", uncached = "create()", neverDefault = true) HostDispatch dispatch) throws ArityException, UnsupportedTypeException {
        registry.checkOwner();
        if (values.length != arguments.size()) throw ArityException.create(arguments.size(), arguments.size(), values.length);
        // Complete all host validation before an IO action can run. Unwrap only
        // the exact BigInteger codec from a polyglot HostObject.
        Object[] inputs = new Object[values.length];
        try {
            for (int index = 0; index < values.length; index++) {
                Object value = values[index], unwrapped = value;
                if (value != null && owner.getEnv().isHostObject(value)) {
                    Object host = owner.getEnv().asHostObject(value); if (host instanceof BigInteger) unwrapped = host;
                }
                inputs[index] = arguments.get(index).fromHost(unwrapped, InteropLibrary.getUncached());
            }
        } catch (RuntimeFault failure) { throw UnsupportedTypeException.create(values, failure.getMessage()); }
        var threads = owner.getThreads();
        threads.enterCurrent(null, false, program.getAsynchronousExceptions(), null);
        var outcome = GuestThreadStatus.FINISHED;
        try {
            try {
                Object applied = AsyncContinuations.publicResult(dispatch.executePublic(guestTarget, new Object[]{guestEntry, inputs}), dispatch);
                Object boxed = ioTarget == null ? applied : AsyncContinuations.publicResult(dispatch.executePublic(ioTarget, new Object[]{applied}), dispatch);
                return result.toHost(boxed);
            } catch (ThunkSuspended suspended) { throw AsyncContinuations.publicSuspension(suspended, dispatch); }
            catch (CallSegmentSuspended suspended) { throw AsyncContinuations.publicSuspension(suspended, dispatch); }
        } catch (Throwable failure) {
            outcome = GuestThreadStatus.Companion.uncaught(failure);
            if (failure instanceof GuestException guest) dispatch.escaping(guest);
            throw failure;
        } finally { threads.leaveCurrent(outcome); }
    }
    @ExportMessage public boolean hasLanguage() { return true; }
    @ExportMessage public Class<? extends TruffleLanguage<?>> getLanguage() { return Language.class; }
    @ExportMessage @TruffleBoundary public String toDisplayString(boolean allowSideEffects) { return signature.unit() + ":" + signature.module() + "/" + signature.symbol(); }
}
