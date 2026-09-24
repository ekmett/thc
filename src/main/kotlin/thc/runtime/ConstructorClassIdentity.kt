// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime

import com.oracle.truffle.api.Assumption
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
import com.oracle.truffle.api.Truffle

internal const val CONSTRUCTOR_CLASS_IDENTITY_PROPERTY = "thc.constructorClassIdentity"

/** One registry entry per actual Java class, without retaining its class or layout.
 * ClassValue owns the lifetime; the opaque token has no back-reference to either. */
private object ConstructorClassOwners {
    class Owners {
        private var owner: Any? = null
        private val exclusive = Truffle.getRuntime().createAssumption("exclusive constructor carrier")

        @Synchronized fun register(token: Any): Assumption {
            if (owner == null) owner = token
            else if (owner !== token) exclusive.invalidate()
            return exclusive
        }
    }

    // Do not inherit ClassValue: a package-local Java caller must not gain its
    // public remove() method and reset ownership while old values remain live.
    private val registry = object : ClassValue<Owners>() {
        override fun computeValue(type: Class<*>): Owners = Owners()
    }

    @TruffleBoundary fun register(type: Class<*>, owner: Any): Assumption = registry.get(type).register(owner)
}

/** Per-layout proof that Java class equality is equivalent to constructor identity. */
internal class ConstructorClassIdentity(val carrier: Class<*>) {
    // This token is separate from the storage allocation key and never enters a value.
    private val owner = Any()
    private val exclusive = ConstructorClassOwners.register(carrier, owner)
    private val uniform = Truffle.getRuntime().createAssumption("uniform constructor factory class")

    fun isExclusive(): Boolean = uniform.isValid && exclusive.isValid

    /** Every allocation is checked before escape, even with class matching disabled. */
    fun observe(actual: Class<*>) {
        if (actual !== carrier) registerAlternative(actual)
    }

    @TruffleBoundary private fun registerAlternative(actual: Class<*>) {
        uniform.invalidate()
        // Also invalidate another layout's proof if it already owns this carrier.
        ConstructorClassOwners.register(actual, owner)
    }
}
