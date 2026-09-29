// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.Assumption;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.TruffleSafepoint;
import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.exception.AbstractTruffleException;
import com.oracle.truffle.api.source.Source;
import com.oracle.truffle.llvm.runtime.LLVMContext;
import com.oracle.truffle.llvm.runtime.NativeContextExtension;
import org.graalvm.polyglot.io.ByteSequence;
import thc.Language;
import thc.PackageScalarLink;
import thc.PackageScalarSignature;
import java.util.HashMap;
import java.util.Map;
import java.util.ArrayDeque;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.lang.ref.Reference;
import java.nio.file.Files;
import static thc.runtime.RuntimeFault.fault;

/** Component C globals and entrypoints belong to one Truffle context. */
public final class PackageScalarLibraries {
    private record Loaded(PackageScalarLink link, FutureTask<Map<String, PackageScalarFunction>> task) {}
    private final TruffleLanguage.Env env;
    private final HashMap<String, Loaded> libraries = new HashMap<>();
    private final PackageFinalizerRegistry finalizers = new PackageFinalizerRegistry();
    private final Assumption alive = Assumption.create("THC package C libraries are open");
    private boolean closed;
    private boolean callbacksRegistered;
    private final ThreadLocal<ArrayDeque<PackageScalarLink>> initializing = ThreadLocal.withInitial(ArrayDeque::new);
    private final InteropLibrary interop = InteropLibrary.getUncached();
    private final FutureTask<Map<String, Object>> pointerOperations;
    public PackageScalarLibraries(TruffleLanguage.Env env) {
        this.env = env;
        pointerOperations = new FutureTask<>(() -> {
            byte[] bytes;
            try (var stream = PackageScalarLibraries.class.getResourceAsStream("/thc/cbits/package-pointer.bc")) {
                if (stream == null) throw fault("Missing package pointer bridge");
                bytes = stream.readAllBytes();
            }
            Object library = env.parseInternal(Source.newBuilder("llvm", ByteSequence.create(bytes), "package-pointer.bc").build()).call();
            var functions = new HashMap<String, Object>();
            for (String name : new String[]{"offset", "equal", "compare", "difference", "overlap", "read", "write",
                    "read_address", "write_address", "strlen", "copy", "fill", "errno", "set_errno", "free", "realloc"})
                functions.put(name, interop.readMember(library, "thc_package_pointer_" + name));
            return functions;
        });
    }
    private Language.State current() {
        var owner = Language.currentState(null);
        if (owner.getEnv() != env) throw fault("Package C library belongs to another context");
        if (!env.isNativeAccessAllowed()) throw fault("Package C bitcode requires native access");
        return owner;
    }
    /** Reserve exact component metadata without running native constructors. */
    @TruffleBoundary public void declare(PackageScalarLink link) { declaration(link); }
    private Loaded declaration(PackageScalarLink link) {
        var owner = current();
        Loaded selected;
        synchronized (this) {
            if (closed) throw fault("Package C library registry is closed");
            for (var entry : libraries.values())
                if (!entry.link().getUnit().equals(link.getUnit()) && entry.link().getComponentSha256().equals(link.getComponentSha256()))
                    throw fault("Package C entry namespace belongs to another unit: " + link.getComponentSha256());
            selected = libraries.get(link.getUnit());
            if (selected != null) {
                if (!selected.link().same(link)) throw fault("Conflicting package C component identity: " + link.getUnit());
            } else {
                selected = new Loaded(link, new FutureTask<>(() -> {
                    // Constructors may call their declared Haskell exports.
                    // Keep callback entry on this native origin, also in Loom;
                    // Sulong holds its reentrant context monitor during loading.
                    var previous = owner.getThreads().enterForeign(ForeignSafety.SAFE);
                    var stack = initializing.get(); stack.push(link);
                    try {
                        registerCallbacks(owner);
                        if (link.getNativeLibrary().length != 0) {
                            var file = Files.createTempFile("thc-package-native-", link.getFormat().equals("llvm-embedded-mach-o") ? ".dylib" : ".so");
                            try {
                                Files.write(file, link.getNativeLibrary());
                                // Keep ordinary unresolved native functions lazy: an
                                // archive member can also contain unused RTS wrappers.
                                // Register LOCAL handles in Sulong's existing context
                                // registry, never in the process-global namespace.
                                env.initializeLanguage(env.getInternalLanguages().get("llvm"));
                                var nativeContext = LLVMContext.get(null).getContextExtensionOrNull(NativeContextExtension.class);
                                if (nativeContext == null) throw fault("Sulong native library loading is unavailable");
                                String path = file.toString().replace("\\", "\\\\").replace("\"", "\\\"");
                                Object handle = env.parseInternal(Source.newBuilder("nfi",
                                    "load(RTLD_LAZY|RTLD_LOCAL) \"" + path + "\"", "package-native").build()).call();
                                nativeContext.addLibraryHandles(handle);
                            } finally { Files.deleteIfExists(file); }
                        }
                        Object library = env.parseInternal(Source.newBuilder("llvm", ByteSequence.create(link.getBytes()),
                            link.getComponentSha256() + switch (link.getFormat()) {
                                case "llvm-embedded-elf" -> ".so";
                                case "llvm-embedded-mach-o" -> ".dylib";
                                default -> ".bc";
                            }).build()).call();
                        var functions = new HashMap<String, PackageScalarFunction>();
                        for (var signature : link.getAbi()) {
                            if (!interop.isMemberReadable(library, signature.getEntry())) throw fault("Missing package C entry: " + signature.getEntry());
                            Object function = interop.readMember(library, signature.getEntry());
                            if (!interop.isExecutable(function)) throw fault("Package C entry is not executable");
                            functions.put(signature.getEntry(), new PackageScalarFunction(owner, signature, function, alive));
                        }
                        if (!link.getFinalizers().isEmpty()) finalizers.register(link, functions, owner.cbits());
                        return functions;
                    } finally { stack.pop(); owner.getThreads().leaveForeign(previous); }
                }));
                libraries.put(link.getUnit(), selected);
            }
        }
        return selected;
    }
    @TruffleBoundary public void link(PackageScalarLink link) {
        var selected = declaration(link);
        if (initializing.get().contains(selected.link())) return;
        selected.task().run();
        await(selected.task());
    }
    private void registerCallbacks(Language.State owner) {
        synchronized (this) { if (callbacksRegistered) return; }
        env.initializeLanguage(env.getInternalLanguages().get("llvm"));
        var nativeContext = LLVMContext.get(null).getContextExtensionOrNull(NativeContextExtension.class);
        if (nativeContext == null) throw fault("Sulong native library loading is unavailable");
        synchronized (this) {
            if (!callbacksRegistered) {
                nativeContext.addLibraryHandles(owner.getNativeCallbacks().namespace());
                callbacksRegistered = true;
            }
        }
    }
    private static <T> T await(FutureTask<T> task) {
        try {
            return task.isDone() ? task.get() : TruffleSafepoint.setBlockedThreadInterruptibleFunction(null,
                (TruffleSafepoint.InterruptibleFunction<FutureTask<T>, T>) pending -> {
                    try { return pending.get(); } catch (ExecutionException failure) { throw rethrow(failure); }
                }, task);
        } catch (ExecutionException failure) { throw rethrow(failure.getCause() != null ? failure.getCause() : failure); }
        catch (InterruptedException failure) { throw rethrow(failure); }
    }
    @TruffleBoundary public Object pointer(Object base, long offset) {
        current();
        if (!alive.isValid()) throw fault("Package C library registry is closed");
        return java.util.Objects.requireNonNull(memory("offset", base, offset));
    }
    @TruffleBoundary public Object memory(String operation, Object... arguments) {
        var owner = current();
        if (!alive.isValid()) throw fault("Package C library registry is closed");
        pointerOperations.run();
        Object function = await(pointerOperations).get(operation);
        if (function == null) throw new java.util.NoSuchElementException("Key " + operation + " is missing in the map.");
        var previous = owner.getThreads().enterForeign(ForeignSafety.UNSAFE);
        try { return interop.execute(function, arguments); }
        catch (Exception failure) { throw rethrow(failure); }
        finally { owner.getThreads().leaveForeign(previous); Reference.reachabilityFence(arguments); }
    }
    // These run inside the caller's foreign activation. In particular, capturing
    // errno must precede pointer reconciliation and Loom guest readmission.
    @TruffleBoundary void seedErrno() {
        var owner = current();
        if (!alive.isValid()) throw fault("Package C library registry is closed");
        pointerOperations.run();
        var functions = await(pointerOperations);
        int value = Math.toIntExact(owner.getStdio().errno());
        try { interop.execute(functions.get("set_errno"), value); }
        catch (Exception failure) { throw rethrow(failure); }
    }
    @TruffleBoundary void captureErrno() {
        var owner = current();
        try {
            int value = interop.asInt(interop.execute(await(pointerOperations).get("errno")));
            owner.getStdio().captureForeignErrno(value);
        } catch (Exception failure) { throw rethrow(failure); }
    }
    @TruffleBoundary void free(PackageReturnedAddress address) {
        nativeAllocation("free", address.transport());
    }
    @TruffleBoundary ManagedAddress realloc(PackageReturnedAddress address, long size) {
        Object result = nativeAllocation("realloc", address.transport(), size);
        return interop.isNull(result) ? ManagedAddress.nullAddress()
            : ManagedAddress.fromReturnedAddress(new PackageReturnedAddress(current(), alive, result, null));
    }
    private Object nativeAllocation(String operation, Object... arguments) {
        var owner = current();
        if (!alive.isValid()) throw fault("Package C library registry is closed");
        pointerOperations.run();
        Object function = await(pointerOperations).get(operation);
        var previous = owner.getThreads().enterForeign(ForeignSafety.UNSAFE);
        try {
            seedErrno();
            try { return Calls.interop(interop, function, arguments); }
            finally { captureErrno(); }
        } catch (Exception failure) { throw rethrow(failure); }
        finally { owner.getThreads().leaveForeign(previous); Reference.reachabilityFence(arguments); }
    }
    @TruffleBoundary public Object transport(ManagedAddress address) {
        var owner = current();
        var returned = address.returnedAddress();
        if (returned != null) return returned.transport();
        if (address == ManagedAddress.nullAddress()) return new PackageNativePointer(0, null);
        if (address.stableHandle() != null) return owner.getStablePointers().nativeTransport(address);
        address.requireByteRegion(0, false);
        if (address.hasNativeStorage() || address.nativeImageKey() != null)
            return new PackageNativePointer(address.toNativeBits(), null);
        return pointer(new CbitsBuffer(address.cbitsBuffer(), address.cbitsWritable(),
            () -> address.cbitsSize(), 0, null, null, address.cbitsStorageKey()), address.cbitsOffset());
    }
    @TruffleBoundary public Object comparisonTransport(ManagedAddress address) {
        current();
        Long bits = address.numericBits();
        return bits != null ? new PackageNativePointer(bits, null) : transport(address);
    }
    @TruffleBoundary public Long managedAliasOffset(Object result, Object argument, long offset, long size) {
        current();
        long relative;
        try { relative = interop.asLong(memory("difference", result, argument)); }
        catch (AbstractTruffleException failure) { return null; }
        catch (Exception failure) { throw rethrow(failure); }
        long absolute;
        try { absolute = Math.addExact(offset, relative); } catch (ArithmeticException failure) { return null; }
        if (absolute < 0 || absolute > size) return null;
        try { return interop.asInt(memory("equal", result, pointer(argument, relative))) != 0 ? relative : null; }
        catch (Exception failure) { throw rethrow(failure); }
    }
    @TruffleBoundary public PackageScalarFunction resolve(PackageScalarLink link, PackageScalarSignature signature) {
        var owner = current();
        Loaded selected;
        synchronized (this) {
            if (closed) throw fault("Package C library registry is closed");
            selected = libraries.get(link.getUnit());
            if (selected == null) throw fault("Unlinked package C component: " + link.getUnit());
            if (!selected.link().same(link) || !selected.link().getAbi().contains(signature))
                throw fault("Package C call differs from its registered component ABI");
        }
        if (initializing.get().contains(selected.link())) {
            // BUILD_SCOPES/SYMBOLS precede INIT_MODULE. Only this loader's
            // synchronous callback can use those already initialized symbols;
            // unrelated threads must await the completed component instead.
            try {
                var scope = env.getScopeInternal(env.getInternalLanguages().get("llvm"));
                Object function = interop.readMember(scope, signature.getEntry());
                if (!interop.isExecutable(function)) throw fault("Initializing package C entry is not executable");
                return new PackageScalarFunction(owner, signature, function, alive);
            } catch (com.oracle.truffle.api.interop.InteropException failure) { throw rethrow(failure); }
        }
        selected.task().run();
        var function = await(selected.task()).get(signature.getEntry());
        if (function == null) throw new java.util.NoSuchElementException("Key " + signature.getEntry() + " is missing in the map.");
        return function;
    }
    @TruffleBoundary public CFinalizerFunction finalizer(String symbol) {
        var owner = current();
        Loaded[] declared;
        synchronized (this) {
            if (closed) throw fault("Package C library registry is closed");
            declared = libraries.values().toArray(Loaded[]::new);
        }
        // Only the loader's synchronous callback may see a component before
        // INIT_MODULE returns. Its function symbols are already initialized.
        for (var selected : declared) {
            var link = selected.link();
            boolean matches = link.getAbi().stream().anyMatch(signature ->
                link.getFinalizers().contains(signature.getEntry()) && signature.getSymbol().equals(symbol));
            if (!matches) continue;
            if (initializing.get().contains(link)) {
                var functions = new HashMap<String, PackageScalarFunction>();
                for (var signature : link.getAbi()) if (link.getFinalizers().contains(signature.getEntry()))
                    functions.put(signature.getEntry(), resolve(link, signature));
                finalizers.register(link, functions, owner.cbits());
            } else if (!selected.task().isDone()) {
                // A constructor may demand a different declared component.
                // Use normal loading/awaiting; never retry a failed component.
                link(link);
            }
        }
        return finalizers.resolve(symbol);
    }
    /** Resolve an original declaration owner, or an unambiguous old C label.
     * The address thunk takes no arguments and returns the genuine LLVM/native
     * global, without inventing extent, writable storage or deallocation rights. */
    @TruffleBoundary public ManagedAddress dataAddress(String unit, String symbol) {
        current();
        PackageScalarCall selected = null;
        synchronized (this) {
            if (closed) throw fault("Package C library registry is closed");
            for (var library : libraries.values()) {
                var link = library.link();
                if (unit != null && !unit.equals(link.getUnit())) continue;
                for (var signature : link.getAbi()) if (link.getDataSymbols().contains(signature.getEntry()) && signature.getSymbol().equals(symbol)) {
                    if (selected != null) throw fault("Ambiguous native data label: " + symbol);
                    selected = new PackageScalarCall(link, signature);
                }
            }
        }
        if (selected == null) throw fault("Unlinked native data label: " + (unit == null ? "" : unit + ":") + symbol);
        return address(selected);
    }
    @TruffleBoundary public ManagedAddress address(PackageScalarCall addressThunk) {
        var owner = current();
        if (addressThunk.getArguments().length != 0 || !addressThunk.getResult().equals("AddrRep"))
            throw fault("Native address thunk requires a nullary address ABI");
        var function = resolve(addressThunk.getLink(), addressThunk.getSignature());
        var previous = owner.getThreads().enterForeign(ForeignSafety.UNSAFE);
        try {
            Object address = interop.execute(function.getReceiver());
            return ManagedAddress.fromReturnedAddress(new PackageReturnedAddress(owner, alive, address, null));
        } catch (Exception failure) { throw rethrow(failure); }
        finally { owner.getThreads().leaveForeign(previous); }
    }
    public synchronized void close() { closed = true; alive.invalidate(); finalizers.close(); libraries.clear(); }
    @SuppressWarnings("unchecked") private static <E extends Throwable> RuntimeException rethrow(Throwable failure) throws E { throw (E) failure; }
}
