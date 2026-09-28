// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.lang.foreign.ValueLayout;
import java.net.URI;
import java.nio.channels.ClosedChannelException;
import java.nio.file.Path;

/** One factory-owned directory. Borrowers own duplicate descriptors, never a
 * pathname cache; replacement and disposal cannot reuse a borrowed descriptor.
 * This object never changes the process working directory. */
public final class NativeDirectoryOwner implements Closeable {
    private NativeFileLease current;

    public NativeDirectoryOwner(Path initial) { current = acquire(-1, pathBytes(initial)); }

    public synchronized Borrow borrow() {
        var source = current;
        if (source == null) throw propagate(new ClosedChannelException());
        var copy = new NativeFileLease();
        try {
            int fd = source.duplicateForWait();
            copy.openSlot().set(ValueLayout.JAVA_INT, 0, fd);
            return new Borrow(copy, fd);
        } catch (Throwable failure) {
            try { copy.closeDirectory(); } catch (Throwable closing) { failure.addSuppressed(closing); }
            throw propagate(failure);
        }
    }

    public void change(Path path) { change(pathBytes(path)); }
    public synchronized void change(byte[] path) {
        // Acquisition and publication are one transaction; filesystem callbacks
        // read this same owner and perform no second setter after the commit.
        try (var anchor = borrow()) {
            var replacement = acquire(anchor.descriptor, path);
            var previous = current;
            current = replacement;
            previous.closeDirectory();
        }
    }
    public Path currentPath() { try (var anchor = borrow()) { return anchor.currentPath(); } }
    public byte[] name(int capacity) { try (var anchor = borrow()) { return anchor.name(capacity); } }
    @Override public synchronized void close() {
        var previous = current;
        if (previous == null) return;
        current = null;
        previous.closeDirectory();
    }

    public static final class Borrow implements Closeable {
        private final NativeFileLease lease;
        private final int descriptor;
        Borrow(NativeFileLease lease, int descriptor) { this.lease = lease; this.descriptor = descriptor; }
        public NativeFileLease getLease() { return lease; }
        public int getDescriptor() { return descriptor; }
        public Path resolve(Path path) {
            lease.requireOpen();
            return path.isAbsolute() ? path : Path.of("/proc/self/fd/" + descriptor)
                .resolve(path.equals(Path.of("")) ? Path.of(".") : path);
        }
        public byte[] name(int capacity) {
            lease.requireOpen();
            return NativeDirectoryApi.name(descriptor, capacity);
        }
        public Path currentPath() {
            int capacity = 4096;
            while (true) {
                try { return bytesPath(name(capacity)); }
                catch (Throwable failure) {
                    if (!(failure instanceof NativeFileException nativeFailure)
                        || nativeFailure.getErrno() != 34 || capacity > Integer.MAX_VALUE / 2)
                        throw propagate(failure);
                    capacity *= 2;
                }
            }
        }
        @Override public void close() { lease.closeDirectory(); }
    }

    private static NativeFileLease acquire(int at, byte[] path) {
        var lease = new NativeFileLease();
        try {
            NativeDirectoryApi.open(at, path, lease.openSlot());
            lease.requireOpen();
            return lease;
        } catch (Throwable failure) {
            try { lease.closeDirectory(); } catch (Throwable closing) { failure.addSuppressed(closing); }
            throw propagate(failure);
        }
    }

    /** Unix Path URIs preserve raw bytes, including invalid UTF-8. Relative
     * paths are placed under a fixed root, never the process working directory. */
    public static byte[] pathBytes(Path path) {
        boolean absolute = path.isAbsolute();
        var uri = (absolute ? path : Path.of("/").resolve(path)).toUri();
        check(uri.getScheme().equals("file") && (uri.getRawAuthority() == null || uri.getRawAuthority().isEmpty()));
        String raw = uri.getRawPath();
        // UnixUriUtils may append a slash after observing an unrelated host
        // directory. That observation must not change a context-relative name.
        if (!path.equals(path.getRoot()) && raw.endsWith("/")) raw = raw.substring(0, raw.length() - 1);
        var bytes = new ByteArrayOutputStream();
        int index = absolute ? 0 : 1;
        while (index < raw.length()) {
            char ch = raw.charAt(index++);
            if (ch == '%') {
                check(index + 1 < raw.length());
                int high = Character.digit(raw.charAt(index++), 16);
                int low = Character.digit(raw.charAt(index++), 16);
                if (high < 0 || low < 0) throw new IllegalArgumentException("Invalid hexadecimal path escape");
                bytes.write((high << 4) | low);
            } else {
                check(ch >= 1 && ch <= 127);
                bytes.write(ch);
            }
        }
        bytes.write(0);
        return bytes.toByteArray();
    }

    public static Path bytesPath(byte[] bytes) {
        check(bytes.length != 0 && bytes[0] == '/');
        for (byte b : bytes) check(b != 0);
        var text = new StringBuilder("file://");
        for (byte b : bytes) {
            int value = b & 255;
            if (value == '/') text.append('/');
            else text.append('%').append("0123456789ABCDEF".charAt(value >>> 4))
                .append("0123456789ABCDEF".charAt(value & 15));
        }
        return Path.of(URI.create(text.toString()));
    }
    private static void check(boolean condition) {
        if (!condition) throw new IllegalStateException("Check failed.");
    }
    @SuppressWarnings("unchecked") private static <E extends Throwable> RuntimeException propagate(Throwable failure) throws E {
        throw (E) failure;
    }
}
