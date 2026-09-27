// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

/** Re-enter saved caller scopes while its exact child remains parked. */
public final class AstChildSuspension {
    private final Object child;
    private final AsyncRequest request;
    public AstChildSuspension(Object child, AsyncRequest request) { this.child = child; this.request = request; }
    public Object getChild() { return child; }
    public AsyncRequest getRequest() { return request; }
}
