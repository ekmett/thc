// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc

import org.graalvm.polyglot.Context
import thc.runtime.NativeFileProvider

/** Explicit Linux host-filesystem authority for an opt-in context. This factory
 * owns the final filesystem configuration and returns a built Context, never a
 * mutable Builder. It cannot authenticate arbitrary filesystem wrappers.
 * Provider proof only: no CLI/ManagedFiles replacement or original fstat yet. */
object NativeIO {
    enum class StandardEndpoint { INPUT, OUTPUT, ERROR }

    @JvmStatic fun createContext(standardEndpoints: Set<StandardEndpoint> = emptySet()): Context =
        NativeFileProvider.createContext(standardEndpoints.toSet())
}
