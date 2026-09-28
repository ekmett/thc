// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;
/** A private thread-local result-register token, never a payload carrier. */
public final class TupleComplete {
    public static final TupleComplete INSTANCE = new TupleComplete();
    private TupleComplete() {}
}
