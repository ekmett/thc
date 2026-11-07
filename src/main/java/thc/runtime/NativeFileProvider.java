// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.source.Source;
import org.graalvm.polyglot.io.ByteSequence;
import org.graalvm.polyglot.io.IOAccess;
import org.graalvm.polyglot.Context;
import java.io.Closeable;
import java.nio.ByteBuffer;
import java.nio.ReadOnlyBufferException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.NonReadableChannelException;
import java.nio.channels.NonWritableChannelException;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.StandardOpenOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import thc.Language;
import thc.NativeIO.StandardEndpoint;
import thc.NativeFileSystem;
import thc.NativeIO;
import thc.ContextProfile;
import static thc.Main.withContextProfile;
import static thc.runtime.RuntimeFault.fault;

/** Acquisition is reachable solely through the explicitly configured fixed
 * NativeFileSystem, never from a guest fd or arbitrary embedding stream. */
public final class NativeFileProvider implements Closeable {
    private final TruffleLanguage.Env env;
    private final GuestThreads threads;
    private final NativeDirectoryOwner directory;
    private final InteropLibrary interop = InteropLibrary.getUncached();
    private final Object library;
    private final LinkedHashSet<NativeFileLease> leases = new LinkedHashSet<>();
    private boolean disposed;
    private final NativeDirectoryStreams directoryStreams;
    private ManagedProcesses processService;
    private final int statSize;
    private final int termiosSize;

    /** Resource and selected-ABI availability only; this does not admit other
     * POSIX operations such as pidfds, epoll, raw-open or terminal images. */
    public static boolean supportedHost() {
        try {
            String suffix = StdioHostAbi.load().librarySuffix();
            return NativeFileProvider.class.getResource("/thc/native/native-file-api" + suffix) != null &&
                NativeFileProvider.class.getResource("/thc/native/native-file-api.bc") != null &&
                NativeFileProvider.class.getResource("/thc/native/native-directory-api" + suffix) != null;
        } catch (java.io.IOException | RuntimeException unavailable) { return false; }
    }

    public static Context createContext(Set<StandardEndpoint> endpoints) {
        return createContext(endpoints, ContextProfile.NATIVE, false);
    }
    public static Context createContext(Set<StandardEndpoint> endpoints, ContextProfile profile) {
        return createContext(endpoints, profile, false);
    }
    /** No arbitrary Builder, FileSystem, provider attachment, or global map. */
    public static Context createContext(Set<StandardEndpoint> endpoints, ContextProfile profile, boolean allowProcesses) {
        return createContext(endpoints, profile, allowProcesses, false);
    }
    /** Helper process permission is independent of the Linux guest process transport. */
    public static Context createContext(Set<StandardEndpoint> endpoints, ContextProfile profile, boolean allowProcesses, boolean interfaceHelper) {
        return createContext(endpoints, profile, allowProcesses, interfaceHelper, null);
    }
    /** The callback completes before native directory ownership, provider construction
     * and standard endpoint installation. Failure disposes the owning context. */
    public static Context createContext(Set<StandardEndpoint> endpoints, ContextProfile profile, boolean allowProcesses,
            boolean interfaceHelper, Consumer<Context> beforeNativeStartup) {
        if (!supportedHost()) throw new UnsupportedOperationException("Native files require matching selected-ABI resources");
        if (allowProcesses && !NativeIO.supportedPosixHost())
            throw new UnsupportedOperationException("Native subprocesses require the Linux pidfd transport");
        var filesystem = new NativeFileSystem(endpoints, beforeNativeStartup != null);
        Context context;
        try {
            context = withContextProfile(Context.newBuilder("thc").allowNativeAccess(true)
                .allowCreateProcess(allowProcesses || interfaceHelper).allowIO(IOAccess.newBuilder().fileSystem(filesystem).build()), profile).build();
        } catch (Throwable failure) {
            try { filesystem.close(); } catch (Throwable closing) { failure.addSuppressed(closing); }
            throw propagate(failure);
        }
        try {
            if (beforeNativeStartup != null) beforeNativeStartup.accept(context);
            context.initialize("thc"); context.enter();
            try {
                var state = Language.currentState();
                if (state.getNativeFiles() != null) throw new IllegalStateException("Check failed.");
                filesystem.startNativeDirectory();
                var provider = new NativeFileProvider(state.getEnv(), state.getThreads(), filesystem.getDirectoryOwner());
                state.getFiles().installNative(provider, endpoints);
                state.setNativeFiles(provider);
                if (profile == ContextProfile.LAUNCHER) state.getSignals().authorizeLauncher();
            } finally { context.leave(); }
            return context;
        } catch (Throwable failure) {
            try { context.close(); } catch (Throwable closing) { failure.addSuppressed(closing); }
            try { filesystem.close(); } catch (Throwable closing) { failure.addSuppressed(closing); }
            throw propagate(failure);
        }
    }
    public static NativeFileProvider current() {
        var state = Language.currentState();
        var provider = state.getNativeFiles();
        if (provider != null && provider.env == state.getEnv()) return provider;
        throw new SecurityException("Native files require the explicit fixed-filesystem NativeIO context");
    }

