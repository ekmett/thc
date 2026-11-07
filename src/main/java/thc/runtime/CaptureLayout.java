// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.bytecode.BytecodeNode;
import com.oracle.truffle.api.bytecode.LocalAccessor;
import com.oracle.truffle.api.frame.Frame;
import com.oracle.truffle.api.frame.FrameSlotKind;
import com.oracle.truffle.api.frame.FrameSlotTypeException;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.oracle.truffle.api.staticobject.DefaultStaticProperty;
import com.oracle.truffle.api.staticobject.StaticShape;
import static thc.runtime.RuntimeServiceStatus.fault;

/* Adapted from Cadenza's capture layout. Retained notices: NOTICE.md and LICENSE.md. */
/** Fixed storage metadata for durable selected captures, never an invocation frame. */
public final class CaptureLayout {
    @CompilationFinal(dimensions = 1) private final CaptureField[] fields;
    @CompilationFinal(dimensions = 1) private final int[] offsets;
    private final Object allocationKey = new Object();
    private final StaticShape<CapturedFrameFactory> shape;
    public CaptureLayout(TruffleLanguage<?> language, boolean[] eligible) {
        this(language, eligible, new boolean[eligible.length], new Class<?>[eligible.length], new boolean[eligible.length], new boolean[eligible.length], new CoreRepresentation[eligible.length], new NarrowInteger[eligible.length]);
    }
    public CaptureLayout(TruffleLanguage<?> language, boolean[] eligible, boolean[] exactLong) {
        this(language, eligible, exactLong, new Class<?>[eligible.length], new boolean[eligible.length], new boolean[eligible.length], new CoreRepresentation[eligible.length], new NarrowInteger[eligible.length]);
    }
    public CaptureLayout(TruffleLanguage<?> language, boolean[] eligible, boolean[] exactLong, Class<?>[] exactReference) {
        this(language, eligible, exactLong, exactReference, new boolean[eligible.length], new boolean[eligible.length], new CoreRepresentation[eligible.length], new NarrowInteger[eligible.length]);
    }
    public CaptureLayout(TruffleLanguage<?> language, boolean[] eligible, boolean[] exactLong, Class<?>[] exactReference, boolean[] exactFloat) {
        this(language, eligible, exactLong, exactReference, exactFloat, new boolean[eligible.length], new CoreRepresentation[eligible.length], new NarrowInteger[eligible.length]);
    }
    public CaptureLayout(TruffleLanguage<?> language, boolean[] eligible, boolean[] exactLong, Class<?>[] exactReference, boolean[] exactFloat, boolean[] exactDouble) {
        this(language, eligible, exactLong, exactReference, exactFloat, exactDouble, new CoreRepresentation[eligible.length], new NarrowInteger[eligible.length]);
    }
    public static CaptureLayout withVectors(TruffleLanguage<?> language, CoreRepresentation[] vectors, boolean[] eligible) {
        return withVectors(language, vectors, eligible, new boolean[eligible.length], new Class<?>[eligible.length], new boolean[eligible.length], new boolean[eligible.length], new NarrowInteger[eligible.length]);
    }
    public static CaptureLayout withVectors(TruffleLanguage<?> language, CoreRepresentation[] vectors, boolean[] eligible, boolean[] exactLong) {
        return withVectors(language, vectors, eligible, exactLong, new Class<?>[eligible.length], new boolean[eligible.length], new boolean[eligible.length], new NarrowInteger[eligible.length]);
    }
    public static CaptureLayout withVectors(TruffleLanguage<?> language, CoreRepresentation[] vectors, boolean[] eligible, boolean[] exactLong, Class<?>[] exactReference) {
        return withVectors(language, vectors, eligible, exactLong, exactReference, new boolean[eligible.length], new boolean[eligible.length], new NarrowInteger[eligible.length]);
    }
    public static CaptureLayout withVectors(TruffleLanguage<?> language, CoreRepresentation[] vectors, boolean[] eligible, boolean[] exactLong, Class<?>[] exactReference, boolean[] exactFloat) {
        return withVectors(language, vectors, eligible, exactLong, exactReference, exactFloat, new boolean[eligible.length], new NarrowInteger[eligible.length]);
    }
    public static CaptureLayout withVectors(TruffleLanguage<?> language, CoreRepresentation[] vectors, boolean[] eligible, boolean[] exactLong, Class<?>[] exactReference, boolean[] exactFloat, boolean[] exactDouble) {
        return withVectors(language, vectors, eligible, exactLong, exactReference, exactFloat, exactDouble, new NarrowInteger[eligible.length]);
    }
    public static CaptureLayout withVectors(TruffleLanguage<?> language, CoreRepresentation[] vectors, boolean[] eligible,
            boolean[] exactLong, Class<?>[] exactReference, boolean[] exactFloat, boolean[] exactDouble, NarrowInteger[] exactInt) {
        return new CaptureLayout(language, eligible, exactLong, exactReference, exactFloat, exactDouble, vectors, exactInt);
    }
    private CaptureLayout(TruffleLanguage<?> language, boolean[] eligible, boolean[] exactLong,
            Class<?>[] exactReference, boolean[] exactFloat, boolean[] exactDouble, CoreRepresentation[] vectors, NarrowInteger[] exactInt) {
        int count = eligible.length;
        if (exactLong.length != count || exactReference.length != count || exactFloat.length != count ||
            exactDouble.length != count || vectors.length != count || exactInt.length != count)
            throw new IllegalArgumentException("Failed requirement.");
        for (int i = 0; i < count; i++) {
            if (exactLong[i] && (!eligible[i] || exactReference[i] != null) ||
                exactReference[i] != null && exactReference[i].isPrimitive())
                throw new IllegalArgumentException("Failed requirement.");
            int claims = (exactLong[i] ? 1 : 0) + (exactFloat[i] ? 1 : 0) + (exactDouble[i] ? 1 : 0) +
                (exactReference[i] != null ? 1 : 0) + (exactInt[i] != null ? 1 : 0);
            if (claims > 1 || vectors[i] != null && (eligible[i] || claims != 0))
                throw new IllegalArgumentException("Failed requirement.");
        }
        fields = new CaptureField[count];
        offsets = new int[count + 1];
        for (int i = 0; i < count; i++) {
            fields[i] = new CaptureField(i, eligible[i] && exactReference[i] == null && !exactFloat[i] &&
                !exactDouble[i] && exactInt[i] == null, exactLong[i], exactReference[i], exactFloat[i],
                exactDouble[i], vectors[i], exactInt[i]);
            offsets[i + 1] = offsets[i] + 1;
        }
        StaticShape.Builder builder = StaticShape.newBuilder(language);
        builder.safetyChecks(!Boolean.getBoolean(Frames.STATIC_SHAPE_UNCHECKED_PROPERTY));
        for (CaptureField field : fields) field.register(builder);
        shape = builder.build(CapturedFrame.class, CapturedFrameFactory.class);
    }
    public int getStorageSize() { return offsets[offsets.length - 1]; }
    public int fieldWidth(int index) { return offsets[index + 1] - offsets[index]; }
    public boolean isVector(int index) { return fields[index].vector != null; }
    /** Declared frame carrier, or Illegal when the environment selects Long versus Object. */
    FrameSlotKind fixedFrameKind(int index) {
        var field = fields[index];
        if (field.exactInt != null) return FrameSlotKind.Int;
        if (field.exactLong) return FrameSlotKind.Long;
        if (field.exactFloat) return FrameSlotKind.Float;
        if (field.exactDouble) return FrameSlotKind.Double;
        return field.primitiveEligible ? FrameSlotKind.Illegal : FrameSlotKind.Object;
    }
    public CoreRepresentation vectorProof(int index) { return fields[index].vector == null ? null : fields[index].vector.getProof(); }
    public CoreRepresentation vectorProof$org_intelligence_thc(int index) { return vectorProof(index); }
    public Object checkAllocationKey(Object key) {
        if (key != allocationKey) throw fault("Invalid capture allocation key");
        return null;
    }
    @ExplodeLoop public CapturedFrame capture(VirtualFrame frame, int[] sourceSlots) {
        return capture(frame, sourceSlots, null);
    }
    @ExplodeLoop public CapturedFrame capture(VirtualFrame frame, int[] sourceSlots, ExecutableProgram program) {
        if (sourceSlots.length != getStorageSize()) throw new IllegalStateException("Check failed.");
        CapturedFrame environment = shape.getFactory().create(this, allocationKey, program);
        for (int i = 0; i < fields.length; i++) {
            CaptureField field = fields[i];
            if (field.vector != null) { field.vector.initialize(environment, frame, sourceSlots, offsets[i]); continue; }
            int slot = sourceSlots[offsets[i]];
            if (field.exactInt != null) {
                int value;
                if (frame.isInt(slot)) value = frame.getInt(slot);
                else if (frame.isObject(slot) && frame.getObject(slot) instanceof Integer number) value = number;
                else throw fault("Expected primitive Int capture");
                field.initializeInt(environment, value);
            }
            else if (field.exactLong) {
                long value;
                if (frame.isLong(slot)) value = frame.getLong(slot);
                else if (frame.isObject(slot) && frame.getObject(slot) instanceof Long number) value = number;
                else throw fault("Expected primitive Long capture");
                field.initializeLong(environment, value);
            }
            else if (field.exactFloat) {
                float value;
                if (frame.isFloat(slot)) value = frame.getFloat(slot);
                else if (frame.isObject(slot) && frame.getObject(slot) instanceof Float number) value = number;
                else throw fault("Expected primitive Float capture");
                field.initializeFloat(environment, value);
            }
            else if (field.exactDouble) {
                double value;
                if (frame.isDouble(slot)) value = frame.getDouble(slot);
                else if (frame.isObject(slot) && frame.getObject(slot) instanceof Double number) value = number;
                else throw fault("Expected primitive Double capture");
                field.initializeDouble(environment, value);
            }
            else field.initialize(environment, FrameAccess.read(frame, slot));
        }
        return environment;
    }
    @ExplodeLoop public CapturedFrame captureValues(Object[] values) {
        return captureValues(values, null);
    }
    @ExplodeLoop public CapturedFrame captureValues(Object[] values, ExecutableProgram program) {
        for (CaptureField field : fields) if (field.vector != null) throw new IllegalStateException("Vector captures require typed local sources");
        if (values.length != fields.length) throw new IllegalStateException("Check failed.");
        CapturedFrame environment = shape.getFactory().create(this, allocationKey, program);
        for (int i = 0; i < fields.length; i++) fields[i].initialize(environment, values[i]);
        return environment;
    }
    @ExplodeLoop public CapturedFrame captureLocals(BytecodeNode bytecode, VirtualFrame frame, LocalAccessor[] sourceSlots) {
        return captureLocals(bytecode, frame, sourceSlots, null);
    }
    @ExplodeLoop public CapturedFrame captureLocals(BytecodeNode bytecode, VirtualFrame frame, LocalAccessor[] sourceSlots,
                                                  ExecutableProgram program) {
        if (sourceSlots.length != getStorageSize()) throw new IllegalStateException("Check failed.");
        CapturedFrame environment = shape.getFactory().create(this, allocationKey, program);
        try { for (int i = 0; i < fields.length; i++) {
            CaptureField field = fields[i];
            int offset = offsets[i];
            if (field.vector != null) field.vector.initialize(environment, bytecode, frame, sourceSlots, offset);
            else if (field.exactInt != null) field.initializeInt(environment, sourceSlots[offset].getInt(bytecode, frame));
            else if (field.exactLong) field.initializeLong(environment, sourceSlots[offset].getLong(bytecode, frame));
            else if (field.exactFloat) field.initializeFloat(environment, sourceSlots[offset].getFloat(bytecode, frame));
            else if (field.exactDouble) field.initializeDouble(environment, sourceSlots[offset].getDouble(bytecode, frame));
            else field.initialize(environment, sourceSlots[offset].getObject(bytecode, frame));
        } } catch (com.oracle.truffle.api.nodes.UnexpectedResultException failure) { throw propagate(failure); }
        return environment;
    }
    @SuppressWarnings("unchecked")
    private static <E extends Throwable> RuntimeException propagate(Throwable failure) throws E { throw (E) failure; }
    private CaptureField checkedField(CapturedFrame environment, int index) {
        if (environment.getLayout() != this) throw fault("Captured frame does not match layout");
        if (index < 0 || index >= fields.length) throw fault("Invalid capture field index");
        return fields[index];
    }
    public Object read(CapturedFrame environment, int index) { return checkedField(environment, index).read(environment); }
    public Object inspect(CapturedFrame environment, int index) {
        CaptureField field = checkedField(environment, index);
        if (field.exactInt != null) return field.exactInt.widen(field.readInt(environment));
        if (field.vector != null) return field.vector.restoreRaw$org_intelligence_thc(environment);
        return field.read(environment);
    }
    public Object inspect$org_intelligence_thc(CapturedFrame environment, int index) { return inspect(environment, index); }
    public void restore(CapturedFrame environment, int index, Frame frame, int slot) { checkedField(environment, index).restore(environment, frame, slot); }
    public void restoreVector(CapturedFrame environment, int index, Frame frame, int[] slots, int offset) {
        OwnedVectorFields vector = checkedField(environment, index).vector;
        if (vector == null) throw fault("Capture is not a vector");
        vector.restore(environment, frame, slots, offset);
    }
    public void restoreVector(CapturedFrame environment, int index, BytecodeNode bytecode, VirtualFrame frame, LocalAccessor[] slots, int offset) {
        OwnedVectorFields vector = checkedField(environment, index).vector;
        if (vector == null) throw fault("Capture is not a vector");
        vector.restore(environment, bytecode, frame, slots, offset);
    }
    public boolean isInt(CapturedFrame environment, int index) { return checkedField(environment, index).exactInt != null; }
    public boolean isLong(CapturedFrame environment, int index) { return checkedField(environment, index).isLong(environment); }
    public boolean isObject(CapturedFrame environment, int index) { return checkedField(environment, index).isObject(environment); }
    public boolean isFloat(CapturedFrame environment, int index) { return checkedField(environment, index).exactFloat; }
    public boolean isDouble(CapturedFrame environment, int index) { return checkedField(environment, index).exactDouble; }
    public int readInt(CapturedFrame environment, int index) { return checkedField(environment, index).readInt(environment); }
    public long readLong(CapturedFrame environment, int index) { return checkedField(environment, index).readLong(environment); }
    public float readFloat(CapturedFrame environment, int index) { return checkedField(environment, index).readFloat(environment); }
    public double readDouble(CapturedFrame environment, int index) { return checkedField(environment, index).readDouble(environment); }
    public Object readObject(CapturedFrame environment, int index) { return checkedField(environment, index).readObject(environment); }
    private static final class CaptureField {
        private final int index;
        private final boolean primitiveEligible, exactLong, exactFloat, exactDouble;
        private final Class<?> exactReference;
        private final NarrowInteger exactInt;
        private final OwnedVectorFields vector;
        private final DefaultStaticProperty objectValue, primitiveValue, hasPrimitive;
        CaptureField(int index, boolean primitiveEligible, boolean exactLong, Class<?> exactReference,
                boolean exactFloat, boolean exactDouble, CoreRepresentation vectorProof, NarrowInteger exactInt) {
            this.index = index; this.primitiveEligible = primitiveEligible; this.exactLong = exactLong;
            this.exactReference = exactReference; this.exactFloat = exactFloat; this.exactDouble = exactDouble; this.exactInt = exactInt;
            vector = vectorProof == null ? null : new OwnedVectorFields(vectorProof, "capture_" + index);
            objectValue = new DefaultStaticProperty("capture_" + index + "_object");
            primitiveValue = new DefaultStaticProperty("capture_" + index + "_primitive");
            hasPrimitive = new DefaultStaticProperty("capture_" + index + "_tag");
        }
        void register(StaticShape.Builder builder) {
            if (vector != null) { vector.register(builder); return; }
            if (exactInt != null) builder.property(primitiveValue, exactInt.getStorageClass(), true);
            else if (exactFloat) builder.property(primitiveValue, float.class, true);
            else if (exactDouble) builder.property(primitiveValue, double.class, true);
            else if (!exactLong) builder.property(objectValue, exactReference == null ? Object.class : exactReference, true);
            if (primitiveEligible) {
                builder.property(primitiveValue, long.class, true);
                if (!exactLong) builder.property(hasPrimitive, boolean.class, true);
            }
        }
        void initializeInt(CapturedFrame storage, int value) { exactInt.write(primitiveValue, storage, value); }
        void initializeLong(CapturedFrame storage, long value) { primitiveValue.setLong(storage, value); }
        void initializeFloat(CapturedFrame storage, float value) { primitiveValue.setFloat(storage, value); }
        void initializeDouble(CapturedFrame storage, double value) { primitiveValue.setDouble(storage, value); }
        void initialize(CapturedFrame storage, Object value) {
            if (vector != null) throw fault("Vector capture requires vector-aware initialization");
            if (exactInt != null) {
                if (!(value instanceof Integer number)) throw fault("Expected primitive Int capture");
                initializeInt(storage, number);
            }
            else if (exactFloat) {
                if (!(value instanceof Float number)) throw fault("Expected primitive Float capture");
                initializeFloat(storage, number);
            }
            else if (exactDouble) {
                if (!(value instanceof Double number)) throw fault("Expected primitive Double capture");
                initializeDouble(storage, number);
            }
            else if (exactLong) {
                if (!(value instanceof Long number)) throw fault("Expected primitive Long capture");
                initializeLong(storage, number);
            }
            else if (primitiveEligible && value instanceof Long number) {
                primitiveValue.setLong(storage, number); hasPrimitive.setBoolean(storage, true);
            } else {
                objectValue.setObject(storage, value);
                if (primitiveEligible) hasPrimitive.setBoolean(storage, false);
            }
        }
        boolean isLong(CapturedFrame storage) { return exactLong || primitiveEligible && hasPrimitive.getBoolean(storage); }
        boolean isObject(CapturedFrame storage) {
            return vector == null && exactInt == null && !exactLong && !exactFloat && !exactDouble &&
                (!primitiveEligible || !hasPrimitive.getBoolean(storage));
        }
        FrameSlotKind kind(CapturedFrame storage) {
            if (vector != null) throw fault("Vector capture requires a typed destination");
            if (exactInt != null) return FrameSlotKind.Int;
            if (exactFloat) return FrameSlotKind.Float;
            if (exactDouble) return FrameSlotKind.Double;
            return isObject(storage) ? FrameSlotKind.Object : FrameSlotKind.Long;
        }
        Object read(CapturedFrame storage) {
            if (vector != null) throw fault("Vector capture requires a typed destination");
            if (exactInt != null) return exactInt.read(primitiveValue, storage);
            if (exactFloat) return primitiveValue.getFloat(storage);
            if (exactDouble) return primitiveValue.getDouble(storage);
            if (isObject(storage)) return objectValue.getObject(storage);
            return primitiveValue.getLong(storage);
        }
        void restore(CapturedFrame storage, Frame frame, int slot) {
            if (vector != null) throw fault("Vector capture requires a typed destination");
            if (exactInt != null) FrameAccess.writeInt(frame, slot, exactInt.read(primitiveValue, storage));
            else if (exactFloat) FrameAccess.writeFloat(frame, slot, primitiveValue.getFloat(storage));
            else if (exactDouble) FrameAccess.writeDouble(frame, slot, primitiveValue.getDouble(storage));
            else if (isObject(storage)) FrameAccess.write(frame, slot, objectValue.getObject(storage));
            else FrameAccess.writeLong(frame, slot, primitiveValue.getLong(storage));
        }
        int readInt(CapturedFrame storage) {
            if (!(exactInt != null)) throw FrameSlotTypeException.create(index, FrameSlotKind.Int, kind(storage));
            return exactInt.read(primitiveValue, storage);
        }
        long readLong(CapturedFrame storage) {
            if (!(isLong(storage))) throw FrameSlotTypeException.create(index, FrameSlotKind.Long, kind(storage));
            return primitiveValue.getLong(storage);
        }
        float readFloat(CapturedFrame storage) {
            if (!(exactFloat)) throw FrameSlotTypeException.create(index, FrameSlotKind.Float, kind(storage));
            return primitiveValue.getFloat(storage);
        }
        double readDouble(CapturedFrame storage) {
            if (!(exactDouble)) throw FrameSlotTypeException.create(index, FrameSlotKind.Double, kind(storage));
            return primitiveValue.getDouble(storage);
        }
        Object readObject(CapturedFrame storage) {
            if (!(isObject(storage))) throw FrameSlotTypeException.create(index, FrameSlotKind.Object, kind(storage));
            return objectValue.getObject(storage);
        }
    }
}
