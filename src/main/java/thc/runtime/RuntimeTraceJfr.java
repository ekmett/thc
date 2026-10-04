// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

/** Provider boundary keeps optional JFR availability and denied controls testable. */
public interface RuntimeTraceJfr {
    long support();
    long emit(long contextId, String phase, long token, String name, long elapsedNanos, String payloadHex);
}
