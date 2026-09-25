// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal
import com.oracle.truffle.api.bytecode.BytecodeNode
import com.oracle.truffle.api.bytecode.LocalAccessor
import com.oracle.truffle.api.frame.VirtualFrame
import com.oracle.truffle.api.nodes.ExplodeLoop

/** One exact VecRep, with primitive lanes only at storage boundaries. */
internal class VectorLayout(val proof: CoreRepresentation) {
    init { validate(proof) }
    val vector: CoreVector = proof.vector!!
    val lanes: Int = vector.lanes
    val lane: CoreRepresentation = laneProof(vector)
    fun write(frame: VirtualFrame, slots: IntArray, offset: Int, value: Any?) =
        GeneratedVectorTransport.write(vector, frame, slots, offset, value)
    fun read(frame: VirtualFrame, slots: IntArray, offset: Int): Any =
        GeneratedVectorTransport.read(vector, frame, slots, offset)
    fun write(bytecode: BytecodeNode, frame: VirtualFrame, slots: Array<LocalAccessor>, offset: Int, value: Any?) =
        GeneratedVectorTransport.write(vector, bytecode, frame, slots, offset, value)
    fun read(bytecode: BytecodeNode, frame: VirtualFrame, slots: Array<LocalAccessor>, offset: Int): Any =
        GeneratedVectorTransport.read(vector, bytecode, frame, slots, offset)
    @ExplodeLoop fun copy(frame: VirtualFrame, from: IntArray, sourceOffset: Int,
        into: IntArray, targetOffset: Int) {
        for (index in 0 until lanes) when {
            lane.isFloat -> FrameAccess.writeFloat(frame, into[targetOffset + index], frame.getFloat(from[sourceOffset + index]))
            lane.isDouble -> FrameAccess.writeDouble(frame, into[targetOffset + index], frame.getDouble(from[sourceOffset + index]))
            else -> FrameAccess.writeLong(frame, into[targetOffset + index], frame.getLong(from[sourceOffset + index]))
        }
    }

    companion object {
        fun validate(proof: CoreRepresentation) {
            val vector = proof.vector ?: fault("Vector transport requires an exact VecRep")
            if (!proof.present || proof.kind != CoreKind.VECTOR || proof.isAggregate ||
                proof.primReps != listOf("VecRep ${vector.lanes} ${vector.element}"))
                fault("Vector transport requires an exact VecRep")
            CoreVector.parse(mapOf("lanes" to vector.lanes, "element" to vector.element),
                proof.kind, proof.primReps, false)
        }
        private fun laneRep(vector: CoreVector): String = when (vector.element) {
            "Int8ElemRep" -> "Int8Rep"; "Word8ElemRep" -> "Word8Rep"
            "Int16ElemRep" -> "Int16Rep"; "Word16ElemRep" -> "Word16Rep"
            "Int32ElemRep" -> "Int32Rep"; "Word32ElemRep" -> "Word32Rep"
            "Int64ElemRep" -> "Int64Rep"; "Word64ElemRep" -> "Word64Rep"
            "FloatElemRep" -> "FloatRep"; "DoubleElemRep" -> "DoubleRep"
            else -> fault("Unknown vector lane representation")
        }
        fun laneProof(vector: CoreVector): CoreRepresentation {
            val rep = laneRep(vector)
            return CoreRepresentation(when (rep) {
                "FloatRep" -> CoreKind.FLOAT; "DoubleRep" -> CoreKind.DOUBLE; else -> CoreKind.LONG
            }, evaluated = true, present = true, primReps = listOf(rep))
        }
        /** Private physical tags distinguish dense vector lanes from scalar Long slots. */
        fun storageReps(proof: CoreRepresentation): List<String> = when {
            proof.components != null -> proof.components.flatMap(::storageReps)
            proof.kind == CoreKind.VOID -> emptyList()
            proof.isVector -> {
                validate(proof)
                val rep = laneRep(proof.vector!!)
                val physical = if (rep in setOf("Int8Rep", "Word8Rep", "Int16Rep", "Word16Rep", "Int32Rep", "Word32Rep"))
                    "VectorLane $rep" else rep
                List(proof.vector.lanes) { physical }
            }
            else -> listOf(proof.primReps?.singleOrNull() ?: fault("Unresolved typed storage leaf"))
        }
    }
}

/** Locals own primitive lanes; only an actual local vector operation reconstructs its carrier. */
internal class VectorLocalRead(shape: TupleShape,
    @field:CompilationFinal(dimensions = 1) private val sources: IntArray) : Expr() {
    private val vector = VectorLayout(shape.proof)
    init { require(sources.size == vector.lanes); representation = shape.proof.copy(evaluated = true) }
    override fun execute(frame: VirtualFrame): Any = vector.read(frame, sources, 0)
    @ExplodeLoop override fun executeTuple(frame: VirtualFrame, slots: IntArray, offset: Int): Any? {
        vector.copy(frame, sources, 0, slots, offset)
        return null
    }
}
