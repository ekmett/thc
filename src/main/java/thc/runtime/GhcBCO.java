// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.nodes.Node;
import java.util.List;
import thc.Language;
import static thc.runtime.RuntimeFault.fault;

/** GHC 9.14.1 Bytecodes.h, not THC's Truffle bytecode format. Operands are
 * native-endian Word16s; LARGE_ARGS joins four words most-significant first.
 * Decoding owns code/literals, while the pointer table retains real lazy guest
 * references. Native info-table addresses are never interpreted as JVM objects. */
public final class GhcBCO {
    private GhcBCO() {}
    private static boolean scalar(CoreRepresentation rep) { return !rep.isAggregate() && !rep.isVector(); }
    private static boolean pointer(CoreRepresentation rep) {
        return scalar(rep) && (rep.getKind() == CoreKind.OBJECT || rep.getKind() == CoreKind.DATA || rep.getKind() == CoreKind.CLOSURE);
    }
    public static void validate(String name, List<CoreRepresentation> args, List<?> flags, CoreRepresentation result) {
        var fields = result.getComponents();
        boolean valid = name.equals("newBCO#") ? args.size() == 6 &&
            pointer(args.get(0)) && pointer(args.get(1)) && pointer(args.get(2)) && pointer(args.get(4)) &&
            scalar(args.get(3)) && args.get(3).getKind() == CoreKind.LONG &&
            scalar(args.get(5)) && args.get(5).getKind() == CoreKind.VOID && flags.equals(List.of(false, false, false, false, false, false)) &&
            result.isTuple() && fields != null && fields.size() == 2 && fields.get(0).getKind() == CoreKind.VOID && pointer(fields.get(1)) :
            args.size() == 1 && pointer(args.get(0)) && flags.equals(List.of(true)) &&
            result.isTuple() && fields != null && fields.size() == 1 && pointer(fields.get(0));
        if (!valid) throw fault(name + ": incompatible BCO operands or result shape");
        TupleShape.validate(result);
    }
    @TruffleBoundary
    public static Closure create(Node node, Language language, Metrics metrics, Object code, Object literals, Object pointers,
                                 long arity, Object bitmap, Object state) {
        TupleResults.requireVoidCarrier(state);
        if (arity < 0 || arity >= Integer.MAX_VALUE) throw fault("BCO arity outside managed calling convention");
        long codeBytes = ManagedByteArray.sizeGuest(code);
        long literalBytes = ManagedByteArray.sizeGuest(literals);
        long bitmapBytes = ManagedByteArray.sizeGuest(bitmap);
        if (codeBytes == 0 || codeBytes % 2 != 0 || literalBytes % 8 != 0 || bitmapBytes < 8 || bitmapBytes % 8 != 0)
            throw fault("BCO requires Word16 instructions and 64-bit literals/bitmap");
        long stackWords = ManagedByteArray.readIntGuest(bitmap, 0);
        if (stackWords < 0 || stackWords > Integer.MAX_VALUE || bitmapBytes < 8 + ((stackWords + 63) / 64) * 8)
            throw fault("BCO bitmap outside managed bounds or truncated");
        var nonPointers = new boolean[(int) stackWords];
        for (int i = 0; i < nonPointers.length; i++)
            nonPointers[i] = (ManagedByteArray.readIntGuest(bitmap, 1 + (long) i / 64) >>> (i % 64) & 1L) != 0;
        var words = new int[(int) (codeBytes / 2)];
        for (int i = 0; i < words.length; i++) words[i] = (int) ManagedByteArray.readInt16Guest(code, i, true);
        var data = new long[(int) (literalBytes / 8)];
        for (int i = 0; i < data.length; i++) data[i] = ManagedByteArray.readIntGuest(literals, i);
        var refs = ManagedArray.require(pointers);
        var instructions = new GhcInstruction[words.length];
        int pc = 0;
        while (pc < words.length) {
            int start = pc, encoded = words[pc++];
            if ((encoded & 0x7f00) != 0) throw fault("BCO instruction has unknown flags");
            int opcode = encoded & 255;
            int count = switch (opcode) {
                case 1, 2, 5, 6, 7, 8, 9, 10, 11, 13, 14, 15, 16, 17, 18, 22, 23, 24, 39, 40, 55, 88 -> 1;
                case 3, 25, 38, 41, 42, 43, 46, 47, 57, 67, 68 -> 2;
                case 4, 70 -> 3;
                case 19, 20, 21, 26, 27, 28, 29, 30, 31, 32, 33, 34, 35, 36, 54, 58, 60, 61, 62, 63, 64, 65, 69,
                     90, 91, 92, 93, 94, 95, 96, 97, 98, 99, 100, 110, 111, 112, 113, 114, 115, 116, 117, 118, 119 -> 0;
                default -> throw fault("Unsupported GHC 9.14.1 BCO opcode " + opcode + " at Word16 " + start);
            };
            var operands = new long[count];
            for (int i = 0; i < count; i++) {
                int width = (encoded & 0x8000) != 0 && opcode != 88 ? 4 : 1;
                if (pc > words.length - width) throw fault("Truncated BCO operand at Word16 " + start);
                long value = 0;
                for (int j = 0; j < width; j++) value = (value << 16) | (long) words[pc++];
                operands[i] = value;
            }
            instructions[start] = new GhcInstruction(opcode, operands, pc);
        }
        for (var instruction : instructions) {
            if (instruction == null) continue;
            switch (instruction.opcode) {
                case 11, 13, 14, 15, 16, 17, 18 -> table(instruction.args[0], refs.length);
                case 70 -> { table(instruction.args[0], refs.length); table(instruction.args[1], data.length); table(instruction.args[2], refs.length); }
                case 22, 23, 24 -> table(instruction.args[0], data.length);
                case 25 -> {
                    long first = instruction.args[0], count = instruction.args[1];
                    if (first < 0 || count < 0 || first > data.length || count > data.length - first)
                        throw fault("BCO literal range outside its storage");
                }
                case 46, 47, 67, 68 -> table(instruction.args[0], data.length);
            }
            Long jump = switch (instruction.opcode) {
                case 55 -> instruction.args[0];
                case 46, 47, 67, 68 -> instruction.args[1];
                default -> null;
            };
            if (jump != null && (jump < 0 || jump >= instructions.length || instructions[jump.intValue()] == null))
                throw fault("BCO jump does not name an instruction boundary");
        }
        var root = new GhcBCORoot(language, Language.currentState(node), metrics, instructions, data, refs, (int) arity, nonPointers);
        return new Closure(null, (int) arity, root.getCallTarget());
    }
    private static void table(long index, int size) {
        if (index < 0 || index >= size) throw fault("BCO table index outside its storage");
    }
    public static Thunk updating(Node node, Object value) {
        if (!(value instanceof Closure closure) || !(closure.target.getRootNode() instanceof GhcBCORoot root))
            throw fault("mkApUpd0# requires a BCO");
        if (root.getOwner() != Language.currentState(node)) throw fault("BCO belongs to another context");
        if (closure.arity != 0 || closure.suppliedCount != 0 || root.getArity() != 0 || root.getStackWords() != 0)
            throw fault("mkApUpd0# requires a zero-arity BCO");
        return new Thunk(closure.target, null);
    }
}
