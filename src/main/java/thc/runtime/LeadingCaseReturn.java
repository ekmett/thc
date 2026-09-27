// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/** Immutable partial-inlining recipe, fixed before the lowered root is published. */
public final class LeadingCaseReturn {
    public static final String LEADING_CASE_RETURN_PROPERTY = "thc.leadingCaseReturn";
    private final DataLayout layout;
    private final int scrutineeIndex;
    private final int resultIndex;
    private final CoreSourceLocation source;
    private LeadingCaseReturn(DataLayout layout, int scrutineeIndex, int resultIndex, CoreSourceLocation source) {
        this.layout = layout; this.scrutineeIndex = scrutineeIndex; this.resultIndex = resultIndex; this.source = source;
    }
    public DataLayout getLayout() { return layout; }
    public int getScrutineeIndex() { return scrutineeIndex; }
    public int getResultIndex() { return resultIndex; }
    public CoreSourceLocation getSource() { return source; }
    public static LeadingCaseReturn discover(List<? extends Map<String, ?>> args, List<?> expression,
            CoreRepresentation result, int argumentOffset, Set<String> usedFormals, boolean hasCaptures,
            Function<String, DataLayout> dataLayout, CoreSources sources, CoreSourceLocation inheritedSource) {
        if (!Boolean.getBoolean(LEADING_CASE_RETURN_PROPERTY) || !result.isLong() || !"case".equals(expression.getFirst())) return null;
        HashSet<String> ids = new HashSet<>();
        for (Map<String, ?> arg : args) ids.add((String) arg.get("id"));
        if (hasCaptures || ids.size() != args.size()) return null;
        List<?> scrutinee = (List<?>) expression.get(1);
        if (!"var".equals(scrutinee.getFirst())) return null;
        int scrutineeIndex = indexOf(args, scrutinee.get(1));
        if (scrutineeIndex < 0) return null;
        CoreKind kind = CoreRepresentations.INSTANCE.binder(args.get(scrutineeIndex)).getKind();
        if (kind != CoreKind.UNKNOWN && kind != CoreKind.OBJECT && kind != CoreKind.DATA) return null;
        HashSet<String> seen = new HashSet<>();
        for (Object raw : (List<?>) expression.get(3)) {
            List<?> arm = (List<?>) raw;
            if (!"data".equals(arm.getFirst())) continue;
            String id = (String) arm.get(1);
            // Earlier duplicate alternatives retain priority.
            if (!seen.add(id) || !((List<?>) arm.get(2)).isEmpty()) continue;
            List<?> value = (List<?>) arm.get(3);
            if (!"var".equals(value.getFirst()) || Objects.equals(value.get(1), expression.get(2))) continue;
            int resultIndex = indexOf(args, value.get(1));
            if (resultIndex < 0 || resultIndex == scrutineeIndex ||
                !CoreRepresentations.INSTANCE.binder(args.get(resultIndex)).isLong()) continue;
            boolean otherLive = false;
            for (String formal : usedFormals)
                if (!Objects.equals(formal, scrutinee.get(1)) && !Objects.equals(formal, value.get(1))) otherLive = true;
            if (otherLive) continue;
            DataLayout layout = dataLayout.apply(id);
            if (layout.getArity() != 0) continue;
            CoreSourceLocation location = sources.expression(value, sources.expression(expression, inheritedSource));
            return new LeadingCaseReturn(layout, scrutineeIndex + argumentOffset, resultIndex + argumentOffset, location);
        }
        return null;
    }
    private static int indexOf(List<? extends Map<String, ?>> args, Object id) {
        for (int i = 0; i < args.size(); i++) if (Objects.equals(args.get(i).get("id"), id)) return i;
        return -1;
    }
}
