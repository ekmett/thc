// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.Assumption;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.interop.*;
import com.oracle.truffle.api.library.ExportLibrary;
import com.oracle.truffle.api.library.ExportMessage;
import com.oracle.truffle.api.source.Source;
import java.util.Arrays;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.List;
import java.util.StringJoiner;
import thc.Language;
import thc.ManagedCallbackSignature;
import thc.ManagedExportValue;
import thc.PackageScalarSignature;
import static thc.runtime.RuntimeFault.fault;

/** Public NFI signatures and native callback lifetimes belong to one THC context. */
public final class NativeCallbacks {
    private final Language.State owner;
    private final Assumption alive = Assumption.create("THC native callbacks are open");
    private final HashMap<PackageScalarSignature, PackageScalarFunction> dynamic = new HashMap<>();
    private final HashMap<Long, Callback> callbacks = new HashMap<>();
    private final ThreadLocal<ArrayDeque<PackagePointerCells>> pointerCalls = ThreadLocal.withInitial(ArrayDeque::new);
    public NativeCallbacks(Language.State owner) { this.owner = owner; }
    public void checkOwner() {
        if (Language.currentState(null) != owner || !alive.isValid()) throw fault("Native callback belongs to another or closed context");
        if (!owner.getEnv().isNativeAccessAllowed()) throw fault("Native callback requires native access");
    }
    static String nfiType(String rep) {
        // Signed transport preserves each primitive's raw bits; GHC owns signedness.
        return switch (rep) {
            case "Int8Rep", "Word8Rep" -> "sint8";
            case "Int16Rep", "Word16Rep" -> "sint16";
            case "Int32Rep", "Word32Rep" -> "sint32";
            case "IntRep", "WordRep", "Int64Rep", "Word64Rep" -> "sint64";
            case "FloatRep" -> "float";
            case "DoubleRep" -> "double";
            case "AddrRep" -> "pointer";
            case "void" -> "void";
            default -> throw fault("Unsupported native callback scalar: " + rep);
        };
    }
    private Object signature(List<String> arguments, String result) {
        var parameters = new StringJoiner(","); for (var argument : arguments) parameters.add(nfiType(argument));
        String text = "(" + parameters + "):" + nfiType(result);
        return owner.getEnv().parseInternal(Source.newBuilder("nfi", text, "THC foreign signature").build()).call();
    }
    private record Helper(ManagedCallbackSignature declaration, ExecutableProgram program, Language language) implements TruffleObject {}
    @TruffleBoundary public ManagedAddress helper(ManagedCallbackSignature declaration, ExecutableProgram program, Language language) {
        checkOwner();
        return ManagedAddress.fromReturnedAddress(new PackageReturnedAddress(owner, alive, new Helper(declaration, program, language), null));
    }
    private final class Callback {
        final Assumption open = Assumption.create("THC native callback is live");
        final ManagedAddress stable;
        Object closure;
        long bits;
        ManagedAddress address;
        Callback(ManagedAddress stable) { this.stable = stable; }
        void check() { checkOwner(); if (!open.isValid()) throw fault("Native callback has been freed"); }
        void checkEntry() {
            check();
            for (var projection : pointerCalls.get()) if (projection.holdsAllocationMonitors())
                throw fault("Native callback cannot enter during a pointer-cell projection");
        }
        void release() { open.invalidate(); closure = null; }
    }
    @ExportLibrary(InteropLibrary.class)
    static final class NativeCallbackPointer implements TruffleObject {
        private final NativeCallbacks.Callback callback;
        NativeCallbackPointer(NativeCallbacks.Callback callback) { this.callback = callback; }
        @ExportMessage boolean isPointer() { callback.check(); return true; }
        @ExportMessage long asPointer() { callback.check(); return callback.bits; }
        @ExportMessage void toNative() { callback.check(); }
    }
    @TruffleBoundary public ManagedAddress create(ManagedAddress stable, ManagedAddress helperAddress, ManagedAddress encoding) {
        checkOwner();
        if (helperAddress.returnedAddress() == null || !(helperAddress.returnedAddress().transport() instanceof Helper helper))
            throw fault("createAdjustor requires its declared callback helper");
        if (!helper.declaration.typeString().equals(encoding.utf8())) throw fault("createAdjustor type encoding differs from its emitted wrapper");
        var callback = new Callback(stable);
        Object guest = owner.getStablePointers().dereference(stable);
        try {
            var executable = new ManagedExportValue(this, callback::checkEntry, owner, helper.language, helper.program, helper.declaration.signature(), guest);
            Object signature = signature(executable.nativeArguments(), executable.nativeResult());
            var interop = InteropLibrary.getUncached();
            Object closure = interop.invokeMember(signature, "createClosure", executable);
            long bits = interop.asPointer(closure);
            if (bits == 0) throw fault("NFI returned a null callback");
            callback.closure = closure; callback.bits = bits;
            callback.address = ManagedAddress.fromReturnedAddress(new PackageReturnedAddress(owner, callback.open, new NativeCallbackPointer(callback), null));
            synchronized (this) {
                checkOwner();
                if (callbacks.putIfAbsent(bits, callback) != null) throw fault("Duplicate live native callback pointer");
            }
            return callback.address;
        } catch (Throwable failure) {
            callback.release();
            try { owner.getStablePointers().free(stable); }
            catch (Throwable cleanup) { failure.addSuppressed(cleanup); }
            throw rethrow(failure);
        }
    }
    @TruffleBoundary public void free(ManagedAddress address) {
        checkOwner();
        long bits = address.toNativeBits();
        Callback callback;
        synchronized (this) {
            callback = callbacks.remove(bits);
            if (callback == null) throw fault("freeHaskellFunPtr requires a live callback from this context");
            callback.release();
        }
        owner.getStablePointers().free(callback.stable);
    }
    @TruffleBoundary synchronized ManagedAddress recover(long bits) {
        checkOwner();
        var callback = callbacks.get(bits);
        return callback == null ? null : callback.address;
    }
    @TruffleBoundary public ManagedAddress incoming(Object pointer) {
        checkOwner();
        var interop = InteropLibrary.getUncached();
        try {
            if (interop.isNull(pointer)) return ManagedAddress.nullAddress();
            if (!interop.isPointer(pointer)) throw fault("Native callback argument is not a native pointer");
            long bits = interop.asPointer(pointer);
            var callback = recover(bits);
            if (callback != null) return callback;
            for (var projection : pointerCalls.get()) {
                var retained = projection.recoverBacking(bits);
                if (retained != null) return retained;
            }
            var known = owner.getStablePointers().recoverToken(bits);
            if (known == null) known = owner.getNativeAllocations().recoverAddress(bits);
            if (known == null) known = owner.getNativeAddresses().recover(bits);
            if (known.hasNativeStorage() || known.nativeImageKey() != null || known.stableHandle() != null) return known;
            return ManagedAddress.fromReturnedAddress(new PackageReturnedAddress(owner, alive, pointer, null));
        } catch (InteropException failure) { throw rethrow(failure); }
    }
    void enterPointerCall(PackagePointerCells projection) { pointerCalls.get().push(projection); }
    void leavePointerCall() { pointerCalls.get().pop(); }
    @TruffleBoundary public Object outgoing(ManagedAddress address) {
        checkOwner();
        Object pointer = owner.getPackageCbits().transport(address);
        var interop = InteropLibrary.getUncached(); interop.toNative(pointer);
        if (!interop.isPointer(pointer)) throw fault("Native callback result requires a live native address");
        return pointer;
    }
    @TruffleBoundary public synchronized PackageScalarFunction function(PackageScalarCall call) {
        checkOwner();
        var declaration = call.getSignature();
        return dynamic.computeIfAbsent(declaration, key -> new PackageScalarFunction(owner, key,
            call.getKind() == PackageScalarCall.Kind.DYNAMIC ? new Dynamic(this, signature(key.arguments().subList(1, key.arguments().size()), key.result())) : null, alive));
    }
    @ExportLibrary(InteropLibrary.class)
    static final class Dynamic implements TruffleObject {
        private final NativeCallbacks registry;
        private final Object signature;
        Dynamic(NativeCallbacks registry, Object signature) { this.registry = registry; this.signature = signature; }
        @ExportMessage boolean isExecutable() { registry.checkOwner(); return true; }
        @ExportMessage @TruffleBoundary Object execute(Object[] arguments) throws UnsupportedMessageException, UnsupportedTypeException, ArityException {
            registry.checkOwner();
            var interop = InteropLibrary.getUncached();
            Object pointer = arguments[0];
            interop.toNative(pointer);
            if (!interop.isPointer(pointer) || interop.asPointer(pointer) == 0) throw fault("Dynamic foreign call requires a non-null native function pointer");
            Object function;
            try { function = interop.invokeMember(signature, "bind", pointer); }
            catch (UnknownIdentifierException failure) { throw fault("NFI signature does not expose bind"); }
            return interop.execute(function, Arrays.copyOfRange(arguments, 1, arguments.length));
        }
    }
    public synchronized void close() {
        alive.invalidate(); dynamic.clear();
        for (var callback : callbacks.values()) callback.release();
        callbacks.clear(); // StablePointers owns final context disposal of its entries.
    }
    @SuppressWarnings("unchecked") private static <E extends Throwable> RuntimeException rethrow(Throwable failure) throws E { throw (E) failure; }
}
