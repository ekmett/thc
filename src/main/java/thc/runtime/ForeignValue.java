// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import thc.vm.Lifted;
import thc.Language;
/** The handle owns a managed foreign value, never an address into either heap. */
public final class ForeignValue implements Lifted {
    private final Language.State owner;
    private final Object receiver;
    public ForeignValue(Language.State owner, Object receiver) { this.owner = owner; this.receiver = receiver; }
    public Language.State getOwner() { return owner; }
    public Object getReceiver() { return receiver; }
    /** This carrier is already a terminal language value. */
    @Override public Lifted resolve() { return null; }
    /** This value exposes no constructor-field projections. */
    @Override public Lifted project(int field) { return null; }
}
