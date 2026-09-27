package protocolprobe;

import com.oracle.truffle.api.*;
import com.oracle.truffle.api.frame.*;
import com.oracle.truffle.api.nodes.*;
import com.oracle.truffle.runtime.OptimizedCallTarget;
import org.graalvm.polyglot.Context;
import thc.Language;

/** Compile once against the overlay, then run this same class with stock and overlay API jars.
 * No guest profile fields are changed, and no frame is materialized to prime a descriptor. */
public final class MaterializationProbe {
  static void require(boolean value, String reason) {
    if (!value) throw new AssertionError(reason);
  }

  static final class State {
    volatile boolean capture;
    MaterializedFrame saved;
    int declarations, compiled;
  }

  static final class ProbeRoot extends RootNode {
    final Language language;
    final boolean declared;
    final State state;

    ProbeRoot(Language language, FrameDescriptor descriptor, boolean declared, State state) {
      super(language, descriptor);
      this.language = language;
      this.declared = declared;
      this.state = state;
    }

    @Override protected boolean requiresMaterializableFrame() {
      state.declarations++;
      return declared;
    }

    @Override public boolean isCloningAllowed() { return true; }
    @Override protected boolean isCloneUninitializedSupported() { return true; }
    @Override protected RootNode cloneUninitialized() {
      return new ProbeRoot(language, getFrameDescriptor(), declared, new State());
    }

    @Override public Object execute(VirtualFrame frame) {
      frame.setLong(0, 42L);
      if (CompilerDirectives.inCompiledCode()) state.compiled++;
      if (state.capture) state.saved = frame.materialize();
      return 42L;
    }
  }

  static FrameDescriptor descriptor() {
    var builder = FrameDescriptor.newBuilder();
    builder.addSlot(FrameSlotKind.Long, null, null);
    return builder.build();
  }

  static void install(OptimizedCallTarget target) throws Exception {
    target.compile(true);
    Object runtime = Truffle.getRuntime();
    runtime.getClass().getMethod("bypassedInstalledCode", OptimizedCallTarget.class)
        .invoke(runtime, target);
    require(target.isValidLastTier(), "installed");
  }

  static void firstCapture(ProbeRoot root, boolean overlay, boolean materializable, String name)
      throws Exception {
    State state = root.state;
    var target = (OptimizedCallTarget) root.getCallTarget();
    require(state.declarations == (overlay ? 1 : 0) && state.saved == null && state.compiled == 0,
        name + ": declaration before first execution");
    for (int i = 0; i < 3; i++) require(target.call().equals(42L), name + ": ordinary value");
    install(target);
    int before = state.compiled;
    state.capture = true;
    require(target.call().equals(42L), name + ": captured value");
    boolean valid = target.isValidLastTier();
    require(state.compiled == before + 1, name + ": first installed entry");
    require(state.saved != null && state.saved.getLong(0) == 42L, name + ": saved frame contents");
    // The ordinary policy permits speculation; it does not require a deoptimization.
    // In this handwritten root the compiler can allocate the frame on the cold path.
    if (materializable) require(valid, name + ": declared first capture retained");
    require(state.declarations == (overlay ? 1 : 0), name + ": no execution-time declaration");
    System.out.println("PASS " + name + " declarations=" + state.declarations + " retained=" + valid);
  }

  static final class CallRoot extends RootNode {
    @Child DirectCallNode call;
    CallRoot(Language language, RootCallTarget target) {
      super(language);
      call = DirectCallNode.create(target);
    }
    @Override public Object execute(VirtualFrame frame) { return call.call(); }
  }

  static void cloneControl(Language language, boolean overlay) throws Exception {
    var source = new ProbeRoot(language, descriptor(), true, new State());
    var original = source.getCallTarget();
    var caller = new CallRoot(language, original);
    caller.getCallTarget(); // Adopt the call node without executing a guest root.
    require(caller.call.cloneCallTarget(), "public target clone accepted");
    var clonedTarget = caller.call.getCurrentCallTarget();
    require(clonedTarget != original, "actual split target identity");
    var clone = (ProbeRoot) ((RootCallTarget) clonedTarget).getRootNode();
    require(clone != source && clone.state != source.state, "fresh clone state");
    require(clone.getFrameDescriptor() == source.getFrameDescriptor(), "shared clone descriptor");
    require(source.state.declarations == (overlay ? 1 : 0) && source.state.saved == null
        && source.state.compiled == 0, "cloning does not execute or redeclare the source");
    firstCapture(clone, overlay, overlay, "public split clone");
    require(caller.call.getCurrentCallTarget() == clonedTarget, "stable split target after capture");
  }

  static final class LoopState {
    int declarations, compiled, copies;
    boolean sameArguments;
    final Object[] arguments = { new Object() };
  }

  static final class OsrBody extends Node implements BytecodeOSRNode {
    @CompilerDirectives.CompilationFinal private Object metadata;
    final LoopState state;
    OsrBody(LoopState state) { this.state = state; }
    public Object getOSRMetadata() { return metadata; }
    public void setOSRMetadata(Object value) { metadata = value; }

