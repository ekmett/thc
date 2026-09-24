package thc.runtime

import com.oracle.truffle.api.frame.VirtualFrame

/** The read form is constructed only by the validated immediate-case lowering.
 * It publishes a dense local vector, never a State/vector tuple value. */
internal class VectorByteArrayExpression(private val operation: VectorByteArrayOp,
    @field:Children private var arguments: Array<Expr>) : Expr() {
    init { representation = if (operation.isWrite) CoreVectorMemory.stateProof else operation.vectorProof }
    override fun execute(frame: VirtualFrame): Any {
        val bytes = ManagedByteArray.require(arguments[0].execute(frame))
        val index = arguments[1].executeRequiredLong(frame)
        if (operation.isWrite) {
            // A subject enum when creates a mutable switch-map array load that
            // prevents partial evaluation from selecting this node's family.
            when {
                operation.family === VectorMemoryFamily.INT32 -> {
                    val value = arguments[2].execute(frame) as? Int32X4 ?: fault("Expected Int32X4#")
                    ManagedByteArray.requireState(arguments[3].execute(frame))
                    Int32X4.writeArray(bytes, index, value, operation.scalarOffset)
                }
                operation.family === VectorMemoryFamily.WORD32 -> {
                    val value = arguments[2].execute(frame) as? Word32X4 ?: fault("Expected Word32X4#")
                    ManagedByteArray.requireState(arguments[3].execute(frame))
                    Word32X4.writeArray(bytes, index, value, operation.scalarOffset)
                }
                operation.family === VectorMemoryFamily.FLOAT32 -> {
                    val value = arguments[2].execute(frame) as? FloatX4 ?: fault("Expected FloatX4#")
                    ManagedByteArray.requireState(arguments[3].execute(frame))
                    FloatX4.writeArray(bytes, index, value, operation.scalarOffset)
                }
                operation.family === VectorMemoryFamily.DOUBLE64 -> {
                    val value = arguments[2].execute(frame) as? DoubleX2 ?: fault("Expected DoubleX2#")
                    ManagedByteArray.requireState(arguments[3].execute(frame))
                    DoubleX2.writeArray(bytes, index, value, operation.scalarOffset)
                }
                else -> fault("Unsupported local vector memory family")
            }
            return Unit
        }
        if (operation.isRead) ManagedByteArray.requireState(arguments[2].execute(frame))
        return when {
            operation.family === VectorMemoryFamily.INT32 -> Int32X4.readArray(bytes, index, operation.scalarOffset)
            operation.family === VectorMemoryFamily.WORD32 -> Word32X4.readArray(bytes, index, operation.scalarOffset)
            operation.family === VectorMemoryFamily.FLOAT32 -> FloatX4.readArray(bytes, index, operation.scalarOffset)
            operation.family === VectorMemoryFamily.DOUBLE64 -> DoubleX2.readArray(bytes, index, operation.scalarOffset)
            else -> fault("Unsupported local vector memory family")
        }
    }
}
