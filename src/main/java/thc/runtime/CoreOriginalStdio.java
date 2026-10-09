// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.nodes.Node;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import thc.Language;
import thc.PackageScalarLink;

public final class CoreOriginalStdio {
    private CoreOriginalStdio() {}
    // Preserve the genuine installed owner; only this pinned Unix release is reviewed.
    private static final Pattern UNIX_UNIT = Pattern.compile("unix-2\\.8\\.8\\.0-(?:inplace|[0-9a-f]+)");
    private static final Set<String> SCALAR_KEYS = Set.of("kind", "primReps", "evaluated");
    private static final Set<String> TUPLE_KEYS = Set.of("kind", "primReps", "evaluated", "aggregate", "components");
    private static final Set<String> DESCRIPTOR_KEYS = Set.of("schema", "target", "convention", "safety", "arity", "suppliedArity", "argumentReps", "resultRep");

    public static boolean isOriginalUnixUnit(Object unit) {
        return unit instanceof String text && UNIX_UNIT.matcher(text).matches();
    }
    @TruffleBoundary public static long waitStatus(Node node, OriginalStdioOp operation, long status) {
        requireProof(operation.getWaitStatus(), "wait-status operation");
        var state = Language.currentState(node);
        var previous = state.getThreads().enterForeign(ForeignSafety.UNSAFE);
        try { return state.cbits().waitStatus(operation, (int) status); }
        finally { state.getThreads().leaveForeign(previous); }
    }
    public static ManagedStdio current(Node node) { return Language.currentState(node).getStdio(); }
    public static NativeDirectoryStreams directories(Node node) { return NativeFileProvider.current().getDirectoryStreams(); }
    public static RtsFileLocks locks(Node node) { return Language.currentState(node).getRtsFileLocks(); }
    public static ManagedIconv iconv(Node node) { return Language.currentState(node).getIconv(); }

    /** Windows uses real process CRT descriptors. Keep the original package
     * adapter and its strict ABI proof instead of manufacturing ManagedFiles IDs. */
    static PackageScalarCall windowsOpening(OriginalStdioOp operation, Object metadata, List<?> arguments,
                                           List<?> flags, Object result, List<PackageScalarLink> links) {
        if (!WindowsDirectoryStreams.supportedHost() || operation == null || !operation.getOpening() ||
                !"Word16Rep".equals(operation.getArguments().get(2))) return null;
        var call = CorePackageScalarForeign.validate(metadata, arguments, flags, result, links);
        requireProof(call != null && windowsOpening(call), "Windows opening requires its real selected-package native adapter");
        return call;
    }
    static boolean windowsOpening(PackageScalarCall call) {
        return WindowsDirectoryStreams.supportedHost() && call.getKind() == PackageScalarCall.Kind.STATIC &&
            "ghc-internal".equals(call.getLink().getUnit()) && call.getLink().getTarget().startsWith("x86_64-") &&
            call.getLink().getTarget().contains("-windows-") && call.getLink().getNativeLibrary().length != 0 &&
            "__hscore_open".equals(call.getSignature().getSymbol()) && "ccall".equals(call.getSignature().getConvention()) &&
            List.of("AddrRep", "Int32Rep", "Word16Rep").equals(call.getSignature().getArguments()) &&
            "Int32Rep".equals(call.getResult());
    }

    private static void requireProof(boolean condition, String detail) {
        if (!condition) throw new RuntimeFault("Invalid original stdio call: " + detail);
    }
    private static boolean exactInteger(Object value, int expected) {
        return (value instanceof Integer || value instanceof Long) && ((Number) value).longValue() == expected;
    }

