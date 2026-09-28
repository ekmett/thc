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

/** Logical alternatives retain GHC's native slots; narrow payloads convert at projections. */
public final class SumShape {
    public static final SumShape INSTANCE = new SumShape();
    private SumShape() {}
    private static final String LIFTED = "BoxedRep (Just Lifted)", UNLIFTED = "BoxedRep (Just Unlifted)";
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
        List<CoreRepresentation> alternatives = proof.getAlternatives();
        if (alternatives == null) throw fault("Missing sum alternatives");
        if (proof.getKind() != CoreKind.UNKNOWN || proof.getComponents() != null || proof.getVector() != null) throw fault("Sum proof must retain its aggregate kind");
        if (alternatives.size() < 2) throw new UnsupportedCore("Unsupported Core aggregate representation: unboxed-sum requires at least two alternatives");
        var fields = new ArrayList<List<String>>();
        for (CoreRepresentation alternative : alternatives) {
            var row = new ArrayList<String>();
            for (String rep : nativePayload(alternative)) row.add(slot(rep));
            fields.add(row);
        }
        List<String> merged = List.of();
        for (List<String> row : fields) { var sorted = new ArrayList<>(row); sorted.sort(SLOT_ORDER); merged = merge(merged, sorted); }
        var physical = new ArrayList<String>(); physical.add("WordRep"); physical.addAll(merged);
        if (!physical.equals(proof.getPrimReps()) || !Integer.valueOf(0).equals(proof.getTagSlot())) throw fault("Sum physical representation or tag slot mismatch");
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
        if (!expected.equals(proof.getAlternativeSlots())) throw fault("Sum alternative projection mismatch");
    }
    private static List<String> nativePayload(CoreRepresentation proof) {
        if (proof.isSum()) validate(proof);
        else if (proof.isTuple()) {
            if (proof.getKind() != CoreKind.UNKNOWN) throw fault("Sum tuple payload components disagree with physical representations");
            var reps = new ArrayList<String>();
            for (CoreRepresentation component : proof.getComponents()) reps.addAll(nativePayload(component));
            if (!Objects.equals(proof.getPrimReps(), reps)) throw fault("Sum tuple payload components disagree with physical representations");
        } else if (proof.isVector()) VectorLayout.validate(proof);
        else if (proof.getKind() == CoreKind.VOID) {
            if (!List.of().equals(proof.getPrimReps())) throw fault("Sum void payload has registers");
        } else if (proof.getKind() == CoreKind.ADDRESS) {
            if (!List.of("AddrRep").equals(proof.getPrimReps()) || !proof.getEvaluated()) throw fault("Sum address payload requires an evaluated managed carrier");
        } else if (proof.getKind() != CoreKind.LONG && proof.getKind() != CoreKind.FLOAT && proof.getKind() != CoreKind.DOUBLE &&
                   proof.getKind() != CoreKind.DATA && proof.getKind() != CoreKind.CLOSURE && proof.getKind() != CoreKind.OBJECT)
            throw new UnsupportedCore("Unsupported Core aggregate representation: unboxed-sum payload");
        if (proof.getPrimReps() == null) throw new UnsupportedCore("Unsupported Core aggregate representation: unboxed-sum unresolved field");
        return proof.getPrimReps();
    }
    public static final class Transport {
        private final List<CoreRepresentation> fields;
        private final List<Integer> nativeSlots;
        private final List<List<Integer>> projections;
        public Transport(List<CoreRepresentation> fields, List<Integer> nativeSlots) { this(fields, nativeSlots, List.of()); }
        public Transport(List<CoreRepresentation> fields, List<Integer> nativeSlots, List<List<Integer>> projections) {
            this.fields = fields; this.nativeSlots = nativeSlots; this.projections = projections;
        }
        public List<CoreRepresentation> getFields() { return fields; }
        public List<Integer> getNativeSlots() { return nativeSlots; }
        public List<List<Integer>> getProjections() { return projections; }
    }
    private record Register(int nativeSlot, boolean address) {}
    public static Transport transport(CoreRepresentation proof) {
        validate(proof);
        var registers = new LinkedHashMap<Register, CoreRepresentation>();
        registers.put(new Register(0, false), scalarStorage("WordRep"));
        var alternatives = new ArrayList<List<Register>>();
        for (int index = 0; index < proof.getAlternatives().size(); index++) {
            Transport payload = payloadTransport(proof.getAlternatives().get(index));
            var row = new ArrayList<Register>();
            for (int field = 0; field < payload.fields.size(); field++) {
                CoreRepresentation leaf = payload.fields.get(field);
                int nativeSlot = proof.getAlternativeSlots().get(index).get(payload.nativeSlots.get(field));
                Register key = new Register(nativeSlot, leaf.getKind() == CoreKind.ADDRESS);
                registers.putIfAbsent(key, key.address ? leaf : scalarStorage(proof.getPrimReps().get(nativeSlot)));
                row.add(key);
            }
            alternatives.add(row);
        }
        var keys = new ArrayList<>(registers.keySet());
        keys.sort(Comparator.comparingInt(Register::nativeSlot).thenComparing(Register::address));
        var fields = new ArrayList<CoreRepresentation>();
        var nativeSlots = new ArrayList<Integer>();
        for (Register key : keys) { fields.add(registers.get(key)); nativeSlots.add(key.nativeSlot); }
        var projections = new ArrayList<List<Integer>>();
        for (List<Register> row : alternatives) {
            var projection = new ArrayList<Integer>();
            for (Register key : row) projection.add(keys.indexOf(key));
            projections.add(projection);
        }
        return new Transport(fields, nativeSlots, projections);
    }
    private static Transport payloadTransport(CoreRepresentation proof) {
        if (proof.isSum()) return transport(proof);
        if (proof.isTuple()) {
            var fields = new ArrayList<CoreRepresentation>();
            var nativeSlots = new ArrayList<Integer>();
            int offset = 0;
            for (CoreRepresentation component : proof.getComponents()) {
                Transport child = payloadTransport(component);
                fields.addAll(child.fields);
                for (int slot : child.nativeSlots) nativeSlots.add(slot + offset);
                offset += component.getPrimReps().size();
            }
            return new Transport(fields, nativeSlots);
        }
        return proof.getKind() == CoreKind.VOID ? new Transport(List.of(), List.of()) : new Transport(List.of(proof), List.of(0));
    }
    private static CoreRepresentation scalarStorage(String rep) {
        CoreVector vector = null;
        if (rep.startsWith("VecRep ")) { String[] pieces = rep.split(" ", -1); vector = new CoreVector(Integer.parseInt(pieces[1]), pieces[2]); }
        CoreKind kind = vector != null ? CoreKind.VECTOR : switch (rep) {
            case "WordRep", "Word64Rep" -> CoreKind.LONG;
            case "FloatRep" -> CoreKind.FLOAT; case "DoubleRep" -> CoreKind.DOUBLE;
            default -> CoreKind.OBJECT;
        };
        return new CoreRepresentation(kind, true, true, List.of(rep), null, vector, null, null, null);
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
    public static void payload(CoreRepresentation expected, CoreRepresentation actual) { payload(expected, actual, null); }
    public static void payload(CoreRepresentation expected, CoreRepresentation actual, Boolean liftedFlag) {
        if (!actual.getPresent() || !TupleShape.Companion.compatible(expected, actual)) throw fault("Sum payload logical shape mismatch");
        if (liftedFlag != null && liftedFlag != (!expected.isAggregate() && List.of(LIFTED).equals(expected.getPrimReps()))) throw fault("Sum payload levity mismatch");
    }
    public static int checkedTag(long value, int arity) {
        if (arity < 2 || value < 1L || value > (long) arity) throw fault("Invalid unboxed sum tag");
        return (int) value;
    }
    private static RuntimeFault fault(String message) { CompilerDirectives.transferToInterpreterAndInvalidate(); return new RuntimeFault(message); }
}
