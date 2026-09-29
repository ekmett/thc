// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import thc.PackageScalarLink;

/** Link-time proof only; no foreign execution or dynamic exception type dispatch. */
public final class CoreForeignExceptionBridge {
    private CoreForeignExceptionBridge() {}
    private static final String MODULE = "THC.Internal.Exception";
    private static final String EXCEPTION = "ghc-internal:GHC.Internal.Exception.Type.SomeException";

    @SuppressWarnings("unchecked")
    public static Map<String, Object> read(Map<String, ?> module) {
        var raw = module.get("foreignExceptionBridge");
        if (raw == null) return null;
        if (!(raw instanceof Map<?, ?>)) throw RuntimeFault.fault("Malformed foreign exception bridge");
        var proof = (Map<String, Object>) raw;
        if (!(module.get("unit") instanceof String unit)) throw RuntimeFault.fault("Bridge has no GHC unit");
        var prefix = unit + ":" + MODULE + ".";
        if (!(proof.keySet().equals(Set.of("schema", "unit", "module", "box", "project", "payloadType", "exceptionType")) &&
            (Integer.valueOf(1).equals(proof.get("schema")) || Long.valueOf(1L).equals(proof.get("schema"))) &&
            unit.equals(proof.get("unit")) && MODULE.equals(module.get("module")) && MODULE.equals(proof.get("module")) &&
            (prefix + "boxForeign").equals(proof.get("box")) && (prefix + "projectForeign").equals(proof.get("project")) &&
            (prefix + "ForeignException").equals(proof.get("payloadType")) && EXCEPTION.equals(proof.get("exceptionType"))))
            throw new IllegalArgumentException("Invalid foreign exception bridge identity");
        if (!(module.get("bindings") instanceof List<?> bindings)) throw RuntimeFault.fault("Bridge has no bindings");
        for (var name : List.of("box", "project")) {
            Map<String, Object> binding = null;
            for (var candidate : bindings) {
                var item = (Map<String, Object>) candidate;
                if (Objects.equals(item.get("id"), proof.get(name))) {
                    if (binding != null) { binding = null; break; }
                    binding = item;
                }
            }
            if (binding == null) throw RuntimeFault.fault("Bridge helper is not defined by its genuine module: " + proof.get(name));
            if (!(binding.get("expr") instanceof List<?>)) throw new IllegalArgumentException("Invalid foreign exception helper body");
        }
        return proof;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> select(Map<String, ?> module) {
        List<Map<String, Object>> proofs;
        if (module.get("foreignExceptionBridges") instanceof List<?> values) {
            proofs = (List<Map<String, Object>>) (List<?>) values;
        } else {
            var proof = read(module);
            proofs = proof == null ? List.of() : List.of(proof);
        }
        var unit = module.get("foreignExceptionBridgeUnit");
        if (!(unit == null || unit instanceof String text && !blank(text)))
            throw new IllegalArgumentException("Invalid foreign exception bridge unit");
        Map<String, Object> selected = null;
        if (unit == null) {
            if (proofs.size() == 1) selected = proofs.getFirst();
        } else {
            for (var proof : proofs) {
                if (Objects.equals(proof.get("unit"), unit)) {
                    if (selected != null) { selected = null; break; }
                    selected = proof;
                }
            }
        }
        if (selected != null) return selected;
        throw RuntimeFault.fault(proofs.isEmpty() ? "Foreign execution requires a genuine THC.Exception runtime bundle"
            : "Missing or ambiguous foreign exception bridge unit; select the application's exact runtime unit");
    }

    // Blank names include Unicode whitespace and space characters.
    private static boolean blank(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (!Character.isWhitespace(c) && !Character.isSpaceChar(c)) return false;
        }
        return true;
    }

    /** These nodes execute a foreign language. Native errno-returning adapters
     * and constructing descriptors alone do not require an exception bridge. */
    @SuppressWarnings("unchecked")
    public static boolean executes(List<?> expr, boolean defined, List<PackageScalarLink> links) {
        var metadata = CoreRepresentations.metadata((List<Object>) expr);
        if (metadata == null) return false;
        if (!(metadata.get("foreignCall") instanceof Map<?, ?> call)) return false;
        if (!(call.get("target") instanceof Map<?, ?> target)) return false;
        String symbol = target.get("symbol") instanceof String value ? value : "dynamic".equals(target.get("kind")) ? "<dynamic>" : null;
        if (symbol == null) return false;
        var runtime = CoreRuntimeServices.validate(expr, defined);
        if (runtime != null) return runtime == RuntimeServiceCall.EXCEPTION_TEXT;
        if (CoreCpuAffinity.validate(expr, defined) != null) return false;
        if (CoreForeignOverride.select(metadata) != null) return false;
        if (!(expr.size() > 2 && expr.get(2) instanceof List<?> arguments)) return false;
        if (!(expr.size() > 3 && expr.get(3) instanceof List<?> flags)) return false;
        var proofs = new ArrayList<Object>(arguments.size());
        for (var argument : arguments) {
            var proof = CoreRepresentations.metadata((List<Object>) argument);
            proofs.add(proof == null ? null : proof.get("rep"));
        }
        var scalar = CorePackageScalarForeign.validate(metadata, proofs, flags, metadata.get("rep"), links);
        if (scalar != null) {
            CoreBoundThreadForeign.validateHead((List<?>) expr.get(1), defined);
            return scalar.executesForeign();
        }
        var string = TruffleStringOp.validate((List<Object>) expr, defined);
        if (string != null) return string.parsesNumber();
        if (CoreJavaScript.validate((List<Object>) expr, defined) != null) return true;
        for (var operation : PolyglotOp.values()) {
            if (operation.getSymbol().equals(symbol)) {
                CorePolyglot.validate((List<Object>) expr, defined);
                return true;
            }
        }
        return false;
    }
}
