// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.bytecode.BytecodeNode;
import com.oracle.truffle.api.bytecode.LocalAccessor;
import com.oracle.truffle.api.frame.Frame;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.oracle.truffle.api.staticobject.DefaultStaticProperty;
import com.oracle.truffle.api.staticobject.StaticShape;
import thc.vm.Lifted;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;
import static thc.runtime.DataValues.*;

/* Fixed immutable representations and StaticShape construction follow Cadenza
 * frame assembly and capture layouts. See NOTICE.md and
 * LICENSE.md. Constructor fields need neither object fallbacks nor tags. */
public final class DataLayout {
    private final String id;
    private final String name;
    private final CoreFields logicalFields;
    @CompilationFinal(dimensions = 1) private final Field[] fields;
    private final int arity;
    private final int logicalArity;
    private final boolean hasAggregateFields;
    private static final class SumField {
        final int offset;
        final CoreRepresentation proof;
        SumField(int offset, CoreRepresentation proof) { this.offset = offset; this.proof = proof; }
    }
    @CompilationFinal(dimensions = 1) private final SumField[] sumFields;
    private final String[] exactFieldReps;
    private final Object allocationKey = new Object();
    private final StaticShape<DataValueFactory> shape;
    private final Object classOwnerToken = new Object();
    private final Class<?> ownedCarrier;
    private final boolean classIdentityEnabled = Boolean.getBoolean(ConstructorClassIdentity.CONSTRUCTOR_CLASS_IDENTITY_PROPERTY);
    private final ConstructorClassIdentity constructorClass;
    private final DataValue nullaryValue;
    @CompilationFinal(dimensions = 1) private final DataValue[] boxedValues;
    private final long boxedValueMinimum;
    private final Reusable reusable;

    /** Code-owned storage only. Allocation keys, constructor identity and guest
     * caches belong to each instantiated DataLayout, never to cached code. */
    static final class Reusable {
        final String id;
        final String name;
        final CoreFields logicalFields;
        @CompilationFinal(dimensions = 1) final Field[] fields;
        final StaticShape<DataValueFactory> shape;
        Reusable(TruffleLanguage<?> language, String id, String name, CoreFields logicalFields) {
            this.id = id; this.name = name; this.logicalFields = logicalFields;
            for (CoreRepresentation proof : logicalFields.getLogicalProofs()) {
                if (!proof.getPresent() || proof.getKind() == CoreKind.UNKNOWN && !proof.isTypedTransport())
                    throw new UnsupportedCore("Reusable constructor fields require exact representation proofs");
                CoreRepresentations.requireInput(proof);
            }
            fields = newFields(logicalFields.getStorage(), logicalFields.getReferenceTypes(), logicalFields.getVectorProofs());
            // A shared carrier must carry its exact per-load owner, not use the
            // ordinary one-layout-per-class shortcut.
            shape = buildShape(language, fields, false);
        }
        DataLayout instantiate() {
            return new DataLayout(null, id, name, logicalFields.getStorage(), logicalFields.getReferenceTypes(),
                logicalFields.getVectorProofs(), logicalFields, this);
        }
        private void checkOwner(DataLayout owner) {
            if (owner.reusable != this) throw fault("Reusable constructor storage does not match owner");
        }
        DataValue allocate(DataLayout owner) {
            checkOwner(owner);
            return owner.nullaryValue != null ? owner.nullaryValue : shape.getFactory().create(owner, owner.allocationKey);
        }
        boolean isLong(int index) { return fields[index].isLong(); }
        boolean isInt(int index) { return fields[index].isInt(); }
        boolean isFloat(int index) { return fields[index].isFloat(); }
        boolean isDouble(int index) { return fields[index].isDouble(); }
        boolean isVector(int index) { return fields[index].vector != null; }
        int fieldOffset(int index) { return logicalFields.getOffsets()[index]; }
        CoreRepresentation logicalProof(int index) { return logicalFields.getLogicalProofs()[index]; }
        DataValue createLong(DataLayout owner, long value) {
            checkOwner(owner);
            DataValue[] cached = owner.boxedValues;
            if (cached != null && value >= owner.boxedValueMinimum && value < owner.boxedValueMinimum + cached.length)
                return cached[(int) (value - owner.boxedValueMinimum)];
            DataValue result = allocate(owner); fields[0].initializeLong(result, value); return result;
        }
        void initialize(DataLayout owner, DataValue value, int index, Object field) {
            checkOwner(owner); owner.checkField(value, index); fields[index].initialize(value, field);
        }
        void initializeLong(DataLayout owner, DataValue value, int index, long field) {
            checkOwner(owner); owner.checkField(value, index); fields[index].initializeLong(value, field);
        }
        void initializeInt(DataLayout owner, DataValue value, int index, int field) {
            checkOwner(owner); owner.checkField(value, index); fields[index].initializeInt(value, field);
        }
        void initializeFloat(DataLayout owner, DataValue value, int index, float field) {
            checkOwner(owner); owner.checkField(value, index); fields[index].initializeFloat(value, field);
        }
        void initializeDouble(DataLayout owner, DataValue value, int index, double field) {
            checkOwner(owner); owner.checkField(value, index); fields[index].initializeDouble(value, field);
        }
        void restore(DataLayout owner, DataValue value, int index, Frame frame, int slot) {
            checkOwner(owner); owner.checkField(value, index); fields[index].restore(value, frame, slot);
        }
        void initializeVector(DataLayout owner, DataValue value, int index, Frame frame, int[] slots, int offset) {
            checkOwner(owner); owner.checkField(value, index); fields[index].vector.initialize(value, frame, slots, offset);
        }
        void restoreVector(DataLayout owner, DataValue value, int index, Frame frame, int[] slots, int offset) {
            checkOwner(owner); owner.checkField(value, index); fields[index].vector.restore(value, frame, slots, offset);
        }
    }
    Reusable reusableStorage() { return reusable; }

