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
        var sourceTotals = new ArrayList<CoreJsonIndex.Statistics>();
        for (var source : sources) sourceTotals.add(source.statistics());
        var adapterTotals = new ArrayList<CoreJsonBindings.Statistics>();
        for (var adapter : adapters) adapterTotals.add(adapter.statistics());
        var totals = new LinkedHashMap<String, Object>();
        long jsonSourceBytes = 0;
        for (var item : sourceTotals) jsonSourceBytes += item.sourceByteSize();
        totals.put("jsonSourceBytes", jsonSourceBytes);
        long jsonIndexPrimitiveBytes = 0;
        for (var item : sourceTotals) jsonIndexPrimitiveBytes += item.indexByteSize();
        totals.put("jsonIndexPrimitiveBytes", jsonIndexPrimitiveBytes);
        long jsonSidecarBytes = 0;
        for (var item : sourceTotals) jsonSidecarBytes += item.serializedByteSize() == null ? 0L : item.serializedByteSize();
        totals.put("jsonSidecarBytes", jsonSidecarBytes);
        long jsonSourceFileBytesRead = 0;
        for (var item : sourceTotals) jsonSourceFileBytesRead += item.sourceFileBytesRead();
        totals.put("jsonSourceFileBytesRead", jsonSourceFileBytesRead);
        long jsonSourceHashBytesScanned = 0;
        for (var item : sourceTotals) jsonSourceHashBytesScanned += item.sourceHashBytesScanned();
        totals.put("jsonSourceHashBytesScanned", jsonSourceHashBytesScanned);
        long jsonStructuralBytesScanned = 0;
        for (var item : sourceTotals) jsonStructuralBytesScanned += item.structuralBytesScanned();
        totals.put("jsonStructuralBytesScanned", jsonStructuralBytesScanned);
        long jsonIndexSourceBytesScanned = 0;
        for (var item : sourceTotals) jsonIndexSourceBytesScanned += item.indexSourceBytesScanned();
        totals.put("jsonIndexSourceBytesScanned", jsonIndexSourceBytesScanned);
        long jsonDecodedSpanCount = 0;
        for (var item : sourceTotals) jsonDecodedSpanCount += item.decodedSpanCount();
        totals.put("jsonDecodedSpanCount", jsonDecodedSpanCount);
        long jsonDecodedByteCount = 0;
        for (var item : sourceTotals) jsonDecodedByteCount += item.decodedByteCount();
        totals.put("jsonDecodedByteCount", jsonDecodedByteCount);
        long jsonNavigationByteReads = 0;
        for (var item : sourceTotals) jsonNavigationByteReads += item.navigationByteReads();
        totals.put("jsonNavigationByteReads", jsonNavigationByteReads);
        long jsonRegeneratedSourceBytes = 0;
        for (var item : sourceTotals) jsonRegeneratedSourceBytes += item.regeneratedSourceBytes();
        totals.put("jsonRegeneratedSourceBytes", jsonRegeneratedSourceBytes);
        long jsonBindingHeaders = 0;
        for (var item : adapterTotals) jsonBindingHeaders += item.bindingHeaders();
        totals.put("jsonBindingHeaders", jsonBindingHeaders);
        long jsonBodyMaterializations = 0;
        for (var item : adapterTotals) jsonBodyMaterializations += item.bodyMaterializations();
        totals.put("jsonBodyMaterializations", jsonBodyMaterializations);
        long jsonExpressionViews = 0;
        for (var item : adapterTotals) jsonExpressionViews += item.expressionViews();
        totals.put("jsonExpressionViews", jsonExpressionViews);
        long jsonLinkingExpressionViews = 0;
        for (var item : adapterTotals) jsonLinkingExpressionViews += item.linkingExpressionViews();
        totals.put("jsonLinkingExpressionViews", jsonLinkingExpressionViews);
        long jsonScalarDecodes = 0;
        for (var item : adapterTotals) jsonScalarDecodes += item.scalarDecodes();
        totals.put("jsonScalarDecodes", jsonScalarDecodes);
        long jsonLinkingScalarDecodes = 0;
        for (var item : adapterTotals) jsonLinkingScalarDecodes += item.linkingScalarDecodes();
        totals.put("jsonLinkingScalarDecodes", jsonLinkingScalarDecodes);
        long jsonSummaryExpressionsVisited = 0;
        for (var item : adapterTotals) jsonSummaryExpressionsVisited += item.summaryExpressionsVisited();
        totals.put("jsonSummaryExpressionsVisited", jsonSummaryExpressionsVisited);
        int jsonCanonicalStrings = 0;
        for (var item : adapterTotals) jsonCanonicalStrings += item.canonicalStrings();
        totals.put("jsonCanonicalStrings", jsonCanonicalStrings);
        return totals;
    }
}
