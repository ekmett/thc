// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

/** Owned standard info image and its identity key for a managed diagnostic frame. */
public record ManagedStackFrameInfo(ManagedAddress standard, ManagedAddress key) {
    public ManagedAddress getStandard() { return standard; }
    public ManagedAddress getKey() { return key; }
}
