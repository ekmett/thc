package protocolprobe;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.bytecode.*;
import com.oracle.truffle.runtime.OptimizedCallTarget;
import org.graalvm.polyglot.Context;
import java.io.*;
import java.nio.file.*;
import thc.Language;
/** Compiler/Bytecode-DSL API controls require Java alongside the generated Java root. */
public final class BranchProbe {
  static void require(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
  static BytecodeParser<BranchRootGen.Builder> parser(boolean unprofiled) { return b -> {
    b.beginRoot();
    if(unprofiled) b.beginUnprofiledIfThen(); else b.beginIfThen();
    b.emitLoadArgument(0); b.emitMark();
    if(unprofiled) b.endUnprofiledIfThen(); else b.endIfThen();
    b.beginReturn(); b.emitLoadConstant(42L); b.endReturn(); b.endRoot();
  }; }
  static void semantic(BranchRoot root) {
    int start=BranchRoot.effects.get();
    require(root.getCallTarget().call(false).equals(42L),"false result");
    require(BranchRoot.effects.get()==start,"false effect");
    require(root.getCallTarget().call(true).equals(42L),"true result");
    require(BranchRoot.effects.get()==start+1,"true effect");
  }
  static void compiled(Language lang, boolean unprofiled, boolean initial) throws Exception {
    BranchRoot root=BranchRootGen.create(lang,BytecodeConfig.DEFAULT,parser(unprofiled)).getNode(0);
    root.getBytecodeNode().setUncachedThreshold(0);
    OptimizedCallTarget target=(OptimizedCallTarget)root.getCallTarget();
    for(int i=0;i<3;i++) require(target.call(initial).equals(42L),"warmup");
    target.compile(true);
    Object runtime=Truffle.getRuntime();
    runtime.getClass().getMethod("bypassedInstalledCode",OptimizedCallTarget.class).invoke(runtime,target);
    require(target.isValidLastTier(),"installed");
    int effects=BranchRoot.effects.get();
    Object result=target.call(!initial);
    boolean retained=target.isValidLastTier();
    require(result.equals(42L),"compiled result");
    require(BranchRoot.effects.get()==effects+(initial?0:1),"exact first effect");
    require(retained==unprofiled,"retained="+retained+" unprofiled="+unprofiled+" initial="+initial);
    System.out.println("first opposite direction unprofiled="+unprofiled+" initial="+initial+" retained="+retained);
  }
  private static final class CallerRoot extends com.oracle.truffle.api.nodes.RootNode {
    @Child private com.oracle.truffle.api.nodes.DirectCallNode call;
    CallerRoot(Language language, BranchRoot target) {
      super(language); call = com.oracle.truffle.api.nodes.DirectCallNode.create(target.getCallTarget());
    }
    @Override public Object execute(com.oracle.truffle.api.frame.VirtualFrame frame) {
      return call.call(frame.getArguments());
    }
    BranchRoot split() {
      require(call.cloneCallTarget(), "declared clone support");
      return (BranchRoot) ((com.oracle.truffle.api.RootCallTarget) call.getCurrentCallTarget()).getRootNode();
    }
  }
  static void structure(Language language) throws Exception {
    var source = com.oracle.truffle.api.source.Source.newBuilder("thc", "branch", "protocol-control").build();
    BranchRoot nested = BranchRootGen.create(language, BytecodeConfig.DEFAULT, b -> {
      b.beginSource(source); b.beginSourceSection(0, 6); b.beginRoot();
      b.beginUnprofiledIfThen(); b.emitLoadArgument(0);
      b.beginBlock();
      b.beginUnprofiledIfThen(); b.emitLoadArgument(1); b.emitMark(); b.endUnprofiledIfThen();
      b.endBlock(); b.endUnprofiledIfThen();
      b.beginReturn(); b.emitLoadConstant(42L); b.endReturn(); b.endRoot();
      b.endSourceSection(); b.endSource();
    }).getNode(0);
    int start = BranchRoot.effects.get();
    for (boolean a : new boolean[]{false, true}) for (boolean b : new boolean[]{false, true})
      require(nested.getCallTarget().call(a, b).equals(42L), "nested result");
    require(BranchRoot.effects.get() == start + 1, "nested effect only when both true");
    nested.getRootNodes().ensureSourceInformation();
    require(nested.getBytecodeNode().getSourceSection() != null, "source update");
    require(nested.getCallTarget().call(true, true).equals(42L), "source update result");
    CallerRoot caller = new CallerRoot(language, nested);
    caller.getCallTarget();
    BranchRoot clone = caller.split();
    require(clone != nested, "distinct clone");
    require(clone.getCallTarget().call(true, true).equals(42L), "clone result");
    try {
      nested.getCallTarget().call(42L, true);
      throw new AssertionError("nonboolean branch accepted");
    } catch (ClassCastException expected) {}
    try {
      BranchRootGen.create(language, BytecodeConfig.DEFAULT, b -> {
        b.beginRoot(); b.beginUnprofiledIfThen(); b.emitLoadArgument(0);
        b.endUnprofiledIfThen(); b.endRoot();
      });
      throw new AssertionError("missing branch body accepted");
    } catch (IllegalStateException | IllegalArgumentException expected) {}
    System.out.println("PASS nested/void stack, source reparse, clone and malformed operand controls");
  }
  public static void main(String[] args) throws Exception {
    try(Context context=Context.newBuilder("thc").allowExperimentalOptions(true)
      .option("engine.BackgroundCompilation","false").option("engine.MultiTier","false")
      .option("engine.SingleTierCompilationThreshold","10000000")
      .option("engine.CompilationFailureAction","Throw").build()) {
      context.initialize("thc"); context.enter();
      try {
        Language lang=TruffleLanguage.LanguageReference.create(Language.class).get(null);
        structure(lang);
        for(boolean policy:new boolean[]{false,true}) for(boolean initial:new boolean[]{false,true}) compiled(lang,policy,initial);
        BranchRoot uncached=BranchRootGen.create(lang,BytecodeConfig.DEFAULT,parser(true)).getNode(0);
        uncached.getBytecodeNode().setUncachedThreshold(Integer.MAX_VALUE); semantic(uncached);
        byte[] stock=Files.readAllBytes(Path.of(args[0]));
        BranchRoot legacy=BranchRootGen.deserialize(lang,BytecodeConfig.DEFAULT,
          ()->new DataInputStream(new ByteArrayInputStream(stock)),(c,in)->BranchRoot.readConstant(in)).getNode(0);
        semantic(legacy);
        var bytes=new ByteArrayOutputStream();
        BranchRoot serialized = BranchRootGen.create(lang, BytecodeConfig.DEFAULT, parser(true)).getNode(0);
        serialized.materializationDeclared = true;
        require(serialized.materializationDeclarations == 0, "serialized policy is configured before target publication");
        serialized.getRootNodes().serialize(new DataOutputStream(bytes),
          (c,out,value)->BranchRoot.writeConstant(out,value));
        BranchRoot roundtrip=BranchRootGen.deserialize(lang,BytecodeConfig.DEFAULT,
          ()->new DataInputStream(new ByteArrayInputStream(bytes.toByteArray())),(c,in)->BranchRoot.readConstant(in)).getNode(0);
        // Generated field deserialization restores policy before publishing
        // the target, with no guest execution or synthetic materialization.
        require(roundtrip.materializationDeclarations == 0, "deserialized target not published");
        require(roundtrip.materializationDeclared, "serialized root policy restored");
        roundtrip.getCallTarget();
        require(roundtrip.materializationDeclarations == 1, "deserialized root declaration before execution");
        semantic(roundtrip);
        require(roundtrip.materializationDeclarations == 1, "no declaration during deserialized execution");
        boolean found=false;
        for(Instruction instruction:roundtrip.getBytecodeNode().getInstructions()) {
          if(instruction.getName().startsWith("branch.false.unprofiled")) {
            found=true;
            for(Instruction.Argument arg:instruction.getArguments())
              require(arg.getKind()!=Instruction.Argument.Kind.BRANCH_PROFILE,"invented branch profile");
          }
        }
        require(found,"unprofiled instruction survives serialization");
        System.out.println("PASS cached/uncached, stock stream/custom operation, new roundtrip and introspection");
      } finally { context.leave(); }
    }
  }
}