    private NativeFileProvider(TruffleLanguage.Env env, GuestThreads threads, NativeDirectoryOwner directory) {
        this.env = env; this.threads = threads; this.directory = directory;
        directoryStreams = new NativeDirectoryStreams(directory);
        if (!env.isNativeAccessAllowed() || !env.isFileIOAllowed())
            throw new SecurityException("Native files require explicit file IO and native access");
        if (!supportedHost()) throw new UnsupportedOperationException("Native files require matching selected-ABI resources");
        try {
            byte[] bytes;
            String resource = "native-file-api.bc";
            try (var input = getClass().getResourceAsStream("/thc/native/" + resource)) {
                if (input == null) throw fault("Missing native file provider bridge");
                bytes = input.readAllBytes();
            }
            library = env.parseInternal(Source.newBuilder("llvm", ByteSequence.create(bytes), resource).build()).call();
            statSize = (int) interop.asLong(interop.execute(interop.readMember(library, "thc_file_stat_size")));
            long expected = PosixStat.execute(OriginalStdioOp.SIZEOF_STAT, ManagedAddress.nullAddress(), 0);
            if (statSize != expected) throw fault("Native file provider/stat image ABI mismatch");
            termiosSize = (int) interop.asLong(interop.execute(interop.readMember(library, "thc_file_termios_size")));
            env.registerOnDispose(this);
        } catch (Throwable failure) { throw propagate(failure); }
    }
    public NativeDirectoryStreams getDirectoryStreams() { return directoryStreams; }
    synchronized SulongCbits.CapiResult unixPath(OriginalStdioOp operation, Object[] arguments) {
        requireCurrent();
        if (disposed) throw propagate(new ClosedChannelException());
        var path = (ManagedAddress) arguments[0];
        try (var anchor = directory.borrow()) {
            byte[] name = path.withNativeBorrow(() -> {
                var owner = path.cbitsOwner();
                if (owner == null) return NativeUnix.anchoredPath(anchor.getDescriptor(), path);
                synchronized (owner) { return NativeUnix.anchoredPath(anchor.getDescriptor(), path); }
            });
            return NativeUnix.invoke(operation, arguments, name);
        }
    }
    public synchronized ManagedProcesses getProcesses() {
        requireCurrent();
        if (disposed) throw propagate(new ClosedChannelException());
        if (processService == null) processService = new ManagedProcesses(directory);
        return processService;
    }
    private void requireCurrent() {
        if (Language.currentState().getEnv() != env) throw fault("Native file resource belongs to another context");
    }

    private long result(String name, Object... arguments) { return result(false, name, arguments); }
    private long result(boolean signed, String name, Object... arguments) {
        requireCurrent();
        var previous = threads.enterForeign(ForeignSafety.UNSAFE);
        try (var scope = new NativeLimbScope()) {
            var error = scope.allocate(8);
            var actual = Arrays.copyOf(arguments, arguments.length + 1);
            actual[arguments.length] = error;
            long value = interop.asLong(interop.execute(interop.readMember(library, "thc_file_" + name), actual));
            long errno = error.readWord(0);
            if (errno != 0) {
                if (errno < 1 || errno > Integer.MAX_VALUE || value != -1) throw fault("Invalid native file result");
                throw new NativeFileException(name, (int) errno);
            }
            if (name.equals("open") && value == -2)
                throw new UnsupportedOperationException("Native file acquisition currently supports regular files only");
            if (value < 0 && !signed) throw fault("Negative native file success");
            return value;
        } catch (Throwable failure) { throw propagate(failure); }
        finally { threads.leaveForeign(previous); }
    }

