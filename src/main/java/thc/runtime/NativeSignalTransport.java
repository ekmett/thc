// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import java.io.IOException;
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
import static thc.runtime.RuntimeFault.fault;

/** Machine-code signal boundary: an asynchronous native handler never enters Truffle. */
public final class NativeSignalTransport implements ProcessSignalTransport {
    private final Arena arena = Arena.ofShared();
    private final MemorySegment session;
    private final MemorySegment image;
    private boolean closed;
    public NativeSignalTransport() {
        try {
            try (var call = Arena.ofConfined()) {
                image = arena.allocate(Api.infoSize, 8);
                var errors = call.allocate(Api.capture);
                session = (MemorySegment) Api.open.invokeExact(errors);
                if (session.address() == 0) throw fault("Process signal ownership unavailable (errno " + errors.get(ValueLayout.JAVA_INT, Api.errno) + ")");
            }
        } catch (Throwable failure) { arena.close(); throw failed("Process signal setup failed", failure); }
    }
    @Override public Result install(int signal, int action) {
        try {
            if (signal == Api.abi.signal("SIGUSR2") && !userSignalAvailable())
                throw fault("SIGUSR2 requires a verified standalone JVM suspend-signal relocation (_JAVA_SR_SIGNUM=64 on Linux) and native dispositions");
            try (var call = Arena.ofConfined()) {
                var errors = call.allocate(Api.capture);
                int old = (int) Api.install.invokeExact(errors, session, signal, action);
                return new Result(old, old == -3 ? errors.get(ValueLayout.JAVA_INT, Api.errno) : 0);
            }
        } catch (Throwable failure) { throw failed("Process signal install failed", failure); }
    }
    /** Only the single reader calls this; zero is an explicit wake. */
    @Override public Event take() {
        try {
            return switch ((int) Api.take.invokeExact(session, image)) {
                case 0 -> null;
                case 1 -> new Event((int) Api.number.invokeExact(image), image.toArray(ValueLayout.JAVA_BYTE));
                case -2 -> throw fault("Process signal queue overflow");
                default -> throw fault("Process signal read failed");
            };
        } catch (Throwable failure) { throw failed("Process signal read failed", failure); }
    }
    @Override public void wake() {
        try { Api.wake.invokeExact(session); } catch (Throwable failure) { throw failed("Process signal wake failed", failure); }
    }
    @Override public void resetWake() {
        try { Api.reset.invokeExact(session); } catch (Throwable failure) { throw failed("Process signal wake reset failed", failure); }
    }
    /** Caller has stopped/joined the reader and unregistered its interrupter. */
    @Override public void close() {
        if (closed) return;
        closed = true;
        try {
            try (var call = Arena.ofConfined()) {
                var errors = call.allocate(Api.capture);
                int result = (int) Api.close.invokeExact(errors, session);
                if (result != 0) throw fault("Process signal restoration failed");
            }
        } catch (Throwable failure) { throw failed("Process signal close failed", failure); }
        finally { arena.close(); }
    }
    public static boolean userSignalAvailable() {
        try { return (int) Api.usr2Available.invokeExact() == 1; }
        catch (Throwable failure) { throw propagate(failure); }
    }
    /** Explicit CLI-only termination after context shutdown, never guest FFI. */
    public static void exitBySignal(int signal) {
        if (signal < 1 || signal >= Api.abi.signal("NSIG")) throw new IllegalArgumentException("Invalid process exit signal");
        try { Api.exit.invokeExact(signal); } catch (Throwable failure) { throw failed("Process signal exit failed", failure); }
        throw new AssertionError("Signal exit returned");
    }
    private static RuntimeException failed(String message, Throwable failure) {
        if (failure instanceof RuntimeException runtime) throw runtime;
        if (failure instanceof Error error) throw error;
        var result = new RuntimeFault(message); result.initCause(failure); throw result;
    }
    private static final class Api {
        private static final StdioHostAbi abi = abi();
        private static StdioHostAbi abi() {
            try { return StdioHostAbi.load(); }
            catch (IOException failure) { throw failed("Missing process signal ABI", failure); }
        }
        private static final Linker linker = Linker.nativeLinker();
        static final MemoryLayout capture = Linker.Option.captureStateLayout();
        static final long errno = capture.byteOffset(MemoryLayout.PathElement.groupElement("errno"));
        private static final SymbolLookup library = load();
        private static SymbolLookup load() {
            String suffix = abi.librarySuffix();
            try (var input = NativeSignalTransport.class.getResourceAsStream("/thc/native/native-process-signal-api" + suffix)) {
                if (input == null) throw new IOException("Missing native process signal bridge");
                var library = NativeLibraryFiles.createTempFile("thc-process-signals-", suffix);
                Files.copy(input, library, StandardCopyOption.REPLACE_EXISTING);
                // Late handlers may retain this code after a session closes.
                return SymbolLookup.libraryLookup(library, Arena.global());
            } catch (IOException failure) { throw failed("Cannot load process signal bridge", failure); }
        }
        private static MethodHandle function(String name, FunctionDescriptor descriptor, boolean errno) {
            return linker.downcallHandle(library.find("thc_signal_" + name).orElseThrow(), descriptor,
                errno ? new Linker.Option[]{Linker.Option.captureCallState("errno")} : new Linker.Option[0]);
        }
        static final MethodHandle open = function("open", FunctionDescriptor.of(ValueLayout.ADDRESS), true);
        static final MethodHandle size = function("info_size", FunctionDescriptor.of(ValueLayout.JAVA_INT), false);
        static final MethodHandle constant = function("constant", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT), false);
        static final MethodHandle number = function("number", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS), false);
        static final MethodHandle usr2Available = function("usr2_available", FunctionDescriptor.of(ValueLayout.JAVA_INT), false);
        static final MethodHandle install = function("install", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT), true);
        static final MethodHandle take = function("take", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS), false);
        static final MethodHandle wake = function("wake", FunctionDescriptor.ofVoid(ValueLayout.ADDRESS), false);
        static final MethodHandle reset = function("reset_wake", FunctionDescriptor.ofVoid(ValueLayout.ADDRESS), false);
        static final MethodHandle close = function("close", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS), true);
        static final MethodHandle exit = function("exit", FunctionDescriptor.ofVoid(ValueLayout.JAVA_INT), false);
        static final long infoSize = verify();
        private static long verify() {
            try {
                var names = new java.util.ArrayList<String>(); names.add("NSIG");
                names.addAll(StdioHostAbi.SIGNAL_NAMES); names.addAll(java.util.List.of("SIGBUS", "SIGSEGV"));
                for (int index = 0; index < names.size(); index++)
                    if ((int) constant.invokeExact(index) != abi.signal(names.get(index)))
                        throw fault("Native process signal constants/ABI mismatch");
                long bytes = (int) size.invokeExact();
                if (bytes != abi.getSiginfoBytes() || bytes < 1 || bytes > 512)
                    throw fault("Native process siginfo image/ABI mismatch");
                return bytes;
            } catch (Throwable failure) { throw failed("Native process signal ABI verification failed", failure); }
        }
    }
    @SuppressWarnings("unchecked") private static <E extends Throwable> RuntimeException propagate(Throwable failure) throws E { throw (E) failure; }
}
