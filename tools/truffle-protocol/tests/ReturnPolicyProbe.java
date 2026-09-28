package protocolprobe;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.DirectCallNode;
import com.oracle.truffle.api.nodes.IndirectCallNode;
import com.oracle.truffle.api.nodes.ControlFlowException;
import com.oracle.truffle.api.nodes.NodeUtil;
import com.oracle.truffle.api.exception.AbstractTruffleException;
import com.oracle.truffle.api.nodes.ExecutionSignature;
import com.oracle.truffle.api.nodes.RootNode;
import com.oracle.truffle.runtime.OptimizedCallTarget;
import org.graalvm.polyglot.Context;
import thc.Language;

/**
 * Java controls for the actual Truffle root/compiler API boundary. Compile once against the
 * overlay and run the same class with stock and overlay API/runtime jars. No profile fields
 * are read or written, and installing code never executes a settling guest call.
 */
public final class ReturnPolicyProbe {
  private static final Object ALTERNATE = new Alternate();

  private static final class Alternate {}

  static void require(boolean value, String reason) {
    if (!value) throw new AssertionError(reason);
  }

  static final class State {
    volatile boolean alternate;
    int declarations, aotPreparations, compiled, interpreted, effects;
  }

  static final class ProbeRoot extends RootNode {
    final Language language;
    final boolean declared;
    final boolean supportsAot;
    final State state;

    ProbeRoot(Language language, boolean declared, boolean supportsAot, State state) {
      super(language);
      this.language = language;
      this.declared = declared;
      this.supportsAot = supportsAot;
      this.state = state;
    }

    @Override public boolean requiresUnprofiledReturn() {
      state.declarations++;
      return declared;
    }

    @Override protected ExecutionSignature prepareForAOT() {
      state.aotPreparations++;
      return supportsAot
          ? ExecutionSignature.create(Long.class, new Class<?>[]{Long.class}) : null;
    }

    @Override public boolean isCloningAllowed() { return true; }
    @Override protected boolean isCloneUninitializedSupported() { return true; }
    @Override protected RootNode cloneUninitialized() {
      return new ProbeRoot(language, declared, supportsAot, new State());
    }

    @Override public Object execute(VirtualFrame frame) {
      if (CompilerDirectives.inCompiledCode()) state.compiled++;
      else state.interpreted++;
      state.effects++;
      Object argument = frame.getArguments()[0];
      // Both classes are supported semantically. Normal profiling can specialize this branch.
      long answer = argument instanceof Long number
          ? number.longValue() + 1L : ((String) argument).length() + 1L;
      if (state.alternate) return ALTERNATE;
      return answer;
    }
  }

  static void install(OptimizedCallTarget target) throws Exception {
    target.compile(true);
    Object runtime = Truffle.getRuntime();
    runtime.getClass().getMethod("bypassedInstalledCode", OptimizedCallTarget.class)
        .invoke(runtime, target);
    require(target.isValidLastTier(), "installed last-tier target");
  }

  static OptimizedCallTarget ordinary(ProbeRoot root, boolean overlay, String name) {
    State state = root.state;
    var target = (OptimizedCallTarget) root.getCallTarget();
    require(state.declarations == (overlay ? 1 : 0), name + ": policy before execution");
    require(state.compiled == 0 && state.interpreted == 0 && state.effects == 0,
        name + ": publication did not execute the root");
    require(state.aotPreparations == 0 && !target.isInitialized(),
        name + ": return declaration did not trigger AOT or first-execution initialization");
    if (root.supportsAot) {
      require(target.prepareForAOT(), name + ": explicit AOT preparation accepted");
      require(state.aotPreparations == 1 && target.isInitialized(),
          name + ": existing AOT initialization semantics");
      require(state.compiled == 0 && state.interpreted == 0 && state.effects == 0,
          name + ": AOT preparation did not execute the root");
    }
    for (int i = 0; i < 3; i++)
      require(target.call(41L).equals(42L), name + ": ordinary result " + i);
    require(state.compiled == 0 && state.interpreted == 3 && state.effects == 3,
        name + ": exactly three ordinary warmups");
    return target;
  }

