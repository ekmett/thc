// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.MaterializedFrame;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.ExecutionSignature;
import com.oracle.truffle.api.nodes.IndirectCallNode;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.NodeUtil;
import com.oracle.truffle.api.ReplaceObserver;
import com.oracle.truffle.api.nodes.UnexpectedResultException;
import com.oracle.truffle.api.source.SourceSection;
import com.oracle.truffle.runtime.OptimizedCallTarget;

/** Initially inline, then a finite graph-budget boundary around an owned region.
 * The body remains adopted by its original function: capture, Bloom, joins and
 * instance lookup keep the same owner and the exact same activation frame. */
final class AstSameFrameArm extends Expr implements ReplaceObserver {
    private static final int OBJECT = 0, INT = 1, LONG = 2, FLOAT = 3, DOUBLE = 4,
            CLOSURE = 5, DATA = 6, ADDRESS = 7, TUPLE = 8;
    @Child private Expr body;
    @Child private IndirectCallNode call = IndirectCallNode.create();
    @CompilationFinal(dimensions = 1) private volatile RootCallTarget[] targets;
    private volatile long sideGeneration;
    private boolean copiedExtraction;
    private enum Role { ARM, DEMAND, JOIN, BRANCH }
    private final Role role;

    AstSameFrameArm(Expr body) { this(body, Role.ARM); }
    private AstSameFrameArm(Expr body, Role role) {
        this.body = body;
        this.role = role;
        setRepresentation(body.getRepresentation());
        setCoreSourceLocation(body.getCoreSourceLocation());
    }
    @Override public boolean isInstrumentable() { return role == Role.ARM && super.isInstrumentable(); }
    @Override public boolean needsCallState() { return role != Role.ARM ? body.needsCallState() : super.needsCallState(); }

    /** Only an unpublished recovery copy gains operation boundaries. Functions
     * already containing same-frame regions have retained their frame identity. */
    static boolean extractFresh(Node region) {
        if (NodeUtil.findFirstNodeInstance(region.getRootNode(), AstSameFrameArm.class) != null) {
            Expr operation = firstOperation(region, false);
            if (operation != null) {
                if (operation instanceof LocalJoinRegion) {
                    // Split the fresh join's first demand before constructing its
                    // side targets; both boundaries retain the original frame.
                    Expr first = firstOperation(operation, false);
                    if (first instanceof Evaluate && first.getParent() instanceof LocalBinding binding &&
                            binding.getParent() instanceof Case selection) {
                        prepare(first.replace(new AstSameFrameArm(first, Role.DEMAND)));
                        // Keep each complete alternative, including an installed probe,
                        // in its original owner; local jumps cross the side unchanged.
                        for (Alternative alternative : selection.alternatives) {
                            Expr body = alternative.getBody();
                            if (!(body instanceof AstSameFrameArm) && !body.getRepresentation().isTypedTransport() &&
                                    NodeUtil.findFirstNodeInstance(body, LocalJoinCall.class) != null)
                                prepare(body.replace(new AstSameFrameArm(body, Role.BRANCH)));
                        }
                    }
                }
                return prepare(operation.replace(new AstSameFrameArm(operation, operation instanceof LocalJoinRegion ? Role.JOIN : Role.DEMAND)));
            }
        }
        return extract(region);
    }

    private static Expr firstOperation(Node node, boolean nested) {
        // Existing regions own their descendants. An extracted side starts its
        // search at its body, so it must not separate that same operation again.
        if (node instanceof AstSameFrameArm) return null;
        if (nested && node instanceof LocalJoinRegion join && !join.getRepresentation().isTypedTransport() &&
                !(join.getParent() instanceof com.oracle.truffle.api.instrumentation.InstrumentableNode.WrapperNode)) return join;
        Node first = null;
        if (node instanceof Case selection) {
            LocalBinding binding = selection.scrutineeBinding();
            Evaluate demand = binding.demandCandidate();
            if (demand != null) return demand;
            first = binding;
            Expr operation = firstOperation(first, true);
            if (operation != null) return operation;
        }
        for (Node child : node.getChildren()) {
            if (child == first) continue;
            Expr operation = firstOperation(child, true);
            if (operation != null) return operation;
        }
        return null;
    }

