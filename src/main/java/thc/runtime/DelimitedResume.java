// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;
import com.oracle.truffle.api.exception.AbstractTruffleException;
public final class DelimitedResume {
    private final Object value;
    private final AbstractTruffleException failure;
    public DelimitedResume(Object value) { this(value, null); }
    public DelimitedResume(Object value, AbstractTruffleException failure) { this.value = value; this.failure = failure; }
    public Object getValue() { return value; }
    public AbstractTruffleException getFailure() { return failure; }
    public Object get() { if (failure != null) throw failure; return value; }
}
