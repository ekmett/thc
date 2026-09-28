// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;
import thc.Language;
import thc.NativeIO;
import static thc.runtime.RuntimeFault.fault;

/** Ordinary libc calls. Only transport is managed: scoped images, context cwd,
 * owned file descriptors, errno, and the declared foreign-call safety. */
public final class NativeUnix {
    private NativeUnix() {}

    static boolean pathname(OriginalStdioOp operation) {
        return operation.ordinal() >= OriginalStdioOp.UNIX_CHOWN.ordinal()
            && operation.ordinal() <= OriginalStdioOp.UNIX_LUTIMES.ordinal();
    }
    static boolean descriptor(OriginalStdioOp operation) {
        return operation.ordinal() >= OriginalStdioOp.UNIX_FCHMOD.ordinal()
            && operation.ordinal() <= OriginalStdioOp.UNIX_TCGETPGRP.ordinal();
    }

    @TruffleBoundary public static long execute(OriginalStdioOp operation, Object[] arguments) {
        if (!operation.getUnixNative()) throw fault("Not a native Unix declaration");
        var state = Language.currentState();
        if (!NativeIO.supportedPosixHost() || !state.getEnv().isNativeAccessAllowed())
            throw new SecurityException("Original Unix calls require Linux x86_64 native access");
        var previous = state.getThreads().enterForeign(ForeignSafety.synchronous(operation.getSafety()));
        try {
            SulongCbits.CapiResult result;
            if (pathname(operation)) result = NativeFileProvider.current().unixPath(operation, arguments);
            else if (descriptor(operation)) result = state.getFiles().unixDescriptor(operation, arguments);
            else result = invoke(operation, arguments, null);
            // POSIX advice/allocation return the error number itself.
            if ((operation == OriginalStdioOp.UNIX_FADVISE || operation == OriginalStdioOp.UNIX_FALLOCATE) && result.errno() != 0)
                return result.errno();
            state.getStdio().nativeError(result.errno());
            return result.value();
        } finally { state.getThreads().leaveForeign(previous); }
    }

    private static String symbol(OriginalStdioOp operation) {
        String original = operation.getSymbol();
        String symbol = original.startsWith("ghczuwrapper")
            ? original.substring(original.lastIndexOf("ZC") + 2).replace("zu", "_") : original;
        return symbol.equals("makedev") ? "gnu_dev_makedev" : symbol;
    }
    private static MemoryLayout layout(String rep) {
        return switch (rep) {
            case "AddrRep" -> ValueLayout.ADDRESS;
            case "Int32Rep", "Word32Rep" -> ValueLayout.JAVA_INT;
            case "Int64Rep", "Word64Rep", "IntRep", "WordRep" -> ValueLayout.JAVA_LONG;
            default -> throw fault("Unsupported native Unix ABI carrier");
        };
    }
    private static final class Bindings {
        static final Linker LINKER = Linker.nativeLinker();
        static final MemoryLayout CAPTURE = Linker.Option.captureStateLayout();
        static final long ERRNO = CAPTURE.byteOffset(MemoryLayout.PathElement.groupElement("errno"));
        static final MethodHandle ERRNO_ADDRESS = LINKER.downcallHandle(
            LINKER.defaultLookup().find("__errno_location").orElseThrow(), FunctionDescriptor.of(ValueLayout.ADDRESS));
        static final ConcurrentHashMap<OriginalStdioOp, MethodHandle> CALLS = new ConcurrentHashMap<>();
        static MethodHandle function(OriginalStdioOp operation) {
            return CALLS.computeIfAbsent(operation, op -> LINKER.downcallHandle(
                LINKER.defaultLookup().find(symbol(op)).orElseThrow(),
                FunctionDescriptor.of(layout(op.getResult()), op.getArguments().stream()
                    .filter(java.util.Objects::nonNull).map(NativeUnix::layout).toArray(MemoryLayout[]::new)),
                Linker.Option.captureCallState("errno")));
        }
    }

    // Sizes are checked against the selected native headers in native-file-api.c.
    private static int imageSize(OriginalStdioOp operation) {
        return switch (operation) {
            case UNIX_TIME -> 8;
            case UNIX_TIMES, UNIX_UTIMES, UNIX_LUTIMES, UNIX_FUTIMES, UNIX_FUTIMENS -> 32;
            case UNIX_UNAME -> 390;
            case UNIX_GETRLIMIT -> 16;
            case UNIX_SIGFILLSET, UNIX_SIGDELSET, UNIX_SIGISMEMBER -> 128;
            case UNIX_CFGETISPEED, UNIX_CFGETOSPEED, UNIX_CFSETISPEED, UNIX_CFSETOSPEED -> 60;
            default -> throw fault("No image ABI for Unix declaration");
        };
    }
    private static boolean output(OriginalStdioOp operation) {
        return switch (operation) {
            case UNIX_TIME, UNIX_TIMES, UNIX_UNAME, UNIX_GETRLIMIT, UNIX_SIGFILLSET,
                 UNIX_SIGDELSET, UNIX_CFSETISPEED, UNIX_CFSETOSPEED -> true;
            default -> false;
        };
    }
    private static boolean nullable(OriginalStdioOp operation) {
        return switch (operation) {
            case UNIX_TIME, UNIX_UTIMES, UNIX_LUTIMES, UNIX_FUTIMES, UNIX_FUTIMENS -> true;
            default -> false;
        };
    }

