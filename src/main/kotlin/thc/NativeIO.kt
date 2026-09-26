// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc

import org.graalvm.polyglot.Context
import thc.runtime.NativeFileProvider

/** Explicit Linux host-filesystem authority for an opt-in context. This factory
 * owns the final filesystem configuration and returns a built Context, never a
 * mutable Builder. It cannot authenticate arbitrary filesystem wrappers.
 * Connects the provider to this context's shared managed descriptor owners.
 * The supported Linux command line uses this authority with explicit standard
 * endpoint grants; custom embedding contexts keep their own IO configuration. */
object NativeIO {
    enum class StandardEndpoint { INPUT, OUTPUT, ERROR }

    @JvmStatic fun createContext(standardEndpoints: Set<StandardEndpoint> = emptySet()): Context =
        NativeFileProvider.createContext(standardEndpoints.toSet())

    internal fun supportedHost(): Boolean = System.getProperty("os.name") == "Linux" &&
        System.getProperty("os.arch") in setOf("amd64", "x86_64")

    internal fun commandLineContext(ffiMode: FfiMode): Context =
        NativeFileProvider.createContext(StandardEndpoint.entries.toSet(), ContextProfile.LAUNCHER, ffiMode)
}
