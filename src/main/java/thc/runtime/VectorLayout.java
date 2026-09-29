// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.bytecode.BytecodeNode;
import com.oracle.truffle.api.bytecode.LocalAccessor;
import com.oracle.truffle.api.frame.VirtualFrame;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import jdk.incubator.vector.*;

/** An exact VecRep occupies one raw Vector API reference in activation transport. */
public final class VectorLayout {
    private final CoreRepresentation proof;
    private final CoreVector vector;
    private final int lanes;
    private final CoreRepresentation lane;
    private final Class<?> carrierType;
    private final Class<? extends Vector<?>> exactType;
    public VectorLayout(CoreRepresentation proof) {
        validate(proof);
        this.proof = proof;
        vector = proof.getVector();
        lanes = vector.getLanes();
        lane = laneProof(vector);
        carrierType = switch (vector.getElement()) {
            case "Int8ElemRep", "Word8ElemRep" -> ByteVector.class;
            case "Int16ElemRep", "Word16ElemRep" -> ShortVector.class;
            case "Int32ElemRep", "Word32ElemRep" -> IntVector.class;
            case "Int64ElemRep", "Word64ElemRep" -> LongVector.class;
            case "FloatElemRep" -> FloatVector.class;
            case "DoubleElemRep" -> DoubleVector.class;
            default -> { com.oracle.truffle.api.CompilerDirectives.transferToInterpreterAndInvalidate(); throw new RuntimeFault("Unknown vector lane representation"); }
        };
        // Retain the exact physical carrier, not runtime-initialized JDK species
        // metadata. Prepared layouts and their compiled checks must be persistable.
        exactType = getSpecies().vectorType();
    }
    public CoreRepresentation getProof() { return proof; }
    public CoreVector getVector() { return vector; }
    public int getLanes() { return lanes; }
    public int getWidth() { return 1; }
    public CoreRepresentation getLane() { return lane; }
    public Class<?> getCarrierType() { return carrierType; }
    public VectorSpecies<?> getSpecies() {
        int elementBits = switch (vector.getElement()) {
            case "Int8ElemRep", "Word8ElemRep" -> 8;
            case "Int16ElemRep", "Word16ElemRep" -> 16;
            case "Int32ElemRep", "Word32ElemRep", "FloatElemRep" -> 32;
            default -> 64;
        };
        VectorShape shape = VectorShape.forBitSize(lanes * elementBits);
        return switch (vector.getElement()) {
            case "Int8ElemRep", "Word8ElemRep" -> ByteVector.SPECIES_128.withShape(shape);
            case "Int16ElemRep", "Word16ElemRep" -> ShortVector.SPECIES_128.withShape(shape);
            case "Int32ElemRep", "Word32ElemRep" -> IntVector.SPECIES_128.withShape(shape);
            case "Int64ElemRep", "Word64ElemRep" -> LongVector.SPECIES_128.withShape(shape);
            case "FloatElemRep" -> FloatVector.SPECIES_128.withShape(shape);
            case "DoubleElemRep" -> DoubleVector.SPECIES_128.withShape(shape);
            default -> { com.oracle.truffle.api.CompilerDirectives.transferToInterpreterAndInvalidate(); throw new RuntimeFault("Unknown vector lane representation"); }
        };
    }
    public Vector<?> require(Object value) {
        if (!(value instanceof Vector<?> raw)) { com.oracle.truffle.api.CompilerDirectives.transferToInterpreterAndInvalidate(); throw new RuntimeFault("Expected raw vector carrier"); }
        if (!exactType.isInstance(raw)) { com.oracle.truffle.api.CompilerDirectives.transferToInterpreterAndInvalidate(); throw new RuntimeFault("Vector carrier species disagrees with VecRep"); }
        return CompilerDirectives.castExact(raw, exactType);
    }
    public void write(VirtualFrame frame, int[] slots, int offset, Object value) { FrameAccess.writeObject(frame, slots[offset], require(value)); }
    public Object read(VirtualFrame frame, int[] slots, int offset) { return require(frame.getObject(slots[offset])); }
    public void write(BytecodeNode bytecode, VirtualFrame frame, LocalAccessor[] slots, int offset, Object value) {
        slots[offset].setObject(bytecode, frame, require(value));
    }
    public Object read(BytecodeNode bytecode, VirtualFrame frame, LocalAccessor[] slots, int offset) { return require(slots[offset].getObject(bytecode, frame)); }
    public void copy(VirtualFrame frame, int[] from, int sourceOffset, int[] into, int targetOffset) {
        write(frame, into, targetOffset, read(frame, from, sourceOffset));
    }
    public static VectorLayout fromStorageRep(String rep) {
        String[] parts = rep.split(" ", -1);
        if (parts.length != 3 || !parts[0].equals("Vector")) throw new IllegalArgumentException("Failed requirement.");
        String element = switch (parts[1]) {
            case "byte" -> "Int8ElemRep"; case "short" -> "Int16ElemRep";
            case "int" -> "Int32ElemRep"; case "long" -> "Int64ElemRep";
            case "float" -> "FloatElemRep"; case "double" -> "DoubleElemRep";
            default -> { com.oracle.truffle.api.CompilerDirectives.transferToInterpreterAndInvalidate(); throw new RuntimeFault("Invalid physical vector lane"); }
        };
        int count = Integer.parseInt(parts[2]);
        return new VectorLayout(new CoreRepresentation(CoreKind.VECTOR, true, true,
            List.of("VecRep " + count + " " + element), null, new CoreVector(count, element), null, null, null));
    }
    public static void validate(CoreRepresentation proof) {
        CoreVector vector = proof.getVector();
        if (vector == null || !proof.getPresent() || proof.getKind() != CoreKind.VECTOR || proof.isAggregate() ||
            !List.of("VecRep " + vector.getLanes() + " " + vector.getElement()).equals(proof.getPrimReps()))
            { com.oracle.truffle.api.CompilerDirectives.transferToInterpreterAndInvalidate(); throw new RuntimeFault("Vector transport requires an exact VecRep"); }
        CoreVector.parse(Map.of("lanes", vector.getLanes(), "element", vector.getElement()), proof.getKind(), proof.getPrimReps(), false);
    }
    private static String laneRep(CoreVector vector) {
        return switch (vector.getElement()) {
            case "Int8ElemRep" -> "Int8Rep"; case "Word8ElemRep" -> "Word8Rep";
            case "Int16ElemRep" -> "Int16Rep"; case "Word16ElemRep" -> "Word16Rep";
            case "Int32ElemRep" -> "Int32Rep"; case "Word32ElemRep" -> "Word32Rep";
            case "Int64ElemRep" -> "Int64Rep"; case "Word64ElemRep" -> "Word64Rep";
            case "FloatElemRep" -> "FloatRep"; case "DoubleElemRep" -> "DoubleRep";
            default -> { com.oracle.truffle.api.CompilerDirectives.transferToInterpreterAndInvalidate(); throw new RuntimeFault("Unknown vector lane representation"); }
        };
    }
    public static CoreRepresentation laneProof(CoreVector vector) {
        String rep = laneRep(vector);
        CoreKind kind = switch (rep) { case "FloatRep" -> CoreKind.FLOAT; case "DoubleRep" -> CoreKind.DOUBLE; default -> CoreKind.LONG; };
        return new CoreRepresentation(kind, true, true, List.of(rep), null, null, null, null, null);
    }
    public static List<String> storageReps(CoreRepresentation proof) {
        if (proof.getComponents() != null || proof.isSum()) {
            var result = new ArrayList<String>();
            for (CoreRepresentation field : proof.getComponents() != null ? proof.getComponents() : SumShape.INSTANCE.storage(proof)) result.addAll(storageReps(field));
            return result;
        }
        if (proof.getKind() == CoreKind.VOID) return List.of();
        if (proof.isVector()) {
            validate(proof);
            CoreVector vector = proof.getVector();
            String physical = switch (vector.getElement()) {
                case "Int8ElemRep", "Word8ElemRep" -> "byte";
                case "Int16ElemRep", "Word16ElemRep" -> "short";
                case "Int32ElemRep", "Word32ElemRep" -> "int";
                case "Int64ElemRep", "Word64ElemRep" -> "long";
                case "FloatElemRep" -> "float"; case "DoubleElemRep" -> "double";
                default -> { com.oracle.truffle.api.CompilerDirectives.transferToInterpreterAndInvalidate(); throw new RuntimeFault("Unknown vector lane representation"); }
            };
            return List.of("Vector " + physical + " " + vector.getLanes());
        }
        List<String> reps = proof.getPrimReps();
        if (reps == null || reps.size() != 1) { com.oracle.truffle.api.CompilerDirectives.transferToInterpreterAndInvalidate(); throw new RuntimeFault("Unresolved typed storage leaf"); }
        return List.of(reps.getFirst());
    }
}
