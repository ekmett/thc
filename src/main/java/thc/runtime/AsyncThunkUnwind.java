// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

/** Async delivery carries its origin separately from the guest payload. */
public final class AsyncThunkUnwind extends RuntimeException {
    private final Object payload;
    public AsyncThunkUnwind(Object payload) { super("Asynchronous guest unwind"); this.payload = payload; }
    public Object getPayload() { return payload; }
}
