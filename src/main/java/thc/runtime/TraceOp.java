// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

public enum TraceOp {
    EVENT("traceEvent#", "event", 2), BINARY("traceBinaryEvent#", "binary", 3), MARKER("traceMarker#", "marker", 2);
    private final String primitive, label;
    private final int arity;
    TraceOp(String primitive, String label, int arity) { this.primitive = primitive; this.label = label; this.arity = arity; }
    public String getPrimitive() { return primitive; }
    public String getLabel() { return label; }
    public int getArity() { return arity; }
    public static TraceOp named(String name) {
        return switch (name) { case "traceEvent#" -> EVENT; case "traceBinaryEvent#" -> BINARY; case "traceMarker#" -> MARKER; default -> null; };
    }
}