    public DataLayout(TruffleLanguage<?> language, String id, String name, String[] fieldReps) {
        this(language, id, name, fieldReps, new Class<?>[fieldReps.length]);
    }
    public DataLayout(TruffleLanguage<?> language, String id, String name, String[] fieldReps, Class<?>[] referenceTypes) {
        this(language, id, name, fieldReps, referenceTypes, new CoreRepresentation[fieldReps.length], null);
    }
    public static DataLayout fromFields(TruffleLanguage<?> language, String id, String name, CoreFields fields) {
        return new DataLayout(language, id, name, fields.getStorage(), fields.getReferenceTypes(), fields.getVectorProofs(), fields);
    }
    private DataLayout(TruffleLanguage<?> language, String id, String name, String[] fieldReps,
                       Class<?>[] referenceTypes, CoreRepresentation[] vectorProofs, CoreFields logicalFields) {
        this(language, id, name, fieldReps, referenceTypes, vectorProofs, logicalFields, null);
    }
    private DataLayout(TruffleLanguage<?> language, String id, String name, String[] fieldReps,
                       Class<?>[] referenceTypes, CoreRepresentation[] vectorProofs, CoreFields logicalFields, Reusable reusable) {
        if (referenceTypes.length != fieldReps.length || vectorProofs.length != fieldReps.length) throw new IllegalArgumentException("Failed requirement.");
        this.id = id; this.name = name; this.logicalFields = logicalFields;
        this.reusable = reusable;
        arity = fieldReps.length;
        logicalArity = logicalFields == null ? arity : logicalFields.getLogicalProofs().length;
        hasAggregateFields = logicalFields != null && logicalFields.getHasAggregates();
        var sums = new ArrayList<SumField>();
        if (logicalFields != null) for (int i = 0; i < logicalFields.getLogicalProofs().length; i++)
            collectSums(logicalFields.getLogicalProofs()[i], logicalFields.getOffsets()[i], sums);
        sumFields = sums.toArray(SumField[]::new);
        exactFieldReps = fieldReps.clone();
        boolean requested = reusable == null && Boolean.parseBoolean(System.getProperty(ClassOwnedLayouts.CLASS_OWNED_LAYOUTS_PROPERTY, "true"));
        Field[] chosenFields = reusable == null ? newFields(fieldReps, referenceTypes, vectorProofs) : reusable.fields;
        StaticShape<DataValueFactory> chosenShape = reusable == null ? buildShape(language, chosenFields, requested) : reusable.shape;
        DataValue sample = chosenShape.getFactory().create(this, allocationKey);
        boolean reserved = requested && ClassOwnedLayouts.reserve(sample.getClass(), classOwnerToken);
        if (requested && !reserved) {
            // Properties are shape-specific. Keep existing pointer-free values valid.
            chosenFields = newFields(fieldReps, referenceTypes, vectorProofs);
            chosenShape = buildShape(language, chosenFields, false);
            sample = chosenShape.getFactory().create(this, allocationKey);
        }
        fields = chosenFields; shape = chosenShape;
        ownedCarrier = reserved ? sample.getClass() : null;
        constructorClass = reusable == null ? new ConstructorClassIdentity(sample.getClass()) : null;
        nullaryValue = arity == 0 ? sample : null;
        boolean cache = Boolean.getBoolean(BOXED_VALUE_CACHE_PROPERTY) && fieldReps.length == 1 && !hasAggregateFields;
        boolean intlike = cache && id.equals(BOXED_INT_CONSTRUCTOR_ID) && name.equals("I#") && fieldReps[0].equals("IntRep");
        boolean charlike = cache && id.equals(BOXED_CHAR_CONSTRUCTOR_ID) && name.equals("C#") && fieldReps[0].equals("WordRep");
        boxedValueMinimum = intlike ? -16L : 0L;
        boxedValues = intlike || charlike ? new DataValue[(int) (255L - boxedValueMinimum + 1L)] : null;
        if (boxedValues != null) for (int i = 0; i < boxedValues.length; i++) {
            DataValue value = shape.getFactory().create(this, allocationKey);
            checkAllocated(value);
            fields[0].initializeLong(value, boxedValueMinimum + i);
            boxedValues[i] = value;
        }
        // No partially initialized descriptor or cache escapes through class ownership.
        if (ownedCarrier != null) ClassOwnedLayouts.publish(ownedCarrier, classOwnerToken, this);
    }
    private static Field[] newFields(String[] reps, Class<?>[] references, CoreRepresentation[] vectors) {
        Field[] fields = new Field[reps.length];
        for (int i = 0; i < fields.length; i++) fields[i] = new Field(i, reps[i], references[i], vectors[i]);
        return fields;
    }
    private static int collectSums(CoreRepresentation proof, int offset, List<SumField> sums) {
        if (proof.isSum()) { sums.add(new SumField(offset, proof)); return SumShape.INSTANCE.storage(proof).size(); }
        if (proof.getComponents() != null) {
            int width = 0;
            for (CoreRepresentation component : proof.getComponents()) width += collectSums(component, offset + width, sums);
            return width;
        }
        return proof.getKind() == CoreKind.VOID ? 0 : 1;
    }
    private static StaticShape<DataValueFactory> buildShape(TruffleLanguage<?> language, Field[] properties, boolean fieldless) {
        StaticShape.Builder builder = StaticShape.newBuilder(language);
        builder.safetyChecks(!Boolean.getBoolean(Frames.STATIC_SHAPE_UNCHECKED_PROPERTY));
        for (Field property : properties) property.register(builder);
        return fieldless ? builder.build(DataValue.class, DataValueFactory.class) : builder.build(LayoutDataValue.class, DataValueFactory.class);
    }
    public String getId() { return id; }
    public String getName() { return name; }
    public int getArity() { return arity; }
    public int getLogicalArity() { return logicalArity; }
    public int fieldOffset(int index) { return logicalFields == null ? index : logicalFields.getOffsets()[index]; }
    public CoreRepresentation logicalProof(int index) { return logicalFields == null ? null : logicalFields.getLogicalProofs()[index]; }
    public int logicalWidth(int index) { return fieldOffset(index + 1) - fieldOffset(index); }
    public boolean getHasAggregateFields() { return hasAggregateFields; }
    public boolean getHasBoxedValueCache() { return boxedValues != null; }
    public boolean hasFieldRepresentation(int index, String rep) { return index >= 0 && index < exactFieldReps.length && exactFieldReps[index].equals(rep); }
    private boolean owns(Object value) {
        return ownedCarrier != null ? value != null && value.getClass() == ownedCarrier : value instanceof LayoutDataValue data && data.getLayout() == this;
    }
    private void checkAllocated(DataValue value) {
        if (ownedCarrier != null && value.getClass() != ownedCarrier) throw fault("Unexpected carrier for class-owned constructor layout");
        if (constructorClass != null) constructorClass.observe(value.getClass());
    }
    public boolean matches(Object value) {
        if (ownedCarrier != null) return owns(value);
        if (classIdentityEnabled && constructorClass != null && constructorClass.isExclusive()) return value != null && value.getClass() == constructorClass.getCarrier();
        return owns(value);
    }
    public Object checkAllocationKey(Object key) {
        if (key != allocationKey) throw fault("Invalid constructor allocation key");
        return null;
    }
    @ExplodeLoop public DataValue create(Object[] values) {
        if (values.length != arity) throw fault("Constructor field count does not match layout");
        for (Field field : fields) if (field.vector != null) throw fault("Vector constructor fields require vector-aware initialization");
        if (boxedValues != null) {
            if (!(values[0] instanceof Long number)) throw fault("Expected primitive Long constructor field");
            return createLong(number);
        }
        DataValue value = allocate();
        for (int i = 0; i < fields.length; i++) fields[i].initialize(value, values[i]);
        return value;
    }
    public DataValue createLong(long field) {
        if (arity != 1 || !fields[0].isLong()) throw fault("Expected one primitive Long constructor field");
        DataValue[] cached = boxedValues;
        if (cached != null && field >= boxedValueMinimum && field < boxedValueMinimum + cached.length) return cached[(int) (field - boxedValueMinimum)];
        DataValue value = allocate(); fields[0].initializeLong(value, field); return value;
    }
    public DataValue createInt(int field) {
        if (arity != 1 || !fields[0].isInt()) throw fault("Expected one primitive Int constructor field");
        DataValue value = allocate(); fields[0].initializeInt(value, field); return value;
    }
    public DataValue allocate() {
        DataValue value = nullaryValue != null ? nullaryValue : shape.getFactory().create(this, allocationKey);
        checkAllocated(value); return value;
    }
    public void initialize(DataValue value, int index, Object field) { checkField(value, index); fields[index].initialize(value, field); }
    public void initializeInt(DataValue value, int index, int field) { checkField(value, index); fields[index].initializeInt(value, field); }
    public void initializeLong(DataValue value, int index, long field) { checkField(value, index); fields[index].initializeLong(value, field); }
    public void initializeFloat(DataValue value, int index, float field) { checkField(value, index); fields[index].initializeFloat(value, field); }
    public void initializeDouble(DataValue value, int index, double field) { checkField(value, index); fields[index].initializeDouble(value, field); }
    public boolean isVector(int index) { return fields[index].vector != null; }
    public int fieldWidth(int index) { return fields[index].isVoid() ? 0 : 1; }
    public CoreRepresentation vectorProof(int index) { return fields[index].vector == null ? null : fields[index].vector.getProof(); }
    private OwnedVectorFields checkedVector(DataValue value, int index) {
        checkField(value, index);
        if (fields[index].vector == null) throw fault("Constructor field is not a vector");
        return fields[index].vector;
    }
    public void initializeVector(DataValue value, int index, Frame frame, int[] slots, int offset) { checkedVector(value, index).initialize(value, frame, slots, offset); }
    public void initializeVector(DataValue value, int index, BytecodeNode bytecode, VirtualFrame frame, LocalAccessor[] slots, int offset) { checkedVector(value, index).initialize(value, bytecode, frame, slots, offset); }
    public void restoreVector(DataValue value, int index, Frame frame, int[] slots, int offset) { checkedVector(value, index).restore(value, frame, slots, offset); }
    public void restoreVector(DataValue value, int index, BytecodeNode bytecode, VirtualFrame frame, LocalAccessor[] slots, int offset) { checkedVector(value, index).restore(value, bytecode, frame, slots, offset); }
    /** Logical scalar reference projections preserve the existing carrier identity. */
    public Lifted project(DataValue value, int index) {
        if (index < 0 || index >= logicalArity) return null;
        CoreRepresentation proof = logicalProof(index);
        if (proof != null && proof.isAggregate()) return null;
        int physical = fieldOffset(index);
        checkField(value, physical);
        Field field = fields[physical];
        if (!field.isReference()) return null;
        Object reference = field.read(value);
        return reference instanceof Lifted lifted ? lifted : null;
    }
    public Object read(DataValue value, int index) { checkField(value, index); return fields[index].read(value); }
    public boolean compactPointer(int index) { return exactFieldReps[index].equals("LiftedRep") || exactFieldReps[index].equals("UnliftedRep") || exactFieldReps[index].equals("BoxedRep"); }
    public boolean inactiveSumReference(DataValue value, int index) {
        if (!compactPointer(index)) return false;
        for (SumField field : sumFields) {
            int width = SumShape.INSTANCE.storage(field.proof).size();
            if (index < field.offset || index >= field.offset + width) continue;
            var slots = new ArrayList<Integer>(width);
            for (int i = 0; i < width; i++) slots.add(field.offset + i);
            return !active(value, index, field.proof, slots);
        }
        return false;
    }
    private boolean active(DataValue value, int index, CoreRepresentation proof, List<Integer> slots) {
        if (proof.isSum()) {
            int tag = SumShape.INSTANCE.checkedTag(readLong(value, slots.getFirst()), proof.getAlternatives().size());
            var selected = new ArrayList<Integer>();
            for (int slot : SumShape.INSTANCE.projection(proof, tag - 1)) selected.add(slots.get(slot));
            return selected.contains(index) && active(value, index, proof.getAlternatives().get(tag - 1), selected);
        }
        if (proof.getComponents() != null) {
            int offset = 0;
            for (CoreRepresentation component : proof.getComponents()) {
                int width = TupleShape.Companion.flatten(component).size();
                List<Integer> selected = slots.subList(offset, offset + width);
                if (selected.contains(index)) return active(value, index, component, selected);
                offset += width;
            }
            return false;
        }
        return slots.contains(index);
    }
    public boolean acceptsCompactPointer(int index, Object value) { return fields[index].acceptsReference(value); }
    public long compactBytes() {
        long size = 8L;
        for (Field field : fields) {
            if (field.vector != null) {
                CoreVector vector = field.vector.getProof().getVector();
                size += (long) vector.getLanes() * switch (vector.getElement()) {
                    case "Int8ElemRep", "Word8ElemRep" -> 1;
                    case "Int16ElemRep", "Word16ElemRep" -> 2;
                    case "Int32ElemRep", "Word32ElemRep", "FloatElemRep" -> 4;
                    default -> 8;
                };
            } else size += field.isVoid() ? 0L : 8L;
        }
        return size;
    }
    public void copyCompactScalar(DataValue source, DataValue target, int index) {
        if (!owns(source) || !owns(target)) throw fault("Compact constructor layout mismatch");
        Field field = fields[index];
        if (field.vector != null) field.vector.copy(source, target);
        else field.initialize(target, field.read(source));
    }
    public void writeCompactScalar(DataValue value, int index, DataOutputStream output) throws IOException {
        Field field = fields[index];
        if (field.vector != null) field.vector.writeImage(value, output);
        else switch (exactFieldReps[index]) {
            case "VoidRep" -> {}
            case "AddrRep" -> output.writeLong(((ManagedAddress) field.read(value)).toNativeBits());
            case "FloatRep" -> output.writeInt(Float.floatToRawIntBits(field.readFloat(value)));
            case "DoubleRep" -> output.writeLong(Double.doubleToRawLongBits(field.readDouble(value)));
            default -> output.writeLong(field.isInt() ? field.narrowInteger.widen(field.readInt(value)) : field.readLong(value));
        }
    }
    public void readCompactScalar(DataValue value, int index, DataInputStream input) throws IOException {
        Field field = fields[index];
        if (field.vector != null) field.vector.readImage(value, input);
        else {
            Object raw;
            switch (exactFieldReps[index]) {
                case "VoidRep" -> raw = thc.runtime.Unit.INSTANCE;
                case "AddrRep" -> raw = NativeAddresses.current(null).recover(input.readLong());
                case "FloatRep" -> raw = Float.intBitsToFloat(input.readInt());
                case "DoubleRep" -> raw = Double.longBitsToDouble(input.readLong());
                default -> { if (field.isInt()) raw = (int) input.readLong(); else raw = input.readLong(); }
            }
            field.initialize(value, raw);
        }
    }
    public Object inspect(DataValue value, int index) {
        if (isVector(index)) return checkedVector(value, index).restoreRaw(value);
        if (isInt(index)) return fields[index].narrowInteger.widen(readInt(value, index));
        return read(value, index);
    }
    public Object readFirstLifted(DataValue value) {
        boolean aggregate = logicalFields != null && logicalFields.getLogicalProofs().length != 0 && logicalFields.getLogicalProofs()[0].isAggregate();
        if (!aggregate) {
            Field[] firstFields = fields;
            if (firstFields == null) {
                CompilerDirectives.transferToInterpreter();
                throw new NullPointerException("Constructor fields must not be null");
            }
            if (firstFields.length != 0 && firstFields[0] != null && firstFields[0].isLifted) return read(value, 0);
        }
        throw fault("atomicModifyMutVar2# requires a lifted first record field");
    }
    private void checkIndex(int index) { if (index < 0 || index >= arity) throw fault("Invalid constructor field index"); }
    private void checkField(DataValue value, int index) {
        if (!owns(value)) throw fault("Constructor value does not match layout");
        checkIndex(index);
    }
    public boolean isInt(int index) { checkIndex(index); return fields[index].isInt(); }
    public boolean isLong(int index) { checkIndex(index); return fields[index].isLong(); }
    public boolean isFloat(int index) { checkIndex(index); return fields[index].isFloat(); }
    public boolean isDouble(int index) { checkIndex(index); return fields[index].isDouble(); }
    public int readInt(DataValue value, int index) { checkField(value, index); return fields[index].readInt(value); }
    public long readLong(DataValue value, int index) { checkField(value, index); return fields[index].readLong(value); }
    public float readFloat(DataValue value, int index) { checkField(value, index); return fields[index].readFloat(value); }
    public double readDouble(DataValue value, int index) { checkField(value, index); return fields[index].readDouble(value); }
    public void restore(DataValue value, int index, Frame frame, int slot) { checkField(value, index); fields[index].restore(value, frame, slot); }
    @TruffleBoundary public String describe(DataValue value) {
        if (!owns(value)) throw fault("Constructor value does not match layout");
        if (arity == 0) return name;
        StringJoiner result = new StringJoiner(", ", name + "[", "]");
        for (int i = 0; i < fields.length; i++) {
            Object field = fields[i].vector == null ? read(value, i) : "<" + fields[i].vector.getProof().getPrimReps().getFirst() + ">";
            result.add(field instanceof DataValue data ? data.getLayout().getName() + "(...)" : String.valueOf(field));
        }
        return result.toString();
    }
    private static RuntimeFault fault(String message) { CompilerDirectives.transferToInterpreterAndInvalidate(); return new RuntimeFault(message); }

