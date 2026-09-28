// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import java.util.Map;
import java.util.function.Function;

/** One program retains the exact genuine GHC boxer/projector selected when linked. */
public final class ForeignExceptionBridge {
    private final String unit;
    private final String boxId;
    private final String projectId;
    private final Function<String, Object> binding;
    private final Function<String, DataLayout> layout;
    private volatile ForeignExceptionRegistry.Projector resolvedProjector;
    public ForeignExceptionBridge(String unit, String boxId, String projectId,
                                  Function<String, Object> binding, Function<String, DataLayout> layout) {
        this.unit = unit; this.boxId = boxId; this.projectId = projectId; this.binding = binding; this.layout = layout;
    }
    public String getUnit() { return unit; }
    public synchronized ForeignExceptionRegistry.Projector projector(Closure closure) {
        if (resolvedProjector == null) resolvedProjector =
            new ForeignExceptionRegistry.Projector(this, closure, layout.apply(CoreExceptionPayload.TYPE));
        return resolvedProjector;
    }
    public Object box() { return binding.apply(boxId); }
    public Object project() { return binding.apply(projectId); }
    public static ForeignExceptionBridge bind(Map<String, ?> data, Function<String, Object> binding, Function<String, DataLayout> layout) {
        if (!(data.get("selectedForeignExceptionBridge") instanceof Map<?, ?> proof)) return null;
        return new ForeignExceptionBridge((String) proof.get("unit"), (String) proof.get("box"), (String) proof.get("project"), binding, layout);
    }
}
