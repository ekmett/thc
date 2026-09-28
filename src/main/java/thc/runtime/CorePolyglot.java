// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;
import java.util.List;
import java.util.Map;
/** Only the explicitly declared, saturated versioned ABI crosses this boundary. */
public final class CorePolyglot {
    private CorePolyglot() {}
    private static final String STATE = "State# RealWorld", BOXED = "BoxedRep (Just Lifted)";
    private static void requireProof(boolean condition, String detail) {
        if (!condition) throw new RuntimeFault("Invalid polyglot foreign call: " + detail);
    }
    private static boolean matches(CoreRepresentation proof, String rep) {
        return !proof.isAggregate() && !proof.isVector() && proof.getPresent() &&
            (rep.equals(STATE) ? List.of() : List.of(rep)).equals(proof.getPrimReps()) &&
            proof.getKind() == switch (rep) { case STATE -> CoreKind.VOID; case "AddrRep" -> CoreKind.ADDRESS;
                case "IntRep", "Int8Rep", "Int64Rep" -> CoreKind.LONG; default -> CoreKind.OBJECT; };
    }
    private static boolean result(CoreRepresentation proof, String rep) {
        var components = proof.getComponents();
        return proof.isTuple() && components != null && components.size() == 2 && matches(components.get(0), STATE) &&
            matches(components.get(1), rep) && List.of(rep).equals(proof.getPrimReps());
    }
    private static boolean exact(Object value, int expected) {
        return (value instanceof Long || value instanceof Integer) && ((Number) value).longValue() == expected;
    }
    @SuppressWarnings("unchecked") public static PolyglotOp validate(List<?> expr, boolean defined) {
        var metadata = CoreRepresentations.metadata((List<Object>) expr);
        if (metadata == null || !(metadata.get("foreignCall") instanceof Map<?, ?> descriptor)) return null;
        if (!(descriptor.get("target") instanceof Map<?, ?> target)) return null;
        if (!(target.get("symbol") instanceof String symbol)) return null;
        PolyglotOp op = null;
        for (var candidate : PolyglotOp.values()) if (candidate.getSymbol().equals(symbol)) { op = candidate; break; }
        if (op == null) throw new UnsupportedCore("Unsupported foreign call: " + symbol);
        if (!(expr.size() > 1 && expr.get(1) instanceof List<?> function)) throw new RuntimeFault("Missing foreign function");
        if (!(expr.size() > 2 && expr.get(2) instanceof List<?> arguments)) throw new RuntimeFault("Missing foreign arguments");
        if (!(expr.size() > 3 && expr.get(3) instanceof List<?> flags)) throw new RuntimeFault("Missing foreign representation flags");
        requireProof(!expr.isEmpty() && "app".equals(expr.getFirst()) && !function.isEmpty() && "var".equals(function.getFirst()) &&
            function.size() > 1 && function.get(1) instanceof String && !defined, "expected an unresolved foreign identifier");
        var types = op.getArgumentTypes();
        requireProof(exact(descriptor.get("schema"), types == null ? 1 : 2) &&
            (types == null ? !descriptor.containsKey("argumentTypes") : types.equals(descriptor.get("argumentTypes"))) &&
            "static".equals(target.get("kind")) && Boolean.TRUE.equals(target.get("isFunction")),
            "expected an exact static declaration, including nominal array types");
        requireProof("prim".equals(descriptor.get("convention")) && "safe".equals(descriptor.get("safety")), "calling convention");
        requireProof(exact(descriptor.get("arity"), op.getArguments().size()) && exact(descriptor.get("suppliedArity"), op.getArguments().size()) &&
            arguments.size() == op.getArguments().size() && flags.size() == arguments.size(), "saturated arity");
        if (!(descriptor.get("argumentReps") instanceof List<?> declared)) throw new RuntimeFault("Missing foreign argument declarations");
        boolean compatible = declared.size() == op.getArguments().size();
        if (compatible) for (int i = 0; i < op.getArguments().size(); i++) {
            if (!(matches(CoreRepresentations.parse(declared.get(i)), op.getArguments().get(i)) &&
                matches(CoreRepresentations.expression((List<Object>) arguments.get(i)), op.getArguments().get(i)) &&
                Boolean.valueOf(BOXED.equals(op.getArguments().get(i))).equals(flags.get(i)))) { compatible = false; break; }
        }
        requireProof(compatible, "argument representations");
        var declaredResult = CoreRepresentations.parse(descriptor.get("resultRep"));
        var actualResult = CoreRepresentations.expression((List<Object>) expr);
        requireProof(op.scalarResult() ? matches(declaredResult, op.getResult()) && matches(actualResult, op.getResult()) :
            result(declaredResult, op.getResult()) && result(actualResult, op.getResult()), "exact scalar or state/result tuple");
        return op;
    }
}