    /** An occurrence certificate cannot relabel a stored foreign operand. */
    public static void validateScalarOperand(OriginalStdioOp operation, int index, CoreRepresentation lowered, CoreRepresentation stored) {
        requireProof(operation.getUnixNative() || operation.getProcessIdentity() || operation == OriginalStdioOp.SET_ERRNO || operation.getEventDescriptor() || operation.getWaitStatus() || operation.getPathRemoval() || operation.getFlagConstant() || operation.getFcntl() || operation.getReadiness() || operation.getSeekConstant() || operation.getStat() || operation.getTermios() || operation.getSavedTermios() || operation.getReadImage() || operation.getPathStat() || operation.getPathMode() || operation == OriginalStdioOp.ACCESS || operation == OriginalStdioOp.UNLINKAT || operation == OriginalStdioOp.FSTATAT || operation.getPathLink() || operation.getCurrentDirectory() || operation.getDirectoryStream() || operation == OriginalStdioOp.TCSETATTR || operation.getOpening() || operation.getIconv() || operation.getDuplication() || operation.getLocking(),
            "strict operand operation");
        var primitive = operation.getArguments().get(index);
        var kind = switch (primitive) { case null -> CoreKind.VOID; case "AddrRep" -> CoreKind.ADDRESS; default -> CoreKind.LONG; };
        var reps = primitive == null ? List.of() : List.of(primitive);
        requireProof(lowered.getPresent() && !lowered.isAggregate() && !lowered.isVector() && lowered.getKind() == kind && reps.equals(lowered.getPrimReps()),
            "lowered foreign operand " + index);
        if (stored != null && stored.getPresent())
            requireProof(!stored.isAggregate() && !stored.isVector() && (stored.getKind() == kind || stored.getKind() == CoreKind.UNKNOWN) &&
                (stored.getPrimReps() == null || reps.equals(stored.getPrimReps())), "stored foreign operand " + index);
    }

    public static void validateHead(List<?> function, boolean defined) {
        var metadata = CoreRepresentations.metadata(function);
        var proof = metadata != null && metadata.get("rep") instanceof Map<?, ?> map ? map : null;
        requireProof(function.size() == 3 && "var".equals(function.get(0)) && function.get(1) instanceof String name &&
            !name.isEmpty() && !defined && proof != null && proof.keySet().equals(SCALAR_KEYS) &&
            "closure".equals(proof.get("kind")) && List.of("BoxedRep (Just Lifted)").equals(proof.get("primReps")) &&
            Boolean.TRUE.equals(proof.get("evaluated")), "unresolved declared foreign variable required");
    }

    /** Reject malformed heads before generic call/capture analysis casts their IDs. */
    public static void validateHeads(Object value) {
        if (value instanceof Map<?, ?> map) {
            for (var child : map.values()) validateHeads(child);
        } else if (value instanceof List<?> list) {
            if (!list.isEmpty() && "app".equals(list.getFirst())) {
                var meta = list.size() > 6 && list.get(6) instanceof Map<?, ?> map ? map : null;
                var descriptor = meta != null && meta.get("foreignCall") instanceof Map<?, ?> map ? map : null;
                var target = descriptor != null && descriptor.get("target") instanceof Map<?, ?> map ? map : null;
                for (var operation : OriginalStdioOp.values()) {
                    if (operation.matchesSymbol(target == null ? null : target.get("symbol"))) {
                        if (list.size() <= 1 || !(list.get(1) instanceof List<?> head))
                            throw new RuntimeFault("Invalid original stdio call: missing variable head");
                        validateHead(head, false);
                        break;
                    }
                }
            }
            for (var child : list) validateHeads(child);
        }
    }

    private static boolean scalar(Object raw, String primitive, boolean declared) {
        if (!(raw instanceof Map<?, ?> value)) return false;
        var kind = switch (primitive) { case null -> "void"; case "AddrRep" -> "address"; default -> "long"; };
        return value.keySet().equals(SCALAR_KEYS) && kind.equals(value.get("kind")) &&
            (primitive == null ? List.of() : List.of(primitive)).equals(value.get("primReps")) &&
            value.get("evaluated") instanceof Boolean && (!declared || Boolean.FALSE.equals(value.get("evaluated")));
    }
    private static boolean result(Object raw, String primitive, boolean declared) {
        if (!(raw instanceof Map<?, ?> value) || !(value.get("components") instanceof List<?> components)) return false;
        if (!value.keySet().equals(TUPLE_KEYS) || !"unknown".equals(value.get("kind")) || !"unboxed-tuple".equals(value.get("aggregate")) ||
            !(primitive == null ? List.of() : List.of(primitive)).equals(value.get("primReps")) || !(value.get("evaluated") instanceof Boolean) ||
            declared && !Boolean.FALSE.equals(value.get("evaluated")) || components.size() != (primitive == null ? 1 : 2)) return false;
        for (int index = 0; index < components.size(); index++) {
            var component = components.get(index);
            if (!scalar(component, index == 0 ? null : primitive, false) || !Boolean.TRUE.equals(((Map<?, ?>) component).get("evaluated"))) return false;
        }
        return true;
    }
    private static boolean declaredArgumentsMatch(Object raw, List<String> expected) {
        if (!(raw instanceof List<?> declared) || declared.size() != expected.size()) return false;
        for (int index = 0; index < expected.size(); index++)
            if (!scalar(declared.get(index), expected.get(index), true)) return false;
        return true;
    }

