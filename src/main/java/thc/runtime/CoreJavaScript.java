// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

public final class CoreJavaScript {
    private CoreJavaScript() {}
    private static final String PREFIX = "thc_javascript_v1_";
    private static void requireProof(boolean condition, String detail) {
        if (!condition) throw new RuntimeFault("Invalid JavaScript foreign call: " + detail);
    }
    private static CoreKind scalar(CoreRepresentation rep) {
        if (!rep.getPresent() || rep.isAggregate() || rep.isVector()) return null;
        if (rep.getKind() == CoreKind.LONG && List.of("IntRep").equals(rep.getPrimReps())) return CoreKind.LONG;
        if (rep.getKind() == CoreKind.DOUBLE && List.of("DoubleRep").equals(rep.getPrimReps())) return CoreKind.DOUBLE;
        if (rep.getKind() == CoreKind.VOID && List.of().equals(rep.getPrimReps())) return CoreKind.VOID;
        return null;
    }
    private static CoreKind result(CoreRepresentation rep) {
        var fields = rep.getComponents();
        if (fields == null) return null;
        if (!rep.getPresent() || rep.isSum() || rep.isVector() || fields.size() < 1 || fields.size() > 2 || scalar(fields.getFirst()) != CoreKind.VOID)
            return null;
        var kind = fields.size() == 1 ? CoreKind.VOID : scalar(fields.get(1));
        if (kind == null || fields.size() == 2 && kind == CoreKind.VOID) return null;
        var reps = new ArrayList<String>();
        for (var field : fields) if (field.getPrimReps() != null) reps.addAll(field.getPrimReps());
        return reps.equals(rep.getPrimReps()) ? kind : null;
    }
    private static boolean exact(Object value, int expected) {
        return (value instanceof Integer || value instanceof Long) && ((Number) value).longValue() == expected;
    }
    @SuppressWarnings("unchecked") public static JavaScriptImport validate(List<?> expr, boolean defined) {
        var metadata = CoreRepresentations.INSTANCE.metadata((List<Object>) expr);
        if (metadata == null || !(metadata.get("foreignCall") instanceof Map<?, ?> descriptor)) return null;
        if (!(descriptor.get("target") instanceof Map<?, ?> target)) return null;
        if (!(target.get("symbol") instanceof String symbol)) return null;
        if (!symbol.startsWith(PREFIX) && !"javascript-v1".equals(descriptor.get("intrinsic"))) return null;
        if (!(descriptor.get("javascriptSource") instanceof String source)) throw new RuntimeFault("Invalid JavaScript foreign call: missing source");
        var encoded = HexFormat.of().formatHex(source.getBytes(StandardCharsets.UTF_8));
        requireProof(!source.isEmpty() && symbol.equals(PREFIX + encoded) && "javascript-v1".equals(descriptor.get("intrinsic")), "versioned source marker");
        var function = expr.size() > 1 && expr.get(1) instanceof List<?> list ? list : null;
        requireProof(!expr.isEmpty() && "app".equals(expr.getFirst()) && function != null && !function.isEmpty() &&
            "var".equals(function.getFirst()) && function.size() > 1 && function.get(1) instanceof String && !defined, "expected an unresolved foreign identifier");
        requireProof(exact(descriptor.get("schema"), 1) && "static".equals(target.get("kind")) && Boolean.TRUE.equals(target.get("isFunction")), "static version 1 function declaration");
        requireProof("ccall".equals(descriptor.get("convention")) && ("safe".equals(descriptor.get("safety")) || "unsafe".equals(descriptor.get("safety"))), "synchronous calling convention");
        if (!(descriptor.get("argumentReps") instanceof List<?> declared)) throw new RuntimeFault("Invalid JavaScript foreign call: argument declarations");
        if (!(expr.size() > 2 && expr.get(2) instanceof List<?> arguments)) throw new RuntimeFault("Invalid JavaScript foreign call: arguments");
        if (!(expr.size() > 3 && expr.get(3) instanceof List<?> flags)) throw new RuntimeFault("Invalid JavaScript foreign call: argument flags");
        var kinds = new CoreKind[declared.size()];
        for (int i = 0; i < kinds.length; i++) kinds[i] = scalar(CoreRepresentations.INSTANCE.parse(declared.get(i)));
        boolean scalarArguments = kinds.length > 0 && kinds[kinds.length - 1] == CoreKind.VOID;
        if (scalarArguments) for (int i = 0; i < kinds.length - 1; i++) {
            if (kinds[i] != CoreKind.LONG && kinds[i] != CoreKind.DOUBLE) { scalarArguments = false; break; }
        }
        requireProof(scalarArguments, "scalar argument types");
        boolean saturated = exact(descriptor.get("arity"), kinds.length) && exact(descriptor.get("suppliedArity"), kinds.length) &&
            arguments.size() == kinds.length && flags.size() == kinds.length;
        if (saturated) for (var flag : flags) if (!Boolean.FALSE.equals(flag)) { saturated = false; break; }
        requireProof(saturated, "saturated arity");
        boolean actual = true;
        for (int i = 0; i < arguments.size(); i++) {
            if (scalar(CoreRepresentations.INSTANCE.expression((List<Object>) arguments.get(i))) != kinds[i]) { actual = false; break; }
        }
        requireProof(actual, "actual argument representations");
        var result = result(CoreRepresentations.INSTANCE.parse(descriptor.get("resultRep")));
        requireProof(result != null && result(CoreRepresentations.INSTANCE.expression((List<Object>) expr)) == result, "state/result tuple");
        return new JavaScriptImport(source, Arrays.copyOf(kinds, kinds.length - 1), result, ForeignSafety.synchronous((String) descriptor.get("safety")));
    }
}
