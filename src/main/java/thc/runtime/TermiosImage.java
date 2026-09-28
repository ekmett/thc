// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import java.io.IOException;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;
import thc.Json;
import static thc.runtime.RuntimeServiceStatus.fault;

/** Actual terminal-image layout and read-only signal header constants, not
 * terminal state or native pointers. No descriptor or signal mask is observed. */
public final class TermiosImage {
    private final long size, lflagOffset, ccOffset;
    private final Map<String, Long> constants;
    private TermiosImage(long size, long lflagOffset, long ccOffset, Map<String, Long> constants) {
        this.size = size; this.lflagOffset = lflagOffset; this.ccOffset = ccOffset; this.constants = constants;
    }
    public long getSize() { return size; }
    private <T> T image(ManagedAddress address, boolean writable, Supplier<T> body) {
        var allocation = address.cbitsOwner$org_intelligence_thc();
        if (allocation == null) return checked(address, writable, body);
        synchronized (allocation) { return checked(address, writable, body); }
    }
    private <T> T checked(ManagedAddress address, boolean writable, Supplier<T> body) {
        var nativeOwner = address.nativeAllocation$org_intelligence_thc();
        try (var ignored = nativeOwner == null ? null : nativeOwner.borrow()) {
            address.requireByteRegion$org_intelligence_thc(size, writable);
            return body.get();
        }
    }
    public long lflag(ManagedAddress address) {
        return image(address, false, () -> {
            long result = 0;
            for (int index = 0; index < 4; index++) result |= address.readWord8(lflagOffset + index) << shift(index);
            return result;
        });
    }
    public void poke(ManagedAddress address, long value) {
        if (value < 0 || value > 0xffffffffL) throw fault("Original termios setter requires canonical Word32#");
        image(address, true, () -> {
            for (int index = 0; index < 4; index++) address.writeWord8(lflagOffset + index, (value >>> shift(index)) & 255L);
            return null;
        });
    }
    public ManagedAddress cc(ManagedAddress address) { return image(address, false, () -> address.plus(ccOffset)); }
    public long constant(OriginalStdioOp operation) {
        if (operation == OriginalStdioOp.SIZEOF_TERMIOS) return size;
        String name = switch (operation) {
            case ECHO -> "echo"; case ICANON -> "icanon"; case VMIN -> "vmin"; case VTIME -> "vtime";
            case TCSANOW -> "tcsanow"; case SIZEOF_SIGSET -> "sigsetSize"; case SIGTTOU -> "sigttou";
            case SIG_BLOCK -> "sigBlock"; case SIG_SETMASK -> "sigSetmask";
            default -> throw fault("Invalid termios constant");
        };
        return constants.get(name);
    }
    private static int shift(int index) { return (ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN ? index : 3 - index) * 8; }

    private static void requireAbi(boolean valid) { if (!valid) throw fault("Invalid original Linux termios ABI"); }
    private static long integer(Object value) {
        requireAbi(value instanceof Long || value instanceof Integer);
        return ((Number) value).longValue();
    }
    public static TermiosImage parse(Object raw, String system, String arch) {
        if (!(raw instanceof Map<?, ?> doc)) throw fault("Missing termios ABI");
        requireAbi(doc.keySet().equals(Set.of("schema", "system", "architecture", "target", "sourceSha256", "termios")) &&
            doc.get("sourceSha256") instanceof String hash && hash.matches("[0-9a-f]{64}"));
        String architecture = arch.equals("amd64") ? "x86_64" : arch;
        requireAbi(integer(doc.get("schema")) == 1 && system.equals("Linux") && architecture.equals("x86_64") &&
            system.equals(doc.get("system")) && architecture.equals(doc.get("architecture")) && "x86_64-unknown-linux-gnu".equals(doc.get("target")));
        if (!(doc.get("termios") instanceof Map<?, ?> layout)) throw fault("Missing termios layout");
        var names = List.of("size", "alignment", "lflagOffset", "lflagBytes", "ccOffset", "ccBytes", "ccCount",
            "echo", "icanon", "vmin", "vtime", "tcsanow", "sigsetSize", "sigttou", "sigBlock", "sigSetmask");
        requireAbi(layout.keySet().equals(Set.copyOf(names)));
        var values = new LinkedHashMap<String, Long>();
        for (String name : names) values.put(name, integer(layout.get(name)));
        long size = values.get("size"), lflag = values.get("lflagOffset"), cc = values.get("ccOffset"), count = values.get("ccCount");
        requireAbi(size >= 4 && size <= 4096 && values.get("alignment") == 4L && size % 4 == 0 &&
            values.get("lflagBytes") == 4L && values.get("ccBytes") == 1L && lflag >= 0 && lflag <= size - 4 && lflag % 4 == 0 &&
            count >= 1 && count <= size && cc >= 0 && cc <= size - count && (lflag + 4 <= cc || cc + count <= lflag));
        var constants = new LinkedHashMap<String, Long>();
        for (String name : List.of("echo", "icanon", "vmin", "vtime", "tcsanow", "sigsetSize", "sigttou", "sigBlock", "sigSetmask")) {
            long value = values.get(name);
            requireAbi(value >= Integer.MIN_VALUE && value <= Integer.MAX_VALUE);
            constants.put(name, value);
        }
        requireAbi(constants.get("sigsetSize") >= 1 && constants.get("sigsetSize") <= 4096 &&
            constants.get("echo") > 0 && constants.get("icanon") > 0 && constants.get("vmin") >= 0 && constants.get("vmin") < count &&
            constants.get("vtime") >= 0 && constants.get("vtime") < count && !constants.get("vmin").equals(constants.get("vtime")));
        return new TermiosImage(size, lflag, cc, constants);
    }

    private static volatile TermiosImage host;
    private static TermiosImage host() {
        var result = host;
        if (result == null) synchronized (TermiosImage.class) {
            result = host;
            if (result == null) {
                try (var stream = TermiosImage.class.getResourceAsStream("/thc/native/termios-abi.json")) {
                    if (stream == null) throw fault("Missing termios ABI probe");
                    result = parse(Json.INSTANCE.parse(new String(stream.readAllBytes(), StandardCharsets.UTF_8)), System.getProperty("os.name"), System.getProperty("os.arch"));
                } catch (IOException failure) { throw propagate(failure); }
                host = result;
            }
        }
        return result;
    }
    /** Keep the complete image live through the foreign operation and its
     * copyback, including libc errors. Const input never copies back. */
    @TruffleBoundary public static <T> T transfer(ManagedAddress address, boolean copyBack, Function<byte[], T> operation) {
        var abi = host();
        return abi.image(address, copyBack, () -> {
            byte[] bytes = new byte[(int) abi.size];
            for (int i = 0; i < bytes.length; i++) bytes[i] = (byte) address.readWord8(i);
            try { return operation.apply(bytes); }
            finally { if (copyBack) ManagedAddress.Companion.fromByteArray(bytes).copyNonOverlappingTo(address, abi.size); }
        });
    }
    @TruffleBoundary public static long scalar(OriginalStdioOp operation, ManagedAddress address, long value) {
        var abi = host();
        if (operation == OriginalStdioOp.LFLAG) return abi.lflag(address);
        if (operation == OriginalStdioOp.POKE_LFLAG) { abi.poke(address, value); return 0; }
        return abi.constant(operation);
    }
    @TruffleBoundary public static ManagedAddress pointer(ManagedAddress address) { return host().cc(address); }
    @SuppressWarnings("unchecked") private static <E extends Throwable> RuntimeException propagate(Throwable failure) throws E { throw (E) failure; }
}
