// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
import com.oracle.truffle.api.frame.VirtualFrame
import jdk.incubator.vector.Vector
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import thc.Language

/** THC's detached word image, not the GHC or JVM heap ABI. Pointer words are zero;
 * their actual, unforced references are returned separately in payload order.
 * Word zero is a kind tag. Primitive fields keep their raw native-endian bits;
 * float fields occupy one word and vector fields their actual species width.
 */
internal class ClosureImage(val descriptor: String, val bytes: ByteArray, val pointers: Array<Any?>)

/** Info identities belong to a context, never to a global cache of guest values. */
internal class ClosureInfoTables {
    private val tables = ConcurrentHashMap<String, ManagedAddress>()
    @TruffleBoundary fun address(descriptor: String): ManagedAddress = tables.computeIfAbsent(descriptor) {
        ManagedAddress.fromAllocation(ManagedAllocation.immutable(("THC closure v1: $it\u0000").toByteArray(Charsets.UTF_8), 8))
    }
}

internal object ClosureInspection {
    @JvmStatic @TruffleBoundary
    fun image(value: Any?): ClosureImage {
        val payload = ArrayList<Any?>()
        val references = ArrayList<Boolean>()
        fun field(value: Any?, reference: Boolean) { payload += value; references += reference }
        fun captures(environment: CapturedFrame?) {
            if (environment != null) for (index in 0 until environment.layout.storageSize)
                field(environment.layout.inspect(environment, index), environment.layout.isObject(environment, index))
        }
        val descriptor: String
        val tag: Long
        when (value) {
            is DataValue -> {
                descriptor = "constructor ${value.layout.id}"
                tag = 1
                for (index in 0 until value.layout.arity)
                    if (value.layout.fieldWidth(index) != 0) field(value.layout.inspect(value, index),
                        !value.layout.isLong(index) && !value.layout.isFloat(index) &&
                            !value.layout.isDouble(index) && !value.layout.isVector(index))
            }
            is Closure -> {
                descriptor = "function ${value.target.rootNode.name} arity ${value.arity} supplied ${value.suppliedCount}"
                tag = 2
                captures(value.environment)
                val typed = value.typedSupplied
                if (typed == null) for (index in 0 until value.suppliedCount) field(value.supplied[index], true)
                else for (index in typed.layout.reps.indices) field(when {
                    typed.layout.isLong(index) -> typed.layout.getLong(typed, index)
                    typed.layout.isFloat(index) -> typed.layout.getFloat(typed, index)
                    typed.layout.isDouble(index) -> typed.layout.getDouble(typed, index)
                    else -> typed.layout.getObject(typed, index)
                }, typed.layout.reps[index] == "reference")
            }
            is Thunk -> synchronized(value.monitor) {
                descriptor = "thunk state ${value.state}"
                tag = 3
                // Never wait for, enter, or rethrow a thunk. A completed indirection
                // points at its value; executable runtime/continuation metadata is
                // not misreported as a guest pointer field.
                if (value.state == 2) field(value.value, true) else captures(value.environment)
            }
            is Long -> { descriptor = "boxed Int64"; tag = 4; field(value, false) }
            is Float -> { descriptor = "boxed Float32"; tag = 5; field(value, false) }
            is Double -> { descriptor = "boxed Float64"; tag = 6; field(value, false) }
            else -> { descriptor = "opaque ${value?.javaClass?.name ?: "null"}"; tag = 0 }
        }
        val size = 8 + payload.indices.sumOf { if (!references[it] && payload[it] is Vector<*>)
            (payload[it] as Vector<*>).bitSize() / 8 else 8 }
        val bytes = ByteArray(size)
        val words = ByteBuffer.wrap(bytes).order(ByteOrder.nativeOrder())
        val pointers = ArrayList<Any?>()
        words.putLong(tag)
        for (index in payload.indices) {
            val field = payload[index]
            if (references[index]) { words.putLong(0); pointers += field; continue }
            when (field) {
            is Long -> words.putLong(field)
            is Float -> { words.putInt(field.toRawBits()); words.putInt(0) }
            is Double -> words.putLong(field.toRawBits())
            is Vector<*> -> {
                val raw = field.reinterpretAsBytes().toArray()
                words.put(raw)
            }
            else -> fault("Invalid primitive closure field")
            }
        }
        return ClosureImage(descriptor, bytes, pointers.toTypedArray())
    }

    @JvmStatic fun size(value: Any?): Long = image(value).bytes.size.toLong() / 8
}

internal enum class ClosureInspectOp(val primitive: String, val arity: Int) {
    UNPACK("unpackClosure#", 1), SIZE("closureSize#", 1), AP_STACK("getApStackVal#", 2),
    CCS("getCCSOf#", 2), WHERE("whereFrom#", 3);

