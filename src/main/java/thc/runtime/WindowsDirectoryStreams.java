// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.nodes.Node;
import java.io.Closeable;
import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.function.Supplier;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.io.IOAccess;
import thc.ContextProfile;
import thc.Json;
import thc.Language;
import thc.Main;

import static java.lang.foreign.ValueLayout.*;

/** The original Win32 package's search API. Search HANDLEs are opaque managed
 * identities, never guest-provided native pointers. The registry serializes
 * first/next/close and disposal; caller-owned find-data bytes survive FindClose.
 * Native filesystem authority is installed only by the fixed factory below. */
public final class WindowsDirectoryStreams implements Closeable {
    private final Language.State context;
    private final IdentityHashMap<ManagedAllocation, MemorySegment> handles = new IdentityHashMap<>();
    private boolean disposed;

    private WindowsDirectoryStreams(Language.State context) { this.context = context; }
    private CarrierLocal<Long> lastError() { return context.getWindowsCodePages().lastError; }
    private void current() {
        if (Language.currentState(null) != context) throw RuntimeFault.fault("Windows directory handle belongs to another context");
        if (disposed) throw RuntimeFault.fault("Windows directory registry is closed");
    }
    private ManagedAllocation key(ManagedAddress address) {
        if (address.cbitsOffset() != 0) throw RuntimeFault.fault("Windows search handle requires its exact opaque base");
        var owner = address.cbitsOwner();
        if (owner == null) throw RuntimeFault.fault("Windows search handle has no managed identity");
        return owner;
    }
    private MemorySegment handle(ManagedAddress address) {
        var result = handles.get(key(address));
        if (result == null) throw RuntimeFault.fault("Unknown, closed or cross-context Windows search handle");
        return result;
    }
    @FunctionalInterface private interface ForeignAction<T> { T run() throws Throwable; }
    private <T> T foreign(ForeignAction<T> action) {
        var previous = context.getThreads().enterForeign(ForeignSafety.UNSAFE);
        try { return action.run(); }
        catch (Throwable failure) { throw propagate(failure); }
        finally { context.getThreads().leaveForeign(previous); }
    }
    // Preserve the original checked FFM/resource exception across Java callbacks.
    @SuppressWarnings("unchecked")
    private static <E extends Throwable> RuntimeException propagate(Throwable failure) throws E { throw (E) failure; }

    /** Keep a caller allocation stable through validation, native execution and
     * publication. Invalid output must not advance or acquire a native search. */
    private <T> T output(ManagedAddress address, Supplier<T> action) {
        return address.withNativeBorrow(() -> {
            Supplier<T> checked = () -> {
                address.requireByteRegion(Abi.getSize(), true);
                return action.get();
            };
            var owner = address.cbitsOwner();
            if (owner == null) return checked.get();
            synchronized (owner) { return checked.get(); }
        });
    }
    private String query(ManagedAddress path) {
        return path.withNativeBorrow(() -> {
            Supplier<String> read = () -> {
                var result = new StringBuilder();
                long available = path.availableBytes();
                long index = 0;
                boolean terminated = false;
                while (index + 1 < available) {
                    long unit = path.readWord8(index) | (path.readWord8(index + 1) << 8);
                    if (unit == 0) { terminated = true; break; }
                    result.append((char) unit);
                    index += 2;
                }
                if (!terminated) throw RuntimeFault.fault("Unterminated UTF-16 Windows directory query");
                String supplied = result.toString();
                // Extended namespace paths disable Win32 normalization. A slash
                // here must reach the original API unchanged, including errors.
                return supplied.startsWith("\\\\?\\") ? supplied : supplied.replace('/', '\\');
            };
            var owner = path.cbitsOwner();
            String value;
            if (owner == null) value = read.get();
            else synchronized (owner) { value = read.get(); }
            // Relative queries use context CWD without changing process CWD.
            boolean drive = value.length() >= 2 && value.charAt(1) == ':' &&
                ((value.charAt(0) >= 'A' && value.charAt(0) <= 'Z') || (value.charAt(0) >= 'a' && value.charAt(0) <= 'z'));
            if (value.isEmpty() || value.startsWith("\\\\") || (drive && value.length() >= 3 && value.charAt(2) == '\\')) return value;
            if (drive)
                throw RuntimeFault.fault("Drive-relative Windows queries require directory's absolute furnishPath");
            String cwd = context.getEnv().getCurrentWorkingDirectory().getPath();
            if (value.startsWith("\\")) return Path.of(cwd).getRoot().toString() + value.substring(1);
            int end = cwd.length();
            while (end > 0 && (cwd.charAt(end - 1) == '\\' || cwd.charAt(end - 1) == '/')) end--;
            return cwd.substring(0, end) + "\\" + value;
        });
    }

