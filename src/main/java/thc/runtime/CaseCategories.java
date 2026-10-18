// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;
import java.util.List;

/** A category is not a constructor-family or exhaustiveness proof. */
public final class CaseCategories {
    private CaseCategories() {}
    public static final String TYPED_CASES_PROPERTY = "thc.typedCases";
    public static CaseCategory caseCategory(CoreRepresentation proof, List<Integer> kinds, boolean literalsAreLong) {
        boolean empty = true, data = true, longs = true;
        for (int kind : kinds) if (kind != 0) { empty = false; data &= kind == 1; longs &= kind == 2; }
        // A nondefault literal family with a proved Long carrier is ordinary lowering.
        if (!empty && proof.isLong() && literalsAreLong && longs) return CaseCategory.LONG;
        if (!Boolean.getBoolean(TYPED_CASES_PROPERTY)) return CaseCategory.GENERIC;
        if (empty && !kinds.isEmpty()) return CaseCategory.DEFAULT_ONLY;
        if (proof.getKind() == CoreKind.DATA && data) return CaseCategory.DATA;
        return CaseCategory.GENERIC;
    }
}
