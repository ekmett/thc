// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
import com.oracle.truffle.api.TruffleLanguage
import com.oracle.truffle.api.nodes.Node
import thc.Language

/** A context's POSIX environment. Guest changes never mutate the JVM process.
 * putenv retains the supplied string, as required by the original GHC caller;
 * getenv and environ expose the same storage, not decoded/re-encoded copies. */
internal class GuestEnvironment(private val env: TruffleLanguage.Env) {
    private var entries: MutableList<ManagedAddress>? = null
    private var vector: ManagedAddress? = null

    private fun current(): ManagedNativeAllocations {
        val state = Language.currentState()
        if (state.environment !== this) fault("Environment belongs to another context")
        return state.nativeAllocations
    }

    private fun snapshot(address: ManagedAddress): ByteArray = address.withNativeBorrow {
        val size = address.cStringLength()
        if (size >= Int.MAX_VALUE) fault("Environment string exceeds managed byte capacity")
        ByteArray(size.toInt()) { address.readWord8(it.toLong()).toByte() }
    }

    private fun contents(): MutableList<ManagedAddress> {
        current()
        entries?.let { return it }
        val allocations = current()
        val created = ArrayList<ManagedAddress>()
        try {
            // Truffle applies the embedding host's environment-access policy.
            // The selected Linux filesystem encoding is UTF-8.
            for ((key, value) in env.environment) {
                val bytes = "$key=$value".toByteArray(Charsets.UTF_8)
                val address = allocations.malloc(bytes.size.toLong() + 1)
                if (address === ManagedAddress.nullAddress()) throw OutOfMemoryError("Unable to allocate environment")
                created.add(address)
                for (index in bytes.indices) address.writeWord8(index.toLong(), bytes[index].toLong())
                address.writeWord8(bytes.size.toLong(), 0)
            }
            entries = created
            return created
        } catch (failure: Throwable) {
            for (address in created.asReversed()) try { allocations.free(address) }
                catch (closing: Throwable) { failure.addSuppressed(closing) }
            throw failure
        }
    }

    private fun matches(entry: ManagedAddress, name: ByteArray): Boolean {
        val bytes = snapshot(entry)
        return bytes.size > name.size && bytes[name.size] == '='.code.toByte() &&
            name.indices.all { name[it] == bytes[it] }
    }

    @Synchronized @TruffleBoundary
    fun get(name: ManagedAddress): ManagedAddress {
        current()
        val bytes = snapshot(name)
        if (bytes.isEmpty() || '='.code.toByte() in bytes) return ManagedAddress.nullAddress()
        return contents().firstOrNull { matches(it, bytes) }?.plus(bytes.size.toLong() + 1)
            ?: ManagedAddress.nullAddress()
    }

    @Synchronized @TruffleBoundary
    fun put(entry: ManagedAddress): Long {
        current()
        val bytes = snapshot(entry)
        val equals = bytes.indexOf('='.code.toByte())
        // Linux putenv("NAME") removes NAME; the original GHC setEnv always
        // supplies NAME=VALUE. Keep the empty-name errno behavior too.
        if (equals < 0) return remove(bytes)
        val name = bytes.copyOfRange(0, equals)
        val values = contents()
        val index = values.indexOfFirst { matches(it, name) }
        if (index < 0) values.add(entry) else values[index] = entry
        retireVector()
        return 0
    }

    @Synchronized @TruffleBoundary
    fun unset(name: ManagedAddress): Long {
        current()
        return remove(snapshot(name))
    }

    private fun remove(name: ByteArray): Long {
        if (name.isEmpty() || '='.code.toByte() in name) {
            Language.currentState().stdio.nativeError(22) // Linux EINVAL.
            return -1
        }
        if (contents().removeAll { matches(it, name) }) retireVector()
        return 0
    }

    @Synchronized @TruffleBoundary
    fun environ(): ManagedAddress {
        val allocations = current()
        vector?.let { return it }
        val values = contents()
        val address = allocations.malloc((values.size.toLong() + 1) * 8)
        if (address === ManagedAddress.nullAddress()) throw OutOfMemoryError("Unable to allocate environment vector")
        try {
            for (index in values.indices) address.writeAddressElementIndex(index.toLong(), values[index])
            address.writeAddressElementIndex(values.size.toLong(), ManagedAddress.nullAddress())
            vector = address
            return address
        } catch (failure: Throwable) {
            try { allocations.free(address) } catch (closing: Throwable) { failure.addSuppressed(closing) }
            throw failure
        }
    }

    private fun retireVector() {
        val old = vector
        vector = null
        old?.let { current().free(it) }
        // Returned strings remain live until their original owner releases them
        // or the context closes. Caller-owned putenv strings are never freed here.
    }

    companion object {
        @JvmStatic fun current(node: Node?): GuestEnvironment = Language.currentState(node).environment
    }
}