    /** A real failure extracts one remaining region; already separated bodies
     * do not contribute to this compilation's candidate search. */
    static boolean extract(Node region) {
        AstSameFrameArm[] largest = {null};
        int[] size = {0};
        select(region, largest, size);
        AstSameFrameArm selected = largest[0];
        if (selected == null) return false;
        return prepare(selected);
    }

    private static boolean prepare(AstSameFrameArm selected) {
        return selected.atomic(() -> {
            if (selected.targets != null) return false;
            ContextRoot source = (ContextRoot) selected.getRootNode();
            RootCallTarget[] prepared = new RootCallTarget[TUPLE + 1];
            // Each typed entry preserves the original Expr method, including
            // UnexpectedResultException. No observed guest values seed profiles.
            for (int mode = 0; mode < prepared.length; mode++) {
                OptimizedCallTarget target = (OptimizedCallTarget) new ArmRoot(source, selected, mode).getCallTarget();
                target.prepareForAOT();
                prepared[mode] = target;
            }
            selected.targets = prepared;
            selected.reportReplace(selected.body, selected.body, "separate same-frame case after graph budget failure");
            return true;
        });
    }

    private static int select(Node node, AstSameFrameArm[] largest, int[] size) {
        if (node instanceof AstSameFrameArm arm && arm.targets != null) return 1;
        int count = 1;
        for (Node child : node.getChildren()) count += select(child, largest, size);
        if (node instanceof AstSameFrameArm arm && count > size[0]) {
            largest[0] = arm; size[0] = count;
        }
        return count;
    }

    @Override public Node copy() {
        AstSameFrameArm clone = (AstSameFrameArm) super.copy();
        clone.copiedExtraction = copiedExtraction || targets != null;
        clone.targets = null;
        clone.sideGeneration = 0;
        return clone;
    }

    /** Recovery clones keep prior boundaries, with fresh sides owned by the new
     * body. Ordinary Truffle cloning retains its existing unextracted behavior. */
    static void restoreCopiedExtractions(Node root) {
        for (AstSameFrameArm arm : NodeUtil.findAllNodeInstances(root, AstSameFrameArm.class)) {
            if (arm.copiedExtraction) {
                prepare(arm);
                arm.copiedExtraction = false;
            }
        }
    }

    @Override public boolean nodeReplaced(Node oldNode, Node newNode, CharSequence reason) {
        RootCallTarget[] current = targets;
        if (current != null) for (RootCallTarget target : current)
            ((OptimizedCallTarget) target).nodeReplaced(oldNode, newNode, reason);
        return false; // Also notify the original function and any surrounding OSR loop.
    }

    private Object invoke(VirtualFrame frame, int mode, int[] slots, int offset) {
        Object[] arguments = mode == TUPLE ? new Object[]{frame.materialize(), slots, offset}
                : new Object[]{frame.materialize()};
        // A branch's first local jump must not initialize an indirect-call exception profile.
        return role == Role.BRANCH ? ((OptimizedCallTarget) targets[mode]).call(this, arguments)
                : Calls.indirect(call, targets[mode], arguments);
    }
    @Override public Object execute(VirtualFrame frame) {
        return targets == null ? body.execute(frame) : invoke(frame, OBJECT, null, 0);
    }
    @Override public int executeInt(VirtualFrame frame) throws UnexpectedResultException {
        return targets == null ? body.executeInt(frame) : (Integer) invoke(frame, INT, null, 0);
    }
    @Override public long executeLong(VirtualFrame frame) throws UnexpectedResultException {
        return targets == null ? body.executeLong(frame) : (Long) invoke(frame, LONG, null, 0);
    }
    @Override public float executeFloat(VirtualFrame frame) throws UnexpectedResultException {
        return targets == null ? body.executeFloat(frame) : (Float) invoke(frame, FLOAT, null, 0);
    }
    @Override public double executeDouble(VirtualFrame frame) throws UnexpectedResultException {
        return targets == null ? body.executeDouble(frame) : (Double) invoke(frame, DOUBLE, null, 0);
    }
    @Override public Closure executeClosure(VirtualFrame frame) throws UnexpectedResultException {
        return targets == null ? body.executeClosure(frame) : (Closure) invoke(frame, CLOSURE, null, 0);
    }
    @Override public DataValue executeDataValue(VirtualFrame frame) throws UnexpectedResultException {
        return targets == null ? body.executeDataValue(frame) : (DataValue) invoke(frame, DATA, null, 0);
    }
    @Override public ManagedAddress executeAddress(VirtualFrame frame) throws UnexpectedResultException {
        return targets == null ? body.executeAddress(frame) : (ManagedAddress) invoke(frame, ADDRESS, null, 0);
    }
    @Override public Object executeTuple(VirtualFrame frame, int[] slots, int offset) {
        return targets == null ? body.executeTuple(frame, slots, offset) : invoke(frame, TUPLE, slots, offset);
    }
    @Override public void prepareTuple(int[] slots, int offset) { body.prepareTuple(slots, offset); }

