// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc

import org.graalvm.polyglot.io.FileSystem
import thc.runtime.NativeOpenRequest
import thc.NativeIO.StandardEndpoint
import java.nio.channels.SeekableByteChannel
import java.nio.file.OpenOption
import java.nio.file.Path
import java.nio.file.attribute.FileAttribute

/** Factory-owned host-filesystem authority, not an adapter for an arbitrary
 * custom filesystem's permissions. Only NativeIO's fixed context factory may
 * pair this provider with metadata authority. Ordinary contexts are unchanged.
 * Standard endpoints require separate grants.
 * Native acquisition and opened-resource metadata are Linux-only. */
internal class NativeFileSystem private constructor(private val host: FileSystem,
    private val standardEndpoints: Set<StandardEndpoint>) : FileSystem by host {

    constructor(standardEndpoints: Set<StandardEndpoint> = emptySet()) :
        this(FileSystem.newDefaultFileSystem(), standardEndpoints.toSet())

    override fun newByteChannel(path: Path, options: Set<OpenOption>, vararg attrs: FileAttribute<*>): SeekableByteChannel {
        val requests = options.filterIsInstance<NativeOpenRequest>()
        if (requests.isEmpty()) return host.newByteChannel(path, options, *attrs)
        require(requests.size == 1 && attrs.isEmpty()) { "Invalid native acquisition request" }
        val request = requests.single()
        val endpoint = request.endpoint
        if (endpoint != null && endpoint !in standardEndpoints)
            throw SecurityException("Native standard endpoint was not explicitly granted: $endpoint")
        // This provider itself authorizes the one native acquisition. There is
        // no delegate checkAccess/open followed by a second native open.
        // For an endpoint request the path is only the FileSystem dispatch anchor.
        return request.acquire(if (endpoint == null) host.toAbsolutePath(path) else null,
            options - request)
    }
}
