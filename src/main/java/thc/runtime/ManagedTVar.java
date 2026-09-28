// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

/** Payloads are never forced or compared with equals, and retain their context. */
public final class ManagedTVar {
    final ManagedSTM owner;
    Object value;
    Object revision = new Object();
    public ManagedTVar(ManagedSTM owner, Object value) { this.owner = owner; this.value = value; }
    public ManagedSTM getOwner() { return owner; }
    public Object getValue() { return value; }
    public void setValue(Object value) { this.value = value; }
    public Object getRevision() { return revision; }
    public void setRevision(Object revision) { this.revision = revision; }
}
