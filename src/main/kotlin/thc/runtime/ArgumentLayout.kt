// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal

internal val EMPTY_TUPLE_SLOTS = intArrayOf()

/** Logical tuple identity is independent of flattened physical argument positions.
 * A null layout preserves the scalar-only convention, with identity offsets. */
internal class ArgumentLayout private constructor(
    @field:CompilationFinal(dimensions = 1) private val proofs: Array<CoreRepresentation>,
    @field:CompilationFinal(dimensions = 1) private val offsets: IntArray,
    @field:CompilationFinal(dimensions = 1) val physicalProofs: Array<CoreRepresentation>
) {
    val logicalArity: Int get() = proofs.size
    val physicalArity: Int get() = offsets.last()
    val requiresTyped: Boolean = proofs.any { it.isTuple && !it.isEmptyTuple }
    // Build recursive metadata once during lowering. PAP/overapplication indices
    // can be dynamic even at an otherwise scalar/empty call site; rebuilding a
    // recursive signature there makes partial evaluation expand unknown shapes.
    @field:CompilationFinal(dimensions = 1)
    private val tupleKeys: Array<String?> = Array(proofs.size) { index ->
        proofs[index].takeIf { it.isTuple }?.let(TupleShape::compatibilityKey)
    }
    fun isEmpty(index: Int): Boolean = proofs[index].isEmptyTuple
    fun isTuple(index: Int): Boolean = proofs[index].isTuple
    fun proof(index: Int): CoreRepresentation = proofs[index]
    fun offset(index: Int): Int = offsets[index]
    fun suffix(index: Int): ArgumentLayout? = fromProofs(proofs.drop(index))

    companion object {
        fun fromProofs(proofs: List<CoreRepresentation>): ArgumentLayout? {
            if (proofs.none { it.isTuple }) return null
            proofs.forEach(CoreRepresentations::requireInput)
            val offsets = IntArray(proofs.size + 1)
            val physical = ArrayList<CoreRepresentation>()
            for (i in proofs.indices) {
                physical.addAll(leaves(proofs[i]))
                offsets[i + 1] = physical.size
            }
            return ArgumentLayout(proofs.toTypedArray(), offsets, physical.toTypedArray())
        }
        // State# is erased inside a tuple, but an ordinary scalar State# formal
        // retains its existing Unit argument. Zero physical width never identifies
        // a logical argument: (# #), (# State# s #), and nested empty tuples differ.
        fun leaves(proof: CoreRepresentation): List<CoreRepresentation> =
            if (proof.isTuple) TupleShape.flatten(proof) else listOf(proof)
        fun offset(layout: ArgumentLayout?, index: Int): Int = layout?.offset(index) ?: index
        fun width(layout: ArgumentLayout?, count: Int): Int = offset(layout, count)

        /** Exact logical shapes are mandatory even when their physical widths agree. */
        fun validate(function: Closure, supplied: ArgumentLayout?, offset: Int, count: Int) {
            validate((function.target.rootNode as? GuestRoot)?.inputLayout, function.suppliedCount, supplied, offset, count)
        }
        fun validate(formal: ArgumentLayout?, prefixCount: Int, supplied: ArgumentLayout?, offset: Int, count: Int) {
            if (formal == null && supplied == null) return
            for (i in 0 until count) {
                val expected = formal?.tupleKeys?.get(prefixCount + i)
                val actual = supplied?.tupleKeys?.get(offset + i)
                if (expected !== actual) fault("Conflicting logical tuple argument representation")
            }
        }
    }
}