    fun validate(arguments: List<CoreRepresentation>, flags: List<*>, result: CoreRepresentation) {
        fun role(proof: CoreRepresentation, kind: CoreKind) = !proof.isAggregate && !proof.isVector && proof.kind == kind
        fun reference(proof: CoreRepresentation) = !proof.isAggregate && !proof.isVector &&
            proof.kind in setOf(CoreKind.OBJECT, CoreKind.DATA, CoreKind.CLOSURE)
        if (arguments.size != arity || flags != List(arity) { it == 0 } || !reference(arguments[0]) ||
            (this == AP_STACK && !role(arguments[1], CoreKind.LONG)) ||
            (this == WHERE && !role(arguments[1], CoreKind.ADDRESS)) ||
            (this in setOf(CCS, WHERE) && !role(arguments.last(), CoreKind.VOID)))
            fault("$primitive: invalid closure inspection operands")
        val fields = result.components.orEmpty()
        val valid = when (this) {
            SIZE -> role(result, CoreKind.LONG)
            UNPACK -> result.isTuple && fields.size == 3 && role(fields[0], CoreKind.ADDRESS) &&
                fields.drop(1).all { role(it, CoreKind.OBJECT) }
            AP_STACK -> result.isTuple && fields.size == 2 && role(fields[0], CoreKind.LONG) && reference(fields[1])
            CCS, WHERE -> result.isTuple && fields.size == 2 && role(fields[0], CoreKind.VOID) &&
                role(fields[1], if (this == CCS) CoreKind.ADDRESS else CoreKind.LONG)
        }
        if (!valid) fault("$primitive: invalid closure inspection result")
    }

    companion object { fun named(name: String): ClosureInspectOp? = entries.firstOrNull { it.primitive == name } }
}

internal object CoreProfileAction {
    fun validate(arguments: List<CoreRepresentation>, flags: List<*>, result: CoreRepresentation) {
        val action = arguments.firstOrNull()
        if (arguments.size != 2 || flags != listOf(true, false) || action == null ||
            action.isAggregate || action.isVector || action.kind !in setOf(CoreKind.OBJECT, CoreKind.CLOSURE) ||
            arguments[1].kind != CoreKind.VOID || arguments[1].isAggregate ||
            !result.isTuple || result.components?.firstOrNull()?.kind != CoreKind.VOID)
            fault("clearCCS#: expected State action and State-result tuple")
        TupleShape.validate(result)
    }
}

internal class InspectionState(@field:Child private var state: Expr) : Expr() {
    init { representation = state.representation.copy(evaluated = true) }
    override fun execute(frame: VirtualFrame): Any {
        requireVoidCarrier(state.execute(frame))
        return Unit
    }
}

internal class ClosureInspectExpression(private val operation: ClosureInspectOp,
    @field:Children private val operands: Array<Expr>, proof: CoreRepresentation) : Expr() {
    init { representation = proof.copy(evaluated = true) }
    override fun execute(frame: VirtualFrame): Any = executeLong(frame)
    override fun executeLong(frame: VirtualFrame): Long {
        if (operation != ClosureInspectOp.SIZE) fault("${operation.primitive} requires a tuple destination")
        return ClosureInspection.size(operands[0].execute(frame))
    }
    override fun executeTuple(frame: VirtualFrame, slots: IntArray, offset: Int): Any? {
        // Kotlin's enum switch table is mutable. Keep the operation constant
        // during PE, so unrelated child arities never enter the guest graph.
        if (operation == ClosureInspectOp.UNPACK) {
            val image = ClosureInspection.image(operands[0].execute(frame))
            FrameAccess.writeObject(frame, slots[offset], Language.currentState(this).closureInfo.address(image.descriptor))
            FrameAccess.writeObject(frame, slots[offset + 1], image.bytes)
            FrameAccess.writeObject(frame, slots[offset + 2], image.pointers)
        } else if (operation == ClosureInspectOp.AP_STACK) {
            val value = operands[0].execute(frame)
            operands[1].executeRequiredLong(frame)
            // THC exposes no GHC AP_STACK carrier. Its ordinary saved guest
            // continuations must not be reinterpreted as raw stack payloads.
            FrameAccess.writeLong(frame, slots[offset], 0)
            FrameAccess.writeObject(frame, slots[offset + 1], value)
        } else if (operation == ClosureInspectOp.CCS) {
            requireVoidCarrier(operands[1].execute(frame))
            FrameAccess.writeObject(frame, slots[offset], ManagedAddress.nullAddress())
        } else if (operation == ClosureInspectOp.WHERE) {
            operands[1].executeRequiredAddress(frame)
            requireVoidCarrier(operands[2].execute(frame))
            // No closure IPE tables are registered for this JVM target. As
            // in lookupIPE's absence path, leave the destination untouched.
            FrameAccess.writeLong(frame, slots[offset], 0)
        } else fault("closureSize# is scalar")
        return null
    }
}
