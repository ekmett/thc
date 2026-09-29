// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.TruffleSafepoint;
import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.VirtualFrame;
import java.util.Arrays;
import java.util.List;
import thc.Language;
import static thc.runtime.RuntimeFault.fault;

/** Normal Dispatch supplies PAPs/overapplication; Thunk/Force supplies lazy updates. */
public final class GhcBCORoot extends GuestRoot {
    private final Language.State owner;
    private final GhcInstruction[] code;
    private final long[] literals;
    private final Object[] pointers;
    private final int arity;
    private final boolean[] nonPointers;
    @Child private Force force;
    @Children private Dispatch[] calls;
    private static final class Apply implements GhcBCOStack.Marker { final int count; Apply(int count) { this.count = count; } }
    private enum ReturnKind implements GhcBCOStack.Marker { P, N, F, D, L, V, T }
    private record CaseFrame(ReturnKind kind, Closure continuation, long info, GhcBCORoot tuple) implements GhcBCOStack.Marker {}
    public GhcBCORoot(Language language, Language.State owner, Metrics metrics, GhcInstruction[] code,
                      long[] literals, Object[] pointers, int arity, boolean[] nonPointers) {
        super(language, FrameDescriptor.newBuilder().build());
        this.owner = owner; this.code = code; this.literals = literals; this.pointers = pointers; this.arity = arity; this.nonPointers = nonPointers;
        force = new Force(metrics, true);
        calls = new Dispatch[6];
        for (int i = 0; i < calls.length; i++) calls[i] = DispatchNodeGen.create(i + 1, false, metrics);
    }
    public Language.State getOwner() { return owner; }
    @Override public boolean getAsynchronousExceptions() { return true; }
    public int getArity() { return arity; }
    int getStackWords() { return nonPointers.length; }
    @Override public String getName() { return "<GHC BCO>"; }
    @Override public long bloom(VirtualFrame frame) { return (Long) frame.getArguments()[0]; }
    private static long word(Object value) {
        return switch (value) {
            case Long bits -> bits;
            case Float bits -> (long) Float.floatToRawIntBits(bits) & 0xffffffffL;
            case Double bits -> Double.doubleToRawLongBits(bits);
            case null, default -> throw fault("BCO word operation received a pointer or stack marker");
        };
    }
    private static void pushWord(GhcBCOStack stack, Object value) {
        // A Float occupies the first four bytes at Sp, followed by four padding bytes.
        if (value instanceof Float) { stack.push(0, 4); stack.push(word(value), 4); }
        else stack.push(word(value), 8);
    }
    private static GhcBCORoot bco(Object value) {
        if (!(value instanceof Closure closure) || closure.suppliedCount != 0 || !(closure.target.getRootNode() instanceof GhcBCORoot root))
            throw fault("BCO continuation requires an unapplied BCO");
        root.requireOwner();
        if (root.arity != 0) throw fault("BCO continuation must have zero arity");
        return root;
    }
    private void pushCase(GhcBCOStack stack, ReturnKind kind, Object value, long info, Object tupleValue) {
        GhcBCORoot continuation = bco(value);
        GhcBCORoot tuple = kind == ReturnKind.T ? bco(tupleValue) : null;
        if (tuple != null) {
            checkTupleInfo(info, tuple);
            stack.pushReference(tupleValue); stack.push(info, 8);
        }
        stack.validate(continuation.nonPointers, 0);
        stack.pushReference(value);
        stack.pushReference(new CaseFrame(kind, (Closure) value, info, tuple));
    }
    private static void checkTupleInfo(long info, GhcBCORoot tuple) {
        int spill = (int) (info >>> 24) & 255;
        if ((info >>> 32) != 0 || spill > 62 || tuple.nonPointers.length == 0 || !tuple.nonPointers[0] ||
            Long.bitCount(info & 0xffffffL) + spill != tuple.nonPointers.length - 1)
            throw fault("BCO tuple descriptor disagrees with its stack bitmap");
    }
    private Object returnTuple(VirtualFrame frame, GhcBCOStack stack) {
        GhcBCORoot tuple = bco(stack.pointer(0));
        long info = stack.read(8, 8);
        checkTupleInfo(info, tuple);
        stack.validate(tuple.nonPointers, 1);
        int width = tuple.nonPointers.length - 1;
        if (stack.words() <= width + 2L || !(stack.peek(width + 2L) instanceof CaseFrame next) || next.kind != ReturnKind.T)
            throw fault("External Core tuple returns from a GHC BCO require a checked ABI adapter");
        GhcBCORoot continuation = bco(next.continuation);
        if (next.info != info || !Arrays.equals(next.tuple.nonPointers, tuple.nonPointers))
            throw fault("BCO tuple return does not match its continuation");
        stack.pushReference(ReturnKind.T);
        return AstControl.complete(this, continuation.executeStack(frame, stack), continuation.getCallTarget());
    }
    private Object finish(VirtualFrame frame, GhcBCOStack stack, Object value, boolean enter, ReturnKind kind) {
        Object answer = value;
        if (enter) {
            try { answer = AstControl.force(frame, this, force, answer); }
            catch (AstCapture cut) { ReturnKind pending = kind; throw cut.append((saved, input) -> finish(saved, stack, input, false, pending)); }
        }
        while (!stack.isEmpty()) {
            if (stack.peek(0) instanceof CaseFrame next) {
                GhcBCORoot continuation = bco(next.continuation);
                if (next.kind == ReturnKind.T || kind != null && next.kind != kind)
                    throw fault("BCO return convention does not match its continuation");
                switch (next.kind) {
                    case P -> stack.pushReference(answer);
                    case N, L -> { if (!(answer instanceof Long)) throw fault("BCO word return requires raw word bits"); stack.push((Long) answer, 8); }
                    case F -> { if (!(answer instanceof Float)) throw fault("BCO float return requires Float"); pushWord(stack, answer); }
                    case D -> { if (!(answer instanceof Double)) throw fault("BCO double return requires Double"); stack.push(word(answer), 8); }
                    case V -> TupleResults.requireVoidCarrier(answer);
                    default -> throw fault("BCO tuple return requires its native descriptor");
                }
                stack.pushReference(next.kind);
                return AstControl.complete(this, continuation.executeStack(frame, stack), continuation.getCallTarget());
            }
            if (!(stack.pop() instanceof Apply apply)) throw fault("BCO returned over unconsumed arguments");
            var arguments = new Object[apply.count];
            for (int i = 0; i < arguments.length; i++) arguments[i] = stack.popPointer();
            Object ready;
            try { ready = AstControl.force(frame, this, force, answer); }
            catch (AstCapture cut) {
                throw cut.append((saved, input) -> finish(saved, stack, call(saved, stack, input, arguments), true, null));
            }
            answer = call(frame, stack, ready, arguments);
            kind = null;
            try { answer = AstControl.force(frame, this, force, answer); }
            catch (AstCapture cut) { throw cut.append((saved, input) -> finish(saved, stack, input, false, null)); }
        }
        return answer;
    }
    private Object call(VirtualFrame frame, GhcBCOStack stack, Object value, Object[] arguments) {
        if (!(value instanceof Closure function)) throw fault("BCO application requires a function");
        // A saturated or overapplied BCO keeps the native return continuation on this stack.
        if (function.arity <= arguments.length && function.target.getRootNode() instanceof GhcBCORoot root) {
            root.requireOwner();
            Object[] all = new Object[function.supplied.length + function.arity];
            System.arraycopy(function.supplied, 0, all, 0, function.supplied.length);
            System.arraycopy(arguments, 0, all, function.supplied.length, function.arity);
            if (function.arity < arguments.length) {
                for (int i = arguments.length - 1; i >= function.arity; i--) stack.pushReference(arguments[i]);
                stack.pushReference(new Apply(arguments.length - function.arity));
            }
            root.pushArguments(stack, all, 0);
            try { return AstControl.complete(this, root.executeStack(frame, stack), root.getCallTarget()); }
            catch (AstCapture cut) { throw cut.append((saved, input) -> finish(saved, stack, input, true, null)); }
        }
        try { return calls[arguments.length - 1].execute(frame, function, arguments); }
        catch (AstCapture cut) { throw cut.append((saved, input) -> finish(saved, stack, input, true, null)); }
    }
    void requireOwner() {
        if (Language.currentState(null) != owner) throw fault("BCO belongs to another context");
    }
    private AstCapture own(AstCapture cut) {
        return cut.enclose(steps -> (frame, input) -> resume(frame, steps, input));
    }
    private Object resume(VirtualFrame frame, List<AstResumeStep> steps, Object input) {
        requireOwner();
        try { return AstContinuations.resumeAstSteps(frame, steps, input); }
        catch (AstCapture cut) { throw own(cut); }
        catch (DelimitedCut ignored) { throw fault("Delimited capture through a GHC BCO is not supported"); }
    }
    @Override public Object execute(VirtualFrame frame) {
        // Check ownership before node lookup can consult another root's sharing layer.
        requireOwner();
        if (frame.getArguments().length != getArity() + 1) throw fault("BCO argument packet mismatch");
        var stack = new GhcBCOStack();
        pushArguments(stack, frame.getArguments(), 1);
        return executeStack(frame, stack);
    }
    private void pushArguments(GhcBCOStack stack, Object[] arguments, int skip) {
        if (arity != nonPointers.length || arguments.length - skip != arity)
            throw fault("BCO function entry requires a known argument word layout");
        for (int i = arity - 1; i >= 0; i--) {
            if (nonPointers[i]) pushWord(stack, arguments[i + skip]);
            else stack.pushReference(arguments[i + skip]);
        }
    }
    private Object executeStack(VirtualFrame frame, GhcBCOStack stack) {
        AstStackScope scope = AstStacks.astStackScope(this);
        boolean driver = !scope.getDriving();
        if (driver) scope.setDriving(true);
        try {
            scope.setDepth(scope.getDepth() + 1);
            Object result;
            try {
                try {
                    if (scope.getDepth() >= AstStackScope.MAX_DEPTH) {
                        scope.setSpills(scope.getSpills() + 1);
                        throw new AstCapture(AstStackSpill.INSTANCE, SynchronousMasking.current(this))
                            .append((saved, input) -> resumeLoop(saved, stack, 0, input));
                    }
                    result = run(frame, stack, 0);
                } catch (AstCapture cut) { result = own(cut).freeze(this, frame.materialize()); }
                catch (DelimitedCut ignored) { throw fault("Delimited capture through a GHC BCO is not supported"); }
            } finally { scope.setDepth(scope.getDepth() - 1); }
            SavedGuestContinuation saved = SavedGuestContinuations.savedGuestContinuation(result);
            return driver && saved != null && saved.stackSpill() && saved.asyncRequest() == null ? force.drainStack(saved) : result;
        } finally { if (driver) scope.setDriving(false); }
    }
    private Object resumeLoop(VirtualFrame frame, GhcBCOStack stack, int pc, Object input) {
        if (input != Unit.INSTANCE) throw fault("BCO instruction continuation requires Unit");
        return run(frame, stack, pc);
    }
    private Object run(VirtualFrame frame, GhcBCOStack stack, int pc) {
        while (true) {
            TruffleSafepoint.poll(this);
            AsyncRequest request = GuestThreads.pollCurrent(this, false);
            if (request != null) {
                int next = pc;
                throw new AstCapture(request, SynchronousMasking.current(this)).append((saved, input) -> resumeLoop(saved, stack, next, input));
            }
            var instruction = pc >= 0 && pc < code.length ? code[pc] : null;
            if (instruction == null) throw fault("BCO execution left its instruction stream");
            pc = instruction.next;
            var args = instruction.args;
            int op = instruction.opcode;
            switch (op) {
                case 1 -> { if (args[0] < 0 || args[0] > Integer.MAX_VALUE) throw fault("BCO stack request outside managed bounds"); }
                case 2, 3, 4 -> stack.copy(args);
                case 5, 6, 7, 8, 9, 10 -> {
                    int width = 1 << ((op - 5) % 3);
                    stack.push(stack.read(args[0], width), op >= 8 ? 8 : width);
                }
                case 11 -> stack.pushReference(pointers[(int) args[0]]);
                case 13, 14, 15, 16, 17, 18 -> pushCase(stack, ReturnKind.values()[op - 13], pointers[(int) args[0]], 0, null);
                case 19, 20, 21 -> stack.push(0, 1 << (op - 19));
                case 22, 23, 24 -> stack.push(literals[(int) args[0]], 1 << (op - 22));
                case 25 -> { for (int i = (int) args[1] - 1; i >= 0; i--) stack.push(literals[(int) args[0] + i], 8); }
                case 31, 32, 33, 34, 35, 36 -> stack.pushReference(new Apply(op - 30));
                case 38 -> stack.slide(args[0], args[1]);
                case 70 -> pushCase(stack, ReturnKind.T, pointers[(int) args[0]], literals[(int) args[1]], pointers[(int) args[2]]);
                case 46, 47, 67, 68 -> {
                    long a = stack.read(0, 8), b = literals[(int) args[0]];
                    boolean failure = switch (op) { case 46 -> a >= b; case 67 -> Long.compareUnsigned(a, b) >= 0; default -> a != b; };
                    if (failure) pc = (int) args[1];
                }
                case 54 -> throw fault("GHC BCO CASEFAIL");
                case 55 -> pc = (int) args[0];
                case 57 -> stack.setWord(args[0], stack.read(args[0] * 8, 8) + args[1]);
                case 58 -> { return finish(frame, stack, stack.popPointer(), true, ReturnKind.P); }
                case 60 -> { return finish(frame, stack, stack.popPointer(), false, ReturnKind.P); }
                case 61, 64 -> { return finish(frame, stack, stack.popWord(), false, op == 61 ? ReturnKind.N : ReturnKind.L); }
                case 62 -> { int bits = (int) stack.read(0, 4); stack.popWord(); return finish(frame, stack, Float.intBitsToFloat(bits), false, ReturnKind.F); }
                case 63 -> { return finish(frame, stack, Double.longBitsToDouble(stack.popWord()), false, ReturnKind.D); }
                case 65 -> { return finish(frame, stack, Unit.INSTANCE, false, ReturnKind.V); }
                case 69 -> { return returnTuple(frame, stack); }
                case 88 -> {} // Diagnostic name-table annotation, not executable work.
                case 94, 95 -> { long a = stack.popWord(); stack.push(op == 94 ? ~a : -a, 8); }
                case 90, 91, 92, 93, 96, 97, 98, 99, 100, 110, 111, 112, 113, 114, 115, 116, 117, 118, 119 -> {
                    long a = stack.popWord(), b = stack.popWord();
                    if (op >= 97 && op <= 99 && (b < 0 || b > 63)) throw fault("BCO shift outside GHC's defined domain");
                    int unsigned = Long.compareUnsigned(a, b);
                    stack.push(switch (op) {
                        case 90 -> a + b; case 91 -> a - b; case 92 -> a & b; case 93 -> a ^ b; case 96 -> a * b;
                        case 97 -> a << (int) b; case 98 -> a >> (int) b; case 99 -> a >>> (int) b; case 100 -> a | b;
                        default -> (switch (op) {
                            case 110 -> a != b; case 111 -> a == b; case 112 -> unsigned >= 0; case 113 -> unsigned > 0;
                            case 114 -> unsigned < 0; case 115 -> unsigned <= 0; case 116 -> a >= b; case 117 -> a > b;
                            case 118 -> a < b; default -> a <= b;
                        }) ? 1L : 0L;
                    }, 8);
                }
            }
        }
    }
}