    private NativeResource acquire(boolean readable, boolean writable, Consumer<NativeFileLease> action) {
        requireCurrent();
        var lease = new NativeFileLease();
        try {
            synchronized (this) {
                if (disposed) throw new ClosedChannelException();
                leases.add(lease);
            }
            synchronized (lease) { action.accept(lease); lease.requireOpen(); }
            synchronized (this) { if (disposed) throw new ClosedChannelException(); }
            return new NativeResource(lease, readable, writable);
        } catch (Throwable failure) {
            try { retire(lease); } catch (Throwable closing) { failure.addSuppressed(closing); }
            throw propagate(failure);
        }
    }

    public OpenedNativeFile open(String path, int mode) {
        Set<OpenOption> options = switch (mode) {
            case 0 -> Set.of(StandardOpenOption.READ);
            case 1 -> Set.of(StandardOpenOption.WRITE, StandardOpenOption.CREATE);
            case 2 -> Set.of(StandardOpenOption.WRITE, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            case 3 -> Set.of(StandardOpenOption.READ, StandardOpenOption.WRITE, StandardOpenOption.CREATE);
            default -> throw new IllegalArgumentException("Invalid native open mode");
        };
        return opened(path, new NativeOpenRequest(null, options,
            (pathValue, anchor) -> acquireFile(Objects.requireNonNull(pathValue), mode, Objects.requireNonNull(anchor))), options);
    }
    public OpenedNativeFile openRaw(byte[] path, int flags, long mode) { return openRaw(path, flags, mode, OriginalStdioOp.OPEN, null); }
    public OpenedNativeFile openRaw(byte[] path, int flags, long mode, OriginalStdioOp operation) { return openRaw(path, flags, mode, operation, null); }
    /** Guest pathname bytes never pass through String or a charset decoder. */
    public OpenedNativeFile openRaw(byte[] path, int flags, long mode, OriginalStdioOp operation, Node node) {
        if (!operation.getOpening()) throw new IllegalStateException("Check failed.");
        StdioHostAbi abi;
        try { abi = StdioHostAbi.load(); } catch (Throwable failure) { throw propagate(failure); }
        abi.requireOpenAbi();
        boolean readable = abi.openReadable(flags), writable = abi.openWritable(flags);
        Set<OpenOption> options = Set.of();
        return opened(".", new NativeOpenRequest(null, options, (ignored, anchor) -> {
            if (anchor == null) throw new IllegalStateException("Required value was null.");
            return acquire(readable, writable, lease -> {
                if (operation.getSafety().equals("unsafe")) {
                    try (var scope = new NativeLimbScope()) {
                        var name = scope.allocate((path.length + 7L) & -8L);
                        name.copyFrom(path, 0, path.length);
                        result("open_raw", lease, anchor.getLease(), name, flags, (int) mode);
                    }
                } else {
                    try (var request = new NativeOpenOperation(path, flags, (int) mode, anchor.getDescriptor())) {
                        request.await(node, threads, operation.getSafety().equals("interruptible"), lease);
                    } catch (Throwable failure) { throw propagate(failure); }
                }
            });
        }), options);
    }

    public synchronized long unlinkRaw(byte[] path) {
        requireCurrent(); if (disposed) throw propagate(new ClosedChannelException());
        try (var anchor = directory.borrow(); var scope = new NativeLimbScope()) {
            var name = scope.allocate((path.length + 7L) & -8L);
            name.copyFrom(path, 0, path.length);
            return result("unlink", anchor.getLease(), name);
        }
    }
    /** Rename anchors both paths; symlink anchors only the link, leaving target bytes as data. */
    public synchronized long pathPairRaw(OriginalStdioOp operation, byte[] target, byte[] path) {
        requireCurrent(); if (disposed) throw propagate(new ClosedChannelException());
        try (var anchor = directory.borrow(); var scope = new NativeLimbScope()) {
            var targetName = scope.allocate((target.length + 7L) & -8L);
            targetName.copyFrom(target, 0, target.length);
            var name = scope.allocate((path.length + 7L) & -8L);
            name.copyFrom(path, 0, path.length);
            return result(operation == OriginalStdioOp.RENAME ? "rename" : "symlink", anchor.getLease(), targetName, name);
        }
    }
    public synchronized byte[] readlinkRaw(byte[] path, int capacity) {
        requireCurrent(); if (disposed) throw propagate(new ClosedChannelException());
        try (var anchor = directory.borrow(); var scope = new NativeLimbScope()) {
            var name = scope.allocate((path.length + 7L) & -8L); name.copyFrom(path, 0, path.length);
            var output = scope.allocate((capacity + 7L) & -8L);
            long count = result("readlink", anchor.getLease(), name, output, (long) capacity);
            if (count < 0 || count > capacity) throw fault("Invalid native readlink result");
            var copy = new byte[(int) count]; output.copyTo(copy, 0, copy.length); return copy;
        }
    }
    public synchronized long unlinkAtInvalidRaw(byte[] path, int flags) {
        requireCurrent(); if (disposed) throw propagate(new ClosedChannelException());
        if (path.length == 0 || path[path.length - 1] != 0 || path[0] == '/') throw new IllegalStateException("Check failed.");
        try (var scope = new NativeLimbScope()) {
            var name = scope.allocate((path.length + 7L) & -8L); name.copyFrom(path, 0, path.length);
            return result("unlinkat_invalid", name, flags);
        }
    }
    private byte[] statAtImage(String name, byte[] path, int flags, NativeFileLease lease) {
        try (var scope = new NativeLimbScope()) {
            var bytes = scope.allocate((path.length + 7L) & -8L); bytes.copyFrom(path, 0, path.length);
            var image = scope.allocate(statSize);
            if (lease == null) result(name, bytes, image, flags); else result(name, lease, bytes, image, flags);
            var copy = new byte[statSize]; image.copyTo(copy, 0, copy.length); return copy;
        }
    }
    public synchronized byte[] statAtInvalidRaw(byte[] path, int flags) {
        requireCurrent(); if (disposed) throw propagate(new ClosedChannelException());
        if (path.length == 0 || path[path.length - 1] != 0 || path[0] == '/') throw new IllegalStateException("Check failed.");
        return statAtImage("fstatat_invalid", path, flags, null);
    }
    public synchronized byte[] statAtRaw(byte[] path, int flags) {
        requireCurrent(); if (disposed) throw propagate(new ClosedChannelException());
        try (var anchor = directory.borrow()) { return statAtImage("fstatat", path, flags, anchor.getLease()); }
    }
    public synchronized long changeDirectory(byte[] path) {
        requireCurrent(); if (disposed) throw propagate(new ClosedChannelException());
        directory.change(path); return 0;
    }
    public synchronized byte[] currentDirectory(int capacity) {
        requireCurrent(); if (disposed) throw propagate(new ClosedChannelException());
        return directory.name(capacity);
    }
    public synchronized long unlinkAtRaw(byte[] path, int flags) {
        requireCurrent(); if (disposed) throw propagate(new ClosedChannelException());
        try (var anchor = directory.borrow(); var scope = new NativeLimbScope()) {
            var name = scope.allocate((path.length + 7L) & -8L); name.copyFrom(path, 0, path.length);
            return result("unlinkat", anchor.getLease(), name, flags);
        }
    }
    public synchronized long accessRaw(byte[] path, int mode) {
        requireCurrent(); if (disposed) throw propagate(new ClosedChannelException());
        try (var anchor = directory.borrow(); var scope = new NativeLimbScope()) {
            var name = scope.allocate((path.length + 7L) & -8L); name.copyFrom(path, 0, path.length);
            return result("access", anchor.getLease(), name, mode);
        }
    }
    public synchronized long pathModeRaw(byte[] path, long mode, boolean createDirectory) {
        requireCurrent(); if (disposed) throw propagate(new ClosedChannelException());
        try (var anchor = directory.borrow(); var scope = new NativeLimbScope()) {
            var name = scope.allocate((path.length + 7L) & -8L); name.copyFrom(path, 0, path.length);
            return result(createDirectory ? "mkdir" : "chmod", anchor.getLease(), name, (int) mode);
        }
    }
    public synchronized byte[] statRaw(byte[] path, boolean followLinks) {
        requireCurrent(); if (disposed) throw propagate(new ClosedChannelException());
        try (var anchor = directory.borrow(); var scope = new NativeLimbScope()) {
            var name = scope.allocate((path.length + 7L) & -8L); name.copyFrom(path, 0, path.length);
            var image = scope.allocate(statSize);
            result("path_stat", anchor.getLease(), name, followLinks ? 1 : 0, image);
            var copy = new byte[statSize]; image.copyTo(copy, 0, copy.length); return copy;
        }
    }

    public OpenedNativeFile standard(StandardEndpoint endpoint) {
        Set<OpenOption> options = Set.of(endpoint == StandardEndpoint.INPUT ? StandardOpenOption.READ : StandardOpenOption.WRITE);
        return opened(".", new NativeOpenRequest(endpoint, options, (ignored, anchor) -> acquireStandard(endpoint.ordinal())), options);
    }
    private OpenedNativeFile opened(String path, NativeOpenRequest request, Set<OpenOption> options) {
        requireCurrent();
        SeekableByteChannel channel = null;
        var previous = threads.enterForeign(ForeignSafety.UNSAFE);
        try {
            var actual = new LinkedHashSet<>(options); actual.add(request);
            channel = env.getPublicTruffleFile(path).newByteChannel(actual);
            return request.commit(channel);
        } catch (Throwable failure) {
            try { if (channel != null) channel.close(); } catch (Throwable closing) { failure.addSuppressed(closing); }
            try { request.close(); } catch (Throwable closing) { failure.addSuppressed(closing); }
            throw propagate(failure);
        } finally { threads.leaveForeign(previous); }
    }
    private NativeResource acquireFile(Path path, int mode, NativeDirectoryOwner.Borrow anchor) {
        byte[] bytes = NativeDirectoryOwner.pathBytes(path.equals(Path.of("")) ? Path.of(".") : path);
        return acquire(mode == 0 || mode == 3, mode != 0, lease -> {
            try (var scope = new NativeLimbScope()) {
                var name = scope.allocate((bytes.length + 7L) & -8L); name.copyFrom(bytes, 0, bytes.length);
                result("open", lease, anchor.getLease(), name, mode);
            }
        });
    }
    private NativeResource acquireStandard(int endpoint) {
        return acquire(endpoint == 0, endpoint != 0, lease -> {
            if (endpoint < 0 || endpoint > 2) throw new IllegalArgumentException("Failed requirement.");
            result("standard", lease, endpoint);
        });
    }
    private static void requireLinuxEvents() {
        if (!NativeIO.supportedPosixHost()) throw new UnsupportedOperationException("Native events require the Linux eventfd/epoll transport");
    }
    public NativeFileResource eventfd(int initial, int flags) { requireLinuxEvents(); return acquire(true, true, lease -> result("eventfd", lease, initial, flags)); }
    public NativeFileResource epoll(int size) { requireLinuxEvents(); return acquire(false, false, lease -> result("epoll_create", lease, size)); }
    public record Pipe(NativeFileResource read, NativeFileResource write) {}
    public Pipe pipe() {
        NativeFileResource[] writer = new NativeFileResource[1];
        try {
            var reader = acquire(true, false, readLease -> writer[0] = acquire(false, true, writeLease -> result("pipe", readLease, writeLease)));
            if (writer[0] == null) throw new IllegalStateException("Required value was null.");
            return new Pipe(reader, writer[0]);
        } catch (Throwable failure) {
            try { if (writer[0] != null) writer[0].close(); } catch (Throwable closing) { failure.addSuppressed(closing); }
            throw propagate(failure);
        }
    }
    public NativeFileResource adoptProcessPipe(ManagedProcesses.Pipe pipe, boolean readable, boolean writable) {
        requireCurrent();
        NativeFileLease lease;
        synchronized (this) {
            if (disposed) throw propagate(new ClosedChannelException());
            lease = pipe.takeLease();
        }
        try {
            synchronized (this) { if (disposed) throw new ClosedChannelException(); leases.add(lease); }
            lease.requireOpen();
            synchronized (this) { if (disposed) throw new ClosedChannelException(); }
            return new NativeResource(lease, readable, writable);
        } catch (Throwable failure) {
            try { retire(lease); } catch (Throwable closing) { failure.addSuppressed(closing); }
            throw propagate(failure);
        }
    }
    private void retire(NativeFileLease lease) {
        try { lease.close(); } finally { synchronized (this) { leases.remove(lease); } }
    }
    @Override public void close() {
        ArrayList<NativeFileLease> pending;
        synchronized (this) { if (disposed) return; disposed = true; pending = new ArrayList<>(leases); }
        Throwable failed = null;
        try { if (processService != null) processService.close(); } catch (Throwable failure) { failed = failure; }
        try { directoryStreams.close(); } catch (Throwable failure) {
            if (failed == null) failed = failure; else if (failed != failure) failed.addSuppressed(failure);
        }
        for (var lease : pending) {
            try { retire(lease); } catch (Throwable failure) {
                if (failed == null) failed = failure; else if (failed != failure) failed.addSuppressed(failure);
            }
        }
        try { directory.close(); } catch (Throwable failure) {
            if (failed == null) failed = failure; else if (failed != failure) failed.addSuppressed(failure);
        }
        if (failed != null) throw propagate(failed);
    }

    private final class NativeResource implements NativeFileResource {
        private final NativeFileLease lease;
        private final boolean readable;
        private final boolean writable;
        NativeResource(NativeFileLease lease, boolean readable, boolean writable) {
            this.lease = lease; this.readable = readable; this.writable = writable;
        }
        @Override public NativeFdWait readinessWait() {
            requireCurrent();
            synchronized (NativeFileProvider.this) { if (disposed) throw propagate(new ClosedChannelException()); }
            // Never take the IO monitor while another read may be blocked.
            return NativeFdWait.acquire(lease);
        }
        @Override public void requireLive() {
            synchronized (lease) {
                requireCurrent(); lease.requireOpen();
                synchronized (NativeFileProvider.this) { if (disposed) throw propagate(new ClosedChannelException()); }
            }
        }
        @Override public long unlinkAt(byte[] path, int flags) {
            synchronized (lease) {
                requireCurrent(); lease.requireOpen();
                try (var scope = new NativeLimbScope()) {
                    var name = scope.allocate((path.length + 7L) & -8L); name.copyFrom(path, 0, path.length);
                    return result("unlinkat", lease, name, flags);
                }
            }
        }
        @Override public byte[] statAt(byte[] path, int flags) {
            synchronized (lease) { requireCurrent(); lease.requireOpen(); return statAtImage("fstatat", path, flags, lease); }
        }
        @Override public byte[] statImage() {
            synchronized (lease) {
                requireCurrent(); lease.requireOpen();
                try (var scope = new NativeLimbScope()) {
                    var image = scope.allocate(statSize); result("stat", lease, image);
                    var copy = new byte[statSize]; image.copyTo(copy, 0, copy.length); return copy;
                }
            }
        }
        @Override public void readTermios(byte[] image) {
            synchronized (lease) {
                requireCurrent(); lease.requireOpen();
                requireTermiosAbi();
                if (image.length != termiosSize) throw fault("Native termios image has the wrong size");
                try (var scope = new NativeLimbScope()) {
                    var bytes = scope.allocate((termiosSize + 7L) & -8L); bytes.copyFrom(image, 0, image.length);
                    try { result("tcgetattr", lease, bytes); } finally { bytes.copyTo(image, 0, image.length); }
                }
            }
        }
        @Override public long terminalStatus() {
            synchronized (lease) {
                requireCurrent(); lease.requireOpen();
                try { return result("isatty", lease); }
                catch (Throwable error) {
                    if (error instanceof NativeFileException nativeError) {
                        try { if (nativeError.getErrno() == (int) StdioHostAbi.load().notTerminal()) return 0; }
                        catch (Throwable failure) { throw propagate(failure); }
                    }
                    throw propagate(error);
                }
            }
        }
        @Override public void writeTermios(int action, byte[] image) {
            synchronized (lease) {
                requireCurrent(); lease.requireOpen();
                requireTermiosAbi();
                if (image.length != termiosSize) throw fault("Native termios image has the wrong size");
                try (var scope = new NativeLimbScope()) {
                    var bytes = scope.allocate((termiosSize + 7L) & -8L); bytes.copyFrom(image, 0, image.length);
                    result("tcsetattr", lease, action, bytes);
                }
            }
        }
        @Override public long statusFlags() { synchronized (lease) { requireCurrent(); lease.requireOpen(); return result("getfl", lease); } }
        @Override public long fcntl(int command, long argument, boolean hasArgument) {
            synchronized (lease) {
                requireCurrent(); lease.requireOpen();
                return result(true, "fcntl", lease, command, argument, hasArgument ? 1 : 0);
            }
        }
        @Override public boolean fcntlCreatesDescriptor(int command) {
            synchronized (lease) {
                requireCurrent(); lease.requireOpen();
                return result("fcntl_duplicate_command", command) != 0;
            }
        }
        @Override public NativeFileResource fcntlDuplicate(int command, long minimum) {
            synchronized (lease) {
                requireCurrent(); lease.requireOpen();
                return acquire(readable, writable, target -> result("fcntl_duplicate", target, lease, command, minimum));
            }
        }
        @Override public long writeEvent(long value) {
            synchronized (lease) {
                requireCurrent(); lease.requireOpen();
                requireLinuxEvents();
                if (!writable) throw propagate(new NonWritableChannelException());
                return result("eventfd_write", lease, value);
            }
        }
        @Override public int duplicateDescriptor() { return lease.duplicateForWait(); }
        @Override public int duplicateInheritableDescriptor() {
            synchronized (lease) {
                requireCurrent(); lease.requireOpen();
                return (int) result(true, "duplicate_inheritable", lease);
            }
        }
        @Override public int read(ByteBuffer destination) {
            synchronized (lease) {
                requireCurrent(); lease.requireOpen();
                if (!readable) throw propagate(new NonReadableChannelException());
                if (destination.isReadOnly()) throw new ReadOnlyBufferException();
                int count = Math.min(destination.remaining(), 1024 * 1024);
                if (count == 0) return 0;
                try (var scope = new NativeLimbScope()) {
                    var bytes = scope.allocate((count + 7L) & -8L);
                    long received = result("read", lease, bytes, (long) count);
                    if (received > count) throw fault("Native read exceeded capacity");
                    if (received == 0) return -1;
                    var copy = new byte[(int) received]; bytes.copyTo(copy, 0, copy.length); destination.put(copy); return copy.length;
                }
            }
        }
        @Override public int write(ByteBuffer source) {
            synchronized (lease) {
                requireCurrent(); lease.requireOpen();
                if (!writable) throw propagate(new NonWritableChannelException());
                int count = Math.min(source.remaining(), 1024 * 1024);
                if (count == 0) return 0;
                var copy = new byte[count]; source.duplicate().get(copy);
                try (var scope = new NativeLimbScope()) {
                    var bytes = scope.allocate((count + 7L) & -8L); bytes.copyFrom(copy, 0, copy.length);
                    long written = result("write", lease, bytes, (long) count);
                    if (written > count) throw fault("Native write exceeded capacity");
                    source.position(source.position() + (int) written); return (int) written;
                }
            }
        }
        @Override public long position() { synchronized (lease) { requireCurrent(); lease.requireOpen(); return result("seek", lease, 0L, 1); } }
        @Override public SeekableByteChannel position(long position) {
            synchronized (lease) {
                requireCurrent(); lease.requireOpen();
                if (position < 0) throw new IllegalArgumentException("Failed requirement.");
                result("seek", lease, position, 0); return this;
            }
        }
        @Override public long size() {
            synchronized (lease) {
                requireCurrent(); lease.requireOpen();
                return PosixStat.execute(OriginalStdioOp.ST_SIZE, ManagedAddress.fromByteArray(statImage()), 0);
            }
        }
        @Override public SeekableByteChannel truncate(long size) {
            synchronized (lease) {
                requireCurrent(); lease.requireOpen();
                if (size < 0) throw new IllegalArgumentException("Failed requirement.");
                if (!writable) throw propagate(new NonWritableChannelException());
                if (size < size()) { result("truncate", lease, size); if (position() > size) position(size); }
                return this;
            }
        }
        @Override public boolean isOpen() { return lease.isOpen(); }
        @Override public void close() { retire(lease); }
    }

    private void requireTermiosAbi() {
        if (termiosSize != TermiosImage.scalar(OriginalStdioOp.SIZEOF_TERMIOS, ManagedAddress.nullAddress(), 0))
            throw fault("Native file provider/termios image ABI mismatch");
    }

    @SuppressWarnings("unchecked") private static <E extends Throwable> RuntimeException propagate(Throwable failure) throws E { throw (E) failure; }
}
