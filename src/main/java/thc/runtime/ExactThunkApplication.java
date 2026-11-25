// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.frame.VirtualFrame;

/** One unevaluated exact application; its ordinary thunk retains the update owner. */
final class ExactThunkApplication {
    record Plan(RootCallTarget owner, GlobalBinding function, thc.Language.State context) {}
    final Plan plan;
    final Object argument;
    ExactThunkApplication(Plan plan, Object argument) { this.plan = plan; this.argument = argument; }
    void requireOwner(com.oracle.truffle.api.nodes.Node node) {
        if (plan.context() != thc.Language.currentState(node))
            throw RuntimeFault.fault("Application thunk belongs to another context");
    }
    /** Preserve ownership if the unchanged ordinary body supplies a cold suffix. */
    static final class OwnedContinuation implements SavedGuestContinuation {
        private final SavedGuestContinuation saved;
        private final thc.Language.State context;
        OwnedContinuation(SavedGuestContinuation saved, thc.Language.State context) { this.saved = saved; this.context = context; }
        void requireOwner() {
            if (context != thc.Language.currentState()) throw RuntimeFault.fault("Application thunk belongs to another context");
        }
        @Override public Object getIdentity() { return this; }
        @Override public Object getSourceRoot() { return saved.getSourceRoot(); }
        @Override public Object getYielded() { return saved.getYielded(); }
        @Override public Object continueWith(Object input) { requireOwner(); return saved.continueWith(input); }
        @Override public void discard() { requireOwner(); saved.discard(); }
    }
}

/** Capture the reference at creation; neither the global head nor operand is entered. */
final class DelayExactApplication extends Expr {
    private final ExactThunkApplication.Plan plan;
    private final int slot;
    private final boolean reference;
    DelayExactApplication(com.oracle.truffle.api.RootCallTarget owner, GlobalBinding function,
                          thc.Language.State context, int slot, boolean reference) {
        plan = new ExactThunkApplication.Plan(owner, function, context);
        this.slot = slot; this.reference = reference;
    }
    @Override public Thunk execute(VirtualFrame frame) {
        Object argument = reference ? frame.getObject(slot) : FrameAccess.read(frame, slot);
        return Thunk.withArgument(plan.owner(), new ExactThunkApplication(plan, argument));
    }
}
