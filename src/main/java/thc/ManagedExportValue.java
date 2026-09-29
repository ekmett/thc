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
import java.util.ArrayList;
import thc.runtime.*;

/** A declaration alias uses the same program-owned binder, layouts and CAFs as its peers. */
@ExportLibrary(InteropLibrary.class)
public final class ManagedExportValue implements TruffleObject {
    private final ManagedExportRegistry registry;
    private final NativeCallbacks callbacks;
    private final Runnable callbackCheck;
    private final Language.State owner;
    private final ExecutableProgram program;
    private final ManagedExportSignature signature;
    private final ManagedExportScalar[] arguments;
    private final ManagedExportScalar result;
    private final RootCallTarget guestTarget, ioTarget;
    private final Object guestEntry;
    public ManagedExportValue(ManagedExportRegistry registry, Language.State owner, Language language, ExecutableProgram program, ManagedExportSignature signature) {
        this(registry, null, null, owner, language, program, signature, program.entryValue(signature.binder()));
    }
    public ManagedExportValue(NativeCallbacks callbacks, Runnable check, Language.State owner, Language language,
            ExecutableProgram program, ManagedExportSignature signature, Object closure) {
        this(null, callbacks, check, owner, language, program, signature, closure);
    }
    private ManagedExportValue(ManagedExportRegistry registry, NativeCallbacks callbacks, Runnable check, Language.State owner,
            Language language, ExecutableProgram program, ManagedExportSignature signature, Object closure) {
        this.registry = registry; this.callbacks = callbacks; callbackCheck = check;
        this.owner = owner; this.program = program; this.signature = signature;
        var argumentTypes = new ArrayList<ManagedExportScalar>();
        for (var type : signature.arguments()) argumentTypes.add(ManagedExportScalar.fromNormalizedType(type, ManagedExportScalar.Role.ARGUMENT, signature.wordBits(), program::constructorLayout));
        arguments = argumentTypes.toArray(new ManagedExportScalar[0]);
        result = ManagedExportScalar.fromNormalizedType(signature.result(), ManagedExportScalar.Role.RESULT, signature.wordBits(), program::constructorLayout);
        guestTarget = program.hostEntryTarget(arguments.length); guestEntry = closure;
        ioTarget = signature.ioResult() == null ? null : new ManagedExportIoRoot(language, signature.ioResult()).getCallTarget();
    }
    public ExecutableProgram getProgram() { return program; }
    public RootCallTarget getGuestTarget() { return guestTarget; }
    public RootCallTarget getIoTarget() { return ioTarget; }
    private void checkOwner() { if (registry != null) registry.checkOwner(); else { callbacks.checkOwner(); callbackCheck.run(); } }
    public java.util.List<String> nativeArguments() {
        var result = new ArrayList<String>(); for (var argument : arguments) result.add(argument.nativeRepresentation()); return result;
    }
    public String nativeResult() { return result.nativeRepresentation(); }
    @ExportMessage public boolean isExecutable() { checkOwner(); return true; }
    @ExportMessage public Object execute(Object[] values, @Cached(value = "create()", uncached = "create()", neverDefault = true) HostDispatch dispatch) throws ArityException, UnsupportedTypeException {
        checkOwner();
        owner.admitGuestOrigin();
        var threads = owner.getThreads();
        if (threads.needsHosting()) return threads.hostEntry(dispatch, () -> execute(values, dispatch));
        if (values.length != arguments.length) throw ArityException.create(arguments.length, arguments.length, values.length);
        // Complete all host validation before an IO action can run. Unwrap only
        // the exact BigInteger codec from a polyglot HostObject.
        Object[] inputs = new Object[values.length];
        try {
            for (int index = 0; index < values.length; index++) {
                Object value = values[index], unwrapped = value;
                if (value != null && owner.getEnv().isHostObject(value)) {
                    Object host = owner.getEnv().asHostObject(value); if (host instanceof BigInteger) unwrapped = host;
                }
                inputs[index] = callbacks == null ? arguments[index].fromHost(unwrapped, InteropLibrary.getUncached())
                    : arguments[index].fromNative(unwrapped, callbacks);
            }
        } catch (RuntimeFault failure) { throw UnsupportedTypeException.create(values, failure.getMessage()); }
        threads.enterCurrent(null, false, program.getCapturesContinuations(), null);
        var outcome = GuestThreadStatus.FINISHED;
        try {
            try {
                Object applied = AsyncContinuations.publicResult(dispatch.executePublic(guestTarget, new Object[]{guestEntry, inputs}), dispatch);
                Object boxed = ioTarget == null ? applied : AsyncContinuations.publicResult(dispatch.executePublic(ioTarget, new Object[]{applied}), dispatch);
                return callbacks == null ? result.toHost(boxed) : result.toNative(boxed, callbacks);
            } catch (ThunkSuspended suspended) { throw AsyncContinuations.publicSuspension(suspended, dispatch); }
            catch (CallSegmentSuspended suspended) { throw AsyncContinuations.publicSuspension(suspended, dispatch); }
            catch (AsyncDelivery delivered) { throw AsyncContinuations.uncaught(delivered.getRequest(), dispatch); }
        } catch (Throwable failure) {
            outcome = GuestThreadStatus.uncaught(failure);
            if (failure instanceof GuestException guest) dispatch.escaping(guest);
            throw failure;
        } finally { threads.leaveCurrent(outcome); }
    }
    @ExportMessage public boolean hasLanguage() { return true; }
    @ExportMessage public Class<? extends TruffleLanguage<?>> getLanguage() { return Language.class; }
    @ExportMessage @TruffleBoundary public String toDisplayString(boolean allowSideEffects) { return signature.unit() + ":" + signature.module() + "/" + signature.symbol(); }
}
