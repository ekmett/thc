// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import java.io.Closeable;
import java.io.IOException;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiFunction;
import thc.NativeIO.StandardEndpoint;

/** One synchronous configured-provider transaction, not a path/fd registry.
 * Completion is inseparable from the channel returned by NativeFileSystem.
 * Abort retains the same lease if a provider throws after completing acquisition. */
public final class NativeOpenRequest implements OpenOption, Closeable {
    private final StandardEndpoint endpoint;
    private final Set<OpenOption> expectedOptions;
    private final BiFunction<Path, NativeDirectoryOwner.Borrow, NativeFileResource> create;
    private final Thread thread = Thread.currentThread();
    private NativeFileResource resource;
    private boolean completed, closed;

    public NativeOpenRequest(StandardEndpoint endpoint, Set<OpenOption> expectedOptions,
            BiFunction<Path, NativeDirectoryOwner.Borrow, NativeFileResource> create) {
        this.endpoint = endpoint;
        this.expectedOptions = Objects.requireNonNull(expectedOptions);
        this.create = Objects.requireNonNull(create);
    }
    public StandardEndpoint getEndpoint() { return endpoint; }
    public SeekableByteChannel acquire(Path path, Set<OpenOption> options) { return acquire(path, options, null); }
    public synchronized SeekableByteChannel acquire(Path path, Set<OpenOption> options, NativeDirectoryOwner.Borrow directory) {
        Objects.requireNonNull(options);
        if (Thread.currentThread() != thread || closed || completed)
            throw new IllegalStateException("Closed, late, or duplicate native acquisition");
        if (!options.equals(expectedOptions) || (path == null) != (endpoint != null))
            throw new IllegalArgumentException("Changed native acquisition options");
        completed = true;
        var acquired = create.apply(path, directory);
        resource = acquired;
        return acquired;
    }
    public synchronized OpenedNativeFile commit(SeekableByteChannel channel) {
        Objects.requireNonNull(channel);
        if (Thread.currentThread() != thread || closed) throw new IllegalStateException("Native acquisition is not pending");
        var acquired = resource;
        if (acquired == null) throw new UnsupportedOperationException("FileSystem did not provide opened-resource metadata");
        acquired.requireLive();
        var result = new OpenedNativeFile(channel, acquired);
        resource = null;
        closed = true;
        return result;
    }
    @Override public synchronized void close() throws IOException {
        if (closed) return;
        closed = true;
        var acquired = resource;
        resource = null;
        if (acquired != null) acquired.close();
    }
}
