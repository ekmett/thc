// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc;

import java.util.*;
import java.util.function.Supplier;

/** Retains cumulative admission counters, never unused source snapshots or projections. */
public final class CoreJsonLoadingStatistics implements Supplier<Map<String, Object>> {
    private final List<CoreJsonIndex.Counters> sources = new ArrayList<>();
    private final Set<CoreJsonBindings.Counters> adapters = new LinkedHashSet<>();
    public boolean isEmpty() { return sources.isEmpty(); }
    public void include(CoreJsonIndex source, CoreJsonBindings adapter) {
        sources.add(source.getCounters()); adapters.add(adapter.getCounters());
    }
    @Override public Map<String, Object> get() {
        var sourceTotals = sources.stream().map(CoreJsonIndex.Counters::statistics).toList();
        var adapterTotals = adapters.stream().map(CoreJsonBindings.Counters::statistics).toList();
        var totals = new LinkedHashMap<String, Object>();
        totals.put("jsonSourceBytes", sourceTotals.stream().mapToLong(s -> s.sourceByteSize()).sum());
        totals.put("jsonIndexPrimitiveBytes", sourceTotals.stream().mapToLong(s -> s.indexByteSize()).sum());
        totals.put("jsonSidecarBytes", sourceTotals.stream().mapToLong(s -> s.serializedByteSize() == null ? 0L : s.serializedByteSize()).sum());
        totals.put("jsonSourceFileBytesRead", sourceTotals.stream().mapToLong(s -> s.sourceFileBytesRead()).sum());
        totals.put("jsonSourceHashBytesScanned", sourceTotals.stream().mapToLong(s -> s.sourceHashBytesScanned()).sum());
        totals.put("jsonStructuralBytesScanned", sourceTotals.stream().mapToLong(s -> s.structuralBytesScanned()).sum());
        totals.put("jsonIndexSourceBytesScanned", sourceTotals.stream().mapToLong(s -> s.indexSourceBytesScanned()).sum());
        totals.put("jsonDecodedSpanCount", sourceTotals.stream().mapToLong(s -> s.decodedSpanCount()).sum());
        totals.put("jsonDecodedByteCount", sourceTotals.stream().mapToLong(s -> s.decodedByteCount()).sum());
        totals.put("jsonNavigationByteReads", sourceTotals.stream().mapToLong(s -> s.navigationByteReads()).sum());
        totals.put("jsonRegeneratedSourceBytes", sourceTotals.stream().mapToLong(s -> s.regeneratedSourceBytes()).sum());
        totals.put("jsonBindingHeaders", adapterTotals.stream().mapToLong(s -> s.bindingHeaders()).sum());
        totals.put("jsonBodyMaterializations", adapterTotals.stream().mapToLong(s -> s.bodyMaterializations()).sum());
        totals.put("jsonExpressionViews", adapterTotals.stream().mapToLong(s -> s.expressionViews()).sum());
        totals.put("jsonLinkingExpressionViews", adapterTotals.stream().mapToLong(s -> s.linkingExpressionViews()).sum());
        totals.put("jsonScalarDecodes", adapterTotals.stream().mapToLong(s -> s.scalarDecodes()).sum());
        totals.put("jsonLinkingScalarDecodes", adapterTotals.stream().mapToLong(s -> s.linkingScalarDecodes()).sum());
        totals.put("jsonSummaryExpressionsVisited", adapterTotals.stream().mapToLong(s -> s.summaryExpressionsVisited()).sum());
        totals.put("jsonCanonicalStrings", adapterTotals.stream().mapToInt(s -> s.canonicalStrings()).sum());
        return totals;
    }
}
