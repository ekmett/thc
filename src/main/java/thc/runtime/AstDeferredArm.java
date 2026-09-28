// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.NodeUtil;
import com.oracle.truffle.api.nodes.RootNode;
import com.oracle.truffle.api.nodes.UnexpectedResultException;
import com.oracle.truffle.runtime.OptimizedCallTarget;
import java.util.List;

/** One compiler-selected default arm. No guest values or source Core are retained. */
public final class AstDeferredArm extends Expr {
    @Child private Expr body;
    private final CaptureLayout captures;
    @CompilationFinal(dimensions = 1) private final int[] sourceSlots;
    private final boolean tailPosition;
    @CompilationFinal private PreparedBody prepared;
    @CompilationFinal private RootCallTarget target;

    public AstDeferredArm(Expr body, CaptureLayout captures, int[] sourceSlots, boolean tailPosition) {
        this.body = body;
        this.captures = captures;
        this.sourceSlots = sourceSlots.clone();
        this.tailPosition = tailPosition;
        setRepresentation(body.getRepresentation());
        setCoreSourceLocation(body.getCoreSourceLocation());
    }

    /** Called while lowering in the owning context, before publishing this caller. */
    public void prepare(RootCallTarget target, PreparedBody prepared) {
        if (this.target != null) throw new IllegalStateException("Deferred arm already prepared");
        this.target = target;
        this.prepared = prepared;
        OptimizedCallTarget optimized = (OptimizedCallTarget) target;
        optimized.ensureInitialized();
        optimized.prepareForAOT();
    }

    /** Called only by the root's real GraphTooBig recovery hook, under its lock. */
    public static boolean extract(RootNode root) {
        List<AstDeferredArm> candidates = NodeUtil.findAllNodeInstances(root, AstDeferredArm.class);
        if (candidates.size() != 1) return false;
        AstDeferredArm arm = candidates.getFirst();
        if (arm.target == null) throw new IllegalStateException("Unprepared deferred arm");
        arm.prepared.install(arm.body);
        AstCaseArm call = new AstCaseArm(arm.target, arm.captures, arm.sourceSlots, arm.tailPosition);
        call.setCoreSourceLocation(arm.getCoreSourceLocation());
        arm.replace(call, "extract default arm after graph budget failure");
        return true;
    }

    /** Entry recovery owns its prepared side as well as its copied inline arm.
     * The original prepared target may still belong to an active or saved body. */
    static boolean extractFresh(FunctionRoot root) {
        List<AstDeferredArm> candidates = NodeUtil.findAllNodeInstances(root, AstDeferredArm.class);
        if (candidates.size() != 1) return false;
        AstDeferredArm arm = candidates.getFirst();
        if (arm.target == null || !(arm.target.getRootNode() instanceof FunctionRoot source))
            throw new IllegalStateException("Unprepared deferred arm");
        FunctionRoot side = source.copyForRecovery();
        List<PreparedBody> bodies = NodeUtil.findAllNodeInstances(side, PreparedBody.class);
        if (bodies.size() != 1) throw new IllegalStateException("Invalid deferred side body");
        PreparedBody prepared = new PreparedBody(arm.getRepresentation(), arm.getCoreSourceLocation());
        bodies.getFirst().replace(prepared);
        arm.target = null;
        arm.prepare(side.getCallTarget(), prepared);
        return extract(root);
    }

    @Override public Object execute(VirtualFrame frame) { return body.execute(frame); }
    @Override public int executeInt(VirtualFrame frame) throws UnexpectedResultException { return body.executeInt(frame); }
    @Override public long executeLong(VirtualFrame frame) throws UnexpectedResultException { return body.executeLong(frame); }
    @Override public float executeFloat(VirtualFrame frame) throws UnexpectedResultException { return body.executeFloat(frame); }
    @Override public double executeDouble(VirtualFrame frame) throws UnexpectedResultException { return body.executeDouble(frame); }

    /** The prepared target is private and non-cloning. Caller clones share its
     * immutable layout, not invocation state; the first extraction installs code once. */
    public static final class PreparedBody extends Expr {
        @Child private Expr body;
        public PreparedBody(CoreRepresentation proof, CoreSourceLocation source) {
            setRepresentation(proof);
            setCoreSourceLocation(source);
        }
        synchronized void install(Expr original) {
            if (body == null) body = insert(NodeUtil.cloneNode(original));
        }
        private Expr ready() {
            if (body == null) throw new IllegalStateException("Deferred target entered before extraction");
            return body;
        }
        @Override public Object execute(VirtualFrame frame) { return ready().execute(frame); }
        @Override public int executeInt(VirtualFrame frame) throws UnexpectedResultException { return ready().executeInt(frame); }
        @Override public long executeLong(VirtualFrame frame) throws UnexpectedResultException { return ready().executeLong(frame); }
        @Override public float executeFloat(VirtualFrame frame) throws UnexpectedResultException { return ready().executeFloat(frame); }
        @Override public double executeDouble(VirtualFrame frame) throws UnexpectedResultException { return ready().executeDouble(frame); }
    }
}
