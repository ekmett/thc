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
public final class NativeFileSystem implements FileSystem {
    private final FileSystem host;
    private final Set<StandardEndpoint> standardEndpoints;
    private final NativeDirectoryOwner directoryOwner;
    public NativeFileSystem() { this(Set.of()); }
    public NativeFileSystem(Set<StandardEndpoint> standardEndpoints) {
        this(FileSystem.newDefaultFileSystem(), new LinkedHashSet<>(standardEndpoints));
    }
    private NativeFileSystem(FileSystem host, Set<StandardEndpoint> standardEndpoints) {
        this.host = host;
        this.standardEndpoints = standardEndpoints;
        directoryOwner = new NativeDirectoryOwner(host.toAbsolutePath(host.parsePath("")));
    }
    public NativeDirectoryOwner getDirectoryOwner() { return directoryOwner; }
    @Override public Path parsePath(URI uri) { return host.parsePath(uri); }
    @Override public Path parsePath(String path) { return host.parsePath(path); }

    // Preserve the former delegation: Java defaults not explicitly overridden
    // here still run on this filesystem, not on the host delegate.
    @Override public void setCurrentWorkingDirectory(Path path) {
        if (!path.isAbsolute()) throw new IllegalArgumentException("Current working directory must be absolute");
        directoryOwner.change(path);
    }
    @Override public Path toAbsolutePath(Path path) {
        try (var directory = directoryOwner.borrow()) {
            return path.isAbsolute() ? path : directory.currentPath().resolve(path);
        }
    }
    @Override public Path toRealPath(Path path, LinkOption... options) throws IOException {
        try (var directory = directoryOwner.borrow()) {
            var result = host.toRealPath(directory.resolve(path), options);
            if (path.isAbsolute()) return result;
            var anchor = directory.resolve(host.parsePath("")).normalize();
            return result.startsWith(anchor) ? directory.currentPath().resolve(anchor.relativize(result)) : result;
        }
    }
    @Override public void checkAccess(Path path, Set<? extends AccessMode> modes, LinkOption... options) throws IOException {
        try (var directory = directoryOwner.borrow()) { host.checkAccess(directory.resolve(path), modes, options); }
    }
    @Override public void createDirectory(Path path, FileAttribute<?>... attrs) throws IOException {
        try (var directory = directoryOwner.borrow()) { host.createDirectory(directory.resolve(path), attrs); }
    }
    @Override public void delete(Path path) throws IOException {
        try (var directory = directoryOwner.borrow()) { host.delete(directory.resolve(path)); }
    }
    @Override public Map<String, Object> readAttributes(Path path, String attributes, LinkOption... options) throws IOException {
        try (var directory = directoryOwner.borrow()) { return host.readAttributes(directory.resolve(path), attributes, options); }
    }
    @Override public void copy(Path source, Path target, CopyOption... options) throws IOException {
        try (var directory = directoryOwner.borrow()) { host.copy(directory.resolve(source), directory.resolve(target), options); }
    }
    @Override public void move(Path source, Path target, CopyOption... options) throws IOException {
        try (var directory = directoryOwner.borrow()) { host.move(directory.resolve(source), directory.resolve(target), options); }
    }
    @Override public boolean isSameFile(Path first, Path second, LinkOption... options) throws IOException {
        try (var directory = directoryOwner.borrow()) { return host.isSameFile(directory.resolve(first), directory.resolve(second), options); }
    }
    @Override public SeekableByteChannel newByteChannel(Path path, Set<? extends OpenOption> options, FileAttribute<?>... attrs) throws IOException {
        var requests = new ArrayList<NativeOpenRequest>();
        for (var option : options) if (option instanceof NativeOpenRequest request) requests.add(request);
        if (requests.isEmpty()) {
            try (var directory = directoryOwner.borrow()) { return host.newByteChannel(directory.resolve(path), options, attrs); }
        }
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
        var directory = directoryOwner.borrow();
        try {
            var stream = host.newDirectoryStream(directory.resolve(path), entry -> filter.accept(path.resolve(entry.getFileName())));
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
                    try { stream.close(); } finally { directory.close(); }
                }
            };
        } catch (Throwable failure) {
            try { directory.close(); } catch (Throwable closing) { failure.addSuppressed(closing); }
            throw failure;
        }
    }
}
