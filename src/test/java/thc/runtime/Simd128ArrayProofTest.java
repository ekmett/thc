// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import org.junit.jupiter.api.Test;

class Simd128ArrayProofTest {
    private final VectorArrayProofCases cases = new VectorArrayProofCases(false);
    @Test void allOperationsPreserveEveryByteAcrossAliasesTailsAndBothBackends() throws Exception { cases.allOperationsPreserveEveryByteAcrossAliasesTailsAndBothBackends(); }
    @Test void invalidFullWidthRangesAndTokensDoNotModifyStorage() throws Exception { cases.invalidFullWidthRangesAndTokensDoNotModifyStorage(); }
    @Test void wrongPhysicalCarrierSpeciesAndReadTupleProofsRejectOnBothBackends() throws Exception { cases.wrongPhysicalCarrierSpeciesAndReadTupleProofsRejectOnBothBackends(); }
    @Test void firstInstalledCallHasExactOneGuestEntryForAllOperations() throws Exception { cases.firstInstalledCallHasExactOneGuestEntryForAllOperations(); }
}
