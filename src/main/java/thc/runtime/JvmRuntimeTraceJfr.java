// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import jdk.jfr.FlightRecorder;

final class JvmRuntimeTraceJfr implements RuntimeTraceJfr {
    static final JvmRuntimeTraceJfr INSTANCE = new JvmRuntimeTraceJfr();
    private JvmRuntimeTraceJfr() {}

    @Override public long support() {
        try { return FlightRecorder.isAvailable() ? 0L : RuntimeServiceStatus.UNSUPPORTED; }
        catch (SecurityException ignored) { return RuntimeServiceStatus.DENIED; }
    }

    @Override public long emit(long contextId, String phase, long token, String name, long elapsedNanos, String payloadHex) {
        long available = support();
        if (available != 0L) return available;
        try {
            var event = new RuntimeTraceEvent();
            if (!event.isEnabled()) return RuntimeServiceStatus.DISABLED;
            event.contextId = contextId;
            event.phase = phase;
            event.spanId = token;
            event.message = name;
            event.elapsedNanos = elapsedNanos;
            event.payloadHex = payloadHex;
            event.commit();
            return 0L;
        } catch (SecurityException ignored) { return RuntimeServiceStatus.DENIED; }
        catch (IllegalStateException ignored) { return RuntimeServiceStatus.UNAVAILABLE; }
    }
}
