// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

public record GuestThreadSnapshot(long status, long capability, long locked) {
    public long getStatus() { return status; }
    public long getCapability() { return capability; }
    public long getLocked() { return locked; }
}
