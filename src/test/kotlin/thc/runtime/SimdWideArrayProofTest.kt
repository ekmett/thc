// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime

import org.junit.jupiter.api.Test

class SimdWideArrayProofTest {
    private val cases = VectorArrayProofCases(true)
    @Test fun allOperationsPreserveEveryByteAcrossAliasesTailsAndBothBackends() = cases.allOperationsPreserveEveryByteAcrossAliasesTailsAndBothBackends()
    @Test fun invalidFullWidthRangesAndTokensDoNotModifyStorage() = cases.invalidFullWidthRangesAndTokensDoNotModifyStorage()
    @Test fun wrongPhysicalCarrierSpeciesAndReadTupleProofsRejectOnBothBackends() = cases.wrongPhysicalCarrierSpeciesAndReadTupleProofsRejectOnBothBackends()
    @Test fun firstInstalledCallHasExactOneGuestEntryForAllOperations() = cases.firstInstalledCallHasExactOneGuestEntryForAllOperations()
}
