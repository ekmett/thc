// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.TruffleSafepoint;
import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.source.Source;
import org.graalvm.polyglot.io.ByteSequence;
import com.oracle.truffle.llvm.runtime.LLVMContext;
import com.oracle.truffle.llvm.runtime.NativeContextExtension;
import java.lang.foreign.MemorySegment;
import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import thc.ForeignBitcode;
import thc.Language;
import static thc.runtime.RuntimeFault.fault;

/** Context-owned original C code and checked managed/native allocation views. */
public final class SulongCbits {
    public record CapiResult(long value, long errno) {
        public long getValue() { return value; }
        public long getErrno() { return errno; }
    }
    private record Key(String unit, String name) {}
    private record ForeignLoad(ForeignBitcode declaration, FutureTask<Void> task) {}
    private final TruffleLanguage.Env env;
    private final InteropLibrary interop = InteropLibrary.getUncached();
    private final FutureTask<Object> iconvTask;
    private final FutureTask<Object> waitStatusTask;
    private final CFinalizerFunction ownedFree = new CFinalizerFunction(this, "free", null);
    private final WeakHashMap<Object, WeakReference<CbitsBuffer>> buffers = new WeakHashMap<>();
    private final ConcurrentHashMap<Key, Object> foreign = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Key, Object> foreignErrno = new ConcurrentHashMap<>();
    private final HashMap<Key, ForeignLoad> linkedForeign = new HashMap<>();
    private final HashMap<Key, ForeignLoad> foreignOwners = new HashMap<>();
    private final ThreadLocal<Set<Key>> initializingForeign = ThreadLocal.withInitial(SulongCbits::newInitializingForeignSet);
    @TruffleBoundary private static Set<Key> newInitializingForeignSet() { return new java.util.HashSet<>(); }

