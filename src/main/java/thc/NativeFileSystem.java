// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc;

import java.io.IOException;
import java.net.URI;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.AccessMode;
import java.nio.file.CopyOption;
import java.nio.file.DirectoryStream;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.attribute.FileAttribute;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.graalvm.polyglot.io.FileSystem;
import thc.NativeIO.StandardEndpoint;
import thc.runtime.NativeDirectoryOwner;
import thc.runtime.NativeOpenRequest;

/** Factory-owned host authority. Relative operations borrow the context's
 * directory identity across host renames. Standard endpoints require grants. */
public final class NativeFileSystem implements FileSystem, AutoCloseable {
    private final FileSystem host;
    private final Set<StandardEndpoint> standardEndpoints;
    private volatile NativeDirectoryOwner directoryOwner;
    private volatile boolean closed;
    private Path startupDirectory;
    public NativeFileSystem() { this(Set.of()); }
    public NativeFileSystem(Set<StandardEndpoint> standardEndpoints) {
        this(standardEndpoints, false);
    }
    /** Deferred startup uses ordinary host paths for preparatory resource reads;
     * native directory identity begins only when the factory activates startup. */
    public NativeFileSystem(Set<StandardEndpoint> standardEndpoints, boolean deferNativeStartup) {
        host = FileSystem.newDefaultFileSystem();
        this.standardEndpoints = new LinkedHashSet<>(standardEndpoints);
        startupDirectory = host.toAbsolutePath(host.parsePath(""));
        if (!deferNativeStartup) startNativeDirectory();
    }
    public synchronized void startNativeDirectory() {
        requireOpen();
        if (directoryOwner == null) directoryOwner = new NativeDirectoryOwner(startupDirectory);
    }
    public NativeDirectoryOwner getDirectoryOwner() {
        requireOpen();
        var owner = directoryOwner;
        if (owner == null) throw new IllegalStateException("Native filesystem startup is pending");
        return owner;
    }
    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        var owner = directoryOwner; if (owner != null) owner.close();
    }
    private void requireOpen() { if (closed) throw new IllegalStateException("Native filesystem is closed"); }
    private synchronized Path startupPath(Path path) {
        requireOpen();
        return path.isAbsolute() ? path : startupDirectory.resolve(path);
    }
    @Override public Path parsePath(URI uri) { return host.parsePath(uri); }
    @Override public Path parsePath(String path) { return host.parsePath(path); }

    // Preserve the former delegation: Java defaults not explicitly overridden
    // here still run on this filesystem, not on the host delegate.
    @Override public synchronized void setCurrentWorkingDirectory(Path path) {
        requireOpen();
        if (!path.isAbsolute()) throw new IllegalArgumentException("Current working directory must be absolute");
        if (directoryOwner == null) {
            try {
                if (!Boolean.TRUE.equals(host.readAttributes(path, "basic:isDirectory").get("isDirectory")))
                    throw new IllegalArgumentException("Current working directory must be a directory");
            } catch (IOException failure) { throw new IllegalArgumentException("Invalid current working directory", failure); }
            startupDirectory = path;
            return;
        }
        directoryOwner.change(path);
    }
    @Override public Path toAbsolutePath(Path path) {
        if (directoryOwner == null) return startupPath(path);
        try (var directory = directoryOwner.borrow()) {
            return path.isAbsolute() ? path : directory.currentPath().resolve(path);
        }
    }
    @Override public Path toRealPath(Path path, LinkOption... options) throws IOException {
        if (directoryOwner == null) return host.toRealPath(startupPath(path), options);
        try (var directory = directoryOwner.borrow()) {
            var result = host.toRealPath(directory.resolve(path), options);
            if (path.isAbsolute()) return result;
            var anchor = directory.resolve(host.parsePath("")).normalize();
            return result.startsWith(anchor) ? directory.currentPath().resolve(anchor.relativize(result)) : result;
        }
    }
    @Override public void checkAccess(Path path, Set<? extends AccessMode> modes, LinkOption... options) throws IOException {
        if (directoryOwner == null) { host.checkAccess(startupPath(path), modes, options); return; }
        try (var directory = directoryOwner.borrow()) { host.checkAccess(directory.resolve(path), modes, options); }
    }
    @Override public void createDirectory(Path path, FileAttribute<?>... attrs) throws IOException {
        if (directoryOwner == null) { host.createDirectory(startupPath(path), attrs); return; }
        try (var directory = directoryOwner.borrow()) { host.createDirectory(directory.resolve(path), attrs); }
    }
    @Override public void delete(Path path) throws IOException {
        if (directoryOwner == null) { host.delete(startupPath(path)); return; }
        try (var directory = directoryOwner.borrow()) { host.delete(directory.resolve(path)); }
    }
    @Override public Map<String, Object> readAttributes(Path path, String attributes, LinkOption... options) throws IOException {
        if (directoryOwner == null) return host.readAttributes(startupPath(path), attributes, options);
        try (var directory = directoryOwner.borrow()) { return host.readAttributes(directory.resolve(path), attributes, options); }
    }
    @Override public void copy(Path source, Path target, CopyOption... options) throws IOException {
        if (directoryOwner == null) { host.copy(startupPath(source), startupPath(target), options); return; }
        try (var directory = directoryOwner.borrow()) { host.copy(directory.resolve(source), directory.resolve(target), options); }
    }
    @Override public void move(Path source, Path target, CopyOption... options) throws IOException {
        if (directoryOwner == null) { host.move(startupPath(source), startupPath(target), options); return; }
        try (var directory = directoryOwner.borrow()) { host.move(directory.resolve(source), directory.resolve(target), options); }
    }
    @Override public boolean isSameFile(Path first, Path second, LinkOption... options) throws IOException {
        if (directoryOwner == null) return host.isSameFile(startupPath(first), startupPath(second), options);
        try (var directory = directoryOwner.borrow()) { return host.isSameFile(directory.resolve(first), directory.resolve(second), options); }
    }
    @Override public SeekableByteChannel newByteChannel(Path path, Set<? extends OpenOption> options, FileAttribute<?>... attrs) throws IOException {
        var requests = new ArrayList<NativeOpenRequest>();
        for (var option : options) if (option instanceof NativeOpenRequest request) requests.add(request);
        if (requests.isEmpty()) {
            if (directoryOwner == null) return host.newByteChannel(startupPath(path), options, attrs);
            try (var directory = directoryOwner.borrow()) { return host.newByteChannel(directory.resolve(path), options, attrs); }
        }
        getDirectoryOwner(); // Native acquisition cannot precede the factory's startup boundary.
        if (requests.size() != 1 || attrs.length != 0) throw new IllegalArgumentException("Invalid native acquisition request");
        var request = requests.getFirst();
        var endpoint = request.getEndpoint();
        if (endpoint != null) {
            if (!standardEndpoints.contains(endpoint)) throw new SecurityException("Native standard endpoint was not explicitly granted: " + endpoint);
            var remaining = new LinkedHashSet<OpenOption>(options);
            remaining.remove(request);
            return request.acquire(null, remaining, null);
        }
        try (var directory = directoryOwner.borrow()) {
            var remaining = new LinkedHashSet<OpenOption>(options);
            remaining.remove(request);
            return request.acquire(path, remaining, directory);
        }
    }
    @Override public DirectoryStream<Path> newDirectoryStream(Path path, DirectoryStream.Filter<? super Path> filter) throws IOException {
        var owner = directoryOwner;
        var directory = owner == null ? null : owner.borrow();
        try {
            var stream = host.newDirectoryStream(directory == null ? startupPath(path) : directory.resolve(path), entry -> filter.accept(path.resolve(entry.getFileName())));
            return new DirectoryStream<>() {
                @Override public Iterator<Path> iterator() {
                    var entries = stream.iterator();
                    return new Iterator<>() {
                        @Override public boolean hasNext() { return entries.hasNext(); }
                        @Override public Path next() { return path.resolve(entries.next().getFileName()); }
                        @Override public void remove() { entries.remove(); }
                    };
                }
                @Override public void close() throws IOException {
                    try { stream.close(); } finally { if (directory != null) directory.close(); }
                }
            };
        } catch (Throwable failure) {
            if (directory != null) try { directory.close(); } catch (Throwable closing) { failure.addSuppressed(closing); }
            throw failure;
        }
    }
}
