// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;
import thc.Language;
/** The handle owns a managed foreign value, never an address into either heap. */
public final class ForeignValue {
    private final Language.State owner;
    private final Object receiver;
    public ForeignValue(Language.State owner, Object receiver) { this.owner = owner; this.receiver = receiver; }
    public Language.State getOwner() { return owner; }
    public Object getReceiver() { return receiver; }
}
