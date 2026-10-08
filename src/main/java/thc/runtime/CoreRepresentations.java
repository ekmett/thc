// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import java.util.*;

/** Admission of exact exported representation proofs; does not evaluate guest values. */
@SuppressWarnings("unchecked")
public final class CoreRepresentations {
    private CoreRepresentations() {}
    private static final Set<String> longs = Set.of("IntRep", "WordRep", "Int8Rep", "Word8Rep", "Int16Rep", "Word16Rep",
        "Int32Rep", "Word32Rep", "Int64Rep", "Word64Rep");

    public static void validateDeclaredCaseResult(CoreRepresentation declared, List<CoreRepresentation> alternatives) {
        for (var proof : alternatives) declared.refine(proof);
    }
    public static void validateFloatingCaseResult(CoreRepresentation declared, List<CoreRepresentation> alternatives) {
        CoreRepresentation floating = declared.isFloat() || declared.isDouble() ? declared : null;
        if (floating == null) for (var proof : alternatives) if (proof.isFloat() || proof.isDouble()) { floating = proof; break; }
        if (floating == null) return;
        floating.refine(declared);
        for (var proof : alternatives) floating.refine(proof);
    }
    public static void validateAggregateCaseResult(CoreRepresentation declared, List<CoreRepresentation> alternatives) {
        CoreRepresentation aggregate = declared.isAggregate() ? declared : null;
        if (aggregate == null) for (var proof : alternatives) if (proof.isAggregate()) { aggregate = proof; break; }
        if (aggregate == null) return;
        aggregate.refine(declared);
        for (var proof : alternatives) aggregate.refine(proof);
    }
    public static void validateAggregates(List<Map<String, Object>> bindings) { validateAggregates(bindings, Map.of()); }
    public static void validateAggregates(List<Map<String, Object>> bindings, Map<String, Map<String, Object>> constructors) {
        visitAggregates(bindings, List.of(), Set.of(), constructors);
    }
    private static void visitAggregates(Object value, List<Object> path, Set<List<Object>> exemptions,
            Map<String, Map<String, Object>> constructors) {
        if (value instanceof Map<?, ?> map) {
            for (var entry : map.entrySet()) {
                Object key = entry.getKey(), child = entry.getValue();
                var next = new ArrayList<>(path); next.add(key == null ? "<null>" : key);
                if (("rep".equals(key) || "resultRep".equals(key) || "joinResultRep".equals(key)) &&
                        child instanceof Map<?, ?> && !exemptions.contains(next)) parse(child);
                visitAggregates(child, next, exemptions, constructors);
            }
        } else if (value instanceof List<?> list) {
            // Exempt structural sites, not equal/shared metadata maps elsewhere.
            var local = exemptions;
            if (CoreVectorMemory.readCase((List<Object>) list, constructors) != null) {
                local = new HashSet<>(exemptions);
                var resultPath = new ArrayList<>(path); resultPath.addAll(List.of(1, 6, "rep")); local.add(resultPath);
                var binderPath = new ArrayList<>(path); binderPath.addAll(List.of(4, "binder", "rep")); local.add(binderPath);
            }
            for (int i = 0; i < list.size(); i++) {
                var next = new ArrayList<>(path); next.add(i);
                visitAggregates(list.get(i), next, local, constructors);
            }
        }
    }
    private record FunctionBinders(List<Map<String, Object>> inputs, CoreRepresentation result) {}
    private static FunctionBinders knownFunctionBinders(List<?> expression, List<Map<String, Object>> bindings, boolean ioEntry) {
        Map<String, Map<String, Object>> globals = new LinkedHashMap<>();
        for (var binding : bindings) globals.put((String) binding.get("id"), binding);
        return resolveBinders(expression, Set.of(), globals, ioEntry);
    }
    private static FunctionBinders resolveBinders(List<?> expr, Set<String> seen, Map<String, Map<String, Object>> globals, boolean ioEntry) {
        Object tag = at(expr, 0);
        if ("lam".equals(tag)) return new FunctionBinders((List<Map<String, Object>>) expr.get(1), lambdaResult(expr));
        if ("var".equals(tag)) {
            String id = (String) expr.get(1);
            if (seen.contains(id)) return null;
            var binding = globals.get(id);
            if (binding == null || !(binding.get("expr") instanceof List<?> body)) return null;
            var next = new HashSet<>(seen); next.add(id);
            return resolveBinders((List<Object>) body, next, globals, ioEntry);
        }
        if ("app".equals(tag)) {
            var signature = resolveBinders((List<Object>) expr.get(1), seen, globals, ioEntry);
            if (signature == null) return null;
            int supplied = ((List<?>) expr.get(2)).size();
            if (supplied < signature.inputs.size()) return new FunctionBinders(
                new ArrayList<>(signature.inputs.subList(supplied, signature.inputs.size())), signature.result);
            // A saturated unboxed result is definitely not another IO action.
            return ioEntry && !signature.result.hasBoxedPointer() ? new FunctionBinders(List.of(), signature.result) : null;
        }
        return null;
    }
    public static CoreFunctionSignature knownFunctionSignature(List<?> expression, List<Map<String, Object>> bindings) {
        var signature = knownFunctionBinders(expression, bindings, false);
        if (signature == null) return null;
        List<CoreRepresentation> inputs = new ArrayList<>();
        for (var formal : signature.inputs) inputs.add(binder(formal));
        return new CoreFunctionSignature(inputs, signature.result);
    }
    public static CoreRepresentation knownFunctionResult(List<?> expression, List<Map<String, Object>> bindings) {
        var signature = knownFunctionSignature(expression, bindings);
        return signature == null ? null : signature.result();
    }
    public static CoreRepresentation ioMainResult(Map<String, ?> binding, List<Map<String, Object>> bindings) {
        if (!(binding.get("expr") instanceof List<?> expression)) throw new UnsupportedCore("IO main lacks Core expression");
        var signature = knownFunctionBinders((List<Object>) expression, bindings, true);
        if (signature == null) {
            // An IO newtype may be a shared thunk; authenticate its closure after forcing the action head.
            if (!List.of("BoxedRep (Just Lifted)").equals(binder(binding).getPrimReps()))
                throw new UnsupportedCore("IO main lacks an exact state-transformer signature");
            return new CoreRepresentation(CoreKind.UNKNOWN, false, true, List.of("BoxedRep (Just Lifted)"), List.of(
                new CoreRepresentation(CoreKind.VOID, true, true, List.of()),
                new CoreRepresentation(CoreKind.OBJECT, false, true, List.of("BoxedRep (Just Lifted)"))));
        }
        var formals = signature.inputs;
        var result = signature.result;
        if (formals.size() != 1 || !"State# RealWorld".equals(formals.getFirst().get("type")))
            throw new UnsupportedCore("IO main requires an exact State# RealWorld binder");
        List<CoreRepresentation> inputs = new ArrayList<>();
        for (var formal : formals) inputs.add(binder(formal));
        if (inputs.size() != 1 || inputs.getFirst().getKind() != CoreKind.VOID || !List.of().equals(inputs.getFirst().getPrimReps()))
            throw new UnsupportedCore("IO main requires one exact State# RealWorld input");
        var fields = result.getComponents();
        if (result.getKind() != CoreKind.UNKNOWN || fields == null || fields.size() != 2 || !List.of("BoxedRep (Just Lifted)").equals(result.getPrimReps()) ||
                fields.get(0).getKind() != CoreKind.VOID || !List.of().equals(fields.get(0).getPrimReps()) ||
                !List.of("BoxedRep (Just Lifted)").equals(fields.get(1).getPrimReps()))
            throw new UnsupportedCore("IO main requires the exact (# State#, lifted answer #) result");
        TupleShape.validate(result);
        return result;
    }
    public static void requireInput(CoreRepresentation proof) {
        if (proof.isTuple()) {
            TupleShape.validate(proof);
        } else if (proof.isSum()) SumShape.validate(proof);
        else if (proof.isVector()) VectorLayout.validate(proof);
        else requireScalar(proof, "argument");
    }
    /** Unknown levity of a known pointer does not introduce a forcing obligation. */
    public static boolean mayBeLazy(Object lifted, CoreRepresentation proof) {
        if (lifted instanceof Boolean known) return known;
        if (lifted == null && proof.hasUnknownBoxedLevity()) return true;
        throw new UnsupportedCore("Unknown argument levity without a boxed pointer representation");
    }
    public static boolean argumentMayBeLazy(Object lifted, List<?> expression) {
        return lifted instanceof Boolean known ? known : mayBeLazy(lifted, expression(expression));
    }
    public static void requireJoinArgument(CoreRepresentation expected, CoreRepresentation actual) {
        requireInput(actual);
        if ((expected.isTuple() || actual.isTuple()) && (!expected.isTuple() || !actual.isTuple() || !TupleShape.compatible(expected, actual)))
            throw new RuntimeFault("Local join requires matching exact tuple argument proof");
        if ((expected.isVector() || actual.isVector()) && (!expected.isVector() || !actual.isVector() || !TupleShape.compatible(expected, actual)))
            throw new RuntimeFault("Local join requires matching exact vector argument proof");
        if ((expected.isSum() || actual.isSum()) && (!expected.isSum() || !actual.isSum() || !TupleShape.compatible(expected, actual)))
            throw new RuntimeFault("Local join requires matching exact sum argument proof");
    }
    public static void requireScalar(CoreRepresentation proof, String boundary) {
        requireNoVector(proof, boundary);
        requireNoSum(proof, boundary);
        if (proof.isTuple()) throw new UnsupportedCore("Unsupported Core aggregate representation: unboxed-tuple (" + boundary + ")");
    }
    public static void requireNoSum(CoreRepresentation proof, String boundary) {
        if (proof.isSum()) throw new UnsupportedCore("Unsupported Core aggregate representation: unboxed-sum (" + boundary + ")");
    }
    public static void requireNoVector(CoreRepresentation proof, String boundary) {
        if (proof.isVector()) throw new UnsupportedCore("Unsupported Core vector boundary: " + boundary);
    }
    public static CoreRepresentation parse(Object value) {
        if (value == null) return CoreRepresentation.UNKNOWN;
        if (!(value instanceof Map<?, ?> map)) throw new RuntimeFault("Invalid Core representation metadata");
        List<CoreRepresentation> components = null;
        Object aggregate = map.get("aggregate");
        if (aggregate != null && !"unboxed-sum".equals(aggregate)) {
            if (!"unboxed-tuple".equals(aggregate)) throw new RuntimeFault("Invalid Core aggregate representation: " + aggregate);
            if (!(map.get("components") instanceof List<?> values))
                throw new UnsupportedCore("Unsupported Core aggregate representation: unboxed-tuple lacks exact components");
            components = new ArrayList<>(); for (Object field : values) components.add(parse(field));
        }
        if ("unboxed-sum".equals(map.get("aggregate")) && map.containsKey("components")) throw new RuntimeFault("Sum proof contains tuple components");
        List<CoreRepresentation> alternatives = null;
        if ("unboxed-sum".equals(map.get("aggregate"))) {
            if (!(map.get("alternatives") instanceof List<?> values))
                throw new UnsupportedCore("Unsupported Core aggregate representation: unboxed-sum lacks exact alternatives");
            alternatives = new ArrayList<>(); for (Object field : values) alternatives.add(parse(field));
        }
        Integer tagSlot = alternatives == null ? null : exactInt(map.get("tagSlot"));
        List<List<Integer>> projections = null;
        if (alternatives != null && (!map.containsKey("alternativeSlots") || !map.containsKey("primReps")))
            throw new UnsupportedCore("Unsupported Core aggregate representation: unboxed-sum lacks exact projection");
        if (alternatives != null && map.get("alternativeSlots") != null) {
            if (!(map.get("alternativeSlots") instanceof List<?> rows))
                throw new UnsupportedCore("Unsupported Core aggregate representation: unboxed-sum lacks exact projection");
            projections = new ArrayList<>();
            for (Object row : rows) {
                if (!(row instanceof List<?> values)) throw new RuntimeFault("Invalid sum alternative projection");
                List<Integer> projected = new ArrayList<>();
                for (Object slot : values) projected.add(exactInt(slot));
                projections.add(projected);
            }
        }
        Object rawKind = map.get("kind");
        CoreKind kind = switch (rawKind instanceof String text ? text : "") {
            case "long" -> CoreKind.LONG; case "address" -> CoreKind.ADDRESS; case "void" -> CoreKind.VOID;
            case "float" -> CoreKind.FLOAT; case "double" -> CoreKind.DOUBLE;
            case "data" -> CoreKind.DATA; case "closure" -> CoreKind.CLOSURE; case "object" -> CoreKind.OBJECT;
            case "vector" -> CoreKind.VECTOR; case "unknown" -> CoreKind.UNKNOWN;
            default -> throw new RuntimeFault("Unknown Core representation kind " + map.get("kind"));
        };
        List<String> reps = null;
        Object rawReps = map.get("primReps");
        if (rawReps != null) {
            if (!(rawReps instanceof List<?> values)) throw new RuntimeFault("Invalid Core primitive representations");
            reps = new ArrayList<>();
            for (Object rep : values) {
                if (!(rep instanceof String text)) throw new RuntimeFault("Invalid Core primitive representation");
                reps.add(text);
            }
        }
        if (kind == CoreKind.LONG && (reps == null || reps.size() != 1 || !longs.contains(reps.getFirst())))
            throw new RuntimeFault("Core Long proof lacks a supported primitive representation");
        if (kind == CoreKind.ADDRESS && !List.of("AddrRep").equals(reps)) throw new RuntimeFault("Core address proof lacks AddrRep");
        if (kind == CoreKind.FLOAT && !List.of("FloatRep").equals(reps)) throw new RuntimeFault("Core Float proof lacks FloatRep");
        if (kind == CoreKind.DOUBLE && !List.of("DoubleRep").equals(reps)) throw new RuntimeFault("Core Double proof lacks DoubleRep");
        if (kind == CoreKind.VOID && !List.of().equals(reps)) throw new RuntimeFault("Core void proof has payload registers");
        if ((kind == CoreKind.DATA || kind == CoreKind.CLOSURE || kind == CoreKind.OBJECT) && (reps == null || reps.size() != 1 ||
                !List.of("BoxedRep (Just Lifted)", "BoxedRep (Just Unlifted)", "BoxedRep Nothing").contains(reps.getFirst())))
            throw new RuntimeFault("Core reference proof lacks a single boxed representation");
        var vector = CoreVector.parse(map.get("vector"), kind, reps, components != null || alternatives != null);
        if (!(map.get("evaluated") instanceof Boolean evaluated)) throw new RuntimeFault("Missing Core evaluatedness proof");
        var proof = new CoreRepresentation(kind, evaluated, true, reps, components, vector, alternatives, tagSlot, projections);
        if (components != null) TupleShape.validate(proof);
        if (alternatives != null) SumShape.validate(proof);
        return proof;
    }
    private static int exactInt(Object value) {
        if (!(value instanceof Long || value instanceof Integer)) throw new RuntimeFault("Invalid sum slot index");
        Number number = (Number) value;
        int index = number.intValue();
        if (number.doubleValue() != (double) index) throw new RuntimeFault("Invalid sum slot index");
        return index;
    }
    public static CoreRepresentation binder(Map<String, ?> binding) { return parse(binding.get("rep")); }
    private static Object at(List<?> values, int index) { return index < values.size() ? values.get(index) : null; }
    public static Map<String, Object> metadata(List<?> expr) {
        Object tag = at(expr, 0);
        int index = switch (tag instanceof String text ? text : "") {
            case "var", "prim" -> 2; case "lit", "lam", "con" -> 3; case "app" -> 6;
            case "let", "case" -> 4; case "void" -> 1; default -> -1;
        };
        return index < 0 || !(at(expr, index) instanceof Map<?, ?> map) ? null : (Map<String, Object>) map;
    }
    public static CoreRepresentation expression(List<?> expr) {
        if ("lit".equals(at(expr, 0))) {
            Object kind = at(expr, 1);
            if ("rubbish".equals(kind)) return RubbishLiterals.proof((List<Object>) expr);
            if ("bignat".equals(kind)) return BigNatLiterals.proof((List<Object>) expr);
            if (Arrays.asList("int8", "word8", "int16", "word16", "int32", "word32").contains(kind)) return narrowLiteralProof(expr);
        }
        var metadata = metadata(expr);
        return parse(metadata == null ? null : metadata.get("rep"));
    }
    public static CoreRepresentation narrowLiteralProof(List<?> expr) {
        Object literal = expr.get(1);
        String expected = switch (literal instanceof String text ? text : "") {
            case "int8" -> "Int8Rep"; case "word8" -> "Word8Rep";
            case "int16" -> "Int16Rep"; case "word16" -> "Word16Rep";
            case "int32" -> "Int32Rep"; case "word32" -> "Word32Rep";
            default -> throw new RuntimeFault("Not a supported narrow literal: " + expr.get(1));
        };
        var metadata = metadata(expr);
        var proof = parse(metadata == null ? null : metadata.get("rep"));
        boolean unconstrained = proof.getKind() == CoreKind.UNKNOWN && proof.getPrimReps() == null && !proof.isAggregate() && !proof.isVector();
        if (proof.getPresent() && !unconstrained && (!proof.isInt() || proof.isAggregate() || proof.isVector()))
            throw new RuntimeFault(expr.get(1) + " literal requires a scalar Int carrier");
        return new CoreRepresentation(CoreKind.LONG, true, true, List.of(expected), null, null, null, null, null);
    }
    public static CoreRepresentation lambdaResult(List<?> expr) {
        var metadata = metadata(expr); return parse(metadata == null ? null : metadata.get("resultRep"));
    }
    /** The case operation's result, distinct from an enclosing erased dictionary's ABI proof. */
    public static CoreRepresentation caseResult(List<?> expr) {
        var metadata = metadata(expr);
        return metadata != null && metadata.containsKey("resultRep") ? parse(metadata.get("resultRep")) : expression(expr);
    }
    public static CoreRepresentation caseBinder(List<?> expr) {
        var metadata = metadata(expr);
        return metadata != null && metadata.get("binder") instanceof Map<?, ?> map ? binder((Map<String, Object>) map) : CoreRepresentation.UNKNOWN;
    }
    public static List<Map<String, Object>> alternativeBinders(List<?> alt) {
        return at(alt, 4) instanceof Map<?, ?> map && map.get("binders") instanceof List<?> binders ? (List<Map<String, Object>>) binders : List.of();
    }
    public static Integer joinArity(Map<String, ?> binding) {
        Object value = binding.get("joinValueArity");
        if (value == null) return null;
        if (!(value instanceof Number number)) throw new RuntimeFault("Invalid join value arity");
        int result = number.intValue();
        if (result < 0 || number.doubleValue() != (double) result) throw new RuntimeFault("Invalid join value arity");
        return result;
    }
    public static CoreRepresentation joinResult(Map<String, ?> binding) { return parse(binding.get("joinResultRep")); }
}
