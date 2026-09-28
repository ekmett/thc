// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.interop.InteropException;
import com.oracle.truffle.api.interop.InteropLibrary;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import static thc.runtime.RuntimeFault.fault;

/** Actual host iconv, not a charset emulation. Native pointers never reach Core.
 * Parsing happens outside our lock; short, callback-free native calls and handle
 * lifetime transitions are serialized within this guest context. */
public final class ManagedIconv {
    private final Supplier<SulongCbits> cbits;
    private final ManagedStdio stdio;
    private final GuestThreads threads;
    private final Object lock = new Object();
    private final InteropLibrary interop = InteropLibrary.getUncached();
    private final HashMap<Long, Object> handles = new HashMap<>();
    private Object library, locale;
    private ManagedAddress name;
    private boolean disposed;
    private static final AtomicLong IDS = new AtomicLong(1);
    public ManagedIconv(Supplier<SulongCbits> cbits, ManagedStdio stdio, GuestThreads threads) {
        this.cbits = cbits; this.stdio = stdio; this.threads = threads;
    }
    private Object nativeLibrary() {
        var previous = threads.enterForeign(ForeignSafety.UNSAFE);
        try { return cbits.get().iconvLibrary$org_intelligence_thc(); } finally { threads.leaveForeign(previous); }
    }
    private Object call(String symbol, Object... arguments) {
        var previous = threads.enterForeign(ForeignSafety.UNSAFE);
        try { return interop.execute(interop.readMember(library, symbol), arguments); }
        catch (InteropException failure) { throw propagate(failure); }
        finally { threads.leaveForeign(previous); }
    }
    private void initialize(Object nativeLibrary) {
        if (disposed) throw fault("Iconv context is closed");
        stdio.nativeError$org_intelligence_thc(0); // Validate the target C ABI before allocating native state.
        if (library == null) library = nativeLibrary;
        if (locale == null) {
            var created = call("thc_iconv_locale_new");
            if (interop.isNull(created)) throw fault("Native LC_CTYPE initialization from environment failed");
            locale = created;
        }
    }
    private byte[] bytes(ManagedAddress address, long count) {
        address.requireByteRegion$org_intelligence_thc(count, false);
        var bytes = new byte[(int) count];
        for (int i = 0; i < bytes.length; i++) bytes[i] = (byte) address.readWord8(i);
        return bytes;
    }
    private byte[] cstring(ManagedAddress address) {
        long available = address.availableBytes(), count = 0;
        while (count < available) if (address.readWord8(count++) == 0) return bytes(address, count);
        throw fault("Unterminated iconv encoding name");
    }
    private static final class Result {
        final byte[] bytes;
        final CbitsBuffer buffer;
        private final ByteBuffer words;
        Result(int size) {
            bytes = new byte[size * 8]; buffer = new CbitsBuffer(bytes, true);
            words = ByteBuffer.wrap(bytes).order(ByteOrder.nativeOrder());
        }
        long get(int index) { return words.getLong(index * 8); }
    }
    @TruffleBoundary public ManagedAddress localeEncoding() {
        var nativeLibrary = nativeLibrary();
        synchronized (lock) {
            initialize(nativeLibrary);
            if (name != null) return name;
            try {
                long size = interop.asLong(call("thc_iconv_locale_size", locale));
                if (size < 1 || size > Integer.MAX_VALUE) throw fault("Invalid native locale encoding name");
                var bytes = new byte[(int) size];
                call("thc_iconv_locale_copy", locale, new CbitsBuffer(bytes, true));
                if (bytes[bytes.length - 1] != 0) throw fault("Unterminated native locale encoding name");
                name = ManagedAddress.Companion.fromAllocation(ManagedAllocation.immutable(bytes, 8));
                return name;
            } catch (InteropException failure) { throw propagate(failure); }
        }
    }
    @TruffleBoundary public long open(ManagedAddress to, ManagedAddress from) {
        var toBytes = cstring(to);
        var fromBytes = cstring(from);
        var nativeLibrary = nativeLibrary();
        synchronized (lock) {
            initialize(nativeLibrary);
            // Reserve before native allocation, so exhaustion cannot leak a handle.
            long id = IDS.getAndUpdate(value -> value == Long.MAX_VALUE ? value : value + 1);
            if (id == Long.MAX_VALUE) throw fault("Iconv handle registry exhausted");
            var error = new Result(1);
            var handle = call("thc_iconv_open", locale, new CbitsBuffer(toBytes, false), (long) toBytes.length,
                new CbitsBuffer(fromBytes, false), (long) fromBytes.length, error.buffer);
            stdio.nativeError$org_intelligence_thc(error.get(0));
            if (interop.isNull(handle)) return -1;
            handles.put(id, handle); return id;
        }
    }
    @TruffleBoundary public long close(long id) {
        synchronized (lock) {
            var handle = handles.remove(id);
            if (handle == null) throw fault("Unknown, closed or cross-context iconv handle");
            var error = new Result(1);
            try {
                long result = interop.asLong(call("thc_iconv_close", handle, error.buffer));
                stdio.nativeError$org_intelligence_thc(error.get(0)); return result;
            } catch (InteropException failure) { throw propagate(failure); }
        }
    }
    private ManagedAddress pointer(ManagedAddress cell) {
        cell.requireRange(0, 8, true);
        var owner = cell.cbitsOwner$org_intelligence_thc();
        if (owner == null || owner.getAddressWidth() != 8 || cell.cbitsOffset$org_intelligence_thc() % 8 != 0)
            throw fault("Iconv pointer cell requires aligned native LP64 storage");
        return cell.readAddressElementIndex(0);
    }
    private long count(ManagedAddress cell) {
        cell.requireByteRegion$org_intelligence_thc(8, true);
        if (cell.cbitsOffset$org_intelligence_thc() % 8 != 0) throw fault("Iconv count cell requires native size_t alignment");
        long count = ManagedAddressRead.WORD64.read(cell, 0);
        if (count < 0 || count > Integer.MAX_VALUE) throw fault("Iconv count exceeds managed buffer capacity");
        return count;
    }
    @TruffleBoundary public long convert(long id, ManagedAddress inputCell, ManagedAddress inputCount,
                                         ManagedAddress outputCell, ManagedAddress outputCount) {
        synchronized (lock) {
            var handle = handles.get(id);
            if (handle == null) throw fault("Unknown, closed or cross-context iconv handle");
            var nil = ManagedAddress.Companion.nullAddress();
            var input = inputCell == nil ? nil : pointer(inputCell);
            boolean reset = input == nil;
            var output = outputCell == nil ? nil : pointer(outputCell);
            boolean discard = output == nil;
            if (discard && !reset) throw fault("Iconv output pointer is null outside reset");
            long inSize = reset ? 0 : count(inputCount), outSize = discard ? 0 : count(outputCount);
            if (!reset) input.requireByteRegion$org_intelligence_thc(inSize, false);
            if (!discard) output.requireByteRegion$org_intelligence_thc(outSize, true);
            // Reject overlapping cells/buffers before native state changes.
            var regions = new ManagedAddress[6];
            var sizes = new long[6];
            int n = 0;
            if (!reset) {
                regions[n] = inputCell; sizes[n++] = 8;
                regions[n] = inputCount; sizes[n++] = 8;
                regions[n] = input; sizes[n++] = inSize;
            }
            if (!discard) {
                regions[n] = outputCell; sizes[n++] = 8;
                regions[n] = outputCount; sizes[n++] = 8;
                regions[n] = output; sizes[n++] = outSize;
            }
            for (int i = 0; i < n; i++) for (int j = 0; j < i; j++)
                if (regions[i].overlaps(0, sizes[i], regions[j], 0, sizes[j]))
                    throw fault("Iconv requires disjoint pointer cells, counts and byte regions");
            var inBytes = reset ? new byte[0] : bytes(input, inSize);
            var outBytes = new byte[(int) outSize];
            var result = new Result(4);
            call("thc_iconv_convert", handle, new CbitsBuffer(inBytes, false), inSize,
                new CbitsBuffer(outBytes, true), outSize, reset ? 1 : 0, discard ? 1 : 0, result.buffer);
            long inLeft = result.get(2), outLeft = result.get(3);
            if (inLeft < 0 || inLeft > inSize || outLeft < 0 || outLeft > outSize) throw fault("Invalid native iconv cursor writeback");
            if (!discard) {
                for (int i = 0; i < (int) (outSize - outLeft); i++) output.writeWord8(i, outBytes[i]);
                outputCell.writeAddressElementIndex(0, output.plus(outSize - outLeft));
                outputCount.writeNativeScalar(0, 8, outLeft);
            }
            if (!reset) {
                inputCell.writeAddressElementIndex(0, input.plus(inSize - inLeft));
                inputCount.writeNativeScalar(0, 8, inLeft);
            }
            stdio.nativeError$org_intelligence_thc(result.get(1)); return result.get(0);
        }
    }
    /** Called during language finalization while LLVM guest calls remain legal. */
    @TruffleBoundary public void dispose() {
        synchronized (lock) {
            if (!disposed) {
                disposed = true;
                for (var handle : handles.values()) call("thc_iconv_close", handle, new Result(1).buffer);
                handles.clear();
                if (locale != null) call("thc_iconv_locale_free", locale);
                locale = null; library = null; name = null;
            }
        }
    }
    public int liveHandles() { synchronized (lock) { return handles.size(); } }
    @SuppressWarnings("unchecked") private static <E extends Throwable> RuntimeException propagate(Throwable failure) throws E { throw (E) failure; }
}
