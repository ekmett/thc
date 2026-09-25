// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime

import thc.NativeIO.StandardEndpoint
import java.io.Closeable
import java.nio.channels.SeekableByteChannel
import java.nio.file.OpenOption
import java.nio.file.Path

/** One synchronous configured-provider transaction, not a path/fd registry.
 * Completion is inseparable from the channel returned by NativeFileSystem.
 * Abort retains the same lease if a provider throws after completing acquisition. */
internal class NativeOpenRequest(val endpoint: StandardEndpoint?,
    private val expectedOptions: Set<OpenOption>,
    private val create: (Path?) -> NativeFileResource) : OpenOption, Closeable {
    private val thread = Thread.currentThread()
    private var resource: NativeFileResource? = null
    private var completed = false
    private var closed = false

    @Synchronized fun acquire(path: Path?, options: Set<OpenOption>): SeekableByteChannel {
        check(Thread.currentThread() === thread && !closed && !completed) { "Closed, late, or duplicate native acquisition" }
        require(options == expectedOptions && ((path == null) == (endpoint != null))) { "Changed native acquisition options" }
        completed = true
        return create(path).also { resource = it }
    }

    @Synchronized fun commit(channel: SeekableByteChannel): OpenedNativeFile {
        check(Thread.currentThread() === thread && !closed) { "Native acquisition is not pending" }
        val acquired = resource ?: throw UnsupportedOperationException("FileSystem did not provide opened-resource metadata")
        acquired.requireLive()
        val result = OpenedNativeFile(channel, acquired)
        resource = null
        closed = true
        return result
    }

    @Synchronized override fun close() {
        if (closed) return
        closed = true
        val acquired = resource
        resource = null
        acquired?.close()
    }
}

internal interface NativeFileResource : SeekableByteChannel {
    fun statImage(): ByteArray
    fun readTermios(image: ByteArray)
    fun terminalStatus(): Long
    fun writeTermios(action: Int, image: ByteArray)
    fun statusFlags(): Long
    fun setStatusFlags(flags: Long): Long
    fun requireLive()
    fun readinessWait(): NativeFdWait
}

/** Preserve the public Truffle channel wrapper for byte operations. Metadata
 * comes from the same configured provider transaction. No private unwrapping. */
internal class OpenedNativeFile(private val channel: SeekableByteChannel,
    private val metadata: NativeFileResource) : NativeFileResource, SeekableByteChannel by channel {
    override fun statImage(): ByteArray = metadata.statImage()
    override fun readTermios(image: ByteArray) = metadata.readTermios(image)
    override fun terminalStatus(): Long = metadata.terminalStatus()
    override fun writeTermios(action: Int, image: ByteArray) = metadata.writeTermios(action, image)
    override fun statusFlags(): Long = metadata.statusFlags()
    override fun setStatusFlags(flags: Long): Long = metadata.setStatusFlags(flags)
    override fun requireLive() = metadata.requireLive()
    override fun readinessWait(): NativeFdWait = metadata.readinessWait()
    override fun close() {
        try { channel.close() } finally { metadata.close() }
    }
}
