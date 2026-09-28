// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.Assumption;
import thc.Language;
import thc.PackageScalarSignature;

/** The context owns this callable and its lifetime; call sites own interop nodes. */
public final class PackageScalarFunction {
    private final Language.State owner;
    private final PackageScalarSignature signature;
    private final Object receiver;
    private final Assumption alive;
    public PackageScalarFunction(Language.State owner, PackageScalarSignature signature, Object receiver, Assumption alive) {
        this.owner = owner; this.signature = signature; this.receiver = receiver; this.alive = alive;
    }
    public Language.State getOwner() { return owner; }
    public PackageScalarSignature getSignature() { return signature; }
    public Object getReceiver() { return receiver; }
    public Assumption getAlive() { return alive; }
}
