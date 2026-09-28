// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.TruffleFile;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.nodes.Node;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.InvalidPathException;
import java.nio.file.NoSuchFileException;
import java.nio.file.NotDirectoryException;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import thc.Language;
import thc.NativeIO.StandardEndpoint;

/** Explicit thc_io_v1 service, not a POSIX ABI. Descriptors and reader/writer
 * claims belong to one THC context. Native readiness has a separate cancellable
 * capability; it is not interruptible foreign byte transport or a scheduler. */
public final class ManagedFiles {
    private final TruffleLanguage.Env env;
    private final GuestThreads threads;
    private final long descriptorLimit;
    private final Consumer<NativeFdWait> signalReadinessClose;
    private enum Readiness { REGULAR_FILE, UNAVAILABLE, NATIVE_UNCLASSIFIED }
    private enum AnonymousKind { PIPE, EVENT, EPOLL }
    private record FileIdentity(long device, long inode) {}
    private static final class OpenClaim {
        FileIdentity identity;
        final boolean writable;
        final Long reserved;
        final Thread owner = Thread.currentThread();
        final CountDownLatch finished = new CountDownLatch(1);
        OpenClaim(FileIdentity identity, boolean writable) { this(identity, writable, null); }
        OpenClaim(FileIdentity identity, boolean writable, Long reserved) {
            this.identity = identity; this.writable = writable; this.reserved = reserved;
        }
    }
    private static final class OpenDescription {
        final InputStream input;
        final OutputStream output;
        final SeekableByteChannel channel;
        final NativeFileResource nativeResource;
        final NativeEpoll epoll;
        final AnonymousKind anonymousKind;
        final FileIdentity identity;
        final boolean readable, writable, append, canExtend;
        final Readiness readiness;
        // Registry protects references; IO and physical retirement share this
        // object's monitor. The registry never waits for that monitor while held.
        long references = 1;
        boolean closed;
        final LinkedHashSet<NativeEpoll.Registration> epollRegistrations = new LinkedHashSet<>();
        OpenDescription(InputStream input, OutputStream output, SeekableByteChannel channel,
            NativeFileResource nativeResource, NativeEpoll epoll, AnonymousKind anonymousKind, FileIdentity identity,
            boolean readable, boolean writable, boolean append, boolean canExtend, Readiness readiness) {
            this.input = input; this.output = output; this.channel = channel; this.nativeResource = nativeResource;
            this.epoll = epoll; this.anonymousKind = anonymousKind; this.identity = identity;
            this.readable = readable; this.writable = writable; this.append = append; this.canExtend = canExtend;
            this.readiness = readiness;
        }
        static OpenDescription input(InputStream input) {
            return new OpenDescription(input, null, null, null, null, null, null, true, false, false, true, Readiness.UNAVAILABLE);
        }
        static OpenDescription output(OutputStream output) {
            return new OpenDescription(null, output, null, null, null, null, null, false, true, false, true, Readiness.UNAVAILABLE);
        }
    }
    private static final class Descriptor {
        final OpenDescription owner;
        boolean closed;
        final LinkedHashSet<NativeFdWait> readinessWaits = new LinkedHashSet<>();
        final LinkedHashSet<NativeEventWait.Watch> eventWaits = new LinkedHashSet<>();
        Descriptor(OpenDescription owner) { this.owner = owner; }
    }
    private record Failure(long kind, String message, long nativeErrno) {
        Failure(long kind, String message) { this(kind, message, 0); }
    }
    private static final class FileFailure extends IOException {
        final long kind;
        FileFailure(long kind, String message) { super(message); this.kind = kind; }
    }
    private final ThreadLocal<Failure> failure = ThreadLocal.withInitial(() -> new Failure(0, ""));
    private final LinkedHashMap<Long, Descriptor> descriptors = new LinkedHashMap<>();
    // Includes zero-reference owners until their provider callback returns.
    private final LinkedHashSet<OpenDescription> owners = new LinkedHashSet<>();
    private final ArrayList<OpenClaim> opening = new ArrayList<>();
    private long nextDescriptor = 3; // Private open API does not reuse numbers.
    private volatile boolean disposed;
    private NativeFileProvider nativeProvider;
    private final LinkedHashMap<Long, Descriptor> eventControls = new LinkedHashMap<>();
    private final Object nativeAbiLock = new Object();
    private volatile StdioHostAbi nativeAbi;
    private final Object processForeignLock = new Object();
    private volatile ManagedProcessForeign processForeign;