    @TruffleBoundary public synchronized ManagedAddress first(ManagedAddress path, ManagedAddress destination) {
        current();
        String name = query(path);
        return output(destination, () -> {
            try (var arena = Arena.ofConfined()) {
                var text = arena.allocate((name.length() + 1L) * 2, 2);
                for (int index = 0; index < name.length(); index++) text.set(JAVA_CHAR, index * 2L, name.charAt(index));
                var data = arena.allocate(Abi.getSize(), Abi.getAlignment());
                var error = arena.allocate(Api.capture);
                // Allocate identity before acquiring the OS resource.
                var token = ManagedAddress.fromAllocation(ManagedAllocation.immutable(new byte[0], 8, true));
                var nativeHandle = foreign(() -> (MemorySegment) Api.first.invokeExact(error, text, data));
                lastError().set(Api.error(error));
                if (nativeHandle.address() == -1L) return invalidHandle();
                try {
                    destination.copyFromByteArray(data.toArray(JAVA_BYTE), 0, Abi.getSize());
                    handles.put(key(token), nativeHandle);
                    return token;
                } catch (Throwable failure) {
                    try { int ignored = (int) Api.close.invokeExact(error, nativeHandle); }
                    catch (Throwable closing) { failure.addSuppressed(closing); }
                    throw propagate(failure);
                }
            }
        });
    }
    @TruffleBoundary public synchronized long next(ManagedAddress address, ManagedAddress destination) {
        current();
        var nativeHandle = handle(address);
        return output(destination, () -> {
            try (var arena = Arena.ofConfined()) {
                var data = arena.allocate(Abi.getSize(), Abi.getAlignment());
                var error = arena.allocate(Api.capture);
                int result = foreign(() -> (int) Api.next.invokeExact(error, nativeHandle, data));
                lastError().set(Api.error(error));
                if (result != 0) destination.copyFromByteArray(data.toArray(JAVA_BYTE), 0, Abi.getSize());
                return (long) result;
            }
        });
    }
    @TruffleBoundary public synchronized long closeSearch(ManagedAddress address) {
        current();
        var nativeHandle = handle(address);
        try (var arena = Arena.ofConfined()) {
            var error = arena.allocate(Api.capture);
            int result = foreign(() -> (int) Api.close.invokeExact(error, nativeHandle));
            lastError().set(Api.error(error));
            if (result != 0) handles.remove(key(address));
            return result;
        }
    }
    @TruffleBoundary public long error() { current(); return lastError().get(); }
    public synchronized int liveCount() { return handles.size(); }
    @Override public synchronized void close() {
        if (disposed) return;
        disposed = true;
        Throwable failure = null;
        try (var arena = Arena.ofConfined()) {
            var error = arena.allocate(Api.capture);
            for (var nativeHandle : handles.values()) {
                try {
                    if ((int) Api.close.invokeExact(error, nativeHandle) == 0)
                        throw new IllegalStateException("FindClose failed during context disposal: " + Api.error(error));
                } catch (Throwable closing) {
                    if (failure == null) failure = closing; else failure.addSuppressed(closing);
                }
            }
        }
        handles.clear();
        if (failure != null) throw propagate(failure);
    }

