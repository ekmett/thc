// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.CompilerDirectives;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;

/** Native GHC slots and canonical JVM projections are distinct contracts. */
public final class SumShape {
    public static final SumShape INSTANCE = new SumShape();
    private SumShape() {}
    private static final String LIFTED = "BoxedRep (Just Lifted)", UNLIFTED = "BoxedRep (Just Unlifted)", POINTER = "BoxedRep Nothing";
    private static final List<String> ORDER = List.of(LIFTED, UNLIFTED, "WordRep", "Word64Rep", "FloatRep", "DoubleRep");
    private static final List<String> ELEMENTS = List.of("Int8ElemRep", "Int16ElemRep", "Int32ElemRep", "Int64ElemRep",
        "Word8ElemRep", "Word16ElemRep", "Word32ElemRep", "Word64ElemRep", "FloatElemRep", "DoubleElemRep");
    private static final Comparator<String> SLOT_ORDER = (left, right) -> {
        int l = ORDER.indexOf(left), r = ORDER.indexOf(right);
        if (l < 0) l = ORDER.size();
        if (r < 0) r = ORDER.size();
        if (l != r) return Integer.compare(l, r);
        if (l < ORDER.size()) return 0;
        String[] a = left.split(" ", -1), b = right.split(" ", -1);
        int count = Integer.compare(Integer.parseInt(a[1]), Integer.parseInt(b[1]));
        return count != 0 ? count : Integer.compare(ELEMENTS.indexOf(a[2]), ELEMENTS.indexOf(b[2]));
    };
    private static String slot(String rep) {
        return switch (rep) {
            case "IntRep", "WordRep", "Int8Rep", "Word8Rep", "Int16Rep", "Word16Rep", "Int32Rep", "Word32Rep", "AddrRep" -> "WordRep";
            case "Int64Rep", "Word64Rep" -> "Word64Rep";
            case LIFTED, UNLIFTED, "FloatRep", "DoubleRep" -> rep;
            default -> {
                if (!rep.startsWith("VecRep ")) throw new UnsupportedCore("Unsupported Core aggregate representation: unboxed-sum field " + rep);
                yield rep;
            }
        };
    }
    private static String fits(String left, String right) {
        if (left.equals(right)) return left;
        if ((left.equals("WordRep") || left.equals("Word64Rep")) && (right.equals("WordRep") || right.equals("Word64Rep"))) return "Word64Rep";
        return null;
    }
    private static List<String> merge(List<String> existing, List<String> needed) {
        var result = new ArrayList<String>();
        int left = 0, right = 0;
        while (left < existing.size() && right < needed.size()) {
            String common = fits(existing.get(left), needed.get(right));
            if (common != null) { result.add(common); left++; right++; }
            else if (SLOT_ORDER.compare(needed.get(right), existing.get(left)) < 0) result.add(needed.get(right++));
            else result.add(existing.get(left++));
        }
        result.addAll(existing.subList(left, existing.size()));
        result.addAll(needed.subList(right, needed.size()));
        return result;
    }
    public static void validate(CoreRepresentation proof) {
        NativeLayout nativeLayout = nativeLayout(proof);
        if (nativeLayout.projections != null && proof.getAlternativeSlots() == null)
            throw new UnsupportedCore("Unsupported Core aggregate representation: unboxed-sum lacks exact projection");
        if (!Objects.equals(nativeLayout.fields, proof.getPrimReps()) || !Integer.valueOf(0).equals(proof.getTagSlot()))
            throw fault("Sum physical representation or tag slot mismatch");
        if (!Objects.equals(nativeLayout.projections, proof.getAlternativeSlots())) throw fault("Sum alternative projection mismatch");
    }
    private record NativeLayout(List<String> fields, List<List<Integer>> projections) {}
    private static NativeLayout nativeLayout(CoreRepresentation proof) {
        List<CoreRepresentation> alternatives = proof.getAlternatives();
        if (alternatives == null) throw fault("Missing sum alternatives");
        if (proof.getKind() != CoreKind.UNKNOWN || proof.getComponents() != null || proof.getVector() != null) throw fault("Sum proof must retain its aggregate kind");
        if (alternatives.size() < 2) throw new UnsupportedCore("Unsupported Core aggregate representation: unboxed-sum requires at least two alternatives");
        var fields = new ArrayList<List<String>>();
        boolean unresolved = false;
        for (CoreRepresentation alternative : alternatives) {
            var row = new ArrayList<String>();
            List<String> nativeReps = nativePayload(alternative);
            if (nativeReps == null) unresolved = true;
            else for (String rep : nativeReps) {
                if (POINTER.equals(rep)) unresolved = true;
                else row.add(slot(rep));
            }
            fields.add(row);
        }
        // A known pointer is transportable, but unknown levity supplies no GHC slot.
        if (unresolved) return new NativeLayout(null, null);
        List<String> merged = List.of();
        for (List<String> row : fields) { var sorted = new ArrayList<>(row); sorted.sort(SLOT_ORDER); merged = merge(merged, sorted); }
        var physical = new ArrayList<String>(); physical.add("WordRep"); physical.addAll(merged);
        var expected = new ArrayList<List<Integer>>();
        for (List<String> row : fields) {
            var used = new HashSet<Integer>();
            var selected = new ArrayList<Integer>();
            for (String rep : row) {
                int found = -1;
                for (int i = 1; i < physical.size(); i++) if (!used.contains(i) && physical.get(i).equals(fits(rep, physical.get(i)))) { found = i; break; }
                if (found < 0) throw new NoSuchElementException("Collection contains no element matching the predicate.");
                selected.add(found); used.add(found);
            }
            expected.add(selected);
        }
        return new NativeLayout(physical, expected);
    }
    static CoreRepresentation relayout(CoreRepresentation proof) {
        NativeLayout layout = nativeLayout(proof);
        return proof.copy(proof.getKind(), proof.getEvaluated(), proof.getPresent(), layout.fields,
            null, null, proof.getAlternatives(), 0, layout.projections);
    }
    private static List<String> nativePayload(CoreRepresentation proof) {
        if (proof.isSum()) validate(proof);
        else if (proof.isTuple()) {
            if (proof.getKind() != CoreKind.UNKNOWN) throw fault("Sum tuple payload components disagree with physical representations");
            List<String> reps = new ArrayList<>();
            for (CoreRepresentation component : proof.getComponents()) {
                List<String> child = nativePayload(component);
                if (child == null) reps = null;
                else if (reps != null) reps.addAll(child);
            }
            if (!Objects.equals(proof.getPrimReps(), reps)) throw fault("Sum tuple payload components disagree with physical representations");
        } else if (proof.isVector()) VectorLayout.validate(proof);
        else if (proof.getKind() == CoreKind.VOID) {
            if (!List.of().equals(proof.getPrimReps())) throw fault("Sum void payload has registers");
        } else if (proof.getKind() == CoreKind.ADDRESS) {
            if (!List.of("AddrRep").equals(proof.getPrimReps()) || !proof.getEvaluated()) throw fault("Sum address payload requires an evaluated managed carrier");
        } else if (proof.getKind() != CoreKind.LONG && proof.getKind() != CoreKind.FLOAT && proof.getKind() != CoreKind.DOUBLE &&
                   proof.getKind() != CoreKind.DATA && proof.getKind() != CoreKind.CLOSURE && proof.getKind() != CoreKind.OBJECT)
            throw new UnsupportedCore("Unsupported Core aggregate representation: unboxed-sum payload");
        if (proof.getPrimReps() == null && !proof.isAggregate()) throw new UnsupportedCore("Unsupported Core aggregate representation: unboxed-sum unresolved field");
        return proof.getPrimReps();
    }
    public static final class Transport {
        private final List<CoreRepresentation> fields;
        private final List<List<Integer>> projections;
        public Transport(List<CoreRepresentation> fields, List<List<Integer>> projections) {
            this.fields = fields; this.projections = projections;
        }
        public List<CoreRepresentation> getFields() { return fields; }
        public List<List<Integer>> getProjections() { return projections; }
    }
    private record Register(String representation, int ordinal) {}
    private static final List<String> JVM_ORDER = List.of(POINTER, "WordRep", "AddrRep", "FloatRep", "DoubleRep");
    private static String storageRep(CoreRepresentation proof) {
        if (proof.hasBoxedPointer()) return POINTER;
        if (proof.getKind() == CoreKind.LONG) return "WordRep";
        return proof.getPrimReps().getFirst();
    }
    public static Transport transport(CoreRepresentation proof) {
        validate(proof);
        var registers = new LinkedHashMap<Register, CoreRepresentation>();
        var alternatives = new ArrayList<List<Register>>();
        for (CoreRepresentation alternative : proof.getAlternatives()) {
            List<CoreRepresentation> payload = TupleShape.flatten(alternative);
            var row = new ArrayList<Register>();
            var counts = new LinkedHashMap<String, Integer>();
            for (CoreRepresentation leaf : payload) {
                String rep = storageRep(leaf);
                int ordinal = counts.getOrDefault(rep, 0);
                counts.put(rep, ordinal + 1);
                Register key = new Register(rep, ordinal);
                registers.putIfAbsent(key, leaf.getKind() == CoreKind.ADDRESS ? leaf : scalarStorage(rep));
                row.add(key);
            }
            alternatives.add(row);
        }
        var keys = new ArrayList<>(registers.keySet());
        keys.sort((left, right) -> {
            int l = JVM_ORDER.indexOf(left.representation), r = JVM_ORDER.indexOf(right.representation);
            if (l < 0) l = JVM_ORDER.size();
            if (r < 0) r = JVM_ORDER.size();
            int order = Integer.compare(l, r);
            if (order == 0 && l == JVM_ORDER.size()) order = SLOT_ORDER.compare(left.representation, right.representation);
            return order != 0 ? order : Integer.compare(left.ordinal, right.ordinal);
        });
        var fields = new ArrayList<CoreRepresentation>(); fields.add(scalarStorage("WordRep"));
        for (Register key : keys) fields.add(registers.get(key));
        var projections = new ArrayList<List<Integer>>();
        for (List<Register> row : alternatives) {
            var projection = new ArrayList<Integer>();
            for (Register key : row) projection.add(1 + keys.indexOf(key));
            projections.add(projection);
        }
        return new Transport(fields, projections);
    }
    private static CoreRepresentation scalarStorage(String rep) {
        CoreVector vector = null;
        if (rep.startsWith("VecRep ")) { String[] pieces = rep.split(" ", -1); vector = new CoreVector(Integer.parseInt(pieces[1]), pieces[2]); }
        CoreKind kind = vector != null ? CoreKind.VECTOR : switch (rep) {
            case "WordRep", "Word64Rep" -> CoreKind.LONG;
            case "FloatRep" -> CoreKind.FLOAT; case "DoubleRep" -> CoreKind.DOUBLE;
            default -> CoreKind.OBJECT;
        };
        return new CoreRepresentation(kind, kind != CoreKind.OBJECT, true, List.of(rep), null, vector, null, null, null);
    }
    public static List<CoreRepresentation> storage(CoreRepresentation proof) { return transport(proof).fields; }
    public static List<Integer> projection(CoreRepresentation proof, int alternative) { return transport(proof).projections.get(alternative); }
    private static boolean exact(Object value, int expected) { return (value instanceof Long || value instanceof Integer) && ((Number) value).longValue() == (long) expected; }
    public static int constructor(CoreRepresentation proof, Map<String, ?> info, Object arity) {
        if (info == null || !"unboxed-sum".equals(info.get("kind")) || !exact(info.get("arity"), 1) || !exact(arity, 1) || !exact(info.get("sumArity"), proof.getAlternatives().size())) throw fault("Sum constructor family or payload arity mismatch");
        Object raw = info.get("tag");
        if (!(raw instanceof Long) && !(raw instanceof Integer)) throw fault("Invalid sum constructor tag");
        Number number = (Number) raw;
        int tag = number.intValue();
        if (number.doubleValue() != (double) tag || tag < 1 || tag > proof.getAlternatives().size()) throw fault("Invalid sum constructor tag");
        return tag;
    }
    public static void payload(CoreRepresentation expected, CoreRepresentation actual) {
        if (!actual.getPresent() || !TupleShape.Companion.compatible(expected, actual)) throw fault("Sum payload logical shape mismatch");
    }
    public static void payload(CoreRepresentation expected, CoreRepresentation actual, Boolean liftedFlag) {
        payload(expected, actual);
        if (liftedFlag == null) {
            if (!actual.hasUnknownBoxedLevity()) throw fault("Sum payload levity mismatch");
        } else if (!expected.hasUnknownBoxedLevity() && liftedFlag != (!expected.isAggregate() && List.of(LIFTED).equals(expected.getPrimReps())) ||
                   !actual.hasUnknownBoxedLevity() && liftedFlag != (!actual.isAggregate() && List.of(LIFTED).equals(actual.getPrimReps())))
            throw fault("Sum payload levity mismatch");
    }
    public static int checkedTag(long value, int arity) {
        if (arity < 2 || value < 1L || value > (long) arity) throw fault("Invalid unboxed sum tag");
        return (int) value;
    }
    private static RuntimeFault fault(String message) { CompilerDirectives.transferToInterpreterAndInvalidate(); return new RuntimeFault(message); }
}