    public ManagedFiles(TruffleLanguage.Env env, GuestThreads threads) { this(env, threads, (long) Integer.MAX_VALUE + 1); }
    public ManagedFiles(TruffleLanguage.Env env, GuestThreads threads, long descriptorLimit) {
        this(env, threads, descriptorLimit, NativeFdWait::descriptorClosed);
    }
    public ManagedFiles(TruffleLanguage.Env env, GuestThreads threads, long descriptorLimit, Consumer<NativeFdWait> signalReadinessClose) {
        this.env = env; this.threads = threads; this.descriptorLimit = descriptorLimit; this.signalReadinessClose = signalReadinessClose;
        descriptors.put(0L, new Descriptor(OpenDescription.input(env.in())));
        descriptors.put(1L, new Descriptor(OpenDescription.output(env.out())));
        descriptors.put(2L, new Descriptor(OpenDescription.output(env.err())));
        for (var descriptor : descriptors.values()) owners.add(descriptor.owner);
        if (descriptorLimit < 3 || descriptorLimit > (long) Integer.MAX_VALUE + 1) throw new IllegalArgumentException("Failed requirement.");
    }
    private StdioHostAbi nativeAbi() {
        var result = nativeAbi;
        if (result == null) synchronized (nativeAbiLock) {
            result = nativeAbi;
            if (result == null) {
                try { nativeAbi = result = StdioHostAbi.load(); }
                catch (IOException failure) { throw propagate(failure); }
            }
        }
        return result;
    }
    public ManagedProcessForeign getProcessForeign() {
        var result = processForeign;
        if (result == null) synchronized (processForeignLock) {
            result = processForeign;
            if (result == null) processForeign = result = new ManagedProcessForeign();
        }
        return result;
    }
    @TruffleBoundary public long errorKind() { return failure.get().kind; }
    @TruffleBoundary public long nativeErrno() { return failure.get().nativeErrno; }
    @TruffleBoundary public ManagedAddress errorMessage() {
        byte[] bytes = failure.get().message.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return ManagedAddress.fromHex(java.util.HexFormat.of().formatHex(bytes));
    }
    private static RuntimeException fail(long kind, String message) { return propagate(new FileFailure(kind, message)); }
    private synchronized Descriptor descriptor(long fd) {
        var entry = descriptors.get(fd);
        if (entry == null) throw fail(4, "Closed or unknown THC file descriptor: " + fd);
        return entry;
    }
    // Registry held. Notify the old identity before physical retirement/reuse.
    private Throwable invalidate(Descriptor entry) {
        entry.closed = true;
        eventControls.values().removeIf(value -> value == entry);
        Throwable failure = null;
        for (var request : entry.readinessWaits) {
            try { signalReadinessClose.accept(request); }
            catch (Throwable error) { failure = combineFailures(failure, error); }
        }
        for (var request : entry.eventWaits) {
            try { request.descriptorClosed(); }
            catch (Throwable error) { failure = combineFailures(failure, error); }
        }
        return failure;
    }
    private static Throwable combineFailures(Throwable first, Throwable next) {
        if (first == null) return next;
        if (next != null && next != first) first.addSuppressed(next);
        return first;
    }
    @FunctionalInterface private interface OwnerAction<T> { T run(OpenDescription owner) throws Throwable; }
    private <T> T withDescriptor(long fd, OwnerAction<T> action) {
        var entry = descriptor(fd);
        synchronized (entry.owner) {
            synchronized (this) {
                if (entry.closed || descriptors.get(fd) != entry) throw fail(4, "Closed or unknown THC file descriptor: " + fd);
            }
            try { return action.run(entry.owner); } catch (Throwable failure) { throw propagate(failure); }
        }
    }
    private boolean reserved(long fd) {
        for (var claim : opening) if (claim.reserved != null && claim.reserved == fd) return true;
        return false;
    }
    private long unusedDescriptor(long first) {
        long fd = first;
        while (fd < descriptorLimit && (descriptors.containsKey(fd) || reserved(fd))) fd++;
        if (fd == descriptorLimit) throw fail(10, "THC file descriptor space exhausted");
        return fd;
    }
    private void retire(OpenDescription owner) {
        synchronized (owner) {
            if (owner.closed) return;
            owner.closed = true;
            Throwable failure = null;
            try {
                List<NativeEpoll.Registration> registrations;
                synchronized (this) { registrations = new ArrayList<>(owner.epollRegistrations); }
                for (var registration : registrations) {
                    try { registration.close(); } catch (Throwable error) { failure = combineFailures(failure, error); }
                }
                try { if (owner.epoll != null) owner.epoll.close(); } catch (Throwable error) { failure = combineFailures(failure, error); }
                try { if (owner.channel != null) owner.channel.close(); } catch (Throwable error) { failure = combineFailures(failure, error); }
                try { if (owner.output != null) owner.output.flush(); } catch (Throwable error) { failure = combineFailures(failure, error); }
            } finally { synchronized (this) { owners.remove(owner); } }
            if (failure != null) throw propagate(failure);
        }
    }
    private FileIdentity identity(TruffleFile file, String name) throws IOException {
        var attributes = file.getAttributes(List.of(TruffleFile.IS_REGULAR_FILE, TruffleFile.IS_DIRECTORY, TruffleFile.UNIX_DEV, TruffleFile.UNIX_INODE));
        if (Boolean.TRUE.equals(attributes.get(TruffleFile.IS_DIRECTORY))) throw fail(9, "Cannot open a directory: " + name);
        if (!Boolean.TRUE.equals(attributes.get(TruffleFile.IS_REGULAR_FILE))) throw fail(7, "Only regular files are supported: " + name);
        Long device = attributes.get(TruffleFile.UNIX_DEV), inode = attributes.get(TruffleFile.UNIX_INODE);
        if (device == null || inode == null) throw fail(7, "File provider does not expose stable Unix identity: " + name);
        return new FileIdentity(device, inode);
    }
    private OpenDescription nativeDescription(NativeFileResource resource, boolean readable, boolean writable, boolean append, boolean canExtend) {
        var image = ManagedAddress.fromByteArray(resource.statImage());
        long mode = PosixStat.execute(OriginalStdioOp.ST_MODE, image, 0);
        boolean regular = PosixStat.execute(OriginalStdioOp.IS_REG, ManagedAddress.nullAddress(), mode) == 1;
        var identity = regular ? new FileIdentity(PosixStat.execute(OriginalStdioOp.ST_DEV, image, 0), PosixStat.execute(OriginalStdioOp.ST_INO, image, 0)) : null;
        return new OpenDescription(null, null, resource, resource, null, null, identity, readable, writable, append, canExtend,
            regular ? Readiness.REGULAR_FILE : Readiness.UNAVAILABLE);
    }
    public void installNative(NativeFileProvider provider, Set<StandardEndpoint> endpoints) {
        var claim = new OpenClaim(null, false);
        var acquired = new LinkedHashMap<Long, OpenDescription>();
        var retired = new ArrayList<OpenDescription>();
        boolean published = false;
        var previous = threads.enterForeign(ForeignSafety.UNSAFE);
        try {
            synchronized (this) {
                check(!disposed && nativeProvider == null && nextDescriptor == 3 && opening.isEmpty());
                boolean singles = descriptors.keySet().equals(Set.of(0L, 1L, 2L));
                if (singles) for (var descriptor : descriptors.values()) if (descriptor.owner.references != 1) { singles = false; break; }
                check(singles);
                opening.add(claim);
            }
            for (var endpoint : endpoints) {
                var resource = provider.standard(endpoint);
                try { acquired.put((long) endpoint.ordinal(), nativeDescription(resource, endpoint == StandardEndpoint.INPUT, endpoint != StandardEndpoint.INPUT, false, false)); }
                catch (Throwable failure) {
                    try { resource.close(); } catch (Throwable closing) { failure.addSuppressed(closing); }
                    throw failure;
                }
            }
            synchronized (this) {
                if (disposed) throw fail(4, "THC file context is closed");
                for (var pair : acquired.entrySet()) {
                    var old = descriptors.get(pair.getKey());
                    old.closed = true;
                    old.owner.references = 0;
                    retired.add(old.owner);
                    owners.add(pair.getValue());
                    descriptors.put(pair.getKey(), new Descriptor(pair.getValue()));
                }
                nativeProvider = provider;
                published = true;
            }
            for (var owner : retired) retire(owner);
            for (var owner : acquired.values()) owner.nativeResource.requireLive();
            synchronized (this) {
                if (disposed) throw fail(4, "THC file context was disposed during native installation");
                check(nativeProvider == provider);
            }
        } catch (Throwable failure) {
            if (!published) for (var owner : acquired.values()) {
                try { retire(owner); } catch (Throwable closing) { failure.addSuppressed(closing); }
            }
            throw propagate(failure);
        } finally {
            synchronized (this) { opening.remove(claim); }
            claim.finished.countDown();
            threads.leaveForeign(previous);
        }
    }
    private void requireUnclaimed(FileIdentity identity, boolean writable, String name, OpenClaim own) {
        for (var other : owners) if (identity.equals(other.identity) && (writable || other.writable))
            throw fail(8, "File already has an incompatible reader/writer in this THC context: " + name);
        for (var other : opening) if (other != own && identity.equals(other.identity) && (writable || other.writable))
            throw fail(8, "File already has an incompatible reader/writer in this THC context: " + name);
    }
    @FunctionalInterface private interface FileAction { long run() throws Throwable; }
    private long result(FileAction action) { return result(ForeignSafety.UNSAFE, action); }
    private long result(ForeignSafety safety, FileAction action) {
        if (disposed) { failure.set(new Failure(4, "THC file context is closed")); return -1; }
        var previous = threads.enterForeign(safety);
        try { return action.run(); }
        catch (FileFailure error) { failure.set(new Failure(error.kind, message(error, "File operation failed"))); return -1; }
        catch (NoSuchFileException error) { failure.set(new Failure(1, message(error, "File does not exist"))); return -1; }
        catch (NotDirectoryException error) { failure.set(new Failure(1, message(error, "A path component is not a directory"))); return -1; }
        catch (AccessDeniedException error) { failure.set(new Failure(2, message(error, "File access denied"))); return -1; }
        catch (SecurityException error) { failure.set(new Failure(2, message(error, "File access denied by the embedding context"))); return -1; }
        catch (FileAlreadyExistsException error) { failure.set(new Failure(3, message(error, "File already exists"))); return -1; }
        catch (InvalidPathException error) { failure.set(new Failure(5, message(error, "Invalid file path"))); return -1; }
        catch (UnsupportedOperationException error) { failure.set(new Failure(7, message(error, "File operation is not supported"))); return -1; }
        catch (NativeFileException error) {
            failure.set(new Failure(nativeAbi().privateErrorKind(error.getErrno()), message(error, "Native file operation failed"), error.getErrno())); return -1;
        }
        catch (IOException error) { failure.set(new Failure(6, message(error, "File operation failed"))); return -1; }
        catch (Throwable error) { throw propagate(error); }
        finally { threads.leaveForeign(previous); }
    }
    private static String message(Throwable error, String fallback) { return error.getMessage() == null ? fallback : error.getMessage(); }