    /** Caller binding names are irrelevant; raw FCallId proof must match exactly.
     * Unrecognized symbols retain ordinary unsupported-foreign handling. */
    public static OriginalStdioOp validate(Object metadata, List<?> argumentReps, List<?> flags, Object resultRep) {
        if (!(metadata instanceof Map<?, ?> meta) || !(meta.get("foreignCall") instanceof Map<?, ?> descriptor) ||
            !(descriptor.get("target") instanceof Map<?, ?> target) || !(target.get("symbol") instanceof String symbol)) return null;
        boolean recognized = false;
        OriginalStdioOp operation = null;
        for (var candidate : OriginalStdioOp.values()) {
            if (candidate.matchesSymbol(symbol)) {
                recognized = true;
                if (candidate.getConvention().equals(descriptor.get("convention")) && candidate.getSafety().equals(descriptor.get("safety"))) {
                    if (operation == null) operation = candidate;
                    if (declaredArgumentsMatch(descriptor.get("argumentReps"), candidate.getArguments())) {
                        operation = candidate;
                        break;
                    }
                }
            }
        }
        if (!recognized) return null;
        if (operation == null) throw new RuntimeFault("Invalid original stdio call: calling convention/safety");
        requireProof(descriptor.keySet().equals(DESCRIPTOR_KEYS) && exactInteger(descriptor.get("schema"), 1), "descriptor schema");
        requireProof(target.keySet().equals(Set.of("kind", "symbol", "unit", "isFunction")) && "static".equals(target.get("kind")) &&
            operation.acceptsUnit(target.get("unit")) && Boolean.TRUE.equals(target.get("isFunction")), "static original installed-library function target");
        // The strict unit grammar needs only these two z-encoding substitutions.
        requireProof(!symbol.contains("ZCunixzm") || CoreOriginalStdio.isOriginalUnixUnit(target.get("unit")) &&
            symbol.contains("ZC" + ((String) target.get("unit")).replace("-", "zm").replace(".", "zi") + "ZC"), "Unix wrapper owner");
        requireProof(operation != OriginalStdioOp.FSTATAT || symbol.equals(operation.getSymbol().replace("directoryzm1zi3zi10zi0zminplace",
            ((String) target.get("unit")).replace("-", "zm").replace(".", "zi"))), "Directory wrapper owner");
        requireProof(operation.getConvention().equals(descriptor.get("convention")) && operation.getSafety().equals(descriptor.get("safety")), "calling convention/safety");
        var expected = operation.getArguments();
        requireProof(exactInteger(descriptor.get("arity"), expected.size()) && exactInteger(descriptor.get("suppliedArity"), expected.size()), "saturated arity");
        requireProof(declaredArgumentsMatch(descriptor.get("argumentReps"), expected), "declared argument representations");
        boolean valid = argumentReps.size() == expected.size();
        for (int index = 0; valid && index < expected.size(); index++) valid = scalar(argumentReps.get(index), expected.get(index), false);
        requireProof(valid, "actual argument representations");
        valid = flags.size() == expected.size();
        for (var flag : flags) { if (!(flag instanceof Boolean value) || value) { valid = false; break; } }
        requireProof(valid, "unlifted argument flags");
        requireProof(result(descriptor.get("resultRep"), operation.getResult(), true) && result(meta.get("rep"), operation.getResult(), false) &&
            result(resultRep, operation.getResult(), false), "exact State/result tuple");
        return operation;
    }
}