    private static final class Field {
        private static final int LONG = 0, OBJECT = 1, VOID = 2, FLOAT = 3, DOUBLE = 4, VECTOR = 5, INT = 6;
        final OwnedVectorFields vector;
        final boolean isLifted;
        final NarrowInteger narrowInteger;
        private final int kind;
        private final boolean address;
        private final Class<?> referenceType;
        private final DefaultStaticProperty property;
        Field(int index, String representation, Class<?> referenceType, CoreRepresentation vectorProof) {
            vector = vectorProof == null ? null : new OwnedVectorFields(vectorProof, "field_" + index);
            isLifted = representation.equals("LiftedRep");
            narrowInteger = NarrowInteger.fromRep(representation);
            kind = vector != null ? VECTOR : narrowInteger != null ? INT : switch (representation) {
                case "IntRep", "WordRep", "Int64Rep", "Word64Rep" -> LONG;
                case "FloatRep" -> FLOAT; case "DoubleRep" -> DOUBLE;
                case "LiftedRep", "UnliftedRep", "BoxedRep", "AddrRep" -> OBJECT;
                case "VoidRep" -> VOID;
                default -> throw new UnsupportedCore("Unsupported constructor field representation: " + representation);
            };
            address = representation.equals("AddrRep");
            this.referenceType = address ? ManagedAddress.class : referenceType == null ? Object.class : referenceType;
            if (vector != null && !vector.getProof().getPrimReps().equals(List.of(representation))) throw new IllegalArgumentException("Vector field representation mismatch");
            if (referenceType != null && kind != OBJECT || address && referenceType != null && referenceType != ManagedAddress.class) throw new IllegalArgumentException("Failed requirement.");
            property = new DefaultStaticProperty("field_" + index);
        }
        void register(StaticShape.Builder builder) {
            switch (kind) {
                case INT -> builder.property(property, narrowInteger.getStorageClass(), true);
                case LONG -> builder.property(property, long.class, true);
                case FLOAT -> builder.property(property, float.class, true);
                case DOUBLE -> builder.property(property, double.class, true);
                case OBJECT -> builder.property(property, referenceType, true);
                case VECTOR -> vector.register(builder);
                case VOID -> {}
            }
        }
        void initialize(DataValue value, Object field) {
            switch (kind) {
                case INT -> { if (!(field instanceof Integer number)) throw fault("Expected primitive Int constructor field"); narrowInteger.write(property, value, number); }
                case LONG -> { if (!(field instanceof Long number)) throw fault("Expected primitive Long constructor field"); property.setLong(value, number); }
                case FLOAT -> { if (!(field instanceof Float number)) throw fault("Expected primitive Float constructor field"); property.setFloat(value, number); }
                case DOUBLE -> { if (!(field instanceof Double number)) throw fault("Expected primitive Double constructor field"); property.setDouble(value, number); }
                case OBJECT -> { if (address && !(field instanceof ManagedAddress)) throw fault("Expected a managed literal Addr# constructor field"); property.setObject(value, field); }
                case VOID -> { if (field != thc.runtime.Unit.INSTANCE) throw fault("Expected zero-width constructor field"); }
                case VECTOR -> throw fault("Vector constructor field requires vector-aware initialization");
            }
        }
        void initializeInt(DataValue value, int field) { if (kind != INT) throw fault("Constructor field is not primitive Int"); narrowInteger.write(property, value, field); }
        void initializeLong(DataValue value, long field) { if (kind != LONG) throw fault("Constructor field is not primitive Long"); property.setLong(value, field); }
        void initializeFloat(DataValue value, float field) { if (kind != FLOAT) throw fault("Constructor field is not primitive Float"); property.setFloat(value, field); }
        void initializeDouble(DataValue value, double field) { if (kind != DOUBLE) throw fault("Constructor field is not primitive Double"); property.setDouble(value, field); }
        Object read(DataValue value) {
            return switch (kind) {
                case INT -> narrowInteger.read(property, value);
                case LONG -> property.getLong(value); case FLOAT -> property.getFloat(value); case DOUBLE -> property.getDouble(value);
                case OBJECT -> property.getObject(value);
                case VECTOR -> throw fault("Vector constructor field requires a typed destination");
                default -> thc.runtime.Unit.INSTANCE;
            };
        }
        boolean isReference() { return kind == OBJECT && !address; }
        boolean isInt() { return kind == INT; }
        boolean isLong() { return kind == LONG; }
        boolean isFloat() { return kind == FLOAT; }
        boolean isDouble() { return kind == DOUBLE; }
        boolean isVoid() { return kind == VOID; }
        boolean acceptsReference(Object value) { return kind == OBJECT && !address && referenceType.isInstance(value); }
        int readInt(DataValue value) { if (kind != INT) throw fault("Constructor field is not primitive Int"); return narrowInteger.read(property, value); }
        long readLong(DataValue value) { if (kind != LONG) throw fault("Constructor field is not primitive Long"); return property.getLong(value); }
        float readFloat(DataValue value) { if (kind != FLOAT) throw fault("Constructor field is not primitive Float"); return property.getFloat(value); }
        double readDouble(DataValue value) { if (kind != DOUBLE) throw fault("Constructor field is not primitive Double"); return property.getDouble(value); }
        void restore(DataValue value, Frame frame, int slot) {
            switch (kind) {
                case INT -> FrameAccess.writeInt(frame, slot, narrowInteger.read(property, value));
                case LONG -> FrameAccess.writeLong(frame, slot, property.getLong(value));
                case FLOAT -> FrameAccess.writeFloat(frame, slot, property.getFloat(value));
                case DOUBLE -> FrameAccess.writeDouble(frame, slot, property.getDouble(value));
                case OBJECT -> FrameAccess.write(frame, slot, property.getObject(value));
                case VOID -> FrameAccess.write(frame, slot, thc.runtime.Unit.INSTANCE);
                case VECTOR -> throw fault("Vector constructor field requires a typed destination");
            }
        }
    }
}