    /** Read0/Write1/Append2/ReadWrite3. Truncation follows this context's writer
     * claim; legacy pre/post identity checks do not promise host mutation/ABA
     * exclusion or replace native locks. */
    public long open(ManagedAddress path, long mode) { return open(path, mode, ForeignSafety.UNSAFE); }
    @TruffleBoundary public long open(ManagedAddress path, long mode, ForeignSafety safety) {
        String name = path.utf8();
        return result(safety, () -> {
            if (mode < 0 || mode > 3) throw fail(5, "Unknown THC open mode: " + mode);
            if (name.isEmpty()) throw fail(1, "Empty file path");
            synchronized (this) {
                if (disposed) throw fail(4, "THC file context is closed");
                unusedDescriptor(nextDescriptor);
            }
            NativeFileProvider nativeProvider;
            synchronized (this) { nativeProvider = this.nativeProvider; }
            if (nativeProvider != null) return openNative(nativeProvider, name, mode);
            var file = env.getPublicTruffleFile(name);
            boolean writable = mode != 0;
            FileIdentity before;
            try { before = identity(file, name); } catch (NoSuchFileException absent) { before = null; }
            Set<StandardOpenOption> options = switch ((int) mode) {
                case 0 -> Set.of(StandardOpenOption.READ);
                case 2 -> new LinkedHashSet<>(List.of(StandardOpenOption.WRITE, StandardOpenOption.CREATE, StandardOpenOption.APPEND));
                case 3 -> new LinkedHashSet<>(List.of(StandardOpenOption.READ, StandardOpenOption.WRITE, StandardOpenOption.CREATE));
                default -> new LinkedHashSet<>(List.of(StandardOpenOption.WRITE, StandardOpenOption.CREATE));
            };
            var claim = new OpenClaim(before, writable);
            SeekableByteChannel channel = null;
            try {
                synchronized (this) {
                    if (disposed) throw fail(4, "THC file context is closed");
                    unusedDescriptor(nextDescriptor);
                    if (before != null) requireUnclaimed(before, writable, name, null);
                    opening.add(claim);
                }
                var acquired = file.newByteChannel(options);
                channel = acquired;
                var after = identity(file, name);
                if (before != null && !before.equals(after)) throw fail(8, "File identity changed while opening: " + name);
                synchronized (this) {
                    if (disposed) throw fail(4, "THC file context is closed");
                    requireUnclaimed(after, writable, name, claim);
                    claim.identity = after;
                }
                if (mode == 1) acquired.truncate(0);
                long fd;
                synchronized (this) {
                    if (disposed) throw fail(4, "THC file context is closed");
                    requireUnclaimed(after, writable, name, claim);
                    fd = unusedDescriptor(nextDescriptor);
                    nextDescriptor = fd + 1;
                    var owner = new OpenDescription(null, null, acquired, null, null, null, after,
                        mode == 0 || mode == 3, writable, mode == 2, true, Readiness.REGULAR_FILE);
                    owners.add(owner);
                    descriptors.put(fd, new Descriptor(owner));
                    opening.remove(claim);
                }
                channel = null;
                return fd;
            } catch (Throwable error) {
                try { if (channel != null) channel.close(); } catch (Throwable closing) { error.addSuppressed(closing); }
                throw error;
            } finally {
                synchronized (this) { opening.remove(claim); }
                claim.finished.countDown();
            }
        });
    }
    private long openNative(NativeFileProvider provider, String name, long mode) throws Throwable {
        var claim = new OpenClaim(null, mode != 0);
        OpenedNativeFile resource = null;
        try {
            synchronized (this) {
                if (disposed) throw fail(4, "THC file context is closed");
                unusedDescriptor(nextDescriptor);
                opening.add(claim);
            }
            var acquired = provider.open(name, (int) mode);
            resource = acquired;
            var owner = nativeDescription(acquired, mode == 0 || mode == 3, mode != 0, mode == 2, true);
            var identity = owner.identity;
            if (identity == null) throw fail(7, "Native acquisition is not a regular file: " + name);
            synchronized (this) {
                if (disposed) throw fail(4, "THC file context is closed");
                requireUnclaimed(identity, owner.writable, name, claim);
                claim.identity = identity;
            }
            if (mode == 1) acquired.truncate(0);
            long fd;
            synchronized (this) {
                if (disposed) throw fail(4, "THC file context is closed");
                requireUnclaimed(identity, owner.writable, name, claim);
                fd = unusedDescriptor(nextDescriptor);
                nextDescriptor = fd + 1;
                owners.add(owner);
                descriptors.put(fd, new Descriptor(owner));
                opening.remove(claim);
            }
            resource = null;
            return fd;
        } catch (Throwable failure) {
            try { if (resource != null) resource.close(); } catch (Throwable closing) { failure.addSuppressed(closing); }
            throw failure;
        } finally {
            synchronized (this) { opening.remove(claim); }
            claim.finished.countDown();
        }
    }
    private byte[] originalPathBytes(ManagedAddress path) {
        var allocation = path.cbitsOwner();
        return path.withNativeBorrow(() -> {
            if (allocation == null) return snapshotPath(path);
            synchronized (allocation) { return snapshotPath(path); }
        });
    }
    private byte[] snapshotPath(ManagedAddress path) {
        long length = path.cStringLength();
        if (length >= Integer.MAX_VALUE) throw RuntimeFault.fault("Native path exceeds managed byte capacity");
        var bytes = new byte[(int) length + 1];
        for (int i = 0; i < bytes.length; i++) bytes[i] = (byte) path.readWord8(i);
        return bytes;
    }
    @TruffleBoundary public ManagedAddress openDirectoryOriginal(ManagedAddress path) {
        byte[] bytes = originalPathBytes(path);
        ManagedAddress[] address = {ManagedAddress.nullAddress()};
        result(() -> {
            NativeFileProvider provider;
            synchronized (this) {
                if (disposed) throw fail(4, "THC file context is closed");
                provider = nativeProvider;
                if (provider == null) throw fail(7, "Directory streams require the explicit NativeIO context");
            }
            address[0] = provider.getDirectoryStreams().open(bytes);
            return 0;
        });
        return address[0];
    }
    @TruffleBoundary public ManagedAddress openDirectoryDescriptor(long fd) {
        ManagedAddress[] address = {ManagedAddress.nullAddress()};
        Throwable[] postCommitFailure = {null};
        result(() -> {
            var entry = descriptor(fd);
            synchronized (entry.owner) {
                NativeFileProvider provider;
                synchronized (this) {
                    if (disposed || entry.closed || descriptors.get(fd) != entry) throw fail(4, "Closed or unknown THC file descriptor: " + fd);
                    provider = nativeProvider;
                    if (provider == null) throw fail(7, "Directory streams require the explicit NativeIO context");
                }
                var nativeResource = entry.owner.nativeResource;
                if (nativeResource == null) throw fail(4, "Directory fd is not a native resource");
                var acquired = provider.getDirectoryStreams().fromDescriptor(nativeResource);
                boolean published = false;
                try {
                    Throwable wakeFailure;
                    boolean last;
                    synchronized (this) {
                        if (disposed || entry.closed || descriptors.get(fd) != entry) throw fail(4, "Directory descriptor was closed during acquisition");
                        descriptors.remove(fd);
                        wakeFailure = invalidate(entry);
                        last = --entry.owner.references == 0;
                    }
                    published = true;
                    address[0] = acquired;
                    if (last) try { retire(entry.owner); } catch (Throwable failure) {
                        if (!(failure instanceof IOException)) throw failure;
                    }
                    postCommitFailure[0] = wakeFailure;
                } finally { if (!published) provider.getDirectoryStreams().abandon(acquired); }
            }
            return 0;
        });
        if (postCommitFailure[0] != null) throw propagate(postCommitFailure[0]);
        return address[0];
    }
    private long[] anonymousDescriptors(AnonymousKind kind, int initial, int flags, Consumer<long[]> publish) throws Throwable {
        boolean pipe = kind == AnonymousKind.PIPE;
        var claims = new ArrayList<OpenClaim>();
        NativeFileProvider provider;
        synchronized (this) {
            if (disposed) throw fail(4, "THC file context is closed");
            provider = nativeProvider;
            if (provider == null) throw fail(7, "Event descriptors require the explicit NativeIO context");
            try {
                for (int i = 0; i < (pipe ? 2 : 1); i++) {
                    var claim = new OpenClaim(null, !pipe || i == 1, unusedDescriptor(0));
                    claims.add(claim); opening.add(claim);
                }
            } catch (Throwable failure) {
                for (var claim : claims) { opening.remove(claim); claim.finished.countDown(); }
                throw failure;
            }
        }
        List<NativeFileResource> acquired = List.of();
        var epolls = new ArrayList<NativeEpoll>();
        try {
            if (pipe) { var pair = provider.pipe(); acquired = List.of(pair.read(), pair.write()); }
            else if (kind == AnonymousKind.EPOLL) acquired = List.of(provider.epoll(initial));
            else acquired = List.of(provider.eventfd(initial, flags));
            var created = new ArrayList<OpenDescription>();
            for (int i = 0; i < acquired.size(); i++) {
                var resource = acquired.get(i);
                NativeEpoll epoll = null;
                if (kind == AnonymousKind.EPOLL) { epoll = new NativeEpoll(resource.duplicateDescriptor()); epolls.add(epoll); }
                created.add(new OpenDescription(null, null, resource, resource, epoll, kind, null,
                    kind != AnonymousKind.EPOLL && (!pipe || i == 0), kind != AnonymousKind.EPOLL && (!pipe || i == 1),
                    false, false, Readiness.NATIVE_UNCLASSIFIED));
            }
            long[] fds = new long[claims.size()];
            for (int i = 0; i < fds.length; i++) fds[i] = claims.get(i).reserved;
            synchronized (this) {
                if (disposed) throw fail(4, "THC file context is closed");
                publish.accept(fds);
                for (int i = 0; i < created.size(); i++) {
                    owners.add(created.get(i)); descriptors.put(fds[i], new Descriptor(created.get(i))); opening.remove(claims.get(i));
                }
            }
            acquired = List.of();
            epolls.clear();
            return fds;
        } catch (Throwable failure) {
            for (var epoll : epolls) try { epoll.close(); } catch (Throwable closing) { failure.addSuppressed(closing); }
            for (var resource : acquired) try { resource.close(); } catch (Throwable closing) { failure.addSuppressed(closing); }
            throw failure;
        } finally {
            synchronized (this) { for (var claim : claims) opening.remove(claim); }
            for (var claim : claims) claim.finished.countDown();
        }
    }
    @TruffleBoundary public long eventfd(int initial, int flags) {
        return result(() -> anonymousDescriptors(AnonymousKind.EVENT, initial, flags, ignored -> {})[0]);
    }

