package thc.runtime

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
import com.oracle.truffle.api.TruffleLanguage
import com.oracle.truffle.api.frame.Frame
import com.oracle.truffle.api.nodes.ExplodeLoop
import com.oracle.truffle.api.staticobject.DefaultStaticProperty
import com.oracle.truffle.api.staticobject.StaticShape

/* Fixed immutable field representations follow Cadenza's frame/frame_assembly.kt;
 * StaticShape construction follows frame/capture_layout.kt. See NOTICE.md and
 * LICENSE.txt. Constructor fields have known GHC representations, so unlike a
 * recursive closure capture they need neither an object fallback nor a tag.
 */

/** One immutable, constructor-specific layout shared by all values of that constructor. */
class DataLayout(
    language: TruffleLanguage<*>,
    val id: String,
    val name: String,
    fieldReps: Array<String>
) {
    @CompilationFinal(dimensions = 1)
    private val fields = Array(fieldReps.size) { Field(it, fieldReps[it]) }
    val arity: Int = fields.size

    private val shape = StaticShape.newBuilder(language).also { builder ->
        fields.forEach { it.register(builder) }
    }.build(DataValue::class.java, DataValueFactory::class.java)

    private val nullaryValue: DataValue? = if (arity == 0) shape.factory.create(this) else null

    /** The temporary input array is never retained; every stored property is final. */
    @ExplodeLoop
    fun create(values: Array<Any?>): DataValue {
        if (values.size != arity) fault("Constructor field count does not match layout")
        val constant = nullaryValue
        if (constant != null) return constant
        val value = shape.factory.create(this)
        for (index in fields.indices) fields[index].initialize(value, values[index])
        return value
    }

    fun read(value: DataValue, index: Int): Any? {
        if (value.layout !== this) fault("Constructor value does not match layout")
        if (index < 0 || index >= arity) fault("Invalid constructor field index")
        return fields[index].read(value)
    }

    fun isLong(index: Int): Boolean {
        if (index < 0 || index >= arity) fault("Invalid constructor field index")
        return fields[index].isLong()
    }

    fun readLong(value: DataValue, index: Int): Long {
        if (value.layout !== this) fault("Constructor value does not match layout")
        if (index < 0 || index >= arity) fault("Invalid constructor field index")
        return fields[index].readLong(value)
    }

    /** Keep primitive property reads next to indexed frame writes, as Cadenza does. */
    fun restore(value: DataValue, index: Int, frame: Frame, slot: Int) {
        if (value.layout !== this) fault("Constructor value does not match layout")
        if (index < 0 || index >= arity) fault("Invalid constructor field index")
        fields[index].restore(value, frame, slot)
    }

    @TruffleBoundary
    internal fun describe(value: DataValue): String {
        if (arity == 0) return name
        return fields.indices.joinToString(prefix = "$name[", postfix = "]") { index ->
            val field = read(value, index)
            // Debugging must not force lazy fields or recursively walk a long list.
            if (field is DataValue) "${field.layout.name}(...)" else field.toString()
        }
    }

    private class Field(index: Int, representation: String) {
        private val kind = when (representation) {
            "IntRep", "WordRep", "Int8Rep", "Word8Rep", "Int16Rep", "Word16Rep",
            "Int32Rep", "Word32Rep", "Int64Rep", "Word64Rep" -> LONG
            "LiftedRep", "UnliftedRep" -> OBJECT
            "VoidRep" -> VOID
            else -> throw UnsupportedCore("Unsupported constructor field representation: $representation")
        }
        private val property = DefaultStaticProperty("field_$index")

        fun register(builder: StaticShape.Builder) {
            when (kind) {
                LONG -> builder.property(property, Long::class.javaPrimitiveType, true)
                OBJECT -> builder.property(property, Any::class.java, true)
                // A zero-width Core slot exists logically, but takes no payload storage.
                VOID -> Unit
            }
        }

        fun initialize(value: DataValue, field: Any?) {
            when (kind) {
                LONG -> property.setLong(value, field as? Long ?: fault("Expected primitive Long constructor field"))
                OBJECT -> property.setObject(value, field)
                VOID -> if (field !== Unit) fault("Expected zero-width constructor field")
            }
        }

        fun read(value: DataValue): Any? = when (kind) {
            LONG -> property.getLong(value)
            OBJECT -> property.getObject(value)
            else -> Unit
        }

        fun isLong(): Boolean = kind == LONG

        fun readLong(value: DataValue): Long {
            if (kind != LONG) fault("Constructor field is not primitive Long")
            return property.getLong(value)
        }

        fun restore(value: DataValue, frame: Frame, slot: Int) {
            when (kind) {
                LONG -> FrameAccess.write(frame, slot, property.getLong(value))
                OBJECT -> FrameAccess.write(frame, slot, property.getObject(value))
                VOID -> FrameAccess.write(frame, slot, Unit)
            }
        }

        companion object {
            private const val LONG = 0
            private const val OBJECT = 1
            private const val VOID = 2
        }
    }
}

/** Public superclass for Truffle's generated storage classes; no generic payload array. */
open class DataValue(val layout: DataLayout) {
    @TruffleBoundary
    override fun toString(): String = layout.describe(this)
}

/** Must match the public DataValue superclass constructor exactly. */
interface DataValueFactory {
    fun create(layout: DataLayout): DataValue
}
