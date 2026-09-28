// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

/** Cold caller input distinguishes a child result from its guest failure. */
public final class ChildResume {
    private final Object value;
    private final RuntimeException failure;
    public ChildResume(Object value, RuntimeException failure) { this.value = value; this.failure = failure; }
    public Object getValue() { return value; }
    public RuntimeException getFailure() { return failure; }
}
