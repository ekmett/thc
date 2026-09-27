// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

/** Result facts shared by identity-root and identity-edge eligibility.
 * Exact evaluated references are ordinary guest values, not borrowed result
 * storage. Forwarding the same object preserves its fields, closure environment
 * or address owner without entering any of them. Unknown and lazy references
 * still require their existing completion/force steps. */
final class AstTailResult {
    private AstTailResult() {}

    static boolean supports(CoreRepresentation proof) {
        return proof.getEvaluated() && !proof.isTypedTransport() &&
                (proof.isInt() || proof.isLong() || proof.isFloat() || proof.isDouble() ||
                 proof.referenceCarrier() != null);
    }

    /** Both roots have already passed supports; do not erase an exact carrier
     * merely because all reference values cross the Java Object return ABI. */
    static boolean sameCarrier(CoreRepresentation caller, CoreRepresentation callee) {
        return caller.getKind() == callee.getKind() && caller.isInt() == callee.isInt() &&
                caller.referenceCarrier() == callee.referenceCarrier();
    }
}
