// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary

internal const val CLASS_OWNED_LAYOUTS_PROPERTY = "thc.classOwnedLayouts"

/** Permanent class ownership for values that do not carry a layout pointer.
 * The ClassValue instance never keeps a separate strong collection of its keys. */
internal object ClassOwnedLayouts {
    private class Owner {
        private var token: Any? = null
        @Volatile private var descriptor: DataLayout? = null

        @Synchronized fun reserve(candidate: Any): Boolean {
            if (token == null) token = candidate
            return token === candidate
        }

        @Synchronized fun publish(candidate: Any, layout: DataLayout) {
            if (token !== candidate || descriptor != null && descriptor !== layout)
                fault("Invalid permanent constructor class ownership")
            // Release only after all immutable descriptor fields and cached values
            // have initialized. Reservation alone must not publish a partial `this`.
            descriptor = layout
        }

        fun resolve(): DataLayout = descriptor ?: fault("Unpublished constructor class ownership")
    }

    // Composition is deliberate: callers must not gain ClassValue.remove().
    private val owners = object : ClassValue<Owner>() {
        override fun computeValue(type: Class<*>): Owner = Owner()
    }

    @TruffleBoundary fun reserve(carrier: Class<*>, token: Any): Boolean = owners.get(carrier).reserve(token)
    @TruffleBoundary fun publish(carrier: Class<*>, token: Any, layout: DataLayout) =
        owners.get(carrier).publish(token, layout)

    /** Generic/debug access only. Compiled constructor operations already know their layout. */
    @TruffleBoundary fun resolve(carrier: Class<*>): DataLayout = owners.get(carrier).resolve()
}
