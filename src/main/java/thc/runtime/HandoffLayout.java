// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.oracle.truffle.api.staticobject.DefaultStaticProperty;
import com.oracle.truffle.api.staticobject.StaticShape;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import thc.Language;
import static thc.runtime.RuntimeServiceStatus.fault;

/** Physical generated fields, independent of logical Haskell argument identities. */
public final class HandoffLayout {
    private final int id;
    private final List<String> reps;
    @CompilationFinal(dimensions = 1) private final int[] kinds;
    @CompilationFinal(dimensions = 1) private final DefaultStaticProperty[] fields;
    @CompilationFinal(dimensions = 1) private final VectorLayout[] vectors;
    private final StaticShape<HandoffFactory> shape;
    public HandoffLayout(Language language, int id, List<String> reps) {
        this.id = id; this.reps = reps;
        kinds = new int[reps.size()]; fields = new DefaultStaticProperty[reps.size()]; vectors = new VectorLayout[reps.size()];
        StaticShape.Builder builder = StaticShape.newBuilder(language);
        for (int i = 0; i < reps.size(); i++) {
            String rep = reps.get(i);
            kinds[i] = switch (rep) {
                case "long" -> 0; case "float" -> 1; case "double" -> 2; case "reference" -> 3;
                case "int8-lane" -> 4; case "word8-lane" -> 5; case "int16-lane" -> 6; case "word16-lane" -> 7;
                case "int32-lane" -> 8; case "word32-lane" -> 9;
                default -> { if (!VECTOR_REPS.contains(rep)) throw fault("Invalid physical handoff field: " + rep); yield 10; }
            };
            fields[i] = new DefaultStaticProperty("handoff_" + i);
            if (VECTOR_REPS.contains(rep)) vectors[i] = VectorLayout.fromStorageRep(rep);
            Class<?> type = switch (kinds[i]) {
                case 0 -> long.class; case 1 -> float.class; case 2 -> double.class;
                case 4, 5 -> byte.class; case 6, 7 -> short.class; case 8, 9 -> int.class;
                case 10 -> vectors[i].getCarrierType(); default -> Object.class;
            };
            builder.property(fields[i], type, false);
        }
        shape = builder.build(HandoffStorage.class, HandoffFactory.class);
    }
    public int getId() { return id; }
    public List<String> getReps() { return reps; }
    public HandoffStorage create() { return shape.getFactory().create(this); }
    public boolean isInt(int index) { return kinds[index] >= 4 && kinds[index] <= 9; }
    public boolean isLong(int index) { return kinds[index] == 0; }
    public boolean isFloat(int index) { return kinds[index] == 1; }
    public boolean isDouble(int index) { return kinds[index] == 2; }
    public boolean isObject(int index) { return kinds[index] == 3 || kinds[index] == 10; }
    public int getInt(HandoffStorage storage, int index) {
        return switch (kinds[index]) {
            case 4 -> fields[index].getByte(storage);
            case 5 -> fields[index].getByte(storage) & 255;
            case 6 -> fields[index].getShort(storage);
            case 7 -> fields[index].getShort(storage) & 65535;
            case 8, 9 -> fields[index].getInt(storage);
            default -> throw fault("Expected Int handoff field");
        };
    }
    public long getLong(HandoffStorage storage, int index) {
        if (!isLong(index)) throw fault("Expected Long handoff field");
        return fields[index].getLong(storage);
    }
    public float getFloat(HandoffStorage storage, int index) { return fields[index].getFloat(storage); }
    public double getDouble(HandoffStorage storage, int index) { return fields[index].getDouble(storage); }
    public Object getObject(HandoffStorage storage, int index) { return fields[index].getObject(storage); }
    public void setObject(HandoffStorage storage, int index, Object value) {
        fields[index].setObject(storage, vectors[index] == null ? value : vectors[index].require(value));
    }
    public void setInt(HandoffStorage storage, int index, int value) {
        switch (kinds[index]) {
            case 4, 5 -> fields[index].setByte(storage, (byte) value);
            case 6, 7 -> fields[index].setShort(storage, (short) value);
            case 8, 9 -> fields[index].setInt(storage, value);
            default -> throw fault("Expected Int handoff field");
        }
    }
    public void setLong(HandoffStorage storage, int index, long value) {
        if (!isLong(index)) throw fault("Expected Long handoff field");
        fields[index].setLong(storage, value);
    }
    public void setFloat(HandoffStorage storage, int index, float value) { fields[index].setFloat(storage, value); }
    public void setDouble(HandoffStorage storage, int index, double value) { fields[index].setDouble(storage, value); }
    @ExplodeLoop public void copyIn(HandoffStorage storage, Object[] values) {
        if (values.length != fields.length) throw new IllegalStateException("Check failed.");
        for (int i = 0; i < fields.length; i++) {
            switch (kinds[i]) {
                case 0 -> {
                    if (!(values[i] instanceof Long value)) throw fault("Invalid Long handoff field");
                    setLong(storage, i, value);
                }
                case 4, 5, 6, 7, 8, 9 -> {
                    if (!(values[i] instanceof Integer value)) throw fault("Invalid Int handoff field");
                    setInt(storage, i, value);
                }
                case 1 -> {
                    if (!(values[i] instanceof Float value)) throw fault("Invalid Float handoff field");
                    fields[i].setFloat(storage, value);
                }
                case 2 -> {
                    if (!(values[i] instanceof Double value)) throw fault("Invalid Double handoff field");
                    fields[i].setDouble(storage, value);
                }
                default -> setObject(storage, i, values[i]);
            }
        }
    }
    public void clearReferences(HandoffStorage storage) {
        int width = fields.length;
        // Fixed fields specialize during PE; resumed dynamic layouts keep a counted clear.
        if (CompilerDirectives.isPartialEvaluationConstant(width)) clearFixed(storage, width);
        else for (int i = 0; i < width; i++) if (isObject(i)) fields[i].setObject(storage, null);
    }
    @ExplodeLoop private void clearFixed(HandoffStorage storage, int width) {
        for (int i = 0; i < width; i++) if (isObject(i)) fields[i].setObject(storage, null);
    }
    private static final Set<String> VECTOR_REPS;
    static {
        HashSet<String> vectors = new HashSet<>();
        String[] lanes = {"byte", "short", "int", "long", "float", "double"};
        int[] counts = {16, 8, 4, 2, 4, 2};
        for (int i = 0; i < lanes.length; i++) for (int multiplier : new int[] {1, 2, 4})
            vectors.add("Vector " + lanes[i] + " " + counts[i] * multiplier);
        VECTOR_REPS = Set.copyOf(vectors);
    }
    private static final Set<String> LONG_REPS = Set.of("IntRep", "WordRep", "Int8Rep", "Word8Rep",
        "Int16Rep", "Word16Rep", "Int32Rep", "Word32Rep", "Int64Rep", "Word64Rep");
    public static boolean supports(String rep) { return LONG_REPS.contains(rep) || rep.startsWith("BoxedRep "); }
    public static boolean supportsResult(String rep) { return supports(rep) || Set.of("FloatRep", "DoubleRep", "AddrRep").contains(rep); }
    public static String fieldKind(String rep) {
        if (VECTOR_REPS.contains(rep)) return rep;
        return switch (rep) {
            case "Int8Rep", "VectorLane Int8Rep" -> "int8-lane";
            case "Word8Rep", "VectorLane Word8Rep" -> "word8-lane";
            case "Int16Rep", "VectorLane Int16Rep" -> "int16-lane";
            case "Word16Rep", "VectorLane Word16Rep" -> "word16-lane";
            case "Int32Rep", "VectorLane Int32Rep" -> "int32-lane";
            case "Word32Rep", "VectorLane Word32Rep" -> "word32-lane";
            case "FloatRep" -> "float"; case "DoubleRep" -> "double"; case "AddrRep" -> "reference";
            default -> {
                if (LONG_REPS.contains(rep)) yield "long";
                if (rep.startsWith("BoxedRep ")) yield "reference";
                throw fault("Unsupported handoff representation: " + rep);
            }
        };
    }
}
