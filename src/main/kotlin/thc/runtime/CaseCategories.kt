// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime

internal const val TYPED_CASES_PROPERTY = "thc.typedCases"
internal enum class CaseCategory { GENERIC, DATA, LONG, DEFAULT_ONLY }

/** A case category is not a proof of constructor-family membership or exhaustiveness. */
internal fun caseCategory(proof: CoreRepresentation, kinds: List<Int>, literalsAreLong: Boolean): CaseCategory {
    if (!java.lang.Boolean.getBoolean(TYPED_CASES_PROPERTY)) return CaseCategory.GENERIC
    val explicit = kinds.filter { it != 0 }
    return when {
        explicit.isEmpty() && kinds.isNotEmpty() -> CaseCategory.DEFAULT_ONLY
        proof.kind == CoreKind.DATA && explicit.all { it == 1 } -> CaseCategory.DATA
        proof.isLong && literalsAreLong && explicit.all { it == 2 } -> CaseCategory.LONG
        else -> CaseCategory.GENERIC
    }
}
