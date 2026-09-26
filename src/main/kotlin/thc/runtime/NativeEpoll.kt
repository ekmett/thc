// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime

import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemoryLayout
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout

/** Real kernel set. A distinct duplicate per logical Descriptor preserves Linux
 * registrations for dup aliases without exposing process fd integers to Core. */
internal class NativeEpoll(private val descriptor: Int) : AutoCloseable {
    private val registrations = linkedMapOf<Any, Registration>()
    private var closed = false

    inner class Registration internal constructor(val key: Any, internal val descriptor: Int,
        private val detach: (Registration) -> Unit) : AutoCloseable {
        private var closed = false
        internal fun finishDeleted() {
            if (closed) return
            closed = true
            registrations.remove(key)
            try { NativePollApi.close(descriptor) } finally { detach(this) }
        }
        override fun close() = synchronized(this@NativeEpoll) {
            if (!closed) {
                try {
                    if (!this@NativeEpoll.closed) NativeEpollApi.control(this@NativeEpoll.descriptor, 2, descriptor, null)
                } finally { finishDeleted() }
            }
        }
    }

    @Synchronized fun control(operation: Int, key: Any, target: NativeFileResource, event: ByteArray?,
        attach: (Registration) -> Unit, detach: (Registration) -> Unit) {
        if (closed) throw NativeFileException("epoll_ctl", 9)
        if (operation != 1 && operation != 2 && operation != 3) throw NativeFileException("epoll_ctl", 22)
        val existing = registrations[key]
        if (existing != null) {
            // Let the kernel report EEXIST for a duplicate ADD too.
            NativeEpollApi.control(descriptor, operation, existing.descriptor, event)
            if (operation == 2) {
                // DEL has succeeded; close only the duplicate and callback.
                existing.finishDeleted()
            }
            return
        }
        if (operation != 1) throw NativeFileException("epoll_ctl", 2) // ENOENT, including logical fd reuse.
        val duplicate = target.duplicateDescriptor()
        var registered = false
        val registration = Registration(key, duplicate, detach)
        try {
            NativeEpollApi.control(descriptor, operation, duplicate, event)
            registered = true
            registrations[key] = registration
            attach(registration)
        } catch (failure: Throwable) {
            try { if (registered) registration.close() else NativePollApi.close(duplicate) }
                catch (closing: Throwable) { failure.addSuppressed(closing) }
            throw failure
        }
    }

    @Synchronized fun ready(destination: ManagedAddress, maximum: Int): Int {
        if (closed) throw NativeFileException("epoll_wait", 9)
        return NativeEpollApi.ready(descriptor, destination, maximum)
    }

    @Synchronized override fun close() {
        if (closed) return
        closed = true
        var failure: Throwable? = null
        for (registration in registrations.values.toList()) try { registration.close() }
            catch (error: Throwable) { if (failure == null) failure = error else failure.addSuppressed(error) }
        try { NativePollApi.close(descriptor) }
        catch (error: Throwable) { if (failure == null) failure = error else failure.addSuppressed(error) }
        failure?.let { throw it }
    }
}

/** Image ABI is checked against native headers by native-file-api.c. Kernel
 * event data remains opaque bytes; it is never mistaken for a host descriptor. */
private object NativeEpollApi {
    private val linker = Linker.nativeLinker()
    private val capture = Linker.Option.captureStateLayout()
    private val errnoOffset = capture.byteOffset(MemoryLayout.PathElement.groupElement("errno"))
    private val ctl = linker.downcallHandle(linker.defaultLookup().find("epoll_ctl").orElseThrow(),
        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
            ValueLayout.JAVA_INT, ValueLayout.ADDRESS), Linker.Option.captureCallState("errno"))
    private val wait = linker.downcallHandle(linker.defaultLookup().find("epoll_wait").orElseThrow(),
        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS,
            ValueLayout.JAVA_INT, ValueLayout.JAVA_INT), Linker.Option.captureCallState("errno"))

    fun control(descriptor: Int, operation: Int, target: Int, event: ByteArray?) = Arena.ofConfined().use { arena ->
        val errors = arena.allocate(capture)
        val image = if (event == null) MemorySegment.NULL else arena.allocate(12, 8).also {
            it.copyFrom(MemorySegment.ofArray(event))
        }
        if ((ctl.invokeWithArguments(errors, descriptor, operation, target, image) as Int) < 0)
            throw NativeFileException("epoll_ctl", errors.get(ValueLayout.JAVA_INT, errnoOffset))
    }

    fun ready(descriptor: Int, destination: ManagedAddress, maximum: Int): Int = Arena.ofConfined().use { arena ->
        val errors = arena.allocate(capture)
        val image = arena.allocate(maximum * 12L, 8)
        val count = wait.invokeWithArguments(errors, descriptor, image, maximum, 0) as Int
        if (count < 0) throw NativeFileException("epoll_wait", errors.get(ValueLayout.JAVA_INT, errnoOffset))
        for (index in 0 until count * 12) destination.writeWord8(index.toLong(),
            image.get(ValueLayout.JAVA_BYTE, index.toLong()).toLong())
        count
    }
}
