// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import java.util.List;

/** Remaining formal inputs and result for an admitted alias or partial application. */
public record CoreFunctionSignature(List<CoreRepresentation> inputs, CoreRepresentation result) {
    public List<CoreRepresentation> getInputs() { return inputs; }
    public CoreRepresentation getResult() { return result; }
    // Remaining Kotlin callers destructure the existing two-field signature.
    public List<CoreRepresentation> component1() { return inputs; }
    public CoreRepresentation component2() { return result; }
}
