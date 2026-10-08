// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

/** Cold caller input distinguishes a child result from its guest failure. */
public final class ChildResume {
    private Object value;
    private RuntimeException failure;
    public ChildResume(Object value, RuntimeException failure) { this.value = value; this.failure = failure; }
    public Object getValue() { return value; }
    public RuntimeException getFailure() { return failure; }
    /** Transfer only after the caller has validated the exact completed child. */
    public Object takeValue() { Object result = value; value = null; return result; }
    public RuntimeException takeFailure() { RuntimeException result = failure; failure = null; return result; }
}
