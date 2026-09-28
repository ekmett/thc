// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.dsl.TypeCast;
import com.oracle.truffle.api.dsl.TypeCheck;
import com.oracle.truffle.api.dsl.TypeSystem;
import jdk.incubator.vector.*;
import thc.runtime.Unit;
import static thc.runtime.RuntimeFault.fault;

/**
 * Runtime categories used by typed AST execution. Guest Int#/Word#/Char# retain
 * their 64-bit payload; boxed constructors and unevaluated thunks stay distinct.
 * Numeric widening belongs to the particular guest operation, not a global cast.
 */
@TypeSystem({
        int.class,
        long.class,
        float.class,
        double.class,
        boolean.class,
        Closure.class,
        DataValue.class,
        ManagedAddress.class,
        Thunk.class,
        Unit.class
})
public abstract class RuntimeTypes {
    @TypeCheck(Unit.class)
    public static boolean isUnit(Object value) {
        return value == Unit.INSTANCE;
    }

    @TypeCast(Unit.class)
    public static Unit asUnit(Object value) {
        assert value == Unit.INSTANCE;
        return Unit.INSTANCE;
    }

    // Keep physical carrier checks independent of CoreVectors' proof registry:
    // prepared runtime graphs cannot contain metadata initialization checks.
    public static ByteVector requireByte(Object raw, VectorSpecies<Byte> species) {
        if (!(raw instanceof ByteVector value)) throw fault("Expected ByteVector");
        if (!value.species().equals(species)) throw fault("Unexpected vector species");
        return (ByteVector) CompilerDirectives.castExact(value, species.vectorType());
    }

    public static ShortVector requireShort(Object raw, VectorSpecies<Short> species) {
        if (!(raw instanceof ShortVector value)) throw fault("Expected ShortVector");
        if (!value.species().equals(species)) throw fault("Unexpected vector species");
        return (ShortVector) CompilerDirectives.castExact(value, species.vectorType());
    }

    public static IntVector requireInt(Object raw, VectorSpecies<Integer> species) {
        if (!(raw instanceof IntVector value)) throw fault("Expected IntVector");
        if (!value.species().equals(species)) throw fault("Unexpected vector species");
        return (IntVector) CompilerDirectives.castExact(value, species.vectorType());
    }

    public static LongVector requireLong(Object raw, VectorSpecies<Long> species) {
        if (!(raw instanceof LongVector value)) throw fault("Expected LongVector");
        if (!value.species().equals(species)) throw fault("Unexpected vector species");
        return (LongVector) CompilerDirectives.castExact(value, species.vectorType());
    }

    public static FloatVector requireFloat(Object raw, VectorSpecies<Float> species) {
        if (!(raw instanceof FloatVector value)) throw fault("Expected FloatVector");
        if (!value.species().equals(species)) throw fault("Unexpected vector species");
        return (FloatVector) CompilerDirectives.castExact(value, species.vectorType());
    }

    public static DoubleVector requireDouble(Object raw, VectorSpecies<Double> species) {
        if (!(raw instanceof DoubleVector value)) throw fault("Expected DoubleVector");
        if (!value.species().equals(species)) throw fault("Unexpected vector species");
        return (DoubleVector) CompilerDirectives.castExact(value, species.vectorType());
    }

    public static int laneIndex(long index, int lanes) {
        if (index < 0L || index >= (long) lanes) throw fault("Invalid vector lane index");
        return (int) index;
    }
}
