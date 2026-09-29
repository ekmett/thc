// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.TruffleSafepoint;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.DirectCallNode;
import com.oracle.truffle.api.nodes.Node;

/** Dense argument transport still crosses the Truffle boundary with an empty packet. */
public final class HandoffCaller extends Node {
    private static final Object[] EMPTY_ARGUMENTS = new Object[0];
    private final RootCallTarget target;
    private final HandoffEntry entry;
    private final Metrics metrics;
    @Child private TargetCache trampolineDispatch;
    public HandoffCaller(RootCallTarget target, HandoffEntry entry, Metrics metrics) {
        this.target = target; this.entry = entry; this.metrics = metrics;
        trampolineDispatch = new TargetCache(metrics);
    }
    public Object call(VirtualFrame frame, Object[] packet, DirectCallNode callNode, boolean tail) {
        HandoffState state = entry.state();
        int inherited = tail && getRootNode() instanceof FunctionRoot root ? root.handoffDestination(frame) : -1;
        if (tail && inherited < 0) throw new IllegalStateException("Check failed.");
        HandoffStorage input = state.getArguments().acquire(entry.getArguments());
        long generation = input.getGeneration();
        boolean transferred = false;
        try {
            if (tail) packet[0] = 0L;
            entry.getArguments().copyIn(input, packet);
            if (tail) {
                var sourceValue = getRootNode();
                if (sourceValue == null) {
                    CompilerDirectives.transferToInterpreter();
                    throw new NullPointerException("null cannot be cast to non-null type thc.runtime.GuestRoot");
                }
                GuestRoot source = (GuestRoot) sourceValue;
                long mask = source.bloom(frame);
                var destinationValue = target.getRootNode();
                if (destinationValue == null) {
                    CompilerDirectives.transferToInterpreter();
                    throw new NullPointerException("null cannot be cast to non-null type thc.runtime.GuestRoot");
                }
                GuestRoot destination = (GuestRoot) destinationValue;
                if ((mask & destination.mask) == destination.mask) {
                    transferred = true;
                    if (metrics.getEnabled()) { state.setTailTransfers(state.getTailTransfers() + 1); metrics.incrementTailBounces(); }
                    throw new HandoffTailCall(target, input);
                }
                entry.getArguments().setLong(input, 0, mask);
            }
            HandoffStorage oldPending = state.getPending();
            if (oldPending != null) throw new IllegalStateException("Check failed.");
            state.setPending(input);
            if (metrics.getEnabled()) state.setCalls(state.getCalls() + 1);
            try {
                Object result = Calls.direct(callNode, EMPTY_ARGUMENTS);
                // Preserve ordinary result identity; decode only our private tokens.
                if (result == HandoffEntry.INT_COMPLETE) return state.getReturnInt();
                if (result == HandoffEntry.COMPLETE) return state.getReturnLong();
                return result;
            } finally { state.setPending(oldPending); }
        } catch (HandoffTailCall transfer) {
            if (tail) throw transfer;
            return trampoline(state, transfer);
        } finally {
            if (!transferred && input.getLive() && input.getGeneration() == generation)
                state.getArguments().release(input, entry.getArguments());
        }
    }
    @TruffleBoundary private void releaseUnknown(HandoffState state, HandoffStorage input) { state.getArguments().release(input); }
    public Object trampoline(HandoffState state, HandoffTailCall initial) {
        HandoffTailCall transfer = initial;
        while (true) {
            if (metrics.getEnabled()) metrics.incrementTrampolineIterations();
            HandoffTailCall next = transfer;
            long generation = next.getArguments().getGeneration();
            try {
                // Poll inside the generation-checked loan cleanup.
                TruffleSafepoint.poll(this);
                HandoffStorage oldPending = state.getPending();
                if (oldPending != null) throw new IllegalStateException("Check failed.");
                state.setPending(next.getArguments());
                if (metrics.getEnabled()) state.setCalls(state.getCalls() + 1);
                try {
                    Object result = trampolineDispatch.call(next.getTarget(), EMPTY_ARGUMENTS);
                    if (result == HandoffEntry.INT_COMPLETE) return state.getReturnInt();
                    if (result == HandoffEntry.COMPLETE) return state.getReturnLong();
                    SavedGuestContinuation saved = SavedGuestContinuations.savedGuestContinuation(result);
                    if (saved != null) return new AstTailYield(saved, next.getTarget());
                    return result;
                } finally { state.setPending(oldPending); }
            } catch (HandoffTailCall tail) { transfer = tail; }
            finally {
                if (next.getArguments().getLive() && next.getArguments().getGeneration() == generation)
                    releaseUnknown(state, next.getArguments());
            }
        }
    }
    public Object trampoline$org_intelligence_thc(HandoffState state, HandoffTailCall initial) { return trampoline(state, initial); }
}
