// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import java.util.HashMap;
import static thc.runtime.RuntimeServiceStatus.fault;

/** Original RTS bookkeeping, not OS locks or ManagedFiles owner claims.
 * Word64 keys and identities are bit patterns, not host/context descriptors. */
public final class RtsFileLocks {
    private record Identity(long device, long inode) {}
    private static final class Lock {
        final Identity identity;
        int readers;
        Lock(Identity identity, int readers) { this.identity = identity; this.readers = readers; }
    }
    private static final class Key {
        final Lock lock;
        int claims = 1;
        Key(Lock lock) { this.lock = lock; }
    }
    private final HashMap<Identity, Lock> objects = new HashMap<>();
    private final HashMap<Long, Key> keys = new HashMap<>();
    private boolean disposed;

    @TruffleBoundary
    public synchronized long lock(long key, long device, long inode, long writing) {
        if (disposed) throw fault("RTS file lock context is closed");
        if (writing != (long) (int) writing) throw fault("Original lockFile requires a canonical signed CInt flag");
        var identity = new Identity(device, inode);
        var oldKey = keys.get(key);
        if (oldKey != null && !oldKey.lock.identity.equals(identity))
            throw fault("RTS lock key already refers to a different resource");
        var old = objects.get(identity);
        if (old != null && (writing != 0L || old.readers < 0)) return -1L;
        if ((old != null && old.readers == Integer.MAX_VALUE) || (oldKey != null && oldKey.claims == Integer.MAX_VALUE))
            throw fault("RTS reader claim count exceeds the original signed int range");
        var current = old;
        if (current == null) {
            current = new Lock(identity, writing == 0L ? 0 : -1);
            objects.put(identity, current);
        }
        if (writing == 0L) current.readers++;
        if (oldKey == null) keys.put(key, new Key(current));
        else oldKey.claims++;
        return 0L;
    }

    @TruffleBoundary
    public synchronized long unlock(long key) {
        if (disposed) throw fault("RTS file lock context is closed");
        var claim = keys.get(key);
        if (claim == null) return 1L;
        var current = claim.lock;
        if (current.readers < 0) current.readers++;
        else current.readers--;
        if (current.readers == 0) objects.remove(current.identity);
        if (--claim.claims == 0) keys.remove(key);
        return 0L;
    }

    @TruffleBoundary
    public synchronized void dispose() {
        disposed = true;
        keys.clear();
        objects.clear();
    }
}
