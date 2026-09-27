// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.source.SourceSection;
import java.util.List;

/** Compiled-only shortcut attributed to the callee arm, without a synthetic frame. */
public final class LeadingCaseReturnNode extends Node {
    private final LeadingCaseReturn recipe;
    private final Metrics metrics;
    public LeadingCaseReturnNode(LeadingCaseReturn recipe, Metrics metrics) { this.recipe = recipe; this.metrics = metrics; }
    @Override public SourceSection getSourceSection() { return recipe.getSource() == null ? null : recipe.getSource().getSection(); }
    public List<CoreSourceNote> getCoreSourceNotes() { return recipe.getSource() == null ? List.of() : recipe.getSource().getNotes(); }
    public Object execute(Object[] arguments) {
        if (!CompilerDirectives.inCompiledCode()) return null;
        if (!recipe.getLayout().matches(arguments[recipe.getScrutineeIndex()]) || !(arguments[recipe.getResultIndex()] instanceof Long)) return null;
        if (metrics.getEnabled()) metrics.incrementLeadingCaseReturns();
        // Preserve the exact reference ABI object, without unboxing/reboxing.
        return arguments[recipe.getResultIndex()];
    }
}