    private void releasePins(List<OpenDescription> pins) {
        var retired = new ArrayList<OpenDescription>();
        synchronized (this) {
            for (var owner : pins) if (--owner.references == 0) retired.add(owner);
            pins.clear();
        }
        Throwable failure = null;
        for (var owner : retired) {
            try { retire(owner); } catch (Throwable closing) {
                if (failure == null) failure = closing; else failure.addSuppressed(closing);
            }
        }
        if (failure != null) throw propagate(failure);
    }
    /** Outputs have been borrowed and checked by the caller. Descriptor and
     * process publication commit together; foreign activation belongs to it. */
    @TruffleBoundary public int launchProcess(List<byte[]> arguments, List<byte[]> environment,
        byte[] cwd, int[] streams, int flags, Long childGroup, Long childUser, byte[] searchPath,
        BiConsumer<Integer, int[]> publish) {
        if (streams.length != 3) throw new IllegalArgumentException("Failed requirement.");
        var claims = new OpenClaim[3];
        var pins = new ArrayList<OpenDescription>();
        var endpoints = new ManagedProcesses.Stream[] { ManagedProcesses.Stream.Endpoint.CLOSED, ManagedProcesses.Stream.Endpoint.CLOSED, ManagedProcesses.Stream.Endpoint.CLOSED };
        var acquired = new ArrayList<NativeFileResource>();
        ManagedProcesses.Launch launch = null;
        ManagedProcesses processes = null;
        boolean committed = false;
        Throwable primary = null;
        try {
            NativeFileProvider provider;
            synchronized (this) {
                if (disposed) throw fail(4, "THC file context is closed");
                provider = nativeProvider;
                if (provider == null) throw fail(7, "Process descriptors require the explicit NativeIO context");
                for (int i = 0; i < streams.length; i++) {
                    int fd = streams[i];
                    if (fd == -1) {
                        var claim = new OpenClaim(null, i == 0, unusedDescriptor(0));
                        claims[i] = claim; opening.add(claim);
                        endpoints[i] = ManagedProcesses.Stream.Endpoint.PIPE;
                    } else if (fd == -2) {
                        // Explicitly closed child endpoint.
                    } else if (fd >= 0) {
                        var owner = descriptor(fd).owner;
                        var resource = owner.nativeResource;
                        if (resource == null) throw fail(7, "Inherited process descriptor has no native capability");
                        if (owner.references == Long.MAX_VALUE) throw fail(10, "Native descriptor reference limit");
                        pins.add(owner); owner.references++;
                        endpoints[i] = new ManagedProcesses.Stream.Descriptor(resource);
                    } else throw fail(4, "Invalid inherited process descriptor");
                }
            }
            processes = provider.getProcesses();
            launch = processes.spawn(arguments, environment, cwd, endpoints[0], endpoints[1], endpoints[2], flags, childGroup, childUser, searchPath);
            var pipes = new ManagedProcesses.Pipe[] { launch.getInput(), launch.getOutput(), launch.getError() };
            var opened = new OpenDescription[3];
            int[] returned = {-1, -1, -1};
            for (int i = 0; i < pipes.length; i++) if (pipes[i] != null) {
                var resource = provider.adoptProcessPipe(pipes[i], i != 0, i == 0);
                acquired.add(resource);
                opened[i] = new OpenDescription(null, null, resource, resource, null, AnonymousKind.PIPE, null,
                    i != 0, i == 0, false, false, Readiness.NATIVE_UNCLASSIFIED);
                returned[i] = claims[i].reserved.intValue();
            }
            releasePins(pins);
            synchronized (this) {
                if (disposed) throw fail(4, "THC file context is closed");
                int pid = processes.publishProcessId(launch.getHandle());
                publish.accept(pid, returned);
                for (int i = 0; i < opened.length; i++) if (opened[i] != null) {
                    owners.add(opened[i]); descriptors.put((long) returned[i], new Descriptor(opened[i]));
                }
                committed = true;
                return pid;
            }
        } catch (Throwable failure) {
            Throwable reported = failure instanceof FileFailure fileFailure
                ? new NativeFileException("process descriptors", (int) nativeAbi().error(fileFailure.kind)) : failure;
            primary = reported;
            throw propagate(reported);
        } finally {
            Throwable cleanupFailure = null;
            if (!committed) {
                if (launch != null) try { processes.abortUnpublished(launch.getHandle()); }
                    catch (Throwable failure) { cleanupFailure = failure; }
                for (var resource : acquired) try { resource.close(); } catch (Throwable failure) {
                    if (cleanupFailure == null) cleanupFailure = failure; else cleanupFailure.addSuppressed(failure);
                }
            }
            synchronized (this) {
                for (var claim : claims) if (claim != null) { opening.remove(claim); claim.finished.countDown(); }
            }
            try { releasePins(pins); } catch (Throwable failure) {
                if (cleanupFailure == null) cleanupFailure = failure; else cleanupFailure.addSuppressed(failure);
            }
            if (cleanupFailure != null) {
                if (primary == null) throw propagate(cleanupFailure);
                primary.addSuppressed(cleanupFailure);
            }
        }
    }
    public ProcessResult processOperation(ProcessOp operation, int pid) { return processOperation(operation, pid, null, null); }
    public ProcessResult processOperation(ProcessOp operation, int pid, Node node) { return processOperation(operation, pid, node, null); }
    @TruffleBoundary public ProcessResult processOperation(ProcessOp operation, int pid, Node node, Runnable beforeBlock) {
        var service = NativeFileProvider.current().getProcesses();
        var handle = service.fromProcessId(pid);
        return switch (operation) {
            case POLL -> service.poll(handle);
            case WAIT -> service.waitFor(handle, node, beforeBlock);
            case TERMINATE -> service.terminate(handle);
            case CREATE -> throw RuntimeFault.fault("Creation requires a descriptor transaction");
        };
    }
    @TruffleBoundary public long epollCreate(int size) { return result(() -> anonymousDescriptors(AnonymousKind.EPOLL, size, 0, ignored -> {})[0]); }
    @TruffleBoundary public long pipe(ManagedAddress destination) {
        destination.requireByteRegion(8, true);
        return destination.withNativeBorrow(() -> {
            var allocation = destination.cbitsOwner();
            if (allocation == null) return acquirePipe(destination);
            synchronized (allocation) { return acquirePipe(destination); }
        });
    }
    private long acquirePipe(ManagedAddress destination) {
        return result(() -> {
            destination.requireByteRegion(8, true);
            anonymousDescriptors(AnonymousKind.PIPE, 0, 0, fds -> {
                for (int index = 0; index < fds.length; index++) for (int b = 0; b < 4; b++)
                    destination.writeWord8(index * 4L + b, fds[index] >>> (b * 8));
            });
            return 0;
        });
    }
    @TruffleBoundary public long eventfdWrite(long fd, long value) {
        return result(() -> withDescriptor(fd, entry -> {
            var resource = entry.nativeResource;
            if (resource == null) throw fail(7, "THC descriptor has no native event capability: " + fd);
            return resource.writeEvent(value);
        }));
    }
    @TruffleBoundary public void controlFd(long slot, long fd) {
        if (fd == -1) { synchronized (this) { eventControls.remove(slot); } return; }
        Descriptor entry;
        synchronized (this) { entry = descriptors.get(fd); }
        if (entry == null) throw RuntimeFault.fault("Event-manager control requires a live context descriptor");
        synchronized (entry.owner) {
            var owner = entry.owner;
            var expected = slot == -1 ? AnonymousKind.EVENT : AnonymousKind.PIPE;
            var nativeResource = owner.nativeResource;
            if (!owner.writable || owner.anonymousKind != expected || nativeResource == null)
                throw RuntimeFault.fault("Event-manager control requires an owned native " + (slot == -1 ? "eventfd" : "pipe writer"));
            if ((nativeResource.statusFlags() & nativeAbi().flagConstant(OriginalStdioOp.O_NONBLOCK)) == 0)
                throw RuntimeFault.fault("Event-manager control requires a nonblocking descriptor");
            synchronized (this) {
                if (disposed || entry.closed || descriptors.get(fd) != entry) throw RuntimeFault.fault("Event-manager control descriptor closed during registration");
                eventControls.put(slot, entry);
            }
        }
    }
    @TruffleBoundary public void shutdownEventManagers() {
        Map<Long, Descriptor> registrations;
        synchronized (this) { registrations = new LinkedHashMap<>(eventControls); eventControls.clear(); }
        Throwable failure = null;
        for (var pair : registrations.entrySet()) {
            var entry = pair.getValue();
            try {
                synchronized (entry.owner) {
                    boolean closed;
                    synchronized (this) { closed = entry.closed; }
                    if (closed || entry.owner.closed) continue;
                    var resource = entry.owner.nativeResource;
                    if ((resource.statusFlags() & nativeAbi().flagConstant(OriginalStdioOp.O_NONBLOCK)) == 0)
                        throw RuntimeFault.fault("Event-manager control descriptor is no longer nonblocking");
                    try {
                        if (pair.getKey() == -1) resource.writeEvent(0xff);
                        else resource.write(ByteBuffer.wrap(new byte[] {(byte) 0xfe}));
                    } catch (NativeFileException error) { if (error.getErrno() != 11) throw error; }
                }
            } catch (Throwable error) { failure = combineFailures(failure, error); }
        }
        if (failure != null) throw propagate(failure);
    }
    private static long integer(ManagedAddress address, long offset, int width) {
        long value = 0;
        for (int b = 0; b < width; b++) value |= address.readWord8(offset + b) << (b * 8);
        return value;
    }
    @TruffleBoundary public long poll(ManagedAddress address, long count, int timeout, Node node) {
        if (count < 0 || count > Integer.MAX_VALUE / 8) throw RuntimeFault.fault("poll descriptor image exceeds managed capacity");
        if (count != 0) address.requireByteRegion(count * 8, true);
        return address.withNativeBorrow(() -> result(() -> {
            long[] fds = new long[(int) count];
            for (int i = 0; i < fds.length; i++) fds[i] = (int) integer(address, i * 8L, 4);
            short[] events = new short[fds.length];
            for (int i = 0; i < fds.length; i++) events[i] = (short) integer(address, i * 8L + 4, 2);
            Descriptor[] entries = new Descriptor[fds.length];
            synchronized (this) {
                if (disposed) throw fail(4, "THC file context is closed");
                if (nativeProvider == null) throw fail(7, "poll requires the explicit NativeIO context");
                for (int i = 0; i < fds.length; i++) entries[i] = descriptors.get(fds[i]);
            }
            boolean[] invalid = new boolean[fds.length];
            for (int i = 0; i < fds.length; i++) invalid[i] = fds[i] >= 0 && entries[i] == null;
            int[] duplicates = new int[fds.length];
            java.util.Arrays.fill(duplicates, -1);
            try {
                for (int i = 0; i < fds.length; i++) {
                    var entry = entries[i];
                    if (entry == null) continue;
                    var nativeResource = entry.owner.nativeResource;
                    if (nativeResource == null) throw fail(7, "THC stream has no native poll capability: " + fds[i]);
                    try { duplicates[i] = nativeResource.duplicateDescriptor(); }
                    catch (Throwable failure) { if (failure instanceof ClosedChannelException) invalid[i] = true; else throw failure; }
                }
            } catch (Throwable failure) {
                for (int fd : duplicates) if (fd >= 0) try { NativePollApi.close(fd); }
                    catch (Throwable closing) { failure.addSuppressed(closing); }
                throw failure;
            }
            try (var request = NativeEventWait.acquire(duplicates, events, invalid)) {
                try {
                    synchronized (this) {
                        for (int i = 0; i < entries.length; i++) if (entries[i] != null) {
                            var entry = entries[i];
                            if (disposed || entry.closed || descriptors.get(fds[i]) != entry) request.getWatches()[i].descriptorClosed();
                            else entry.eventWaits.add(request.getWatches()[i]);
                        }
                    }
                    var ready = request.await(node, timeout);
                    if (count != 0) address.requireByteRegion(count * 8, true);
                    for (int i = 0; i < ready.length; i++) {
                        address.writeWord8(i * 8L + 6, ready[i]);
                        address.writeWord8(i * 8L + 7, (long) ready[i] >>> 8);
                    }
                    long readyCount = 0;
                    for (short value : ready) if (value != 0) readyCount++;
                    return readyCount;
                } finally {
                    synchronized (this) {
                        for (int i = 0; i < entries.length; i++) if (entries[i] != null) entries[i].eventWaits.remove(request.getWatches()[i]);
                    }
                }
            }
        }));
    }
    @TruffleBoundary public long epollControl(long fd, int operation, long targetFd, ManagedAddress event) {
        return result(() -> {
            var selected = descriptor(fd);
            var target = descriptor(targetFd);
            var epoll = selected.owner.epoll;
            if (epoll == null) throw new NativeFileException("epoll_ctl", 22);
            if (selected.owner == target.owner) throw new NativeFileException("epoll_ctl", 22);
            var nativeResource = target.owner.nativeResource;
            if (nativeResource == null) throw fail(7, "THC stream has no native epoll capability: " + targetFd);
            byte[] bytes = null;
            if (operation != 2) {
                event.requireByteRegion(12, false);
                bytes = event.withNativeBorrow(() -> {
                    var image = new byte[12];
                    for (int i = 0; i < image.length; i++) image[i] = (byte) event.readWord8(i);
                    return image;
                });
            }
            synchronized (this) {
                if (selected.closed || target.closed || descriptors.get(fd) != selected || descriptors.get(targetFd) != target)
                    throw new NativeFileException("epoll_ctl", 9);
            }
            epoll.control(operation, target, nativeResource, bytes, registration -> {
                synchronized (this) {
                    if (disposed || target.owner.references == 0) throw propagate(new NativeFileException("epoll_ctl", 9));
                    target.owner.epollRegistrations.add(registration);
                }
            }, registration -> {
                synchronized (this) { target.owner.epollRegistrations.remove(registration); }
            });
            return 0;
        });
    }
    private static long remaining(int timeout, long started) {
        return timeout < 0 ? -1 : Math.max(0, timeout - Math.max(0, System.nanoTime() - started) / 1_000_000);
    }
    @TruffleBoundary public long epollWait(long fd, ManagedAddress destination, int maximum, int timeout, Node node) {
        return result(() -> {
            if (maximum <= 0) throw new NativeFileException("epoll_wait", 22);
            if (maximum > Integer.MAX_VALUE / 12) throw RuntimeFault.fault("epoll output image exceeds managed capacity");
            destination.requireByteRegion(maximum * 12L, true);
            var selected = descriptor(fd);
            var epoll = selected.owner.epoll;
            if (epoll == null) throw new NativeFileException("epoll_wait", 22);
            long started = System.nanoTime();
            return destination.withNativeBorrow(() -> {
                while (true) {
                    synchronized (this) {
                        if (disposed || selected.closed || descriptors.get(fd) != selected) throw propagate(new NativeFileException("epoll_wait", 9));
                    }
                    int count = epoll.ready(destination, maximum);
                    if (count != 0 || timeout == 0 || timeout > 0 && remaining(timeout, started) == 0) return (long) count;
                    if (awaitReady(selected, fd, false, remaining(timeout, started), node, null) == -2)
                        throw propagate(new NativeFileException("epoll_wait", 9));
                }
            });
        });
    }

