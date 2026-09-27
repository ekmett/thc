// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc

import org.graalvm.polyglot.io.FileSystem
import thc.runtime.NativeDirectoryOwner
import thc.runtime.NativeOpenRequest
import thc.NativeIO.StandardEndpoint
import java.nio.channels.SeekableByteChannel
import java.nio.file.AccessMode
import java.nio.file.CopyOption
import java.nio.file.DirectoryStream
import java.nio.file.LinkOption
import java.nio.file.OpenOption
import java.nio.file.Path
import java.nio.file.attribute.FileAttribute

/** Factory-owned host-filesystem authority, not an adapter for arbitrary custom
 * filesystem permissions. Relative operations borrow the
 * context's opened directory, preserving its identity across host renames.
 * Standard endpoints require separate grants. NativeIO owns disposal. */
internal class NativeFileSystem private constructor(private val host: FileSystem,
    private val standardEndpoints: Set<StandardEndpoint>) : FileSystem by host {

    val directoryOwner = NativeDirectoryOwner(host.toAbsolutePath(host.parsePath("")))

    constructor(standardEndpoints: Set<StandardEndpoint> = emptySet()) :
        this(FileSystem.newDefaultFileSystem(), standardEndpoints.toSet())

    // Kotlin delegation does not forward Java default methods. The public and
    // internal Truffle filesystems share this owner and this one commit point.
    override fun setCurrentWorkingDirectory(currentWorkingDirectory: Path) {
        require(currentWorkingDirectory.isAbsolute) { "Current working directory must be absolute" }
        directoryOwner.change(currentWorkingDirectory)
    }

    override fun toAbsolutePath(path: Path): Path = directoryOwner.borrow().use { directory ->
        if (path.isAbsolute) path else directory.currentPath().resolve(path)
    }

    override fun toRealPath(path: Path, vararg linkOptions: LinkOption): Path = directoryOwner.borrow().use { directory ->
        val result = host.toRealPath(directory.resolve(path), *linkOptions)
        if (path.isAbsolute) result else {
            // NOFOLLOW_LINKS can retain the private descriptor prefix. Name it
            // through the same borrow; never publish a path to a released fd.
            val anchor = directory.resolve(host.parsePath("")).normalize()
            if (result.startsWith(anchor)) directory.currentPath().resolve(anchor.relativize(result)) else result
        }
    }

    override fun checkAccess(path: Path, modes: Set<AccessMode>, vararg linkOptions: LinkOption) =
        directoryOwner.borrow().use { directory -> host.checkAccess(directory.resolve(path), modes, *linkOptions) }

    override fun createDirectory(dir: Path, vararg attrs: FileAttribute<*>) =
        directoryOwner.borrow().use { directory -> host.createDirectory(directory.resolve(dir), *attrs) }

    override fun delete(path: Path) =
        directoryOwner.borrow().use { directory -> host.delete(directory.resolve(path)) }

    override fun readAttributes(path: Path, attributes: String, vararg options: LinkOption): Map<String, Any> =
        directoryOwner.borrow().use { directory -> host.readAttributes(directory.resolve(path), attributes, *options) }

    override fun copy(source: Path, target: Path, vararg options: CopyOption) =
        directoryOwner.borrow().use { directory -> host.copy(directory.resolve(source), directory.resolve(target), *options) }

    override fun move(source: Path, target: Path, vararg options: CopyOption) =
        directoryOwner.borrow().use { directory -> host.move(directory.resolve(source), directory.resolve(target), *options) }

    override fun isSameFile(path1: Path, path2: Path, vararg options: LinkOption): Boolean =
        directoryOwner.borrow().use { directory -> host.isSameFile(directory.resolve(path1), directory.resolve(path2), *options) }

    override fun newByteChannel(path: Path, options: Set<OpenOption>, vararg attrs: FileAttribute<*>): SeekableByteChannel {
        val requests = options.filterIsInstance<NativeOpenRequest>()
        if (requests.isEmpty()) return directoryOwner.borrow().use { directory ->
            host.newByteChannel(directory.resolve(path), options, *attrs)
        }
        require(requests.size == 1 && attrs.isEmpty()) { "Invalid native acquisition request" }
        val request = requests.single()
        val endpoint = request.endpoint
        if (endpoint != null) {
            if (endpoint !in standardEndpoints)
                throw SecurityException("Native standard endpoint was not explicitly granted: $endpoint")
            return request.acquire(null, options - request)
        }
        // Native acquisition consumes the original path and this same borrowed
        // directory synchronously; neither a name lookup nor a second open.
        return directoryOwner.borrow().use { directory -> request.acquire(path, options - request, directory) }
    }

    override fun newDirectoryStream(dir: Path, filter: DirectoryStream.Filter<in Path>): DirectoryStream<Path> {
        val directory = directoryOwner.borrow()
        try {
            val stream = host.newDirectoryStream(directory.resolve(dir)) { entry ->
                filter.accept(dir.resolve(entry.fileName))
            }
            return object : DirectoryStream<Path> {
                override fun iterator(): MutableIterator<Path> {
                    val entries = stream.iterator()
                    return object : MutableIterator<Path> {
                        override fun hasNext() = entries.hasNext()
                        override fun next(): Path = dir.resolve(entries.next().fileName)
                        override fun remove() = entries.remove()
                    }
                }
                override fun close() {
                    try { stream.close() } finally { directory.close() }
                }
            }
        } catch (failure: Throwable) {
            try { directory.close() } catch (closing: Throwable) { failure.addSuppressed(closing) }
            throw failure
        }
    }
}