    /** The only image pointer in these declarations is borrowed before the effect. */
    static SulongCbits.CapiResult invoke(OriginalStdioOp operation, Object[] arguments, byte[] path) {
        ManagedAddress image = null;
        for (int i = pathname(operation) ? 1 : 0; i < arguments.length; i++)
            if (arguments[i] instanceof ManagedAddress address) image = address;
        if (image == null || image.sameLocation(ManagedAddress.nullAddress()) && nullable(operation))
            return call(operation, arguments, path, null);
        var checked = image;
        return checked.withNativeBorrow(() -> {
            var owner = checked.cbitsOwner();
            if (owner == null) return call(operation, arguments, path, checked);
            synchronized (owner) { return call(operation, arguments, path, checked); }
        });
    }

    private static SulongCbits.CapiResult call(OriginalStdioOp operation, Object[] arguments,
                                               byte[] path, ManagedAddress image) {
        // Resolve before effects or errno capture, so linkage cannot disturb the result.
        var function = Bindings.function(operation);
        try (var arena = Arena.ofConfined()) {
            var capture = arena.allocate(Bindings.CAPTURE);
            var actual = new Object[arguments.length + 1];
            actual[0] = capture;
            MemorySegment bytes = MemorySegment.NULL;
            int size = image == null ? 0 : imageSize(operation);
            if (image != null) {
                image.requireByteRegion(size, output(operation));
                bytes = arena.allocate(size, 8);
                for (int i = 0; i < size; i++) bytes.set(ValueLayout.JAVA_BYTE, i, (byte) image.readWord8(i));
            }
            for (int i = 0; i < arguments.length; i++) {
                if (i == 0 && path != null) {
                    var name = arena.allocate(path.length);
                    name.copyFrom(MemorySegment.ofArray(path));
                    actual[i + 1] = name;
                } else if (arguments[i] instanceof ManagedAddress) actual[i + 1] = bytes;
                else if (layout(operation.getArguments().get(i)) == ValueLayout.JAVA_INT)
                    actual[i + 1] = ((Number) arguments[i]).intValue();
                else actual[i + 1] = ((Number) arguments[i]).longValue();
            }
            // pathconf/sysconf may return -1 with errno unchanged. Seed zero to
            // distinguish this success sentinel from a native failure.
            var errno = ((MemorySegment) Bindings.ERRNO_ADDRESS.invokeExact()).reinterpret(4);
            int prior = errno.get(ValueLayout.JAVA_INT, 0);
            errno.set(ValueLayout.JAVA_INT, 0, 0);
            long result;
            int error;
            try {
                result = ((Number) function.invokeWithArguments(actual)).longValue();
                error = capture.get(ValueLayout.JAVA_INT, Bindings.ERRNO);
            } finally { errno.set(ValueLayout.JAVA_INT, 0, prior); }
            if (image != null && output(operation))
                for (int i = 0; i < size; i++) image.writeWord8(i, bytes.get(ValueLayout.JAVA_BYTE, i));
            if ("Word32Rep".equals(operation.getResult())) result = Integer.toUnsignedLong((int) result);
            return new SulongCbits.CapiResult(result, result == -1 ? error : 0);
        } catch (Throwable failure) { throw propagate(failure); }
    }

    static byte[] anchoredPath(int descriptor, ManagedAddress address) {
        long length = address.cStringLength();
        if (length >= Integer.MAX_VALUE) throw fault("Unix pathname exceeds managed capacity");
        byte[] path = new byte[(int) length + 1];
        for (int i = 0; i < path.length; i++) path[i] = (byte) address.readWord8(i);
        if (length == 0 || path[0] == '/') return path;
        byte[] anchor = ("/proc/self/fd/" + descriptor + "/").getBytes(StandardCharsets.US_ASCII);
        byte[] result = Arrays.copyOf(anchor, Math.addExact(anchor.length, path.length));
        System.arraycopy(path, 0, result, anchor.length, path.length);
        return result;
    }
    @SuppressWarnings("unchecked") private static <E extends Throwable> RuntimeException propagate(Throwable error) throws E { throw (E) error; }
}
