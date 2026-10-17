// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.TruffleSafepoint;
import com.oracle.truffle.api.nodes.Node;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import static thc.runtime.RuntimeServiceStatus.fault;

/** One original safe/interruptible open. Only its native worker acquires the
 * file. Safepoint retries wait for the SAME request; they never replay open.
 * The request owns its pathname and fd until transfer to the existing lease.
 * No guest exception is claimed here: GHC's wrapper delivers after failure. */
public final class NativeOpenOperation implements AutoCloseable, TruffleSafepoint.Interrupter {
    private final AtomicBoolean interrupted = new AtomicBoolean();
    private MemorySegment handle;

    public NativeOpenOperation(byte[] path, int flags, int mode, int directory) {
        handle = Api.start(Objects.requireNonNull(path), flags, mode, directory);
    }
    private MemorySegment request() {
        if (handle == null) throw new IllegalStateException("Required value was null.");
        return handle;
    }
    @Override public void interrupt(Thread thread) {
        Objects.requireNonNull(thread);
        interrupted.set(true);
        Api.wake(request());
    }
    @Override public void resetInterrupted() {
        Api.reset(request());
        interrupted.set(false);
    }
    public void await(Node node, GuestThreads threads, boolean interruptible, NativeFileLease lease) throws NativeFileException {
        Objects.requireNonNull(threads);
        Objects.requireNonNull(lease);
        var request = request();
        TruffleSafepoint.InterruptibleFunction<MemorySegment, Void> action = pending -> {
            while (!Api.done(pending)) {
                if (interruptible && threads.interruptibleForeignPending()) Api.cancel(pending);
                if (interrupted.get()) throw new InterruptedException();
                Api.await(pending);
                if (interrupted.get()) throw new InterruptedException();
            }
            return null;
        };
        TruffleSafepoint.getCurrent().setBlockedFunction(node, this, action, request, null, null);
        // Interrupter is unregistered before destruction/publication. A hard
        // unwind before this point takes close(), cancelling and joining first.
        int error = Api.finish(request, lease.openSlot());
        handle = null;
        if (error != 0) throw new NativeFileException("open", error);
    }
    @Override public void close() {
        var request = handle;
        if (request == null) return;
        try { Api.cancel(request); }
        finally {
            // No LLVM/current-context dependency: also runs on hard shutdown.
            Api.finish(request, MemorySegment.NULL);
            handle = null;
        }
    }

    // Read-only OS observations for ownership assertions, never acquisition authority.
    static long observe(int kind, String path) {
        try (var arena = Arena.ofConfined()) {
            var name = path == null ? MemorySegment.NULL : arena.allocateFrom(path);
            long result = (long) Api.OBSERVE.invokeExact(kind, name);
            if (result < 0) throw fault("Native open observation failed (errno " + -result + ")");
            return result;
        } catch (Throwable failure) { throw Api.propagate(failure); }
    }

    /** This helper must execute machine code: its signal handler must never
     * enter Sulong/JVM. Linking is lazy and uses the fixed native-file capability. */
    private static final class Api {
        private static final Linker LINKER = Linker.nativeLinker();
        private static final SymbolLookup LIBRARY = library();
        private static SymbolLookup library() {
            if (!NativeFileProvider.supportedHost())
                throw new IllegalStateException("Owned open requires matching selected-ABI resources");
            try {
                String suffix = StdioHostAbi.load().librarySuffix();
                var file = NativeLibraryFiles.createTempFile("thc-open-", suffix);
                try (var input = NativeOpenOperation.class.getResourceAsStream("/thc/native/native-open-request" + suffix)) {
                    if (input == null) throw fault("Missing native open request bridge");
                    Files.copy(input, file, StandardCopyOption.REPLACE_EXISTING);
                }
                // The installed handler references this code, never request memory.
                return SymbolLookup.libraryLookup(file, Arena.global());
            } catch (Throwable failure) { throw propagate(failure); }
        }
        private static MethodHandle function(String name, MemoryLayout result, MemoryLayout... arguments) {
            return LINKER.downcallHandle(LIBRARY.find("thc_open_" + name).orElseThrow(),
                result == null ? FunctionDescriptor.ofVoid(arguments) : FunctionDescriptor.of(result, arguments));
        }
        private static final MethodHandle OBSERVE = function("observe", ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT, ValueLayout.ADDRESS);
        private static final MethodHandle START = function("start_at", ValueLayout.ADDRESS, ValueLayout.ADDRESS,
            ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS);
        private static final MethodHandle DONE = function("done", ValueLayout.JAVA_INT, ValueLayout.ADDRESS);
        private static final MethodHandle CANCEL = function("cancel", ValueLayout.JAVA_INT, ValueLayout.ADDRESS);
        private static final MethodHandle WAKE = function("wake", null, ValueLayout.ADDRESS);
        private static final MethodHandle RESET = function("reset", null, ValueLayout.ADDRESS);
        private static final MethodHandle WAIT = function("wait", ValueLayout.JAVA_INT, ValueLayout.ADDRESS);
        private static final MethodHandle FINISH = function("finish", ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS);

        private static MemorySegment start(byte[] path, int flags, int mode, int directory) {
            try (var arena = Arena.ofConfined()) {
                if (path.length == 0 || path[path.length - 1] != 0)
                    throw new IllegalStateException("Native open requires a terminated pathname snapshot");
                var bytes = arena.allocate(path.length);
                bytes.copyFrom(MemorySegment.ofArray(path));
                var error = arena.allocate(ValueLayout.JAVA_INT);
                var result = (MemorySegment) START.invokeExact(bytes, flags, mode, directory, error);
                if (result.address() == 0) throw fault("Native open request unavailable (errno " + error.get(ValueLayout.JAVA_INT, 0) + ")");
                return result;
            } catch (Throwable failure) { throw propagate(failure); }
        }
        private static boolean done(MemorySegment request) {
            try { return (int) DONE.invokeExact(request) != 0; }
            catch (Throwable failure) { throw propagate(failure); }
        }
        private static void cancel(MemorySegment request) {
            try {
                int error = (int) CANCEL.invokeExact(request);
                if (error != 0) throw fault("Native open cancellation unavailable (errno " + error + ")");
            } catch (Throwable failure) { throw propagate(failure); }
        }
        private static void wake(MemorySegment request) {
            try { WAKE.invokeExact(request); }
            catch (Throwable failure) { throw propagate(failure); }
        }
        private static void reset(MemorySegment request) {
            try { RESET.invokeExact(request); }
            catch (Throwable failure) { throw propagate(failure); }
        }
        private static void await(MemorySegment request) throws InterruptedException {
            try {
                int result = (int) WAIT.invokeExact(request);
                if (result == 0) throw new InterruptedException();
                if (result < 0) throw fault("Native open completion wait failed (errno " + -result + ")");
            } catch (Throwable failure) { throw propagate(failure); }
        }
        private static int finish(MemorySegment request, MemorySegment lease) {
            try {
                int result = (int) FINISH.invokeExact(request, lease);
                if (result < 0) throw fault("Native open worker join failed (errno " + -result + ")");
                return result;
            } catch (Throwable failure) { throw propagate(failure); }
        }
        @SuppressWarnings("unchecked") private static <T extends Throwable> RuntimeException propagate(Throwable failure) throws T {
            throw (T) failure;
        }
    }
}
