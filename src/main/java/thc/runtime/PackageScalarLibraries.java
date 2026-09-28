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
import org.graalvm.polyglot.io.ByteSequence;
import thc.Language;
import thc.PackageScalarLink;
import thc.PackageScalarSignature;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.lang.ref.Reference;
import static thc.runtime.RuntimeFault.fault;

/** Component C globals and entrypoints belong to one Truffle context. */
public final class PackageScalarLibraries {
    private record Loaded(PackageScalarLink link, FutureTask<Map<String, PackageScalarFunction>> task) {}
    private final TruffleLanguage.Env env;
    private final HashMap<String, Loaded> libraries = new HashMap<>();
    private final PackageFinalizerRegistry finalizers = new PackageFinalizerRegistry();
    private final Assumption alive = Assumption.create("THC package C libraries are open");
    private boolean closed;
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
                    "read_address", "write_address", "strlen", "copy", "fill", "errno", "set_errno"})
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
    @TruffleBoundary public void link(PackageScalarLink link) {
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
                }));
                libraries.put(link.getUnit(), selected);
            }
        }
        selected.task().run();
        await(selected.task());
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
        current();
        Loaded selected;
        synchronized (this) {
            if (closed) throw fault("Package C library registry is closed");
            selected = libraries.get(link.getUnit());
            if (selected == null) throw fault("Unlinked package C component: " + link.getUnit());
            if (!selected.link().same(link) || !selected.link().getAbi().contains(signature))
                throw fault("Package C call differs from its registered component ABI");
        }
        var function = await(selected.task()).get(signature.getEntry());
        if (function == null) throw new java.util.NoSuchElementException("Key " + signature.getEntry() + " is missing in the map.");
        return function;
    }
    @TruffleBoundary public CFinalizerFunction finalizer(String symbol) { current(); return finalizers.resolve(symbol); }
    public synchronized void close() { closed = true; alive.invalidate(); finalizers.close(); libraries.clear(); }
    @SuppressWarnings("unchecked") private static <E extends Throwable> RuntimeException rethrow(Throwable failure) throws E { throw (E) failure; }
}
