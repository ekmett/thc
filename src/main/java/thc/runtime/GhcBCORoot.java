// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.TruffleSafepoint;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.VirtualFrame;
import java.util.Arrays;
import java.util.ArrayDeque;
import thc.Language;
import static thc.runtime.RuntimeFault.fault;

/** Native BCO stack frames and owned AP/PAP payloads; Thunk/Force supplies lazy updates. */
public final class GhcBCORoot extends GuestRoot {
    private final Language.State owner;
    private final Metrics metrics;
    private final GhcInstruction[] code;
    private final long[] literals;
    private final Object[] pointers;
    private final int arity;
    private final boolean[] nonPointers;
    @Child private Force force;
    @Children private Dispatch[] calls;
    private record Apply(ReturnKind kind, int count) implements GhcBCOStack.Marker {
        int words() { return kind == ReturnKind.V ? 0 : count; }
    }
    // The cached target owns only layout metadata. Each closure/thunk environment
    // owns its payload, which Force can release when an updating thunk completes.
    private final CaptureLayout applicationLayout;
    private GhcBCORoot applicationEntry;
    private static final class Application {
        final GhcBCORoot entry;
        final int arity, words;
        final boolean thunk;
        private volatile Body body;
        Application(GhcBCORoot entry, int arity, int words, boolean thunk) {
            this.entry = entry; this.arity = arity; this.words = words; this.thunk = thunk;
        }
        synchronized void fill(GhcBCORoot root, GhcBCOStack payload) {
            if (body != null) throw fault("BCO application filled twice");
            if (payload.words() != words || arity > root.arity || thunk && words != root.nonPointers.length)
                throw fault("BCO application payload/arity mismatch");
            payload.validatePrefix(root.nonPointers);
            body = new Body(root, payload);
        }
        Body body() {
            Body result = body;
            if (result == null) throw fault("BCO application entered before initialization");
            return result;
        }
    }
    private record Body(GhcBCORoot root, GhcBCOStack payload) {}

