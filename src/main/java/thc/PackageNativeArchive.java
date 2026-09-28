// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc;

import java.util.*;

/** A retained obligation is a prohibition, never a foreign execution capability. */
public final class PackageNativeArchive {
    private final String detail, unit;
    private final boolean wholeModule;
    private final List<Map<?,?>> excluded, unavailable;
    public PackageNativeArchive(String detail, boolean wholeModule, String unit, List<Map<?,?>> excluded) { this(detail, wholeModule, unit, excluded, List.of()); }
    public PackageNativeArchive(String detail, boolean wholeModule, String unit, List<Map<?,?>> excluded, List<Map<?,?>> unavailable) {
        this.detail = detail; this.wholeModule = wholeModule; this.unit = unit; this.excluded = excluded; this.unavailable = unavailable;
    }
    public String getDetail() { return detail; }
    public boolean getWholeModule() { return wholeModule; }
    public List<Map<?,?>> getExcluded() { return excluded; }
    public boolean blocks(Object binding) {
        if (wholeModule) return true;
        for (var call : calls(binding)) {
            if (!(call.get("target") instanceof Map<?,?> target) || !Objects.equals(target.get("unit"), unit)) continue;
            for (var emitted : excluded) if (Objects.equals(target.get("symbol"), emitted.get("symbol")) && Objects.equals(call.get("convention"), emitted.get("convention")) && Objects.equals(call.get("safety"), emitted.get("safety"))) return true;
            for (var entry : unavailable) {
                List<?> actual = call.get("argumentReps") instanceof List<?> args ? args.stream().map(arg -> arg instanceof Map<?,?> map ? map.get("primReps") : null).toList() : null;
                var expected = new ArrayList<List<?>>();
                for (Object rep : (List<?>) entry.get("arguments")) expected.add(Collections.singletonList(Objects.equals(rep, "ByteArray#") || Objects.equals(rep, "MutableByteArray#") ? "BoxedRep (Just Unlifted)" : rep));
                expected.add(List.of());
                if (Objects.equals(target.get("symbol"), entry.get("symbol")) && Objects.equals(call.get("convention"), entry.get("convention")) && Objects.equals(call.get("safety"), entry.get("safety")) && Objects.equals(actual, expected)) return true;
            }
        }
        return false;
    }
    public static List<Map<?,?>> calls(Object value) {
        var calls = new ArrayList<Map<?,?>>();
        if (value instanceof Map<?,?> fields) {
            if (fields.get("foreignCall") instanceof Map<?,?> call) calls.add(call);
            for (Object child : fields.values()) calls.addAll(calls(child));
        } else if (value instanceof List<?> fields) for (Object child : fields) calls.addAll(calls(child));
        return calls;
    }
}
