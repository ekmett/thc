// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import jdk.incubator.vector.Vector;

/** THC's detached word image, not the GHC or JVM heap ABI. Pointer words are zero;
 * primitives retain raw native-endian bits and vectors their actual species width. */
public final class ClosureInspection {
    private ClosureInspection() {}
    private static void field(List<Object> payload, List<Boolean> references, Object value, boolean reference) {
        // Inactive sum reference slots retain their word position, but are not guest pointers.
        payload.add(reference && value == null ? 0L : value);
        references.add(reference && value != null);
    }
    private static void captures(List<Object> payload, List<Boolean> references, CapturedFrame environment) {
        if (environment != null) for (int i = 0; i < environment.getLayout().getStorageSize(); i++)
            field(payload, references, environment.getLayout().inspect(environment, i), environment.getLayout().isObject(environment, i));
    }
    @TruffleBoundary public static ClosureImage image(Object value) {
        List<Object> payload = new ArrayList<>();
        List<Boolean> references = new ArrayList<>();
        String descriptor;
        long tag;
        if (value instanceof DataValue data) {
            var layout = data.getLayout();
            descriptor = "constructor " + layout.getId(); tag = 1;
            for (int i = 0; i < layout.getArity(); i++) {
                if (layout.inactiveSumReference(data, i)) field(payload, references, 0L, false);
                else if (layout.fieldWidth(i) != 0) field(payload, references, layout.inspect(data, i),
                    !layout.isInt(i) && !layout.isLong(i) && !layout.isFloat(i) && !layout.isDouble(i) && !layout.isVector(i));
            }
        } else if (value instanceof Closure closure) {
            descriptor = "function " + closure.target.getRootNode().getName() + " arity " + closure.arity + " supplied " + closure.suppliedCount;
            tag = 2; captures(payload, references, closure.environment);
            HandoffStorage typed = closure.typedSupplied;
            if (typed == null) for (int i = 0; i < closure.suppliedCount; i++) field(payload, references, closure.supplied[i], true);
            else for (int i = 0; i < typed.getLayout().getReps().size(); i++) {
                var layout = typed.getLayout();
                Object item;
                if (layout.isInt(i)) item = Objects.requireNonNull(NarrowInteger.fromRep(layout.getReps().get(i))).widen(layout.getInt(typed, i));
                else if (layout.isLong(i)) item = layout.getLong(typed, i);
                else if (layout.isFloat(i)) item = layout.getFloat(typed, i);
                else if (layout.isDouble(i)) item = layout.getDouble(typed, i);
                else item = layout.getObject(typed, i);
                field(payload, references, item, layout.getReps().get(i).equals("reference"));
            }
        } else if (value instanceof Thunk thunk) {
            synchronized (thunk.getMonitor()) {
                descriptor = "thunk state " + thunk.getState(); tag = 3;
                // Never wait for, enter or rethrow a thunk. Runtime metadata is not a guest pointer.
                if (thunk.getState() == 2) field(payload, references, thunk.getValue(), true);
                else captures(payload, references, thunk.getEnvironment());
            }
        } else if (value instanceof Integer number) { descriptor = "boxed Int32"; tag = 4; field(payload, references, number.longValue(), false); }
        else if (value instanceof Long) { descriptor = "boxed Int64"; tag = 4; field(payload, references, value, false); }
        else if (value instanceof Float) { descriptor = "boxed Float32"; tag = 5; field(payload, references, value, false); }
        else if (value instanceof Double) { descriptor = "boxed Float64"; tag = 6; field(payload, references, value, false); }
        else { descriptor = "opaque " + (value == null ? "null" : value.getClass().getName()); tag = 0; }
        int size = 8;
        for (int i = 0; i < payload.size(); i++) size += !references.get(i) && payload.get(i) instanceof Vector<?> vector ? vector.bitSize() / 8 : 8;
        byte[] bytes = new byte[size];
        ByteBuffer words = ByteBuffer.wrap(bytes).order(ByteOrder.nativeOrder());
        List<Object> pointers = new ArrayList<>();
        words.putLong(tag);
        for (int i = 0; i < payload.size(); i++) {
            Object item = payload.get(i);
            if (references.get(i)) { words.putLong(0); pointers.add(item); continue; }
            if (item instanceof Long number) words.putLong(number);
            else if (item instanceof Float number) { words.putInt(Float.floatToRawIntBits(number)); words.putInt(0); }
            else if (item instanceof Double number) words.putLong(Double.doubleToRawLongBits(number));
            else if (item instanceof Vector<?> vector) words.put(VectorMemory.asBytes(vector).toArray());
            else throw RuntimeFault.fault("Invalid primitive closure field");
        }
        return new ClosureImage(descriptor, bytes, pointers.toArray());
    }
    public static long size(Object value) { return image(value).getBytes().length / 8L; }
}
