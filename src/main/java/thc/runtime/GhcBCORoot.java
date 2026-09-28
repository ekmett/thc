// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.TruffleSafepoint;
import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.VirtualFrame;
import java.util.ArrayList;
import thc.Language;
import static thc.runtime.RuntimeFault.fault;

/** Normal Dispatch supplies PAPs/overapplication; Thunk/Force supplies lazy updates. */
public final class GhcBCORoot extends GuestRoot {
    private final Language.State owner;
    private final GhcInstruction[] code;
    private final long[] literals;
    private final Object[] pointers;
    private final boolean[] nonPointers;
    @Child private Force force;
    @Children private Dispatch[] calls;
    private static final class Apply { final int count; Apply(int count) { this.count = count; } }
    public GhcBCORoot(Language language, Language.State owner, Metrics metrics, GhcInstruction[] code,
                      long[] literals, Object[] pointers, boolean[] nonPointers) {
        super(language, FrameDescriptor.newBuilder().build());
        this.owner = owner; this.code = code; this.literals = literals; this.pointers = pointers; this.nonPointers = nonPointers;
        force = new Force(metrics);
        calls = new Dispatch[6];
        for (int i = 0; i < calls.length; i++) calls[i] = DispatchNodeGen.create(i + 1, false, metrics);
    }
    public Language.State getOwner() { return owner; }
    public int getArity() { return nonPointers.length; }
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
    private static int index(ArrayList<Object> stack, long offset) {
        if (offset < 0 || offset >= stack.size()) throw fault("BCO stack offset outside live values");
        return stack.size() - 1 - (int) offset;
    }
    private static Object pop(ArrayList<Object> stack) { return stack.remove(index(stack, 0)); }
    private Object finish(VirtualFrame frame, ArrayList<Object> stack, Object value, boolean enter) {
        try {
            Object answer = enter ? force.execute(frame, value) : value;
            while (!stack.isEmpty()) {
                if (!(pop(stack) instanceof Apply apply)) throw fault("BCO returned over unconsumed arguments");
                var arguments = new Object[apply.count];
                for (int i = 0; i < arguments.length; i++) arguments[i] = pop(stack);
                if (!(force.execute(frame, answer) instanceof Closure function)) throw fault("BCO application requires a function");
                answer = calls[apply.count - 1].execute(frame, function, arguments);
                if (answer instanceof TailYield || SavedGuestContinuations.savedGuestContinuation(answer) != null)
                    throw fault("GHC BCO asynchronous continuation is not supported");
                answer = force.execute(frame, answer);
            }
            return answer;
        } catch (DelimitedCut ignored) {
            throw fault("Delimited capture through a GHC BCO is not supported");
        } catch (ThunkSuspended ignored) {
            throw fault("GHC BCO asynchronous thunk suspension is not supported");
        } catch (CallSegmentSuspended | CapturedCallSuspension ignored) {
            throw fault("GHC BCO asynchronous call suspension is not supported");
        }
    }
    @Override public Object execute(VirtualFrame frame) {
        // Check ownership before node lookup can consult another root's sharing layer.
        if (Language.currentState(null) != owner) throw fault("BCO belongs to another context");
        if (frame.getArguments().length != getArity() + 1) throw fault("BCO argument packet mismatch");
        var stack = new ArrayList<Object>();
        for (int i = getArity() - 1; i >= 0; i--)
            stack.add(nonPointers[i] ? word(frame.getArguments()[i + 1]) : frame.getArguments()[i + 1]);
        int pc = 0;
        while (true) {
            TruffleSafepoint.poll(this);
            var instruction = pc >= 0 && pc < code.length ? code[pc] : null;
            if (instruction == null) throw fault("BCO execution left its instruction stream");
            pc = instruction.next;
            var args = instruction.args;
            int op = instruction.opcode;
            switch (op) {
                case 1 -> { if (args[0] < 0 || args[0] > Integer.MAX_VALUE) throw fault("BCO stack request outside managed bounds"); }
                case 2, 3, 4 -> {
                    // All PUSH_LL/LLL offsets refer to the original stack pointer.
                    var copied = new ArrayList<Object>(args.length);
                    for (long offset : args) copied.add(stack.get(index(stack, offset)));
                    stack.addAll(copied);
                }
                case 11 -> stack.add(pointers[(int) args[0]]);
                case 25 -> { for (int i = (int) args[1] - 1; i >= 0; i--) stack.add(literals[(int) args[0] + i]); }
                case 31, 32, 33, 34, 35, 36 -> stack.add(new Apply(op - 30));
                case 38 -> {
                    long keep = args[0], drop = args[1];
                    if (keep < 0 || drop < 0 || keep > stack.size() || drop > stack.size() - keep)
                        throw fault("BCO SLIDE outside live values");
                    stack.subList(stack.size() - (int) keep - (int) drop, stack.size() - (int) keep).clear();
                }
                case 46, 47, 67, 68 -> {
                    long a = word(stack.get(index(stack, 0))), b = literals[(int) args[0]];
                    boolean failure = switch (op) { case 46 -> a >= b; case 67 -> Long.compareUnsigned(a, b) >= 0; default -> a != b; };
                    if (failure) pc = (int) args[1];
                }
                case 54 -> throw fault("GHC BCO CASEFAIL");
                case 55 -> pc = (int) args[0];
                case 57 -> { int i = index(stack, args[0]); stack.set(i, word(stack.get(i)) + args[1]); }
                case 58 -> { return finish(frame, stack, pop(stack), true); }
                case 60 -> { return finish(frame, stack, pop(stack), false); }
                case 61, 64 -> { return finish(frame, stack, word(pop(stack)), false); }
                case 62 -> { return finish(frame, stack, Float.intBitsToFloat((int) word(pop(stack))), false); }
                case 63 -> { return finish(frame, stack, Double.longBitsToDouble(word(pop(stack))), false); }
                case 88 -> {} // Diagnostic name-table annotation, not executable work.
                case 94, 95 -> { long a = word(pop(stack)); stack.add(op == 94 ? ~a : -a); }
                case 90, 91, 92, 93, 96, 97, 98, 99, 100, 110, 111, 112, 113, 114, 115, 116, 117, 118, 119 -> {
                    long a = word(pop(stack)), b = word(pop(stack));
                    if (op >= 97 && op <= 99 && (b < 0 || b > 63)) throw fault("BCO shift outside GHC's defined domain");
                    int unsigned = Long.compareUnsigned(a, b);
                    stack.add(switch (op) {
                        case 90 -> a + b; case 91 -> a - b; case 92 -> a & b; case 93 -> a ^ b; case 96 -> a * b;
                        case 97 -> a << (int) b; case 98 -> a >> (int) b; case 99 -> a >>> (int) b; case 100 -> a | b;
                        default -> (switch (op) {
                            case 110 -> a != b; case 111 -> a == b; case 112 -> unsigned >= 0; case 113 -> unsigned > 0;
                            case 114 -> unsigned < 0; case 115 -> unsigned <= 0; case 116 -> a >= b; case 117 -> a > b;
                            case 118 -> a < b; default -> a <= b;
                        }) ? 1L : 0L;
                    });
                }
            }
        }
    }
}
