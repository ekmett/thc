// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.FrameSlotKind;
import com.oracle.truffle.api.frame.MaterializedFrame;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.ControlFlowException;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.oracle.truffle.api.nodes.ExecutionSignature;
import com.oracle.truffle.api.nodes.LoopNode;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.NodeUtil;
import com.oracle.truffle.api.nodes.DirectCallNode;
import com.oracle.truffle.api.nodes.RepeatingNode;
import com.oracle.truffle.api.profiles.BranchProfile;
import com.oracle.truffle.api.source.SourceSection;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Arrays;
import java.util.List;
import static thc.runtime.RuntimeFault.fault;
import static thc.runtime.AstSelfCalls.requireReferenceCarrier;
import static thc.runtime.AstStacks.astStackScope;
import static thc.runtime.SavedGuestContinuations.savedGuestContinuation;

/* Indexed frames, selective captures, rooted application and self-tail frame
 * restoration follow Cadenza. See NOTICE.md and LICENSE.md. */
public final class FunctionRoot extends GuestRoot {
    private static final int[] NO_SCALAR_VOID_INPUTS = new int[0];
    private static final MethodHandle ARGUMENT_COUNT = argumentCountIntrinsic();
    private static MethodHandle argumentCountIntrinsic() {
        try {
            return MethodHandles.publicLookup().findStatic(com.oracle.truffle.api.nodes.RootNode.class,
                "assumeArgumentCount", MethodType.methodType(Object[].class, Object[].class, int.class));
        } catch (NoSuchMethodException stockApi) { return null; }
        catch (IllegalAccessException failure) { throw new ExceptionInInitializerError(failure); }
    }
    @CompilationFinal private int ordinaryArgumentCount = -1;
    @Override protected void prepareForCall() {
        super.prepareForCall();
        var proofs = getInputProofs();
        if (proofs != null)
            ordinaryArgumentCount = getEntryArgumentOffset() + ArgumentLayout.width(getInputLayout(), proofs.size());
    }
    /** PAP and overapplication handling precede entry; the complete ABI fixes this length. */
    private Object[] ordinaryArguments(Object[] arguments) {
        if (ordinaryArgumentCount < 0 || ARGUMENT_COUNT == null) return arguments;
        try { return (Object[]) ARGUMENT_COUNT.invokeExact(arguments, ordinaryArgumentCount); }
        catch (RuntimeException | Error failure) { throw failure; }
        catch (Throwable impossible) { throw CompilerDirectives.shouldNotReachHere(impossible); }
    }
    private final String label;
    private final CaptureLayout captureLayout;
    @CompilationFinal(dimensions = 1) private final int[] environmentSlots;
    @CompilationFinal(dimensions = 1) private final int[] argumentSlots;
    @CompilationFinal(dimensions = 1) private final int[] argumentIndices;
    @CompilationFinal(dimensions = 1) private int[] scalarVoidIndices = NO_SCALAR_VOID_INPUTS;
    @CompilationFinal(dimensions = 1) private final CoreRepresentation[] argumentProofs;
    @CompilationFinal(dimensions = 2) private final int[][] environmentVectorSlots;
    private final Metrics metrics;
    @CompilationFinal private int programSlot = -1;
    @CompilationFinal private Object programCodeIdentity;
    @CompilationFinal private boolean preparedForAOT;
    private final CoreSourceLocation coreSourceLocation;
    private final HandoffEntry handoff;
    private final boolean enableAsync;
    private final boolean enableDelimited;
    private final FunctionRootRole role;
    private final boolean stackCapture;
    private final boolean capturesContinuations;
    @CompilationFinal private boolean copyInitialFrame;
    @CompilationFinal(dimensions = 1) private int[] initialClears = new int[0];
    private final boolean deferredBudget;
    private final boolean budgetBoundary;
    private volatile long budgetGeneration;
    @CompilationFinal(dimensions = 1) private final Class<?>[] argumentReferences;
    @CompilationFinal(dimensions = 1) private final boolean[] strictArguments;
    @CompilationFinal(dimensions = 1) private final int[] strictSlots;
    @Child private Force entryForce;
    @CompilationFinal private boolean hasSelfTail;
    private final BranchProfile tailCallProfile = BranchProfile.create();
    @Child private LoopNode loop;
    @Child private HandoffCaller delimitedHandoff;
    @Child private volatile DirectCallNode recoveredEntry;
    @CompilationFinal private volatile boolean coldEntry;
    @CompilationFinal private boolean recoveryBoundary;

    @Override public Node copy() {
        FunctionRoot copy = (FunctionRoot) super.copy();
        copy.recoveredEntry = null;
        copy.coldEntry = false;
        return copy;
    }

    @TruffleBoundary private DirectCallNode recoverEntry() {
        var service = thc.Language.currentState(this).getGraphRecovery();
        var claim = service.claim(this);
        if (claim == null) return recoveredEntry;
        try {
            FunctionRoot replacement = copyForRecovery();
            boolean reduced = claim.target().getRootNode() instanceof AstSameFrameArm.ArmRoot side
                    ? side.extractInCopy(replacement)
                    : deferredBudget ? AstDeferredArm.extractFresh(replacement) : AstSameFrameArm.extract(replacement);
            if (!reduced) {
                atomic(() -> {
                    if (recoveredEntry == null && service.publishable(this, claim)) {
                        coldEntry = true;
                        reportReplace(this, this, "cold trampoline after compilation failure");
                    }
                    return null;
                });
                return null;
            }
            replacement.budgetGeneration = budgetGeneration + 1;
            replacement.recoveryBoundary = true;
            DirectCallNode prepared = DirectCallNode.create(replacement.getCallTarget());
            return atomic(() -> {
                if (recoveredEntry == null && service.publishable(this, claim)) {
                    recoveredEntry = insert(prepared);
                    reportReplace(this, this, "smaller fresh entry after compilation failure");
                }
                return recoveredEntry;
            });
        } finally {
            // A cold trampoline keeps this source generation, including pending
            // sibling failures. Only a published replacement retires all receipts.
            service.complete(this, claim, recoveredEntry != null);
        }
    }

