// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.exception.AbstractTruffleException;
import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.TruffleObject;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.oracle.truffle.api.nodes.Node;
import java.lang.ref.Reference;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import thc.Language;
import static thc.runtime.RuntimeFault.fault;

/** One adopted call site for an exact component ABI; reusable sites receive
 * their invoking program's function rather than caching a context receiver. */
public final class PackageScalarAccess extends Node {
    private final PackageScalarCall call;
    private final int functionIndex;
    @Child private ForeignExceptionAccess foreignExceptions = new ForeignExceptionAccess();
    @CompilationFinal(dimensions = 1) private final String[] argumentReps;
    @CompilationFinal private volatile PackageScalarFunction cached;
    @Child private InteropLibrary calls;
    @Child private InteropLibrary numbers;
    private final boolean pointers;
    private final boolean integerResult;
    private final boolean addressResult;
    private final boolean windowsOpening;
    private final List<ManagedAddress> noPointerArguments;

    public PackageScalarAccess(PackageScalarCall call) {
        this(call, -1);
    }
    public PackageScalarAccess(PackageScalarCall call, int functionIndex) {
        this.call = call;
        this.functionIndex = functionIndex;
        calls = functionIndex < 0 ? InteropLibrary.getFactory().createDispatched(1) : InteropLibrary.getUncached();
        numbers = functionIndex < 0 ? InteropLibrary.getFactory().createDispatched(1) : InteropLibrary.getUncached();
        argumentReps = call.getArguments().clone();
        boolean hasPointers = false;
        for (String rep : argumentReps)
            if (rep.equals("AddrRep") || rep.equals("ByteArray#") || rep.equals("MutableByteArray#")) hasPointers = true;
        pointers = hasPointers;
        integerResult = switch (call.getResult()) {
            case "Int8Rep", "Word8Rep", "Int16Rep", "Word16Rep", "Int32Rep", "Word32Rep",
                "IntRep", "WordRep", "Int64Rep", "Word64Rep" -> true;
            default -> false;
        };
        addressResult = call.getResult().equals("AddrRep");
        windowsOpening = CoreOriginalStdio.windowsOpening(call);
        noPointerArguments = List.of();
    }

    /** Original machine-word adapters retain a checked CInt boundary. */
    public static int packageScalarInt32(long value) {
        if (value != (long) (int) value) throw fault("Package C Int32 argument is out of range");
        return (int) value;
    }
    public static Object packageCInteger(String rep, int value) {
        return switch (rep) {
            case "Int8Rep" -> { if ((byte) value != value) throw fault("Package C Int8 argument is out of range"); yield (byte) value; }
            case "Word8Rep" -> { if (value < 0 || value > 255) throw fault("Package C Word8 argument is out of range"); yield (byte) value; }
            case "Int16Rep" -> { if ((short) value != value) throw fault("Package C Int16 argument is out of range"); yield (short) value; }
            case "Word16Rep" -> { if (value < 0 || value > 65535) throw fault("Package C Word16 argument is out of range"); yield (short) value; }
            case "Int32Rep", "Word32Rep" -> value;
            default -> throw fault("Package C argument is not a narrow integer");
        };
    }
    private PackageScalarFunction function(Program instance) {
        var owner = Language.currentState(null);
        var entry = functionIndex < 0 ? cached : instance.packageFunction(functionIndex);
        if (entry == null) {
            if (functionIndex >= 0) throw fault("Prepared package C function is absent");
            entry = initialize(owner);
        }
        if (entry.getOwner() != owner) throw fault("Package C call site belongs to another context");
        if (!entry.getAlive().isValid()) throw fault("Package C library registry is closed");
        return entry;
    }
    @TruffleBoundary private PackageScalarFunction initialize(Language.State owner) {
        var resolved = call.getKind() != PackageScalarCall.Kind.STATIC ? owner.getNativeCallbacks().function(call)
            : owner.getPackageCbits().resolve(call.getLink(), call.getSignature());
        synchronized (this) {
            if (cached == null) { CompilerDirectives.transferToInterpreterAndInvalidate(); cached = resolved; }
            return cached;
        }
    }
    @ExplodeLoop private PackageScalarFunction prepare(Object[] arguments, Object state, Program instance) {
        TupleResults.requireVoidCarrier(state);
        if (arguments.length != argumentReps.length) throw fault("Package C argument count mismatch");
        for (int index = 0; index < argumentReps.length; index++) {
            boolean valid = switch (argumentReps[index]) {
                case "Int8Rep", "Word8Rep" -> arguments[index] instanceof Byte;
                case "Int16Rep", "Word16Rep" -> arguments[index] instanceof Short;
                case "Int32Rep", "Word32Rep" -> arguments[index] instanceof Integer;
                case "IntRep", "WordRep", "Int64Rep", "Word64Rep" -> arguments[index] instanceof Long;
                case "FloatRep" -> arguments[index] instanceof Float;
                case "DoubleRep" -> arguments[index] instanceof Double;
                case "AddrRep" -> arguments[index] instanceof ManagedAddress;
                case "ByteArray#", "MutableByteArray#" -> arguments[index] instanceof byte[] || arguments[index] instanceof ManagedAllocation;
                default -> false;
            };
            if (!valid) throw fault("Package C argument differs from its scalar carrier");
        }
        return function(instance);
    }
    private Object invoke(PackageScalarFunction entry, Object[] arguments, Program instance) {
        if (call.getKind() == PackageScalarCall.Kind.CREATE_CALLBACK)
            return entry.getOwner().getNativeCallbacks().create((ManagedAddress) arguments[0], (ManagedAddress) arguments[1], (ManagedAddress) arguments[2]);
        if (call.getKind() == PackageScalarCall.Kind.FREE_CALLBACK) {
            entry.getOwner().getNativeCallbacks().free((ManagedAddress) arguments[0]); return null;
        }
        if (windowsOpening) {
            arguments = arguments.clone();
            arguments[0] = WindowsNativeIo.required().openingPath((ManagedAddress) arguments[0]);
        }
        var threads = entry.getOwner().getThreads();
        var previous = threads.enterForeign(call.getSafety());
        try {
            try {
                return pointers ? invokePointers(entry, arguments)
                    : normalizeResult(entry, invokeNative(entry, arguments), noPointerArguments, null, null, null);
            } finally {
                threads.leaveForeign(previous);
                Reference.reachabilityFence(arguments);
            }
        } catch (AbstractTruffleException error) {
            if (instance != null) throw foreignExceptions.raise(error, instance.foreignExceptionBridge());
            throw foreignExceptions.raise(error);
        } catch (Exception failure) { throw rethrow(failure); }
    }
    private Object invokeNative(PackageScalarFunction entry, Object[] arguments) throws com.oracle.truffle.api.interop.InteropException {
        if (windowsOpening) return WindowsNativeIo.required().invokeOpening(() -> Calls.interop(calls, entry.getReceiver(), arguments));
        var libraries = entry.getOwner().getPackageCbits();
        libraries.seedErrno();
        try { return Calls.interop(calls, entry.getReceiver(), arguments); }
        finally { libraries.captureErrno(); }
    }
    private static final class PointerBuffer {
        ManagedAddress address;
        boolean writable;
        CbitsBuffer transport;
        PointerBuffer(ManagedAddress address, boolean writable) { this.address = address; this.writable = writable; }
    }

