// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import thc.Json;
import thc.Language;
import thc.NativeIO.StandardEndpoint;
import static java.lang.foreign.ValueLayout.*;
import static thc.runtime.RuntimeFault.fault;

/** The selected package's real CRT/WinSock namespace. Never accept a logical
 * ManagedFiles descriptor or promote guest storage at this boundary. The fixed
 * NativeIO factory owns filesystem and process-standard-endpoint authority. */
public final class WindowsNativeIo implements AutoCloseable {
    public record Result(int length, int error, long windowsError, boolean consoleAbort) {}
    private final Language.State context;
    private final Set<StandardEndpoint> endpoints;
    private MemorySegment owner;
    private int borrowers;
    private volatile boolean closed;

    WindowsNativeIo(Language.State context, Set<StandardEndpoint> endpoints) {
        this.context = context; this.endpoints = Set.copyOf(endpoints);
    }
    private void current() {
        if (Language.currentState(null) != context || closed) throw fault("Windows native IO belongs to another or closed context");
        if (!context.getEnv().isNativeAccessAllowed()) throw new SecurityException("Windows native IO requires native access");
    }
    private synchronized MemorySegment borrow() {
        current();
        if (owner == null) {
            var image = context.getPackageCbits().windowsIoImage();
            try (var arena = Arena.ofConfined()) {
                String path = image.toString();
                var name = arena.allocate((path.length() + 1L) * 2, 2);
                for (int i = 0; i < path.length(); i++) name.set(JAVA_CHAR, i * 2L, path.charAt(i));
                name.set(JAVA_CHAR, path.length() * 2L, '\0');
                var error = arena.allocate(JAVA_INT);
                var binding = (MemorySegment) Api.bind.invokeExact(name, error);
                if (binding.address() == 0) throw fault("Selected package Windows IO namespace cannot bind: " + Integer.toUnsignedLong(error.get(JAVA_INT, 0)));
                owner = binding;
            } catch (Throwable failure) { throw propagate(failure); }
        }
        borrowers++;
        return owner;
    }
    private synchronized void release() { borrowers--; notifyAll(); }
    private void descriptor(int fd, boolean socket, boolean writing) {
        current();
        // Socket values do not name the CRT standard endpoints.
        if (!socket && fd >= 0 && fd <= 2) {
            var endpoint = StandardEndpoint.values()[fd];
            if (!endpoints.contains(endpoint) || writing == (endpoint == StandardEndpoint.INPUT))
                throw new SecurityException("Native standard endpoint was not admitted for this operation: " + endpoint);
        }
    }
    /** Capture both results on the native origin, before SAFE readmission.
     * The private descriptor copy and caller's storage borrow survive the call;
     * native close/reuse of the original cannot redirect an acquired operation. */
    @TruffleBoundary public Result transfer(int fd, boolean socket, int count, ManagedAddress address, boolean writing) {
        descriptor(fd, socket, writing);
        if (count < 0) throw fault("Windows RTS transfer requires a nonnegative CInt length");
        if (address == ManagedAddress.nullAddress()) {
            if (count != 0) throw fault("Windows RTS transfer requires storage for a nonzero length");
        } else {
            address.requireByteRegion(count, !writing);
            if (!address.hasNativeIOStorage()) throw fault("Windows RTS transfer requires addressable caller-owned storage");
        }
        var binding = borrow();
        try {
            if (address == ManagedAddress.nullAddress() && count == 0)
                return transfer(binding, fd, socket, count, MemorySegment.NULL, writing);
            return address.withNativeIOWindow(count, !writing, window ->
                transfer(binding, fd, socket, count, window, writing));
        } finally { release(); }
    }
    private Result transfer(MemorySegment binding, int fd, boolean socket, int count, MemorySegment buffer, boolean writing) {
        try (var arena = Arena.ofConfined()) {
            var loan = arena.allocate(16, 8);
            var result = arena.allocate(16, 4);
            var previous = context.getThreads().enterForeign(ForeignSafety.SAFE);
            try {
                int error = (int) Api.acquire.invokeExact(binding, fd, socket ? 1 : 0, loan);
                if (error != 0) return new Result(-1, error, 0, false);
                try {
                    Api.transfer.invokeExact(binding, loan, writing ? 1 : 0, count, buffer, result);
                    return new Result(result.get(JAVA_INT, 0), result.get(JAVA_INT, 4),
                        Integer.toUnsignedLong(result.get(JAVA_INT, 8)), result.get(JAVA_INT, 12) != 0);
                } finally { Api.release.invokeExact(binding, loan); }
            } finally { context.getThreads().leaveForeign(previous); }
        } catch (Throwable failure) { throw propagate(failure); }
    }
    /** Disposal waits for the native component/storage loans, without unpinning
     * the selected DLL while a SAFE call is still using its imported functions. */
    @Override public void close() {
        boolean interrupted = false;
        synchronized (this) {
            if (closed) return;
            closed = true;
            while (borrowers != 0) {
                try { wait(); } catch (InterruptedException interruption) { interrupted = true; }
            }
            try { if (owner != null) Api.unbind.invokeExact(owner); }
            catch (Throwable failure) { throw propagate(failure); }
            finally { owner = null; if (interrupted) Thread.currentThread().interrupt(); }
        }
    }
    static final class Api {
        static final MethodHandle bind, unbind, acquire, transfer, release;
        static {
            try {
                if (!WindowsDirectoryStreams.supportedHost()) throw fault("Windows IO requires native Win64");
                Map<?, ?> receipt;
                try (var input = WindowsNativeIo.class.getResourceAsStream("/thc/cbits/windows-io-abi.json")) {
                    if (input == null) throw fault("Missing Windows IO ABI receipt");
                    receipt = (Map<?, ?>) Json.parse(new String(input.readAllBytes(), StandardCharsets.UTF_8));
                }
                if (!Long.valueOf(1).equals(receipt.get("schema")) || !Long.valueOf(16).equals(receipt.get("loanSize")) ||
                        !Long.valueOf(16).equals(receipt.get("resultSize")) || !(receipt.get("target") instanceof String target) ||
                        !target.startsWith("x86_64-") || !target.endsWith("-windows-gnu")) throw fault("Invalid Windows IO ABI receipt");
                byte[] bytes;
                try (var input = WindowsNativeIo.class.getResourceAsStream("/thc/cbits/windows-io.dll")) {
                    if (input == null) throw fault("Missing Windows IO boundary DLL");
                    bytes = input.readAllBytes();
                }
                if (!HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)).equals(receipt.get("dllSha256")))
                    throw fault("Windows IO boundary DLL hash differs from its ABI receipt");
                var file = Files.createTempFile("thc-windows-io-", ".dll");
                file.toFile().deleteOnExit(); Files.write(file, bytes);
                var lookup = SymbolLookup.libraryLookup(file, Arena.global());
                var linker = Linker.nativeLinker();
                var abi = call(linker, lookup, "abi", FunctionDescriptor.of(JAVA_LONG));
                if ((long) abi.invokeExact() != 0x0000000800100010L) throw fault("Loaded Windows IO ABI differs from Win64 layouts");
                bind = call(linker, lookup, "bind", FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS));
                unbind = call(linker, lookup, "unbind", FunctionDescriptor.ofVoid(ADDRESS));
                acquire = call(linker, lookup, "acquire", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS));
                transfer = call(linker, lookup, "transfer", FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS, ADDRESS));
                release = call(linker, lookup, "release", FunctionDescriptor.ofVoid(ADDRESS, ADDRESS));
            } catch (Throwable failure) { throw new ExceptionInInitializerError(failure); }
        }
        private static MethodHandle call(Linker linker, SymbolLookup lookup, String name, FunctionDescriptor descriptor) {
            return linker.downcallHandle(lookup.find("thc_windows_io_" + name).orElseThrow(), descriptor);
        }
    }
    @SuppressWarnings("unchecked") private static <E extends Throwable> RuntimeException propagate(Throwable failure) throws E { throw (E) failure; }
}