  static void firstAlternate(ProbeRoot root, boolean overlay, String name) throws Exception {
    var target = ordinary(root, overlay, name);
    install(target);
    State state = root.state;
    int compiled = state.compiled, interpreted = state.interpreted, effects = state.effects;
    state.alternate = true;
    Object result = target.call(41L);
    boolean retained = target.isValidLastTier();
    require(result == ALTERNATE, name + ": exact alternate identity");
    require(state.compiled == compiled + 1 && state.interpreted == interpreted,
        name + ": exact first installed entry without interpreted replay");
    require(state.effects == effects + 1, name + ": exactly one first-call effect");
    require(retained == (overlay && root.declared),
        name + ": retained=" + retained + " declared=" + root.declared + " overlay=" + overlay);
    require(state.declarations == (overlay ? 1 : 0),
        name + ": declaration was not repeated during execution");
    require(state.aotPreparations == (root.supportsAot ? 1 : 0),
        name + ": no implicit AOT preparation");
    System.out.println("PASS " + name + " retained=" + retained + " compiled=" + state.compiled
        + " interpreted=" + state.interpreted + " effects=" + state.effects);
  }

  static void argumentControl(Language language, boolean declared, boolean overlay, boolean arity)
      throws Exception {
    String name = "argument " + (arity ? "arity" : "class") + " declared=" + declared;
    var root = new ProbeRoot(language, declared, false, new State());
    var target = ordinary(root, overlay, name);
    install(target);
    State state = root.state;
    int compiled = state.compiled, interpreted = state.interpreted, effects = state.effects;
    Object result = arity ? target.call(41L, "extra") : target.call("changed");
    boolean retained = target.isValidLastTier();
    require(result.equals(arity ? 42L : 8L), name + ": changed argument result");
    require(!retained, name + ": ordinary argument speculation still invalidates");
    require(state.compiled == compiled && state.interpreted == interpreted + 1,
        name + ": argument guard invalidated before root entry");
    require(state.effects == effects + 1, name + ": changed argument executed once");
    require(state.declarations == (overlay ? 1 : 0) && state.aotPreparations == 0,
        name + ": argument transition did not alter declaration or invoke AOT");
    System.out.println("PASS " + name + " retained=" + retained + " compiled=" + state.compiled
        + " interpreted=" + state.interpreted + " effects=" + state.effects);
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

  private static final class Control extends ControlFlowException {}
  private static final class GuestFailure extends AbstractTruffleException {
    final Object payload;
    GuestFailure(Object payload) { super("unforced guest payload"); this.payload = payload; }
  }
  private static final class ExceptionState {
    int compiled, entered, effects, calls;
    RuntimeException caught;
  }
  private static final class ExceptionCaller extends RootNode {
    final ExceptionState state;
    @Child IndirectCallNode call;
    ExceptionCaller(Language language, IndirectCallNode call, ExceptionState state) {
      super(language); this.call = call; this.state = state;
    }
    @Override public Object execute(VirtualFrame frame) {
      state.entered++;
      if (CompilerDirectives.inCompiledCode()) state.compiled++;
      try {
        return call.call((RootCallTarget) frame.getArguments()[0], frame.getArguments()[1]);
      } catch (RuntimeException failure) {
        state.caught = failure;
        state.effects++;
        return 42L;
      }
    }
  }

  static void firstException(Language language, boolean overlay, boolean declared) throws Exception {
    // Stock uses the ordinary factory; the additive symbol is not linked there.
    IndirectCallNode call = overlay && declared
        ? com.oracle.truffle.runtime.OptimizedIndirectCallNode.createUnprofiledExceptions()
        : IndirectCallNode.create();
    ExceptionState state = new ExceptionState();
    ExceptionCaller original = new ExceptionCaller(language, call, state);
    ExceptionCaller clone = NodeUtil.cloneNode(original);
    require(original.call != clone.call, "cloned call node owns its profile/declaration");
    Object payload = new Object() {
      @Override public String toString() { throw new AssertionError("payload forced"); }
    };
    RuntimeException[] failures = {new GuestFailure(payload), new Control(), new IllegalStateException("host")};
    RootCallTarget throwing = new RootNode(language) {
      @Override public Object execute(VirtualFrame frame) {
        state.calls++;
        throw (RuntimeException) frame.getArguments()[0];
      }
    }.getCallTarget();
    for (ExceptionCaller root : new ExceptionCaller[]{original, clone}) {
      OptimizedCallTarget target = (OptimizedCallTarget) root.getCallTarget();
      int before = state.entered, effects = state.effects, compiled = state.compiled, calls = state.calls;
      install(target);
      require(state.entered == before && state.calls == calls, "no preparation calls");
      int count = overlay && declared ? failures.length : 1;
      for (int i = 0; i < count; i++) {
        require(target.call(throwing, failures[i]).equals(42L), "caught result");
        require(state.caught == failures[i], "original thrown identity");
        require(state.entered == before + i + 1 && state.effects == effects + i + 1 &&
            state.calls == calls + i + 1 && state.compiled == compiled + i + 1,
            "first installed exception has no replay");
        require(target.isValidLastTier() == (overlay && declared), "declared exception retention");
      }
      require(((GuestFailure) failures[0]).payload == payload, "lazy payload identity");
    }
    System.out.println("PASS cold indirect exceptions declared=" + declared + " overlay=" + overlay);
  }

  static void cloneControl(Language language, boolean declared, boolean overlay) throws Exception {
    var source = new ProbeRoot(language, declared, false, new State());
    var original = source.getCallTarget();
    var caller = new CallRoot(language, original);
    caller.getCallTarget(); // Adopt the direct call without executing the source.
    require(caller.call.cloneCallTarget(), "public target clone accepted");
    var clonedTarget = (OptimizedCallTarget) caller.call.getCurrentCallTarget();
    require(clonedTarget != original, "actual split target identity");
    var clone = (ProbeRoot) clonedTarget.getRootNode();
    require(clone != source && clone.state != source.state && clone.declared == source.declared,
        "fresh clone state and preserved immutable declaration");
    require(source.state.compiled == 0 && source.state.interpreted == 0
        && source.state.effects == 0 && source.state.declarations == (overlay ? 1 : 0),
        "splitting did not execute or redeclare source");
    firstAlternate(clone, overlay, "public split declared=" + declared);
    require(caller.call.getCurrentCallTarget() == clonedTarget, "stable split target identity");
  }

  public static void main(String[] args) throws Exception {
    require(args.length == 1 && (args[0].equals("stock") || args[0].equals("overlay")),
        "usage: ReturnPolicyProbe stock|overlay (same compiled class)");
    boolean overlay = args[0].equals("overlay");
    // Initialize the pinned runtime's ordinary module exports before linking its implementation API.
    Truffle.getRuntime();
    // This additive method is deliberately not resolved on the real stock runtime binary.
    if (overlay)
      require(OptimizedCallTarget.declaredReturnPolicyVersion() == 1, "exact return-policy linkage");
    try (Context context = Context.newBuilder("thc").allowExperimentalOptions(true)
        .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false")
        .option("engine.SingleTierCompilationThreshold", "10000000")
        .option("engine.SplittingAllowForcedSplits", "true")
        .option("engine.CompilationFailureAction", "Throw").build()) {
      context.initialize("thc"); context.enter();
      try {
        Language language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
        for (boolean declared : new boolean[]{false, true}) {
          firstException(language, overlay, declared);
          firstAlternate(new ProbeRoot(language, declared, false, new State()), overlay,
              "ordinary declared=" + declared);
          firstAlternate(new ProbeRoot(language, declared, true, new State()), overlay,
              "AOT narrow return declared=" + declared);
          argumentControl(language, declared, overlay, false);
          argumentControl(language, declared, overlay, true);
          cloneControl(language, declared, overlay);
        }
      } finally { context.leave(); }
    }
  }
}
