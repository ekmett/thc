// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import thc.PackageScalarLink;
import thc.PackageScalarSignature;
import static thc.runtime.RuntimeFault.fault;

public final class CorePackageScalarForeign {
    private CorePackageScalarForeign() {}
    private static void check(boolean value, String detail) { if (!value) throw fault("Invalid package scalar call: " + detail); }
    private static boolean number(Object value, int wanted) { return Integer.valueOf(wanted).equals(value) || Long.valueOf(wanted).equals(value); }
    private static CoreKind kind(String rep) {
        if (rep == null) return CoreKind.VOID;
        return switch (rep) {
            case "FloatRep" -> CoreKind.FLOAT; case "DoubleRep" -> CoreKind.DOUBLE; case "AddrRep" -> CoreKind.ADDRESS;
            case "ByteArray#", "MutableByteArray#" -> CoreKind.OBJECT; default -> CoreKind.LONG;
        };
    }
    private static List<String> primReps(String rep) {
        if (rep == null) return List.of();
        return List.of(rep.equals("ByteArray#") || rep.equals("MutableByteArray#") ? "BoxedRep (Just Unlifted)" : rep);
    }
    private static boolean scalar(Object value, String rep, boolean declared) {
        return value instanceof Map<?, ?> raw && raw.keySet().equals(Set.of("kind", "primReps", "evaluated"))
            && kind(rep).name().toLowerCase(Locale.ROOT).equals(raw.get("kind")) && primReps(rep).equals(raw.get("primReps"))
            && raw.get("evaluated") instanceof Boolean && (!declared || Boolean.FALSE.equals(raw.get("evaluated")));
    }
    private static boolean result(Object value, String rep, boolean declared) {
        if (!(value instanceof Map<?, ?> raw) || !(raw.get("components") instanceof List<?> fields)) return false;
        boolean empty = rep.equals("void");
        if (!raw.keySet().equals(Set.of("kind", "primReps", "evaluated", "aggregate", "components"))
            || !"unknown".equals(raw.get("kind")) || !"unboxed-tuple".equals(raw.get("aggregate"))
            || !(empty ? List.of() : List.of(rep)).equals(raw.get("primReps")) || !(raw.get("evaluated") instanceof Boolean)
            || declared && !Boolean.FALSE.equals(raw.get("evaluated")) || fields.size() != (empty ? 1 : 2)) return false;
        for (int i = 0; i < fields.size(); i++)
            if (!scalar(fields.get(i), i == 0 ? null : rep, false) || !Boolean.TRUE.equals(((Map<?, ?>) fields.get(i)).get("evaluated"))) return false;
        return true;
    }
    public static PackageScalarCall validate(Object metadata, List<?> arguments, List<?> flags, Object output, List<PackageScalarLink> links) {
        if (!(metadata instanceof Map<?, ?> meta) || !(meta.get("foreignCall") instanceof Map<?, ?> descriptor)
            || !(descriptor.get("target") instanceof Map<?, ?> target)) return null;
        PackageScalarLink link = null;
        for (var candidate : links) if (Objects.equals(candidate.getUnit(), target.get("unit"))) {
            if (link != null) return null;
            link = candidate;
        }
        if (link == null) return null;
        if (!(descriptor.get("argumentReps") instanceof List<?> declared)) throw fault("Missing package C declared arguments");
        PackageScalarSignature signature = null;
        for (var candidate : link.getAbi()) {
            int count = candidate.getArguments().size() + 1;
            if (!Objects.equals(candidate.getSymbol(), target.get("symbol")) || !Objects.equals(candidate.getConvention(), descriptor.get("convention"))
                || !Objects.equals(candidate.getSafety(), descriptor.get("safety")) || declared.size() != count
                || !result(descriptor.get("resultRep"), candidate.getResult(), true)) continue;
            boolean matches = true;
            for (int i = 0; i < count; i++)
                if (!scalar(declared.get(i), i == count - 1 ? null : candidate.getArguments().get(i), true)) { matches = false; break; }
            if (matches) {
                if (signature != null) throw fault("Unlinked or ambiguous package C signature in scalar component: " + target.get("symbol"));
                signature = candidate;
            }
        }
        if (signature == null) throw fault("Unlinked or ambiguous package C signature in scalar component: " + target.get("symbol"));
        check(descriptor.keySet().equals(Set.of("schema", "target", "convention", "safety", "arity", "suppliedArity", "argumentReps", "resultRep"))
            && number(descriptor.get("schema"), 1) && target.keySet().equals(Set.of("kind", "symbol", "unit", "isFunction"))
            && "static".equals(target.get("kind")) && Boolean.TRUE.equals(target.get("isFunction"))
            && signature.getConvention().equals(descriptor.get("convention")) && signature.getSafety().equals(descriptor.get("safety")),
            "static exact-safety declaration");
        int count = signature.getArguments().size() + 1;
        boolean matches = number(descriptor.get("arity"), count) && number(descriptor.get("suppliedArity"), count)
            && arguments.size() == count && declared.size() == count && flags.size() == count;
        if (matches) for (int i = 0; i < count; i++) {
            String rep = i == count - 1 ? null : signature.getArguments().get(i);
            if (!Boolean.FALSE.equals(flags.get(i)) || !scalar(declared.get(i), rep, true) || !scalar(arguments.get(i), rep, false)) { matches = false; break; }
        }
        check(matches && result(descriptor.get("resultRep"), signature.getResult(), true)
            && result(meta.get("rep"), signature.getResult(), false) && result(output, signature.getResult(), false),
            "exact scalar arguments and State/result tuple");
        return new PackageScalarCall(link, signature);
    }
    public static void validateOperand(PackageScalarCall call, int index, CoreRepresentation lowered, CoreRepresentation stored) {
        String rep = index >= 0 && index < call.getArguments().length ? call.getArguments()[index] : null;
        check(lowered.getPresent() && !lowered.isAggregate() && !lowered.isVector() && lowered.getKind() == kind(rep)
            && Objects.equals(lowered.getPrimReps(), primReps(rep)), "lowered operand " + index);
        if (stored != null && stored.getPresent()) check(!stored.isAggregate() && !stored.isVector()
            && (stored.getKind() == kind(rep) || stored.getKind() == CoreKind.UNKNOWN)
            && (stored.getPrimReps() == null || stored.getPrimReps().equals(primReps(rep))), "stored operand " + index);
    }
}
