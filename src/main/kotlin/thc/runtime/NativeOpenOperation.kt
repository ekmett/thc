// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime

import com.oracle.truffle.api.TruffleSafepoint
import com.oracle.truffle.api.nodes.Node
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemoryLayout
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout
import java.lang.invoke.MethodHandle
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean
import thc.NativeIO

/** One original safe/interruptible open. Only its native worker acquires the
 * file. Safepoint retries wait for the SAME request; they never replay open.
 * The request owns its pathname and fd until transfer to the existing lease.
 * No guest exception is claimed here: GHC's wrapper delivers after failure.
 */
internal class NativeOpenOperation(path: ByteArray, flags: Int, mode: Int) : AutoCloseable,
    TruffleSafepoint.Interrupter {
    private val interrupted = AtomicBoolean()
    private var handle: MemorySegment? = NativeOpenApi.start(path, flags, mode)

    override fun interrupt(thread: Thread) {
        interrupted.set(true)
        NativeOpenApi.wake(checkNotNull(handle))
    }

    override fun resetInterrupted() {
        NativeOpenApi.reset(checkNotNull(handle))
        interrupted.set(false)
    }

    fun await(node: Node?, threads: GuestThreads, interruptible: Boolean, lease: NativeFileLease) {
        val request = checkNotNull(handle)
        val action = TruffleSafepoint.InterruptibleFunction<Unit, Unit> {
            while (!NativeOpenApi.done(request)) {
                if (interruptible && threads.interruptibleForeignPending()) NativeOpenApi.cancel(request)
                if (interrupted.get()) throw InterruptedException()
                NativeOpenApi.await(request)
                if (interrupted.get()) throw InterruptedException()
            }
        }
        TruffleSafepoint.getCurrent().setBlockedFunction(node, this, action, Unit, null, null)
        // Interrupter is unregistered before destruction/publication. A hard
        // unwind before this point takes close(), cancelling and joining first.
        val error = NativeOpenApi.finish(request, lease.openSlot())
        handle = null
        if (error != 0) throw NativeFileException("open", error)
    }

    override fun close() {
        val request = handle ?: return
        try { NativeOpenApi.cancel(request) }
        finally {
            // No LLVM/current-context dependency: also runs on hard shutdown.
            NativeOpenApi.finish(request, MemorySegment.NULL)
            handle = null
        }
    }
}

/** This helper must execute machine code: its signal handler must never enter
 * Sulong/JVM. Linking is lazy and limited to the fixed native-file capability. */
private object NativeOpenApi {
    private val linker = Linker.nativeLinker()
    private val library: SymbolLookup = run {
        check(NativeIO.supportedHost()) { "Interruptible open requires Linux x86_64" }
        val file = Files.createTempFile("thc-open-", ".so")
        file.toFile().deleteOnExit()
        NativeOpenOperation::class.java.getResourceAsStream("/thc/native/native-open-request.so").use { input ->
            if (input == null) fault("Missing native open request bridge")
            Files.copy(input, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        }
        // The installed handler references this code, never request memory.
        SymbolLookup.libraryLookup(file, Arena.global())
    }
    private fun function(name: String, result: MemoryLayout?, vararg arguments: MemoryLayout): MethodHandle =
        linker.downcallHandle(library.find("thc_open_$name").orElseThrow(),
            if (result == null) FunctionDescriptor.ofVoid(*arguments) else FunctionDescriptor.of(result, *arguments))
    private val start = function("start", ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
        ValueLayout.JAVA_INT, ValueLayout.ADDRESS)
    private val done = function("done", ValueLayout.JAVA_INT, ValueLayout.ADDRESS)
    private val cancel = function("cancel", ValueLayout.JAVA_INT, ValueLayout.ADDRESS)
    private val wake = function("wake", null, ValueLayout.ADDRESS)
    private val reset = function("reset", null, ValueLayout.ADDRESS)
    private val wait = function("wait", ValueLayout.JAVA_INT, ValueLayout.ADDRESS)
    private val finish = function("finish", ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS)

    fun start(path: ByteArray, flags: Int, mode: Int): MemorySegment = Arena.ofConfined().use { arena ->
        check(path.isNotEmpty() && path.last() == 0.toByte()) { "Native open requires a terminated pathname snapshot" }
        val bytes = arena.allocate(path.size.toLong()).also { it.copyFrom(MemorySegment.ofArray(path)) }
        val error = arena.allocate(ValueLayout.JAVA_INT)
        val result = start.invokeWithArguments(bytes, flags, mode, error) as MemorySegment
        if (result.address() == 0L) fault("Native open request unavailable (errno ${error.get(ValueLayout.JAVA_INT, 0)})")
        result
    }
    fun done(request: MemorySegment) = done.invokeWithArguments(request) as Int != 0
    fun cancel(request: MemorySegment) {
        val error = cancel.invokeWithArguments(request) as Int
        if (error != 0) fault("Native open cancellation unavailable (errno $error)")
    }
    fun wake(request: MemorySegment) { wake.invokeWithArguments(request) }
    fun reset(request: MemorySegment) { reset.invokeWithArguments(request) }
    fun await(request: MemorySegment) {
        val result = wait.invokeWithArguments(request) as Int
        if (result == 0) throw InterruptedException()
        if (result < 0) fault("Native open completion wait failed (errno ${-result})")
    }
    fun finish(request: MemorySegment, lease: MemorySegment): Int {
        val result = finish.invokeWithArguments(request, lease) as Int
        if (result < 0) fault("Native open worker join failed (errno ${-result})")
        return result
    }
}