    private enum ReturnKind implements GhcBCOStack.Marker { P, N, F, D, L, V, T }
    private record CaseFrame(ReturnKind kind, Closure continuation, long info, GhcBCORoot tuple) implements GhcBCOStack.Marker {}
    public GhcBCORoot(Language language, Language.State owner, Metrics metrics, GhcInstruction[] code,
                      long[] literals, Object[] pointers, int arity, boolean[] nonPointers) {
        super(language, FrameDescriptor.newBuilder().build());
        this.owner = owner; this.metrics = metrics; this.code = code; this.literals = literals; this.pointers = pointers; this.arity = arity; this.nonPointers = nonPointers;
        applicationLayout = code == null ? new CaptureLayout(language, new boolean[] { false }) : null;
        if (applicationLayout != null) configureEntry(new boolean[0], true);
        force = new Force(metrics, true);
        calls = new Dispatch[6];
        for (int i = 0; i < calls.length; i++) calls[i] = DispatchNodeGen.create(i + 1, false, metrics);
    }
    @TruffleBoundary private synchronized GhcBCORoot applicationEntry() {
        if (applicationEntry == null)
            applicationEntry = new GhcBCORoot(getLanguage(Language.class), owner, metrics, null, null, null, 0, new boolean[0]);
        return applicationEntry;
    }
    private Application application(CapturedFrame environment) {
        requireOwner();
        if (applicationLayout == null || environment == null || environment.getLayout() != applicationLayout ||
            !(environment.getObject(0) instanceof Application app) || app.entry != this)
            throw fault("BCO application environment mismatch");
        return app;
    }
    private static int bound(long value) {
        if (value < 0 || value > Integer.MAX_VALUE / 8 - 1) throw fault("BCO application outside managed bounds");
        return (int) value;
    }
    private static int logicalArity(long value) {
        if (value <= 0 || value >= Integer.MAX_VALUE) throw fault("BCO PAP arity outside managed bounds");
        return (int) value;
    }
    private Object allocate(int arity, int words, boolean thunk) {
        if (!thunk && arity == 0) throw fault("BCO PAP requires positive arity");
        GhcBCORoot entry = applicationEntry();
        Application app = new Application(entry, arity, words, thunk);
        CapturedFrame environment = entry.applicationLayout.captureValues(new Object[] { app });
        return thunk ? new Thunk(entry.getCallTarget(), environment) : new Closure(environment, arity, entry.getCallTarget());
    }
    private static Application allocated(Object value, boolean thunk) {
        if (thunk && value instanceof Thunk pending && pending.getTarget() != null && pending.getTarget().getRootNode() instanceof GhcBCORoot root)
            return root.application(pending.getEnvironment());
        if (!thunk && value instanceof Closure closure && closure.suppliedCount == 0 && closure.target.getRootNode() instanceof GhcBCORoot root)
            return root.application(closure.environment);
        throw fault("BCO application allocation kind mismatch");
    }
    private void fill(GhcBCOStack stack, long offset, int count, boolean thunk) {
        if (offset <= count) throw fault("BCO application destination overlaps its payload");
        Application app = allocated(stack.pointer(offset), thunk);
        if (app.thunk != thunk) throw fault("BCO application allocation kind mismatch");
        Object function = stack.pointer(0);
        if (!(function instanceof Closure closure) || closure.environment != null || closure.suppliedCount != 0 ||
            !(closure.target.getRootNode() instanceof GhcBCORoot root) || root.applicationLayout != null)
            throw fault("BCO application body requires an unapplied BCO");
        root.requireOwner();
        app.fill(root, stack.snapshot(1, count));
        stack.dropWords(count + 1);
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
        if (root.arity != 0 || root.applicationLayout != null) throw fault("BCO continuation must have zero arity");
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
            GhcBCOStack arguments = stack.snapshot(0, apply.words());
            if (apply.kind == ReturnKind.P) for (int i = 0; i < arguments.words(); i++) arguments.pointer(i);
            else if (apply.kind != ReturnKind.V) arguments.read(0, 8);
            stack.dropWords(apply.words());
            Object ready;
            try { ready = AstControl.force(frame, this, force, answer); }
            catch (AstCapture cut) {
                throw cut.append((saved, input) -> finish(saved, stack, call(saved, stack, input, apply, arguments), true, null));
            }
            answer = call(frame, stack, ready, apply, arguments);
            kind = null;
            try { answer = AstControl.force(frame, this, force, answer); }
            catch (AstCapture cut) { throw cut.append((saved, input) -> finish(saved, stack, input, false, null)); }
        }
        return answer;
    }
    private Object call(VirtualFrame frame, GhcBCOStack stack, Object value, Apply apply, GhcBCOStack arguments) {
        if (!(value instanceof Closure function)) throw fault("BCO application requires a function");
        if (function.target.getRootNode() instanceof GhcBCORoot target) {
            target.requireOwner();
            if (function.arity == 0) throw fault("BCO function entry requires positive arity");
            Body body;
            if (target.applicationLayout != null) body = target.application(function.environment).body();
            else body = new Body(target, new GhcBCOStack());
            GhcBCORoot root = body.root;
            var prefix = new GhcBCOStack();
            // Ordinary Core PAPs have a checked one-word layout. Internal BCO
            // PAPs instead retain physical payload and logical arity separately.
            root.pushExternal(prefix, function.supplied, 0, body.payload.words(), function.suppliedCount);
            prefix.pushPayload(body.payload);
            int consumed = Math.min(function.arity, apply.count);
            if (consumed < apply.count && apply.kind != ReturnKind.P) throw fault("BCO non-pointer overapplication");
            if (consumed < apply.count) {
                stack.pushPayload(arguments.snapshot(consumed, apply.count - consumed));
                stack.pushReference(new Apply(ReturnKind.P, apply.count - consumed));
            }
            var payload = arguments.snapshot(0, apply.kind == ReturnKind.V ? 0 : consumed);
            payload.pushPayload(prefix);
            payload.validatePrefix(root.nonPointers);
            if (function.arity > apply.count) {
                Closure result = (Closure) root.allocate(function.arity - apply.count, payload.words(), false);
                allocated(result, false).fill(root, payload);
                return result;
            }
            if (payload.words() != root.nonPointers.length) throw fault("BCO saturated application disagrees with its bitmap");
            stack.pushPayload(payload);
            try { return AstControl.complete(this, root.executeStack(frame, stack), root.getCallTarget()); }
            catch (AstCapture cut) { throw cut.append((saved, input) -> finish(saved, stack, input, true, null)); }
        }
        Object[] values = new Object[apply.count];
        for (int i = 0; i < values.length; i++) values[i] = switch (apply.kind) {
            case P -> arguments.pointer(i);
            case N, L -> arguments.read(0, 8);
            case F -> Float.intBitsToFloat((int) arguments.read(0, 4));
            case D -> Double.longBitsToDouble(arguments.read(0, 8));
            case V -> Unit.INSTANCE;
            default -> throw fault("BCO application convention unsupported");
        };
        try { return calls[values.length - 1].execute(frame, function, values); }
        catch (AstCapture cut) { throw cut.append((saved, input) -> finish(saved, stack, input, true, null)); }
    }
    void requireOwner() {
        if (Language.currentState(null) != owner) throw fault("BCO belongs to another context");
    }
    private AstCapture own(AstCapture cut) {
        return cut.enclose(steps -> (frame, input) -> resume(frame, steps, input));
    }
    private Object resume(VirtualFrame frame, ArrayDeque<AstResumeStep> steps, Object input) {
        requireOwner();
        try { return AstContinuations.resumeAstSteps(frame, steps, input); }
        catch (AstCapture cut) { throw own(cut); }
        catch (DelimitedCut ignored) { throw fault("Delimited capture through a GHC BCO is not supported"); }
    }
    @Override public Object execute(VirtualFrame frame) {
        // Check ownership before node lookup can consult another root's sharing layer.
        requireOwner();
        var stack = new GhcBCOStack();
        GhcBCORoot body = this;
        if (applicationLayout != null) {
            Object[] arguments = frame.getArguments();
            if (arguments.length < 2 || !(arguments[1] instanceof CapturedFrame environment))
                throw fault("BCO application argument packet mismatch");
            Application app = application(environment);
            Body contents = app.body();
            body = contents.root;
            body.pushExternal(stack, arguments, 2, contents.payload.words(), app.arity);
            stack.pushPayload(contents.payload);
            if (stack.words() != body.nonPointers.length) throw fault("BCO application entry disagrees with its bitmap");
        } else pushArguments(stack, frame.getArguments(), 1);
        return executeStack(frame, stack, body);
    }
    private void pushExternal(GhcBCOStack stack, Object[] arguments, int skip, int offset, int logical) {
        if (arguments.length - skip != logical || logical > nonPointers.length - offset)
            throw fault("BCO function entry requires a known argument word layout");
        for (int i = logical - 1; i >= 0; i--) {
            if (nonPointers[offset + i]) pushWord(stack, arguments[i + skip]);
            else stack.pushReference(arguments[i + skip]);
        }
    }
    private void pushArguments(GhcBCOStack stack, Object[] arguments, int skip) {
        if (arity != nonPointers.length) throw fault("BCO function entry requires a known argument word layout");
        pushExternal(stack, arguments, skip, 0, arity);
    }
    private Object executeStack(VirtualFrame frame, GhcBCOStack stack) { return executeStack(frame, stack, this); }
    private Object executeStack(VirtualFrame frame, GhcBCOStack stack, GhcBCORoot body) {
        body.requireOwner();
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
                            .append((saved, input) -> body.resumeLoop(saved, stack, 0, input));
                    }
                    result = body.run(frame, stack, 0);
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
                case 26, 27, 28, 29, 30 -> stack.pushReference(new Apply(ReturnKind.values()[op - 25], 1));
                case 31, 32, 33, 34, 35, 36 -> stack.pushReference(new Apply(ReturnKind.P, op - 30));
                case 39, 40 -> stack.pushReference(allocate(0, bound(args[0]), true));
                case 41 -> stack.pushReference(allocate(logicalArity(args[0]), bound(args[1]), false));
                case 42, 43 -> fill(stack, args[0], bound(args[1]), op == 42);
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