    private synchronized NativeFileProvider requireProvider(String message) {
        if (disposed) throw fail(4, "THC file context is closed");
        if (nativeProvider == null) throw fail(7, message);
        return nativeProvider;
    }
    private long allocationLocked(ManagedAddress address, FileAction action) {
        var allocation = address.cbitsOwner();
        try {
            if (allocation == null) return action.run();
            synchronized (allocation) { return action.run(); }
        } catch (Throwable failure) { throw propagate(failure); }
    }
    @TruffleBoundary public long changeDirectoryOriginal(ManagedAddress path) {
        byte[] bytes = originalPathBytes(path);
        return result(() -> requireProvider("Original chdir requires the explicit native filesystem").changeDirectory(bytes));
    }
    @TruffleBoundary public long currentDirectoryOriginal(ManagedAddress output, long capacity) {
        if (output.sameLocation(ManagedAddress.nullAddress())) throw RuntimeFault.fault("Original getcwd NULL allocation is not supported");
        if (capacity < 0 || capacity > Integer.MAX_VALUE) throw RuntimeFault.fault("Original getcwd exceeds managed byte capacity");
        output.requireByteRegion(capacity, true);
        return result(() -> {
            var provider = requireProvider("Original getcwd requires the explicit native filesystem");
            return output.withNativeBorrow(() -> allocationLocked(output, () -> {
                output.requireByteRegion(capacity, true);
                var observed = provider.currentDirectory((int) capacity);
                var name = java.util.Arrays.copyOf(observed, observed.length + 1);
                ManagedAddress.fromByteArray(name).copyNonOverlappingTo(output, name.length);
                return 0;
            }));
        });
    }
    @TruffleBoundary public long symlinkOriginal(ManagedAddress target, ManagedAddress path) {
        byte[] targetBytes = originalPathBytes(target);
        byte[] pathBytes = originalPathBytes(path);
        return result(() -> requireProvider("Original symlink requires the explicit native filesystem").symlinkRaw(targetBytes, pathBytes));
    }
    @TruffleBoundary public long readlinkOriginal(ManagedAddress path, ManagedAddress output, long capacity) {
        if (capacity < 0 || capacity > Integer.MAX_VALUE) throw RuntimeFault.fault("Original readlink exceeds managed byte capacity");
        output.requireByteRegion(capacity, true);
        byte[] bytes = originalPathBytes(path);
        return result(() -> {
            var provider = requireProvider("Original readlink requires the explicit native filesystem");
            return output.withNativeBorrow(() -> allocationLocked(output, () -> {
                output.requireByteRegion(capacity, true);
                var prefix = provider.readlinkRaw(bytes, (int) capacity);
                ManagedAddress.fromByteArray(prefix).copyNonOverlappingTo(output, prefix.length);
                return prefix.length;
            }));
        });
    }
    @TruffleBoundary public long unlinkAtOriginal(long fd, ManagedAddress path, int flags, long cwd, long removeDir) {
        byte[] bytes = originalPathBytes(path);
        return result(() -> {
            var provider = requireProvider("Original unlinkat requires the explicit native filesystem");
            // Unknown flags precede descriptor lookup. Absolute paths ignore fd.
            if (((long) flags & ~removeDir) != 0) throw fail(5, "Invalid original unlinkat flags");
            if (fd == cwd || bytes[0] == '/') return provider.unlinkAtRaw(bytes, flags);
            try {
                return withDescriptor(fd, entry -> {
                    var nativeResource = entry.nativeResource;
                    if (nativeResource == null) throw fail(7, "Original unlinkat requires an authenticated native descriptor");
                    return nativeResource.unlinkAt(bytes, flags);
                });
            } catch (Throwable failure) {
                if (!(failure instanceof FileFailure missing) || missing.kind != 4) throw failure;
                return provider.unlinkAtInvalidRaw(bytes, flags);
            }
        });
    }
    @TruffleBoundary public long accessOriginal(ManagedAddress path, int mode) {
        byte[] bytes = originalPathBytes(path);
        return result(() -> requireProvider("Original access requires the explicit native filesystem").accessRaw(bytes, mode));
    }
    @TruffleBoundary public long pathModeOriginal(ManagedAddress path, long mode, boolean createDirectory) {
        byte[] bytes = originalPathBytes(path);
        return result(() -> requireProvider("Original pathname mode requires the explicit native filesystem").pathModeRaw(bytes, mode, createDirectory));
    }
    @TruffleBoundary public long unlinkOriginal(ManagedAddress path) {
        byte[] bytes = originalPathBytes(path);
        return result(() -> requireProvider("Original unlink requires the explicit native filesystem").unlinkRaw(bytes));
    }
    @TruffleBoundary public long statAtOriginal(long fd, ManagedAddress path, ManagedAddress destination, int flags, long cwd) {
        long size = PosixStat.execute(OriginalStdioOp.SIZEOF_STAT, ManagedAddress.nullAddress(), 0);
        destination.requireByteRegion(size, true);
        byte[] bytes = originalPathBytes(path);
        return result(() -> {
            var provider = requireProvider("Original fstatat requires the explicit native filesystem");
            return destination.withNativeBorrow(() -> allocationLocked(destination, () -> {
                destination.requireByteRegion(size, true);
                byte[] image;
                if (fd == cwd || bytes[0] == '/') image = provider.statAtRaw(bytes, flags);
                else try {
                    image = withDescriptor(fd, entry -> {
                        var nativeResource = entry.nativeResource;
                        if (nativeResource == null) throw fail(7, "Original fstatat requires an authenticated native descriptor");
                        return nativeResource.statAt(bytes, flags);
                    });
                } catch (Throwable failure) {
                    if (!(failure instanceof FileFailure missing) || missing.kind != 4) throw failure;
                    image = provider.statAtInvalidRaw(bytes, flags);
                }
                if (image.length != size) throw RuntimeFault.fault("Native fstatat image has the wrong size");
                ManagedAddress.fromByteArray(image).copyNonOverlappingTo(destination, size);
                return 0;
            }));
        });
    }
    @TruffleBoundary public long pathStatOriginal(ManagedAddress path, ManagedAddress destination, boolean followLinks) {
        long size = PosixStat.execute(OriginalStdioOp.SIZEOF_STAT, ManagedAddress.nullAddress(), 0);
        destination.requireByteRegion(size, true);
        byte[] bytes = originalPathBytes(path);
        return result(() -> {
            var provider = requireProvider("Original path stat requires the explicit native filesystem");
            return destination.withNativeBorrow(() -> allocationLocked(destination, () -> {
                destination.requireByteRegion(size, true);
                var image = provider.statRaw(bytes, followLinks);
                if (image.length != size) throw RuntimeFault.fault("Native stat image has the wrong size");
                ManagedAddress.fromByteArray(image).copyNonOverlappingTo(destination, size);
                return 0;
            }));
        });
    }
    public long openOriginal(ManagedAddress path, long flags, long mode) { return openOriginal(path, flags, mode, OriginalStdioOp.OPEN, null); }
    public long openOriginal(ManagedAddress path, long flags, long mode, OriginalStdioOp operation) { return openOriginal(path, flags, mode, operation, null); }
    @TruffleBoundary public long openOriginal(ManagedAddress path, long flags, long mode, OriginalStdioOp operation, Node node) {
        check(operation.getOpening());
        nativeAbi().requireOpenAbi();
        if (flags != (long) (int) flags || mode < 0 || mode > 0xffff_ffffL)
            throw RuntimeFault.fault("Original open requires canonical CInt flags and Word32 mode");
        byte[] bytes = originalPathBytes(path);
        return result(() -> {
            NativeFileProvider provider;
            OpenClaim claim;
            synchronized (this) {
                if (disposed) throw fail(4, "THC file context is closed");
                provider = nativeProvider;
                if (provider == null) throw fail(7, "Original open requires the explicit native filesystem");
                claim = new OpenClaim(null, nativeAbi().openWritable(flags), unusedDescriptor(0));
                opening.add(claim);
            }
            OpenedNativeFile resource = null;
            try {
                var acquired = provider.openRaw(bytes, (int) flags, mode, operation, node);
                resource = acquired;
                var owner = new OpenDescription(null, null, acquired, acquired, null, null, null,
                    nativeAbi().openReadable(flags), nativeAbi().openWritable(flags), nativeAbi().openAppend(flags), true, Readiness.NATIVE_UNCLASSIFIED);
                long fd;
                synchronized (this) {
                    if (disposed) throw fail(4, "THC file context is closed");
                    fd = claim.reserved;
                    check(opening.contains(claim) && !descriptors.containsKey(fd));
                    owners.add(owner);
                    descriptors.put(fd, new Descriptor(owner));
                    opening.remove(claim);
                }
                resource = null;
                return fd;
            } catch (Throwable failure) {
                try { if (resource != null) resource.close(); } catch (Throwable closing) { failure.addSuppressed(closing); }
                throw failure;
            } finally {
                synchronized (this) { opening.remove(claim); }
                claim.finished.countDown();
            }
        });
    }
    // Owner held. A raw open does not fail because later metadata cannot be read.
    private boolean regular(OpenDescription entry) {
        return switch (entry.readiness) {
            case REGULAR_FILE -> true;
            case UNAVAILABLE -> false;
            case NATIVE_UNCLASSIFIED -> {
                var image = ManagedAddress.fromByteArray(entry.nativeResource.statImage());
                yield PosixStat.execute(OriginalStdioOp.IS_REG, ManagedAddress.nullAddress(), PosixStat.execute(OriginalStdioOp.ST_MODE, image, 0)) == 1;
            }
        };
    }
    @TruffleBoundary public byte[] statImage(long fd) {
        var previous = threads.enterForeign(ForeignSafety.UNSAFE);
        try {
            return withDescriptor(fd, entry -> {
                if (entry.nativeResource == null) throw new UnsupportedOperationException("THC descriptor has no opened-resource metadata: " + fd);
                return entry.nativeResource.statImage();
            });
        } finally { threads.leaveForeign(previous); }
    }
    @TruffleBoundary public long fstat(long fd, ManagedAddress destination) {
        long size = PosixStat.execute(OriginalStdioOp.SIZEOF_STAT, ManagedAddress.nullAddress(), 0);
        destination.requireByteRegion(size, true);
        return result(() -> withDescriptor(fd, entry -> allocationLocked(destination, () -> {
            destination.requireByteRegion(size, true);
            var resource = entry.nativeResource;
            if (resource == null) throw fail(7, "THC descriptor has no opened-resource metadata: " + fd);
            var image = resource.statImage();
            if (image.length != size) throw RuntimeFault.fault("Native stat image has the wrong size");
            ManagedAddress.fromByteArray(image).copyNonOverlappingTo(destination, size);
            return 0;
        })));
    }
    @TruffleBoundary public long tcgetattr(long fd, ManagedAddress destination) {
        long size = TermiosImage.scalar(OriginalStdioOp.SIZEOF_TERMIOS, ManagedAddress.nullAddress(), 0);
        destination.requireByteRegion(size, true);
        return result(() -> withDescriptor(fd, entry -> {
            var resource = entry.nativeResource;
            if (resource == null) throw fail(7, "THC descriptor has no native terminal capability: " + fd);
            return TermiosImage.transfer(destination, true, image -> { resource.readTermios(image); return 0L; });
        }));
    }
    @TruffleBoundary public long tcsetattr(long fd, int action, ManagedAddress source) {
        long size = TermiosImage.scalar(OriginalStdioOp.SIZEOF_TERMIOS, ManagedAddress.nullAddress(), 0);
        source.requireByteRegion(size, false);
        return result(() -> withDescriptor(fd, entry -> {
            var resource = entry.nativeResource;
            if (resource == null) throw fail(7, "THC descriptor has no native terminal capability: " + fd);
            return TermiosImage.transfer(source, false, image -> { resource.writeTermios(action, image); return 0L; });
        }));
    }

