// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.oracle.truffle.api.nodes.UnexpectedResultException;
import com.oracle.truffle.api.profiles.CountingConditionProfile;

public final class SumCase extends Expr {
    @Child private Expr scrutinee;
    @CompilationFinal(dimensions = 1) private final int[] slots;
    @Children private Expr[] alternatives;
    @CompilationFinal(dimensions = 1) private final int[] tagToArm;
    @CompilationFinal(dimensions = 1) private final CountingConditionProfile[] armProfiles;
    public SumCase(Expr scrutinee, int[] slots, Expr[] alternatives, int[] tagToArm, CoreRepresentation proof) {
        this(scrutinee, slots, alternatives, tagToArm, proof, true);
    }
    public SumCase(Expr scrutinee, int[] slots, Expr[] alternatives, int[] tagToArm, CoreRepresentation proof, boolean profileChoice) {
        this.scrutinee = scrutinee; this.slots = slots; this.alternatives = alternatives; this.tagToArm = tagToArm;
        setRepresentation(proof);
        scrutinee.prepareTuple(slots, 0);
        armProfiles = new CountingConditionProfile[alternatives.length];
        for (int i = 0; i < armProfiles.length; i++) armProfiles[i] = profileChoice ? CountingConditionProfile.create() : CountingConditionProfile.getUncached();
    }
    @Override public void prepareTuple(int[] slots, int offset) {
        for (Expr alternative : alternatives) alternative.prepareTuple(slots, offset);
    }
    private enum Route { GENERIC, INT, LONG, FLOAT, DOUBLE, CLOSURE, DATA, ADDRESS, TUPLE }
    private static final class ResumeBranch implements AstResumeStep {
        private final SumCase node;
        private final Route route;
        private final int[] destination;
        private final int offset;
        ResumeBranch(SumCase node, Route route, int[] destination, int offset) { this.node = node; this.route = route; this.destination = destination; this.offset = offset; }
        @Override public Object resume(VirtualFrame frame, Object input) {
            if (input != null) throw fault("Invalid AST sum-case resume value");
            try { return node.resumeBranch(frame, route, destination, offset); }
            catch (UnexpectedResultException failure) { throw rethrow(failure); }
        }
    }
    private int selected(VirtualFrame frame) { return tagToArm[SumShape.checkedTag(frame.getLong(slots[0]), tagToArm.length) - 1]; }
    private int prepare(VirtualFrame frame, Route route) { return prepare(frame, route, null, 0); }
    private int prepare(VirtualFrame frame, Route route, int[] destination, int offset) {
        try { scrutinee.executeTuple(frame, slots, 0); }
        catch (AstCapture cut) { throw cut.append(new ResumeBranch(this, route, destination, offset)); }
        return selected(frame);
    }
    @ExplodeLoop private Object resumeBranch(VirtualFrame frame, Route route, int[] destination, int offset) throws UnexpectedResultException {
        int selected = selected(frame);
        // Inject probabilities on the same arm edges as ordinary entry.
        for (int i = 0; i < alternatives.length; i++) if (armProfiles[i].profile(selected == i)) {
            Expr branch = alternatives[i];
            return switch (route) {
                case GENERIC -> branch.execute(frame); case INT -> branch.executeInt(frame);
                case LONG -> branch.executeLong(frame); case FLOAT -> branch.executeFloat(frame);
                case DOUBLE -> branch.executeDouble(frame); case CLOSURE -> branch.executeClosure(frame);
                case DATA -> branch.executeDataValue(frame); case ADDRESS -> branch.executeAddress(frame);
                case TUPLE -> branch.executeTuple(frame, java.util.Objects.requireNonNull(destination), offset);
            };
        }
        throw fault("Non-exhaustive unboxed sum case");
    }
    @ExplodeLoop @Override public Object execute(VirtualFrame frame) {
        int selected = prepare(frame, Route.GENERIC);
        for (int i = 0; i < alternatives.length; i++) if (armProfiles[i].profile(selected == i)) return alternatives[i].execute(frame);
        throw fault("Non-exhaustive unboxed sum case");
    }
    @ExplodeLoop @Override public int executeInt(VirtualFrame frame) throws UnexpectedResultException {
        int selected = prepare(frame, Route.INT);
        for (int i = 0; i < alternatives.length; i++) if (armProfiles[i].profile(selected == i)) return alternatives[i].executeInt(frame);
        throw fault("Non-exhaustive unboxed sum case");
    }
    @ExplodeLoop @Override public long executeLong(VirtualFrame frame) throws UnexpectedResultException {
        int selected = prepare(frame, Route.LONG);
        for (int i = 0; i < alternatives.length; i++) if (armProfiles[i].profile(selected == i)) return alternatives[i].executeLong(frame);
        throw fault("Non-exhaustive unboxed sum case");
    }
    @ExplodeLoop @Override public float executeFloat(VirtualFrame frame) throws UnexpectedResultException {
        int selected = prepare(frame, Route.FLOAT);
        for (int i = 0; i < alternatives.length; i++) if (armProfiles[i].profile(selected == i)) return alternatives[i].executeFloat(frame);
        throw fault("Non-exhaustive unboxed sum case");
    }
    @ExplodeLoop @Override public double executeDouble(VirtualFrame frame) throws UnexpectedResultException {
        int selected = prepare(frame, Route.DOUBLE);
        for (int i = 0; i < alternatives.length; i++) if (armProfiles[i].profile(selected == i)) return alternatives[i].executeDouble(frame);
        throw fault("Non-exhaustive unboxed sum case");
    }
    @ExplodeLoop @Override public Closure executeClosure(VirtualFrame frame) throws UnexpectedResultException {
        int selected = prepare(frame, Route.CLOSURE);
        for (int i = 0; i < alternatives.length; i++) if (armProfiles[i].profile(selected == i)) return alternatives[i].executeClosure(frame);
        throw fault("Non-exhaustive unboxed sum case");
    }
    @ExplodeLoop @Override public DataValue executeDataValue(VirtualFrame frame) throws UnexpectedResultException {
        int selected = prepare(frame, Route.DATA);
        for (int i = 0; i < alternatives.length; i++) if (armProfiles[i].profile(selected == i)) return alternatives[i].executeDataValue(frame);
        throw fault("Non-exhaustive unboxed sum case");
    }
    @ExplodeLoop @Override public ManagedAddress executeAddress(VirtualFrame frame) throws UnexpectedResultException {
        int selected = prepare(frame, Route.ADDRESS);
        for (int i = 0; i < alternatives.length; i++) if (armProfiles[i].profile(selected == i)) return alternatives[i].executeAddress(frame);
        throw fault("Non-exhaustive unboxed sum case");
    }
    @ExplodeLoop @Override public Object executeTuple(VirtualFrame frame, int[] slots, int offset) {
        int selected = prepare(frame, Route.TUPLE, slots, offset);
        for (int i = 0; i < alternatives.length; i++) if (armProfiles[i].profile(selected == i)) return alternatives[i].executeTuple(frame, slots, offset);
        throw fault("Non-exhaustive unboxed sum case");
    }
    private static RuntimeFault fault(String message) { CompilerDirectives.transferToInterpreterAndInvalidate(); return new RuntimeFault(message); }
    @SuppressWarnings("unchecked") private static <E extends Throwable> RuntimeException rethrow(Throwable failure) throws E { throw (E) failure; }
}
