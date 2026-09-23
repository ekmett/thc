package thc.runtime

import com.oracle.truffle.api.CompilerDirectives
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal
import com.oracle.truffle.api.TruffleLanguage
import com.oracle.truffle.api.frame.Frame
import com.oracle.truffle.api.frame.FrameDescriptor
import com.oracle.truffle.api.frame.FrameSlotKind
import com.oracle.truffle.api.frame.FrameSlotTypeException
import com.oracle.truffle.api.frame.VirtualFrame
import com.oracle.truffle.api.nodes.ExplodeLoop
import com.oracle.truffle.api.staticobject.DefaultStaticProperty
import com.oracle.truffle.api.staticobject.StaticShape

/* Adapted closely from Cadenza's jit/frame_layout.kt and frame/capture_layout.kt.
 * See NOTICE.md and LICENSE.txt for the retained upstream notices.
 * Haskell Int#/Word#/Char# use Long instead of Cadenza's Nat Int representation.
 */

/** Allocate indexed slots while compiling a root, then freeze its descriptor. */
internal class FrameLayout private constructor(
    private val builder: FrameDescriptor.Builder,
    private val locals: MutableMap<String, Int>
) {
    constructor() : this(FrameDescriptor.newBuilder(), mutableMapOf()) {
        builder.addSlot(FrameSlotKind.Long, "<TCO Bloom Filter>", null)
        builder.addSlot(FrameSlotKind.Object, "<TCO Result>", null)
        builder.addSlot(FrameSlotKind.Object, "<TCO Function>", null)
        builder.addSlot(FrameSlotKind.Object, "<TCO Arguments>", null)
    }

    /** Lexical children share slot allocation but cannot replace parent bindings. */
    fun scope(): FrameLayout = FrameLayout(builder, locals.toMutableMap())

    /** Each binder owns a fresh slot, including a binder shadowing an existing name. */
    fun bind(name: String): Int = builder.addSlot(FrameSlotKind.Illegal, name, null).also {
        locals[name] = it
    }

    fun slot(name: String): Int = locals.getOrPut(name) {
        builder.addSlot(FrameSlotKind.Illegal, name, null)
    }

    fun build(): FrameDescriptor = builder.build()

    companion object {
        const val BLOOM_FILTER = 0
        const val TAIL_RESULT = 1
        const val TAIL_FUNCTION = 2
        const val TAIL_ARGUMENTS = 3
    }
}

/** Primitive locals widen monotonically when an object representation is needed. */
internal object FrameAccess {
    fun read(frame: Frame, slot: Int): Any? = when {
        // Consult the live frame's tags, not the shared descriptor: another
        // activation may have widened the descriptor while this frame retains
        // its primitive value. These are the only tags FrameAccess.write emits.
        frame.isLong(slot) -> frame.getLong(slot)
        frame.isFloat(slot) -> frame.getFloat(slot)
        frame.isDouble(slot) -> frame.getDouble(slot)
        frame.isBoolean(slot) -> frame.getBoolean(slot)
        frame.isObject(slot) -> frame.getObject(slot)
        else -> fault("Unsupported runtime frame slot tag")
    }

    /** Keep a primitive producer unboxed until this slot actually requires object storage. */
    fun writeLong(frame: Frame, slot: Int, value: Long) {
        val descriptor = frame.frameDescriptor
        val kind = descriptor.getSlotKind(slot)
        if (kind == FrameSlotKind.Long || kind == FrameSlotKind.Illegal) {
            if (kind == FrameSlotKind.Illegal) {
                CompilerDirectives.transferToInterpreterAndInvalidate()
                descriptor.setSlotKind(slot, FrameSlotKind.Long)
            }
            frame.setLong(slot, value)
        } else {
            // Another activation may already have widened the shared descriptor.
            // Follow it even when this frame still has an older primitive tag.
            write(frame, slot, value)
        }
    }

    fun writeFloat(frame: Frame, slot: Int, value: Float) {
        val descriptor = frame.frameDescriptor
        val kind = descriptor.getSlotKind(slot)
        if (kind == FrameSlotKind.Float || kind == FrameSlotKind.Illegal) {
            if (kind == FrameSlotKind.Illegal) {
                CompilerDirectives.transferToInterpreterAndInvalidate()
                descriptor.setSlotKind(slot, FrameSlotKind.Float)
            }
            frame.setFloat(slot, value)
        } else write(frame, slot, value)
    }