    public long read(long fd, ManagedAddress address, long count) { return read(fd, address, count, ForeignSafety.UNSAFE); }
    @TruffleBoundary public long read(long fd, ManagedAddress address, long count, ForeignSafety safety) {
        address.requireRange(0, count, true);
        return result(safety, () -> withDescriptor(fd, entry -> {
            if (!entry.readable) throw fail(4, "THC file descriptor is not readable: " + fd);
            if (count == 0) return 0L;
            int n;
            if (address.hasNativeIOStorage()) {
                n = address.withNativeIOWindow(count, true, segment -> {
                    try {
                        address.requireByteRegion(count, true);
                        var window = segment.asSlice(0, Math.min(count, Integer.MAX_VALUE)).asByteBuffer();
                        if (entry.input == null) return entry.channel.read(window);
                        var bytes = new byte[Math.min(window.remaining(), 1024 * 1024)];
                        window.duplicate().get(bytes);
                        int received;
                        try { received = entry.input.read(bytes, 0, bytes.length); }
                        catch (Throwable failure) {
                            try { window.put(bytes); } catch (Throwable copyback) {
                                if (copyback != failure) failure.addSuppressed(copyback);
                            }
                            throw failure;
                        }
                        if (received > 0) window.put(bytes, 0, received);
                        return received;
                    } catch (Throwable failure) { throw propagate(failure); }
                });
            } else if (address.hasExternalStorage()) {
                var bytes = new byte[(int) Math.min(count, 1024 * 1024L)];
                address.copyToByteArray(bytes, 0, bytes.length);
                int received;
                try { received = entry.input != null ? entry.input.read(bytes, 0, bytes.length) : entry.channel.read(ByteBuffer.wrap(bytes)); }
                catch (Throwable failure) {
                    try { address.copyFromByteArray(bytes, 0, bytes.length); }
                    catch (Throwable copyback) { if (copyback != failure) failure.addSuppressed(copyback); }
                    throw failure;
                }
                if (received > 0) address.copyFromByteArray(bytes, 0, received);
                n = received;
            } else {
                byte[] bytes = address.rawBacking();
                int offset = (int) address.cbitsOffset();
                n = entry.input != null ? entry.input.read(bytes, offset, (int) count) : entry.channel.read(ByteBuffer.wrap(bytes, offset, (int) count));
            }
            if (n < 0) return 0L;
            if (n == 0) throw fail(6, "THC input made no progress");
            return (long) n;
        }));
    }
    public long fcntl(long fd, long argument, boolean write) { return fcntl(fd, argument, write, false); }
    @TruffleBoundary public long fcntl(long fd, long argument, boolean write, boolean descriptorFlags) {
        return result(() -> withDescriptor(fd, entry -> {
            var resource = entry.nativeResource;
            if (resource == null) throw fail(7, "THC descriptor has no native fcntl capability: " + fd);
            if (descriptorFlags) return resource.setDescriptorFlags(argument);
            return write ? resource.setStatusFlags(argument) : resource.statusFlags();
        }));
    }
    /** Binds a logical descriptor once. No native fd survives an async cut and
     * retries never resolve a reused number to a new descriptor. */
    public final class WaitToken {
        private final long fd;
        private final boolean writing;
        private final Descriptor entry;
        WaitToken(long fd, boolean writing) {
            this.fd = fd; this.writing = writing;
            synchronized (ManagedFiles.this) { entry = descriptors.get(fd); }
        }
        public void await(Node node, boolean async) { await(node, async, false); }
        @TruffleBoundary(transferToInterpreterOnException = false)
        public void await(Node node, boolean async, boolean compiledAtCut) {
            if (Language.currentState(node).getEnv() != env) throw RuntimeFault.fault("Descriptor wait belongs to another context");
            if (entry == null) throw propagate(new ClosedChannelException());
            int outcome = awaitReady(entry, fd, writing, -1, node, () -> {
                if (async) {
                    var request = threads.poll(node, true);
                    if (request != null) {
                        request.compiledCapture = compiledAtCut;
                        throw new AsyncBlocked(request, node);
                    }
                }
            });
            if (outcome == -2) throw propagate(new ClosedChannelException());
        }
    }
    @TruffleBoundary public WaitToken waitToken(long fd, boolean writing) {
        if (fd != (long) (int) fd) throw RuntimeFault.fault("Descriptor wait requires a signed CInt descriptor");
        return new WaitToken(fd, writing);
    }
    private int awaitReady(Descriptor entry, long fd, boolean writing, long milliseconds, Node node, Runnable beforeBlock) {
        NativeFileResource nativeResource;
        synchronized (this) {
            if (disposed || entry.closed || descriptors.get(fd) != entry) return -2;
            if (entry.owner.readiness == Readiness.REGULAR_FILE) return 1;
            nativeResource = entry.owner.nativeResource;
            if (nativeResource == null) throw fail(7, "THC stream has no readiness contract: " + fd);
        }
        NativeFdWait request;
        try { request = nativeResource.readinessWait(); }
        catch (Throwable failure) { if (failure instanceof ClosedChannelException) return -2; throw propagate(failure); }
        try {
            synchronized (this) {
                if (disposed || entry.closed || descriptors.get(fd) != entry) return -2;
                entry.readinessWaits.add(request);
            }
            if (beforeBlock == null) return request.await(node, writing, milliseconds);
            try (var ignored = GuestThreads.Companion.blocking$org_intelligence_thc(writing ? GuestThreadStatus.WRITE : GuestThreadStatus.READ)) {
                return request.await(node, writing, milliseconds, beforeBlock);
            }
        } finally {
            synchronized (this) { entry.readinessWaits.remove(request); }
            request.close();
        }
    }
    public synchronized int pendingReadiness(long fd) {
        var entry = descriptors.get(fd);
        return entry == null ? 0 : entry.readinessWaits.size();
    }
    public synchronized int pendingEventWaits(long fd) {
        var entry = descriptors.get(fd);
        return entry == null ? 0 : entry.eventWaits.size();
    }
    public long ready(long fd, long milliseconds) { return ready(fd, milliseconds, false, null); }
    public long ready(long fd, long milliseconds, boolean writing) { return ready(fd, milliseconds, writing, null); }
    @TruffleBoundary public long ready(long fd, long milliseconds, boolean writing, Node node) {
        if (fd < 0) {
            if (milliseconds != 0) throw RuntimeFault.fault("Original fdReady cannot wait on an ignored negative descriptor");
            return 0;
        }
        return result(() -> {
            Descriptor entry;
            synchronized (this) { entry = descriptors.get(fd); }
            if (entry == null) return 1;
            int ready = awaitReady(entry, fd, writing, milliseconds, node, null);
            return ready == -2 ? 1 : ready;
        });
    }
    public long write(long fd, ManagedAddress address, long count) { return write(fd, address, count, ForeignSafety.UNSAFE); }
    @TruffleBoundary public long write(long fd, ManagedAddress address, long count, ForeignSafety safety) {
        address.requireRange(0, count, false);
        return result(safety, () -> withDescriptor(fd, entry -> {
            if (!entry.writable) throw fail(4, "THC file descriptor is not writable: " + fd);
            if (count == 0) return 0L;
            if (address.hasNativeIOStorage()) {
                return address.withNativeIOWindow(count, false, segment -> {
                    try {
                        address.requireByteRegion(count, false);
                        var window = segment.asSlice(0, Math.min(count, Integer.MAX_VALUE)).asByteBuffer().asReadOnlyBuffer();
                        if (entry.output == null) return (long) entry.channel.write(window);
                        var bytes = new byte[Math.min(window.remaining(), 1024 * 1024)];
                        window.get(bytes);
                        entry.output.write(bytes, 0, bytes.length);
                        return (long) bytes.length;
                    } catch (Throwable failure) { throw propagate(failure); }
                });
            }
            if (address.hasExternalStorage()) {
                var bytes = new byte[(int) Math.min(count, 1024 * 1024L)];
                address.copyToByteArray(bytes, 0, bytes.length);
                if (entry.output != null) { entry.output.write(bytes, 0, bytes.length); return (long) bytes.length; }
                return (long) entry.channel.write(ByteBuffer.wrap(bytes));
            }
            byte[] bytes = address.rawBacking();
            int offset = (int) address.cbitsOffset();
            if (entry.output != null) { entry.output.write(bytes, offset, (int) count); return count; }
            return (long) entry.channel.write(ByteBuffer.wrap(bytes, offset, (int) count));
        }));
    }
    public long close(long fd) { return close(fd, ForeignSafety.UNSAFE); }
    @TruffleBoundary public long close(long fd, ForeignSafety safety) {
        return result(safety, () -> {
            var entry = descriptor(fd);
            Throwable failure;
            boolean last;
            synchronized (this) {
                if (descriptors.get(fd) != entry || entry.closed) throw fail(4, "Closed or unknown THC file descriptor: " + fd);
                descriptors.remove(fd);
                failure = invalidate(entry);
                last = --entry.owner.references == 0;
            }
            if (last) try { retire(entry.owner); } catch (Throwable error) { failure = combineFailures(failure, error); }
            if (failure != null) throw failure;
            return 0;
        });
    }
    @TruffleBoundary public long duplicate(long fd) {
        return result(() -> {
            synchronized (this) {
                var source = descriptors.get(fd);
                if (source == null) throw fail(4, "Closed or unknown THC file descriptor: " + fd);
                long target = unusedDescriptor(0);
                source.owner.references++;
                descriptors.put(target, new Descriptor(source.owner));
                return target;
            }
        });
    }
    @TruffleBoundary public long duplicateTo(long fd, long target) {
        OpenDescription[] retired = {null};
        Throwable[] wakeFailure = {null};
        long installed = result(() -> {
            synchronized (this) {
                var source = descriptors.get(fd);
                if (source == null) throw fail(4, "Closed or unknown THC file descriptor: " + fd);
                if (target < 0 || target >= descriptorLimit) throw fail(4, "THC dup2 target is out of range: " + target);
                if (fd == target) return target;
                if (reserved(target)) throw fail(8, "THC dup2 target is being acquired: " + target);
                source.owner.references++;
                var old = descriptors.put(target, new Descriptor(source.owner));
                if (old != null) {
                    wakeFailure[0] = invalidate(old);
                    if (--old.owner.references == 0) retired[0] = old.owner;
                }
            }
            return target;
        });
        var owner = retired[0];
        if (owner != null) {
            var previous = threads.enterForeign(ForeignSafety.UNSAFE);
            try { retire(owner); }
            catch (Throwable error) {
                if (!(error instanceof IOException)) wakeFailure[0] = combineFailures(wakeFailure[0], error);
            } finally { threads.leaveForeign(previous); }
        }
        if (wakeFailure[0] != null) throw propagate(wakeFailure[0]);
        return installed;
    }
    public long seek(long fd, long offset, long mode) { return seek(fd, offset, mode, ForeignSafety.UNSAFE); }
    @TruffleBoundary public long seek(long fd, long offset, long mode, ForeignSafety safety) {
        return result(safety, () -> withDescriptor(fd, entry -> {
            var channel = entry.channel;
            if (channel == null) throw fail(7, "THC stream is not seekable: " + fd);
            long base;
            if (mode == 0) base = 0;
            else if (mode == 1) base = channel.position();
            else if (mode == 2) base = channel.size();
            else throw fail(5, "Unknown THC seek mode: " + mode);
            long position;
            try { position = Math.addExact(base, offset); } catch (ArithmeticException overflow) { throw fail(5, "THC file offset overflow"); }
            if (position < 0) throw fail(5, "Negative THC file offset");
            channel.position(position);
            return position;
        }));
    }
    public long size(long fd) { return size(fd, ForeignSafety.UNSAFE); }
    @TruffleBoundary public long size(long fd, ForeignSafety safety) {
        return result(safety, () -> withDescriptor(fd, entry -> {
            if (!regular(entry)) throw fail(7, "THC stream has no file size: " + fd);
            if (entry.channel == null) throw fail(7, "THC stream has no file size: " + fd);
            return entry.channel.size();
        }));
    }
    public long setSize(long fd, long length) { return setSize(fd, length, ForeignSafety.UNSAFE); }
    @TruffleBoundary public long setSize(long fd, long length, ForeignSafety safety) { return resize(fd, length, false, safety); }
    @TruffleBoundary public long truncateOriginal(long fd, long length) { return resize(fd, length, true, ForeignSafety.UNSAFE); }
    private long resize(long fd, long length, boolean original, ForeignSafety safety) {
        return result(safety, () -> withDescriptor(fd, entry -> {
            if (!regular(entry)) throw fail(original ? 5 : 7, "Cannot resize a THC stream: " + fd);
            var channel = entry.channel;
            if (channel == null) throw fail(original ? 5 : 7, "Cannot resize a THC stream: " + fd);
            if (!entry.writable) throw fail(original ? 5 : 4, "THC file descriptor is not writable: " + fd);
            if (length < 0) throw fail(5, "Negative THC file size");
            long oldSize = channel.size();
            boolean append = entry.nativeResource == null ? entry.append : nativeAbi().openAppend(entry.nativeResource.statusFlags());
            if (length > oldSize && (append || !entry.canExtend)) throw fail(7, "Extending an append-mode file or native standard endpoint is not supported");
            long position = channel.position();
            try {
                if (length <= oldSize) channel.truncate(length);
                else {
                    channel.position(length - 1);
                    if (channel.write(ByteBuffer.wrap(new byte[] {0})) != 1) throw fail(6, "Unable to extend THC file");
                }
            } finally { channel.position(position); }
            return 0L;
        }));
    }
    public long isTerminal(long fd) { return isTerminal(fd, ForeignSafety.UNSAFE); }
    @TruffleBoundary public long isTerminal(long fd, ForeignSafety safety) {
        return result(safety, () -> withDescriptor(fd, entry -> entry.nativeResource == null ? 0L : entry.nativeResource.terminalStatus()));
    }
    public long deviceType(long fd) { return deviceType(fd, ForeignSafety.UNSAFE); }
    @TruffleBoundary public long deviceType(long fd, ForeignSafety safety) {
        return result(safety, () -> withDescriptor(fd, entry -> regular(entry) ? 0L : 1L));
    }
    @TruffleBoundary public void dispose() {
        Throwable failed = null;
        List<OpenDescription> entries;
        List<OpenClaim> pending;
        synchronized (this) {
            if (disposed) return;
            disposed = true;
            pending = new ArrayList<>(opening);
            opening.clear();
            entries = new ArrayList<>(owners);
            for (var entry : descriptors.values()) failed = combineFailures(failed, invalidate(entry));
            for (var entry : entries) entry.references = 0;
            descriptors.clear();
        }
        var previous = threads.enterForeignForDisposal$org_intelligence_thc();
        try {
            for (var entry : entries) try { retire(entry); } catch (Throwable error) { failed = combineFailures(failed, error); }
            boolean interrupted = false;
            for (var claim : pending) if (claim.owner != Thread.currentThread()) {
                while (true) try { claim.finished.await(); break; }
                catch (InterruptedException ignored) { interrupted = true; }
            }
            if (interrupted) Thread.currentThread().interrupt();
        } finally { threads.leaveForeign(previous); }
        failure.remove();
        if (failed instanceof IOException) {
            var fault = new RuntimeFault("THC file disposal failed: " + failed.getMessage());
            fault.initCause(failed);
            throw fault;
        }
        if (failed != null) throw propagate(failed);
    }
    private static void check(boolean condition) { if (!condition) throw new IllegalStateException("Check failed."); }
    @SuppressWarnings("unchecked") private static <E extends Throwable> RuntimeException propagate(Throwable failure) throws E { throw (E) failure; }
}
