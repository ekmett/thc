package protocolprobe;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.bytecode.BytecodeConfig;
import com.oracle.truffle.api.bytecode.BytecodeParser;
import com.oracle.truffle.api.bytecode.ContinuationResult;
import com.oracle.truffle.api.bytecode.ContinuationRootNode;
import com.oracle.truffle.api.bytecode.Instruction;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.DirectCallNode;
import com.oracle.truffle.api.nodes.RootNode;
import com.oracle.truffle.runtime.OptimizedCallTarget;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import org.graalvm.polyglot.Context;
import thc.Language;
import static protocolprobe.ReturnPolicyProbe.install;
import static protocolprobe.ReturnPolicyProbe.require;

/** Genuine generated source/resume targets; no synthetic frames, tokens, or transition training. */
public final class ReturnContinuationProbe {
  static BytecodeParser<ReturnContinuationRootGen.Builder> parser(boolean declared) {
    return b -> {
      b.beginRoot();
      b.emitDeclareReturn(declared);
      b.emitEnter(0);
      b.beginUnprofiledIfThen();
      b.emitShouldYield(0);
      b.beginBlock();
      b.beginYield(); b.emitLoadConstant(1L); b.endYield();
      b.emitEnter(1);
      b.beginUnprofiledIfThen();
      b.emitShouldYield(1);
      b.beginBlock();
      b.beginYield(); b.emitLoadConstant(2L); b.endYield();
      b.emitEnter(2);
      b.endBlock();
      b.endUnprofiledIfThen();
      b.endBlock();
      b.endUnprofiledIfThen();
      b.beginReturn(); b.emitLoadConstant(42L); b.endReturn();
      b.endRoot();
    };
  }

  static ReturnContinuationRoot create(Language language, boolean declared) {
    return ReturnContinuationRootGen.create(language, BytecodeConfig.DEFAULT, parser(declared))
        .getNode(0);
  }

  static List<ContinuationRootNode> children(ReturnContinuationRoot root) {
    List<ContinuationRootNode> result = new ArrayList<>();
    for (Instruction instruction : root.getBytecodeNode().getInstructions()) {
      for (Instruction.Argument argument : instruction.getArguments()) {
        if (argument.getKind() == Instruction.Argument.Kind.CONSTANT
            && argument.asConstant() instanceof ContinuationRootNode continuation)
          result.add(continuation);
      }
      if (instruction.getName().startsWith("branch.false.unprofiled"))
        for (Instruction.Argument argument : instruction.getArguments())
          require(argument.getKind() != Instruction.Argument.Kind.BRANCH_PROFILE,
              "continuation cut must not train a branch profile");
    }
    require(result.size() == 2 && result.get(0) != result.get(1), "two actual yield constants");
    return result;
  }

  static void untouched(ReturnContinuationRoot root, String name) {
    require(root.sourceCompiled == 0 && root.sourceInterpreted == 0
        && root.resumeCompiled == 0 && root.resumeInterpreted == 0
        && root.nestedCompiled == 0 && root.nestedInterpreted == 0,
        name + ": no guest segment has executed");
  }

  static ContinuationResult raw(Object value, ContinuationRootNode child,
      Set<ContinuationResult> seen, long expectedValue, String name) {
    require(value != null && value.getClass() == ContinuationResult.class,
        name + ": exact raw generated token class");
    ContinuationResult token = (ContinuationResult) value;
    require(seen.add(token), name + ": fresh token identity");
    require(token.getContinuationRootNode() == child
        && token.getContinuationCallTarget() == child.getCallTarget(),
        name + ": exact generated child and target identities");
    require(token.getResult().equals(expectedValue), name + ": yielded result");
    return token;
  }