    Object loop(VirtualFrame frame) {
      while (frame.getLong(0) < 100000L) {
        frame.setLong(0, frame.getLong(0) + 1L);
        if (CompilerDirectives.inInterpreter() && BytecodeOSRNode.pollOSRBackEdge(this)) {
          Object value = BytecodeOSRNode.tryOSR(this, 0, null, null, frame);
          if (value != null) return value;
        }
      }
      return frame.getLong(0);
    }

    public Object executeOSR(VirtualFrame frame, int bci, Object ignored) {
      if (CompilerDirectives.inCompiledCode()) state.compiled++;
      state.sameArguments = frame.getArguments() == state.arguments;
      return loop(frame);
    }

    public void copyIntoOSRFrame(VirtualFrame osr, VirtualFrame parent, int target, Object metadata) {
      state.copies++;
      BytecodeOSRNode.super.copyIntoOSRFrame(osr, parent, target, metadata);
    }
  }

  static final class OsrRoot extends RootNode {
    final boolean declared;
    final LoopState state;
    @Child OsrBody body;
    OsrRoot(Language language, FrameDescriptor descriptor, boolean declared, LoopState state) {
      super(language, descriptor);
      this.declared = declared;
      this.state = state;
      body = new OsrBody(state);
    }
    @Override protected boolean requiresMaterializableFrame() {
      state.declarations++;
      return declared;
    }
    @Override public Object execute(VirtualFrame frame) {
      frame.setLong(0, 0L);
      Object result = body.loop(frame);
      require(frame.getLong(0) == 100000L, "OSR preserves or restores parent slot");
      return result;
    }
  }

  static void osr(Language language, FrameDescriptor descriptor, boolean declared, boolean overlay,
      boolean parentFrame, String name) {
    LoopState state = new LoopState();
    OsrRoot root = new OsrRoot(language, descriptor, declared, state);
    var target = root.getCallTarget();
    require(state.declarations == (overlay ? 1 : 0) && state.compiled == 0 && state.copies == 0,
        name + ": policy before OSR execution");
    require(target.call(state.arguments).equals(100000L), name + ": OSR value");
    require(state.compiled > 0, name + ": actual compiled OSR");
    require(state.sameArguments == parentFrame, name + ": OSR parent-frame arguments");
    require((state.copies == 0) == parentFrame, name + ": OSR parent-frame transfer");
    require(state.declarations == (overlay ? 1 : 0), name + ": OSR does not redeclare source policy");
    System.out.println("PASS " + name + " parentFrame=" + parentFrame + " compiled=" + state.compiled
        + " copies=" + state.copies + " declarations=" + state.declarations);
  }

  public static void main(String[] args) throws Exception {
    require(args.length == 1 && (args[0].equals("stock") || args[0].equals("overlay")),
        "usage: MaterializationProbe stock|overlay (same compiled class)");
    boolean overlay = args[0].equals("overlay");
    // Do not resolve this additive method on the real stock API binary.
    if (overlay) require(RootNode.materializableFramePolicyVersion() == 1, "exact additive API linkage");
    try (Context context = Context.newBuilder("thc").allowExperimentalOptions(true)
        .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false")
        .option("engine.SingleTierCompilationThreshold", "10000000")
        .option("engine.OSRCompilationThreshold", "1024")
        .option("engine.SplittingAllowForcedSplits", "true")
        .option("engine.CompilationFailureAction", "Throw").build()) {
      context.initialize("thc"); context.enter();
      try {
        Language language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
        firstCapture(new ProbeRoot(language, descriptor(), false, new State()), overlay, false, "default");
        firstCapture(new ProbeRoot(language, descriptor(), true, new State()), overlay, overlay, "declared");
        // This descriptor is marked only by target publication, never by executing a seed root.
        FrameDescriptor shared = descriptor();
        var seed = new ProbeRoot(language, shared, true, new State());
        seed.getCallTarget();
        firstCapture(new ProbeRoot(language, shared.copy(), false, new State()), overlay, false, "copied default");
        firstCapture(new ProbeRoot(language, shared.copy(), true, new State()), overlay, overlay, "copied declared");
        // OSR distinguishes policy from an incidental successful materialize implementation.
        // It also proves stock descriptor.copy resets the observation on the same binary.
        osr(language, descriptor(), false, overlay, false, "OSR default");
        osr(language, descriptor(), true, overlay, overlay, "OSR declared");
        osr(language, shared, false, overlay, overlay, "OSR shared undeclared");
        osr(language, shared.copy(), false, overlay, false, "OSR copied default");
        osr(language, shared.copy(), true, overlay, overlay, "OSR copied declared");
        firstCapture(new ProbeRoot(language, shared, false, new State()), overlay, overlay, "shared undeclared");
        cloneControl(language, overlay);
        require(seed.state.saved == null && seed.state.compiled == 0, "shared seed was never executed");
      } finally { context.leave(); }
    }
  }
}