    final FunctionRoot copyForRecovery() {
        FunctionRoot replacement = NodeUtil.cloneNode(this);
        freshLoops(replacement);
        AstSameFrameArm.restoreCopiedExtractions(replacement);
        return replacement;
    }

    private static void freshLoops(Node root) {
        for (Node child : root.getChildren()) freshLoops(child);
        if (root instanceof LoopNode loop) {
            Node body = NodeUtil.cloneNode((Node) loop.getRepeatingNode());
            loop.replace(Truffle.getRuntime().createLoopNode((RepeatingNode) body));
        }
    }

    public FunctionRoot(TruffleLanguage<?> language, FrameDescriptor descriptor, String label,
                        CaptureLayout captureLayout, int[] environmentSlots, int[] argumentSlots,
                        int[] argumentIndices, Expr body, Metrics metrics) {
        this(language, descriptor, label, captureLayout, environmentSlots, argumentSlots, argumentIndices,
            body, metrics, new CoreRepresentation[0], body.getRepresentation(), body.getCoreSourceLocation(),
            new boolean[0], null, null, new int[0], null, false, new int[0][], false, FunctionRootRole.FUNCTION, false);
    }
    public FunctionRoot(TruffleLanguage<?> language, FrameDescriptor descriptor, String label,
                        CaptureLayout captureLayout, int[] environmentSlots, int[] argumentSlots,
                        int[] argumentIndices, Expr body, Metrics metrics, CoreRepresentation[] argumentProofs,
                        CoreRepresentation resultProof, CoreSourceLocation coreSourceLocation, boolean[] entryStrict,
                        HandoffEntry handoff, TupleShape tuple, int[] tupleSlots, ArgumentLayout inputLayout,
                        boolean enableAsync, int[][] environmentVectorSlots, boolean enableDelimited,
                        FunctionRootRole role, boolean stackCapture) {
        this(language, descriptor, label, captureLayout, environmentSlots, argumentSlots, argumentIndices,
            body, metrics, argumentProofs, resultProof, coreSourceLocation, entryStrict, handoff, tuple,
            tupleSlots, inputLayout, enableAsync, environmentVectorSlots, enableDelimited, role, stackCapture,
            false, false);
    }
    public FunctionRoot(TruffleLanguage<?> language, FrameDescriptor descriptor, String label,
                        CaptureLayout captureLayout, int[] environmentSlots, int[] argumentSlots,
                        int[] argumentIndices, Expr body, Metrics metrics, CoreRepresentation[] argumentProofs,
                        CoreRepresentation resultProof, CoreSourceLocation coreSourceLocation, boolean[] entryStrict,
                        HandoffEntry handoff, TupleShape tuple, int[] tupleSlots, ArgumentLayout inputLayout,
                        boolean enableAsync, int[][] environmentVectorSlots, boolean enableDelimited,
                        FunctionRootRole role, boolean stackCapture, boolean deferredBudget, boolean budgetBoundary) {
        this(language, descriptor, label, captureLayout, environmentSlots, argumentSlots, argumentIndices,
            body, metrics, argumentProofs, resultProof, coreSourceLocation, entryStrict, handoff, tuple, tupleSlots,
            inputLayout, enableAsync, environmentVectorSlots, enableDelimited, role, stackCapture, deferredBudget,
            budgetBoundary, false);
    }
    private FunctionRoot(TruffleLanguage<?> language, FrameDescriptor descriptor, String label,
                        CaptureLayout captureLayout, int[] environmentSlots, int[] argumentSlots,
                        int[] argumentIndices, Expr body, Metrics metrics, CoreRepresentation[] argumentProofs,
                        CoreRepresentation resultProof, CoreSourceLocation coreSourceLocation, boolean[] entryStrict,
                        HandoffEntry handoff, TupleShape tuple, int[] tupleSlots, ArgumentLayout inputLayout,
                        boolean enableAsync, int[][] environmentVectorSlots, boolean enableDelimited,
                        FunctionRootRole role, boolean stackCapture, boolean deferredBudget, boolean budgetBoundary,
                        boolean rawResult) {
        super(language, descriptor);
        this.label = label; this.captureLayout = captureLayout; this.environmentSlots = environmentSlots;
        this.argumentSlots = argumentSlots; this.argumentIndices = argumentIndices; this.argumentProofs = argumentProofs;
        this.metrics = metrics; this.coreSourceLocation = coreSourceLocation; this.handoff = handoff;
        this.enableAsync = enableAsync; this.environmentVectorSlots = environmentVectorSlots;
        this.enableDelimited = enableDelimited; this.role = role; this.stackCapture = stackCapture;
        capturesContinuations = enableAsync || stackCapture;
        this.deferredBudget = deferredBudget;
        this.budgetBoundary = budgetBoundary;
        configureEntry(entryStrict, captureLayout != null);
        configureInput(inputLayout);
        configureTupleResult(tuple);
        configureScalarResult(body.getRepresentation().refine(resultProof));
        // Establish mandatory carriers before publishing the root, never on its first compiled call.
        if (captureLayout != null) for (int i = 0; i < environmentSlots.length; i++) {
            FrameSlotKind kind = captureLayout.fixedFrameKind(i);
            if (kind == FrameSlotKind.Illegal) continue;
            int[] vectorSlots = i < environmentVectorSlots.length ? environmentVectorSlots[i] : null;
            initialize(descriptor, vectorSlots == null ? environmentSlots[i] : vectorSlots[0], kind);
        }
        if (inputLayout != null) for (int i = 0; i < argumentSlots.length; i++) {
            if (capturesContinuations && contains(getStrictArgumentPositions(), argumentIndices[i] + getEntryArgumentOffset())) {
                initialize(descriptor, argumentSlots[i], FrameSlotKind.Object);
                continue;
            }
            initialize(descriptor, argumentSlots[i], FrameLayout.carrierKind(inputLayout.getPhysicalProofs()[argumentIndices[i]]));
        }
        if (tuple != null) for (int i = 0; i < tupleSlots.length; i++)
            initialize(descriptor, tupleSlots[i], tuple.getLayout(), i);
        for (int i = 0; i < argumentSlots.length; i++) {
            if (capturesContinuations && contains(getStrictArgumentPositions(), argumentIndices[i] + getEntryArgumentOffset())) {
                // Deferred strict ingress may still be a Thunk, even though its body proof is evaluated.
                initialize(descriptor, argumentSlots[i], FrameSlotKind.Object);
                continue;
            }
            if (i >= argumentProofs.length) {
                initialize(descriptor, argumentSlots[i], FrameSlotKind.Object);
                continue;
            }
            CoreRepresentation proof = argumentProofs[i];
            FrameSlotKind kind;
            if (proof.referenceCarrier() != null) kind = FrameSlotKind.Object;
            else if (proof.isInt()) kind = FrameSlotKind.Int;
            else if (proof.isLong()) kind = FrameSlotKind.Long;
            else if (proof.isFloat()) kind = FrameSlotKind.Float;
            else if (proof.isDouble()) kind = FrameSlotKind.Double;
            else kind = FrameSlotKind.Object;
            initialize(descriptor, argumentSlots[i], kind);
        }
        if (handoff != null) {
            for (int i = 0; i < handoff.getSnapshotSlots().length; i++)
                initialize(descriptor, handoff.getSnapshotSlots()[i], handoff.getArguments().isInt(i) ?
                    FrameSlotKind.Int : handoff.getArguments().isLong(i) ? FrameSlotKind.Long : FrameSlotKind.Object);
            initialize(descriptor, handoff.getDestinationSlot(), FrameSlotKind.Long);
        }
        argumentReferences = new Class<?>[argumentProofs.length];
        for (int i = 0; i < argumentProofs.length; i++) argumentReferences[i] = argumentProofs[i].referenceCarrier();
        strictArguments = new boolean[argumentSlots.length];
        int[] strict = new int[argumentSlots.length];
        int count = 0;
        for (int i = 0; i < argumentSlots.length; i++) {
            strictArguments[i] = capturesContinuations && contains(getStrictArgumentPositions(), argumentIndices[i] + getEntryArgumentOffset());
            if (strictArguments[i]) strict[count++] = i;
        }
        strictSlots = Arrays.copyOf(strict, count);
        entryForce = new Force(metrics, enableAsync);
        loop = Truffle.getRuntime().createLoopNode(new SelfRepeater(new FunctionBody(body, metrics, resultProof, tuple, tupleSlots, role == FunctionRootRole.INITIALIZER, rawResult), metrics));
    }
    /** A real internal call boundary with its own saved frame, but no extra result demand or tail owner. */
    static FunctionRoot application(TruffleLanguage<?> language, String label, Expr body, Metrics metrics,
                                    boolean async, boolean delimited) {
        return new FunctionRoot(language, new FrameLayout().build(), label, null, new int[0],
            new int[]{FrameLayout.TAIL_FUNCTION, FrameLayout.TAIL_ARGUMENTS}, new int[]{0, 1}, body, metrics,
            new CoreRepresentation[]{CoreRepresentation.UNKNOWN, CoreRepresentation.UNKNOWN},
            CoreRepresentation.UNKNOWN, null, new boolean[2], null, null, new int[0], null,
            async, new int[0][], delimited, FunctionRootRole.PASS_THROUGH, true, false, true, true);
    }
    /** Explicit WHNF demand owns one input and a saved frame, with no caller-local writeback. */
    static FunctionRoot demand(TruffleLanguage<?> language, Metrics metrics, boolean async, boolean delimited) {
        Expr input = new Expr() {
            @Override public Object execute(VirtualFrame frame) { return frame.getObject(FrameLayout.TAIL_FUNCTION); }
        };
        return new FunctionRoot(language, new FrameLayout().build(), "nonlocal demand", null, new int[0],
            new int[]{FrameLayout.TAIL_FUNCTION}, new int[]{0}, new Evaluate(input, metrics), metrics,
            new CoreRepresentation[]{CoreRepresentation.UNKNOWN}, CoreRepresentation.UNKNOWN, null,
            new boolean[1], null, null, new int[0], null, async, new int[0][], delimited,
            FunctionRootRole.PASS_THROUGH, true, false, true, true);
    }
    private static boolean contains(int[] values, int value) {
        for (int element : values) if (element == value) return true;
        return false;
    }
    private static void initialize(FrameDescriptor descriptor, int slot, FrameSlotKind kind) {
        if (descriptor.getSlotKind(slot) == FrameSlotKind.Illegal) descriptor.setSlotKind(slot, kind);
    }
    private static void initialize(FrameDescriptor descriptor, int slot, HandoffLayout layout, int field) {
        initialize(descriptor, slot, layout.isInt(field) ? FrameSlotKind.Int :
            layout.isLong(field) ? FrameSlotKind.Long : layout.isFloat(field) ? FrameSlotKind.Float :
            layout.isDouble(field) ? FrameSlotKind.Double : FrameSlotKind.Object);
    }
    public HandoffEntry getHandoff() { return handoff; }
    /** Set by lowering before publication, only for bodies without exposed frame aliases. */
    void configureInitialFrameCopy(boolean enabled) { copyInitialFrame = enabled; }
    /** Freeze lowering's dead-slot list before publication; saved frames never reinitialize it. */
    void configureInitialClears(int[] slots) {
        for (int slot : slots)
            if (slot <= FrameLayout.TAIL_ARGUMENTS || slot >= getFrameDescriptor().getNumberOfSlots() ||
                    contains(argumentSlots, slot) || contains(environmentSlots, slot))
                throw new IllegalArgumentException("Initial scratch slot aliases live input");
        initialClears = slots.clone();
    }
    @ExplodeLoop private void clearInitialLocals(VirtualFrame frame) {
        for (int slot : initialClears) frame.clear(slot);
    }
    void configureProgramSlot(int slot, Object codeIdentity) {
        if (slot < 0 || programSlot >= 0 || metrics != null) throw new IllegalStateException("Invalid reusable root configuration");
        programSlot = slot;
        programCodeIdentity = java.util.Objects.requireNonNull(codeIdentity);
        // Reusable roots cannot discover self recursion by executing a guest body.
        // Prepare the existing loop before publication, leaving ordinary JIT
        // roots' observed self-tail activation unchanged.
        hasSelfTail = true;
        shareCompilationOwnership();
    }
    Metrics invocationMetrics(VirtualFrame frame) {
        return metrics != null ? metrics : Program.instance(frame, programSlot).instanceMetrics();
    }
    void requireContinuationOwner(VirtualFrame frame) {
        if (programSlot < 0) return;
        Program program = Program.instance(frame, programSlot);
        if (!program.usesCode(programCodeIdentity) || !program.belongsToCurrentContext(this))
            throw fault("Prepared continuation belongs to another program context");
    }
    boolean isPreparedForAOT() { return preparedForAOT; }
    private static void prepareAotDispatch(Node node) {
        if (node instanceof AstTypedApplication application) application.prepareForAOT();
        else if (node instanceof TupleApplication application) application.prepareForAOT();
        else if (node instanceof InputDispatch input) input.prepareForAOT();
        else if (node instanceof TupleDispatch tuple) tuple.prepareForAOT();
        for (Node child : node.getChildren()) prepareAotDispatch(child);
    }
    @Override protected ExecutionSignature prepareForAOT() {
        // The existing loop handles self transfers from the first guest entry.
        // Cold compilation must not discover recursion by retiring that entry.
        hasSelfTail = true;
        prepareAotDispatch(this);
        // Frame carriers were established structurally before target publication;
        // no context lookup, guest execution or observed-profile seeding is needed.
        // An outlined reusable arm also returns an internal TailCall for its
        // owning caller to rethrow. This physical root result is not the guest ABI.
        Class<?> resultClass = capturesContinuations || role == FunctionRootRole.PASS_THROUGH ? null : numericClass(getScalarResultProof());
        if (handoff != null) {
            // One physical root accepts an ordinary packet or an empty packet
            // carrying a pending loan, and may return a private completion token.
            // Each ingress retains its own exact arity, layout and loan checks.
            preparedForAOT = true;
            return ExecutionSignature.create(null, null);
        }
        if (getTypedInput() != null) {
            // Narrow scalars already use the typed packet calling convention.
            // Its generated storage class varies; retain exact layout/owner checks.
            preparedForAOT = true;
            return ExecutionSignature.create(resultClass, new Class<?>[]{null});
        }
        List<CoreRepresentation> inputs = getInputProofs();
        if (inputs == null) inputs = Arrays.asList(argumentProofs);
        ArgumentLayout input = getInputLayout();
        Class<?>[] signature = new Class<?>[getEntryArgumentOffset() + ArgumentLayout.width(input, inputs.size())];
        signature[0] = Long.class;
        // ExecutionSignature requires an exact runtime class, not a superclass.
        // StaticShape selects the concrete CapturedFrame subclass; keep its
        // existing authenticated layout/owner checks, without inventing a class.
        if (getEntryArgumentOffset() == 2) signature[1] = null;
        for (int i = 0; i < inputs.size(); i++) {
            CoreRepresentation proof = inputs.get(i);
            if (proof.isEmptyTuple()) continue;
            Class<?> carrier = proof.getKind() == CoreKind.VOID ? Unit.class : numericClass(proof);
            if (capturesContinuations && contains(getStrictArgumentPositions(), getEntryArgumentOffset() + i))
                carrier = null; // Deferred strict ingress may still carry a lazy thunk.
            // Lifted data/functions may arrive as a lazy thunk or a value.
            // Keep the existing constructor/captured-program owner checks;
            // these alternatives do not have one exact signature class.
            signature[getEntryArgumentOffset() + ArgumentLayout.offset(input, i)] = carrier;
        }
        preparedForAOT = true;
        return ExecutionSignature.create(resultClass, signature);
    }
    private static Class<?> numericClass(CoreRepresentation proof) {
        if (proof.isInt()) return Integer.class;
        if (proof.isLong()) return Long.class;
        if (proof.isFloat()) return Float.class;
        if (proof.isDouble()) return Double.class;
        return null;
    }
    public boolean getEnableAsync() { return enableAsync; }
    @Override public boolean getAsynchronousExceptions() { return enableAsync; }
    public boolean getEnableDelimited() { return enableDelimited; }
    public boolean getCapturesContinuations() { return capturesContinuations; }
    /** Shared AOT code must observe admission without retiring its installed target. */
    public boolean usesRuntimeAsyncAdmission() { return programSlot >= 0; }
    public boolean getStackCapture() { return stackCapture; }
    public FunctionRootRole getRole() { return role; }
    public HandoffEntry getHandoff$org_intelligence_thc() { return handoff; }
    public boolean getEnableAsync$org_intelligence_thc() { return enableAsync; }
    public boolean getEnableDelimited$org_intelligence_thc() { return enableDelimited; }
    public boolean getCapturesContinuations$org_intelligence_thc() { return capturesContinuations; }
    public FunctionRootRole getRole$org_intelligence_thc() { return role; }

