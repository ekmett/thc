// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.Assumption;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.bytecode.LocalAccessor;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.DirectCallNode;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.runtime.OptimizedCallTarget;

/** Prepublished, finite expression regions. No guest profile is seeded. */
public final class BytecodeCaseRegion extends Node {
    static final int WIDTH = 32;
    static final int MAX_ALTERNATIVES = 1024;
    static final int MAX_REGIONS = 8;

    static final class Plan {
        final Assumption inline = Truffle.getRuntime().createAssumption("inline bytecode case regions");
        int generation() { return inline.isValid() ? 0 : 1; }
        synchronized long recover(long failed) {
            if (failed == 0 && inline.isValid()) inline.invalidate();
            return generation();
        }
    }

    /** Recreated by bytecode source replay; contains no activation or guest values. */
    public record Source(@CompilationFinal(dimensions = 2) LocalAccessor[][] captures, BytecodeTupleSlots destination) {}

    @CompilationFinal(dimensions = 1) private final Object[] guards;
    private final int width;
    @Children private Side[] sides;
    private final boolean tail;

    BytecodeCaseRegion(Object[] guards, int width, RootCallTarget[] targets, CaptureLayout[] captures, boolean tail) {
        this(guards, width, targets, captures, tail, true);
    }

    BytecodeCaseRegion(RootCallTarget target, CaptureLayout captures, boolean tail) {
        this(new Object[0], 1, new RootCallTarget[]{target}, new CaptureLayout[]{captures}, tail, false);
    }

    private BytecodeCaseRegion(Object[] guards, int width, RootCallTarget[] targets, CaptureLayout[] captures,
            boolean tail, boolean valueArgument) {
        this.guards = guards;
        this.width = width;
        this.tail = tail;
        this.sides = new Side[targets.length];
        for (int i = 0; i < targets.length; i++) {
            OptimizedCallTarget target = (OptimizedCallTarget) targets[i];
            target.ensureInitialized();
            target.prepareForAOT();
            sides[i] = new Side(targets[i], captures[i], valueArgument);
        }
    }

    // The outlined caller must not PE-expand the original selector and side bodies.
    // These are the very same immutable constructor guards, in their original order.
    @TruffleBoundary(transferToInterpreterOnException = false)
    private int select(Object value) {
        for (int i = 0; i < guards.length; i++) {
            Object guard = guards[i];
            boolean matches = guard instanceof DataLayout layout ? layout.matches(value)
                    : value instanceof Long number && ((Long) guard).longValue() == number.longValue();
            if (matches) return i / width;
        }
        return sides.length - 1; // That region owns the original default (or FailCase).
    }

    Object execute(VirtualFrame frame, BytecodeRoot owner, Source source, Object value, MaskingState callerMask) {
        int selected = select(value);
        Object answer;
        try {
            answer = invoke(frame, owner, source, value, selected);
        } catch (TailCall transfer) {
            // Only the original logical root can consume its own backedge.
            if (tail && owner.isSelf(transfer.getTarget())) return transfer;
            throw transfer;
        }
        answer = complete(sides[selected].target, answer, callerMask,
                source.destination() == null ? null : source.destination().getShape());
        if (source.destination() != null) source.destination().consume(frame, this, answer);
        return source.destination() == null ? answer : Unit.INSTANCE;
    }

    @ExplodeLoop
    private Object invoke(VirtualFrame frame, BytecodeRoot owner, Source source, Object value, int selected) {
        for (int i = 0; i < sides.length; i++) {
            if (i == selected) {
                return sides[i].execute(frame, owner, source.captures()[i], value);
            }
        }
        throw new IllegalStateException("Invalid bytecode case region");
    }

    private Object complete(RootCallTarget target, Object result, MaskingState callerMask, TupleShape shape) {
        DelimitedControl.captureBytecode(result, shape);
        RootCallTarget callee = result instanceof TailYield tail ? tail.getTarget() :
                result instanceof AstTailYield tail ? tail.getTarget() : target;
        SavedGuestContinuation continuation = SavedGuestContinuations.savedGuestContinuation(
                result instanceof TailYield tail ? tail.getContinuation() :
                result instanceof AstTailYield tail ? tail.getContinuation() : result);
        if (continuation == null) {
            if (SynchronousMasking.current(this) != callerMask)
                throw new IllegalStateException("Case region did not restore its caller mask");
            return result;
        }
        return suspend(callee, continuation, callerMask, shape);
    }

    @TruffleBoundary(transferToInterpreterOnException = false)
    private Object suspend(RootCallTarget target, SavedGuestContinuation continuation, MaskingState callerMask, TupleShape shape) {
        try {
            Object source = continuation.getSourceRoot();
            if (!(source instanceof GuestRoot callee) || !callee.isSelf(target)
                    || shape != null && !callee.hasTupleResult(shape)
                    || !AsyncContinuations.isYieldMarker(continuation.getYielded()))
                throw new IllegalStateException("Unrelated case-region continuation");
            MaskingState parked = continuation.getYielded() instanceof CallSegmentSuspended suspended
                    ? suspended.getParkedActiveMask() : null;
            if (parked != null && SynchronousMasking.current(this) != callerMask)
                throw new IllegalStateException("Parked case region did not restore its caller mask");
            MaskingState active = parked != null ? parked : SynchronousMasking.current(this);
            throw new CapturedCallSuspension(new CallSegment(continuation.getIdentity(), active, callerMask, shape));
        } finally {
            SynchronousMasking.set(this, callerMask);
        }
    }

    private static final class Side extends Node {
        private final RootCallTarget target;
        private final CaptureLayout captures;
        private final boolean valueArgument;
        @Child private DirectCallNode call;
        Side(RootCallTarget target, CaptureLayout captures, boolean valueArgument) {
            this.target = target;
            this.captures = captures;
            this.valueArgument = valueArgument;
            this.call = DirectCallNode.create(target);
        }
        Object execute(VirtualFrame frame, BytecodeRoot owner, LocalAccessor[] sources, Object value) {
            long bloom = owner.bloom(frame);
            if (!valueArgument) return Calls.direct(call, captures == null ? new Object[]{bloom}
                    : new Object[]{bloom, captures.captureLocals(owner.getBytecodeNode(), frame, sources)});
            return Calls.direct(call, captures == null ? new Object[]{bloom, value}
                    : new Object[]{bloom, captures.captureLocals(owner.getBytecodeNode(), frame, sources), value});
        }
    }
}
