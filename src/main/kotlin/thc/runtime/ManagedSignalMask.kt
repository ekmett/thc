// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
import com.oracle.truffle.api.TruffleSafepoint
import com.oracle.truffle.api.interop.InteropLibrary
import com.oracle.truffle.api.nodes.Node
import com.oracle.truffle.api.source.Source
import org.graalvm.polyglot.io.ByteSequence
import thc.Language
import thc.NativeIO
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.atomic.AtomicReference

/** Partial original sigprocmask: query or effective SIGTTOU-only changes on a
 * platform thread with native authority. The real OS thread owns the mask;
 * neither context disposal nor guest-thread identity implies mask restoration.
 * Original callers must restore on the same platform thread. */
internal class ManagedSignalMask(private val owner: Language.State) {
    private val interop = InteropLibrary.getUncached()
    private val libraryTask = AtomicReference<FutureTask<Any>?>()

    private fun authority() {
        if (Language.currentState() !== owner) fault("Signal-mask service belongs to another context")
        if (!owner.env.isNativeAccessAllowed) fault("Original sigprocmask requires native access")
        if (Thread.currentThread().isVirtual) fault("Original sigprocmask requires a platform thread")
        if (!NativeIO.supportedHost()) fault("Original sigprocmask requires Linux x86_64 glibc")
    }

    private fun library(): Any {
        var task = libraryTask.get()
        if (task == null) {
            val candidate = FutureTask<Any> {
                val bytes = javaClass.getResourceAsStream("/thc/native/native-signal-api.so")?.use { it.readBytes() }
                    ?: fault("Missing native signal-mask bridge")
                val result = owner.env.parseInternal(Source.newBuilder("llvm", ByteSequence.create(bytes), "native-signal-api.so").build()).call()
                val size = interop.asLong(interop.execute(interop.readMember(result, "thc_signal_size")))
                if (size != imageSize()) fault("Native signal-mask/image ABI mismatch")
                result
            }
            if (libraryTask.compareAndSet(null, candidate)) { task = candidate; candidate.run() }
            else task = libraryTask.get()
        }
        return try {
            val selected = task!!
            if (selected.isDone) selected.get()
            else TruffleSafepoint.setBlockedThreadInterruptibleFunction(null,
                TruffleSafepoint.InterruptibleFunction<FutureTask<Any>, Any> { it.get() }, selected)
        } catch (failure: ExecutionException) {
            libraryTask.compareAndSet(task, null)
            throw (failure.cause ?: failure)
        }
    }

    private fun imageSize() = TermiosImage.scalar(OriginalStdioOp.SIZEOF_SIGSET, ManagedAddress.nullAddress(), 0)

    @TruffleBoundary fun call(how: Long, set: ManagedAddress, oldset: ManagedAddress): Long {
        authority() // Before native loading, buffer access, or mask effects.
        if (how != how.toInt().toLong()) fault("Original sigprocmask requires a canonical signed CInt")
        val size = imageSize()
        if (set !== ManagedAddress.nullAddress()) set.requireByteRegion(size)
        if (oldset !== ManagedAddress.nullAddress()) oldset.requireByteRegion(size, writable = true)
        if (set !== ManagedAddress.nullAddress() && oldset !== ManagedAddress.nullAddress() &&
            set.overlaps(0, size, oldset, 0, size))
            fault("Original sigprocmask requires disjoint restrict-qualified input/output images")
        // Resolve LLVM before borrowing caller storage. Both owners then remain
        // live from the input snapshot through the native effect and copyback.
        val function = interop.readMember(library(), "thc_signal_mask")
        return set.withNativeBorrows(oldset) {
            val input = if (set === ManagedAddress.nullAddress()) null else {
                val snapshot = {
                    set.requireByteRegion(size)
                    ByteArray(size.toInt()) { set.readWord8(it.toLong()).toByte() }
                }
                val allocation = set.cbitsOwner()
                if (allocation == null) snapshot() else synchronized(allocation) { snapshot() }
            }
            if (input != null && oldset !== ManagedAddress.nullAddress() && set.overlaps(0, size, oldset, 0, size))
                fault("Original sigprocmask requires disjoint restrict-qualified input/output images")
            if (oldset !== ManagedAddress.nullAddress()) oldset.requireByteRegion(size, writable = true)
            val invoke = {
                if (oldset !== ManagedAddress.nullAddress()) oldset.requireByteRegion(size, writable = true)
                NativeLimbScope().use { scope ->
                    val output = if (oldset === ManagedAddress.nullAddress()) null else ByteArray(size.toInt()) { oldset.readWord8(it.toLong()).toByte() }
                    val nativeInput: Any = input?.let { scope.snapshot(it, 0, it.size) } ?: scope.allocate(0)
                    val nativeOutput: Any = output?.let { scope.snapshot(it, 0, it.size) } ?: scope.allocate(0)
                    val error = scope.allocate(8)
                    val previous = owner.threads.enterForeign()
                    try {
                        val result = interop.asLong(interop.execute(function, how.toInt(), nativeInput, nativeOutput, if (input == null) 0 else 1, if (output == null) 0 else 1, error))
                        val errno = error.readWord(0)
                        if (result == -2L && errno == 0L) fault("Original sigprocmask supports only effective SIGTTOU mask changes")
                        if (result !in -1L..0L || (result == -1L && errno !in 1L..Int.MAX_VALUE.toLong()) ||
                            (result == 0L && errno != 0L)) fault("Invalid native signal-mask result")
                        if (output != null) {
                            (nativeOutput as NativeLimbScope.Pointer).copyTo(output, 0, output.size)
                            for (i in output.indices) oldset.writeWord8(i.toLong(), output[i].toLong())
                        }
                        if (result == -1L) owner.stdio.nativeError(errno)
                        result
                    } finally { owner.threads.leaveForeign(previous) }
                }
            }
            // Snapshot input before locking output: no inverse two-allocation lock
            // order, and shrink/pointer-cell installation cannot invalidate copyback.
            val allocation = oldset.cbitsOwner()
            if (allocation == null) invoke() else synchronized(allocation) { invoke() }
        }
    }

    companion object {
        @JvmStatic fun execute(node: Node, how: Long, set: ManagedAddress, oldset: ManagedAddress): Long =
            Language.currentState(node).signalMask.call(how, set, oldset)
    }
}