    public SulongCbits(TruffleLanguage.Env env) {
        this.env = env;
        try (var stream = SulongCbits.class.getResourceAsStream("/thc/cbits/manifest.json")) {
            if (stream == null) throw fault("Missing original C build manifest");
            var manifest = (Map<?, ?>) thc.Json.parse(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
            String os = System.getProperty("os.name");
            String system = os.startsWith("Mac") ? "Darwin" : os.startsWith("Windows") ? "Windows" : os;
            if (!system.equals(manifest.get("system")) || !architecture((String) manifest.get("architecture")).equals(architecture(System.getProperty("os.arch"))))
                throw fault("Original C bitcode does not match this runtime platform");
        } catch (Exception failure) { throw rethrow(failure); }
        iconvTask = new FutureTask<>(() -> {
            if (System.getProperty("os.name").startsWith("Mac")) {
                // Darwin supplies iconv outside libc. Keep its ordinary native
                // linkage in Sulong's context registry, not the global namespace.
                env.initializeLanguage(env.getInternalLanguages().get("llvm"));
                var nativeContext = LLVMContext.get(null).getContextExtensionOrNull(NativeContextExtension.class);
                if (nativeContext == null) throw fault("Sulong native library loading is unavailable");
                Object handle = env.parseInternal(Source.newBuilder("nfi",
                    "load(RTLD_LAZY|RTLD_LOCAL) \"/usr/lib/libiconv.2.dylib\"", "iconv-native").build()).call();
                nativeContext.addLibraryHandles(handle);
            }
            return load("iconv");
        });
        waitStatusTask = new FutureTask<>(() -> load("wait-status"));
    }
    private static String architecture(String value) {
        return switch (value.toLowerCase(java.util.Locale.ROOT)) { case "arm64" -> "aarch64"; case "amd64" -> "x86_64"; default -> value; };
    }
    private Object load(String name) {
        try (var stream = SulongCbits.class.getResourceAsStream("/thc/cbits/" + name + ".bc")) {
            if (stream == null) throw fault("Missing compiled original C resource: " + name);
            return env.parseInternal(Source.newBuilder("llvm", ByteSequence.create(stream.readAllBytes()), name + ".bc").build()).call();
        } catch (Exception failure) { throw rethrow(failure); }
    }
    private static <T> T await(FutureTask<T> task) {
        task.run();
        try {
            return task.isDone() ? task.get() : TruffleSafepoint.setBlockedThreadInterruptibleFunction(null,
                (TruffleSafepoint.InterruptibleFunction<FutureTask<T>, T>) pending -> {
                    try { return pending.get(); } catch (ExecutionException failure) { throw rethrow(failure); }
                }, task);
        } catch (ExecutionException failure) { throw rethrow(failure.getCause() != null ? failure.getCause() : failure); }
        catch (InterruptedException failure) { throw rethrow(failure); }
    }
    public long waitStatus(OriginalStdioOp operation, int status) {
        if (!System.getProperty("os.name").equals("Linux") || !Set.of("amd64", "x86_64").contains(System.getProperty("os.arch")))
            throw fault("Original unix wait status currently requires Linux x86_64");
        try {
            Object result = interop.execute(interop.readMember(await(waitStatusTask), "thc_wait_" + operation.name()), status);
            if (!interop.fitsInInt(result)) throw fault("Original unix wait-status result is not CInt");
            return interop.asInt(result);
        } catch (Exception failure) { throw rethrow(failure); }
    }
    public ManagedAddress finalizerLabel(String symbol) {
        var function = symbol.equals("free") ? ownedFree :
            Language.currentState(null).getPackageCbits().finalizer(symbol);
        if (function == null) return Language.currentState(null).getPackageCbits().dataAddress(null, symbol);
        return function.getAddress();
    }
    /** Only this live context's canonical label can shed its explicit callback roots. */
    boolean isOwnedFree(CFinalizerFunction function) {
        return function == ownedFree && Language.currentState(null).cbits() == this;
    }
    public void invokeFinalizer(CFinalizerFunction function, ManagedAddress address) {
        function.requireOwner(this);
        if (Language.currentState(null).cbits() != this) throw fault("C finalizer belongs to another THC context");
        if (function.getPackageFunction() != null) {
            try { PackageFinalizerRegistry.invoke(function.getPackageFunction(), address); }
            catch (Exception failure) { throw rethrow(failure); }
            return;
        }
        if (function.getSymbol().equals("free")) { Language.currentState(null).getNativeAllocations().free(address); return; }
        throw fault("Unsupported C finalizer");
    }
    public Object iconvLibrary() {
        String os = System.getProperty("os.name");
        if (!os.equals("Linux") && !os.startsWith("Mac")) throw fault("Original native iconv requires the Linux GNU or Darwin LP64 host ABI");
        return await(iconvTask);
    }
    private static boolean sameLink(ForeignBitcode first, ForeignBitcode second) {
        return first.getUnit().equals(second.getUnit()) && first.getModule().equals(second.getModule())
            && first.getTarget().equals(second.getTarget()) && first.getSymbols().equals(second.getSymbols())
            && first.getAbi().equals(second.getAbi()) && Arrays.equals(first.getBytes(), second.getBytes());
    }
    private Language.State currentForeignOwner() {
        var owner = Language.currentState(null);
        if (owner.getEnv() != env) throw fault("CAPI library belongs to another THC context");
        if (!env.isNativeAccessAllowed()) throw fault("CAPI library requires native access");
        return owner;
    }
    /** Reserve the exact library and all symbol owners before native effects. */
    public void declare(ForeignBitcode record) { declaration(record); }
    private synchronized ForeignLoad declaration(ForeignBitcode record) {
        currentForeignOwner();
        var key = new Key(record.getUnit(), record.getModule());
        var previous = linkedForeign.get(key);
        if (previous != null) {
            if (!sameLink(previous.declaration(), record)) throw new IllegalArgumentException("Conflicting CAPI library identity");
            return previous;
        }
        for (String symbol : record.getSymbols())
            if (foreignOwners.containsKey(new Key(record.getUnit(), symbol))) throw new IllegalArgumentException("Conflicting CAPI symbol owner");
        var selected = new ForeignLoad(record, new FutureTask<>(() -> { initializeForeign(record); return null; }));
        linkedForeign.put(key, selected);
        for (String symbol : record.getSymbols()) foreignOwners.put(new Key(record.getUnit(), symbol), selected);
        return selected;
    }
    public void link(ForeignBitcode record) { awaitForeign(declaration(record)); }
    private void awaitForeign(ForeignLoad selected) {
        var record = selected.declaration();
        if (initializingForeign.get().contains(new Key(record.getUnit(), record.getModule())))
            throw fault("Recursive original CAPI initialization: " + record.getUnit() + ":" + record.getModule());
        await(selected.task());
    }
    private void initializeForeign(ForeignBitcode record) {
        var owner = currentForeignOwner();
        var previous = owner.getThreads().enterForeign(ForeignSafety.SAFE);
        var key = new Key(record.getUnit(), record.getModule());
        initializingForeign.get().add(key);
        try {
            owner.getPackageCbits().registerCallbacks(owner);
            Object library = env.parseInternal(Source.newBuilder("llvm", ByteSequence.create(record.getBytes()),
                record.getUnit() + "-" + record.getModule() + ".bc").build()).call();
            var resolved = new HashMap<String, Object>();
            for (String symbol : record.getSymbols()) {
                if (!interop.isMemberReadable(library, symbol)) throw new IllegalArgumentException("Missing compiled original CAPI symbol: " + symbol);
                resolved.put(symbol, interop.readMember(library, symbol));
            }
            if (!interop.isMemberReadable(library, "thc_capi_errno")) throw new IllegalArgumentException("CAPI library lacks errno bridge");
            Object errno = interop.readMember(library, "thc_capi_errno");
            for (var entry : resolved.entrySet()) {
                var symbolKey = new Key(record.getUnit(), entry.getKey());
                foreign.put(symbolKey, entry.getValue()); foreignErrno.put(symbolKey, errno);
            }
        } catch (Exception failure) { throw rethrow(failure); }
        finally { initializingForeign.get().remove(key); owner.getThreads().leaveForeign(previous); }
    }
    private void requireForeign(String unit, String symbol) {
        currentForeignOwner();
        ForeignLoad selected;
        synchronized (this) { selected = foreignOwners.get(new Key(unit, symbol)); }
        if (selected == null) throw fault("Unlinked original CAPI target: " + unit + ":" + symbol);
        awaitForeign(selected);
    }
    private Object foreignFunction(String unit, String symbol) {
        requireForeign(unit, symbol);
        Object function = foreign.get(new Key(unit, symbol));
        if (function == null) throw fault("Unlinked original CAPI target: " + unit + ":" + symbol);
        return function;
    }
    public long capiZero(String unit, String symbol) { return capiZero(unit, symbol, false); }
    public long capiZero(String unit, String symbol, boolean timeClock) {
        try {
            Object value = interop.execute(foreignFunction(unit, symbol));
            if (timeClock) {
                if (!interop.fitsInInt(value)) throw fault("CAPI clock identifier is not a CInt: " + symbol);
                return interop.asInt(value);
            }
            if (!interop.fitsInLong(value)) throw fault("CAPI result is not a machine word: " + symbol);
            return interop.asLong(value);
        } catch (Exception failure) { throw rethrow(failure); }
    }
    public CapiResult capiWordAddress(String unit, String symbol, long word, ManagedAddress address) {
        return capiWordAddress(new CapiCall(unit, symbol, false), word, address);
    }
    public CapiResult capiWordAddress(CapiCall call, long word, ManagedAddress address) {
        Object clock;
        if (call.timeClock()) {
            int value = PackageScalarAccess.packageScalarInt32(word);
            if (value < -1) throw fault("Dynamic time clock IDs require an owned descriptor or guest CPU-clock translation");
            clock = value;
        } else clock = word;
        if (call.timeClock() && call.resolution() && address == ManagedAddress.nullAddress()) {
            try {
                Object result = interop.execute(foreignFunction(call.unit(), call.symbol()), clock, new NativeLimbScope.Pointer(MemorySegment.NULL, 0, null));
                if (!interop.fitsInInt(result)) throw fault("CAPI result is not a CInt: " + call.symbol());
                long value = interop.asInt(result);
                if (value != 0 && value != -1) throw fault("CAPI clock status is outside the POSIX result domain");
                return new CapiResult(value, value < 0 ? capiErrno(call.unit(), call.symbol()) : 0);
            } catch (Exception failure) { throw rethrow(failure); }
        }
        Supplier<CapiResult> invoke = () -> {
            address.requireByteRegion(16, true);
            if (!call.timeClock()) address.cbitsSegment();
            try (var scope = new NativeLimbScope()) {
                var owner = address.cbitsOwner();
                var pointer = !call.timeClock() && owner != null && owner.hasNativeStorage() ? scope.borrow(address, 16) : scope.allocate(16);
                Object result = interop.execute(foreignFunction(call.unit(), call.symbol()), clock, pointer);
                if (!interop.fitsInInt(result)) throw fault("CAPI result is not a CInt: " + call.symbol());
                long value = interop.asInt(result);
                if (value != 0 && value != -1) throw fault("CAPI clock status is outside the POSIX result domain");
                long errno = value < 0 ? capiErrno(call.unit(), call.symbol()) : 0;
                if (value == 0 && !pointer.aliases(address)) {
                    if (call.timeClock()) {
                        address.writeNativeScalar(0, 8, pointer.readWord(0));
                        address.writeNativeScalar(1, 8, pointer.readWord(1));
                    } else pointer.copyTo(address.cbitsSegment(), address.cbitsOffset(), 16);
                }
                return new CapiResult(value, errno);
            } catch (Exception failure) { throw rethrow(failure); }
        };
        return address.withNativeBorrow(() -> {
            var owner = address.cbitsOwner();
            if (owner == null) return invoke.get();
            synchronized (owner) { return invoke.get(); }
        });
    }
    public long capiErrno(String unit, String symbol) {
        requireForeign(unit, symbol);
        Object function = foreignErrno.get(new Key(unit, symbol));
        if (function == null) throw fault("No linked CAPI errno domain");
        try {
            Object value = interop.execute(function);
            if (!interop.fitsInInt(value)) throw fault("CAPI errno is not a CInt");
            return interop.asInt(value);
        } catch (Exception failure) { throw rethrow(failure); }
    }
    public CbitsBuffer buffer(ManagedAddress address) { return buffer(address, true); }
    public synchronized CbitsBuffer buffer(ManagedAddress address, boolean allowNativePointer) {
        var bytes = address.cbitsBuffer();
        var owner = address.cbitsOwner();
        Object key = address.cbitsStorageKey();
        if (!allowNativePointer) return new CbitsBuffer(bytes, address.cbitsWritable(), () -> address.cbitsSize());
        var reference = buffers.get(key);
        var buffer = reference == null ? null : reference.get();
        if (buffer != null) return buffer;
        Supplier<NativeReadOnlyPointer> image = address.nativeImageKey() == null ? null : () -> {
            var registry = NativeAddresses.current(null); registry.project(address);
            var pointer = registry.transport(address);
            if (pointer == null) throw fault("Missing immutable native image"); return pointer;
        };
        LongSupplier nativeAddress = owner != null && owner.hasNativeStorage() ? () -> address.toNativeBits() - address.cbitsOffset() : null;
        buffer = new CbitsBuffer(bytes, address.cbitsWritable(), () -> address.cbitsSize(), 0, image, nativeAddress);
        buffers.put(key, new WeakReference<>(buffer)); return buffer;
    }
    public CbitsBuffer pointerTransport(ManagedAddress address) { return pointerTransport(address, true); }
    public CbitsBuffer pointerTransport(ManagedAddress address, boolean allowNativePointer) {
        address.requireByteRegion(0, false);
        Supplier<NativeReadOnlyPointer> image = !allowNativePointer || address.nativeImageKey() == null ? null : () -> {
            var registry = NativeAddresses.current(null); registry.project(address);
            var pointer = registry.transport(address);
            if (pointer == null) throw fault("Missing immutable callback pointer image"); return pointer;
        };
        var owner = address.cbitsOwner();
        LongSupplier nativeAddress = allowNativePointer && owner != null && owner.hasNativeStorage() ? () -> address.toNativeBits() - address.cbitsOffset() : null;
        return new CbitsBuffer(address.cbitsBuffer(), address.cbitsWritable(),
            () -> address.cbitsSize(), address.cbitsOffset(), image, nativeAddress);
    }
    public static SulongCbits current(Node node) { return Language.currentState(node).cbits(); }
    @SuppressWarnings("unchecked") private static <E extends Throwable> RuntimeException rethrow(Throwable failure) throws E { throw (E) failure; }
}