    @TruffleBoundary private Object invokePointers(PackageScalarFunction entry, Object[] arguments) {
        var addresses = new ArrayList<ManagedAddress>();
        int[] indices = new int[argumentReps.length];
        for (int index = 0; index < argumentReps.length; index++) {
            ManagedAddress address = switch (argumentReps[index]) {
                case "AddrRep" -> (ManagedAddress) arguments[index];
                case "ByteArray#", "MutableByteArray#" -> ManagedAddress.fromGuestByteArray(arguments[index]);
                default -> null;
            };
            if (address != null) { indices[addresses.size()] = index; addresses.add(address); }
        }
        // Complete argument admission before a pointer-cell graph is materialized.
        for (int i = 0; i < addresses.size(); i++) {
            var address = addresses.get(i);
            if (address == ManagedAddress.nullAddress()) continue;
            if (address.stableHandle() != null) entry.getOwner().getStablePointers().validate(address);
            else if (address.returnedAddress() != null) address.returnedAddress().requireCurrent();
            else address.requireByteRegion(0, argumentReps[indices[i]].equals("MutableByteArray#"));
        }
        Object[] converted = arguments.clone();
        var lease = new PackagePointerLease();
        try {
            return PackagePointerCells.invoke(entry, addresses, projection -> {
              try {
                var buffers = new IdentityHashMap<Object, PointerBuffer>();
                var views = new IdentityHashMap<ManagedAddress, PointerBuffer>();
                for (int i = 0; i < addresses.size(); i++) {
                    var address = addresses.get(i);
                    int index = indices[i];
                    if (address == ManagedAddress.nullAddress()) continue;
                    if (address.stableHandle() != null) { entry.getOwner().getStablePointers().validate(address); continue; }
                    if (projection.contains(address)) continue;
                    if (address.returnedAddress() != null) { address.returnedAddress().requireCurrent(); continue; }
                    if (address.nativeAllocation() != null) { address.requireByteRegion(0, false); continue; }
                    address.requireByteRegion(0, argumentReps[index].equals("MutableByteArray#"));
                    boolean writable = !argumentReps[index].equals("ByteArray#") && address.cbitsWritable();
                    Object key = address.cbitsStorageKey();
                    var buffer = buffers.get(key);
                    if (buffer == null) { buffer = new PointerBuffer(address, writable); buffers.put(key, buffer); }
                    if (buffer.address.cbitsOwner() == null && address.cbitsOwner() != null) buffer.address = address;
                    buffer.writable |= writable;
                    views.put(address, buffer);
                }
                for (var buffer : buffers.values()) {
                    var address = buffer.address;
                    Supplier<NativeReadOnlyPointer> nativeImage = address.nativeImageKey() == null ? null : () -> {
                        entry.getOwner().getNativeAddresses().project(address);
                        var image = entry.getOwner().getNativeAddresses().transport(address);
                        if (image == null) throw fault("Missing immutable C pointer image");
                        return image;
                    };
                    var owner = address.cbitsOwner();
                    LongSupplier nativeAddress = owner != null && owner.hasNativeStorage()
                        ? () -> address.toNativeBits() - address.cbitsOffset() : null;
                    buffer.transport = new CbitsBuffer(address.cbitsBuffer(), buffer.writable,
                        () -> address.cbitsSize(), 0, nativeImage, nativeAddress, address.cbitsStorageKey());
                }
                for (int i = 0; i < addresses.size(); i++) {
                    var address = addresses.get(i);
                    Object convertedAddress;
                    if (address == ManagedAddress.nullAddress()) convertedAddress = new PackageNativePointer(0, lease);
                    else if (address.stableHandle() != null) convertedAddress = entry.getOwner().getStablePointers().nativeTransport(address);
                    else if (projection.contains(address)) convertedAddress = new PackageNativePointer(projection.address(address), lease);
                    else if (address.returnedAddress() != null) convertedAddress = address.returnedAddress().transport();
                    else if (address.nativeAllocation() != null) {
                        address.requireByteRegion(0, false);
                        convertedAddress = new PackageNativePointer(address.toNativeBits(), lease);
                    } else convertedAddress = entry.getOwner().getPackageCbits().pointer(views.get(address).transport, address.cbitsOffset());
                    // A static image otherwise projects lazily inside C. Complete that
                    // registry operation before acquiring pointer-graph owner monitors,
                    // including when the image arrived through a returned alias.
                    if (projection.holdsAllocationMonitors() && address.nativeImageKey() != null)
                        InteropLibrary.getUncached().toNative(convertedAddress);
                    converted[indices[i]] = convertedAddress;
                }
                return () -> {
                    var callbacks = entry.getOwner().getNativeCallbacks();
                    callbacks.enterPointerCall(projection);
                    try { return invokeNative(entry, converted); }
                    catch (Exception failure) { throw rethrow(failure); }
                    finally { callbacks.leavePointerCall(); }
                };
              } catch (Exception failure) { throw rethrow(failure); }
            }, (projection, result) -> {
                try { return normalizeResult(entry, result, addresses, indices, converted, projection); }
                catch (Exception failure) { throw rethrow(failure); }
            });
        } catch (Exception failure) { throw rethrow(failure);
        } finally {
            lease.open = false;
            Reference.reachabilityFence(addresses); Reference.reachabilityFence(converted);
        }
    }

