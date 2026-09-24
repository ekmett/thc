package thc.runtime

import com.oracle.truffle.api.frame.VirtualFrame

/** The read form is constructed only by the validated immediate-case lowering.
 * It publishes a dense local vector, never a State/vector tuple value. */
internal class VectorByteArrayExpression(private val operation: VectorByteArrayOp,
    @field:Children private var arguments: Array<Expr>) : Expr() {
    init { representation = if (operation.isWrite) CoreVectorMemory.stateProof else CoreVectors.proof32 }
    override fun execute(frame: VirtualFrame): Any {
        val bytes = ManagedByteArray.require(arguments[0].execute(frame))
        val index = arguments[1].executeRequiredLong(frame)
        if (operation.isWrite) {
            val value = arguments[2].execute(frame) as? Int32X4 ?: fault("Expected Int32X4#")
            ManagedByteArray.requireState(arguments[3].execute(frame))
            Int32X4.writeArray(bytes, index, value, operation.scalarOffset)
            return Unit
        }
        if (operation.isRead) ManagedByteArray.requireState(arguments[2].execute(frame))
        return Int32X4.readArray(bytes, index, operation.scalarOffset)
    }
}
