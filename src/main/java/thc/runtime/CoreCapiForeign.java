// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.nodes.Node;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import thc.ForeignBitcode;
import thc.Language;

/** Exact CAPI descriptors of verified, context-loaded bitcode exports. */
public final class CoreCapiForeign {
    private CoreCapiForeign() {}

    public static long zero(Node node, CapiCall call) {
        var state = Language.currentState(node);
        var previous = state.getThreads().enterForeign(ForeignSafety.UNSAFE);
        try { return state.cbits().capiZero(call.unit(), call.symbol(), call.timeClock()); }
        finally { state.getThreads().leaveForeign(previous); }
    }

    public static long wordAddress(Node node, CapiCall call, long word, ManagedAddress address) {
        var state = Language.currentState(node);
        var previous = state.getThreads().enterForeign(ForeignSafety.UNSAFE);
        try {
            var result = state.cbits().capiWordAddress(call, word, address);
            if (result.getValue() < 0) state.getStdio().captureForeignErrno(result.getErrno());
            return result.getValue();
        } finally { state.getThreads().leaveForeign(previous); }
    }

    private static final Set<String> SCALAR_KEYS = Set.of("kind", "primReps", "evaluated");
    private static final Set<String> TUPLE_KEYS = Set.of("kind", "primReps", "evaluated", "aggregate", "components");
    private static final Set<String> DESCRIPTOR_KEYS = Set.of("schema", "target", "convention", "safety", "arity",
        "suppliedArity", "argumentReps", "resultRep");

    private static RuntimeFault failProof(String message) {
        return new RuntimeFault("Invalid linked CAPI call: " + message);
    }

    private static boolean integer(Object value, int number) {
        return Integer.valueOf(number).equals(value) || Long.valueOf(number).equals(value);
    }

    private static boolean scalar(Object value, String primitive, boolean declared) {
        if (!(value instanceof Map<?, ?> map)) return false;
        var kind = switch (primitive) { case null -> "void"; case "AddrRep" -> "address"; default -> "long"; };
        return map.keySet().equals(SCALAR_KEYS) && kind.equals(map.get("kind")) &&
            (primitive == null ? List.of() : List.of(primitive)).equals(map.get("primReps")) &&
            map.get("evaluated") instanceof Boolean && (!declared || Boolean.FALSE.equals(map.get("evaluated")));
    }

    private static boolean result(Object value, String primitive, boolean declared) {
        if (!(value instanceof Map<?, ?> map) || !(map.get("components") instanceof List<?> parts)) return false;
        return map.keySet().equals(TUPLE_KEYS) && "unknown".equals(map.get("kind")) &&
            "unboxed-tuple".equals(map.get("aggregate")) && List.of(primitive).equals(map.get("primReps")) &&
            map.get("evaluated") instanceof Boolean && (!declared || Boolean.FALSE.equals(map.get("evaluated"))) &&
            parts.size() == 2 && scalar(parts.get(0), null, false) && Boolean.TRUE.equals(((Map<?, ?>) parts.get(0)).get("evaluated")) &&
            scalar(parts.get(1), primitive, false) && Boolean.TRUE.equals(((Map<?, ?>) parts.get(1)).get("evaluated"));
    }

