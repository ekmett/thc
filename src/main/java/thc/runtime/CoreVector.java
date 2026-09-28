// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Vector identity is one VecRep, independent of its physical runtime value. */
public record CoreVector(int lanes, String element) {
    public CoreVector { Objects.requireNonNull(element); }
    public int getLanes() { return lanes; }
    public String getElement() { return element; }
    @Override public String toString() { return "CoreVector(lanes=" + lanes + ", element=" + element + ")"; }

    public static final CoreVector INT64X2 = new CoreVector(2, "Int64ElemRep");
    public static final CoreVector INT32X4 = new CoreVector(4, "Int32ElemRep");
    public static final CoreVector INT16X8 = new CoreVector(8, "Int16ElemRep");
    public static final CoreVector INT8X16 = new CoreVector(16, "Int8ElemRep");
    public static final CoreVector WORD8X16 = new CoreVector(16, "Word8ElemRep");
    public static final CoreVector WORD16X8 = new CoreVector(8, "Word16ElemRep");
    public static final CoreVector WORD32X4 = new CoreVector(4, "Word32ElemRep");
    public static final CoreVector FLOATX4 = new CoreVector(4, "FloatElemRep");
    public static final CoreVector DOUBLEX2 = new CoreVector(2, "DoubleElemRep");

    public static CoreVector parse(Object raw, CoreKind kind, List<String> reps, boolean aggregate) {
        if (kind != CoreKind.VECTOR) {
            // Validate a redundant physical VecRep on a logical aggregate without
            // changing its logical representation into a scalar vector.
            if (aggregate && raw != null) { parse(raw, CoreKind.VECTOR, reps, false); return null; }
            boolean vectorRep = false;
            if (raw == null && !aggregate && reps != null) {
                for (String rep : reps) if (rep.startsWith("VecRep ")) { vectorRep = true; break; }
            }
            if (raw != null || vectorRep) throw new RuntimeFault("Vector representation lacks exact vector metadata");
            return null;
        }
        if (!(raw instanceof Map<?, ?> record)) throw new RuntimeFault("Missing Core vector shape");
        if (!(record.get("lanes") instanceof Number lanes)) throw new RuntimeFault("Invalid vector lane count");
        if (!(lanes instanceof Integer || lanes instanceof Long)) throw new RuntimeFault("Invalid vector lane count");
        if (!(record.get("element") instanceof String element)) throw new RuntimeFault("Invalid vector element kind");
        if (lanes.doubleValue() != (double) lanes.intValue() || aggregate) throw new RuntimeFault("Invalid Core vector shape");
        var vector = new CoreVector(lanes.intValue(), element);
        if (!List.of("VecRep " + vector.lanes + " " + element).equals(reps))
            throw new RuntimeFault("Vector shape disagrees with primitive representation");
        if (!vector.equals(INT64X2) && !vector.equals(INT32X4) && !vector.equals(INT16X8) && !vector.equals(INT8X16)
                && !vector.equals(WORD8X16) && !vector.equals(WORD16X8) && !vector.equals(WORD32X4)
                && !vector.equals(FLOATX4) && !vector.equals(DOUBLEX2) && !GeneratedVectors.supports(vector))
            throw new UnsupportedCore("Unsupported Core vector representation: " + vector);
        return vector;
    }
}
