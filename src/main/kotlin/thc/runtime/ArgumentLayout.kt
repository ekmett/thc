package thc.runtime

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal

internal val EMPTY_TUPLE_SLOTS = intArrayOf()

/** Immutable logical positions, not guest payload. Only exact (# #) has no input field.
 * A null layout is the original scalar-only convention, with identity offsets. */
internal class ArgumentLayout private constructor(
    @field:CompilationFinal(dimensions = 1) private val empty: BooleanArray,
    @field:CompilationFinal(dimensions = 1) private val offsets: IntArray
) {
    val logicalArity: Int get() = empty.size
    val physicalArity: Int get() = offsets.last()
    fun isEmpty(index: Int): Boolean = empty[index]
    fun offset(index: Int): Int = offsets[index]
    fun suffix(index: Int): ArgumentLayout? = fromEmpty(empty.copyOfRange(index, empty.size))

    companion object {
        fun fromProofs(proofs: List<CoreRepresentation>): ArgumentLayout? =
            fromEmpty(BooleanArray(proofs.size) { proofs[it].isEmptyTuple })
        private fun fromEmpty(empty: BooleanArray): ArgumentLayout? {
            if (empty.none { it }) return null
            val offsets = IntArray(empty.size + 1)
            for (i in empty.indices) offsets[i + 1] = offsets[i] + if (empty[i]) 0 else 1
            return ArgumentLayout(empty, offsets)
        }
        fun offset(layout: ArgumentLayout?, index: Int): Int = layout?.offset(index) ?: index
        fun width(layout: ArgumentLayout?, count: Int): Int = offset(layout, count)

        /** Empty proofs cannot be supplied to scalar formals (including State#), or vice versa. */
        fun validate(function: Closure, supplied: ArgumentLayout?, offset: Int, count: Int) {
            val formal = (function.target.rootNode as? GuestRoot)?.inputLayout
            if (formal == null && supplied == null) return
            for (i in 0 until count) {
                val expected = formal?.isEmpty(function.suppliedCount + i) == true
                val actual = supplied?.isEmpty(offset + i) == true
                if (expected != actual) fault("Conflicting empty tuple argument representation")
            }
        }
    }
}