    /** Non-adopted reference, as with Truffle partial blocks. No frame or guest
     * arguments are retained and no control-flow exception is intercepted. */
    static final class ArmRoot extends ContextRoot {
        private final AstSameFrameArm arm;
        private final int mode;
        ArmRoot(ContextRoot source, AstSameFrameArm arm, int mode) {
            super(source, FrameDescriptor.newBuilder().build());
            this.arm = arm;
            this.mode = mode;
        }
        ContextRoot sourceRoot() { return (ContextRoot) arm.getRootNode(); }
        boolean ownsCompilationFailure() { return arm.role != Role.ARM; }

        boolean extractInCopy(FunctionRoot replacement) {
            var originalArms = NodeUtil.findAllNodeInstances(sourceRoot(), AstSameFrameArm.class);
            var copiedArms = NodeUtil.findAllNodeInstances(replacement, AstSameFrameArm.class);
            int index = originalArms.indexOf(arm);
            return index >= 0 && originalArms.size() == copiedArms.size() && extractFresh(copiedArms.get(index).body);
        }
        @Override public Object execute(VirtualFrame callFrame) {
            Object[] args = callFrame.getArguments();
            VirtualFrame frame = (MaterializedFrame) args[0];
            try {
                return switch (mode) {
                    case INT -> arm.body.executeInt(frame);
                    case LONG -> arm.body.executeLong(frame);
                    case FLOAT -> arm.body.executeFloat(frame);
                    case DOUBLE -> arm.body.executeDouble(frame);
                    case CLOSURE -> arm.body.executeClosure(frame);
                    case DATA -> arm.body.executeDataValue(frame);
                    case ADDRESS -> arm.body.executeAddress(frame);
                    case TUPLE -> arm.body.executeTuple(frame, (int[]) args[1], (Integer) args[2]);
                    default -> arm.body.execute(frame);
                };
            } catch (UnexpectedResultException failure) {
                return AstSameFrameArm.<RuntimeException>rethrow(failure);
            }
        }
        @Override protected ExecutionSignature prepareForAOT() { return ExecutionSignature.GENERIC; }
        @Override protected boolean prepareForCompilation(boolean rootCompilation, int tier, boolean lastTier) {
            return rootCompilation; // Only this required post-budget boundary vetoes inlining.
        }
        // Optional graph-budget hooks when running with the THC runtime overlay.
        public long getGraphBudgetGeneration() { return arm.sideGeneration; }
        public long prepareGraphBudgetRetry(long failedGeneration) {
            if (compilationFailureObserved) return failedGeneration;
            synchronized (arm) {
                if (failedGeneration == arm.sideGeneration && extract(arm.body)) arm.sideGeneration++;
                return arm.sideGeneration;
            }
        }
        @Override public SourceSection getSourceSection() { return arm.getSourceSection(); }
        @Override public String getName() {
            String kind = switch (arm.role) {
                case ARM -> "case arm";
                case DEMAND -> "case demand";
                case JOIN -> "join region";
                case BRANCH -> "join branch";
            };
            return arm.getRootNode().getName() + " <" + kind + ">";
        }
        @Override public boolean isCloningAllowed() { return false; }
    }

    @SuppressWarnings("unchecked")
    private static <E extends Throwable> Object rethrow(Throwable failure) throws E { throw (E) failure; }
}
