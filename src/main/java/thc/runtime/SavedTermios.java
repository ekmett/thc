// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.nodes.Node;
import java.util.Arrays;
import thc.Language;
import static thc.runtime.RuntimeServiceStatus.fault;

/** GHC 9.14.1 rts/posix/TTY.c's three saved pointers, scoped to a Context.
 * These operations never dereference, copy, allocate or free terminal storage.
 * Retaining the original Addr# keeps its existing backing and offset intact. */
public final class SavedTermios {
    private final Language.State owner;
    private final ManagedAddress[] slots = new ManagedAddress[3];
    private boolean closed;

    public SavedTermios(Language.State owner) { this.owner = owner; }
    private void check(long fd) {
        if (closed || Language.currentState(null) != owner) throw fault("Saved termios belongs to another or closed THC context");
        if (fd != (long) (int) fd) throw fault("Original saved termios requires a canonical signed CInt descriptor");
    }
    @TruffleBoundary public synchronized ManagedAddress get(long fd) {
        check(fd);
        return fd >= 0 && fd <= 2 && slots[(int) fd] != null ? slots[(int) fd] : ManagedAddress.nullAddress();
    }
    @TruffleBoundary public synchronized void set(long fd, ManagedAddress address) {
        check(fd);
        if (fd >= 0 && fd <= 2) slots[(int) fd] = address == ManagedAddress.nullAddress() ? null : address;
    }
    public synchronized int retainedCount() {
        int count = 0;
        for (var slot : slots) if (slot != null) count++;
        return count;
    }
    public synchronized void close() { closed = true; Arrays.fill(slots, null); }

    public static ManagedAddress execute(Node node, OriginalStdioOp operation, long fd, ManagedAddress address) {
        var saved = Language.currentState(node).getSavedTermios();
        return switch (operation) {
            case GET_SAVED_TERMIOS -> saved.get(fd);
            case SET_SAVED_TERMIOS -> { saved.set(fd, address); yield ManagedAddress.nullAddress(); }
            default -> throw fault("Invalid saved termios operation");
        };
    }
}
