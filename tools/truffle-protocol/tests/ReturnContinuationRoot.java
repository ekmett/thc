package protocolprobe;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.bytecode.BytecodeRootNode;
import com.oracle.truffle.api.bytecode.ConstantOperand;
import com.oracle.truffle.api.bytecode.GenerateBytecode;
import com.oracle.truffle.api.bytecode.Instruction;
import com.oracle.truffle.api.bytecode.Operation;
import com.oracle.truffle.api.dsl.Bind;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.nodes.RootNode;
import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import thc.Language;

/** Java is required for the actual Bytecode DSL and generated continuation compiler boundary. */
@GenerateBytecode(languageClass = Language.class, enableYield = true,
    enableSerialization = true, boxingEliminationTypes = {boolean.class})
public abstract class ReturnContinuationRoot extends RootNode implements BytecodeRootNode {
  transient volatile boolean start, again;
  transient int sourceCompiled, sourceInterpreted, resumeCompiled, resumeInterpreted;
  transient int nestedCompiled, nestedInterpreted;

  protected ReturnContinuationRoot(Language language, FrameDescriptor descriptor) {
    super(language, descriptor);
  }

  @Override public final boolean requiresUnprofiledReturn() {
    // The parser fixes this constant before any source or child target is requested.
    // It survives clone and serialization without a mutable policy field or setter.
    for (Instruction instruction : getBytecodeNode().getInstructions()) {
      if (instruction.getName().startsWith("c.DeclareReturn")) {
        for (Instruction.Argument argument : instruction.getArguments())
          if (argument.getKind() == Instruction.Argument.Kind.CONSTANT
              && argument.asConstant() instanceof Boolean declared)
            return declared;
      }
    }
    throw new AssertionError("Missing immutable return-policy instruction metadata");
  }

  @Override protected final boolean requiresMaterializableFrame() {
    // Both policy lanes admit real yields: isolate return profiling from frame speculation.
    return true;
  }

  @Override public boolean isCloningAllowed() { return true; }

  @Operation
  @ConstantOperand(type = boolean.class, name = "declared")
  public static final class DeclareReturn {
    @Specialization public static void declare(boolean declared) {}
  }

  @Operation
  @ConstantOperand(type = int.class, name = "segment")
  public static final class Enter {
    @Specialization public static void enter(int segment,
        @Bind("$rootNode") ReturnContinuationRoot root) {
      boolean compiled = CompilerDirectives.inCompiledCode();
      switch (segment) {
        case 0 -> { if (compiled) root.sourceCompiled++; else root.sourceInterpreted++; }
        case 1 -> { if (compiled) root.resumeCompiled++; else root.resumeInterpreted++; }
        case 2 -> { if (compiled) root.nestedCompiled++; else root.nestedInterpreted++; }
        default -> throw new AssertionError("Unknown execution segment");
      }
    }
  }

  @Operation
  @ConstantOperand(type = int.class, name = "segment")
  public static final class ShouldYield {
    @Specialization public static boolean shouldYield(int segment,
        @Bind("$rootNode") ReturnContinuationRoot root) {
      return segment == 0 ? root.start : root.again;
    }
  }

  static void writeConstant(DataOutput out, Object value) throws IOException {
    if (value instanceof Long number) { out.writeByte(0); out.writeLong(number); }
    else if (value instanceof Boolean flag) { out.writeByte(1); out.writeBoolean(flag); }
    else if (value instanceof Integer number) { out.writeByte(2); out.writeInt(number); }
    else throw new IOException("Unsupported continuation fixture constant: " + value);
  }

  static Object readConstant(DataInput in) throws IOException {
    return switch (in.readUnsignedByte()) {
      case 0 -> in.readLong();
      case 1 -> in.readBoolean();
      case 2 -> in.readInt();
      default -> throw new IOException("Unknown continuation fixture constant");
    };
  }
}
