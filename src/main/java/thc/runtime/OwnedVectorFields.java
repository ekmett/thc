// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.bytecode.BytecodeNode;
import com.oracle.truffle.api.bytecode.LocalAccessor;
import com.oracle.truffle.api.frame.Frame;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.oracle.truffle.api.staticobject.DefaultStaticProperty;
import com.oracle.truffle.api.staticobject.StaticShape;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import jdk.incubator.vector.*;

/** A logical vector owns final primitive properties in its enclosing heap object. */
public final class OwnedVectorFields {
    private final CoreRepresentation proof;
    private final VectorLayout transport;
    private final int lanes;
    private final String lane;
    private final NarrowInteger narrowLane;
    @CompilationFinal(dimensions = 1) private final DefaultStaticProperty[] properties;
    public OwnedVectorFields(CoreRepresentation proof, String name) {
        VectorLayout.validate(proof);
        this.proof = proof;
        transport = new VectorLayout(proof);
        lanes = proof.getVector().getLanes();
        lane = VectorLayout.laneProof(proof.getVector()).getPrimReps().getFirst();
        properties = new DefaultStaticProperty[lanes];
        for (int i = 0; i < lanes; i++) properties[i] = new DefaultStaticProperty(name + "_lane_" + i);
        narrowLane = NarrowInteger.fromRep(lane);
    }
    public CoreRepresentation getProof() { return proof; }
    public int getLanes() { return lanes; }
    public void register(StaticShape.Builder builder) {
        Class<?> type = switch (lane) {
            case "Int8Rep", "Word8Rep" -> byte.class;
            case "Int16Rep", "Word16Rep" -> short.class;
            case "Int32Rep", "Word32Rep" -> int.class;
            case "Int64Rep", "Word64Rep" -> long.class;
            case "FloatRep" -> float.class; case "DoubleRep" -> double.class;
            default -> { com.oracle.truffle.api.CompilerDirectives.transferToInterpreterAndInvalidate(); throw new RuntimeFault("Invalid owned vector lane"); }
        };
        for (DefaultStaticProperty property : properties) builder.property(property, type, true);
    }
    private NarrowInteger narrow() {
        if (narrowLane == null) throw new IllegalStateException("Required value was null.");
        return narrowLane;
    }
    private void putInt(Object owner, int index, int value) { narrow().write(properties[index], owner, value); }
    private int getInt(Object owner, int index) { return narrow().read(properties[index], owner); }
    private static void checkSlots(int size, int offset) {
        if (offset < 0 || offset >= size) throw new IllegalArgumentException("Invalid owned vector transport slot");
    }
    @ExplodeLoop private void initializeRaw(Object owner, Object raw) {
        Vector<?> value = transport.require(raw);
        for (int i = 0; i < lanes; i++) {
            switch (value) {
                case ByteVector vector -> putInt(owner, i, vector.lane(i));
                case ShortVector vector -> putInt(owner, i, vector.lane(i));
                case IntVector vector -> putInt(owner, i, vector.lane(i));
                case LongVector vector -> properties[i].setLong(owner, vector.lane(i));
                case FloatVector vector -> properties[i].setFloat(owner, vector.lane(i));
                case DoubleVector vector -> properties[i].setDouble(owner, vector.lane(i));
                default -> { com.oracle.truffle.api.CompilerDirectives.transferToInterpreterAndInvalidate(); throw new RuntimeFault("Unsupported raw vector carrier"); }
            }
        }
    }
    @SuppressWarnings("unchecked") @ExplodeLoop public Object restoreRaw(Object owner) {
        switch (lane) {
            case "Int8Rep", "Word8Rep" -> {
                ByteVector value = ByteVector.broadcast((VectorSpecies<Byte>) transport.getSpecies(), (byte) getInt(owner, 0));
                for (int i = 1; i < lanes; i++) value = value.withLane(i, (byte) getInt(owner, i));
                return value;
            }
            case "Int16Rep", "Word16Rep" -> {
                ShortVector value = ShortVector.broadcast((VectorSpecies<Short>) transport.getSpecies(), (short) getInt(owner, 0));
                for (int i = 1; i < lanes; i++) value = value.withLane(i, (short) getInt(owner, i));
                return value;
            }
            case "Int32Rep", "Word32Rep" -> {
                IntVector value = IntVector.broadcast((VectorSpecies<Integer>) transport.getSpecies(), getInt(owner, 0));
                for (int i = 1; i < lanes; i++) value = value.withLane(i, getInt(owner, i));
                return value;
            }
            case "Int64Rep", "Word64Rep" -> {
                LongVector value = LongVector.broadcast((VectorSpecies<Long>) transport.getSpecies(), properties[0].getLong(owner));
                for (int i = 1; i < lanes; i++) value = value.withLane(i, properties[i].getLong(owner));
                return value;
            }
            case "FloatRep" -> {
                FloatVector value = FloatVector.broadcast((VectorSpecies<Float>) transport.getSpecies(), properties[0].getFloat(owner));
                for (int i = 1; i < lanes; i++) value = value.withLane(i, properties[i].getFloat(owner));
                return value;
            }
            case "DoubleRep" -> {
                DoubleVector value = DoubleVector.broadcast((VectorSpecies<Double>) transport.getSpecies(), properties[0].getDouble(owner));
                for (int i = 1; i < lanes; i++) value = value.withLane(i, properties[i].getDouble(owner));
                return value;
            }
            default -> { com.oracle.truffle.api.CompilerDirectives.transferToInterpreterAndInvalidate(); throw new RuntimeFault("Unsupported owned vector lane"); }
        }
    }
    public Object restoreRaw$org_intelligence_thc(Object owner) { return restoreRaw(owner); }
    public void initialize(Object owner, Frame frame, int[] slots, int offset) {
        checkSlots(slots.length, offset);
        initializeRaw(owner, frame.getObject(slots[offset]));
    }
    public void copy(Object source, Object target) {
        for (int i = 0; i < lanes; i++) switch (lane) {
            case "FloatRep" -> properties[i].setFloat(target, properties[i].getFloat(source));
            case "DoubleRep" -> properties[i].setDouble(target, properties[i].getDouble(source));
            case "Int64Rep", "Word64Rep" -> properties[i].setLong(target, properties[i].getLong(source));
            default -> putInt(target, i, getInt(source, i));
        }
    }
    /** Images retain exact lane bits, never Vector API objects. */
    public void writeImage(Object owner, DataOutputStream output) throws IOException {
        for (int i = 0; i < lanes; i++) switch (lane) {
            case "FloatRep" -> output.writeInt(Float.floatToRawIntBits(properties[i].getFloat(owner)));
            case "DoubleRep" -> output.writeLong(Double.doubleToRawLongBits(properties[i].getDouble(owner)));
            case "Int64Rep", "Word64Rep" -> output.writeLong(properties[i].getLong(owner));
            default -> output.writeLong(narrow().widen(getInt(owner, i)));
        }
    }
    public void readImage(Object owner, DataInputStream input) throws IOException {
        for (int i = 0; i < lanes; i++) switch (lane) {
            case "FloatRep" -> properties[i].setFloat(owner, Float.intBitsToFloat(input.readInt()));
            case "DoubleRep" -> properties[i].setDouble(owner, Double.longBitsToDouble(input.readLong()));
            case "Int64Rep", "Word64Rep" -> properties[i].setLong(owner, input.readLong());
            default -> putInt(owner, i, (int) input.readLong());
        }
    }
    public void restore(Object owner, Frame frame, int[] slots, int offset) {
        checkSlots(slots.length, offset);
        FrameAccess.writeObject(frame, slots[offset], restoreRaw(owner));
    }
    public void initialize(Object owner, BytecodeNode bytecode, VirtualFrame frame, LocalAccessor[] slots, int offset) {
        checkSlots(slots.length, offset);
        initializeRaw(owner, slots[offset].getObject(bytecode, frame));
    }
    public void restore(Object owner, BytecodeNode bytecode, VirtualFrame frame, LocalAccessor[] slots, int offset) {
        checkSlots(slots.length, offset);
        slots[offset].setObject(bytecode, frame, restoreRaw(owner));
    }
}
