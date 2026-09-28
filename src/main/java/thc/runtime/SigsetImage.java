// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import thc.Json;
import static thc.runtime.RuntimeServiceStatus.fault;

/** Only caller-owned sigset_t image operations, never a host signal mask. */
public final class SigsetImage {
    private final long size;
    private final long[] clearBytes, signalBits;
    private final long invalidErrno;
    private SigsetImage(long size, long[] clearBytes, long[] signalBits, long invalidErrno) {
        this.size = size; this.clearBytes = clearBytes; this.signalBits = signalBits; this.invalidErrno = invalidErrno;
    }
    public long getSize() { return size; }
    public long apply(OriginalStdioOp operation, ManagedAddress address, long signal, ManagedStdio stdio) {
        if (!operation.getSigset()) throw fault("Invalid sigset image operation");
        if (signal != (long) (int) signal) throw fault("Original sigaddset requires a canonical signed CInt");
        var owner = address.cbitsOwner$org_intelligence_thc();
        if (owner == null) return checked(operation, address, signal, stdio);
        synchronized (owner) { return checked(operation, address, signal, stdio); }
    }
    private long checked(OriginalStdioOp operation, ManagedAddress address, long signal, ManagedStdio stdio) {
        address.requireByteRegion$org_intelligence_thc(size, true);
        if (operation == OriginalStdioOp.SIGEMPTYSET) {
            for (long offset : clearBytes) address.writeWord8(offset, 0);
            return 0;
        }
        long bit = signal >= 1 && signal <= signalBits.length ? signalBits[(int) signal - 1] : -1;
        if (bit < 0) { stdio.nativeError$org_intelligence_thc(invalidErrno); return -1; }
        long offset = bit / 8;
        address.writeWord8(offset, address.readWord8(offset) | (1L << ((int) bit % 8)));
        return 0;
    }

    private static void requireAbi(boolean valid) { if (!valid) throw fault("Invalid original Linux sigset image ABI"); }
    private static long integer(Object value) {
        requireAbi(value instanceof Long || value instanceof Integer);
        return ((Number) value).longValue();
    }
    private static long[] integers(Object value, String message) {
        if (!(value instanceof List<?> list)) throw fault(message);
        long[] values = new long[list.size()];
        for (int i = 0; i < values.length; i++) values[i] = integer(list.get(i));
        return values;
    }
    public static SigsetImage parse(Object raw, String system, String arch) {
        if (!(raw instanceof Map<?, ?> doc)) throw fault("Missing sigset image ABI");
        String architecture = arch.equals("amd64") ? "x86_64" : arch;
        requireAbi(doc.keySet().equals(Set.of("schema", "system", "architecture", "target", "sourceSha256", "sigset")) &&
            integer(doc.get("schema")) == 1 && system.equals("Linux") && architecture.equals("x86_64") &&
            system.equals(doc.get("system")) && architecture.equals(doc.get("architecture")) && "x86_64-unknown-linux-gnu".equals(doc.get("target")) &&
            doc.get("sourceSha256") instanceof String hash && hash.matches("[0-9a-f]{64}"));
        if (!(doc.get("sigset") instanceof Map<?, ?> layout)) throw fault("Missing sigset image layout");
        requireAbi(layout.keySet().equals(Set.of("size", "alignment", "invalidErrno", "clearBytes", "signalBits")));
        long size = integer(layout.get("size")), errno = integer(layout.get("invalidErrno"));
        requireAbi(size >= 1 && size <= 4096 && integer(layout.get("alignment")) == 8 && size % 8 == 0 && errno >= 1 && errno <= Integer.MAX_VALUE);
        long[] clear = integers(layout.get("clearBytes"), "Missing sigset clear-byte table");
        long[] bits = integers(layout.get("signalBits"), "Missing sigset signal-bit table");
        requireAbi(clear.length > 0 && clear.length <= size && bits.length >= 1 && bits.length <= 128);
        var cleared = new HashSet<Long>();
        long previous = -1;
        for (long offset : clear) {
            requireAbi(offset >= 0 && offset < size && offset > previous);
            cleared.add(offset); previous = offset;
        }
        var valid = new HashSet<Long>();
        for (long bit : bits) {
            requireAbi(bit == -1 || bit >= 0 && bit < size * 8);
            if (bit >= 0) requireAbi(valid.add(bit) && cleared.contains(bit / 8));
        }
        requireAbi(!valid.isEmpty());
        return new SigsetImage(size, clear, bits, errno);
    }

    private static volatile SigsetImage host;
    private static SigsetImage host() {
        var result = host;
        if (result == null) synchronized (SigsetImage.class) {
            result = host;
            if (result == null) {
                try (var stream = SigsetImage.class.getResourceAsStream("/thc/native/sigset-abi.json")) {
                    if (stream == null) throw fault("Missing sigset image ABI probe");
                    result = parse(Json.INSTANCE.parse(new String(stream.readAllBytes(), StandardCharsets.UTF_8)), System.getProperty("os.name"), System.getProperty("os.arch"));
                } catch (IOException failure) { throw propagate(failure); }
                host = result;
            }
        }
        return result;
    }
    @TruffleBoundary public static long execute(OriginalStdioOp operation, ManagedAddress address, long signal, ManagedStdio stdio) {
        return host().apply(operation, address, signal, stdio);
    }
    @SuppressWarnings("unchecked") private static <E extends Throwable> RuntimeException propagate(Throwable failure) throws E { throw (E) failure; }
}
