// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
import com.oracle.truffle.api.nodes.Node
import thc.Language

/** GHC 9.14.1 rts/posix/TTY.c's three saved pointers, scoped to a Context.
 * These operations never dereference, copy, allocate or free terminal storage.
 * Retaining the original Addr# keeps its existing backing and offset intact. */
internal class SavedTermios(private val owner: Language.State) {
    private val slots = arrayOfNulls<ManagedAddress>(3)
    private var closed = false
    private fun check(fd: Long) {
        if (closed || Language.currentState() !== owner) fault("Saved termios belongs to another or closed THC context")
        if (fd != fd.toInt().toLong()) fault("Original saved termios requires a canonical signed CInt descriptor")
    }
    @Synchronized @TruffleBoundary fun get(fd: Long): ManagedAddress {
        check(fd)
        return if (fd in 0L..2L) slots[fd.toInt()] ?: ManagedAddress.nullAddress() else ManagedAddress.nullAddress()
    }
    @Synchronized @TruffleBoundary fun set(fd: Long, address: ManagedAddress) {
        check(fd)
        if (fd in 0L..2L) slots[fd.toInt()] = address.takeUnless { it === ManagedAddress.nullAddress() }
    }
    @Synchronized internal fun retainedCount(): Int = slots.count { it != null }
    @Synchronized fun close() { closed = true; slots.fill(null) }

    companion object {
        @JvmStatic fun execute(node: Node, operation: OriginalStdioOp, fd: Long, address: ManagedAddress): ManagedAddress {
            val saved = Language.currentState(node).savedTermios
            return when (operation) {
                OriginalStdioOp.GET_SAVED_TERMIOS -> saved.get(fd)
                OriginalStdioOp.SET_SAVED_TERMIOS -> { saved.set(fd, address); ManagedAddress.nullAddress() }
                else -> fault("Invalid saved termios operation")
            }
        }
    }
}