    fun writeDouble(frame: Frame, slot: Int, value: Double) {
        val descriptor = frame.frameDescriptor
        val kind = descriptor.getSlotKind(slot)
        if (kind == FrameSlotKind.Double || kind == FrameSlotKind.Illegal) {
            if (kind == FrameSlotKind.Illegal) {
                CompilerDirectives.transferToInterpreterAndInvalidate()
                descriptor.setSlotKind(slot, FrameSlotKind.Double)
            }
            frame.setDouble(slot, value)
        } else write(frame, slot, value)
    }

    fun write(frame: Frame, slot: Int, value: Any?) {
        val descriptor = frame.frameDescriptor
        val kind = descriptor.getSlotKind(slot)
        when {
            value is Float && (kind == FrameSlotKind.Float || kind == FrameSlotKind.Illegal) -> writeFloat(frame, slot, value)
            value is Double && (kind == FrameSlotKind.Double || kind == FrameSlotKind.Illegal) -> writeDouble(frame, slot, value)
            value is Long && (kind == FrameSlotKind.Long || kind == FrameSlotKind.Illegal) -> {
                if (kind == FrameSlotKind.Illegal) {
                    CompilerDirectives.transferToInterpreterAndInvalidate()
                    descriptor.setSlotKind(slot, FrameSlotKind.Long)
                }
                frame.setLong(slot, value)
            }
            value is Boolean && (kind == FrameSlotKind.Boolean || kind == FrameSlotKind.Illegal) -> {
                if (kind == FrameSlotKind.Illegal) {
                    CompilerDirectives.transferToInterpreterAndInvalidate()
                    descriptor.setSlotKind(slot, FrameSlotKind.Boolean)
                }
                frame.setBoolean(slot, value)
            }
            else -> {
                if (kind != FrameSlotKind.Object) {
                    CompilerDirectives.transferToInterpreterAndInvalidate()
                    descriptor.setSlotKind(slot, FrameSlotKind.Object)
                }
                frame.setObject(slot, value)
            }
        }
    }
}

/** Construction-time experiment; checked StaticShape storage remains the default. */
internal const val STATIC_SHAPE_UNCHECKED_PROPERTY = "thc.staticShapeUnchecked"

/** The check is evaluated before Object initialization, so a rejected subclass
 * cannot resurrect an unauthenticated object from a finalizer. No key is stored. */
abstract class ValidatedStorage protected constructor(@Suppress("UNUSED_PARAMETER") checked: Unit)

