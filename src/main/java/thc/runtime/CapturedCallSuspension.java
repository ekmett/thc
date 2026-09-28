// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.exception.AbstractTruffleException;

/** Only the exact call edge may capture this returned continuation. */
public final class CapturedCallSuspension extends AbstractTruffleException implements InternalGuestControl {
    private final CallSegment segment;
    public CapturedCallSuspension(CallSegment segment) {
        super("Internal bytecode call suspension", null, 0, null);
        this.segment = segment;
    }
    public CallSegment getSegment() { return segment; }
}
