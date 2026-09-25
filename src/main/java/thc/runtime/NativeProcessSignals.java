// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import java.io.IOException;
import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.nio.file.Files;
import java.nio.file.Path;

/** Machine-code signal boundary. In particular this library must not be parsed
 * by Sulong: a POSIX asynchronous handler may never enter the Truffle runtime. */
public final class NativeProcessSignals implements AutoCloseable {
    private static final class Api {
        static final Linker LINKER = Linker.nativeLinker();
        static final MemoryLayout CAPTURE = Linker.Option.captureStateLayout();
        static final long ERRNO = CAPTURE.byteOffset(MemoryLayout.PathElement.groupElement("errno"));
        static final SymbolLookup LIBRARY = load();
        static SymbolLookup load() {
            if (!System.getProperty("os.name").equals("Linux") ||
                !(System.getProperty("os.arch").equals("amd64") || System.getProperty("os.arch").equals("x86_64")))
                throw new RuntimeFault("Process signals require Linux x86_64");
            try (var input = NativeProcessSignals.class.getResourceAsStream("/thc/native/native-process-signal-api.so")) {
                if (input == null) throw new IOException("Missing native process signal bridge");
                Path library = Files.createTempFile("thc-process-signals-", ".so");
                library.toFile().deleteOnExit();
                Files.copy(input, library, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                // A late native handler can still refer to its code after a
                // session closes. Keep the machine library mapped until exit.
                return SymbolLookup.libraryLookup(library, Arena.global());
            } catch (IOException failure) { throw failed("Cannot load process signal bridge", failure); }
        }
        static MethodHandle function(String name, FunctionDescriptor descriptor, boolean errno) {
            return LINKER.downcallHandle(LIBRARY.find("thc_signal_" + name).orElseThrow(), descriptor,
                errno ? new Linker.Option[]{Linker.Option.captureCallState("errno")} : new Linker.Option[]{});
        }
        static final MethodHandle OPEN = function("open", FunctionDescriptor.of(ValueLayout.ADDRESS), true);
        static final MethodHandle SIZE = function("info_size", FunctionDescriptor.of(ValueLayout.JAVA_INT), false);
        static final MethodHandle INSTALL = function("install", FunctionDescriptor.of(ValueLayout.JAVA_INT,
            ValueLayout.ADDRESS, ValueLayout.JAVA_INT), true);
        static final MethodHandle TAKE = function("take", FunctionDescriptor.of(ValueLayout.JAVA_INT,
            ValueLayout.ADDRESS, ValueLayout.ADDRESS), false);
        static final MethodHandle WAKE = function("wake", FunctionDescriptor.ofVoid(ValueLayout.ADDRESS), false);
        static final MethodHandle RESET = function("reset_wake", FunctionDescriptor.ofVoid(ValueLayout.ADDRESS), false);
        static final MethodHandle CLOSE = function("close", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS), true);
        static final MethodHandle EXIT = function("exit", FunctionDescriptor.ofVoid(ValueLayout.JAVA_INT), false);
    }
    public record Result(int action, int errno) {}
    private final MemorySegment session;
    private final Arena arena = Arena.ofShared();
    private final MemorySegment image;
    private boolean closed;
    public NativeProcessSignals() {
        try (Arena call = Arena.ofConfined()) {
            image = arena.allocate((int) Api.SIZE.invokeExact(), 8);
            MemorySegment errors = call.allocate(Api.CAPTURE);
            session = (MemorySegment) Api.OPEN.invokeExact(errors);
            if (session.address() == 0) throw new RuntimeFault("Process signal ownership unavailable (errno " +
                errors.get(ValueLayout.JAVA_INT, Api.ERRNO) + ")");
        } catch (RuntimeException | Error failure) { arena.close(); throw failure; }
        catch (Throwable failure) { arena.close(); throw failed("Process signal setup failed", failure); }
    }
    public Result install(int action) {
        try (Arena call = Arena.ofConfined()) {
            MemorySegment errors = call.allocate(Api.CAPTURE);
            int old = (int) Api.INSTALL.invokeExact(errors, session, action);
            return new Result(old, old == -3 ? errors.get(ValueLayout.JAVA_INT, Api.ERRNO) : 0);
        } catch (RuntimeException | Error failure) { throw failure; }
        catch (Throwable failure) { throw failed("Process signal install failed", failure); }
    }
    /** Only the single reader calls this; 0 is an explicit wake. */
    public byte[] take() {
        try {
            int result = (int) Api.TAKE.invokeExact(session, image);
            if (result == 0) return null;
            if (result != 1) throw new RuntimeFault(result == -2 ? "Process signal queue overflow" : "Process signal read failed");
            return image.toArray(ValueLayout.JAVA_BYTE);
        } catch (RuntimeException | Error failure) { throw failure; }
        catch (Throwable failure) { throw failed("Process signal read failed", failure); }
    }
    public void wake() {
        try { Api.WAKE.invokeExact(session); }
        catch (Throwable failure) { throw failed("Process signal wake failed", failure); }
    }
    public void resetWake() {
        try { Api.RESET.invokeExact(session); }
        catch (Throwable failure) { throw failed("Process signal wake reset failed", failure); }
    }
    /** Caller has stopped and joined the reader and unregistered its interrupter. */
    @Override public void close() {
        if (closed) return;
        closed = true;
        try (Arena call = Arena.ofConfined()) {
            MemorySegment errors = call.allocate(Api.CAPTURE);
            int result = (int) Api.CLOSE.invokeExact(errors, session);
            if (result != 0) throw new RuntimeFault("Process signal restoration failed");
        } catch (RuntimeException | Error failure) { throw failure; }
        catch (Throwable failure) { throw failed("Process signal close failed", failure); }
        finally { arena.close(); }
    }
    /** Explicit CLI-only termination after context shutdown. Never called by guest FFI. */
    public static void exitBySignal(int signal) {
        if (signal <= 0 || signal >= 65) throw new IllegalArgumentException("Invalid process exit signal");
        try { Api.EXIT.invokeExact(signal); }
        catch (Throwable failure) { throw failed("Process signal exit failed", failure); }
        throw new AssertionError("Signal exit returned");
    }
    private static RuntimeFault failed(String message, Throwable cause) {
        RuntimeFault failure = new RuntimeFault(message); failure.initCause(cause); return failure;
    }
}
