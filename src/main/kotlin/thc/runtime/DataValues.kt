// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
import com.oracle.truffle.api.TruffleLanguage
import com.oracle.truffle.api.bytecode.BytecodeNode
import com.oracle.truffle.api.bytecode.LocalAccessor
import com.oracle.truffle.api.frame.Frame
import com.oracle.truffle.api.frame.VirtualFrame
import com.oracle.truffle.api.nodes.ExplodeLoop
import com.oracle.truffle.api.staticobject.DefaultStaticProperty
import com.oracle.truffle.api.staticobject.StaticShape

/* Fixed immutable field representations follow Cadenza's frame/frame_assembly.kt;
 * StaticShape construction follows frame/capture_layout.kt. See NOTICE.md and
 * LICENSE.txt. Constructor fields have known GHC representations, so unlike a
 * recursive closure capture they need neither an object fallback nor a tag.
 */

internal const val BOXED_VALUE_CACHE_PROPERTY = "thc.boxedValueCache"
// Exact wired constructor identity in the pinned GHC 9.14.1 export, not a printed type/name match.
internal const val BOXED_INT_CONSTRUCTOR_ID = "ghc-internal:GHC.Internal.Types.I#"
internal const val BOXED_CHAR_CONSTRUCTOR_ID = "ghc-internal:GHC.Internal.Types.C#"
// Pinned GHC 9.14.1 rts/Constants.h: MIN/MAX_INTLIKE and MIN/MAX_CHARLIKE.
private const val INTLIKE_MIN = -16L
private const val INTLIKE_MAX = 255L
private const val CHARLIKE_MIN = 0L
private const val CHARLIKE_MAX = 255L

