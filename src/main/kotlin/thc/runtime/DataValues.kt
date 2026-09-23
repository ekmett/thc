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
    fieldReps: Array<String>,
    referenceTypes: Array<Class<*>?> = arrayOfNulls(fieldReps.size)
) {
    init { require(referenceTypes.size == fieldReps.size) }
    @CompilationFinal(dimensions = 1)
    private val fields = Array(fieldReps.size) { Field(it, fieldReps[it], referenceTypes[it]) }
    val arity: Int = fields.size
    // Only this layout's private factory path can authorize a storage object.
    // The key is checked by the superclass constructor and never retained there.
    private val allocationKey = Any()

    private val shape = StaticShape.newBuilder(language).also { builder ->
        builder.safetyChecks(!java.lang.Boolean.getBoolean(STATIC_SHAPE_UNCHECKED_PROPERTY))
        fields.forEach { it.register(builder) }
    }.build(DataValue::class.java, DataValueFactory::class.java)

    private val classIdentityEnabled = java.lang.Boolean.getBoolean(CONSTRUCTOR_CLASS_IDENTITY_PROPERTY)
    private val constructorClass: ConstructorClassIdentity
    private val nullaryValue: DataValue?

    init {
        // Register checked/off-mode layouts too: a later enabled layout must
        // discover all prior owners of a shared array-storage carrier class.
        val sample = shape.factory.create(this, allocationKey)
        constructorClass = ConstructorClassIdentity(sample.javaClass)
        nullaryValue = if (arity == 0) sample else null
    }

    fun matches(value: Any?): Boolean {
        if (classIdentityEnabled && constructorClass.isExclusive())
            return value != null && value.javaClass === constructorClass.carrier
        return value is DataValue && value.layout === this
    }

    internal fun checkAllocationKey(key: Any?) {
        if (key !== allocationKey) fault("Invalid constructor allocation key")
    }

    /** The temporary input array is never retained; every stored property is final. */
    @ExplodeLoop
    fun create(values: Array<Any?>): DataValue {
        if (values.size != arity) fault("Constructor field count does not match layout")
        val value = allocate()
        for (index in fields.indices) fields[index].initialize(value, values[index])
        return value
    }

    /** Keep the value private until every final field has been initialized exactly once. */
    internal fun allocate(): DataValue {
        val value = nullaryValue ?: shape.factory.create(this, allocationKey)
        constructorClass.observe(value.javaClass)
        return value
    }

    internal fun initialize(value: DataValue, index: Int, field: Any?) {
        if (value.layout !== this) fault("Constructor value does not match layout")
        if (index < 0 || index >= arity) fault("Invalid constructor field index")
        fields[index].initialize(value, field)
    }

    /** A primitive producer can initialize its property without an Object-array bridge. */
    internal fun initializeLong(value: DataValue, index: Int, field: Long) {
        if (value.layout !== this) fault("Constructor value does not match layout")
        if (index < 0 || index >= arity) fault("Invalid constructor field index")
        fields[index].initializeLong(value, field)
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
        if (value.layout !== this) fault("Constructor value does not match layout")
        if (arity == 0) return name
        return fields.indices.joinToString(prefix = "$name[", postfix = "]") { index ->
            val field = read(value, index)
            // Debugging must not force lazy fields or recursively walk a long list.
            if (field is DataValue) "${field.layout.name}(...)" else field.toString()
        }
    }

    private class Field(index: Int, representation: String, referenceType: Class<*>?) {
        private val kind = when (representation) {
            "IntRep", "WordRep", "Int8Rep", "Word8Rep", "Int16Rep", "Word16Rep",
            "Int32Rep", "Word32Rep", "Int64Rep", "Word64Rep" -> LONG
            "LiftedRep", "UnliftedRep" -> OBJECT
            "VoidRep" -> VOID
            else -> throw UnsupportedCore("Unsupported constructor field representation: $representation")
        }
        private val referenceType = referenceType ?: Any::class.java
        init { require(referenceType == null || kind == OBJECT) }
        private val property = DefaultStaticProperty("field_$index")

        fun register(builder: StaticShape.Builder) {
            when (kind) {
                LONG -> builder.property(property, Long::class.javaPrimitiveType, true)
                OBJECT -> builder.property(property, referenceType, true)
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

        fun initializeLong(value: DataValue, field: Long) {
            if (kind != LONG) fault("Constructor field is not primitive Long")
            property.setLong(value, field)
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
                LONG -> FrameAccess.writeLong(frame, slot, property.getLong(value))
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
open class DataValue(val layout: DataLayout, allocationKey: Any?) :
    ValidatedStorage(layout.checkAllocationKey(allocationKey)) {
    @TruffleBoundary
    override fun toString(): String = layout.describe(this)
}

/** Must match the public DataValue superclass constructor exactly. */
interface DataValueFactory {
    fun create(layout: DataLayout, allocationKey: Any?): DataValue
}