    public static final AbiValues Abi = new AbiValues();
    public static final class AbiValues {
        private volatile Map<?, ?> fields;
        private Map<?, ?> fields() {
            var result = fields;
            if (result != null) return result;
            synchronized (this) {
                if (fields == null) {
                    // A failed receipt load must remain retryable.
                    try { fields = load(); } catch (IOException failure) { throw propagate(failure); }
                }
                return fields;
            }
        }
        private Map<?, ?> load() throws IOException {
            if (!supportedHost()) throw new IllegalStateException("Windows directory ABI requires Windows x86_64");
            Map<?, ?> document;
            try (var stream = WindowsDirectoryStreams.class.getResourceAsStream("/thc/native/windows-directory-abi.json")) {
                if (stream == null) throw RuntimeFault.fault("Missing native Windows directory ABI");
                document = (Map<?, ?>) Json.INSTANCE.parse(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
            }
            if (!(document.get("layout") instanceof Map<?, ?>)) throw RuntimeFault.fault("Malformed Windows directory ABI");
            var layout = (Map<?, ?>) document.get("layout");
            if (!(Long.valueOf(1).equals(document.get("schema")) && "x86_64".equals(document.get("architecture")) &&
                    Long.valueOf(8).equals(layout.get("pointerBytes")) && Long.valueOf(2).equals(layout.get("wcharBytes")) &&
                    Long.valueOf(4).equals(layout.get("boolBytes")) && Long.valueOf(4).equals(layout.get("dwordBytes"))))
                throw new IllegalStateException("Check failed.");
            return layout;
        }
        public long getSize() { return (Long) fields().get("findDataBytes"); }
        public long getAlignment() { return (Long) fields().get("findDataAlignment"); }
        public long getNameOffset() { return (Long) fields().get("nameOffset"); }
        public long getNameUnits() { return (Long) fields().get("nameUnits"); }
        public long getNoMoreFiles() { return (Long) fields().get("noMoreFiles"); }
    }
    private static final class Api {
        private static final Linker linker = Linker.nativeLinker();
        private static final SymbolLookup lookup = SymbolLookup.libraryLookup("kernel32.dll", Arena.global());
        static final MemoryLayout capture = Linker.Option.captureStateLayout();
        private static final long errorOffset = capture.byteOffset(MemoryLayout.PathElement.groupElement("GetLastError"));
        private static final Linker.Option captureError = Linker.Option.captureCallState("GetLastError");
        private static MethodHandle call(String name, MemoryLayout result, MemoryLayout... arguments) {
            return linker.downcallHandle(lookup.find(name).orElseThrow(), FunctionDescriptor.of(result, arguments), captureError);
        }
        static final MethodHandle first = call("FindFirstFileW", ADDRESS, ADDRESS, ADDRESS);
        static final MethodHandle next = call("FindNextFileW", JAVA_INT, ADDRESS, ADDRESS);
        static final MethodHandle close = call("FindClose", JAVA_INT, ADDRESS);
        static long error(MemorySegment storage) { return Integer.toUnsignedLong(storage.get(JAVA_INT, errorOffset)); }
    }
    public static boolean supportedHost() {
        return System.getProperty("os.name").startsWith("Windows") &&
            (System.getProperty("os.arch").equals("amd64") || System.getProperty("os.arch").equals("x86_64"));
    }
    public static ManagedAddress invalidHandle() { return ManagedAddress.unownedNumeric(-1L); }
    public static WindowsDirectoryStreams current(Node node) {
        var streams = Language.currentState(node).getWindowsDirectories();
        if (streams == null) throw new SecurityException("Windows directory scanning requires the fixed-filesystem NativeIO context");
        return streams;
    }
    public static Context createContext() { return createContext(ContextProfile.NATIVE); }
    public static Context createContext(ContextProfile profile) {
        return createContext(profile, java.util.Set.of());
    }
    public static Context createContext(ContextProfile profile, java.util.Set<thc.NativeIO.StandardEndpoint> endpoints) {
        if (!supportedHost()) throw new IllegalStateException("Windows directory scanning requires native Windows x86_64");
        // A built Context cannot have its filesystem replaced after this
        // authority is installed. Never authenticate custom wrappers.
        var builder = Context.newBuilder("thc").allowNativeAccess(true)
            .allowIO(IOAccess.newBuilder().allowHostFileAccess(true).build());
        var context = Main.withContextProfile(builder, profile).build();
        try {
            context.initialize("thc");
            context.enter();
            try {
                var state = Language.currentState(null);
                var streams = new WindowsDirectoryStreams(state);
                state.getEnv().registerOnDispose(streams);
                state.setWindowsDirectories(streams);
                state.setWindowsNativeIo(new WindowsNativeIo(state, endpoints));
            } finally { context.leave(); }
            return context;
        } catch (Throwable failure) {
            try { context.close(); } catch (Throwable closing) { failure.addSuppressed(closing); }
            throw propagate(failure);
        }
    }
}
