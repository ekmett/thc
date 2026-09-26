// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime

/** GHC's provider-independent little-limb-first, native-byte-order Word array.
 * This is still the guest allocation, not a BigInteger or a process address.
 * Counts and mutability are checked before any provider can allocate or store. */
internal class LimbRegion private constructor(val address: ManagedAddress, val limbs: Long, private val writable: Boolean) {
    val byteSize: Long get() = limbs * 8L
    fun overlaps(other: LimbRegion): Boolean = address.overlaps(0, byteSize, other.address, 0, other.byteSize)
    fun sameStart(other: LimbRegion): Boolean = address.sameLocation(other.address) ||
        (overlaps(other) && address.cbitsOffset() == other.address.cbitsOffset())
    fun requireOutput(size: Long) {
        if (!writable || limbs != size) fault("Limb output requires an exact writable region")
        address.requireRange(0, byteSize, true)
    }
    fun requirePositive() { if (limbs == 0L) fault("This limb operation requires a nonempty input") }
    internal fun validate() { address.requireRange(0, byteSize, writable) }

    companion object {
        fun read(value: Any?, limbs: Long, allowEmpty: Boolean = false): LimbRegion =
            checked(value, limbs, false, allowEmpty)
        fun write(value: Any?, limbs: Long, allowEmpty: Boolean = false): LimbRegion =
            checked(value, limbs, true, allowEmpty)
        private fun checked(value: Any?, limbs: Long, writable: Boolean, allowEmpty: Boolean): LimbRegion {
            return region(ManagedAddress.fromGuestByteArray(value), limbs, writable, allowEmpty)
        }
        // Useful for provider subregions; original ByteArray FCalls still enter
        // exclusively through read/write above, never through guest Addr# casts.
        internal fun region(address: ManagedAddress, limbs: Long, writable: Boolean,
            allowEmpty: Boolean = false): LimbRegion {
            if (limbs < (if (allowEmpty) 0L else 1L) || limbs > Int.MAX_VALUE.toLong() / 8L)
                fault("Limb count outside managed Word-array domain")
            address.requireRange(0, limbs * 8L, writable)
            // Native providers must additionally reject pointer-bearing storage;
            // this common representation does not expose it to a provider yet.
            return LimbRegion(address, limbs, writable)
        }
    }
}

/** Arithmetic providers share storage and exact output/alias contracts. A future
 * native-limb implementation can replace GMP without changing guest arrays. */
internal interface LimbProvider {
    fun add(output: LimbRegion, left: LimbRegion, right: LimbRegion): Long
    fun addWord(output: LimbRegion, input: LimbRegion, word: Long): Long
    fun subtract(output: LimbRegion, left: LimbRegion, right: LimbRegion): Long
    fun compare(left: LimbRegion, right: LimbRegion): Long
    fun multiply(output: LimbRegion, left: LimbRegion, right: LimbRegion): Long
    fun multiplyWord(output: LimbRegion, input: LimbRegion, word: Long): Long
    fun divideWord(output: LimbRegion, fractionalLimbs: Long, input: LimbRegion, divisor: Long): Long
    fun moduloWord(input: LimbRegion, divisor: Long): Long
    fun shiftRight(output: LimbRegion, input: LimbRegion, count: Long, negative: Boolean): Long
    fun toDouble(input: LimbRegion, negative: Boolean, exponent: Long): Double
    fun divide(quotient: LimbRegion, remainder: LimbRegion, fractionalLimbs: Long,
        numerator: LimbRegion, divisor: LimbRegion)
    fun quotient(output: LimbRegion, numerator: LimbRegion, divisor: LimbRegion)
    fun remainder(output: LimbRegion, numerator: LimbRegion, divisor: LimbRegion)
}