/** A closure/thunk's selective capture representation, fixed during root compilation. */
class CaptureLayout @JvmOverloads constructor(language: TruffleLanguage<*>, primitiveEligible: BooleanArray,
                                              exactLong: BooleanArray = BooleanArray(primitiveEligible.size),
                                              exactReference: Array<Class<*>?> = arrayOfNulls(primitiveEligible.size),
                                              exactFloat: BooleanArray = BooleanArray(primitiveEligible.size),
                                              exactDouble: BooleanArray = BooleanArray(primitiveEligible.size)) {
    init {
        require(exactLong.size == primitiveEligible.size && exactReference.size == primitiveEligible.size)
        require(exactLong.indices.all { !exactLong[it] || primitiveEligible[it] && exactReference[it] == null })
        require(exactReference.all { it == null || !it.isPrimitive })
        require(exactFloat.size == primitiveEligible.size && exactDouble.size == primitiveEligible.size)
        require(exactLong.indices.all {
            listOf(exactLong[it], exactFloat[it], exactDouble[it], exactReference[it] != null).count { flag -> flag } <= 1
        })
    }
    @CompilationFinal(dimensions = 1)
    private val fields = Array(primitiveEligible.size) {
        // Internally inferred WHNF kinds can refine a legacy adaptive capture.
        // A proven reference needs neither the primitive arm nor its tag.
        CaptureField(it, primitiveEligible[it] && exactReference[it] == null && !exactFloat[it] && !exactDouble[it],
            exactLong[it], exactReference[it], exactFloat[it], exactDouble[it])
    }
    private val allocationKey = Any()
    private val shape = StaticShape.newBuilder(language).also { builder ->
        builder.safetyChecks(!java.lang.Boolean.getBoolean(STATIC_SHAPE_UNCHECKED_PROPERTY))
        fields.forEach { it.register(builder) }
    }.build(CapturedFrame::class.java, CapturedFrameFactory::class.java)

    internal fun checkAllocationKey(key: Any?) {
        if (key !== allocationKey) fault("Invalid capture allocation key")
    }

    @ExplodeLoop
    fun capture(frame: VirtualFrame, sourceSlots: IntArray): CapturedFrame {
        check(sourceSlots.size == fields.size)
        val environment = shape.factory.create(this, allocationKey)
        for (index in fields.indices) {
            if (fields[index].exactLong) fields[index].initializeLong(environment, frame.getLong(sourceSlots[index]))
            else if (fields[index].exactFloat) fields[index].initializeFloat(environment, frame.getFloat(sourceSlots[index]))
            else if (fields[index].exactDouble) fields[index].initializeDouble(environment, frame.getDouble(sourceSlots[index]))
            else fields[index].initialize(environment, FrameAccess.read(frame, sourceSlots[index]))
        }
        return environment
    }

    /** Bytecode stack operands use the same selective immutable capture shape. */
    @ExplodeLoop
    fun captureValues(values: Array<Any?>): CapturedFrame {
        check(values.size == fields.size)
        val environment = shape.factory.create(this, allocationKey)
        for (index in fields.indices) fields[index].initialize(environment, values[index])
        return environment
    }

    private fun checkedField(environment: CapturedFrame, index: Int): CaptureField {
        if (environment.layout !== this) fault("Captured frame does not match layout")
        if (index < 0 || index >= fields.size) fault("Invalid capture field index")
        return fields[index]
    }

    /** The call site's constant layout lets Graal fold StaticProperty offsets. */
    fun read(environment: CapturedFrame, index: Int): Any? = checkedField(environment, index).read(environment)

    /** Primitive reads and writes stay together so temporary boxes can disappear. */
    fun restore(environment: CapturedFrame, index: Int, frame: Frame, slot: Int) {
        checkedField(environment, index).restore(environment, frame, slot)
    }

    fun isLong(environment: CapturedFrame, index: Int): Boolean = checkedField(environment, index).isLong(environment)
    fun isObject(environment: CapturedFrame, index: Int): Boolean = checkedField(environment, index).isObject(environment)
    fun readLong(environment: CapturedFrame, index: Int): Long = checkedField(environment, index).readLong(environment)
    fun isFloat(environment: CapturedFrame, index: Int): Boolean = checkedField(environment, index).exactFloat
    fun isDouble(environment: CapturedFrame, index: Int): Boolean = checkedField(environment, index).exactDouble
    fun readFloat(environment: CapturedFrame, index: Int): Float = checkedField(environment, index).readFloat(environment)
    fun readDouble(environment: CapturedFrame, index: Int): Double = checkedField(environment, index).readDouble(environment)
    fun readObject(environment: CapturedFrame, index: Int): Any? = checkedField(environment, index).readObject(environment)

    private class CaptureField(private val index: Int, private val primitiveEligible: Boolean, val exactLong: Boolean,
                               private val exactReference: Class<*>?, val exactFloat: Boolean, val exactDouble: Boolean) {
        private val objectValue = DefaultStaticProperty("capture_${index}_object")
        private val primitiveValue = DefaultStaticProperty("capture_${index}_primitive")
        private val hasPrimitive = DefaultStaticProperty("capture_${index}_tag")

        fun register(builder: StaticShape.Builder) {
            if (exactFloat) builder.property(primitiveValue, Float::class.javaPrimitiveType, true)
            else if (exactDouble) builder.property(primitiveValue, Double::class.javaPrimitiveType, true)
            else if (!exactLong) builder.property(objectValue, exactReference ?: Any::class.java, true)
            if (primitiveEligible) {
                builder.property(primitiveValue, Long::class.javaPrimitiveType, true)
                if (!exactLong) builder.property(hasPrimitive, Boolean::class.javaPrimitiveType, true)
            }
        }

        // Final properties are initialized once before escape. Retaining the object
        // arm also supports recursive indirections and values outside the Long subset.
        fun initializeLong(storage: CapturedFrame, value: Long) { primitiveValue.setLong(storage, value) }
        fun initializeFloat(storage: CapturedFrame, value: Float) { primitiveValue.setFloat(storage, value) }
        fun initializeDouble(storage: CapturedFrame, value: Double) { primitiveValue.setDouble(storage, value) }

        fun initialize(storage: CapturedFrame, value: Any?) {
            if (exactFloat) initializeFloat(storage, value as? Float ?: fault("Expected primitive Float capture"))
            else if (exactDouble) initializeDouble(storage, value as? Double ?: fault("Expected primitive Double capture"))
            else if (exactLong) {
                initializeLong(storage, value as? Long ?: fault("Expected primitive Long capture"))
            } else if (primitiveEligible && value is Long) {
                primitiveValue.setLong(storage, value)
                hasPrimitive.setBoolean(storage, true)
            } else {
                objectValue.setObject(storage, value)
                if (primitiveEligible) hasPrimitive.setBoolean(storage, false)
            }
        }

        fun isLong(storage: CapturedFrame): Boolean = exactLong || primitiveEligible && hasPrimitive.getBoolean(storage)
        fun isObject(storage: CapturedFrame): Boolean = !exactLong && !exactFloat && !exactDouble && (!primitiveEligible || !hasPrimitive.getBoolean(storage))
        fun kind(storage: CapturedFrame): FrameSlotKind =
            if (exactFloat) FrameSlotKind.Float else if (exactDouble) FrameSlotKind.Double
            else if (isObject(storage)) FrameSlotKind.Object else FrameSlotKind.Long

        fun read(storage: CapturedFrame): Any? =
            if (exactFloat) primitiveValue.getFloat(storage) else if (exactDouble) primitiveValue.getDouble(storage)
            else if (isObject(storage)) objectValue.getObject(storage) else primitiveValue.getLong(storage)

        fun restore(storage: CapturedFrame, frame: Frame, slot: Int) {
            if (exactFloat) FrameAccess.writeFloat(frame, slot, primitiveValue.getFloat(storage))
            else if (exactDouble) FrameAccess.writeDouble(frame, slot, primitiveValue.getDouble(storage))
            else if (isObject(storage)) {
                FrameAccess.write(frame, slot, objectValue.getObject(storage))
            } else {
                FrameAccess.writeLong(frame, slot, primitiveValue.getLong(storage))
            }
        }

        fun readLong(storage: CapturedFrame): Long {
            if (!isLong(storage)) throw FrameSlotTypeException.create(index, FrameSlotKind.Long, kind(storage))
            return primitiveValue.getLong(storage)
        }

        fun readFloat(storage: CapturedFrame): Float {
            if (!exactFloat) throw FrameSlotTypeException.create(index, FrameSlotKind.Float, kind(storage))
            return primitiveValue.getFloat(storage)
        }

        fun readDouble(storage: CapturedFrame): Double {
            if (!exactDouble) throw FrameSlotTypeException.create(index, FrameSlotKind.Double, kind(storage))
            return primitiveValue.getDouble(storage)
        }

        fun readObject(storage: CapturedFrame): Any? {
            if (!isObject(storage)) throw FrameSlotTypeException.create(index, FrameSlotKind.Object, kind(storage))
            return objectValue.getObject(storage)
        }
    }
}

/** Public superclass and constructor for Truffle's generated StaticShape subclasses. */
open class CapturedFrame(val layout: CaptureLayout, allocationKey: Any?) :
    ValidatedStorage(layout.checkAllocationKey(allocationKey)) {
    fun getValue(index: Int): Any? = layout.read(this, index)
    fun getLong(index: Int): Long = layout.readLong(this, index)
    fun isLong(index: Int): Boolean = layout.isLong(this, index)
    fun getObject(index: Int): Any? = layout.readObject(this, index)
    fun isObject(index: Int): Boolean = layout.isObject(this, index)
}

/** Signature matches the CapturedFrame superclass constructor exactly. */
interface CapturedFrameFactory {
    fun create(layout: CaptureLayout, allocationKey: Any?): CapturedFrame
}
