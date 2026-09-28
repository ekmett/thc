// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;
public enum MaskingState {
    UNMASKED(0), MASKED_UNINTERRUPTIBLE(1), MASKED_INTERRUPTIBLE(2);
    private final long tag;
    MaskingState(long tag) { this.tag = tag; }
    public long getTag() { return tag; }
}
