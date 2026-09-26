// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal
import com.oracle.truffle.api.bytecode.BytecodeNode
import com.oracle.truffle.api.bytecode.LocalAccessor
import com.oracle.truffle.api.frame.VirtualFrame
import com.oracle.truffle.api.nodes.ExplodeLoop
import jdk.incubator.vector.ByteVector
import jdk.incubator.vector.ShortVector
import jdk.incubator.vector.IntVector
import jdk.incubator.vector.LongVector
import jdk.incubator.vector.FloatVector
import jdk.incubator.vector.DoubleVector
import jdk.incubator.vector.Vector
import jdk.incubator.vector.VectorShape
import jdk.incubator.vector.VectorSpecies

/** One exact VecRep occupies one raw Vector API reference in activation transport. */
internal class VectorLayout(val proof: CoreRepresentation) {
    init { validate(proof) }
    val vector: CoreVector = proof.vector!!
    val lanes: Int = vector.lanes
    val width: Int = 1
    val lane: CoreRepresentation = laneProof(vector)
    private val elementBits = when (vector.element) {
        "Int8ElemRep", "Word8ElemRep" -> 8
        "Int16ElemRep", "Word16ElemRep" -> 16
        "Int32ElemRep", "Word32ElemRep", "FloatElemRep" -> 32
        else -> 64
    }
    private val shape = VectorShape.forBitSize(lanes * elementBits)
    val carrierType: Class<*> = when (vector.element) {
        "Int8ElemRep", "Word8ElemRep" -> ByteVector::class.java
        "Int16ElemRep", "Word16ElemRep" -> ShortVector::class.java
        "Int32ElemRep", "Word32ElemRep" -> IntVector::class.java
        "Int64ElemRep", "Word64ElemRep" -> LongVector::class.java
        "FloatElemRep" -> FloatVector::class.java
        "DoubleElemRep" -> DoubleVector::class.java
        else -> fault("Unknown vector lane representation")
    }
    val species: VectorSpecies<*> = when (vector.element) {
        "Int8ElemRep", "Word8ElemRep" -> ByteVector.SPECIES_128.withShape(shape)
        "Int16ElemRep", "Word16ElemRep" -> ShortVector.SPECIES_128.withShape(shape)
        "Int32ElemRep", "Word32ElemRep" -> IntVector.SPECIES_128.withShape(shape)
        "Int64ElemRep", "Word64ElemRep" -> LongVector.SPECIES_128.withShape(shape)
        "FloatElemRep" -> FloatVector.SPECIES_128.withShape(shape)
        "DoubleElemRep" -> DoubleVector.SPECIES_128.withShape(shape)
        else -> fault("Unknown vector lane representation")
    }
    fun require(value: Any?): Vector<*> {
        val raw = value as? Vector<*> ?: fault("Expected raw vector carrier")
        if (raw.species() != species) fault("Vector carrier species disagrees with VecRep")
        return raw
    }
    fun write(frame: VirtualFrame, slots: IntArray, offset: Int, value: Any?) {
        FrameAccess.writeObject(frame, slots[offset], require(value))
    }
    fun read(frame: VirtualFrame, slots: IntArray, offset: Int): Any = require(frame.getObject(slots[offset]))
    fun write(bytecode: BytecodeNode, frame: VirtualFrame, slots: Array<LocalAccessor>, offset: Int, value: Any?) =
        slots[offset].setObject(bytecode, frame, require(value))
    fun read(bytecode: BytecodeNode, frame: VirtualFrame, slots: Array<LocalAccessor>, offset: Int): Any =
        require(slots[offset].getObject(bytecode, frame))
    fun copy(frame: VirtualFrame, from: IntArray, sourceOffset: Int,
        into: IntArray, targetOffset: Int) {
        write(frame, into, targetOffset, read(frame, from, sourceOffset))
    }

    companion object {
        /** Decode only the physical tags emitted by storageReps, during layout construction. */
        fun fromStorageRep(rep: String): VectorLayout {
            val parts = rep.split(' ')
            kotlin.require(parts.size == 3 && parts[0] == "Vector")
            val element = when (parts[1]) {
                "byte" -> "Int8ElemRep"; "short" -> "Int16ElemRep"
                "int" -> "Int32ElemRep"; "long" -> "Int64ElemRep"
                "float" -> "FloatElemRep"; "double" -> "DoubleElemRep"
                else -> fault("Invalid physical vector lane")
            }
            val count = parts[2].toInt()
            return VectorLayout(CoreRepresentation(CoreKind.VECTOR, evaluated = true, present = true,
                primReps = listOf("VecRep $count $element"), vector = CoreVector(count, element)))
        }
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
        /** Physical vector kinds share signed/unsigned lane classes, but retain species. */
        fun storageReps(proof: CoreRepresentation): List<String> = when {
            proof.components != null -> proof.components.flatMap(::storageReps)
            proof.kind == CoreKind.VOID -> emptyList()
            proof.isVector -> {
                validate(proof)
                val vector = proof.vector!!
                val physical = when (vector.element) {
                    "Int8ElemRep", "Word8ElemRep" -> "byte"
                    "Int16ElemRep", "Word16ElemRep" -> "short"
                    "Int32ElemRep", "Word32ElemRep" -> "int"
                    "Int64ElemRep", "Word64ElemRep" -> "long"
                    "FloatElemRep" -> "float"
                    "DoubleElemRep" -> "double"
                    else -> fault("Unknown vector lane representation")
                }
                listOf("Vector $physical ${vector.lanes}")
            }
            else -> listOf(proof.primReps?.singleOrNull() ?: fault("Unresolved typed storage leaf"))
        }
    }
}

/** Locals retain the immutable raw vector; reads and parallel moves never extract lanes. */
internal class VectorLocalRead(shape: TupleShape,
    @field:CompilationFinal(dimensions = 1) private val sources: IntArray) : Expr() {
    private val vector = VectorLayout(shape.proof)
    init { require(sources.size == vector.width); representation = shape.proof.copy(evaluated = true) }
    override fun execute(frame: VirtualFrame): Any = vector.read(frame, sources, 0)
    @ExplodeLoop override fun executeTuple(frame: VirtualFrame, slots: IntArray, offset: Int): Any? {
        vector.copy(frame, sources, 0, slots, offset)
        return null
    }
}
