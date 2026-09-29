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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.StringJoiner;
import thc.Language;
import thc.ManagedCallbackSignature;
import thc.ManagedExportValue;
import thc.MemberNames;
import thc.ManagedExportSignature;
import thc.PackageScalarSignature;
import static thc.runtime.RuntimeFault.fault;

/** Public NFI signatures and native callback lifetimes belong to one THC context. */
public final class NativeCallbacks {
    private final Language.State owner;
    private final Assumption alive = Assumption.create("THC native callbacks are open");
    private final HashMap<PackageScalarSignature, PackageScalarFunction> dynamic = new HashMap<>();
    private final HashMap<Long, Callback> callbacks = new HashMap<>();
    private final Namespace namespace = new Namespace(this);
    private Object stableRelease, boundThreadSupport;
    private final LinkedHashMap<String, StaticExport> exports = new LinkedHashMap<>();
    // A failed load can already have handed pointers to C constructors. Keep
    // their NFI closures alive, but invalid, until the owning context closes.
    private final ArrayList<StaticExport> retiredExports = new ArrayList<>();
    private final ThreadLocal<ArrayDeque<PackagePointerCells>> pointerCalls = ThreadLocal.withInitial(ArrayDeque::new);
    public NativeCallbacks(Language.State owner) { this.owner = owner; }
    public void checkOwner() {
        checkContext();
        if (!owner.getEnv().isNativeAccessAllowed()) throw fault("Native callback requires native access");
    }
    private void checkContext() {
        if (Language.currentState(null) != owner || !alive.isValid()) throw fault("Native callback belongs to another or closed context");
    }
    private void checkEntry() {
        checkOwner();
        for (var projection : pointerCalls.get()) if (projection.holdsAllocationMonitors())
            throw fault("Native callback cannot enter during a pointer-cell projection");
    }
    /** Checked declarations publish names without evaluating their Haskell roots. */
    @TruffleBoundary public synchronized void registerStaticExports(ExecutableProgram program, Language language, List<ManagedExportSignature> declarations) {
        checkContext();
        var pending = new LinkedHashMap<String, StaticExport>();
        for (var declaration : declarations) {
            var existing = exports.get(declaration.symbol());
            if (declaration.symbol().equals("hs_free_stable_ptr") || declaration.symbol().equals("rtsSupportsBoundThreads") || pending.containsKey(declaration.symbol()) ||
                existing != null && (existing.program != program || !existing.declaration.equals(declaration)))
                throw fault("Conflicting native static export: " + declaration.symbol());
            if (existing == null) pending.put(declaration.symbol(), new StaticExport(this, program, language, declaration));
        }
        exports.putAll(pending);
    }
    @TruffleBoundary public synchronized void unregisterStaticExports(ExecutableProgram program) {
        checkContext();
        var iterator = exports.values().iterator();
        while (iterator.hasNext()) {
            var export = iterator.next();
            if (export.program == program) { export.open.invalidate(); retiredExports.add(export); iterator.remove(); }
        }
    }
    @ExportLibrary(InteropLibrary.class)
    static final class StaticExport implements TruffleObject {
        final NativeCallbacks registry;
        final ExecutableProgram program;
        final Language language;
        final ManagedExportSignature declaration;
        final List<String> arguments;
        final String result;
        final Assumption open = Assumption.create("THC static export is registered");
        private Object closure;
        private volatile ManagedExportValue executable;
        StaticExport(NativeCallbacks registry, ExecutableProgram program, Language language, ManagedExportSignature declaration) {
            this.registry = registry; this.program = program; this.language = language; this.declaration = declaration;
            if (declaration.io() && declaration.ioResult() == null) throw fault("Static IO export lacks its checked Core result signature");
            arguments = declaration.arguments().stream().map(type -> ManagedExportScalar.nativeRepresentation(type,
                ManagedExportScalar.Role.ARGUMENT, declaration.wordBits())).toList();
            result = ManagedExportScalar.nativeRepresentation(declaration.result(), ManagedExportScalar.Role.RESULT, declaration.wordBits());
        }
        void check() { registry.checkEntry(); if (!open.isValid()) throw fault("Native static export has been unregistered"); }
        @TruffleBoundary synchronized Object pointer() {
            registry.checkOwner();
            if (!open.isValid()) throw fault("Native static export has been unregistered");
            if (closure == null) {
                try { closure = InteropLibrary.getUncached().invokeMember(registry.signature(arguments, result), "createClosure", this); }
                catch (InteropException failure) { throw rethrow(failure); }
            }
            return closure;
        }
        @TruffleBoundary ManagedExportValue executable() {
            var selected = executable;
            if (selected == null) {
                selected = new ManagedExportValue(registry, this::check, registry.owner, language, program, declaration,
                    program.entryValue(declaration.binder()));
                synchronized (this) { if (executable == null) executable = selected; else selected = executable; }
            }
            return selected;
        }
        @ExportMessage boolean isExecutable() { check(); return true; }
        @ExportMessage Object execute(Object[] values) throws ArityException, UnsupportedTypeException, UnsupportedMessageException {
            check(); return InteropLibrary.getUncached().execute(executable(), values);
        }
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
    /** Sulong searches this context-owned namespace before entering original C. */
    public Object namespace() { checkOwner(); return namespace; }
    @TruffleBoundary private synchronized Object stableRelease() {
        checkOwner();
        if (stableRelease == null) {
            try {
                stableRelease = InteropLibrary.getUncached().invokeMember(signature(List.of("AddrRep"), "void"),
                    "createClosure", new StableRelease(this));
            } catch (InteropException failure) { throw rethrow(failure); }
        }
        return stableRelease;
    }
    @TruffleBoundary private synchronized Object boundThreadSupport() {
        checkOwner();
        if (boundThreadSupport == null) {
            try {
                boundThreadSupport = InteropLibrary.getUncached().invokeMember(signature(List.of(), "IntRep"),
                    "createClosure", new BoundThreadQuery(this));
            } catch (InteropException failure) { throw rethrow(failure); }
        }
        return boundThreadSupport;
    }
    @ExportLibrary(InteropLibrary.class)
    static final class Namespace implements TruffleObject {
        private final NativeCallbacks registry;
        Namespace(NativeCallbacks registry) { this.registry = registry; }
        @ExportMessage boolean hasMembers() { registry.checkOwner(); return true; }
        @ExportMessage @TruffleBoundary Object getMembers(boolean includeInternal) {
            registry.checkOwner();
            synchronized (registry) {
                var names = new ArrayList<>(registry.exports.keySet()); names.add("hs_free_stable_ptr"); names.add("rtsSupportsBoundThreads");
                return new MemberNames(names.toArray(String[]::new));
            }
        }
        @ExportMessage @TruffleBoundary boolean isMemberReadable(String name) {
            registry.checkOwner(); synchronized (registry) { return name.equals("hs_free_stable_ptr") || name.equals("rtsSupportsBoundThreads") || registry.exports.containsKey(name); }
        }
        @ExportMessage @TruffleBoundary Object readMember(String name) throws UnknownIdentifierException {
            registry.checkOwner();
            if (name.equals("hs_free_stable_ptr")) return registry.stableRelease();
            if (name.equals("rtsSupportsBoundThreads")) return registry.boundThreadSupport();
            StaticExport export; synchronized (registry) { export = registry.exports.get(name); }
            if (export != null) return export.pointer();
            throw UnknownIdentifierException.create(name);
        }
    }
    @ExportLibrary(InteropLibrary.class)
    static final class BoundThreadQuery implements TruffleObject {
        private final NativeCallbacks registry;
        BoundThreadQuery(NativeCallbacks registry) { this.registry = registry; }
        @ExportMessage boolean isExecutable() { registry.checkOwner(); return true; }
        @ExportMessage Object execute(Object[] arguments) throws ArityException {
            registry.checkOwner();
            if (arguments.length != 0) throw ArityException.create(0, 0, arguments.length);
            // HsBool is StgInt. Match BoundThreadSupport: native GHC's RTS
            // cannot report the bound-thread semantics of this THC context.
            return 0L;
        }
    }
    @ExportLibrary(InteropLibrary.class)
    static final class StableRelease implements TruffleObject {
        private final NativeCallbacks registry;
        StableRelease(NativeCallbacks registry) { this.registry = registry; }
        @ExportMessage boolean isExecutable() { registry.checkOwner(); return true; }
        @ExportMessage @TruffleBoundary Object execute(Object[] arguments) throws ArityException, UnsupportedTypeException {
            registry.checkOwner();
            if (arguments.length != 1) throw ArityException.create(1, 1, arguments.length);
            try {
                var stable = registry.owner.getStablePointers();
                var address = stable.recoverToken(InteropLibrary.getUncached().asPointer(arguments[0]));
                if (address == null) throw fault("hs_free_stable_ptr requires a live StablePtr from this context");
                stable.free(address);
                return 0;
            } catch (UnsupportedMessageException failure) {
                throw UnsupportedTypeException.create(arguments, "hs_free_stable_ptr requires a native StablePtr token");
            }
        }
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
            NativeCallbacks.this.checkEntry();
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
        alive.invalidate(); dynamic.clear(); stableRelease = null; boundThreadSupport = null;
        for (var callback : callbacks.values()) callback.release();
        callbacks.clear(); // StablePointers owns final context disposal of its entries.
        exports.clear(); retiredExports.clear();
    }
    @SuppressWarnings("unchecked") private static <E extends Throwable> RuntimeException rethrow(Throwable failure) throws E { throw (E) failure; }
}