  static void firstSourceAndResume(ReturnContinuationRoot root, boolean declared, String name)
      throws Exception {
    untouched(root, name);
    require(root.requiresUnprofiledReturn() == declared, name + ": immutable source metadata");
    List<ContinuationRootNode> children = children(root);
    // Publish actual generated child targets BEFORE any source or child execution.
    for (ContinuationRootNode child : children) {
      require(child.getSourceRootNode() == root && child.requiresUnprofiledReturn() == declared,
          name + ": child inherits its actual source policy");
      OptimizedCallTarget childTarget = (OptimizedCallTarget) child.getCallTarget();
      require(!childTarget.isInitialized(), name + ": child publication is not first execution");
    }
    OptimizedCallTarget source = (OptimizedCallTarget) root.getCallTarget();
    OptimizedCallTarget resume = (OptimizedCallTarget) children.get(0).getCallTarget();
    require(!source.isInitialized(), name + ": source publication is not first execution");
    untouched(root, name);
    for (int i = 0; i < 3; i++)
      require(source.call().equals(42L), name + ": ordinary source result " + i);
    require(root.sourceInterpreted == 3 && root.sourceCompiled == 0
        && root.resumeInterpreted == 0 && root.resumeCompiled == 0,
        name + ": exactly three ordinary source warmups");
    install(source);
    root.start = true;
    Object firstValue = source.call();
    boolean sourceRetained = source.isValidLastTier();
    require(root.sourceCompiled == 1 && root.sourceInterpreted == 3,
        name + ": first installed source entry without replay");
    require(sourceRetained == declared, name + ": source first-yield retention");
    Set<ContinuationResult> seen = Collections.newSetFromMap(new IdentityHashMap<>());
    ContinuationResult token = raw(firstValue, children.get(0), seen, 1L, name + " first source");

    // Each of the three ordinary resume calls consumes its own fresh real token.
    // These calls complete normally; none visits the second yield.
    for (int i = 0; i < 3; i++) {
      if (i != 0) token = raw(source.call(), children.get(0), seen, 1L, name + " resume input " + i);
      require(token.continueWith(0L).equals(42L), name + ": ordinary resume result " + i);
    }
    require(root.resumeInterpreted == 3 && root.resumeCompiled == 0
        && root.nestedInterpreted == 0 && root.nestedCompiled == 0,
        name + ": exactly three ordinary resume warmups and no nested transition");
    token = raw(source.call(), children.get(0), seen, 1L, name + " installed resume input");
    install(resume);
    int sourceCompiled = root.sourceCompiled, sourceInterpreted = root.sourceInterpreted;
    root.again = true;
    Object nestedValue = token.continueWith(0L);
    boolean resumeRetained = resume.isValidLastTier();
    require(root.resumeCompiled == 1 && root.resumeInterpreted == 3,
        name + ": first installed resume entry without replay");
    require(root.sourceCompiled == sourceCompiled && root.sourceInterpreted == sourceInterpreted,
        name + ": resuming never replays the source");
    require(root.nestedCompiled == 0 && root.nestedInterpreted == 0,
        name + ": nested suffix has not executed at capture");
    require(resumeRetained == declared, name + ": resume first nested-yield retention");
    ContinuationResult nested = raw(nestedValue, children.get(1), seen, 2L, name + " nested");
    require(nested != token && nested.getFrame() == token.getFrame(),
        name + ": distinct raw token preserves the actual saved frame identity");
    require(nested.continueWith(0L).equals(42L), name + ": nested completion");
    require(root.nestedInterpreted == 1 && root.nestedCompiled == 0
        && root.resumeCompiled == 1 && root.resumeInterpreted == 3
        && root.sourceCompiled == sourceCompiled && root.sourceInterpreted == sourceInterpreted,
        name + ": each continuation suffix executes once without replay");
    require(root.requiresUnprofiledReturn() == declared, name + ": metadata remains unchanged");
    System.out.println("PASS " + name + " sourceRetained=" + sourceRetained
        + " resumeRetained=" + resumeRetained + " rawTokens=" + seen.size());
  }