    // Optional overlay declarations; stock Truffle uses its ordinary policies.
    public boolean requiresUnprofiledReturn() { return capturesContinuations || enableDelimited; }
    protected boolean requiresMaterializableFrame() { return capturesContinuations || enableDelimited; }
    @Override public long bloom(VirtualFrame frame) { return frame.getLong(FrameLayout.BLOOM_FILTER); }
    /** A pass-through side root has no catcher of its own. */
    public long entryBloom(long inherited) {
        return stackCapture && role == FunctionRootRole.PASS_THROUGH ? inherited : inherited | mask;
    }
    /** Identity return: no return loan, tuple copy, lazy force or external delivery. */
    public boolean isTailSpillIdentityRoot() {
        return stackCapture && !enableAsync && !enableDelimited && handoff == null && getTypedInput() == null &&
            getTupleResult() == null && AstTailResult.supports(getScalarResultProof());
    }
    /** The driver runs the saved suffix, not the original body. */
    public Object drainTailChild(VirtualFrame frame, SavedGuestContinuation saved) {
        return entryForce.drainStack(saved, saved.getSourceRoot() instanceof GuestRoot root ? root.getTupleResult() : null, false, true, invocationMetrics(frame));
    }
    public Object restartTailAnchor(VirtualFrame frame, TailCall transfer) {
        if (role != FunctionRootRole.FUNCTION || !isSelf(transfer.getTarget())) throw new IllegalStateException("Check failed.");
        restoreTail(frame, transfer);
        if (invocationMetrics(frame).getEnabled()) invocationMetrics(frame).incrementSelfTailReentries();
        return executeBody(frame);
    }
    /** Scalar State# remains an ABI token even though its local value is erased. */
    public void configureScalarVoidInputs(List<CoreRepresentation> proofs) {
        int count = 0;
        for (var proof : proofs) if (proof.getKind() == CoreKind.VOID) count++;
        if (count == 0) return;
        int[] indices = new int[count];
        int index = 0;
        for (int i = 0; i < proofs.size(); i++)
            if (proofs.get(i).getKind() == CoreKind.VOID)
                indices[index++] = ArgumentLayout.offset(getInputLayout(), i);
        scalarVoidIndices = indices;
    }
    @ExplodeLoop public void buildFrame(Object[] arguments, VirtualFrame frame) {
        arguments = ordinaryArguments(arguments);
        int offset = captureLayout == null ? 1 : 2;
        for (int index : scalarVoidIndices) TupleResults.requireVoidCarrier(arguments[index + offset]);
        for (int i = 0; i < argumentSlots.length; i++) {
            Object value = arguments[argumentIndices[i] + offset];
            Class<?> reference = i < argumentReferences.length ? argumentReferences[i] : null;
            if (strictArguments[i]) FrameAccess.INSTANCE.write(frame, argumentSlots[i], value);
            else if (reference != null) FrameAccess.INSTANCE.write(frame, argumentSlots[i], requireReferenceCarrier(value, reference));
            else if (i < argumentProofs.length && argumentProofs[i].isInt()) {
                if (!(value instanceof Integer integer)) throw fault("Expected primitive Int argument");
                FrameAccess.INSTANCE.writeInt(frame, argumentSlots[i], integer);
            } else if (i < argumentProofs.length && argumentProofs[i].isLong()) {
                if (!(value instanceof Long number)) throw fault("Expected primitive Long argument");
                FrameAccess.INSTANCE.writeLong(frame, argumentSlots[i], number);
            } else if (i < argumentProofs.length && argumentProofs[i].isFloat()) {
                if (!(value instanceof Float number)) throw fault("Expected primitive Float argument");
                FrameAccess.INSTANCE.writeFloat(frame, argumentSlots[i], number);
            } else if (i < argumentProofs.length && argumentProofs[i].isDouble()) {
                if (!(value instanceof Double number)) throw fault("Expected primitive Double argument");
                FrameAccess.INSTANCE.writeDouble(frame, argumentSlots[i], number);
            } else FrameAccess.INSTANCE.write(frame, argumentSlots[i], value);
        }
        if (captureLayout != null) {
            if (!(arguments[1] instanceof CapturedFrame environment)) throw fault("Invalid captured frame");
            restoreCaptured(frame, environment);
        }
    }
    @ExplodeLoop private void restoreCaptured(VirtualFrame frame, CapturedFrame environment) {
        CaptureLayout layout = captureLayout;
        if (layout == null) throw fault("Missing capture layout");
        if (programSlot >= 0) {
            if (environment.getLayout() != layout || !(environment.getProgram() instanceof Program program) ||
                    !program.usesCode(programCodeIdentity) || !program.belongsToCurrentContext(this))
                throw fault("Invalid explicit program environment");
            frame.setObject(programSlot, program);
        }
        for (int i = 0; i < environmentSlots.length; i++) {
            int[] lanes = i < environmentVectorSlots.length ? environmentVectorSlots[i] : null;
            if (lanes == null) layout.restore(environment, i, frame, environmentSlots[i]);
            else layout.restoreVector(environment, i, frame, lanes, 0);
        }
    }
    public int handoffDestination(VirtualFrame frame) { return handoff == null ? -1 : handoff.destination(frame); }
    @ExplodeLoop public void forceEntry(VirtualFrame frame) {
        for (int index = 0; index < strictSlots.length; index++) {
            int argument = strictSlots[index];
            int slot = argumentSlots[argument];
            Object answer;
            try { answer = AstControl.force(frame, this, entryForce, FrameAccess.INSTANCE.read(frame, slot)); }
            catch (AstCapture cut) {
                throw cut.append(new AstResumeStep() {
                    @Override public Object resume(VirtualFrame frame, Object input) {
                        writeStrict(frame, argument, input);
                        return executeCapturableBody(frame);
                    }
                });
            }
            writeStrict(frame, argument, answer);
        }
    }
    private void writeStrict(VirtualFrame frame, int argument, Object value) {
        Class<?> reference = argument < argumentReferences.length ? argumentReferences[argument] : null;
        FrameAccess.INSTANCE.write(frame, argumentSlots[argument], reference == null ? value : requireReferenceCarrier(value, reference));
    }
    private static final class ResumeBody implements AstResumeStep {
        private final FunctionRoot root;
        ResumeBody(FunctionRoot root) { this.root = root; }
        @Override public Object resume(VirtualFrame frame, Object input) {
            if (input != thc.runtime.Unit.INSTANCE) throw fault("Invalid AST root poll resume value");
            return root.executeBody(frame);
        }
    }
    public void pollBeforeBody(Node node) {
        if (!enableAsync) return;
        boolean enteredCompiled = CompilerDirectives.inCompiledCode();
        AsyncRequest request = GuestThreads.pollCurrent(node, false);
        if (request == null) return;
        request.compiledCapture = enteredCompiled;
        throw new AstCapture(request, SynchronousMasking.current(node)).append(new ResumeBody(this));
    }
    public void restoreTail(VirtualFrame frame, TailCall transfer) {
        HandoffStorage input = transfer.getInput();
        if (input != null) restoreTypedInput(frame, input, false);
        else {
            if (getTypedInput() != null) throw fault("Typed input target received a scalar packet");
            buildFrame(transfer.getArgs(), frame);
        }
    }
    @ExplodeLoop public void transferTypedSelf(VirtualFrame frame, Closure function, AstInputSource source, Node node) {
        TypedInputLayout entry = getTypedInput();
        if (entry == null) throw fault("Target does not support typed tuple inputs");
        entry.validateSelfSource(source, frame, node);
        for (int index : scalarVoidIndices) TupleResults.requireVoidCarrier(source.reference(frame, node, null, index));
        for (int i = 0; i < argumentSlots.length; i++) {
            int from = argumentIndices[i], to = argumentSlots[i];
            if (entry.getPacket().isInt(entry.getHeader() + from)) FrameAccess.INSTANCE.writeInt(frame, to, source.readInt(frame, node, null, from));
            else if (entry.getPacket().isLong(entry.getHeader() + from)) FrameAccess.INSTANCE.writeLong(frame, to, source.readLong(frame, node, null, from));
            else if (entry.getPacket().isFloat(entry.getHeader() + from)) FrameAccess.INSTANCE.writeFloat(frame, to, source.readFloat(frame, node, null, from));
            else if (entry.getPacket().isDouble(entry.getHeader() + from)) FrameAccess.INSTANCE.writeDouble(frame, to, source.readDouble(frame, node, null, from));
            else {
                Object value = source.reference(frame, node, null, from);
                Class<?> expected = i < argumentReferences.length ? argumentReferences[i] : null;
                TypedInputs.writeInputReference(frame, to, expected == null ? value : requireReferenceCarrier(value, expected));
            }
        }
        if (captureLayout != null) {
            if (function.environment == null) throw fault("Invalid captured frame");
            restoreCaptured(frame, function.environment);
        }
    }
    @ExplodeLoop private void restoreTypedInput(VirtualFrame frame, HandoffStorage input, boolean initial) {
        TypedInputLayout entry = getTypedInput();
        if (entry == null) throw fault("Target does not support typed tuple inputs");
        try {
            if (initial) entry.validate(input); else entry.validateTail(input);
            for (int index : scalarVoidIndices) TupleResults.requireVoidCarrier(entry.getPacket().getObject(input, index + entry.getHeader()));
            if (initial) frame.setLong(FrameLayout.BLOOM_FILTER, entryBloom(entry.getPacket().getLong(input, 0)));
            for (int i = 0; i < argumentSlots.length; i++) {
                int from = argumentIndices[i] + entry.getHeader(), to = argumentSlots[i];
                if (entry.getPacket().isInt(from)) FrameAccess.INSTANCE.writeInt(frame, to, entry.getPacket().getInt(input, from));
                else if (entry.getPacket().isLong(from)) FrameAccess.INSTANCE.writeLong(frame, to, entry.getPacket().getLong(input, from));
                else if (entry.getPacket().isFloat(from)) FrameAccess.INSTANCE.writeFloat(frame, to, entry.getPacket().getFloat(input, from));
                else if (entry.getPacket().isDouble(from)) FrameAccess.INSTANCE.writeDouble(frame, to, entry.getPacket().getDouble(input, from));
                else {
                    Object value = entry.getPacket().getObject(input, from);
                    Class<?> expected = i < argumentReferences.length ? argumentReferences[i] : null;
                    TypedInputs.writeInputReference(frame, to, expected == null || strictArguments[i] ? value : requireReferenceCarrier(value, expected));
                }
            }
            if (captureLayout != null) {
                if (!(entry.getPacket().getObject(input, 1) instanceof CapturedFrame environment)) throw fault("Invalid captured frame");
                restoreCaptured(frame, environment);
            }
        } finally { entry.releaseChecked(input); }
    }
    @ExplodeLoop public void restoreHandoff(VirtualFrame frame, HandoffStorage input, boolean initial) {
        HandoffEntry entry = handoff;
        if (entry == null) throw fault("Target does not support the handoff ABI");
        if (input.getLayout() != entry.getArguments() || !input.getLive$org_intelligence_thc()) throw new IllegalStateException("Check failed.");
        if (!initial && entry.destination(frame) < 0) throw new IllegalStateException("Check failed.");
        try {
            if (initial) {
                entry.snapshot(frame, input);
                frame.setLong(entry.getDestinationSlot(), 0L);
                frame.setLong(FrameLayout.BLOOM_FILTER, entryBloom(entry.getArguments().getLong(input, 0)));
            }
            int offset = captureLayout == null ? 1 : 2;
            for (int index : scalarVoidIndices) TupleResults.requireVoidCarrier(entry.getArguments().getObject(input, index + offset));
            for (int i = 0; i < argumentSlots.length; i++) {
                int position = argumentIndices[i] + offset;
                if (entry.getArguments().isInt(position)) FrameAccess.INSTANCE.writeInt(frame, argumentSlots[i], entry.getArguments().getInt(input, position));
                else if (entry.getArguments().isLong(position)) FrameAccess.INSTANCE.writeLong(frame, argumentSlots[i], entry.getArguments().getLong(input, position));
                else {
                    Object value = entry.getArguments().getObject(input, position);
                    Class<?> reference = i < argumentReferences.length ? argumentReferences[i] : null;
                    FrameAccess.INSTANCE.write(frame, argumentSlots[i], reference == null || strictArguments[i] ? value : requireReferenceCarrier(value, reference));
                }
            }
            if (captureLayout != null) {
                if (!(entry.getArguments().getObject(input, 1) instanceof CapturedFrame environment)) throw fault("Invalid captured frame");
                restoreCaptured(frame, environment);
            }
        } finally { entry.state().getArguments().release(input, entry.getArguments()); }
    }
    @Override public Object execute(VirtualFrame frame) {
        DirectCallNode redirect = recoveredEntry;
        if (redirect == null && !noGraphFailure.isValid() && graphFailure.get() != null) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            redirect = recoverEntry();
        }
        if (redirect != null) return Calls.direct(redirect, frame.getArguments());
        if (coldEntry) return executeCold(frame.getArguments());
        return executeEntry(frame);
    }
    /** Fresh ingress has not consumed a typed loan or initialized any guest locals yet. */
    @TruffleBoundary private Object executeCold(Object[] arguments) {
        return executeEntry(Truffle.getRuntime().createVirtualFrame(arguments, getFrameDescriptor()));
    }
    private Object executeEntry(VirtualFrame frame) {
        // A normal Core body that cannot enter another guest activation cannot
        // originate a stack spill. Keep ingress and polls, including their
        // capture handler, but do not interpret an ordinary return as control.
        boolean needsCallState = strictSlots.length != 0 ||
            !(loop.getRepeatingNode() instanceof SelfRepeater repeating) || repeating.needsCallState();
        if (!capturesContinuations || !needsCallState)
            return executeInitial(frame, false, needsCallState);
        AstStackScope stack = astStackScope(this);
        boolean driver = !stack.getDriving();
        if (driver) stack.setDriving(true);
        try {
            stack.setDepth(stack.getDepth() + 1);
            Object result;
            try { result = executeInitial(frame, stack.getDepth() >= AstStackScope.MAX_DEPTH, true); }
            finally { stack.setDepth(stack.getDepth() - 1); }
            if (driver) {
                SavedGuestContinuation saved = result instanceof AstTailYield tail ? tail.getContinuation() : savedGuestContinuation(result);
                if (saved != null) {
                    CompilerDirectives.transferToInterpreter();
                    if (saved.stackSpill() && saved.asyncRequest() == null) return entryForce.drainStack(saved,
                        saved.getSourceRoot() instanceof GuestRoot root ? root.getTupleResult() : null, false, false, invocationMetrics(frame));
                }
            }
            return result;
        } finally { if (driver) stack.setDriving(false); }
    }
    private Object executeInitial(VirtualFrame frame, boolean spill, boolean needsCallState) {
        boolean deferredBloom = false;
        clearInitialLocals(frame);
        if (metrics != null && metrics.getEnabled() && CompilerDirectives.inCompiledCode()) metrics.incrementCompiledEntries();
        HandoffEntry entry = handoff;
        TypedInputLayout typed = getTypedInput();
        if (typed != null) restoreTypedInput(frame, typed.take(frame.getArguments()), true);
        else if (entry != null && frame.getArguments().length == 0) {
            HandoffState state = entry.state();
            HandoffStorage input = state.getPending();
            if (input == null) throw fault("Missing typed argument loan");
            state.setPending(null);
            restoreHandoff(frame, input, true);
        } else {
            if (entry != null) entry.initializeOrdinary(frame);
            // Delimited roots retain ancestry eagerly for their independently saved frames.
            deferredBloom = !needsCallState && !enableDelimited;
            if (!deferredBloom) initializeBloom(frame);
            buildFrame(frame.getArguments(), frame);
        }
        if (metrics == null) {
            // Cached-code launchers fail closed on interpreter fallback. The check folds
            // away in compiled code and is not used by ordinary program instances.
            if (CompilerDirectives.inInterpreter() && Boolean.getBoolean("thc.requireCompiledCode"))
                throw fault("Cached guest entered the interpreter: " + label);
            Metrics invocation = invocationMetrics(frame);
            if (invocation.getEnabled() && CompilerDirectives.inCompiledCode()) invocation.incrementCompiledEntries();
        }
        if (spill) return captureStack(initialCaptureFrame(frame, deferredBloom));
        if (!capturesContinuations) return executeCapturableBody(frame);
        try { return executeCapturableBody(frame); }
        catch (AstCapture cut) { return finishCapture(cut, initialCaptureFrame(frame, deferredBloom)); }
    }
    private void initializeBloom(VirtualFrame frame) {
        if (!(ordinaryArguments(frame.getArguments())[0] instanceof Long inherited)) throw fault("Invalid bloom argument");
        frame.setLong(FrameLayout.BLOOM_FILTER, entryBloom(inherited));
    }
    private MaterializedFrame initialCaptureFrame(VirtualFrame frame, boolean deferredBloom) {
        // Snapshot copying belongs to the cold capture path, outside partial evaluation.
        // The saved body can be replaced before resumption and then need ancestry.
        CompilerDirectives.transferToInterpreter();
        if (deferredBloom) initializeBloom(frame);
        MaterializedFrame saved = frame.materialize();
        return copyInitialFrame ? DelimitedContinuations.copyContinuationFrame(saved) : saved;
    }
    @TruffleBoundary public Object finishCapture(AstCapture cut, MaterializedFrame frame) {
        // The synchronous caller has left: a saved suffix returns an owned value,
        // never a token referring to that caller's transient scalar destination.
        if (handoff != null) handoff.initializeOrdinary(frame);
        if (cut.getYielded() instanceof AstPendingTail && isTailSpillIdentityRoot()) {
            AstPendingTail pending = cut.pendingTail();
            if (pending != null && role == FunctionRootRole.PASS_THROUGH) {
                AstStackScope stack = astStackScope(this);
                stack.setCompactedFrames(stack.getCompactedFrames() + 1);
                return new AstTailYield(pending.child, pending.target);
            }
            if (role == FunctionRootRole.FUNCTION) {
                SavedGuestContinuation child = pending != null ? pending.child : cut.freeze(this, frame);
                return new AstTailAnchor(this, frame, child, SynchronousMasking.current(this), StackAnnotations.current(this));
            }
        }
        return cut.freeze(this, frame);
    }
    /** A root-entry cut has no executed body or caller suffix to unwind here. */
    @TruffleBoundary private AstContinuation captureStack(MaterializedFrame frame) {
        if (handoff != null) handoff.initializeOrdinary(frame);
        AstStackScope stack = astStackScope(this);
        stack.setSpills(stack.getSpills() + 1);
        return new AstCapture(AstStackSpill.INSTANCE, SynchronousMasking.current(this)).append(new ResumeBody(this)).freeze(this, frame, true);
    }
    private Object executeCapturableBody(VirtualFrame frame) {
        if (!enableDelimited) return executeBody(frame);
        try { return executeBody(frame); }
        catch (DelimitedCut cut) { throw cut.append(frame, new DelimitedRootStep(this)); }
    }
    /** Called only after a saved self transfer unwound its lexical scopes. */
    Object resumeSelf(VirtualFrame frame) {
        if (role != FunctionRootRole.FUNCTION) throw fault("Saved self transfer has no owning function");
        Metrics invocation = invocationMetrics(frame);
        if (invocation.getEnabled()) invocation.incrementSelfTailReentries();
        if (handoff != null) handoff.initializeOrdinary(frame);
        return executeCapturableBody(frame);
    }
    public Object resumeDelimited(VirtualFrame frame, ControlFlowException transfer, DelimitedActionSite site) {
        if (role == FunctionRootRole.PASS_THROUGH) throw transfer;
        if (transfer instanceof TailCall tail && !isSelf(tail.getTarget())) return site.tail(frame, tail);
        if (transfer instanceof HandoffTailCall tail && !isSelf(tail.getTarget())) {
            HandoffEntry entry = handoff;
            if (entry == null) throw fault("Missing saved handoff entry");
            HandoffCaller caller = delimitedHandoff != null ? delimitedHandoff : installDelimitedHandoff(entry);
            return caller.trampoline$org_intelligence_thc(entry.state(), tail);
        }
        if (transfer instanceof TailCall tail) restoreTail(frame, tail);
        else if (transfer instanceof HandoffTailCall tail) restoreHandoff(frame, tail.getArguments(), false);
        else if (transfer != AstSelfCall.INSTANCE) throw transfer;
        if (invocationMetrics(frame).getEnabled()) invocationMetrics(frame).incrementSelfTailReentries();
        // A resumed suffix returns an owned value, not its caller's stale return loan.
        if (handoff != null) handoff.initializeOrdinary(frame);
        try { return executeBody(frame); }
        catch (DelimitedCut cut) { throw cut.append(frame, new DelimitedRootStep(this)); }
        catch (TailCall tail) { return site.tail(frame, tail); }
        catch (HandoffTailCall tail) {
            HandoffEntry entry = handoff;
            if (entry == null) throw fault("Missing saved handoff entry");
            return (delimitedHandoff != null ? delimitedHandoff : installDelimitedHandoff(entry)).trampoline$org_intelligence_thc(entry.state(), tail);
        }
    }
    @TruffleBoundary private HandoffCaller installDelimitedHandoff(HandoffEntry entry) {
        if (delimitedHandoff == null) delimitedHandoff = insert(new HandoffCaller(getCallTarget(), entry, metrics));
        return delimitedHandoff;
    }
    private Object executeBody(VirtualFrame frame) {
        if (role == FunctionRootRole.PASS_THROUGH) {
            if (!(loop.getRepeatingNode() instanceof SelfRepeater repeating)) throw fault("Invalid function body node");
            try { return repeating.once(frame); }
            catch (TailCall tail) {
                if (programSlot < 0) throw tail;
                // Keep the normal transfer inside the owning function's loop,
                // without training this outlined root's cold exception profile.
                return tail;
            }
        }
        if (hasSelfTail) return loop.execute(frame);
        if (!(loop.getRepeatingNode() instanceof SelfRepeater repeating)) throw fault("Invalid function self-loop node");
        try { return repeating.once(frame); }
        catch (AstSelfCall ignored) {
            tailCallProfile.enter();
            if (invocationMetrics(frame).getEnabled()) invocationMetrics(frame).incrementSelfTailReentries();
            CompilerDirectives.transferToInterpreterAndInvalidate();
            hasSelfTail = true;
            return loop.execute(frame);
        } catch (HandoffTailCall tail) {
            tailCallProfile.enter();
            if (!isSelf(tail.getTarget())) throw tail;
            if (invocationMetrics(frame).getEnabled()) invocationMetrics(frame).incrementSelfTailReentries();
            CompilerDirectives.transferToInterpreterAndInvalidate();
            hasSelfTail = true;
            restoreHandoff(frame, tail.getArguments(), false);
            return loop.execute(frame);
        } catch (TailCall tail) {
            tailCallProfile.enter();
            if (!isSelf(tail.getTarget())) throw tail;
            if (invocationMetrics(frame).getEnabled()) invocationMetrics(frame).incrementSelfTailReentries();
            CompilerDirectives.transferToInterpreterAndInvalidate();
            hasSelfTail = true;
            restoreTail(frame, tail);
            return loop.execute(frame);
        }
    }
    @Override public SourceSection getSourceSection() { return coreSourceLocation == null ? null : coreSourceLocation.getSection(); }
    public List<CoreSourceNote> getCoreSourceNotes() { return coreSourceLocation == null ? List.of() : coreSourceLocation.getNotes(); }
    @Override public String getName() { return label; }
    @Override public String toString() { return label; }
    public long getGraphBudgetGeneration() { return budgetGeneration; }
    /** Compatibility with older runtime overlays: a failed physical target is never rearmed. */
    public synchronized long prepareGraphBudgetRetry(long failedGeneration) {
        if (compilationFailureObserved) return failedGeneration;
        if (deferredBudget && failedGeneration == 0L && budgetGeneration == 0L && AstDeferredArm.extract(this))
            budgetGeneration = 1L;
        else if (failedGeneration == budgetGeneration && AstSameFrameArm.extract(this))
            budgetGeneration++;
        return budgetGeneration;
    }
    @Override protected boolean prepareForCompilation(boolean rootCompilation, int compilationTier, boolean lastTier) {
        return (!budgetBoundary && !recoveryBoundary || rootCompilation) && !coldEntry &&
                super.prepareForCompilation(rootCompilation, compilationTier, lastTier);
    }
    @Override public boolean isCloningAllowed() {
        return !budgetBoundary && !recoveryBoundary && !compilationFailureObserved;
    }
}
