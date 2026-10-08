// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

/** Original CAPI wrappers may occur in another module's optimized binding.
 * Resolve only declarations named by the binding currently being prepared;
 * neither unrelated module headers nor their binding bodies are needed. */
final class CoreCapiProvenance {
    private record Owner(String unit, String module) {}

    private CoreCapiProvenance() {}

    static Map<String, Object> supplement(Map<String, ?> input, Object binding,
            BiFunction<String, String, ForeignBitcode> resolve) {
        var calls = new LinkedHashMap<Owner, List<Object>>();
        for (Map<?, ?> call : CoreCallInventory.mapCalls(binding)) {
            // Newly acquired packages use the ordinary component linker. Old
            // captured clock artifacts still retain their original CAPI route.
            if (call.get("target") instanceof Map<?, ?> target && input.get("packageScalarLinks") instanceof List<?> nativeLinks
                    && nativeLinks.stream().anyMatch(value -> value instanceof PackageScalarLink link
                        && link.getUnit().equals(target.get("unit")) && link.getAbi().stream().anyMatch(signature ->
                            signature.symbol().equals(target.get("symbol")) && signature.convention().equals(call.get("convention"))))) continue;
            Owner owner = owner(call);
            if (owner != null) {
                calls.computeIfAbsent(owner, ignored -> new ArrayList<>())
                        .add(Map.of("foreignCall", call));
            }
        }
        var links = new ArrayList<ForeignBitcode>();
        for (Object value : (List<?>) input.get("foreignLinks")) links.add((ForeignBitcode) value);
        for (var entry : calls.entrySet()) {
            Owner owner = entry.getKey();
            ForeignBitcode link = null;
            for (ForeignBitcode candidate : links) {
                if (candidate.getUnit().equals(owner.unit) && candidate.getModule().equals(owner.module)) {
                    if (link != null) throw new IllegalArgumentException("Duplicate CAPI declaration owner");
                    link = candidate;
                }
            }
            if (link == null) {
                link = resolve.apply(owner.unit, owner.module);
                if (link == null || !owner.unit.equals(link.getUnit()) || !owner.module.equals(link.getModule())) {
                    throw new IllegalArgumentException("Missing original CAPI declaration: " + owner.unit + ":" + owner.module);
                }
                links.add(link);
            }
            // A wrapper name selects provenance, not permission to execute it.
            // Retain the exact original symbol, argument/result and safety ABI.
            CoreForeignArtifacts.validateCalls(entry.getValue(), link, false);
        }
        var result = new LinkedHashMap<String, Object>(input);
        result.put("foreignLinks", links);
        return result;
    }

    private static Owner owner(Map<?, ?> call) {
        if (!"capi".equals(call.get("convention")) || !(call.get("target") instanceof Map<?, ?> target)
                || !(target.get("unit") instanceof String unit) || !(target.get("symbol") instanceof String symbol)) {
            return null;
        }
        // These are the two original-stub families admitted by CoreForeignArtifacts.
        // Their complete source, target, headers, symbols and ABI are still validated
        // by module admission. Other CAPI calls retain the normal unsupported path.
        String module = unit.startsWith("time-") ? "Data.Time.Clock.Internal.CTimespec"
                : unit.startsWith("base-") ? "System.CPUTime.Posix.ClockGetTime" : null;
        if (module == null || !symbol.startsWith("ghczuwrapperZC")) return null;
        int separator = symbol.indexOf("ZC", "ghczuwrapperZC".length());
        if (separator == "ghczuwrapperZC".length() || separator < 0) return null;
        for (int i = "ghczuwrapperZC".length(); i < separator; i++) {
            if (symbol.charAt(i) < '0' || symbol.charAt(i) > '9') return null;
        }
        String prefix = "ZC" + unit.replace("-", "zm").replace(".", "zi") + "ZC"
                + module.replace(".", "zi") + "ZC";
        return symbol.startsWith(prefix, separator) ? new Owner(unit, module) : null;
    }
}
