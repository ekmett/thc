// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.oracle.truffle.api.source.SourceSection;
import java.util.List;

/** Compiled-only shortcut attributed to the callee arm, without a synthetic frame. */
public final class LeadingCaseReturnNode extends Node {
    private final LeadingCaseReturn recipe;
    private final Metrics metrics;
    private final GuestRoot callee;
    @CompilerDirectives.CompilationFinal(dimensions = 1) private final Class<?>[] strictCarriers;
    @Child private EntryArguments entryArguments;
    public LeadingCaseReturnNode(GuestRoot callee, Metrics metrics, boolean[] knownEvaluated, int prefixSize) {
        this.callee = callee; this.recipe = callee.getLeadingCaseReturn(); this.metrics = metrics;
        entryArguments = new EntryArguments(callee.getCallTarget(), metrics, knownEvaluated, prefixSize, true);
        var strict = callee.getEntryStrict(); strictCarriers = new Class<?>[strict.length];
        for (int i = 0; i < strict.length; i++) if (strict[i])
            strictCarriers[i] = callee.getInputProofs().get(i).withEvaluated(true).referenceCarrier();
    }
    public void forceArguments(VirtualFrame frame, Object[] arguments) {
        // The original entry rejects a malformed primitive before strict demands.
        if (arguments[recipe.getResultIndex()] instanceof Long)
            entryArguments.executeCaptured(frame, arguments);
    }
    @Override public SourceSection getSourceSection() { return recipe.getSource() == null ? null : recipe.getSource().getSection(); }
    public List<CoreSourceNote> getCoreSourceNotes() { return recipe.getSource() == null ? List.of() : recipe.getSource().getNotes(); }
    @ExplodeLoop public Object execute(Object[] arguments) {
        if (!CompilerDirectives.inCompiledCode()) return null;
        if (!recipe.getLayout().matches(arguments[recipe.getScrutineeIndex()]) || !(arguments[recipe.getResultIndex()] instanceof Long)) return null;
        // A strict unused formal still has its declared reference carrier.
        // Fall back to the original entry for its normal rejection diagnostics.
        for (int i = 0; i < strictCarriers.length; i++) if (strictCarriers[i] != null &&
                !strictCarriers[i].isInstance(arguments[i + callee.getEntryArgumentOffset()])) return null;
        AsyncRequest request = GuestThreads.pollCurrent(callee, false);
        if (request != null) {
            request.compiledCapture = true;
            Object answer = arguments[recipe.getResultIndex()];
            throw new AstCapture(request, SynchronousMasking.current(this)).append((saved, input) -> {
                if (input != Unit.INSTANCE) throw new RuntimeFault("Invalid leading-case poll resume value");
                return returned(answer);
            });
        }
        return returned(arguments[recipe.getResultIndex()]);
    }
    private Object returned(Object answer) {
        if (metrics.getEnabled()) metrics.incrementLeadingCaseReturns();
        // Preserve the exact reference ABI object, without unboxing/reboxing.
        return answer;
    }
}
