// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.TruffleSafepoint;
import com.oracle.truffle.api.nodes.Node;
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
import java.util.LinkedHashSet;
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
    private final WindowsConsoleEvents console;
    private final ManagedAddress mode = ManagedAddress.windowsIoMode(this);
    private MemorySegment owner;
    private int borrowers;
    private final Set<Request> requests = new LinkedHashSet<>();
    private final Set<Thread> workers = new LinkedHashSet<>();
    private boolean stopping;
    private volatile boolean closed;

    WindowsNativeIo(Language.State context, Set<StandardEndpoint> endpoints, boolean launcher) {
        this.context = context; this.endpoints = Set.copyOf(endpoints);
        console = new WindowsConsoleEvents(context, launcher);
    }
    public WindowsConsoleEvents console() { return console; }
    ManagedAddress modeAddress() { current(); return mode; }
    /** The pinned one-byte CBool describes this CRT/WinSock descriptor
     * frontend, not the host OS or another native GHC RTS's selection. */
    int readModeByte(long displacement) {
        current();
        if (displacement != 0) throw fault("Windows IO selection permits only its one-byte CBool read");
        return 0;
    }
    private void current() {
        if (Language.currentState(null) != context || context.getWindowsNativeIo() != this || closed) throw fault("Windows native IO belongs to another or closed context");
        if (!context.getEnv().isNativeAccessAllowed()) throw new SecurityException("Windows native IO requires native access");
    }
    static WindowsNativeIo required() {
        var io = Language.currentState(null).getWindowsNativeIo();
        if (io == null) throw new SecurityException("Native Windows opening requires the fixed-filesystem NativeIO context");
        io.current();
        return io;
    }
    /** Context CWD needs an absolute UTF-16 pathname snapshot. Allocate this
     * new object addressably; never promote the caller's existing heap alias. */
    @TruffleBoundary ManagedAddress openingPath(ManagedAddress supplied) {
        current();
        if (!supplied.hasNativeIOStorage()) throw fault("Native Windows opening requires addressable caller-owned pathname storage");
        String name = WindowsDirectoryStreams.path(context, supplied);
        var address = ManagedAddress.fromAllocation(ManagedAllocation.nativeMutable((name.length() + 1L) * 2, 8));
        for (int i = 0; i < name.length(); i++) {
            address.writeWord8(i * 2L, name.charAt(i) & 255);
            address.writeWord8(i * 2L + 1, name.charAt(i) >>> 8);
        }
        return address;
    }
    @FunctionalInterface interface NativeCall { Object invoke() throws Throwable; }
    /** The exact package adapter supplies the open body. Its originating
     * selected CRT supplies errno, even if another library has a different CRT. */
    @TruffleBoundary Object invokeOpening(NativeCall call) {
        var binding = borrow();
        try (var arena = Arena.ofConfined()) {
            var error = arena.allocate(8, 4);
            Api.seedErrno.invokeExact(binding, Math.toIntExact(context.getStdio().errno()));
            try { return call.invoke(); }
            finally {
                Api.captureError.invokeExact(binding, error);
                context.getStdio().captureForeignErrno(error.get(JAVA_INT, 0));
                context.getWindowsCodePages().lastError.set(Integer.toUnsignedLong(error.get(JAVA_INT, 4)));
            }
        } catch (Throwable failure) { throw propagate(failure); }
        finally { release(); }
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
    private void descriptor(MemorySegment loan, boolean writing) {
        // Authorize the duplicated kernel object, never an integer coincidence.
        // A file opened into a vacant fd0 is a file; dup(stdin) is still stdin.
        int standard = loan.get(JAVA_INT, 12), allowed = 0;
        for (var endpoint : endpoints) {
            if (writing != (endpoint == StandardEndpoint.INPUT)) allowed |= 1 << endpoint.ordinal();
        }
        if (standard != 0 && (standard & allowed) == 0)
            throw new SecurityException("Native standard endpoint was not admitted for this operation");
    }
    /** Capture both results on the native origin, before SAFE readmission.
     * The private descriptor copy and caller's storage borrow survive the call;
     * native close/reuse of the original cannot redirect an acquired operation. */
    @TruffleBoundary public Result transfer(int fd, boolean socket, int count, ManagedAddress address, boolean writing) {
        return transfer(fd, socket, count, address, writing, null);
    }
    @TruffleBoundary public Result transfer(int fd, boolean socket, int count, ManagedAddress address, boolean writing, Node node) {
        var request = submit(fd, socket, count, address, writing);
        try { return request.await(node); }
        catch (Throwable failure) { request.abandon(); throw propagate(failure); }
    }
    /** Physical completion and guest delivery have separate lifetimes. The
     * native worker acquires and releases every loan on its own origin. */
    final class Request {
        private final int fd, count;
        private final boolean socket, writing;
        private final ManagedAddress address;
        private Thread worker;
        private boolean acquired, completed, abandoned, suspended;
        private Result result;
        private Throwable failure;
        Request(int fd, boolean socket, int count, ManagedAddress address, boolean writing) {
            this.fd = fd; this.socket = socket; this.count = count; this.address = address; this.writing = writing;
        }
        void abandon() {
            synchronized (this) { abandoned = true; notifyAll(); }
            console.wakeCompletion();
        }
        /** A saved activation may retire delivery only in its owning context.
         * Context shutdown uses abandon() directly as its lifecycle authority. */
        void discardFromSavedActivation() { current(); abandon(); }
        synchronized boolean abandoned() { return abandoned; }
        synchronized boolean suspended() { return suspended; }
        private void resumeDelivery() {
            synchronized (this) {
                if (abandoned) throw fault("Windows IO request was abandoned during context shutdown");
                suspended = false;
            }
            console.wakeCompletion();
        }
        private void suspendDelivery() {
            synchronized (this) { suspended = true; }
            console.wakeCompletion();
        }
        private synchronized boolean acquired() { acquired = true; notifyAll(); return !abandoned; }
        private synchronized void complete(Result value, Throwable caught) {
            result = value; failure = caught; completed = true; acquired = true; notifyAll();
        }
        private void run() {
            Result value = null;
            Throwable caught = null;
            try {
                if (!abandoned()) {
                    var binding = borrow();
                    try {
                        value = address == ManagedAddress.nullAddress()
                            ? perform(binding, MemorySegment.NULL)
                            : address.withNativeIOWindow(count, !writing, buffer -> perform(binding, buffer));
                    } finally { release(); }
                }
            } catch (Throwable failure) { caught = failure; }
            finally {
                // Cleanup precedes publication: a waiter never observes a
                // completed request whose native descriptor/storage is live.
                complete(value, caught);
                synchronized (WindowsNativeIo.this) { requests.remove(this); }
            }
        }
        private Result perform(MemorySegment binding, MemorySegment buffer) {
            try (var arena = Arena.ofConfined()) {
                var loan = arena.allocate(16, 8);
                var output = arena.allocate(16, 4);
                int error = (int) Api.acquire.invokeExact(binding, fd, socket ? 1 : 0, loan);
                if (error != 0) return new Result(-1, error, 0, false);
                try {
                    descriptor(loan, writing);
                    if (!acquired()) return null;
                    try (var observation = console.observeRead(!writing && !socket && count != 0 && (loan.get(JAVA_INT, 12) & 1) != 0)) {
                        for (;;) {
                            long before = observation.sequence();
                            Api.transfer.invokeExact(binding, loan, writing ? 1 : 0, count, buffer, output);
                            var value = new Result(output.get(JAVA_INT, 0), output.get(JAVA_INT, 4),
                                Integer.toUnsignedLong(output.get(JAVA_INT, 8)), output.get(JAVA_INT, 12) != 0);
                            if (!value.consoleAbort()) return value;
                            // Only an actual zero-byte console abort may retry,
                            // under the same descriptor/storage loans, after the
                            // original handler's explicit completion. Never EOF.
                            if (!observation.awaitCompletion(before, this::abandoned, this::suspended)) return value;
                        }
                    }
                } finally { Api.release.invokeExact(binding, loan); }
            } catch (Throwable failure) { throw propagate(failure); }
        }
        /** Do not let the caller abandon a queued request before the physical
         * worker owns its resources (or has failed without starting an effect). */
        void awaitAcquired(Node node) {
            current();
            var threads = context.getThreads();
            var permission = threads.enterForeign(ForeignSafety.SAFE);
            try {
                TruffleSafepoint.setBlockedThreadInterruptibleFunction(node,
                    (TruffleSafepoint.InterruptibleFunction<Request, Object>) request -> {
                        synchronized (request) { while (!request.acquired) request.wait(); }
                        return thc.runtime.Unit.INSTANCE;
                    }, this);
            } finally { threads.leaveForeign(permission); }
        }
        Result await(Node node) {
            return await(node, false, false);
        }
        /** A retained continuation keeps this physical request after its first
         * carrier departs. Only terminal discard calls abandon(); resumption
         * observes the original completion, never prepares another request. */
        @TruffleBoundary(transferToInterpreterOnException = false)
        Result awaitResumable(Node node, boolean compiledAtCut) {
            current();
            resumeDelivery();
            return await(node, true, compiledAtCut);
        }
        /** The generated bytecode activation owns only its explicitly saved
         * token. A yield retains registration; completion/failure releases it.
         * No guest callback or delimited capture occurs inside this await. */
        Result awaitResumable(com.oracle.truffle.api.frame.MaterializedFrame frame, Node node, boolean compiledAtCut) {
            current();
            BytecodeContinuations.ownRequest(frame, this);
            try {
                var value = awaitResumable(node, compiledAtCut);
                BytecodeContinuations.releaseRequest(frame, this);
                return value;
            } catch (AsyncBlocked cut) { throw cut; }
            catch (RuntimeException | Error failure) {
                BytecodeContinuations.releaseRequest(frame, this);
                abandon();
                throw failure;
            }
        }
        private Result await(Node node, boolean resumable, boolean compiledAtCut) {
            awaitAcquired(node);
            var threads = context.getThreads();
            for (;;) {
                var permission = threads.enterForeign(ForeignSafety.SAFE);
                try {
                    TruffleSafepoint.setBlockedThreadInterruptibleFunction(node,
                        (TruffleSafepoint.InterruptibleFunction<Request, Object>) request -> {
                            synchronized (request) {
                                while (!request.completed && !request.abandoned && !threads.interruptibleForeignPending()) request.wait();
                            }
                            return thc.runtime.Unit.INSTANCE;
                        }, this);
                } finally { threads.leaveForeign(permission); }
                synchronized (this) {
                    if (completed && !abandoned) {
                        if (failure != null) throw propagate(failure);
                        return result;
                    }
                    if (abandoned) throw fault("Windows IO request was abandoned during context shutdown");
                }
                // A wake is only observation. Only the original guest, after
                // SAFE readmission, may claim its actual throwTo request.
                var incoming = threads.poll(node, true);
                if (incoming != null) {
                    if (resumable) suspendDelivery(); else abandon();
                    incoming.compiledCapture = compiledAtCut;
                    throw new AsyncBlocked(incoming, node);
                }
                // A cancelled sender does not revoke this physical request.
            }
        }
        void join() {
            boolean interrupted = Thread.interrupted();
            for (;;) {
                try { worker.join(); break; }
                catch (InterruptedException failure) { interrupted = true; }
            }
            if (interrupted) Thread.currentThread().interrupt();
        }
    }
    Request submit(int fd, boolean socket, int count, ManagedAddress address, boolean writing) {
        current();
        if (count < 0) throw fault("Windows RTS transfer requires a nonnegative CInt length");
        if (address == ManagedAddress.nullAddress()) {
            if (count != 0) throw fault("Windows RTS transfer requires storage for a nonzero length");
        } else {
            address.requireByteRegion(count, !writing);
            if (!address.hasNativeIOStorage()) throw fault("Windows RTS transfer requires addressable caller-owned storage");
        }
        synchronized (this) {
            if (stopping) throw fault("Windows native IO is stopping");
            workers.removeIf(worker -> !worker.isAlive());
            var request = new Request(fd, socket, count, address, writing);
            // A physical native worker has no Haskell identity or admission.
            // It is owned/joined here; GuestThreads still owns guest execution.
            request.worker = context.getEnv().newTruffleThreadBuilder(request::run).virtual(false).build();
            requests.add(request);
            workers.add(request.worker);
            try {
                request.worker.start();
                return request;
            } catch (Throwable failure) { requests.remove(request); workers.remove(request.worker); throw failure; }
        }
    }
    /** Abandon guest delivery, never claim to have aborted a synchronous
     * native syscall. Its physical worker retains every loan until completion. */
    public synchronized void requestStop() {
        stopping = true;
        for (var request : requests) request.abandon();
        console.requestStop();
    }
    /** Must run before context carrier joins and native owner disposal. */
    public void finishRequests() {
        Thread[] pending;
        synchronized (this) { requestStop(); pending = workers.toArray(Thread[]::new); }
        boolean interrupted = Thread.interrupted();
        Throwable consoleFailure = null;
        try {
            try { console.close(); } catch (Throwable failure) { consoleFailure = failure; }
            for (var worker : pending) for (;;) {
                try { worker.join(); break; }
                catch (InterruptedException failure) { interrupted = true; }
            }
            synchronized (this) { requests.clear(); workers.clear(); }
        } finally { if (interrupted) Thread.currentThread().interrupt(); }
        if (consoleFailure != null) throw propagate(consoleFailure);
    }
    /** Disposal waits for the native component/storage loans, without unpinning
     * the selected DLL while a SAFE call is still using its imported functions. */
    @Override public void close() {
        finishRequests();
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
        static final MethodHandle bind, unbind, acquire, transfer, release, seedErrno, captureError;
        static final MethodHandle consoleOpen, consoleInstall, consoleSequence, consolePending, consoleTake,
            consoleWait, consoleWake, consoleStop, consoleClose;
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
                seedErrno = call(linker, lookup, "seed_errno", FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT));
                captureError = call(linker, lookup, "capture_error", FunctionDescriptor.ofVoid(ADDRESS, ADDRESS));
                consoleOpen = call(linker, lookup, "console_open", FunctionDescriptor.of(ADDRESS, ADDRESS));
                consoleInstall = call(linker, lookup, "console_install", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_LONG));
                consoleSequence = call(linker, lookup, "console_sequence", FunctionDescriptor.of(JAVA_LONG, ADDRESS));
                consolePending = call(linker, lookup, "console_pending", FunctionDescriptor.of(JAVA_LONG, ADDRESS, JAVA_LONG));
                consoleTake = call(linker, lookup, "console_take", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
                consoleWait = call(linker, lookup, "console_wait", FunctionDescriptor.of(JAVA_INT, ADDRESS));
                consoleWake = call(linker, lookup, "console_wake", FunctionDescriptor.ofVoid(ADDRESS));
                consoleStop = call(linker, lookup, "console_stop", FunctionDescriptor.of(JAVA_INT, ADDRESS));
                consoleClose = call(linker, lookup, "console_close", FunctionDescriptor.ofVoid(ADDRESS));
            } catch (Throwable failure) { throw new ExceptionInInitializerError(failure); }
        }
        private static MethodHandle call(Linker linker, SymbolLookup lookup, String name, FunctionDescriptor descriptor) {
            return linker.downcallHandle(lookup.find("thc_windows_io_" + name).orElseThrow(), descriptor);
        }
    }
    @SuppressWarnings("unchecked") private static <E extends Throwable> RuntimeException propagate(Throwable failure) throws E { throw (E) failure; }
}
