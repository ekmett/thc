// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

/** Cold caller input distinguishes a child result from its guest failure. */
public final class ChildResume {
    private final Object value;
    private final GuestException failure;
    public ChildResume(Object value, GuestException failure) { this.value = value; this.failure = failure; }
    public Object getValue() { return value; }
    public GuestException getFailure() { return failure; }
}