  static final class CallRoot extends RootNode {
    @Child DirectCallNode call;
    CallRoot(Language language, RootCallTarget target) {
      super(language);
      call = DirectCallNode.create(target);
    }
    @Override public Object execute(VirtualFrame frame) {
      return call.call(frame.getArguments());
    }
  }

  static void cloneControl(Language language, boolean declared) throws Exception {
    ReturnContinuationRoot original = create(language, declared);
    List<ContinuationRootNode> originalChildren = children(original);
    RootCallTarget originalTarget = original.getCallTarget();
    CallRoot caller = new CallRoot(language, originalTarget);
    caller.getCallTarget();
    require(caller.call.cloneCallTarget(), "public generated-source clone accepted");
    OptimizedCallTarget clonedTarget = (OptimizedCallTarget) caller.call.getCurrentCallTarget();
    ReturnContinuationRoot clone = (ReturnContinuationRoot) clonedTarget.getRootNode();
    require(clonedTarget != originalTarget && clone != original, "actual generated source clone");
    List<ContinuationRootNode> clonedChildren = children(clone);
    for (int i = 0; i < 2; i++) {
      require(clonedChildren.get(i) != originalChildren.get(i)
          && clonedChildren.get(i).getSourceRootNode() == clone
          && originalChildren.get(i).getSourceRootNode() == original,
          "clone owns distinct continuation constants with correct source witnesses");
    }
    untouched(original, "original before clone execution");
    firstSourceAndResume(clone, declared, "generated split declared=" + declared);
    untouched(original, "original after clone execution");
    require(caller.call.getCurrentCallTarget() == clonedTarget, "split target identity retained");
  }

  static void serializationControl(Language language, boolean declared) throws Exception {
    ReturnContinuationRoot original = create(language, declared);
    require(original.requiresUnprofiledReturn() == declared,
        "source metadata exists before target construction");
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    original.getRootNodes().serialize(new DataOutputStream(bytes),
        (context, out, value) -> ReturnContinuationRoot.writeConstant(out, value));
    byte[] serialized = bytes.toByteArray();
    ReturnContinuationRoot restored = ReturnContinuationRootGen.deserialize(
        language, BytecodeConfig.DEFAULT,
        () -> new DataInputStream(new ByteArrayInputStream(serialized)),
        (context, in) -> ReturnContinuationRoot.readConstant(in)).getNode(0);
    require(restored != original && restored.requiresUnprofiledReturn() == declared,
        "genuine deserialization restores immutable metadata before target construction");
    List<ContinuationRootNode> originalChildren = children(original);
    List<ContinuationRootNode> restoredChildren = children(restored);
    for (int i = 0; i < 2; i++)
      require(restoredChildren.get(i) != originalChildren.get(i)
          && restoredChildren.get(i).getSourceRootNode() == restored,
          "deserialization creates restored-source continuation constants");
    untouched(original, "serialized source");
    firstSourceAndResume(restored, declared, "generated serialized declared=" + declared);
    untouched(original, "serialized source after restored execution");
  }

  public static void main(String[] args) throws Exception {
    require(args.length == 0, "usage: ReturnContinuationProbe (overlay API/runtime/processor)");
    Truffle.getRuntime(); // Establish the pinned runtime module exports first.
    require(OptimizedCallTarget.declaredReturnPolicyVersion() == 1, "declared return runtime");
    require(RootNode.materializableFramePolicyVersion() == 1, "materializable frame API");
    try (Context context = Context.newBuilder("thc").allowExperimentalOptions(true)
        .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false")
        .option("engine.SingleTierCompilationThreshold", "10000000")
        .option("engine.SplittingAllowForcedSplits", "true")
        .option("engine.CompilationFailureAction", "Throw").build()) {
      context.initialize("thc"); context.enter();
      try {
        Language language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
        for (boolean declared : new boolean[]{false, true}) {
          firstSourceAndResume(create(language, declared), declared,
              "generated ordinary declared=" + declared);
          cloneControl(language, declared);
          serializationControl(language, declared);
        }
      } finally { context.leave(); }
    }
  }
}
