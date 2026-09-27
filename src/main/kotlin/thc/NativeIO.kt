// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc

import org.graalvm.polyglot.Context
import thc.runtime.NativeFileProvider
import thc.runtime.WindowsDirectoryStreams

/** Explicit host-filesystem authority for an opt-in context. Linux provides
 * native files; Windows currently provides the original Win32 directory search API.
 * This factory
 * owns the final filesystem configuration and returns a built Context, never a
 * mutable Builder. It cannot authenticate arbitrary filesystem wrappers.
 * Connects the provider to this context's shared managed descriptor owners.
 * The supported Linux command line uses this authority with explicit standard
 * endpoint grants; custom embedding contexts keep their own IO configuration. */
object NativeIO {
    enum class StandardEndpoint { INPUT, OUTPUT, ERROR }

    @JvmStatic fun createContext(standardEndpoints: Set<StandardEndpoint> = emptySet()): Context =
        createContext(standardEndpoints, allowProcesses = false)

    @JvmStatic fun createContext(standardEndpoints: Set<StandardEndpoint> = emptySet(), allowProcesses: Boolean): Context =
        if (WindowsDirectoryStreams.supportedHost()) {
            require(!allowProcesses) { "Native subprocesses currently require Linux x86_64" }
            WindowsDirectoryStreams.createContext()
        } else NativeFileProvider.createContext(standardEndpoints.toSet(), allowProcesses = allowProcesses)

    internal fun supportedHost(): Boolean = supportedPosixHost() || WindowsDirectoryStreams.supportedHost()

    internal fun supportedPosixHost(): Boolean = System.getProperty("os.name") == "Linux" &&
        System.getProperty("os.arch") in setOf("amd64", "x86_64")

    internal fun commandLineContext(ffiMode: FfiMode): Context =
        if (WindowsDirectoryStreams.supportedHost()) WindowsDirectoryStreams.createContext(ContextProfile.LAUNCHER, ffiMode)
        else NativeFileProvider.createContext(StandardEndpoint.entries.toSet(), ContextProfile.LAUNCHER, ffiMode, allowProcesses = true)
}
