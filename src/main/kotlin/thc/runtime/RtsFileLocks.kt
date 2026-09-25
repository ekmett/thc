// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary

/** Original RTS bookkeeping, not OS locks or ManagedFiles owner claims.
 * Word64 keys and identities are bit patterns, not host/context descriptors. */
internal class RtsFileLocks {
    private data class Identity(val device: Long, val inode: Long)
    private class Lock(val identity: Identity, var readers: Int)
    private class Key(val lock: Lock, var claims: Int = 1)
    private val objects = HashMap<Identity, Lock>()
    private val keys = HashMap<Long, Key>()
    private var disposed = false

    @TruffleBoundary @Synchronized fun lock(key: Long, device: Long, inode: Long, writing: Long): Long {
        if (disposed) fault("RTS file lock context is closed")
        if (writing != writing.toInt().toLong()) fault("Original lockFile requires a canonical signed CInt flag")
        val identity = Identity(device, inode)
        val oldKey = keys[key]
        if (oldKey != null && oldKey.lock.identity != identity)
            fault("RTS lock key already refers to a different resource")
        val old = objects[identity]
        if (old != null && (writing != 0L || old.readers < 0)) return -1
        if (old?.readers == Int.MAX_VALUE || oldKey?.claims == Int.MAX_VALUE)
            fault("RTS reader claim count exceeds the original signed int range")
        val current = old ?: Lock(identity, if (writing == 0L) 0 else -1).also { objects[identity] = it }
        if (writing == 0L) current.readers++
        if (oldKey == null) keys[key] = Key(current) else oldKey.claims++
        return 0
    }

    @TruffleBoundary @Synchronized fun unlock(key: Long): Long {
        if (disposed) fault("RTS file lock context is closed")
        val claim = keys[key] ?: return 1
        val current = claim.lock
        if (current.readers < 0) current.readers++ else current.readers--
        if (current.readers == 0) objects.remove(current.identity)
        if (--claim.claims == 0) keys.remove(key)
        return 0
    }

    @TruffleBoundary @Synchronized fun dispose() {
        disposed = true
        keys.clear(); objects.clear()
    }
}
