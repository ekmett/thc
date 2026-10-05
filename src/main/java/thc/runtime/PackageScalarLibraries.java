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
import com.oracle.truffle.llvm.runtime.SulongLibrary;
import org.graalvm.polyglot.io.ByteSequence;
import thc.Language;
import thc.PackageScalarLink;
import thc.PackageNativeComponent;
import thc.PackageScalarSignature;
import java.util.HashMap;
import java.util.Map;
import java.util.ArrayDeque;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.lang.ref.Reference;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.HashSet;
import static thc.runtime.RuntimeFault.fault;

/** Component C globals and entrypoints belong to one Truffle context. */
public final class PackageScalarLibraries {
    private record Bundled(PackageNativeComponent.BundledLibrary library, FutureTask<Object> task) {}
    private record Loaded(PackageNativeComponent component, FutureTask<Object> task, Map<String, PackageScalarFunction> functions) {}
    private final TruffleLanguage.Env env;
    private final HashMap<String, Loaded> libraries = new HashMap<>();
    private final HashMap<String, Bundled> bundledLibraries = new HashMap<>();
    private final ThreadLocal<HashSet<String>> initializingBundled = ThreadLocal.withInitial(HashSet::new);
    private final HashMap<String, PackageScalarLink> declarations = new HashMap<>();
    private final HashMap<String, FutureTask<PackageScalarFunction>> adapters = new HashMap<>();
    private final ThreadLocal<HashSet<String>> initializingAdapters = ThreadLocal.withInitial(HashSet::new);
    private final PackageFinalizerRegistry finalizers = new PackageFinalizerRegistry();
    private final Assumption alive = Assumption.create("THC package C libraries are open");
    private boolean closed;
    private boolean callbacksRegistered;
    private final ThreadLocal<ArrayDeque<PackageNativeComponent>> initializing = ThreadLocal.withInitial(ArrayDeque::new);
    private final ThreadLocal<ArrayDeque<String>> awaitingDependencies = ThreadLocal.withInitial(ArrayDeque::new);
    private final InteropLibrary interop = InteropLibrary.getUncached();
    private final FutureTask<Map<String, Object>> pointerOperations;
    private final FutureTask<Object> floatingRuntime;
    public PackageScalarLibraries(TruffleLanguage.Env env) {
        this.env = env;
        floatingRuntime = new FutureTask<>(() -> {
            String system = System.getProperty("os.name");
            boolean windows = system.startsWith("Windows");
            String suffix = windows ? ".dll" : system.startsWith("Mac") ? ".dylib" : ".so";
            var file = Files.createTempFile("thc-rts-float-", suffix);
            try {
                try (var stream = PackageScalarLibraries.class.getResourceAsStream("/thc/cbits/rts-float" + suffix)) {
                    if (stream == null) throw fault("Missing original RTS floating support");
                    Files.copy(stream, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
                String path = file.toString().replace("\\", "\\\\").replace("\"", "\\\"");
                return env.parseInternal(Source.newBuilder("nfi",
                    (windows ? "load " : "load(RTLD_LAZY|RTLD_LOCAL) ") + "\"" + path + "\"", "rts-float").build()).call();
            } finally {
                if (windows) file.toFile().deleteOnExit();
                else Files.deleteIfExists(file);
            }
        });
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
        current();
        var graph = new java.util.LinkedHashMap<String, PackageNativeComponent>();
        if (link.getComponent() != null) collect(link.getComponent(), graph, new java.util.HashSet<>());
        synchronized (this) {
            if (closed) throw fault("Package C library registry is closed");
            var old = declarations.get(link.getUnit());
            if (old != null && !old.same(link)) throw fault("Conflicting package C component ABI: " + link.getUnit());
            var selectedBundled = new HashMap<String, PackageNativeComponent.BundledLibrary>();
            for (var entry : bundledLibraries.values()) selectedBundled.put(entry.library().name(), entry.library());
            var identities = new HashMap<String, String>();
            for (var entry : libraries.values()) identities.put(entry.component().componentSha256(), entry.component().unit());
            for (var component : graph.values()) {
                var owner = identities.putIfAbsent(component.componentSha256(), component.unit());
                if (owner != null && !owner.equals(component.unit()))
                    throw fault("Package C entry namespace belongs to another unit: " + component.componentSha256());
                if (!component.bundledLibraries().isEmpty() && !System.getProperty("os.name").equals("Linux"))
                    throw fault("Bundled native libraries require Linux");
                for (var library : component.bundledLibraries()) {
                    var prior = selectedBundled.putIfAbsent(library.name(), library);
                    if (prior != null && !prior.same(library))
                        throw fault("Conflicting bundled native library: " + library.name());
                }
                var selected = libraries.get(component.unit());
                if (selected != null && !selected.component().same(component))
                    throw fault("Conflicting package C component identity: " + component.unit());
            }
            for (var library : selectedBundled.values()) bundledLibraries.computeIfAbsent(library.name(), ignored ->
                new Bundled(library, new FutureTask<>(() -> loadNative(library.bytes(), ".so", "package-provider:" + library.name()))));
            for (var component : graph.values()) libraries.computeIfAbsent(component.unit(), ignored ->
                new Loaded(component, new FutureTask<>(() -> initialize(component)), new HashMap<>()));
            declarations.putIfAbsent(link.getUnit(), link);
            return libraries.get(link.getUnit());
        }
    }
    private static void collect(PackageNativeComponent component, Map<String, PackageNativeComponent> graph, java.util.Set<String> path) {
        if (!path.add(component.unit())) throw fault("Cyclic package C dependency: " + component.unit());
        var old = graph.putIfAbsent(component.unit(), component);
        if (old != null && !old.same(component)) throw fault("Conflicting package C dependency: " + component.unit());
        if (old == null) for (var dependency : component.dependencies()) {
            if (!dependency.target().equals(component.target())) throw fault("Package C dependency target differs");
            collect(dependency, graph, path);
        }
        path.remove(component.unit());
    }
    private Object initialize(PackageNativeComponent component) throws Exception {
        // Dependency components have their own context-owned state,
        // not another copy embedded in each consumer's LLVM module.
        var pending = awaitingDependencies.get(); pending.push(component.unit());
        try { for (var dependency : component.dependencies()) load(dependency); }
        finally { pending.pop(); }
        var owner = current();
        // Constructors may call their declared Haskell exports.
        // Keep callback entry on this native origin, also in Loom;
        // Sulong holds its reentrant context monitor during loading.
        var previous = owner.getThreads().enterForeign(ForeignSafety.SAFE);
        var stack = initializing.get(); stack.push(component);
        try {
            registerCallbacks(owner);
            for (var library : component.bundledLibraries()) loadBundled(library);
            if (component.nativeLibrary().length != 0) {
                boolean windows = System.getProperty("os.name").startsWith("Windows");
                loadNative(component.nativeLibrary(), windows ? ".dll" :
                    component.format().equals("llvm-embedded-mach-o") ? ".dylib" : ".so", "package-native");
            }
            Object library = env.parseInternal(Source.newBuilder("llvm", ByteSequence.create(component.bytes()),
                component.componentSha256() + switch (component.format()) {
                    case "llvm-embedded-elf" -> ".so";
                    case "llvm-embedded-mach-o" -> ".dylib";
                    default -> ".bc";
                }).build()).call();
            if (!component.exports().isEmpty()) {
                if (!(library instanceof SulongLibrary loaded)) throw fault("Missing package C provider scope");
                var scope = LLVMContext.get(null).getGlobalScopeChain();
                while (scope != null && !scope.getId().equals(loaded.getBitcodeID())) scope = scope.getNext();
                if (scope == null) throw fault("Missing package C provider scope: " + component.unit());
                // SulongLibrary's interop view exposes only functions. The
                // module's public scope includes its defined data and aliases,
                // without borrowing an imported name from another provider.
                for (String symbol : component.exports()) if (!scope.getScope().contains(symbol))
                    throw fault("Missing package C provider export: " + symbol);
            }
            return library;
        } finally { stack.pop(); owner.getThreads().leaveForeign(previous); }
    }
    private Object loadNative(byte[] bytes, String suffix, String name) throws Exception {
        var file = Files.createTempFile("thc-package-native-", suffix);
        boolean windows = System.getProperty("os.name").startsWith("Windows");
        try {
            Files.write(file, bytes);
            // Keep unused native references lazy and handles in Sulong's context registry.
            env.initializeLanguage(env.getInternalLanguages().get("llvm"));
            var nativeContext = LLVMContext.get(null).getContextExtensionOrNull(NativeContextExtension.class);
            if (nativeContext == null) throw fault("Sulong native library loading is unavailable");
            String path = file.toString().replace("\\", "\\\\").replace("\"", "\\\"");
            Object handle = env.parseInternal(Source.newBuilder("nfi",
                (windows ? "load " : "load(RTLD_LAZY|RTLD_LOCAL) ") + "\"" + path + "\"", name).build()).call();
            nativeContext.addLibraryHandles(handle);
            return handle;
        } finally {
            // Windows keeps loaded DLLs locked; Unix mappings survive unlink.
            if (windows) file.toFile().deleteOnExit();
            else Files.deleteIfExists(file);
        }
    }
    private void loadBundled(PackageNativeComponent.BundledLibrary library) {
        Bundled selected;
        synchronized (this) {
            if (closed) throw fault("Package C library registry is closed");
            selected = bundledLibraries.get(library.name());
        }
        if (selected == null || !selected.library().same(library)) throw fault("Undeclared bundled native library: " + library.name());
        var pending = initializingBundled.get();
        if (!pending.add(library.name())) throw fault("Bundled native library is awaiting initialization: " + library.name());
        try { selected.task().run(); await(selected.task()); }
        finally { pending.remove(library.name()); }
    }
    @TruffleBoundary public void link(PackageScalarLink link) {
        var selected = declaration(link);
        if (initializing(link.getUnit())) return;
        if (selected != null) load(selected.component());
        var entries = new HashMap<String, PackageScalarFunction>();
        for (var signature : link.getAbi()) if (!link.getCallSeeds().containsKey(signature.getEntry()))
            entries.put(signature.getEntry(), resolve(link, signature));
        if (!link.getFinalizers().isEmpty()) finalizers.register(link, entries, current().cbits());
    }
    private Object load(PackageNativeComponent component) {
        // A provider constructor can call Haskell, which can ask for its still
        // pending consumer. That consumer has no initialized native symbols;
        // waiting for its own FutureTask would deadlock this native origin.
        if (awaitingDependencies.get().contains(component.unit()))
            throw fault("Package C component is awaiting native dependencies: " + component.unit());
        Loaded selected;
        synchronized (this) {
            if (closed) throw fault("Package C library registry is closed");
            selected = libraries.get(component.unit());
        }
        if (selected == null || !selected.component().same(component)) throw fault("Undeclared package C dependency: " + component.unit());
        selected.task().run();
        return await(selected.task());
    }
    private boolean initializing(String unit) {
        for (var component : initializing.get()) if (component.unit().equals(unit)) return true;
        return false;
    }
    void registerCallbacks(Language.State owner) {
        synchronized (this) { if (callbacksRegistered) return; }
        env.initializeLanguage(env.getInternalLanguages().get("llvm"));
        var nativeContext = LLVMContext.get(null).getContextExtensionOrNull(NativeContextExtension.class);
        if (nativeContext == null) throw fault("Sulong native library loading is unavailable");
        synchronized (this) {
            if (!callbacksRegistered) {
                floatingRuntime.run();
                nativeContext.addLibraryHandles(await(floatingRuntime));
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
            var declared = declarations.get(link.getUnit());
            if (declared == null || !declared.same(link) || !declared.getAbi().contains(signature))
                throw fault("Package C call differs from its registered component ABI");
            signature = declared.getAbi().get(declared.getAbi().indexOf(signature));
        }
        var seed = link.getCallSeeds().get(signature.getEntry());
        if (seed != null) return resolveAdapter(link, signature, seed);
        if (selected == null) throw fault("Unlinked package C component: " + link.getUnit());
        if (initializing(link.getUnit())) {
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
        Object library = load(selected.component());
        synchronized (this) {
            var functions = selected.functions();
            var function = functions.get(signature.getEntry());
            if (function != null) return function;
            try {
                Object receiver = interop.readMember(library, signature.getEntry());
                if (!interop.isExecutable(receiver)) throw fault("Package C entry is not executable");
                function = new PackageScalarFunction(owner, signature, receiver, alive);
                functions.put(signature.getEntry(), function);
                return function;
            } catch (com.oracle.truffle.api.interop.InteropException failure) { throw rethrow(failure); }
        }
    }
    private PackageScalarFunction resolveAdapter(PackageScalarLink link, PackageScalarSignature signature, PackageScalarLink.CallSeed seed) {
        String key = link.getUnit() + "\0" + signature.getEntry();
        if (initializingAdapters.get().contains(key)) throw fault("Package C adapter is already being initialized: " + signature.getEntry());
        // A synchronous constructor callback gets an early receiver, not the
        // canonical task observed by other guest threads after initialization.
        if (seed.providerUnit() != null && initializing(seed.providerUnit())) {
            try { return initializeAdapter(link, signature, seed); }
            catch (Exception failure) { throw rethrow(failure); }
        }
        FutureTask<PackageScalarFunction> task;
        synchronized (this) {
            if (closed) throw fault("Package C library registry is closed");
            task = adapters.computeIfAbsent(key, ignored -> new FutureTask<>(() -> {
                var pending = initializingAdapters.get(); pending.add(key);
                try { return initializeAdapter(link, signature, seed); } finally { pending.remove(key); }
            }));
        }
        task.run();
        return await(task);
    }
    private PackageScalarFunction initializeAdapter(PackageScalarLink link, PackageScalarSignature signature, PackageScalarLink.CallSeed seed) throws Exception {
        var owner = current();
        Loaded provider;
        synchronized (this) {
            if (closed) throw fault("Package C library registry is closed");
            provider = seed.providerUnit() == null ? null : libraries.get(seed.providerUnit());
        }
        if (provider == null || !provider.component().componentSha256().equals(seed.providerComponentSha256()) ||
                !provider.component().exports().contains(seed.providerSymbol()))
            throw fault("Missing package C provider on use: " + link.getUnit() + ":" + signature.getSymbol());
        if (!initializing(seed.providerUnit())) load(provider.component());
        byte[] bytes = HexFormat.of().parseHex(seed.bitcodeHex());
        if (!HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)).equals(seed.bitcodeSha256()))
            throw fault("Package C call seed digest differs");
        // Only the adapter is loaded here. The provider remains the existing
        // context-owned LLVM module: no duplicate globals or constructors.
        // Capture already verified its sole entry, external and exact ABI.
        Object library = env.parseInternal(Source.newBuilder("llvm", ByteSequence.create(bytes), seed.bitcodeSha256() + ".bc").build()).call();
        Object receiver = interop.readMember(library, signature.getEntry());
        if (!interop.isExecutable(receiver)) throw fault("Package C adapter entry is not executable");
        synchronized (this) { if (closed) throw fault("Package C library registry is closed"); }
        return new PackageScalarFunction(owner, signature, receiver, alive);
    }
    @TruffleBoundary public CFinalizerFunction finalizer(String symbol) {
        var owner = current();
        PackageScalarLink[] declared;
        synchronized (this) {
            if (closed) throw fault("Package C library registry is closed");
            declared = declarations.values().toArray(PackageScalarLink[]::new);
        }
        var existing = finalizers.resolve(symbol);
        // Only the loader's synchronous callback may see a component before
        // INIT_MODULE returns. Its function symbols are already initialized.
        for (var link : declared) {
            // A canonical label retains its exact declaration, not a failed
            // competing component that happened to declare the same symbol.
            boolean matches = link.getAbi().stream().anyMatch(signature -> existing != null
                ? signature == existing.getPackageFunction().getSignature()
                : link.getFinalizers().contains(signature.getEntry()) && signature.getSymbol().equals(symbol));
            if (!matches) continue;
            if (initializing(link.getUnit())) {
                var functions = new HashMap<String, PackageScalarFunction>();
                for (var signature : link.getAbi()) if (link.getFinalizers().contains(signature.getEntry()))
                    functions.put(signature.getEntry(), resolve(link, signature));
                finalizers.register(link, functions, owner.cbits());
            } else {
                // A constructor may demand a different declared component.
                // Always observe completion, including a stored failure. Running
                // a completed FutureTask is a no-op, not an initialization retry.
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
            for (var link : declarations.values()) {
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
    public synchronized void close() {
        closed = true; alive.invalidate(); finalizers.close();
        libraries.clear(); bundledLibraries.clear(); declarations.clear(); adapters.clear();
    }
    @SuppressWarnings("unchecked") private static <E extends Throwable> RuntimeException rethrow(Throwable failure) throws E { throw (E) failure; }
}
