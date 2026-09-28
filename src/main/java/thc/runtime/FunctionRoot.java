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
import com.oracle.truffle.api.nodes.LoopNode;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.profiles.BranchProfile;
import com.oracle.truffle.api.source.SourceSection;
import java.util.Arrays;
import java.util.List;
import static thc.runtime.RuntimeFault.fault;
import static thc.runtime.AstSelfCalls.requireReferenceCarrier;
import static thc.runtime.AstStacks.astStackScope;
import static thc.runtime.SavedGuestContinuations.savedGuestContinuation;

/* Indexed frames, selective captures, rooted application and self-tail frame
 * restoration follow Cadenza. See NOTICE.md and LICENSE.txt. */
public final class FunctionRoot extends GuestRoot {
    private final String label;
    private final CaptureLayout captureLayout;
    @CompilationFinal(dimensions = 1) private final int[] environmentSlots;
    @CompilationFinal(dimensions = 1) private final int[] argumentSlots;
    @CompilationFinal(dimensions = 1) private final int[] argumentIndices;
    @CompilationFinal(dimensions = 1) private final CoreRepresentation[] argumentProofs;
    @CompilationFinal(dimensions = 2) private final int[][] environmentVectorSlots;
    private final Metrics metrics;
    private final CoreSourceLocation coreSourceLocation;
    private final HandoffEntry handoff;
    private final boolean enableAsync;
    private final boolean enableDelimited;
    private final FunctionRootRole role;
    private final boolean stackCapture;
    private final boolean capturesContinuations;
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
        if (inputLayout != null) for (int i = 0; i < argumentSlots.length; i++) {
            if (capturesContinuations && contains(getStrictArgumentPositions(), argumentIndices[i] + getEntryArgumentOffset())) continue;
            initialize(descriptor, argumentSlots[i], FrameLayout.carrierKind(inputLayout.getPhysicalProofs()[argumentIndices[i]]));
        }
        if (tuple != null) for (int i = 0; i < tupleSlots.length; i++)
            initialize(descriptor, tupleSlots[i], tuple.getLayout(), i);
        for (int i = 0; i < argumentSlots.length; i++) {
            if (capturesContinuations && contains(getStrictArgumentPositions(), argumentIndices[i] + getEntryArgumentOffset())) continue;
            if (i >= argumentProofs.length) continue;
            CoreRepresentation proof = argumentProofs[i];
            FrameSlotKind kind;
            if (proof.referenceCarrier() != null) kind = FrameSlotKind.Object;
            else if (proof.isInt()) kind = FrameSlotKind.Int;
            else if (proof.isLong()) kind = FrameSlotKind.Long;
            else if (proof.isFloat()) kind = FrameSlotKind.Float;
            else if (proof.isDouble()) kind = FrameSlotKind.Double;
            else continue;
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
        loop = Truffle.getRuntime().createLoopNode(new SelfRepeater(new FunctionBody(body, metrics, resultProof, tuple, tupleSlots), metrics));
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
    public boolean getEnableAsync() { return enableAsync; }
    public boolean getEnableDelimited() { return enableDelimited; }
    public boolean getCapturesContinuations() { return capturesContinuations; }
    public boolean getStackCapture() { return stackCapture; }
    public FunctionRootRole getRole() { return role; }
    public HandoffEntry getHandoff$org_intelligence_thc() { return handoff; }
    public boolean getEnableAsync$org_intelligence_thc() { return enableAsync; }
    public boolean getEnableDelimited$org_intelligence_thc() { return enableDelimited; }
    public boolean getCapturesContinuations$org_intelligence_thc() { return capturesContinuations; }
    public FunctionRootRole getRole$org_intelligence_thc() { return role; }

    @Override public boolean requiresUnprofiledReturn() { return capturesContinuations || enableDelimited; }
    @Override protected boolean requiresMaterializableFrame() { return capturesContinuations || enableDelimited; }
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
    public Object drainTailChild(SavedGuestContinuation saved) {
        return entryForce.drainStack(saved, saved.getSourceRoot() instanceof GuestRoot root ? root.getTupleResult() : null, false, true);
    }
    public Object restartTailAnchor(VirtualFrame frame, TailCall transfer) {
        if (role != FunctionRootRole.FUNCTION || !isSelf(transfer.getTarget())) throw new IllegalStateException("Check failed.");
        restoreTail(frame, transfer);
        if (metrics.getEnabled()) metrics.incrementSelfTailReentries();
        return executeBody(frame);
    }
    @ExplodeLoop public void buildFrame(Object[] arguments, VirtualFrame frame) {
        int offset = captureLayout == null ? 1 : 2;
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
            for (int i = 0; i < argumentSlots.length; i++) {
                int position = argumentIndices[i] + offset;
                if (entry.getArguments().isInt(position)) FrameAccess.INSTANCE.writeInt(frame, argumentSlots[i], entry.getArguments().getInt(input, position));
                else if (entry.getArguments().isLong(position)) FrameAccess.INSTANCE.writeLong(frame, argumentSlots[i], entry.getArguments().getLong(input, position));
                else {
                    Object value = entry.getArguments().getObject(input, position);
                    Class<?> reference = i < argumentReferences.length ? argumentReferences[i] : null;
                    FrameAccess.INSTANCE.write(frame, argumentSlots[i], reference == null ? value : requireReferenceCarrier(value, reference));
                }
            }
            if (captureLayout != null) {
                if (!(entry.getArguments().getObject(input, 1) instanceof CapturedFrame environment)) throw fault("Invalid captured frame");
                restoreCaptured(frame, environment);
            }
        } finally { entry.state().getArguments().release(input, entry.getArguments()); }
    }
    @Override public Object execute(VirtualFrame frame) {
        if (!capturesContinuations) return executeInitial(frame, false);
        AstStackScope stack = astStackScope(this);
        boolean driver = !stack.getDriving();
        if (driver) stack.setDriving(true);
        try {
            stack.setDepth(stack.getDepth() + 1);
            Object result;
            try { result = executeInitial(frame, stack.getDepth() >= AstStackScope.MAX_DEPTH); }
            finally { stack.setDepth(stack.getDepth() - 1); }
            SavedGuestContinuation saved = result instanceof AstTailYield tail ? tail.getContinuation() : savedGuestContinuation(result);
            if (driver && saved != null && saved.stackSpill() && saved.asyncRequest() == null) return entryForce.drainStack(saved);
            return result;
        } finally { if (driver) stack.setDriving(false); }
    }
    private Object executeInitial(VirtualFrame frame, boolean spill) {
        if (metrics.getEnabled() && CompilerDirectives.inCompiledCode()) metrics.incrementCompiledEntries();
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
            if (!(frame.getArguments()[0] instanceof Long inherited)) throw fault("Invalid bloom argument");
            frame.setLong(FrameLayout.BLOOM_FILTER, entryBloom(inherited));
            buildFrame(frame.getArguments(), frame);
        }
        if (spill) return captureStack(frame.materialize());
        if (!capturesContinuations) return executeCapturableBody(frame);
        try { return executeCapturableBody(frame); }
        catch (AstCapture cut) { return finishCapture(cut, frame.materialize()); }
    }
    @TruffleBoundary public Object finishCapture(AstCapture cut, MaterializedFrame frame) {
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
        if (thc.Language.currentState(this).stm.hasTransaction())
            throw new UnsupportedCore("AST stack spilling across an active STM transaction is unsupported");
        AstStackScope stack = astStackScope(this);
        stack.setSpills(stack.getSpills() + 1);
        return new AstCapture(AstStackSpill.INSTANCE, SynchronousMasking.current(this)).append(new ResumeBody(this)).freeze(this, frame, true);
    }
    private Object executeCapturableBody(VirtualFrame frame) {
        if (!enableDelimited) return executeBody(frame);
        try { return executeBody(frame); }
        catch (DelimitedCut cut) { throw cut.append(frame, new DelimitedRootStep(this)); }
    }
    public Object resumeDelimited(VirtualFrame frame, ControlFlowException transfer, DelimitedActionSite site) {
        if (role == FunctionRootRole.PASS_THROUGH) throw transfer;
        if (transfer instanceof TailCall tail && !isSelf(tail.getTarget())) return site.tail(tail);
        if (transfer instanceof HandoffTailCall tail && !isSelf(tail.getTarget())) {
            HandoffEntry entry = handoff;
            if (entry == null) throw fault("Missing saved handoff entry");
            HandoffCaller caller = delimitedHandoff != null ? delimitedHandoff : installDelimitedHandoff(entry);
            return caller.trampoline$org_intelligence_thc(entry.state(), tail);
        }
        if (transfer instanceof TailCall tail) restoreTail(frame, tail);
        else if (transfer instanceof HandoffTailCall tail) restoreHandoff(frame, tail.getArguments(), false);
        else if (transfer != AstSelfCall.INSTANCE) throw transfer;
        if (metrics.getEnabled()) metrics.incrementSelfTailReentries();
        // A resumed suffix returns an owned value, not its caller's stale return loan.
        if (handoff != null) handoff.initializeOrdinary(frame);
        try { return executeBody(frame); }
        catch (DelimitedCut cut) { throw cut.append(frame, new DelimitedRootStep(this)); }
        catch (TailCall tail) { return site.tail(tail); }
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
            return repeating.once(frame);
        }
        if (hasSelfTail) return loop.execute(frame);
        if (!(loop.getRepeatingNode() instanceof SelfRepeater repeating)) throw fault("Invalid function self-loop node");
        try { return repeating.once(frame); }
        catch (AstSelfCall ignored) {
            tailCallProfile.enter();
            if (metrics.getEnabled()) metrics.incrementSelfTailReentries();
            CompilerDirectives.transferToInterpreterAndInvalidate();
            hasSelfTail = true;
            return loop.execute(frame);
        } catch (HandoffTailCall tail) {
            tailCallProfile.enter();
            if (!isSelf(tail.getTarget())) throw tail;
            if (metrics.getEnabled()) metrics.incrementSelfTailReentries();
            CompilerDirectives.transferToInterpreterAndInvalidate();
            hasSelfTail = true;
            restoreHandoff(frame, tail.getArguments(), false);
            return loop.execute(frame);
        } catch (TailCall tail) {
            tailCallProfile.enter();
            if (!isSelf(tail.getTarget())) throw tail;
            if (metrics.getEnabled()) metrics.incrementSelfTailReentries();
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
    @Override public long getGraphBudgetGeneration() { return budgetGeneration; }
    @Override public synchronized long prepareGraphBudgetRetry(long failedGeneration) {
        if (deferredBudget && failedGeneration == 0L && budgetGeneration == 0L && AstDeferredArm.extract(this))
            budgetGeneration = 1L;
        return budgetGeneration;
    }
    @Override protected boolean prepareForCompilation(boolean rootCompilation, int compilationTier, boolean lastTier) {
        return (!budgetBoundary || rootCompilation) && super.prepareForCompilation(rootCompilation, compilationTier, lastTier);
    }
    @Override public boolean isCloningAllowed() { return !budgetBoundary; }
}