    public int executeInt(Object[] arguments, Object state) {
        return executeInt(arguments, state, null);
    }
    public int executeInt(Object[] arguments, Object state, Program instance) {
        if (NarrowInteger.fromRep(call.getResult()) == null) throw fault("Package C result is not a narrow integer ABI");
        Object result = invoke(prepare(arguments, state, instance), arguments, instance);
        try {
            return switch (call.getResult()) {
                case "Int8Rep", "Word8Rep" -> {
                    if (!numbers.fitsInByte(result)) throw fault("Package C result is not an 8-bit integer");
                    int value = numbers.asByte(result);
                    yield call.getResult().equals("Word8Rep") ? value & 255 : value;
                }
                case "Int16Rep", "Word16Rep" -> {
                    if (!numbers.fitsInShort(result)) throw fault("Package C result is not a 16-bit integer");
                    int value = numbers.asShort(result);
                    yield call.getResult().equals("Word16Rep") ? value & 65535 : value;
                }
                default -> {
                    if (!numbers.fitsInInt(result)) throw fault("Package C result is not Int32");
                    yield numbers.asInt(result);
                }
            };
        } catch (Exception failure) { throw rethrow(failure); }
    }
    public long executeLong(Object[] arguments, Object state) {
        return executeLong(arguments, state, null);
    }
    public long executeLong(Object[] arguments, Object state, Program instance) {
        if (!integerResult || NarrowInteger.fromRep(call.getResult()) != null) throw fault("Package C result is not a machine or 64-bit integer ABI");
        Object result = invoke(prepare(arguments, state, instance), arguments, instance);
        if (!numbers.fitsInLong(result)) throw fault("Package C result is not Int64");
        try { return numbers.asLong(result); } catch (Exception failure) { throw rethrow(failure); }
    }
    public float executeFloat(Object[] arguments, Object state) {
        return executeFloat(arguments, state, null);
    }
    public float executeFloat(Object[] arguments, Object state, Program instance) {
        if (!call.getResult().equals("FloatRep")) throw fault("Package C result is not a Float ABI");
        var entry = prepare(arguments, state, instance);
        Object result = invoke(entry, arguments, instance);
        if (!numbers.fitsInFloat(result)) throw fault("Package C result is not Float");
        try { return numbers.asFloat(result); } catch (Exception failure) { throw rethrow(failure); }
    }
    public double executeDouble(Object[] arguments, Object state) {
        return executeDouble(arguments, state, null);
    }
    public double executeDouble(Object[] arguments, Object state, Program instance) {
        if (!call.getResult().equals("DoubleRep")) throw fault("Package C result is not a Double ABI");
        var entry = prepare(arguments, state, instance);
        Object result = invoke(entry, arguments, instance);
        if (!numbers.fitsInDouble(result)) throw fault("Package C result is not Double");
        try { return numbers.asDouble(result); } catch (Exception failure) { throw rethrow(failure); }
    }
    public void executeVoid(Object[] arguments, Object state) {
        executeVoid(arguments, state, null);
    }
    public void executeVoid(Object[] arguments, Object state, Program instance) {
        if (!call.getResult().equals("void")) throw fault("Package C result is not void");
        invoke(prepare(arguments, state, instance), arguments, instance);
    }
    public ManagedAddress executeAddress(Object[] arguments, Object state) {
        return executeAddress(arguments, state, null);
    }
    public ManagedAddress executeAddress(Object[] arguments, Object state, Program instance) {
        if (!addressResult) throw fault("Package C result is not a pointer ABI");
        return (ManagedAddress) invoke(prepare(arguments, state, instance), arguments, instance);
    }
    private Object normalizeResult(PackageScalarFunction entry, Object result, List<ManagedAddress> arguments,
            int[] indices, Object[] carriers, PackagePointerCells projection) throws com.oracle.truffle.api.interop.UnsupportedMessageException {
        if (!addressResult) return result;
        if (numbers.isNull(result)) return ManagedAddress.nullAddress();
        if (!(result instanceof TruffleObject)) throw fault("Package C returned a non-pointer carrier");
        Long bits = numbers.isPointer(result) ? numbers.asPointer(result) : null;
        if (bits != null) {
            if (bits == 0) return ManagedAddress.nullAddress();
            var callback = entry.getOwner().getNativeCallbacks().recover(bits);
            if (callback != null) return callback;
            var stable = entry.getOwner().getStablePointers().recoverToken(bits);
            if (stable != null) return stable;
        }
        ManagedAddress backing = bits != null && projection != null ? projection.recoverBacking(bits) : null;
        for (int i = 0; backing == null && i < arguments.size(); i++) {
            var argument = arguments.get(i);
            var returned = argument.returnedAddress();
            var candidate = returned != null && returned.getBacking() != null ? returned.getBacking() : argument;
            if (bits == null) {
                if (candidate.returnedAddress() != null || candidate.stableHandle() != null
                    || candidate == ManagedAddress.nullAddress() || candidate.hasNativeStorage()) continue;
                Long relative = entry.getOwner().getPackageCbits().managedAliasOffset(result, carriers[indices[i]],
                    candidate.cbitsOffset(), candidate.cbitsSize());
                if (relative == null) continue;
                backing = candidate.plus(relative); break;
            }
            if (!candidate.hasNativeStorage()) continue;
            var allocation = candidate.cbitsOwner();
            long candidateBits = allocation != null && allocation.hasNativeStorage()
                ? allocation.nativeSegment().address() + candidate.cbitsOffset() : candidate.toNativeBits();
            long base = candidateBits - candidate.cbitsOffset();
            long displacement = bits - base;
            if (Long.compareUnsigned(displacement, candidate.cbitsSize()) <= 0) {
                backing = candidate.plus(bits - candidateBits); break;
            }
        }
        if (backing == null && bits != null) {
            var recovered = entry.getOwner().getNativeAllocations().recoverAddress(bits);
            if (recovered == null) recovered = entry.getOwner().getNativeAddresses().recover(bits);
            if (recovered.hasNativeStorage() || recovered.nativeImageKey() != null) backing = recovered;
        }
        return ManagedAddress.fromReturnedAddress(new PackageReturnedAddress(entry.getOwner(), entry.getAlive(), result, backing));
    }
    @SuppressWarnings("unchecked") private static <E extends Throwable> RuntimeException rethrow(Throwable failure) throws E { throw (E) failure; }
}