/** One immutable, constructor-specific layout shared by all values of that constructor. */
class DataLayout private constructor(
    language: TruffleLanguage<*>,
    val id: String,
    val name: String,
    fieldReps: Array<String>,
    referenceTypes: Array<Class<*>?>,
    vectorProofs: Array<CoreRepresentation?>
) {
    constructor(language: TruffleLanguage<*>, id: String, name: String, fieldReps: Array<String>,
        referenceTypes: Array<Class<*>?> = arrayOfNulls(fieldReps.size)) :
        this(language, id, name, fieldReps, referenceTypes, arrayOfNulls(fieldReps.size))

    companion object {
        internal fun fromFields(language: TruffleLanguage<*>, id: String, name: String, fields: CoreFields): DataLayout =
            DataLayout(language, id, name, fields.storage, fields.referenceTypes, fields.vectorProofs)
    }
    init { require(referenceTypes.size == fieldReps.size && vectorProofs.size == fieldReps.size) }
    @CompilationFinal(dimensions = 1)
    private val fields: Array<Field>
    val arity: Int = fieldReps.size
    // Only this layout's private factory path can authorize a storage object.
    // The key is checked by the superclass constructor and never retained there.
    private val allocationKey = Any()

    private val shape: StaticShape<DataValueFactory>
    // The permanent class token is distinct from the allocation authentication key.
    private val classOwnerToken = Any()
    private val ownedCarrier: Class<*>?

    private fun buildShape(language: TruffleLanguage<*>, properties: Array<Field>, fieldless: Boolean): StaticShape<DataValueFactory> =
        StaticShape.newBuilder(language).also { builder ->
            builder.safetyChecks(!java.lang.Boolean.getBoolean(STATIC_SHAPE_UNCHECKED_PROPERTY))
            properties.forEach { it.register(builder) }
        }.build(if (fieldless) DataValue::class.java else LayoutDataValue::class.java, DataValueFactory::class.java)

    private val classIdentityEnabled = java.lang.Boolean.getBoolean(CONSTRUCTOR_CLASS_IDENTITY_PROPERTY)
    private val constructorClass: ConstructorClassIdentity
    private val nullaryValue: DataValue?
    @CompilationFinal(dimensions = 1)
    private val boxedValues: Array<DataValue>?
    private val boxedValueMinimum: Long
    internal val hasBoxedValueCache: Boolean get() = boxedValues != null

    init {
        val requested = java.lang.Boolean.parseBoolean(System.getProperty(CLASS_OWNED_LAYOUTS_PROPERTY, "true"))
        var chosenFields = Array(fieldReps.size) { Field(it, fieldReps[it], referenceTypes[it], vectorProofs[it]) }
        var chosenShape = buildShape(language, chosenFields, requested)
        var sample = chosenShape.factory.create(this, allocationKey)
        val reserved = requested && ClassOwnedLayouts.reserve(sample.javaClass, classOwnerToken)
        if (requested && !reserved) {
            // A shared array carrier already belongs to another layout. Its old
            // pointer-free values remain valid; this layout takes a separate
            // superclass with an explicit owner field before publishing values.
            // StaticProperty instances are shape-specific and cannot be reused.
            chosenFields = Array(fieldReps.size) { Field(it, fieldReps[it], referenceTypes[it], vectorProofs[it]) }
            chosenShape = buildShape(language, chosenFields, false)
            sample = chosenShape.factory.create(this, allocationKey)
        }
        fields = chosenFields
        shape = chosenShape
        ownedCarrier = if (reserved) sample.javaClass else null
        constructorClass = ConstructorClassIdentity(sample.javaClass)
        nullaryValue = if (arity == 0) sample else null
        val range = if (!java.lang.Boolean.getBoolean(BOXED_VALUE_CACHE_PROPERTY) || fieldReps.size != 1) null
        else when {
            id == BOXED_INT_CONSTRUCTOR_ID && name == "I#" && fieldReps[0] == "IntRep" -> INTLIKE_MIN..INTLIKE_MAX
            id == BOXED_CHAR_CONSTRUCTOR_ID && name == "C#" && fieldReps[0] == "WordRep" -> CHARLIKE_MIN..CHARLIKE_MAX
            else -> null
        }
        boxedValueMinimum = range?.first ?: 0L
        boxedValues = range?.let {
            // Neither the array nor any partially initialized value escapes this constructor.
            // Every value owns its storage under both StaticShape strategies.
            Array((it.last - it.first + 1).toInt()) { index ->
                val value = shape.factory.create(this, allocationKey)
                checkAllocated(value)
                fields[0].initializeLong(value, boxedValueMinimum + index)
                value
            }
        }
        if (ownedCarrier != null) ClassOwnedLayouts.publish(ownedCarrier, classOwnerToken, this)
    }

    /** Expected-layout operations use these guards, never a generic class lookup. */
    private fun owns(value: Any?): Boolean = if (ownedCarrier != null)
        value != null && value.javaClass === ownedCarrier
    else value is LayoutDataValue && value.layout === this

    private fun checkAllocated(value: DataValue) {
        // An unexpected new carrier cannot escape and acquire an ambiguous cold
        // identity. The old layout-carrying path retains its original fallback.
        if (ownedCarrier != null && value.javaClass !== ownedCarrier)
            fault("Unexpected carrier for class-owned constructor layout")
        constructorClass.observe(value.javaClass)
    }

    fun matches(value: Any?): Boolean {
        if (ownedCarrier != null) return owns(value)
        if (classIdentityEnabled && constructorClass.isExclusive())
            return value != null && value.javaClass === constructorClass.carrier
        return owns(value)
    }

    internal fun checkAllocationKey(key: Any?) {
        if (key !== allocationKey) fault("Invalid constructor allocation key")
    }

    /** The temporary input array is never retained; every stored property is final. */
    @ExplodeLoop
    fun create(values: Array<Any?>): DataValue {
        if (values.size != arity) fault("Constructor field count does not match layout")
        if (fields.any { it.vector != null }) fault("Vector constructor fields require primitive lane sources")
        if (boxedValues != null)
            return createLong(values[0] as? Long ?: fault("Expected primitive Long constructor field"))
        val value = allocate()
        for (index in fields.indices) fields[index].initialize(value, values[index])
        return value
    }

    /** A one-field primitive constructor; the cache never receives later initialization writes. */
    internal fun createLong(field: Long): DataValue {
        if (arity != 1 || !fields[0].isLong()) fault("Expected one primitive Long constructor field")
        val cached = boxedValues
        if (cached != null && field >= boxedValueMinimum && field < boxedValueMinimum + cached.size)
            return cached[(field - boxedValueMinimum).toInt()]
        val value = allocate()
        fields[0].initializeLong(value, field)
        return value
    }

    /** Keep the value private until every final field has been initialized exactly once. */
    @JvmName("allocate")
    internal fun allocate(): DataValue {
        val value = nullaryValue ?: shape.factory.create(this, allocationKey)
        checkAllocated(value)
        return value
    }

    @JvmName("initialize")
    internal fun initialize(value: DataValue, index: Int, field: Any?) {
        if (!owns(value)) fault("Constructor value does not match layout")
        if (index < 0 || index >= arity) fault("Invalid constructor field index")
        fields[index].initialize(value, field)
    }

    /** A primitive producer can initialize its property without an Object-array bridge. */
    @JvmName("initializeLong")
    internal fun initializeLong(value: DataValue, index: Int, field: Long) {
        if (!owns(value)) fault("Constructor value does not match layout")
        if (index < 0 || index >= arity) fault("Invalid constructor field index")
        fields[index].initializeLong(value, field)
    }

    @JvmName("initializeFloat")
    internal fun initializeFloat(value: DataValue, index: Int, field: Float) {
        if (!owns(value)) fault("Constructor value does not match layout")
        if (index < 0 || index >= arity) fault("Invalid constructor field index")
        fields[index].initializeFloat(value, field)
    }

    @JvmName("initializeDouble")
    internal fun initializeDouble(value: DataValue, index: Int, field: Double) {
        if (!owns(value)) fault("Constructor value does not match layout")
        if (index < 0 || index >= arity) fault("Invalid constructor field index")
        fields[index].initializeDouble(value, field)
    }

    fun isVector(index: Int): Boolean = fields[index].vector != null
    fun fieldWidth(index: Int): Int = fields[index].vector?.lanes ?: if (fields[index].isVoid()) 0 else 1
    internal fun vectorProof(index: Int): CoreRepresentation? = fields[index].vector?.proof
    private fun checkedVector(value: DataValue, index: Int): OwnedVectorFields {
        if (!owns(value)) fault("Constructor value does not match layout")
        if (index < 0 || index >= arity) fault("Invalid constructor field index")
        return fields[index].vector ?: fault("Constructor field is not a vector")
    }
    @JvmName("initializeVector")
    internal fun initializeVector(value: DataValue, index: Int, frame: Frame, slots: IntArray, offset: Int) {
        checkedVector(value, index).initialize(value, frame, slots, offset)
    }
    @JvmName("initializeVector")
    internal fun initializeVector(value: DataValue, index: Int, bytecode: BytecodeNode, frame: VirtualFrame,
        slots: Array<LocalAccessor>, offset: Int) {
        checkedVector(value, index).initialize(value, bytecode, frame, slots, offset)
    }
    fun restoreVector(value: DataValue, index: Int, frame: Frame, slots: IntArray, offset: Int) {
        checkedVector(value, index).restore(value, frame, slots, offset)
    }
    fun restoreVector(value: DataValue, index: Int, bytecode: BytecodeNode, frame: VirtualFrame,
        slots: Array<LocalAccessor>, offset: Int) {
        checkedVector(value, index).restore(value, bytecode, frame, slots, offset)
    }

    fun read(value: DataValue, index: Int): Any? {
        if (!owns(value)) fault("Constructor value does not match layout")
        if (index < 0 || index >= arity) fault("Invalid constructor field index")
        return fields[index].read(value)
    }

    /** The RTS selector in atomicModifyMutVar2# requires a lifted first field. */
    internal fun readFirstLifted(value: DataValue): Any? {
        if (fields.firstOrNull()?.isLifted != true)
            fault("atomicModifyMutVar2# requires a lifted first record field")
        return read(value, 0)
    }

    fun isLong(index: Int): Boolean {
        if (index < 0 || index >= arity) fault("Invalid constructor field index")
        return fields[index].isLong()
    }

    fun readLong(value: DataValue, index: Int): Long {
        if (!owns(value)) fault("Constructor value does not match layout")
        if (index < 0 || index >= arity) fault("Invalid constructor field index")
        return fields[index].readLong(value)
    }

    fun isFloat(index: Int): Boolean {
        if (index < 0 || index >= arity) fault("Invalid constructor field index")
        return fields[index].isFloat()
    }
    fun isDouble(index: Int): Boolean {
        if (index < 0 || index >= arity) fault("Invalid constructor field index")
        return fields[index].isDouble()
    }
    fun readFloat(value: DataValue, index: Int): Float {
        if (!owns(value)) fault("Constructor value does not match layout")
        if (index < 0 || index >= arity) fault("Invalid constructor field index")
        return fields[index].readFloat(value)
    }
    fun readDouble(value: DataValue, index: Int): Double {
        if (!owns(value)) fault("Constructor value does not match layout")
        if (index < 0 || index >= arity) fault("Invalid constructor field index")
        return fields[index].readDouble(value)
    }

    /** Keep primitive property reads next to indexed frame writes, as Cadenza does. */
    fun restore(value: DataValue, index: Int, frame: Frame, slot: Int) {
        if (!owns(value)) fault("Constructor value does not match layout")
        if (index < 0 || index >= arity) fault("Invalid constructor field index")
        fields[index].restore(value, frame, slot)
    }

    @TruffleBoundary
    internal fun describe(value: DataValue): String {
        if (!owns(value)) fault("Constructor value does not match layout")
        if (arity == 0) return name
        return fields.indices.joinToString(prefix = "$name[", postfix = "]") { index ->
            val field = if (fields[index].vector != null) "<${fields[index].vector!!.proof.primReps!!.single()}>"
                else read(value, index)
            // Debugging must not force lazy fields or recursively walk a long list.
            if (field is DataValue) "${field.layout.name}(...)" else field.toString()
        }
    }

    private class Field(index: Int, representation: String, referenceType: Class<*>?, vectorProof: CoreRepresentation?) {
        val vector = vectorProof?.let { OwnedVectorFields(it, "field_$index") }
        val isLifted = representation == "LiftedRep"
        private val kind = if (vector != null) VECTOR else when (representation) {
            "IntRep", "WordRep", "Int8Rep", "Word8Rep", "Int16Rep", "Word16Rep",
            "Int32Rep", "Word32Rep", "Int64Rep", "Word64Rep" -> LONG
            "FloatRep" -> FLOAT
            "DoubleRep" -> DOUBLE
            "LiftedRep", "UnliftedRep", "AddrRep" -> OBJECT
            "VoidRep" -> VOID
            else -> throw UnsupportedCore("Unsupported constructor field representation: $representation")
        }
        private val address = representation == "AddrRep"
        private val referenceType = if (address) ManagedAddress::class.java else referenceType ?: Any::class.java
        init {
            require(vector == null || vector.proof.primReps == listOf(representation)) { "Vector field representation mismatch" }
            require(referenceType == null || kind == OBJECT)
            require(!address || referenceType == null || referenceType == ManagedAddress::class.java)
        }
        private val property = DefaultStaticProperty("field_$index")

        fun register(builder: StaticShape.Builder) {
            when (kind) {
                LONG -> builder.property(property, Long::class.javaPrimitiveType, true)
                FLOAT -> builder.property(property, Float::class.javaPrimitiveType, true)
                DOUBLE -> builder.property(property, Double::class.javaPrimitiveType, true)
                OBJECT -> builder.property(property, referenceType, true)
                VECTOR -> vector!!.register(builder)
                // A zero-width Core slot exists logically, but takes no payload storage.
                VOID -> Unit
            }
        }

        fun initialize(value: DataValue, field: Any?) {
            when (kind) {
                LONG -> property.setLong(value, field as? Long ?: fault("Expected primitive Long constructor field"))
                FLOAT -> property.setFloat(value, field as? Float ?: fault("Expected primitive Float constructor field"))
                DOUBLE -> property.setDouble(value, field as? Double ?: fault("Expected primitive Double constructor field"))
                OBJECT -> property.setObject(value, if (address)
                    field as? ManagedAddress ?: fault("Expected a managed literal Addr# constructor field") else field)
                VOID -> if (field !== Unit) fault("Expected zero-width constructor field")
                VECTOR -> fault("Vector constructor field requires primitive lane sources")
            }
        }

        fun initializeLong(value: DataValue, field: Long) {
            if (kind != LONG) fault("Constructor field is not primitive Long")
            property.setLong(value, field)
        }

        fun initializeFloat(value: DataValue, field: Float) {
            if (kind != FLOAT) fault("Constructor field is not primitive Float")
            property.setFloat(value, field)
        }
        fun initializeDouble(value: DataValue, field: Double) {
            if (kind != DOUBLE) fault("Constructor field is not primitive Double")
            property.setDouble(value, field)
        }

        fun read(value: DataValue): Any? = when (kind) {
            LONG -> property.getLong(value)
            FLOAT -> property.getFloat(value)
            DOUBLE -> property.getDouble(value)
            OBJECT -> property.getObject(value)
            VECTOR -> fault("Vector constructor field requires a typed destination")
            else -> Unit
        }

        fun isLong(): Boolean = kind == LONG
        fun isFloat(): Boolean = kind == FLOAT
        fun isDouble(): Boolean = kind == DOUBLE
        fun isVoid(): Boolean = kind == VOID

        fun readLong(value: DataValue): Long {
            if (kind != LONG) fault("Constructor field is not primitive Long")
            return property.getLong(value)
        }

        fun readFloat(value: DataValue): Float {
            if (kind != FLOAT) fault("Constructor field is not primitive Float")
            return property.getFloat(value)
        }
        fun readDouble(value: DataValue): Double {
            if (kind != DOUBLE) fault("Constructor field is not primitive Double")
            return property.getDouble(value)
        }

        fun restore(value: DataValue, frame: Frame, slot: Int) {
            when (kind) {
                LONG -> FrameAccess.writeLong(frame, slot, property.getLong(value))
                FLOAT -> FrameAccess.writeFloat(frame, slot, property.getFloat(value))
                DOUBLE -> FrameAccess.writeDouble(frame, slot, property.getDouble(value))
                OBJECT -> FrameAccess.write(frame, slot, property.getObject(value))
                VOID -> FrameAccess.write(frame, slot, Unit)
                VECTOR -> fault("Vector constructor field requires a typed destination")
            }
        }

        companion object {
            private const val LONG = 0
            private const val OBJECT = 1
            private const val VOID = 2
            private const val FLOAT = 3
            private const val DOUBLE = 4
            private const val VECTOR = 5
        }
    }
}

/** Public superclass for Truffle's generated storage classes; no generic payload array. */
open class DataValue(layout: DataLayout, allocationKey: Any?) :
    ValidatedStorage(layout.checkAllocationKey(allocationKey)) {
    // Concrete getter: StaticShape deliberately rejects abstract superclass methods.
    open val layout: DataLayout
        @TruffleBoundary get() = ClassOwnedLayouts.resolve(javaClass)

    @TruffleBoundary
    override fun toString(): String = layout.describe(this)
}

/** Default representation and shared-carrier fallback. The fieldless base has no fields. */
open class LayoutDataValue(final override val layout: DataLayout, allocationKey: Any?) :
    DataValue(layout, allocationKey)

/** Must match the public DataValue superclass constructor exactly. */
interface DataValueFactory {
    fun create(layout: DataLayout, allocationKey: Any?): DataValue
}
