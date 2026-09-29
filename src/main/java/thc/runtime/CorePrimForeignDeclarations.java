// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Cold association of nominal stock stack declarations with their recursive
 * calls. Flat emitted witnesses remain archive obligations, never native ABIs. */
public final class CorePrimForeignDeclarations {
    private CorePrimForeignDeclarations() {}
    private static void check(boolean value, String detail) {
        if (!value) throw new IllegalArgumentException("Invalid stock prim declaration: " + detail);
    }
    private static OriginalStackInfoOp operation(Object symbol) {
        for (var candidate : OriginalStackInfoOp.values())
            if ("prim".equals(candidate.getConvention()) && candidate.getSymbol().equals(symbol)) return candidate;
        return null;
    }
    private static Object named(String module, String name, String namespace, Object... arguments) {
        return Map.of("kind", "tycon", "name", Map.of("unit", "ghc-internal", "module", module,
            "occurrence", name, "namespace", namespace), "arguments", List.of(arguments));
    }
    private static Object primitive(String rep) {
        return switch (rep) {
            case "BoxedRep (Just Unlifted)" -> named("GHC.Internal.Prim", "StackSnapshot#", "type");
            case "BoxedRep (Just Lifted)" -> named("GHC.Internal.Types", "Any", "type", named("GHC.Internal.Types", "Type", "type"));
            case "WordRep" -> named("GHC.Internal.Prim", "Word#", "type");
            case "Word32Rep" -> named("GHC.Internal.Prim", "Word32#", "type");
            case "IntRep" -> named("GHC.Internal.Prim", "Int#", "type");
            case "AddrRep" -> named("GHC.Internal.Prim", "Addr#", "type");
            default -> throw new IllegalArgumentException("Unsupported stock stack nominal type");
        };
    }
    private static Object normalized(OriginalStackInfoOp op) {
        Object result;
        if (op.getTupleResult()) {
            var fields = new ArrayList<Object>();
            for (var rep : op.getResults()) fields.add("BoxedRep (Just Unlifted)".equals(rep)
                ? named("GHC.Internal.Types", "UnliftedRep", "type") : named("GHC.Internal.Types", rep, "data"));
            for (var rep : op.getResults()) fields.add(primitive(rep));
            result = named("GHC.Internal.Types", "Tuple" + op.getResults().size() + "#", "type", fields.toArray());
        } else result = primitive(op.getResults().getFirst());
        for (var rep : op.getArguments().reversed()) result = Map.of("kind", "function",
            "multiplicity", named("GHC.Internal.Types", "Many", "data"), "argument", primitive(rep), "result", result);
        return result;
    }
    private static Object declared(OriginalStackInfoOp op, Object normalized) {
        return switch (op) {
            case LARGE_BITMAP, BCO_LARGE_BITMAP, RET_FUN_LARGE_BITMAP -> named("GHC.Internal.Stack.Decode", "LargeBitmapGetter", "type");
            case SMALL_BITMAP, RET_FUN_SMALL_BITMAP -> named("GHC.Internal.Stack.Decode", "SmallBitmapGetter", "type");
            default -> normalized;
        };
    }
    private static final Set<String> SCALARS = Set.of("IntRep", "WordRep", "Int8Rep", "Word8Rep", "Int16Rep", "Word16Rep",
        "Int32Rep", "Word32Rep", "Int64Rep", "Word64Rep", "AddrRep", "FloatRep", "DoubleRep");
    private static Map<String,Object> scalar(String rep, boolean evaluated) {
        String kind = switch (rep) {
            case "void" -> "void"; case "AddrRep" -> "address"; case "FloatRep" -> "float"; case "DoubleRep" -> "double";
            case "BoxedRep (Just Unlifted)", "BoxedRep (Just Lifted)" -> "object"; default -> "long";
        };
        return Map.of("kind", kind, "primReps", "void".equals(rep) ? List.of() : List.of(rep), "evaluated", evaluated);
    }
    private static String carrier(Map<?,?> rep) {
        var reps = (List<?>) rep.get("primReps"); check(reps.size() <= 1, "immediate scalar carrier");
        return reps.isEmpty() ? "void" : (String) reps.getFirst();
    }
    private static Map<String,Object> representation(Object raw, boolean evaluated) {
        check(raw instanceof Map<?,?> type && "tycon".equals(type.get("kind")), "concrete nominal type");
        var type = (Map<?,?>) raw; var name = (Map<?,?>) type.get("name"); var args = (List<?>) type.get("arguments");
        String occurrence = (String) name.get("occurrence");
        if ("ghc-internal".equals(name.get("unit")) && "GHC.Internal.Types".equals(name.get("module"))) {
            check("type".equals(name.get("namespace")), "tuple nominal namespace");
            if ("Any".equals(occurrence)) {
                check(args.equals(List.of(named("GHC.Internal.Types", "Type", "type"))), "Any kind");
                return scalar("BoxedRep (Just Lifted)", evaluated);
            }
            var fields = new ArrayList<Map<String,Object>>();
            if ("Unit#".equals(occurrence)) check(args.isEmpty(), "empty tuple nominal arity");
            else {
                check(occurrence.matches("Tuple[1-9][0-9]*#"), "tuple nominal identity");
                int count;
                try { count = Integer.parseInt(occurrence.substring(5, occurrence.length() - 1)); }
                catch (NumberFormatException invalid) { throw new IllegalArgumentException("Invalid stock prim tuple arity", invalid); }
                check(count <= args.size() && args.size() == count * 2, "tuple nominal arity");
                for (Object field : args.subList(count, args.size())) fields.add(representation(field, true));
                for (int i = 0; i < count; i++) {
                    String rep = switch (carrier(fields.get(i))) {
                        case "void" -> "ZeroBitRep"; case "BoxedRep (Just Unlifted)" -> "UnliftedRep";
                        case "BoxedRep (Just Lifted)" -> "LiftedRep"; default -> carrier(fields.get(i));
                    };
                    check(args.get(i).equals(named("GHC.Internal.Types", rep,
                        Set.of("ZeroBitRep", "UnliftedRep", "LiftedRep").contains(rep) ? "type" : "data")), "tuple runtime-rep identity");
                }
            }
            var reps = new ArrayList<Object>(); for (var field : fields) reps.addAll((List<?>) field.get("primReps"));
            return Map.of("kind", "unknown", "primReps", List.copyOf(reps), "evaluated", evaluated,
                "aggregate", "unboxed-tuple", "components", List.copyOf(fields));
        }
        check("ghc-internal".equals(name.get("unit")) && "GHC.Internal.Prim".equals(name.get("module")) &&
            "type".equals(name.get("namespace")), "primitive nominal owner");
        String rep;
        switch (occurrence) {
            case "State#" -> { check(args.equals(List.of(named("GHC.Internal.Prim", "RealWorld", "type"))), "State identity"); rep = "void"; }
            case "StackSnapshot#", "ThreadId#" -> { check(args.isEmpty(), "boxed nominal arity"); rep = "BoxedRep (Just Unlifted)"; }
            case "StablePtr#" -> { check(args.size() == 2, "stable pointer nominal arity"); rep = "AddrRep"; }
            default -> {
                check(occurrence.endsWith("#"), "scalar nominal identity");
                rep = occurrence.substring(0, occurrence.length() - 1) + "Rep";
                check(args.isEmpty() && SCALARS.contains(rep), "scalar nominal identity");
            }
        }
        return scalar(rep, evaluated);
    }
    private static boolean integer(Object raw, int expected) {
        return (raw instanceof Integer || raw instanceof Long) && ((Number) raw).longValue() == expected;
    }
    private static void associate(Map<?,?> declaration, List<?> calls) {
        var emitted = (Map<?,?>) declaration.get("emitted"); var args = new ArrayList<Map<String,Object>>();
        Object type = declaration.get("normalizedType");
        while (type instanceof Map<?,?> arrow && "function".equals(arrow.get("kind"))) {
            check(named("GHC.Internal.Types", "Many", "data").equals(arrow.get("multiplicity")), "arrow multiplicity");
            args.add(representation(arrow.get("argument"), false)); type = arrow.get("result");
        }
        var output = representation(type, false);
        var results = output.get("components") instanceof List<?> fields ? fields : List.of(output);
        check(args.stream().map(CorePrimForeignDeclarations::carrier).toList().equals(emitted.get("arguments")) &&
            results.stream().map(field -> carrier((Map<?,?>) field)).toList().equals(emitted.get("result")), "emitted/nominal carriers");
        for (Object item : calls) {
            if (!(item instanceof Map<?,?> call) || !(call.get("target") instanceof Map<?,?> target) ||
                !Objects.equals(target.get("unit"), emitted.get("unit")) || !Objects.equals(target.get("symbol"), emitted.get("symbol"))) continue;
            check(call.keySet().equals(Set.of("schema", "target", "convention", "safety", "arity", "suppliedArity", "argumentReps", "resultRep")) &&
                integer(call.get("schema"), 1) && target.equals(Map.of("kind", "static", "isFunction", true,
                    "unit", emitted.get("unit"), "symbol", emitted.get("symbol"))) &&
                "prim".equals(call.get("convention")) && "safe".equals(call.get("safety")) &&
                integer(call.get("arity"), args.size()) && integer(call.get("suppliedArity"), args.size()) &&
                args.equals(call.get("argumentReps")) && output.equals(call.get("resultRep")), "recursive call/nominal ABI");
        }
    }
    public static void validate(Map<?,?> module, Map<?,?> proof) {
        var declarations = new ArrayList<Map<?,?>>();
        for (Object item : (List<?>) proof.get("imports")) {
            var declaration = (Map<?,?>) item; var emitted = (Map<?,?>) declaration.get("emitted");
            if ("prim".equals(emitted.get("convention"))) associate(declaration, (List<?>) proof.get("expectedCalls"));
            var op = operation(emitted.get("symbol"));
            if (op == null || !"ghc-internal".equals(emitted.get("unit"))) continue;
            var normalized = normalized(op);
            check("9.14.1".equals(module.get("ghc")) && "prim".equals(declaration.get("convention")) &&
                "safe".equals(declaration.get("safety")) && declaration.get("header") == null &&
                Boolean.TRUE.equals(declaration.get("isFunction")) && "ghc-internal".equals(declaration.get("unit")), "original identity");
            check(declared(op, normalized).equals(declaration.get("declaredType")) && normalized.equals(declaration.get("normalizedType")), "exact declared and normalized nominal types");
            check(op.getArguments().equals(emitted.get("arguments")) && op.getResults().equals(emitted.get("result")), "exact emitted carriers");
            declarations.add(declaration);
        }
        for (Object item : (List<?>) proof.get("expectedCalls")) {
            if (!(item instanceof Map<?,?> call) || !(call.get("target") instanceof Map<?,?> target) ||
                !"ghc-internal".equals(target.get("unit")) || operation(target.get("symbol")) == null) continue;
            boolean associated = declarations.stream().anyMatch(declaration -> Objects.equals(declaration.get("symbol"), target.get("symbol")));
            check(associated || !"GHC.Internal.Stack.Decode".equals(module.get("module")), "missing original declaration");
            if (!associated) continue; // External declarations belong to their original owning module.
            try {
                var args = call.get("argumentReps") instanceof List<?> list ? list : List.of();
                CoreStackInfoForeign.validate(Map.of("foreignCall", call, "rep", call.get("resultRep")), args,
                    java.util.Collections.nCopies(args.size(), false), call.get("resultRep"));
            } catch (RuntimeFault | NullPointerException failure) {
                throw new IllegalArgumentException("Invalid stock prim declaration: recursive call shape", failure);
            }
        }
    }
}