    public static CapiCall validate(Object metadata, List<?> argumentReps, List<?> flags, Object resultRep, List<ForeignBitcode> links) {
        if (!(metadata instanceof Map<?, ?> meta) || !(meta.get("foreignCall") instanceof Map<?, ?> descriptor) ||
            !(descriptor.get("target") instanceof Map<?, ?> target) || !(target.get("unit") instanceof String unit) ||
            !(target.get("symbol") instanceof String symbol)) return null;
        ForeignBitcode link = null;
        for (var candidate : links) if (candidate.getUnit().equals(unit) && candidate.getSymbols().contains(symbol)) { link = candidate; break; }
        if (link == null) return null;
        boolean time = "Data.Time.Clock.Internal.CTimespec".equals(link.getModule());
        if (!time && !"System.CPUTime.Posix.ClockGetTime".equals(link.getModule())) throw failProof("unsupported module");
        if (!descriptor.keySet().equals(DESCRIPTOR_KEYS) || !integer(descriptor.get("schema"), 1) ||
            !target.keySet().equals(Set.of("kind", "symbol", "unit", "isFunction")) ||
            !"static".equals(target.get("kind")) || !Boolean.TRUE.equals(target.get("isFunction")) ||
            !"capi".equals(descriptor.get("convention")) || !"unsafe".equals(descriptor.get("safety")))
            throw failProof("static target, calling convention or safety");
        if (!(descriptor.get("argumentReps") instanceof List<?> declared)) throw failProof("argument representations");
        boolean zero = switch (link.getAbi().get(symbol)) {
            case "clock-id" -> { if (time) throw failProof("base clock ABI in time module"); yield true; }
            case "clock-buffer" -> { if (time) throw failProof("base clock ABI in time module"); yield false; }
            case "time-clock-id" -> { if (!time) throw failProof("time clock ABI in base module"); yield true; }
            case "time-clock-time", "time-clock-resolution" -> { if (!time) throw failProof("time clock ABI in base module"); yield false; }
            case null, default -> throw failProof("symbol lacks a linked CAPI ABI");
        };
        var word = time ? "Int32Rep" : "Word64Rep";
        var expected = zero ? Collections.<String>singletonList(null) : Arrays.asList(word, "AddrRep", null);
        var output = zero ? word : "Int32Rep";
        if (!integer(descriptor.get("arity"), expected.size()) || !integer(descriptor.get("suppliedArity"), expected.size()) ||
            declared.size() != expected.size() || argumentReps.size() != expected.size() || flags.size() != expected.size())
            throw failProof("actual or declared CAPI argument/result representation");
        for (var flag : flags) if (!Boolean.FALSE.equals(flag)) throw failProof("actual or declared CAPI argument/result representation");
        for (int i = 0; i < expected.size(); i++)
            if (!scalar(declared.get(i), expected.get(i), true) || !scalar(argumentReps.get(i), expected.get(i), false))
                throw failProof("actual or declared CAPI argument/result representation");
        if (!result(descriptor.get("resultRep"), output, true) || !result(meta.get("rep"), output, false) || !result(resultRep, output, false))
            throw failProof("actual or declared CAPI argument/result representation");
        return new CapiCall(unit, symbol, zero, time, "time-clock-resolution".equals(link.getAbi().get(symbol)));
    }

    public static void validateHead(List<?> function, boolean defined) {
        var metadata = CoreRepresentations.metadata(function);
        var proof = metadata != null && metadata.get("rep") instanceof Map<?, ?> map ? map : null;
        if (function.size() != 3 || !"var".equals(function.get(0)) || !(function.get(1) instanceof String name) ||
            name.isEmpty() || defined || proof == null || !proof.keySet().equals(SCALAR_KEYS) ||
            !"closure".equals(proof.get("kind")) || !List.of("BoxedRep (Just Lifted)").equals(proof.get("primReps")) ||
            !Boolean.TRUE.equals(proof.get("evaluated"))) throw failProof("unresolved declared foreign head");
    }

    public static void validateOperand(CapiCall call, int index, CoreRepresentation lowered, CoreRepresentation stored) {
        var primitive = call.zeroArgument() || index == 2 ? null : index == 1 ? "AddrRep" : call.timeClock() ? "Int32Rep" : "Word64Rep";
        var kind = switch (primitive) { case null -> CoreKind.VOID; case "AddrRep" -> CoreKind.ADDRESS; default -> CoreKind.LONG; };
        var reps = primitive == null ? List.of() : List.of(primitive);
        if (!lowered.getPresent() || lowered.isAggregate() || lowered.isVector() || lowered.getKind() != kind || !reps.equals(lowered.getPrimReps()))
            throw failProof("lowered operand " + index);
        if (stored != null && stored.getPresent() && (stored.isAggregate() || stored.isVector() ||
            (stored.getKind() != kind && stored.getKind() != CoreKind.UNKNOWN) ||
            (stored.getPrimReps() != null && !reps.equals(stored.getPrimReps())))) throw failProof("stored operand " + index);
    }
}
