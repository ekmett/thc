// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

/** A known implementation gap, distinct from malformed Core or runtime errors. */
public final class UnsupportedCore extends RuntimeFault {
    public UnsupportedCore(String message) { super(message); }
}
