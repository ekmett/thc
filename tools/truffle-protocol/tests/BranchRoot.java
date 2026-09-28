package protocolprobe;
import com.oracle.truffle.api.bytecode.*;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.nodes.RootNode;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import java.util.concurrent.atomic.AtomicInteger;
import thc.Language;
@GenerateBytecode(languageClass=Language.class, enableSerialization=true,
    enableUncachedInterpreter=true, boxingEliminationTypes={boolean.class})
public abstract class BranchRoot extends RootNode implements BytecodeRootNode {
    static final AtomicInteger effects = new AtomicInteger();
    static void writeConstant(java.io.DataOutput out, Object value) throws java.io.IOException {
        if (value instanceof Long number) { out.writeByte(0); out.writeLong(number); }
        else if (value instanceof Boolean flag) { out.writeByte(1); out.writeBoolean(flag); }
        else if (value instanceof Integer number) { out.writeByte(2); out.writeInt(number); }
        else throw new java.io.IOException("Unsupported fixture constant: " + value);
    }
    static Object readConstant(java.io.DataInput in) throws java.io.IOException {
        return switch (in.readUnsignedByte()) {
            case 0 -> in.readLong();
            case 1 -> in.readBoolean();
            case 2 -> in.readInt();
            default -> throw new java.io.IOException("Unknown fixture constant tag");
        };
    }
    protected BranchRoot(Language language, FrameDescriptor descriptor) { super(language, descriptor); }
    boolean materializationDeclared;
    int materializationDeclarations;
    @Override protected boolean requiresMaterializableFrame() {
        materializationDeclarations++;
        return materializationDeclared;
    }
    @Override public boolean isCloningAllowed() { return true; }
    @Operation public static final class Mark {
        @Specialization @TruffleBoundary public static void mark() { effects.incrementAndGet(); }
    }
    @Operation public static final class Next {
        @Specialization @TruffleBoundary public static boolean next(AtomicInteger remaining) {
            return remaining.getAndDecrement() > 0;
        }
    }
}
