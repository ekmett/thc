// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime;

import jdk.incubator.vector.ByteVector;
import jdk.incubator.vector.ShortVector;
import jdk.incubator.vector.IntVector;
import jdk.incubator.vector.LongVector;
import jdk.incubator.vector.FloatVector;
import jdk.incubator.vector.DoubleVector;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorShape;
import jdk.incubator.vector.VectorShuffle;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.bytecode.BytecodeRootNode;
import com.oracle.truffle.api.bytecode.BytecodeNode;
import com.oracle.truffle.api.bytecode.LocalAccessor;
import com.oracle.truffle.api.bytecode.ConstantOperand;
import com.oracle.truffle.api.bytecode.ContinuationResult;
import com.oracle.truffle.api.bytecode.GenerateBytecode;
import com.oracle.truffle.api.bytecode.Operation;
import com.oracle.truffle.api.bytecode.Prolog;
import com.oracle.truffle.api.bytecode.Variadic;
import com.oracle.truffle.api.dsl.Bind;
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.Fallback;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.exception.AbstractTruffleException;
import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.NodeUtil;
import com.oracle.truffle.api.nodes.DirectCallNode;
import com.oracle.truffle.api.nodes.ControlFlowException;
import thc.Language;

/**
 * Executable Truffle root for Core lowered through the Bytecode DSL.
 *
 * <p>The nested operation declarations define interpreter instructions; generated
 * bytecode nodes execute them using invocation locals and runtime values. Neither
 * the root nor its operation nodes are Haskell heap values or Graal compiler IR.
 * Heap representation and application conventions are shared with the AST backend.
 */
// Generate only the cached interpreter. The former uncached threshold of zero
// transitioned before executing even the first guest instruction.
@GenerateBytecode(languageClass = Language.class, enableYield = true, enableTagInstrumentation = true,
        boxingEliminationTypes = {int.class, long.class, float.class, double.class, boolean.class})
public abstract class BytecodeRoot extends GuestRoot implements BytecodeRootNode {
    // Optional overlay declarations; stock Truffle uses its ordinary policies.
    public final boolean requiresUnprofiledReturn() {
        return asyncEnabled || delimitedEnabled;
    }

    protected final boolean requiresMaterializableFrame() {
        return asyncEnabled || delimitedEnabled;
    }

    private String label = "bytecode";
    @CompilerDirectives.CompilationFinal private boolean passThrough;
    @CompilerDirectives.CompilationFinal private BytecodeCaseRegion.Plan casePlan;
    @Children private BytecodeCaseRegion[] caseRegions = new BytecodeCaseRegion[0];
    @Child private volatile DirectCallNode recoveredEntry;

    private static final class EnterRecovered extends ControlFlowException {
        private static final EnterRecovered INSTANCE = new EnterRecovered();
    }

    /** Stock DSL prologs run only on fresh entry, before any argument ingress.
     * Saved continuations enter their original bytecode directly at the saved PC. */
    @Prolog public static final class RecoveryEntry {
        @Specialization public static void enter(@Bind BytecodeRoot root) {
            DirectCallNode redirect = root.recoveredEntry;
            if (redirect == null && root.graphFailure.get() != null) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                redirect = root.recoverEntry();
            }
            if (redirect != null) throw EnterRecovered.INSTANCE;
        }
    }

    @Override public Object interceptControlFlowException(ControlFlowException failure, VirtualFrame frame,
            BytecodeNode bytecode, int bci) {
        if (failure != EnterRecovered.INSTANCE) throw failure;
        return Calls.direct(recoveredEntry, frame.getArguments());
    }

    @TruffleBoundary private DirectCallNode recoverEntry() {
        var service = Language.currentState(this).getGraphRecovery();
        var claim = service.claim(this);
        if (claim == null) return recoveredEntry;
        try {
            if (casePlan == null || casePlan.generation() != 0) return null;
            // The generated stock clone recreates continuation constants/owners,
            // but deliberately shallow-copies inherited custom children.
            BytecodeRoot replacement = (BytecodeRoot) cloneUninitialized();
            replacement.stackDriver = stackDriver == null ? null : replacement.insert(NodeUtil.cloneNode(stackDriver));
            BytecodeCaseRegion[] regions = new BytecodeCaseRegion[caseRegions.length];
            for (int i = 0; i < regions.length; i++) regions[i] = NodeUtil.cloneNode(caseRegions[i]);
            replacement.caseRegions = replacement.insert(regions);
            replacement.casePlan = new BytecodeCaseRegion.Plan();
            replacement.casePlan.recover(0);
            DirectCallNode prepared = DirectCallNode.create(replacement.getCallTarget());
            return atomic(() -> {
                if (recoveredEntry == null && service.publishable(this, claim)) {
                    recoveredEntry = insert(prepared);
                    reportReplace(this, this, "fresh bytecode entry after terminal graph-size failure");
                }
                return recoveredEntry;
            });
        } finally { service.complete(this, claim, recoveredEntry != null); }
    }

    final void configureCaseRegions(boolean passThrough, boolean inline, BytecodeCaseRegion[] regions) {
        this.passThrough = passThrough;
        caseRegions = insert(regions);
        if (inline) casePlan = new BytecodeCaseRegion.Plan();
    }
    final long entryMask() { return passThrough ? 0L : mask; }
    final boolean useInlineCaseRegions() {
        if (casePlan == null) throw new IllegalStateException("Unconfigured bytecode case decision");
        return casePlan.inline.isValid();
    }
    public final long getGraphBudgetGeneration() { return casePlan == null ? 0 : casePlan.generation(); }
    public final long prepareGraphBudgetRetry(long failedGeneration) {
        return casePlan == null ? failedGeneration : casePlan.recover(failedGeneration);
    }
    @Override protected boolean prepareForCompilation(boolean rootCompilation, int tier, boolean lastTier) {
        // These private roots can only be called by the recovered case edge.
        return (!passThrough || rootCompilation) && super.prepareForCompilation(rootCompilation, tier, lastTier);
    }
    @CompilerDirectives.CompilationFinal private boolean asyncEnabled;
    @Child private Force stackDriver;
    public final void configureAsync(boolean enabled) { asyncEnabled = enabled; }
    public final void configureStackDriver(Metrics metrics) {
        if (asyncEnabled) stackDriver = insert(new Force(metrics, true));
    }
    public final boolean isAsyncEnabled() { return asyncEnabled; }
    @Override public final boolean getAsynchronousExceptions() { return asyncEnabled; }
    @CompilerDirectives.CompilationFinal private boolean delimitedEnabled;
    public final void configureDelimited(boolean enabled) { delimitedEnabled = enabled; }
    public final boolean isDelimitedEnabled() { return delimitedEnabled; }

    @CompilerDirectives.CompilationFinal private LocalAccessor typedBloom;
    @CompilerDirectives.CompilationFinal private LocalAccessor stackTransaction;
    public final void configureTypedBloom(LocalAccessor bloom) { typedBloom = bloom; }
    final void configureStackTransaction(LocalAccessor transaction) { stackTransaction = transaction; }
    protected final ManagedSTM.Transaction resumeStackTransaction(VirtualFrame frame) {
        ManagedSTM stm = Language.currentState(this).stm;
        ManagedSTM.Transaction ambient = stm.currentTransaction();
        Object saved = stackTransaction.getObject(getBytecodeNode(), frame);
        if (saved != null && !(saved instanceof ManagedSTM.Transaction)) throw new IllegalStateException("Invalid saved transaction");
        if (saved == null || ((ManagedSTM.Transaction) saved).active()) stm.restore((ManagedSTM.Transaction) saved);
        return ambient;
    }
    protected final void restoreStackTransaction(ManagedSTM.Transaction ambient) {
        Language.currentState(this).stm.restore(ambient);
    }

    protected BytecodeRoot(Language language, FrameDescriptor descriptor) {
        super(language, descriptor);
    }

    /** Called after the initial or resumed physical activation has unwound. */
    protected final Object finishStackEntry(Object result) {
        Object value = result instanceof TailYield tail ? tail.getContinuation() :
                result instanceof AstTailYield tail ? tail.getContinuation() : result;
        SavedGuestContinuation saved = SavedGuestContinuations.savedGuestContinuation(value);
        if (saved != null && saved.stackSpill() && saved.asyncRequest() == null) {
            if (stackDriver == null) throw new IllegalStateException("Unconfigured bytecode stack driver");
            return stackDriver.drainStack(saved);
        }
        return result;
    }

    public final void setLabel(String label) { this.label = label; }
    @Override public final String getName() { return label; }
    @Override public final String toString() { return label; }
    @Override public final long bloom(VirtualFrame frame) {
        // Self backedges restore DSL locals, leaving the incoming ancestry intact.
        if (typedBloom == null) return (long) frame.getArguments()[0] | entryMask();
        try { return typedBloom.getLong(getBytecodeNode(), frame); }
        catch (com.oracle.truffle.api.nodes.UnexpectedResultException invalid) { throw fail("Invalid typed input bloom"); }
    }

    /** Constant metadata must stay out of the guest operand stack during PE. */
    public static final class ClosureTemplate {
        public final RootCallTarget target;
        public final int arity;
        public final CaptureLayout captureLayout;
        public final Closure closed;
        public ClosureTemplate(RootCallTarget target, int arity, CaptureLayout captureLayout) {
            this.target = target;
            this.arity = arity;
            this.captureLayout = captureLayout;
            this.closed = captureLayout == null ? new Closure(null, Applications.getNO_PAP_ARGUMENTS(), arity, target) : null;
        }
    }

    @Operation
    @ConstantOperand(type = Metrics.class, name = "metrics")
    public static final class EnterRoot {
        @Specialization public static void enter(Metrics metrics) {
            if (metrics.getEnabled() && CompilerDirectives.inCompiledCode()) {
                metrics.incrementCompiledEntries();
            }
        }
    }

    @Operation public static final class StackLimit {
        @Specialization public static boolean reached(@Bind("$node") Node node) {
            AstStackScope scope = AstStacks.astStackScope(node);
            if (scope.getDepth() < AstStackScope.MAX_DEPTH) return false;
            scope.setSpills(scope.getSpills() + 1);
            return true;
        }
    }

    @Operation public static final class CurrentTransaction {
        @Specialization public static Object read(@Bind Node node) { return Language.currentState(node).stm.currentTransaction(); }
    }

    @Operation public static final class InlineCaseRegions {
        @Specialization public static boolean test(@Bind("$node") Node node) {
            BytecodeRoot root = (BytecodeRoot) node.getRootNode();
            return root.useInlineCaseRegions();
        }
    }

    @Operation
    @ConstantOperand(type = int.class, name = "index")
    @ConstantOperand(type = BytecodeCaseRegion.Source.class, name = "source")
    public static final class CallCaseRegion {
        @Specialization public static Object call(VirtualFrame frame, int index, BytecodeCaseRegion.Source source,
                Object scrutinee, MaskingState callerMask, @Bind("$node") Node node) {
            BytecodeRoot root = (BytecodeRoot) node.getRootNode();
            return root.caseRegions[index].execute(frame, root, source, scrutinee, callerMask);
        }
    }

    @Operation
    @ConstantOperand(type = Metrics.class, name = "metrics")
    public static final class JoinTransfer {
        @Specialization public static void record(Metrics metrics) {
            if (metrics.getEnabled()) metrics.incrementLocalJoinTransfers();
        }
    }

    @Operation
    @ConstantOperand(type = GlobalBinding.class, name = "binding")
    public static final class ReadGlobal {
        @Specialization public static Object read(GlobalBinding binding) { return binding.read(); }
    }

    @Operation
    @ConstantOperand(type = GlobalBinding.class, name = "binding")
    @ConstantOperand(type = int.class, name = "arguments")
    public static final class CanConstructPap {
        @Specialization public static boolean test(GlobalBinding binding, int arguments) {
            return DeferredPap.canConstruct(binding, arguments);
        }
    }

    @Operation
    @ConstantOperand(type = LocalAccessor.class, name = "destination")
    public static final class PolyglotEval {
        @Specialization
        public static void apply(VirtualFrame frame, LocalAccessor destination,
                ManagedAddress language, ManagedAddress source, ManagedAddress name, Object state,
                @Cached(value = "createAccess()", neverDefault = true) PolyglotAccess access, @Bind("$node") Node node) {
            destination.setObject(((BytecodeRoot) node.getRootNode()).getBytecodeNode(), frame,
                    access.eval(language, source, name, state));
        }
        public static PolyglotAccess createAccess() { return new PolyglotAccess(); }
    }

    @Operation
    @ConstantOperand(type = LocalAccessor.class, name = "destination")
    public static final class PolyglotReadMember {
        @Specialization
        public static void apply(VirtualFrame frame, LocalAccessor destination,
                Object value, ManagedAddress name, Object state,
                @Cached(value = "createAccess()", neverDefault = true) PolyglotAccess access, @Bind("$node") Node node) {
            destination.setObject(((BytecodeRoot) node.getRootNode()).getBytecodeNode(), frame,
                    access.readMember(frame, value, name, state));
        }
        public static PolyglotAccess createAccess() { return new PolyglotAccess(); }
    }

    @Operation
    @ConstantOperand(type = LocalAccessor.class, name = "destination")
    public static final class PolyglotExecuteInt {
        @Specialization
        public static void apply(VirtualFrame frame, LocalAccessor destination,
                Object value, long argument, Object state,
                @Cached(value = "createAccess()", neverDefault = true) PolyglotAccess access, @Bind("$node") Node node) {
            destination.setLong(((BytecodeRoot) node.getRootNode()).getBytecodeNode(), frame,
                    access.executeInt(frame, value, argument, state));
        }
        public static PolyglotAccess createAccess() { return new PolyglotAccess(); }
    }

    @Operation
    @ConstantOperand(type = LocalAccessor.class, name = "destination")
    @ConstantOperand(type = PolyglotOp.class, name = "operation")
    public static final class PolyglotStorage {
        @Specialization public static void apply(VirtualFrame frame, LocalAccessor destination,
                PolyglotOp operation, @Variadic Object[] arguments,
                @Cached(value = "createAccess()", neverDefault = true) PolyglotAccess access, @Bind("$node") Node node) {
            Object answer = access.storage(frame, operation, arguments);
            var bytecode = ((BytecodeRoot) node.getRootNode()).getBytecodeNode();
            if (operation.getResult().equals("IntRep")) destination.setLong(bytecode, frame, (Long) answer);
            else destination.setObject(bytecode, frame, answer);
        }
        public static PolyglotAccess createAccess() { return new PolyglotAccess(); }
    }

    @Operation
    public static final class InteropLibraryGet {
        @Specialization public static Object apply(Object receiver,
                @Cached(value = "createAccess()", neverDefault = true) InteropLibraryAcquisition acquisition) {
            return acquisition.execute(receiver);
        }
        public static InteropLibraryAcquisition createAccess() { return new InteropLibraryAcquisition(); }
    }

    @Operation
    @ConstantOperand(type = TruffleStringOp.class, name = "operation")
    public static final class TruffleStringOperation {
        @Specialization public static Object apply(VirtualFrame frame, TruffleStringOp operation, @Variadic Object[] arguments,
                @Cached(value = "createSite(operation)", neverDefault = true) TruffleStringOp.Site site) {
            return site.execute(frame, arguments);
        }
        public static TruffleStringOp.Site createSite(TruffleStringOp operation) { return new TruffleStringOp.Site(operation); }
    }

    @Operation
    @ConstantOperand(type = VectorApiOp.class, name = "operation")
    public static final class VectorApi {
        @Specialization public static Object apply(VectorApiOp operation, @Variadic Object[] arguments,
                @Cached(value = "createSite(operation)", neverDefault = true) VectorApiOp.Site site) {
            return site.execute(arguments);
        }
        public static VectorApiOp.Site createSite(VectorApiOp operation) { return new VectorApiOp.Site(operation); }
    }

    @Operation
    @ConstantOperand(type = PolyglotOp.class, name = "operation")
    public static final class InteropMessage {
        @Specialization public static Object apply(PolyglotOp operation, @Variadic Object[] arguments,
                @Cached(value = "createAccess()", neverDefault = true) InteropAccess access) {
            return access.execute(operation, arguments);
        }
        public static InteropAccess createAccess() { return new InteropAccess(); }
    }

    @Operation
    @ConstantOperand(type = BytecodeJavaScriptArguments.class, name = "arguments")
    @ConstantOperand(type = LocalAccessor.class, name = "destination")
    public static final class JavaScriptInt {
        @Specialization public static void call(VirtualFrame frame, BytecodeJavaScriptArguments arguments,
                LocalAccessor destination, @Bind("$node") Node node,
                @Cached(value = "createAccess(arguments)", neverDefault = true) JavaScriptAccess access) {
            BytecodeNode bytecode = ((BytecodeRoot) node.getRootNode()).getBytecodeNode();
            destination.setLong(bytecode, frame, access.executeLong(arguments.read(bytecode, frame), arguments.state(bytecode, frame)));
        }
        public static JavaScriptAccess createAccess(BytecodeJavaScriptArguments arguments) {
            return new JavaScriptAccess(arguments.getDeclaration());
        }
    }

    @Operation
    @ConstantOperand(type = BytecodeJavaScriptArguments.class, name = "arguments")
    @ConstantOperand(type = LocalAccessor.class, name = "destination")
    public static final class JavaScriptDouble {
        @Specialization public static void call(VirtualFrame frame, BytecodeJavaScriptArguments arguments,
                LocalAccessor destination, @Bind("$node") Node node,
                @Cached(value = "createAccess(arguments)", neverDefault = true) JavaScriptAccess access) {
            BytecodeNode bytecode = ((BytecodeRoot) node.getRootNode()).getBytecodeNode();
            destination.setDouble(bytecode, frame, access.executeDouble(arguments.read(bytecode, frame), arguments.state(bytecode, frame)));
        }
        public static JavaScriptAccess createAccess(BytecodeJavaScriptArguments arguments) {
            return new JavaScriptAccess(arguments.getDeclaration());
        }
    }

    @Operation
    @ConstantOperand(type = BytecodeJavaScriptArguments.class, name = "arguments")
    public static final class JavaScriptVoid {
        @Specialization public static void call(VirtualFrame frame, BytecodeJavaScriptArguments arguments,
                @Bind("$node") Node node,
                @Cached(value = "createAccess(arguments)", neverDefault = true) JavaScriptAccess access) {
            BytecodeNode bytecode = ((BytecodeRoot) node.getRootNode()).getBytecodeNode();
            access.executeVoid(arguments.read(bytecode, frame), arguments.state(bytecode, frame));
        }
        public static JavaScriptAccess createAccess(BytecodeJavaScriptArguments arguments) {
            return new JavaScriptAccess(arguments.getDeclaration());
        }
    }

    /** Core's single-register integer representation guarantees a primitive value. */
    @Operation
    @ConstantOperand(type = GlobalBinding.class, name = "binding")
    public static final class ReadGlobalLong {
        @Specialization public static long read(GlobalBinding binding) { return (long) binding.read(); }
    }

    @Operation
    @ConstantOperand(type = GlobalBinding.class, name = "binding")
    public static final class InitializeGlobal {
        @Specialization public static void initialize(GlobalBinding binding, Object value) { binding.initialize(value); }
    }

    @Operation
    public static final class ReadCellIfNeeded {
        @Specialization public static long number(long value) { return value; }
        @Specialization public static boolean bool(boolean value) { return value; }
        @Specialization(replaces = {"number", "bool"})
        public static Object read(Object value) {
            if (value instanceof RecCell cell) {
                if (!cell.getInitialized()) throw fail("Recursive binding read before initialization");
                return cell.getValue();
            }
            if (value == null) throw fail("Uninitialized local binding");
            return value;
        }
    }

    @Operation
    public static final class NewCell {
        @Specialization public static RecCell create() { return new RecCell(); }
    }

    @Operation
    public static final class InitializeCell {
        @Specialization public static void initialize(RecCell cell, Object value) {
            cell.setValue(value);
            cell.setInitialized(true);
        }
    }

    @Operation
    @ConstantOperand(type = ClosureTemplate.class, name = "template")
    public static final class MakeClosure {
        @Specialization public static Closure create(ClosureTemplate template, @Variadic Object[] values) {
            if (template.closed != null) return template.closed;
            return new Closure(template.captureLayout.captureValues(values), Applications.getNO_PAP_ARGUMENTS(), template.arity, template.target);
        }
    }

    @Operation
    @ConstantOperand(type = ClosureTemplate.class, name = "template")
    public static final class MakeThunk {
        @Specialization public static Thunk create(ClosureTemplate template, @Variadic Object[] values) {
            CapturedFrame environment = template.captureLayout == null ? null : template.captureLayout.captureValues(values);
            return new Thunk(template.target, environment);
        }
    }

    /** Capture source descriptors are replay-local metadata, never payload arrays. */
    public static final class VectorCaptureSource {
        public final ClosureTemplate template;
        public final boolean thunk;
        @CompilerDirectives.CompilationFinal(dimensions = 1)
        public final LocalAccessor[] lanes;
        public VectorCaptureSource(ClosureTemplate template, LocalAccessor[] lanes, boolean thunk) {
            this.template = template;
            this.lanes = lanes;
            this.thunk = thunk;
        }
    }

    @Operation
    @ConstantOperand(type = VectorCaptureSource.class, name = "source")
    public static final class MakeVectorCapture {
        @Specialization public static Object create(VirtualFrame frame, VectorCaptureSource source,
                @Bind("$node") Node node) {
            BytecodeNode bytecode = ((BytecodeRoot) node.getRootNode()).getBytecodeNode();
            CapturedFrame environment = source.template.captureLayout.captureLocals(bytecode, frame, source.lanes);
            if (source.thunk) return new Thunk(source.template.target, environment);
            return new Closure(environment, Applications.getNO_PAP_ARGUMENTS(), source.template.arity,
                    source.template.target);
        }
    }

    /** One logical vector field restores to one raw-vector reference local. */
    public static final class VectorCaptureSlots {
        public final CaptureLayout layout;
        public final int index;
        @CompilerDirectives.CompilationFinal(dimensions = 1)
        public final LocalAccessor[] lanes;
        public VectorCaptureSlots(CaptureLayout layout, int index, LocalAccessor[] lanes) {
            this.layout = layout;
            this.index = index;
            this.lanes = lanes;
        }
        public void restore(VirtualFrame frame, BytecodeNode bytecode, CapturedFrame environment) {
            layout.restoreVector(environment, index, bytecode, frame, lanes, 0);
        }
    }

    @Operation
    @ConstantOperand(type = VectorCaptureSlots.class, name = "slots")
    public static final class CaptureReadVector {
        @Specialization public static void read(VirtualFrame frame, VectorCaptureSlots slots,
                CapturedFrame environment, @Bind("$node") Node node) {
            slots.restore(frame, ((BytecodeRoot) node.getRootNode()).getBytecodeNode(), environment);
        }
    }

    @Operation
    @ConstantOperand(type = CaptureLayout.class, name = "layout")
    @ConstantOperand(type = int.class, name = "index")
    public static final class CaptureRead {
        @Specialization(guards = "layout.isInt(environment, index)")
        public static int integer(CaptureLayout layout, int index, CapturedFrame environment) {
            return layout.readInt(environment, index);
        }
        @Specialization(guards = "layout.isFloat(environment, index)")
        public static float floating(CaptureLayout layout, int index, CapturedFrame environment) {
            return layout.readFloat(environment, index);
        }
        @Specialization(guards = "layout.isDouble(environment, index)")
        public static double doubleValue(CaptureLayout layout, int index, CapturedFrame environment) {
            return layout.readDouble(environment, index);
        }
        @Specialization(guards = "layout.isLong(environment, index)")
        public static long number(CaptureLayout layout, int index, CapturedFrame environment) {
            return layout.readLong(environment, index);
        }
        @Specialization(guards = "layout.isObject(environment, index)")
        public static Object object(CaptureLayout layout, int index, CapturedFrame environment) {
            return layout.readObject(environment, index);
        }
    }

    @Operation
    @ConstantOperand(type = CaptureLayout.class, name = "layout")
    @ConstantOperand(type = int.class, name = "index")
    public static final class CaptureReadLong {
        @Specialization public static long read(CaptureLayout layout, int index, CapturedFrame environment) {
            return layout.readLong(environment, index);
        }
    }

    @Operation
    @ConstantOperand(type = Metrics.class, name = "metrics")
    @ConstantOperand(type = boolean.class, name = "async")
    public static final class ForceValue {
        @Specialization public static float floating(Metrics metrics, boolean async, float value) { return value; }
        @Specialization public static double doubleValue(Metrics metrics, boolean async, double value) { return value; }
        @Specialization public static long number(Metrics metrics, boolean async, long value) { return value; }
        @Specialization public static boolean bool(Metrics metrics, boolean async, boolean value) { return value; }
        @Specialization(replaces = {"number", "bool", "floating", "doubleValue"})
        public static Object force(VirtualFrame frame, Metrics metrics, boolean async, Object value,
                @Cached(value = "createForce(metrics, async)", neverDefault = true) Force force) {
            return force.execute(frame, value);
        }
        public static Force createForce(Metrics metrics, boolean async) { return new Force(metrics, async); }
    }

    /** A cold nonlocal force resumes only the thunk saved before entering it. */
    @Operation public static final class ResumeForcedValue {
        @Specialization public static Object resume(Thunk saved, ThunkSuspended suspended, ChildResume resumed) {
            Thunk child = suspended.getThunk();
            if (saved != child)
                throw new IllegalStateException("Forced-value continuation lost its saved child");
            if (resumed.getFailure() != null) throw resumed.takeFailure();
            if (child.getState() != 2 || child.getValue() != resumed.getValue())
                throw new IllegalStateException("Forced-value continuation lost its child update");
            return resumed.takeValue();
        }
        @Fallback public static Object malformed(Object saved, Object suspended, Object resumed) {
            throw new IllegalStateException("Forced-value continuation requires its exact saved thunk and ChildResume");
        }
    }

    /** A successful force updates this activation's mutable binding, not its final capture property. */
    @Operation
    @ConstantOperand(type = Metrics.class, name = "metrics")
    @ConstantOperand(type = LocalAccessor.class, name = "local")
    @ConstantOperand(type = boolean.class, name = "cell")
    @ConstantOperand(type = boolean.class, name = "async")
    public static final class ForceLocal {
        @Specialization public static float floating(Metrics metrics, LocalAccessor local, boolean cell, boolean async, float value) { return value; }
        @Specialization public static double doubleValue(Metrics metrics, LocalAccessor local, boolean cell, boolean async, double value) { return value; }
        @Specialization public static long number(Metrics metrics, LocalAccessor local, boolean cell, boolean async, long value) { return value; }
        @Specialization public static boolean bool(Metrics metrics, LocalAccessor local, boolean cell, boolean async, boolean value) { return value; }
        @Specialization(replaces = {"number", "bool", "floating", "doubleValue"})
        public static Object force(VirtualFrame frame, Metrics metrics, LocalAccessor local, boolean cell, boolean async, Object binding,
                @Bind("$node") Node node,
                @Cached(value = "createForce(metrics, async)", neverDefault = true) Force force) {
            // The compiler knows whether this lexical binding retains a recursive
            // cell. Ordinary formals, fields and published values need no cell test.
            Object original = cell ? ReadCellIfNeeded.read(binding) : binding;
            Object result = force.execute(frame, original);
            publish(frame, local, cell, binding, original, result, node);
            return result;
        }
        public static Force createForce(Metrics metrics, boolean async) { return new Force(metrics, async); }
        static void publish(VirtualFrame frame, LocalAccessor local, boolean cell, Object binding,
                Object original, Object result, Node node) {
            if (!(original instanceof Thunk thunk)) return;
            if (cell) {
                ((RecCell) binding).updateForced(thunk, result);
            } else {
                BytecodeNode bytecode = ((BytecodeRoot) node.getRootNode()).getBytecodeNode();
                if (local.getObject(bytecode, frame) == thunk) {
                    // Reuse the thunk's boxed result in an object-profiled local.
                    // The generic DSL setter also preserves existing primitive
                    // slot profiles; typed setters would unbox and rebox here.
                    local.setObject(bytecode, frame, result);
                }
            }
        }
    }

    @Operation
    public static final class SuspensionOnly {
        @Specialization public static ThunkSuspended capture(AbstractTruffleException failure) {
            if (failure instanceof ThunkSuspended suspended) return suspended;
            throw failure;
        }
    }

    @Operation
    @ConstantOperand(type = LocalAccessor.class, name = "local")
    @ConstantOperand(type = boolean.class, name = "cell")
    public static final class ResumeForcedLocal {
        @Specialization public static Object resume(VirtualFrame frame, LocalAccessor local, boolean cell,
                ThunkSuspended suspended, ChildResume resumed, @Bind("$node") Node node) {
            if (resumed.getFailure() != null) throw resumed.takeFailure();
            BytecodeNode bytecode = ((BytecodeRoot) node.getRootNode()).getBytecodeNode();
            Object binding = local.getObject(bytecode, frame);
            Thunk thunk = suspended.getThunk();
            if (thunk.getState() != 2 || thunk.getValue() != resumed.getValue())
                throw new IllegalStateException("Forced-local continuation lost its child update");
            if (cell) {
                if (!(binding instanceof RecCell recursive))
                    throw new IllegalStateException("Forced-local continuation lost its recursive cell");
                synchronized (recursive) {
                    Object current = ReadCellIfNeeded.read(recursive);
                    if (current != thunk && current != resumed.getValue())
                        throw new IllegalStateException("Forced-local continuation lost its child update");
                    ForceLocal.publish(frame, local, true, binding, thunk, resumed.getValue(), node);
                }
            } else {
                if (binding != thunk && binding != resumed.getValue())
                    throw new IllegalStateException("Forced-local continuation lost its child update");
                ForceLocal.publish(frame, local, false, binding, thunk, resumed.getValue(), node);
            }
            return resumed.takeValue();
        }
        @Fallback public static Object malformed(LocalAccessor local, boolean cell, Object suspended, Object resumed) {
            throw new IllegalStateException("Forced-local continuation requires ChildResume");
        }
    }

    /** Test-owned, non-tail scalar call edge. No packet is allocated on ordinary returns. */
    @Operation
    @ConstantOperand(type = int.class, name = "arity")
    public static final class CaptureApplicationResult {
        @Specialization public static Object capture(int arity, Closure function, Object result, MaskingState callerMask,
                @Bind("$node") Node node) {
            DelimitedControl.captureBytecode(result, null);
            if (!(result instanceof TailYield) && !(result instanceof AstTailYield) &&
                    !(result instanceof ContinuationResult) && !(result instanceof SavedGuestContinuation)) {
                if (SynchronousMasking.current(node) != callerMask) {
                    SynchronousMasking.set(node, callerMask);
                    throw new IllegalStateException("Completed application did not restore its caller mask");
                }
                return result;
            }
            throw captureSuspended(arity, function, result, node, callerMask);
        }
        @TruffleBoundary(transferToInterpreterOnException = false)
        private static RuntimeException captureSuspended(int arity, Closure function, Object result,
                Node node, MaskingState callerMask) {
            try {
                RootCallTarget target = result instanceof TailYield tail ? tail.getTarget() :
                        result instanceof AstTailYield tail ? tail.getTarget() : function.target;
                SavedGuestContinuation continuation = SavedGuestContinuations.savedGuestContinuation(
                        result instanceof TailYield tail ? tail.getContinuation() :
                        result instanceof AstTailYield tail ? tail.getContinuation() : result);
                Object source = continuation.getSourceRoot();
                if (function.arity != arity || !(source instanceof GuestRoot callee) ||
                        !callee.isSelf(target) || !AsyncContinuations.isYieldMarker(continuation.getYielded()))
                    throw new IllegalStateException("Application returned an unrelated guest continuation: " +
                            "arity=" + function.arity + "/" + arity + ", source=" + source +
                            ", target=" + target.getRootNode() + ", yielded=" + continuation.getYielded());
                MaskingState parked = continuation.getYielded() instanceof CallSegmentSuspended suspended
                        ? suspended.getParkedActiveMask() : null;
                if (parked != null && SynchronousMasking.current(node) != callerMask)
                    throw new IllegalStateException("Parked application did not restore its caller mask");
                // Only a yielded call allocates this carrier. Its saved frame
                // and active mask belong to the logical callee, not this host thread.
                MaskingState active = parked != null ? parked : SynchronousMasking.current(node);
                return new CapturedCallSuspension(new CallSegment(continuation.getIdentity(), active, callerMask));
            } finally {
                SynchronousMasking.set(node, callerMask);
            }
        }
    }

    /** Mask operations below are emitted only by the private call checkpoint path. */
    @Operation public static final class CurrentMask {
        @Specialization public static MaskingState read(@Bind("$node") Node node) {
            return SynchronousMasking.current(node);
        }
    }

    /** Poll only at a bytecode cut whose locals and operand stack can be resumed. */
    @Operation
    @ConstantOperand(type = LocalAccessor.class, name = "request")
    public static final class PollAsync {
        @Specialization public static boolean poll(VirtualFrame frame, LocalAccessor request,
                @Bind Node node) {
            // Take the evidence before the mailbox's cold boundary leaves compiled code.
            boolean compiled = CompilerDirectives.inCompiledCode();
            AsyncRequest pending = GuestThreads.pollCurrent(node, false);
            if (pending == null) return false;
            pending.compiledCapture = compiled;
            request.setObject(((BytecodeRoot) node.getRootNode()).getBytecodeNode(), frame, pending);
            return true;
        }
    }

    /** The post-throwTo cut must claim self delivery under both masks and speculation. */
    @Operation
    @ConstantOperand(type = LocalAccessor.class, name = "request")
    public static final class PollMandatoryAsync {
        @Specialization public static boolean poll(VirtualFrame frame, LocalAccessor request, @Bind Node node) {
            boolean compiled = CompilerDirectives.inCompiledCode();
            AsyncRequest pending = GuestThreads.pollMandatoryCurrent(node, false);
            if (pending == null) return false;
            pending.compiledCapture = compiled;
            request.setObject(((BytecodeRoot) node.getRootNode()).getBytecodeNode(), frame, pending);
            return true;
        }
    }

    /** A pending cut uses fixed object registers, never cold generic load/store quickening. */
    @Operation
    @ConstantOperand(type = LocalAccessor.class, name = "request")
    @ConstantOperand(type = LocalAccessor.class, name = "rootEntry")
    @ConstantOperand(type = LocalAccessor.class, name = "active")
    public static final class ParkPendingAsyncMask {
        @Specialization public static AsyncRequest park(VirtualFrame frame, LocalAccessor request,
                LocalAccessor rootEntry, LocalAccessor active, @Bind Node node) {
            BytecodeNode bytecode = ((BytecodeRoot) node.getRootNode()).getBytecodeNode();
            AsyncRequest pending = (AsyncRequest) request.getObject(bytecode, frame);
            active.setObject(bytecode, frame, SynchronousMasking.current(node));
            SynchronousMasking.set(node, (MaskingState) rootEntry.getObject(bytecode, frame));
            return pending;
        }
    }

    @Operation
    @ConstantOperand(type = LocalAccessor.class, name = "active")
    public static final class ReenterPendingAsyncMask {
        @Specialization public static Object reenter(VirtualFrame frame, LocalAccessor active,
                Object resumed, @Bind Node node) {
            if (!(resumed instanceof DelimitedResume)) {
                BytecodeNode bytecode = ((BytecodeRoot) node.getRootNode()).getBytecodeNode();
                SynchronousMasking.set(node, (MaskingState) active.getObject(bytecode, frame));
            }
            return resumed;
        }
    }

    @Operation public static final class ParkAsyncMask {
        @Specialization public static Object park(Object marker, MaskingState rootEntry,
                @Bind Node node) {
            if (!(marker instanceof AsyncRequest || marker instanceof PendingWait)) throw new IllegalStateException("Invalid pending-operation cut");
            SynchronousMasking.set(node, rootEntry);
            return marker;
        }
    }

    @Operation public static final class ResumePendingWait {
        @Specialization public static void resume(Object marker) { if (marker instanceof PendingWait wait) wait.resume(); }
    }
    @Operation public static final class RetainPendingWait {
        @Specialization public static Object retain(Object marker) { return marker instanceof PendingWait ? marker : null; }
    }

    @Operation
    @ConstantOperand(type = boolean.class, name = "writing")
    public static final class PrepareWindowsIoRequest {
        @Specialization public static WindowsNativeIo.Request prepare(boolean writing, long fd, long socket,
                long count, ManagedAddress address, Object state) {
            TupleResults.requireVoidCarrier(state);
            return WindowsNativeIo.required().submit(Math.toIntExact(fd), socket != 0, Math.toIntExact(count), address, writing);
        }
    }

    @Operation
    @ConstantOperand(type = LocalAccessor.class, name = "result")
    public static final class InstallWindowsConsole {
        @Specialization public static void install(VirtualFrame frame, LocalAccessor result, int action,
                ManagedAddress cell, Object state, @Bind Node node) {
            TupleResults.requireVoidCarrier(state);
            result.setInt(((BytecodeRoot) node.getRootNode()).getBytecodeNode(), frame,
                WindowsNativeIo.required().console().install(action, cell));
        }
    }
    @Operation public static final class WindowsConsoleDone {
        @Specialization public static void done(int code, Object state) {
            TupleResults.requireVoidCarrier(state);
            WindowsNativeIo.required().console().done(code);
        }
    }

    /** A prepared native request is saved before the retry/yield loop. Await
     * may resume its original completion, but never prepares another effect. */
    @Operation
    @ConstantOperand(type = LocalAccessor.class, name = "length")
    @ConstantOperand(type = LocalAccessor.class, name = "error")
    public static final class AwaitWindowsIoRequest {
        @Specialization public static void await(VirtualFrame frame, LocalAccessor length,
                LocalAccessor error, WindowsNativeIo.Request request, @Bind Node node) {
            var result = request.awaitResumable(frame.materialize(), node, CompilerDirectives.inCompiledCode());
            BytecodeNode bytecode = ((BytecodeRoot) node.getRootNode()).getBytecodeNode();
            length.setLong(bytecode, frame, result.length());
            error.setLong(bytecode, frame, result.error());
        }
    }

    /** Process WAIT has saved both scalar and errno before this delivery cut. */
    @Operation
    @ConstantOperand(type = LocalAccessor.class, name = "request")
    public static final class PollProcessCompleted {
        @Specialization public static boolean poll(VirtualFrame frame, LocalAccessor request,
                int result, long errno, @Bind Node node) {
            boolean compiled = CompilerDirectives.inCompiledCode();
            AsyncRequest pending = GuestThreads.pollCurrent(node, result == -1 && errno == 4L);
            request.setObject(((BytecodeRoot) node.getRootNode()).getBytecodeNode(), frame, pending);
            if (pending == null) return false;
            pending.compiledCapture = compiled;
            return true;
        }
    }

    @Operation public static final class RestoreProcessErrno {
        @Specialization public static void restore(long errno, @Bind Node node) {
            CoreOriginalStdio.current(node).setErrno(errno);
        }
    }

    @Operation
    @ConstantOperand(type = LocalAccessor.class, name = "status")
    @ConstantOperand(type = LocalAccessor.class, name = "capability")
    @ConstantOperand(type = LocalAccessor.class, name = "locked")
    public static final class ThreadStatus {
        @Specialization public static void observe(VirtualFrame frame, LocalAccessor status,
                LocalAccessor capability, LocalAccessor locked, Object identity, Object state, @Bind Node node) {
            TupleResults.requireVoidCarrier(state);
            GuestThreadSnapshot snapshot = GuestThreadOps.threadStatus(node, identity);
            BytecodeNode bytecode = ((BytecodeRoot) node.getRootNode()).getBytecodeNode();
            status.setLong(bytecode, frame, snapshot.getStatus());
            capability.setLong(bytecode, frame, snapshot.getCapability());
            locked.setLong(bytecode, frame, snapshot.getLocked());
        }
    }

    @Operation
    @ConstantOperand(type = LocalAccessor.class, name = "destination")
    @ConstantOperand(type = boolean.class, name = "listing")
    public static final class ObserveThreads {
        @Specialization public static void observe(VirtualFrame frame, LocalAccessor destination,
                boolean listing, Object state, @Bind Node node) {
            TupleResults.requireVoidCarrier(state);
            GuestThreads threads = GuestThreads.current(node);
            BytecodeNode bytecode = ((BytecodeRoot) node.getRootNode()).getBytecodeNode();
            if (listing) destination.setObject(bytecode, frame, threads.snapshot());
            else destination.setLong(bytecode, frame, threads.isCurrentBound() ? 1L : 0L);
        }
    }

    @Operation public static final class LabelThread {
        @Specialization public static void set(Object identity, Object bytes, Object state, @Bind Node node) {
            TupleResults.requireVoidCarrier(state);
            GuestThreadOps.labelThread(node, identity, bytes);
        }
    }

    @Operation
    @ConstantOperand(type = LocalAccessor.class, name = "present")
    @ConstantOperand(type = LocalAccessor.class, name = "bytes")
    public static final class ThreadLabel {
        @Specialization public static void get(VirtualFrame frame, LocalAccessor present, LocalAccessor bytes,
                Object identity, Object state, @Bind Node node) {
            TupleResults.requireVoidCarrier(state);
            Object label = GuestThreadOps.threadLabel(node, identity);
            BytecodeNode bytecode = ((BytecodeRoot) node.getRootNode()).getBytecodeNode();
            present.setLong(bytecode, frame, label == null ? 0L : 1L);
            bytes.setObject(bytecode, frame, label);
        }
    }

    public enum ThreadPrimitiveKind { MY, FORK, FORK_ON, BEGIN_KILL, FINISH_KILL, SELF_KILL }

    /** One cold instruction keeps the generated interpreter below its partition limit. */
    @Operation
    @ConstantOperand(type = ThreadPrimitiveKind.class, name = "kind")
    public static final class ThreadPrimitive {
        @Specialization public static Object execute(ThreadPrimitiveKind kind, Object first, Object second,
                Object third, @Bind Node node) {
            return switch (kind) {
                case MY -> {
                    TupleResults.requireVoidCarrier(second);
                    yield GuestThreadOps.myThreadId(node);
                }
                case FORK -> {
                    TupleResults.requireVoidCarrier(second);
                    // A lifted fork action may still be a thunk. Only the new
                    // child may enter it; the parent must return after registration.
                    yield GuestThreadOps.fork(node, first, ((BytecodeRoot) node.getRootNode()).isAsyncEnabled());
                }
                case FORK_ON -> {
                    TupleResults.requireVoidCarrier(third);
                    if (!(first instanceof Long capability)) throw fail("forkOn# requires Int#");
                    yield GuestThreadOps.fork(node, second, ((BytecodeRoot) node.getRootNode()).isAsyncEnabled(), capability);
                }
                case BEGIN_KILL -> {
                    TupleResults.requireVoidCarrier(third);
                    yield GuestThreadOps.beginKill(node, first, second);
                }
                case FINISH_KILL -> {
                    GuestThreadOps.finishKill(node, (AsyncRequest) first);
                    yield thc.runtime.Unit.INSTANCE;
                }
                case SELF_KILL -> {
                    TupleResults.requireVoidCarrier(third);
                    yield GuestThreadOps.killSelf(node, first, second);
                }
            };
        }
    }

    @Operation public static final class CurrentAnnotations {
        @Specialization public static StackAnnotationState current(@Bind("$node") Node node) {
            return StackAnnotations.current(node);
        }
    }
    @Operation public static final class EnterAnnotation {
        @Specialization public static StackAnnotationState enter(Object annotation, @Bind Node node) {
            return StackAnnotations.enter(node, annotation);
        }
    }
    @Operation public static final class RestoreAnnotations {
        @Specialization public static void restore(StackAnnotationState prior, @Bind Node node) {
            StackAnnotations.set(node, prior);
        }
    }
    @Operation
    @ConstantOperand(type = LocalAccessor.class, name = "rootEntry")
    @ConstantOperand(type = LocalAccessor.class, name = "active")
    public static final class ParkAnnotations {
        @Specialization public static Object park(VirtualFrame frame, LocalAccessor rootEntry,
                LocalAccessor active, Object marker, @Bind("$node") Node node) {
            // Delimited capture unwinds through explicit annotation-return steps.
            if (!(marker instanceof DelimitedCut)) {
                BytecodeNode bytecode = ((BytecodeRoot) node.getRootNode()).getBytecodeNode();
                active.setObject(bytecode, frame, StackAnnotations.current(node));
                StackAnnotations.set(node, (StackAnnotationState) rootEntry.getObject(bytecode, frame));
            }
            return marker;
        }
    }
    @Operation
    @ConstantOperand(type = LocalAccessor.class, name = "active")
    public static final class ResumeAnnotations {
        @Specialization public static Object resume(VirtualFrame frame, LocalAccessor active,
                Object result, @Bind("$node") Node node) {
            if (!(result instanceof DelimitedResume)) {
                BytecodeNode bytecode = ((BytecodeRoot) node.getRootNode()).getBytecodeNode();
                StackAnnotations.set(node, (StackAnnotationState) active.getObject(bytecode, frame));
            }
            return result;
        }
    }

    @Operation public static final class ParkCallMask {
        @Specialization public static Object park(Object value,
                MaskingState rootEntry, MaskingState callerActive, @Bind("$node") Node node) {
            if (value instanceof DelimitedCut cut) return cut;
            if (value instanceof CallSegmentSuspended suspended) return park(suspended, rootEntry, callerActive, node);
            throw new com.oracle.truffle.api.dsl.UnsupportedSpecializationException(node, null, value, rootEntry, callerActive);
        }
        public static CallSegmentSuspended park(CallSegmentSuspended suspended,
                MaskingState rootEntry, MaskingState callerActive, Node node) {
            try {
                if (SynchronousMasking.current(node) != callerActive)
                    throw new IllegalStateException("Captured caller lost its logical mask before Yield");
                return new CallSegmentSuspended(suspended.getSegment(), callerActive,
                        suspended.getAsyncRequest(), suspended.getStackSpill());
            } finally {
                // Yield skips lexical finally. Each root parks to its own entry
                // mask, so a chain of callers unwinds to the carrier ambient.
                SynchronousMasking.set(node, rootEntry);
            }
        }
    }

    @Operation public static final class ReenterCallMask {
        @Specialization public static Object reenter(Object resumed, MaskingState callerActive,
                @Bind("$node") Node node) {
            if (!(resumed instanceof DelimitedResume)) SynchronousMasking.set(node, callerActive);
            return resumed;
        }
    }

    @Operation
    public static final class CallSuspensionOnly {
        @Specialization public static Object capture(AbstractTruffleException failure) {
            if (failure instanceof DelimitedCut cut) return cut;
            if (failure instanceof CapturedCallSuspension captured) return captured(captured.getSegment());
            if (failure instanceof PendingWait wait) return wait;
            if (failure instanceof AsyncBlocked blocked) return blocked.getRequest();
            if (failure instanceof STMRestart restart) return restart.getRequest();
            throw failure;
        }
        // The first cold packet must classify its real saved continuation without
        // specializing compiled code on whichever continuation subtype loaded first.
        @TruffleBoundary(transferToInterpreterOnException = false)
        private static CallSegmentSuspended captured(CallSegment segment) {
            return new CallSegmentSuspended(segment);
        }
    }

    @Operation
    public static final class ResumeApplication {
        @Specialization public static Object resumeDelimited(DelimitedCut cut, DelimitedResume resumed) {
            return resumed.get();
        }
        @Specialization public static Object resume(CallSegmentSuspended suspended, ChildResume resumed) {
            if (resumed.getFailure() != null) throw resumed.takeFailure();
            CallSegment call = suspended.getSegment();
            if (call.getState() != 2 || call.getValue() != resumed.getValue() || resumed.getValue() instanceof ContinuationResult)
                throw new IllegalStateException("Application continuation lost its call update");
            return resumed.takeValue();
        }
        @Fallback public static Object malformed(Object suspended, Object resumed) {
            throw new IllegalStateException("Application continuation requires ChildResume");
        }
    }

    /** The ordinary noDuplicate# operation remains unchanged; this is a private test checkpoint. */
    @Operation
    @ConstantOperand(type = BytecodeCheckpoint.class, name = "checkpoint")
    public static final class CheckpointArmed {
        @Specialization public static boolean armed(BytecodeCheckpoint checkpoint) {
            if (CompilerDirectives.inCompiledCode()) checkpoint.getCompiledVisits().incrementAndGet();
            if (!checkpoint.getArmed()) return false;
            checkpoint.getVisits().incrementAndGet();
            return true;
        }
    }

    /** Explicit original-foreign ABI widening; never an implicit guest scalar cast. */
    @Operation
    @ConstantOperand(type = NarrowInteger.class, name = "integer")
    public static final class ForeignIntegerToHost {
        @Specialization public static long widen(NarrowInteger integer, int value) { return integer.widen(value); }
    }

    @Operation
    @ConstantOperand(type = int.class, name = "arity")
    public static final class CheckSumTag {
        @Specialization public static long execute(int arity, long value) { return SumShape.INSTANCE.checkedTag(value, arity); }
    }

    @Operation
    @ConstantOperand(type = EnumFamily.class, name = "family")
    public static final class TagToEnum {
        @Specialization public static DataValue select(EnumFamily family, long tag) { return family.select(tag); }
    }

    @Operation
    @ConstantOperand(type = DataTagFamily.class, name = "family")
    public static final class DataToTag {
        @Specialization public static long tag(DataTagFamily family, DataValue value) { return family.tag(value); }
        @Fallback public static long invalid(DataTagFamily family, Object value) {
            throw new RuntimeFault("dataToTag: expected demanded DataValue");
        }
    }

    // Truffle Bytecode DSL specializations require Java; the shared IEEE
    // shared decomposition returns primitive fields, not a carrier.
    @Operation
    @ConstantOperand(type = LocalAccessor.class, name = "mantissa")
    @ConstantOperand(type = LocalAccessor.class, name = "exponent")
    public static final class DecodeFloat {
        @Specialization public static void execute(VirtualFrame frame,
                LocalAccessor mantissa, LocalAccessor exponent, float value, @Bind("$node") Node node) {
            long bits = Float.floatToRawIntBits(value);
            BytecodeNode bytecode = ((BytecodeRoot) node.getRootNode()).getBytecodeNode();
            mantissa.setLong(bytecode, frame, FloatDecodeOp.FLOAT.mantissa(bits));
            exponent.setLong(bytecode, frame, FloatDecodeOp.FLOAT.exponent(bits));
        }
    }

    @Operation
    @ConstantOperand(type = LocalAccessor.class, name = "mantissa")
    @ConstantOperand(type = LocalAccessor.class, name = "exponent")
    public static final class DecodeDouble {
        @Specialization public static void execute(VirtualFrame frame,
                LocalAccessor mantissa, LocalAccessor exponent, double value, @Bind("$node") Node node) {
            long bits = Double.doubleToRawLongBits(value);
            BytecodeNode bytecode = ((BytecodeRoot) node.getRootNode()).getBytecodeNode();
            mantissa.setLong(bytecode, frame, FloatDecodeOp.DOUBLE.mantissa(bits));
            exponent.setLong(bytecode, frame, FloatDecodeOp.DOUBLE.exponent(bits));
        }
    }

    @Operation
    @ConstantOperand(type = LocalAccessor.class, name = "sign")
    @ConstantOperand(type = LocalAccessor.class, name = "high")
    @ConstantOperand(type = LocalAccessor.class, name = "low")
    @ConstantOperand(type = LocalAccessor.class, name = "exponent")
    public static final class DecodeDoubleWords {
        @Specialization public static void execute(VirtualFrame frame,
                LocalAccessor sign, LocalAccessor high, LocalAccessor low, LocalAccessor exponent,
                double value, @Bind("$node") Node node) {
            long bits = Double.doubleToRawLongBits(value);
            BytecodeNode bytecode = ((BytecodeRoot) node.getRootNode()).getBytecodeNode();
            sign.setLong(bytecode, frame, FloatDecodeOp.DOUBLE_WORDS.sign(bits));
            high.setLong(bytecode, frame, FloatDecodeOp.DOUBLE_WORDS.high(bits));
            low.setLong(bytecode, frame, FloatDecodeOp.DOUBLE_WORDS.low(bits));
            exponent.setLong(bytecode, frame, FloatDecodeOp.DOUBLE_WORDS.exponent(bits));
        }
    }

    /** Saturated tuple arithmetic never constructs a result carrier or payload array. */
    @Operation
    @ConstantOperand(type = TupleArithmeticOp.class, name = "operation")
    @ConstantOperand(type = LocalAccessor.class, name = "first")
    @ConstantOperand(type = LocalAccessor.class, name = "second")
    @ConstantOperand(type = LocalAccessor.class, name = "third")
    public static final class TupleArithmetic {
        @Specialization public static void executeInt(VirtualFrame frame, TupleArithmeticOp operation,
                LocalAccessor first, LocalAccessor second, LocalAccessor third,
                int left, int right, @Bind("$node") Node node) {
            int a = operation.firstInt(left, right);
            int b = operation.secondInt(left, right);
            BytecodeNode bytecode = ((BytecodeRoot) node.getRootNode()).getBytecodeNode();
            first.setInt(bytecode, frame, a);
            second.setInt(bytecode, frame, b);
        }
        @Specialization public static void execute(VirtualFrame frame, TupleArithmeticOp operation,
                LocalAccessor first, LocalAccessor second, LocalAccessor third,
                long left, long right, @Bind("$node") Node node) {
            long a = operation.first(left, right);
            long b = operation.second(left, right);
            long c = operation.getResultArity() == 3 ? operation.third(left, right) : 0L;
            BytecodeNode bytecode = ((BytecodeRoot) node.getRootNode()).getBytecodeNode();
            first.setLong(bytecode, frame, a);
            second.setLong(bytecode, frame, b);
            if (operation.getResultArity() == 3) third.setLong(bytecode, frame, c);
        }
    }

    @Operation
    @ConstantOperand(type = LocalAccessor.class, name = "quotient")
    @ConstantOperand(type = LocalAccessor.class, name = "remainder")
    public static final class DoubleWordDivision {
        @Specialization public static void execute(VirtualFrame frame, LocalAccessor quotient,
                LocalAccessor remainder, long high, long low, long divisor, @Bind("$node") Node node) {
            long q = Scalar64Primitives.unsignedDoubleWordQuotient(high, low, divisor);
            BytecodeNode bytecode = ((BytecodeRoot) node.getRootNode()).getBytecodeNode();
            quotient.setLong(bytecode, frame, q);
            remainder.setLong(bytecode, frame, low - q * divisor);
        }
    }

    @Operation
    @ConstantOperand(type = LocalAccessor.class, name = "destination")
    public static final class NewPinnedByteArray {
        @Specialization public static void allocate(VirtualFrame frame, LocalAccessor destination,
                long size, Object state, @Bind("$node") Node node) {
            ManagedByteArray.requireState(state);
            ManagedAllocation allocation = PinnedMemory.allocate(size, 1L);
            destination.setObject(((BytecodeRoot) node.getRootNode()).getBytecodeNode(), frame, allocation);
        }
    }

    @Operation
    @ConstantOperand(type = LocalAccessor.class, name = "destination")
    public static final class NewAlignedPinnedByteArray {
        @Specialization public static void allocate(VirtualFrame frame, LocalAccessor destination,
                long size, long alignment, Object state, @Bind("$node") Node node) {
            ManagedByteArray.requireState(state);
            ManagedAllocation allocation = PinnedMemory.allocate(size, alignment);
            destination.setObject(((BytecodeRoot) node.getRootNode()).getBytecodeNode(), frame, allocation);
        }
    }

    @Operation
    public static final class ByteArrayContents {
        @Specialization public static ManagedAddress address(Object array) {
            return ManagedAddress.fromGuestByteArray(array);
        }
    }

    @Operation
    @ConstantOperand(type = LocalAccessor.class, name = "destination")
    public static final class ReadWord8OffAddr {
        @Specialization public static void read(VirtualFrame frame, LocalAccessor destination,
                ManagedAddress address, long offset, Object state, @Bind("$node") Node node) {
            ManagedByteArray.requireState(state);
            int value = address.readWord8Int(offset);
            destination.setInt(((BytecodeRoot) node.getRootNode()).getBytecodeNode(), frame, value);
        }
    }

    @Operation
    @ConstantOperand(type = LocalAccessor.class, name = "destination")
    public static final class ReadCharOffAddr {
        @Specialization public static void read(VirtualFrame frame, LocalAccessor destination,
                ManagedAddress address, long offset, Object state, @Bind("$node") Node node) {
            ManagedByteArray.requireState(state);
            long value = address.indexChar(offset);
            destination.setLong(((BytecodeRoot) node.getRootNode()).getBytecodeNode(), frame, value);
        }
    }

    // Java is required here by the Truffle Bytecode DSL annotation processor.
    @Operation @ConstantOperand(type = AtomicAddressOp.class, name = "operation")
    @ConstantOperand(type = LocalAccessor.class, name = "destination")
    public static final class AtomicAddressNumeric {
        @Specialization public static void executeInt(VirtualFrame frame, AtomicAddressOp operation,
                LocalAccessor destination, ManagedAddress location, int operand, int replacement,
                Object state, @Bind("$node") Node node) {
            ManagedByteArray.requireState(state);
            destination.setInt(((BytecodeRoot) node.getRootNode()).getBytecodeNode(), frame,
                operation.numericInt(location, operand, replacement));
        }
        @Specialization public static void execute(VirtualFrame frame, AtomicAddressOp operation,
                LocalAccessor destination, ManagedAddress location, long operand, long replacement,
                Object state, @Bind("$node") Node node) {
            ManagedByteArray.requireState(state);
            destination.setLong(((BytecodeRoot) node.getRootNode()).getBytecodeNode(), frame,
                operation.numeric(location, operand, replacement));
        }
    }

    @Operation @ConstantOperand(type = AtomicAddressOp.class, name = "operation")
    @ConstantOperand(type = LocalAccessor.class, name = "destination")
    public static final class AtomicAddressPointer {
        @Specialization public static void execute(VirtualFrame frame, AtomicAddressOp operation,
                LocalAccessor destination, ManagedAddress location, ManagedAddress operand,
                ManagedAddress replacement, Object state, @Bind("$node") Node node) {
            ManagedByteArray.requireState(state);
            destination.setObject(((BytecodeRoot) node.getRootNode()).getBytecodeNode(), frame,
                operation.address(location, operand, replacement));
        }
    }

    @Operation public static final class AtomicAddressWrite {
        @Specialization public static Object execute(ManagedAddress location, long value, Object state) {
            ManagedByteArray.requireState(state);
            AtomicAddressOp.WRITE.numeric(location, value, 0L);
            return thc.runtime.Unit.INSTANCE;
        }
    }

    @Operation
    @ConstantOperand(type = LocalAccessor.class, name = "destination")
    public static final class ReadInt8OffAddr {
        @Specialization public static void read(VirtualFrame frame, LocalAccessor destination,
                ManagedAddress address, long offset, Object state, @Bind("$node") Node node) {
            ManagedByteArray.requireState(state);
            int value = (byte) address.readWord8Int(offset);
            destination.setInt(((BytecodeRoot) node.getRootNode()).getBytecodeNode(), frame, value);
        }
    }

    @Operation
    @ConstantOperand(type = boolean.class, name = "byteOffset")
    @ConstantOperand(type = LocalAccessor.class, name = "destination")
    public static final class ReadAddrOffAddr {
        @Specialization public static void read(VirtualFrame frame, boolean byteOffset, LocalAccessor destination,
                ManagedAddress address, long offset, Object state, @Bind("$node") Node node) {
            ManagedByteArray.requireState(state);
            destination.setObject(((BytecodeRoot) node.getRootNode()).getBytecodeNode(), frame,
                address.readAddressElementIndex(offset, byteOffset));
        }
    }

    @Operation
    @ConstantOperand(type = boolean.class, name = "byteOffset")
    public static final class IndexAddrOffAddr {
        @Specialization public static ManagedAddress read(boolean byteOffset, ManagedAddress address, long index) {
            return address.readAddressElementIndex(index, byteOffset);
        }
    }

    @Operation
    @ConstantOperand(type = boolean.class, name = "byteOffset")
    public static final class IndexAddrArray {
        @Specialization public static ManagedAddress read(boolean byteOffset, Object array, long index) {
            return PinnedMemory.readAddressArray(array, index, byteOffset);
        }
    }

    @Operation
    @ConstantOperand(type = boolean.class, name = "byteOffset")
    @ConstantOperand(type = LocalAccessor.class, name = "destination")
    public static final class ReadAddrArray {
        @Specialization public static void read(VirtualFrame frame, boolean byteOffset, LocalAccessor destination,
                Object array, long index, Object state, @Bind("$node") Node node) {
            ManagedByteArray.requireState(state);
            destination.setObject(((BytecodeRoot) node.getRootNode()).getBytecodeNode(), frame,
                PinnedMemory.readAddressArray(array, index, byteOffset));
        }
    }

    @Operation
    @ConstantOperand(type = ManagedAddressRead.class, name = "operation")
    @ConstantOperand(type = boolean.class, name = "byteOffset")
    @ConstantOperand(type = LocalAccessor.class, name = "destination")
    public static final class ReadManagedAddress {
        @Specialization(guards = "operation.isInt()") public static void readInt(VirtualFrame frame, ManagedAddressRead operation, boolean byteOffset,
                LocalAccessor destination, ManagedAddress address, long offset, Object state,
                @Bind("$node") Node node) {
            ManagedByteArray.requireState(state);
            destination.setInt(((BytecodeRoot) node.getRootNode()).getBytecodeNode(), frame,
                operation.readInt(address, offset, byteOffset));
        }
        @Specialization(guards = "!operation.isInt()") public static void read(VirtualFrame frame, ManagedAddressRead operation, boolean byteOffset,
                LocalAccessor destination, ManagedAddress address, long offset, Object state,
                @Bind("$node") Node node) {
            ManagedByteArray.requireState(state);
            long value = operation.read(address, offset, byteOffset);
            destination.setLong(((BytecodeRoot) node.getRootNode()).getBytecodeNode(), frame, value);
        }
    }

    @Operation @ConstantOperand(type = boolean.class, name = "byteOffset")
    @ConstantOperand(type = LocalAccessor.class, name = "destination")
    public static final class ReadFloatOffAddr {
        @Specialization public static void read(VirtualFrame frame, boolean byteOffset, LocalAccessor destination,
                ManagedAddress address, long index, Object state, @Bind("$node") Node node) {
            ManagedByteArray.requireState(state);
            destination.setFloat(((BytecodeRoot) node.getRootNode()).getBytecodeNode(), frame,
                FloatingAddresses.readFloat(address, index, byteOffset));
        }
    }
    @Operation @ConstantOperand(type = boolean.class, name = "byteOffset")
    @ConstantOperand(type = LocalAccessor.class, name = "destination")
    public static final class ReadDoubleOffAddr {
        @Specialization public static void read(VirtualFrame frame, boolean byteOffset, LocalAccessor destination,
                ManagedAddress address, long index, Object state, @Bind("$node") Node node) {
            ManagedByteArray.requireState(state);
            destination.setDouble(((BytecodeRoot) node.getRootNode()).getBytecodeNode(), frame,
                FloatingAddresses.readDouble(address, index, byteOffset));
        }
    }
    @Operation @ConstantOperand(type = boolean.class, name = "byteOffset")
    public static final class IndexFloatOffAddr {
        @Specialization public static float read(boolean byteOffset, ManagedAddress address, long index) {
            return FloatingAddresses.readFloat(address, index, byteOffset);
        }
    }
    @Operation @ConstantOperand(type = boolean.class, name = "byteOffset")
    public static final class IndexDoubleOffAddr {
        @Specialization public static double read(boolean byteOffset, ManagedAddress address, long index) {
            return FloatingAddresses.readDouble(address, index, byteOffset);
        }
    }
    @Operation @ConstantOperand(type = boolean.class, name = "byteOffset")
    public static final class WriteFloatOffAddr {
        @Specialization public static Object write(boolean byteOffset, ManagedAddress address, long index, float value, Object state) {
            ManagedByteArray.requireState(state);
            FloatingAddresses.writeFloat(address, index, value, byteOffset);
            return thc.runtime.Unit.INSTANCE;
        }
    }
    @Operation @ConstantOperand(type = boolean.class, name = "byteOffset")
    public static final class WriteDoubleOffAddr {
        @Specialization public static Object write(boolean byteOffset, ManagedAddress address, long index, double value, Object state) {
            ManagedByteArray.requireState(state);
            FloatingAddresses.writeDouble(address, index, value, byteOffset);
            return thc.runtime.Unit.INSTANCE;
        }
    }

    @Operation
    @ConstantOperand(type = ManagedAddressRead.class, name = "operation")
    @ConstantOperand(type = boolean.class, name = "byteOffset")
    public static final class IndexManagedAddress {
        @Specialization(guards = "operation.isInt()") public static int readInt(ManagedAddressRead operation, boolean byteOffset,
                ManagedAddress address, long offset) {
            return operation.readInt(address, offset, byteOffset);
        }
        @Specialization(guards = "!operation.isInt()") public static long read(ManagedAddressRead operation, boolean byteOffset,
                ManagedAddress address, long offset) {
            return operation.read(address, offset, byteOffset);
        }
    }

    @Operation
    public static final class WriteWord8OffAddr {
        @Specialization public static Object write(ManagedAddress address, long offset, int value, Object state) {
            ManagedByteArray.requireState(state);
            address.writeWord8Int(offset, value);
            return thc.runtime.Unit.INSTANCE;
        }
        @Specialization public static Object writeChar(ManagedAddress address, long offset, long value, Object state) {
            ManagedByteArray.requireState(state);
            address.writeWord8Int(offset, (int) value);
            return thc.runtime.Unit.INSTANCE;
        }
    }

    @Operation
    @ConstantOperand(type = boolean.class, name = "byteOffset")
    public static final class WriteWord16OffAddr {
        @Specialization public static Object write(boolean byteOffset, ManagedAddress address, long offset, int value, Object state) {
            ManagedByteArray.requireState(state);
            address.writeNativeInt(offset, 2, value, byteOffset);
            return thc.runtime.Unit.INSTANCE;
        }
    }

    @Operation
    @ConstantOperand(type = int.class, name = "width")
    @ConstantOperand(type = boolean.class, name = "byteOffset")
    public static final class WriteNativeScalarOffAddr {
        @Specialization public static Object writeInt(int width, boolean byteOffset, ManagedAddress address, long offset,
                int value, Object state) {
            ManagedByteArray.requireState(state);
            address.writeNativeInt(offset, width, value, byteOffset);
            return thc.runtime.Unit.INSTANCE;
        }
        @Specialization public static Object write(int width, boolean byteOffset, ManagedAddress address, long offset,
                long value, Object state) {
            ManagedByteArray.requireState(state);
            address.writeNativeScalar(offset, width, value, byteOffset);
            return thc.runtime.Unit.INSTANCE;
        }
    }

    @Operation
    @ConstantOperand(type = boolean.class, name = "byteOffset")
    public static final class AddressWrite {
        @Specialization public static Object write(boolean byteOffset, ManagedAddress address, long offset,
                ManagedAddress value, Object state) {
            ManagedByteArray.requireState(state);
            address.writeAddressElementIndex(offset, value, byteOffset);
            return thc.runtime.Unit.INSTANCE;
        }
        // Distinct typed operands share this address-mutation operation.
        @Specialization public static Object copy(boolean byteOffset, ManagedAddress source, ManagedAddress destination,
                long count, Object state) {
            ManagedByteArray.requireState(state);
            source.copyNonOverlappingTo(destination, count);
            return thc.runtime.Unit.INSTANCE;
        }
    }

    // Truffle Bytecode DSL requires Java specializations. Storage ownership,
    // ranges, pointer cells and native lifetimes remain in shared runtime code.
    @Operation
    public static final class CopyAddressToByteArray {
        @Specialization public static Object copy(ManagedAddress source, Object destination,
                long offset, long count, Object state) {
            TupleResults.requireVoidCarrier(state);
            source.copyToByteArray(destination, offset, count);
            return thc.runtime.Unit.INSTANCE;
        }
    }

    @Operation
    public static final class CopyByteArrayToAddress {
        @Specialization public static Object copy(Object source, long offset,
                ManagedAddress destination, long count, Object state) {
            TupleResults.requireVoidCarrier(state);
            destination.copyFromByteArray(source, offset, count);
            return thc.runtime.Unit.INSTANCE;
        }
    }

    @Operation
    @ConstantOperand(type = boolean.class, name = "byteOffset")
    public static final class WriteAddrArray {
        @Specialization public static Object write(boolean byteOffset, Object array, long index,
                ManagedAddress value, Object state) {
            ManagedByteArray.requireState(state);
            PinnedMemory.writeAddressArray(array, index, value, byteOffset);
            return thc.runtime.Unit.INSTANCE;
        }
    }

    @Operation public static final class MoveAddress {
        @Specialization public static Object move(ManagedAddress source, ManagedAddress destination,
                long count, Object state) {
            ManagedByteArray.requireState(state);
            source.moveTo(destination, count);
            return thc.runtime.Unit.INSTANCE;
        }
    }
    @Operation public static final class FillAddress {
        @Specialization public static Object fill(ManagedAddress destination, long count, long value, Object state) {
            ManagedByteArray.requireState(state);
            destination.fill(count, value);
            return thc.runtime.Unit.INSTANCE;
        }
    }

    @Operation
    @ConstantOperand(type = BytecodeProcessArguments.class, name = "arguments")
    @ConstantOperand(type = LocalAccessor.class, name = "destination")
    @ConstantOperand(type = LocalAccessor.class, name = "errno")
    public static final class OriginalProcess {
        @Specialization public static void execute(VirtualFrame frame, BytecodeProcessArguments arguments,
                LocalAccessor destination, LocalAccessor errno, @Bind Node node) {
            BytecodeNode bytecode = ((BytecodeRoot) node.getRootNode()).getBytecodeNode();
            long result = ManagedProcessForeign.current(node).invoke(arguments.getOperation(), arguments.read(bytecode, frame), node);
            destination.setInt(bytecode, frame, (int) result);
            errno.setLong(bytecode, frame, CoreOriginalStdio.current(node).errno());
        }
    }

    @Operation
    @ConstantOperand(type = TargetLayout.class, name = "layout")
    public static final class OriginalStackInfo {
        @Specialization public static ManagedAddress apply(TargetLayout layout, Object snapshot) {
            return ManagedStackRuntime.stackInfo(snapshot, layout);
        }
    }

    @Operation
    @ConstantOperand(type = TargetLayout.class, name = "layout")
    @ConstantOperand(type = LocalAccessor.class, name = "standard")
    @ConstantOperand(type = LocalAccessor.class, name = "key")
    public static final class OriginalStackFrameInfo {
        @Specialization public static void apply(VirtualFrame frame, TargetLayout layout,
                LocalAccessor standard, LocalAccessor key, Object snapshot, long offset,
                @Bind("$node") Node node) {
            ManagedStackFrameInfo result = ManagedStackRuntime.frameInfo(snapshot, offset, layout);
            BytecodeNode bytecode = ((BytecodeRoot) node.getRootNode()).getBytecodeNode();
            standard.setObject(bytecode, frame, result.getStandard());
            key.setObject(bytecode, frame, result.getKey());
        }
    }

    @Operation
    @ConstantOperand(type = TargetLayout.class, name = "layout")
    public static final class OriginalStackFields {
        @Specialization public static int apply(TargetLayout layout, Object snapshot) {
            return (int) ManagedStackRuntime.stackFields(snapshot, layout);
        }
    }

    @Operation
    @ConstantOperand(type = TargetLayout.class, name = "layout")
    @ConstantOperand(type = LocalAccessor.class, name = "bitmap")
    @ConstantOperand(type = LocalAccessor.class, name = "size")
    public static final class OriginalStackSmallBitmap {
        @Specialization public static void apply(VirtualFrame frame, TargetLayout layout,
                LocalAccessor bitmap, LocalAccessor size, Object snapshot, long offset,
                @Bind("$node") Node node) {
            ManagedStackBitmap result = ManagedStackRuntime.smallBitmap(snapshot, offset, layout);
            BytecodeNode bytecode = ((BytecodeRoot) node.getRootNode()).getBytecodeNode();
            bitmap.setLong(bytecode, frame, result.getBitmap());
            size.setLong(bytecode, frame, result.getSize());
        }
    }

    @Operation
    @ConstantOperand(type = TargetLayout.class, name = "layout")
    @ConstantOperand(type = LocalAccessor.class, name = "nextSnapshot")
    @ConstantOperand(type = LocalAccessor.class, name = "nextOffset")
    @ConstantOperand(type = LocalAccessor.class, name = "hasNext")
    public static final class OriginalStackAdvance {
        @Specialization public static void apply(VirtualFrame frame, TargetLayout layout,
                LocalAccessor nextSnapshot, LocalAccessor nextOffset, LocalAccessor hasNext,
                Object snapshot, long offset, @Bind("$node") Node node) {
            ManagedStackAdvance result = ManagedStackRuntime.advance(snapshot, offset, layout);
            BytecodeNode bytecode = ((BytecodeRoot) node.getRootNode()).getBytecodeNode();
            nextSnapshot.setObject(bytecode, frame, result.getSnapshot());
            nextOffset.setLong(bytecode, frame, result.getWordOffset());
            hasNext.setLong(bytecode, frame, result.getHasNext());
        }
    }

    @Operation
    @ConstantOperand(type = TargetLayout.class, name = "layout")
    @ConstantOperand(type = OriginalStackInfoOp.class, name = "operation")
    public static final class OriginalStackIncompatibleGetter {
        @Specialization public static Object apply(TargetLayout layout, OriginalStackInfoOp operation,
                Object snapshot, long offset) {
            return ManagedStackRuntime.incompatibleGetter(operation, snapshot, offset, layout);
        }
    }

    @Operation
    @ConstantOperand(type = TargetLayout.class, name = "layout")
    public static final class OriginalStackWord {
        @Specialization public static long apply(TargetLayout layout, Object snapshot, long offset) {
            return ManagedStackRuntime.word(snapshot, offset, layout);
        }
    }

    @Operation
    @ConstantOperand(type = TargetLayout.class, name = "layout")
    @ConstantOperand(type = OriginalStackInfoOp.class, name = "operation")
    public static final class OriginalStackIncompatibleTupleGetter {
        @Specialization public static void apply(TargetLayout layout, OriginalStackInfoOp operation,
                Object snapshot, long offset) {
            ManagedStackRuntime.incompatibleGetter(operation, snapshot, offset, layout);
        }
    }

    @Operation
    @ConstantOperand(type = TargetLayout.class, name = "layout")
    @ConstantOperand(type = LocalAccessor.class, name = "destination")
    public static final class OriginalStackLookupIpe {
        @Specialization public static void apply(VirtualFrame frame, TargetLayout layout,
                LocalAccessor destination, ManagedAddress key, ManagedAddress output, Object state,
                @Bind("$node") Node node) {
            TupleResults.requireVoidCarrier(state);
            long result = ManagedStackRuntime.lookupIpe(key, output, layout);
            destination.setInt(((BytecodeRoot) node.getRootNode()).getBytecodeNode(), frame, (int) result);
        }
    }

    // Both directions have the same typed operands. Keep one instruction family
    // below the BytecodeDSL partition limit; the direction is constant during PE.
    @Operation
    @ConstantOperand(type = LocalAccessor.class, name = "destination")
    @ConstantOperand(type = OriginalStdioOp.class, name = "operation")
    public static final class OriginalStdioTransfer {
        @Specialization public static void apply(VirtualFrame frame, LocalAccessor destination, OriginalStdioOp operation,
                long fd, ManagedAddress address, long count, Object state, @Bind("$node") Node node) {
            TupleResults.requireVoidCarrier(state);
            ManagedStdio stdio = CoreOriginalStdio.current(node);
            long result;
            if (operation.getOpening()) result = stdio.open(address, fd, count, operation, node);
            else if (operation == OriginalStdioOp.TCSETATTR) result = stdio.tcsetattr(fd, count, address);
            else if (operation.getReading())
                result = stdio.read(fd, address, count, ForeignSafety.synchronous(operation.getSafety()));
            else result = stdio.write(fd, address, count, ForeignSafety.synchronous(operation.getSafety()));
            if (operation.getNarrowResult() != null)
                destination.setInt(((BytecodeRoot) node.getRootNode()).getBytecodeNode(), frame, (int) result);
            else destination.setLong(((BytecodeRoot) node.getRootNode()).getBytecodeNode(), frame, result);
        }
    }

    @Operation
    @ConstantOperand(type = LocalAccessor.class, name = "destination")
    @ConstantOperand(type = CapiCall.class, name = "call")
    public static final class LinkedCapiZero {
        @Specialization public static void apply(VirtualFrame frame, LocalAccessor destination,
                CapiCall call, Object state, @Bind("$node") Node node) {
            TupleResults.requireVoidCarrier(state);
            long result = CoreCapiForeign.zero(node, call);
            if (call.timeClock()) destination.setInt(((BytecodeRoot) node.getRootNode()).getBytecodeNode(), frame, (int) result);
            else destination.setLong(((BytecodeRoot) node.getRootNode()).getBytecodeNode(), frame, result);
        }
    }

    @Operation
    @ConstantOperand(type = LocalAccessor.class, name = "destination")
    public static final class NativeMalloc {
        @Specialization public static void apply(VirtualFrame frame, LocalAccessor destination,
                long size, Object state, @Bind("$node") Node node) {
            TupleResults.requireVoidCarrier(state);
            ManagedAddress result = ManagedNativeAllocations.current(node).malloc(size);
            destination.setObject(((BytecodeRoot) node.getRootNode()).getBytecodeNode(), frame, result);
        }
    }

    @Operation
    @ConstantOperand(type = LocalAccessor.class, name = "destination")
    public static final class NativeRealloc {
        @Specialization public static void apply(VirtualFrame frame, LocalAccessor destination,
                ManagedAddress address, long size, Object state, @Bind("$node") Node node) {
            TupleResults.requireVoidCarrier(state);
            ManagedAddress result = ManagedNativeAllocations.current(node).realloc(address, size);
            destination.setObject(((BytecodeRoot) node.getRootNode()).getBytecodeNode(), frame, result);
        }
    }

    @Operation
    @ConstantOperand(type = BytecodePackageScalarArguments.class, name = "arguments")
    @ConstantOperand(type = LocalAccessor.class, name = "destination")
    public static final class LinkedPackageScalarInt {
        @Specialization public static void call(VirtualFrame frame, BytecodePackageScalarArguments arguments,
                LocalAccessor destination, @Bind("$node") Node node,
                @Cached(value = "createAccess(arguments)", neverDefault = true) PackageScalarAccess access) {
            BytecodeNode bytecode = ((BytecodeRoot) node.getRootNode()).getBytecodeNode();
            destination.setInt(bytecode, frame,
                    access.executeInt(arguments.read(bytecode, frame), arguments.state(bytecode, frame)));
        }
        public static PackageScalarAccess createAccess(BytecodePackageScalarArguments arguments) {
            return new PackageScalarAccess(arguments.getCall());
        }
    }

    @Operation
    @ConstantOperand(type = BytecodePackageScalarArguments.class, name = "arguments")
    @ConstantOperand(type = LocalAccessor.class, name = "destination")
    public static final class LinkedPackageScalarLong {
        @Specialization public static void call(VirtualFrame frame, BytecodePackageScalarArguments arguments,
                LocalAccessor destination, @Bind("$node") Node node,
                @Cached(value = "createAccess(arguments)", neverDefault = true) PackageScalarAccess access) {
            BytecodeNode bytecode = ((BytecodeRoot) node.getRootNode()).getBytecodeNode();
            destination.setLong(bytecode, frame,
                    access.executeLong(arguments.read(bytecode, frame), arguments.state(bytecode, frame)));
        }
        public static PackageScalarAccess createAccess(BytecodePackageScalarArguments arguments) {
            return new PackageScalarAccess(arguments.getCall());
        }
    }

    @Operation
    @ConstantOperand(type = BytecodePackageScalarArguments.class, name = "arguments")
    @ConstantOperand(type = LocalAccessor.class, name = "destination")
    public static final class LinkedPackageScalarFloat {
        @Specialization public static void call(VirtualFrame frame, BytecodePackageScalarArguments arguments,
                LocalAccessor destination, @Bind("$node") Node node,
                @Cached(value = "createAccess(arguments)", neverDefault = true) PackageScalarAccess access) {
            BytecodeNode bytecode = ((BytecodeRoot) node.getRootNode()).getBytecodeNode();
            destination.setFloat(bytecode, frame,
                    access.executeFloat(arguments.read(bytecode, frame), arguments.state(bytecode, frame)));
        }
        public static PackageScalarAccess createAccess(BytecodePackageScalarArguments arguments) {
            return new PackageScalarAccess(arguments.getCall());
        }
    }

    @Operation
    @ConstantOperand(type = BytecodePackageScalarArguments.class, name = "arguments")
    @ConstantOperand(type = LocalAccessor.class, name = "destination")
    public static final class LinkedPackageScalarDouble {
        @Specialization public static void call(VirtualFrame frame, BytecodePackageScalarArguments arguments,
                LocalAccessor destination, @Bind("$node") Node node,
                @Cached(value = "createAccess(arguments)", neverDefault = true) PackageScalarAccess access) {
            BytecodeNode bytecode = ((BytecodeRoot) node.getRootNode()).getBytecodeNode();
            destination.setDouble(bytecode, frame,
                    access.executeDouble(arguments.read(bytecode, frame), arguments.state(bytecode, frame)));
        }
        public static PackageScalarAccess createAccess(BytecodePackageScalarArguments arguments) {
            return new PackageScalarAccess(arguments.getCall());
        }
    }

    @Operation
    @ConstantOperand(type = String.class, name = "symbol")
    @ConstantOperand(type = CoreRepresentation.class, name = "proof")
    public static final class ResolveCFunctionLabel {
        @Specialization public static ManagedAddress resolve(String symbol, CoreRepresentation proof) {
            return CFinalizerLabels.fromCore(symbol, proof);
        }
    }

    @Operation
    @ConstantOperand(type = BytecodePackageScalarArguments.class, name = "arguments")
    @ConstantOperand(type = LocalAccessor.class, name = "destination")
    public static final class LinkedPackageAddress {
        @Specialization public static void call(VirtualFrame frame, BytecodePackageScalarArguments arguments,
                LocalAccessor destination, @Bind("$node") Node node,
                @Cached(value = "createAccess(arguments)", neverDefault = true) PackageScalarAccess access) {
            BytecodeNode bytecode = ((BytecodeRoot) node.getRootNode()).getBytecodeNode();
            destination.setObject(bytecode, frame,
                    access.executeAddress(arguments.read(bytecode, frame), arguments.state(bytecode, frame)));
        }
        public static PackageScalarAccess createAccess(BytecodePackageScalarArguments arguments) {
            return new PackageScalarAccess(arguments.getCall());
        }
    }

    @Operation
    @ConstantOperand(type = BytecodePackageScalarArguments.class, name = "arguments")
    public static final class LinkedPackageVoid {
        @Specialization public static void call(VirtualFrame frame, BytecodePackageScalarArguments arguments,
                @Bind("$node") Node node,
                @Cached(value = "createAccess(arguments)", neverDefault = true) PackageScalarAccess access) {
            BytecodeNode bytecode = ((BytecodeRoot) node.getRootNode()).getBytecodeNode();
            access.executeVoid(arguments.read(bytecode, frame), arguments.state(bytecode, frame));
        }
        public static PackageScalarAccess createAccess(BytecodePackageScalarArguments arguments) {
            return new PackageScalarAccess(arguments.getCall());
        }
    }

    @Operation
    public static final class NativeFree {
        @Specialization public static void apply(ManagedAddress address, Object state, @Bind("$node") Node node) {
            TupleResults.requireVoidCarrier(state);
            ManagedNativeAllocations.current(node).free(address);
        }
    }

    @Operation
    @ConstantOperand(type = LocalAccessor.class, name = "destination")
    public static final class OriginalMemmove {
        @Specialization public static void apply(VirtualFrame frame, LocalAccessor destination,
                ManagedAddress target, ManagedAddress source, long count, Object state, @Bind("$node") Node node) {
            TupleResults.requireVoidCarrier(state);
            destination.setObject(((BytecodeRoot) node.getRootNode()).getBytecodeNode(), frame,
                    source.moveTo(target, count));
        }
    }

    @Operation
    @ConstantOperand(type = LocalAccessor.class, name = "destination")
    public static final class OriginalMemcpy {
        @Specialization public static void apply(VirtualFrame frame, LocalAccessor destination,
                ManagedAddress target, ManagedAddress source, long count, Object state, @Bind("$node") Node node) {
            TupleResults.requireVoidCarrier(state);
            source.copyNonOverlappingTo(target, count);
            destination.setObject(((BytecodeRoot) node.getRootNode()).getBytecodeNode(), frame, target);
        }
    }

    @Operation
    @ConstantOperand(type = LocalAccessor.class, name = "destination")
    @ConstantOperand(type = CapiCall.class, name = "call")
    public static final class LinkedCapiWordAddress {
        @Specialization(guards = "!call.timeClock()") public static void apply(VirtualFrame frame, LocalAccessor destination,
                CapiCall call, long word, ManagedAddress address, Object state, @Bind("$node") Node node) {
            TupleResults.requireVoidCarrier(state);
            long result = CoreCapiForeign.wordAddress(node, call, word, address);
            destination.setInt(((BytecodeRoot) node.getRootNode()).getBytecodeNode(), frame, (int) result);
        }

        @Specialization(guards = "call.timeClock()") public static void narrow(VirtualFrame frame, LocalAccessor destination,
                CapiCall call, int word, ManagedAddress address, Object state, @Bind("$node") Node node) {
            TupleResults.requireVoidCarrier(state);
            int result = (int) CoreCapiForeign.wordAddress(node, call, word, address);
            destination.setInt(((BytecodeRoot) node.getRootNode()).getBytecodeNode(), frame, result);
        }
    }

    @Operation
    @ConstantOperand(type = LocalAccessor.class, name = "destination")
    @ConstantOperand(type = OriginalStdioOp.class, name = "operation")
    public static final class OriginalStdioStatus {
        @Specialization public static void apply(VirtualFrame frame, LocalAccessor destination,
                OriginalStdioOp operation, long fd, ManagedAddress address, Object state, @Bind("$node") Node node) {
            TupleResults.requireVoidCarrier(state);
            // One typed instruction avoids another generated-interpreter partition;
            // the exact operation is compile-time metadata, not a guest operand.
            if (operation == OriginalStdioOp.SET_ERRNO) {
                CoreOriginalStdio.current(node).setErrno(fd);
                return;
            }
            if (operation.getSavedTermios()) {
                ManagedAddress result = SavedTermios.execute(node, operation, fd, address);
                if (operation == OriginalStdioOp.GET_SAVED_TERMIOS)
                    destination.setObject(((BytecodeRoot) node.getRootNode()).getBytecodeNode(), frame, result);
                return;
            }
            if (operation.getTermios()) {
                if (operation == OriginalStdioOp.PTR_C_CC) {
                    destination.setObject(((BytecodeRoot) node.getRootNode()).getBytecodeNode(), frame, TermiosImage.pointer(address));
                } else {
                    long value = TermiosImage.scalar(operation, address, fd);
                    if (operation.getResult() != null)
                        if (operation.getNarrowResult() != null) destination.setInt(((BytecodeRoot) node.getRootNode()).getBytecodeNode(), frame, (int) value);
                        else destination.setLong(((BytecodeRoot) node.getRootNode()).getBytecodeNode(), frame, value);
                }
                return;
            }
            long result;
            if (operation.getWaitStatus()) result = CoreOriginalStdio.waitStatus(node, operation, fd);
            else if (operation.getStat()) result = PosixStat.execute(operation, address, fd);
            else if (operation == OriginalStdioOp.TCGETATTR) result = CoreOriginalStdio.current(node).tcgetattr(fd, address);
            else if (operation == OriginalStdioOp.FSTAT) result = CoreOriginalStdio.current(node).fstat(fd, address);
            else if (operation == OriginalStdioOp.UNLOCK) result = CoreOriginalStdio.locks(node).unlock(fd);
            else if (operation.getProcessIdentity()) result = ProcessIdentity.query(node, operation);
            else if (operation == OriginalStdioOp.ERRNO) result = CoreOriginalStdio.current(node).errno();
            else if (operation.getFlagConstant()) result = CoreOriginalStdio.current(node).flagConstant(operation);
            else if (operation.getSeekConstant()) result = CoreOriginalStdio.current(node).seekConstant(operation);
            else if (operation == OriginalStdioOp.ISATTY) result = CoreOriginalStdio.current(node).isTerminal(fd);
            else if (operation == OriginalStdioOp.CLOSE) result = CoreOriginalStdio.current(node).close(fd);
            else if (operation.getPathRemoval()) result = operation == OriginalStdioOp.RMDIR
                ? CoreOriginalStdio.current(node).removeDirectory(address) : CoreOriginalStdio.current(node).unlink(address);
            else if (operation == OriginalStdioOp.PIPE) result = CoreOriginalStdio.current(node).pipe(address);
            else if (operation == OriginalStdioOp.DUP) result = CoreOriginalStdio.current(node).duplicate(fd);
            else throw new RuntimeFault("Invalid original stdio status operation");
            if (operation.getNarrowResult() != null) destination.setInt(((BytecodeRoot) node.getRootNode()).getBytecodeNode(), frame, (int) result);
            else destination.setLong(((BytecodeRoot) node.getRootNode()).getBytecodeNode(), frame, result);
        }
    }

    @Operation
    @ConstantOperand(type = LocalAccessor.class, name = "destination")
    @ConstantOperand(type = OriginalStdioOp.class, name = "operation")
    public static final class OriginalStdioReady {
        @Specialization public static void apply(VirtualFrame frame, LocalAccessor destination,
                OriginalStdioOp operation, long fd, long writing, long milliseconds, long socket, Object state, @Bind("$node") Node node) {
            TupleResults.requireVoidCarrier(state);
            long result;
            if (operation == OriginalStdioOp.LOCK) result = CoreOriginalStdio.locks(node).lock(fd, writing, milliseconds, socket);
            else if (operation == OriginalStdioOp.EVENTFD) result = CoreOriginalStdio.current(node).eventfd(fd, writing);
            else if (operation == OriginalStdioOp.EVENTFD_WRITE) result = CoreOriginalStdio.current(node).eventfdWrite(fd, writing);
            else if (operation.getFcntl()) result = CoreOriginalStdio.current(node).fcntl(fd, writing, milliseconds,
                operation == OriginalStdioOp.FCNTL_WRITE);
            else if (operation.getReadiness()) result = CoreOriginalStdio.current(node).ready(fd, writing, milliseconds, socket, node);
            else throw new RuntimeFault("Invalid original four-scalar operation");
            destination.setInt(((BytecodeRoot) node.getRootNode()).getBytecodeNode(), frame, (int) result);
        }
    }

    @Operation
    @ConstantOperand(type = LocalAccessor.class, name = "destination")
    @ConstantOperand(type = OriginalStdioOp.class, name = "operation")
    public static final class OriginalEvent {
        @Specialization public static void apply(VirtualFrame frame, LocalAccessor destination,
                OriginalStdioOp operation, long first, long second, long third,
                ManagedAddress address, Object state, @Bind("$node") Node node) {
            TupleResults.requireVoidCarrier(state);
            ManagedStdio stdio = CoreOriginalStdio.current(node);
            long result;
            if (operation.getPoll()) result = stdio.poll(address, first, second, node, ForeignSafety.synchronous(operation.getSafety()));
            else if (operation.getEpollWait()) result = stdio.epollWait(first, address, second, third, node, ForeignSafety.synchronous(operation.getSafety()));
            else if (operation == OriginalStdioOp.EPOLL_CTL) result = stdio.epollControl(first, second, third, address);
            else if (operation == OriginalStdioOp.EPOLL_CREATE) result = stdio.epollCreate(first);
            else { stdio.controlFd(operation, first, second); return; }
            destination.setInt(((BytecodeRoot) node.getRootNode()).getBytecodeNode(), frame, (int) result);
        }
    }

    @Operation
    @ConstantOperand(type = LocalAccessor.class, name = "destination")
    public static final class FileOpen {
        @Specialization public static void apply(VirtualFrame frame, LocalAccessor destination,
                ManagedAddress path, long mode, Object state, @Bind("$node") Node node) {
            TupleResults.requireVoidCarrier(state);
            long result = CoreManagedFiles.current(node).open(path, mode, ForeignSafety.SAFE);
            destination.setLong(((BytecodeRoot) node.getRootNode()).getBytecodeNode(), frame, result);
        }
    }

    @Operation
    @ConstantOperand(type = LocalAccessor.class, name = "destination")
    public static final class CloneMyStack {
        @Specialization public static void capture(VirtualFrame frame, LocalAccessor destination,
                Object state, @Bind("$node") Node node) {
            TupleResults.requireVoidCarrier(state);
            // The current operation's frame is required, not a caller FrameInstance.
            ManagedStackSnapshot snapshot = ManagedStackSnapshot.capture(node, frame);
            destination.setObject(((BytecodeRoot) node.getRootNode()).getBytecodeNode(), frame, snapshot);
        }
    }

    @Operation
    @ConstantOperand(type = LocalAccessor.class, name = "destination")
    public static final class FileRead {
        @Specialization public static void apply(VirtualFrame frame, LocalAccessor destination,
                long fd, ManagedAddress address, long count, Object state, @Bind("$node") Node node) {
            TupleResults.requireVoidCarrier(state);
            long result = CoreManagedFiles.current(node).read(fd, address, count, ForeignSafety.SAFE);
            destination.setLong(((BytecodeRoot) node.getRootNode()).getBytecodeNode(), frame, result);
        }
    }

    @Operation
    @ConstantOperand(type = LocalAccessor.class, name = "destination")
    public static final class FileWrite {
        @Specialization public static void apply(VirtualFrame frame, LocalAccessor destination,
                long fd, ManagedAddress address, long count, Object state, @Bind("$node") Node node) {
            TupleResults.requireVoidCarrier(state);
            long result = CoreManagedFiles.current(node).write(fd, address, count, ForeignSafety.SAFE);
            destination.setLong(((BytecodeRoot) node.getRootNode()).getBytecodeNode(), frame, result);
        }
    }

    @Operation
    @ConstantOperand(type = LocalAccessor.class, name = "destination")
    public static final class FileClose {
        @Specialization public static void apply(VirtualFrame frame, LocalAccessor destination,
                long fd, Object state, @Bind("$node") Node node) {
            TupleResults.requireVoidCarrier(state);
            long result = CoreManagedFiles.current(node).close(fd, ForeignSafety.SAFE);
            destination.setLong(((BytecodeRoot) node.getRootNode()).getBytecodeNode(), frame, result);
        }
    }

    @Operation
    @ConstantOperand(type = LocalAccessor.class, name = "destination")
    public static final class FileErrorKind {
        @Specialization public static void apply(VirtualFrame frame, LocalAccessor destination,
                Object state, @Bind("$node") Node node) {
            TupleResults.requireVoidCarrier(state);
            long result = CoreManagedFiles.current(node).errorKind();
            destination.setLong(((BytecodeRoot) node.getRootNode()).getBytecodeNode(), frame, result);
        }
    }

    @Operation
    @ConstantOperand(type = LocalAccessor.class, name = "destination")
    public static final class FileErrorMessage {
        @Specialization public static void apply(VirtualFrame frame, LocalAccessor destination,
                Object state, @Bind("$node") Node node) {
            TupleResults.requireVoidCarrier(state);
            ManagedAddress result = CoreManagedFiles.current(node).errorMessage();
            destination.setObject(((BytecodeRoot) node.getRootNode()).getBytecodeNode(), frame, result);
        }
    }

    @Operation
    @ConstantOperand(type = LocalAccessor.class, name = "destination")
    public static final class FileSeek {
        @Specialization public static void apply(VirtualFrame frame, LocalAccessor destination,
                long fd, long offset, long mode, Object state, @Bind("$node") Node node) {
            long result;
            if (state == OriginalStdioOp.SEEK) result = CoreOriginalStdio.current(node).seek(fd, offset, mode);
            else {
                TupleResults.requireVoidCarrier(state);
                result = CoreManagedFiles.current(node).seek(fd, offset, mode, ForeignSafety.SAFE);
            }
            destination.setLong(((BytecodeRoot) node.getRootNode()).getBytecodeNode(), frame, result);
        }
    }

    @Operation
    @ConstantOperand(type = LocalAccessor.class, name = "destination")
    public static final class FileSize {
        @Specialization public static void apply(VirtualFrame frame, LocalAccessor destination,
                long fd, Object state, @Bind("$node") Node node) {
            TupleResults.requireVoidCarrier(state);
            long result = CoreManagedFiles.current(node).size(fd, ForeignSafety.SAFE);
            destination.setLong(((BytecodeRoot) node.getRootNode()).getBytecodeNode(), frame, result);
        }
    }

    @Operation
    @ConstantOperand(type = LocalAccessor.class, name = "destination")
    public static final class FileSetSize {
        @Specialization public static void apply(VirtualFrame frame, LocalAccessor destination,
                long fd, long length, Object state, @Bind("$node") Node node) {
            long result;
            if (state == OriginalStdioOp.TRUNCATE) result = CoreOriginalStdio.current(node).truncate(fd, length);
            else if (state == OriginalStdioOp.DUP2) result = CoreOriginalStdio.current(node).duplicateTo(fd, length);
            else {
                TupleResults.requireVoidCarrier(state);
                result = CoreManagedFiles.current(node).setSize(fd, length, ForeignSafety.SAFE);
            }
            if (state == OriginalStdioOp.TRUNCATE || state == OriginalStdioOp.DUP2)
                destination.setInt(((BytecodeRoot) node.getRootNode()).getBytecodeNode(), frame, (int) result);
            else destination.setLong(((BytecodeRoot) node.getRootNode()).getBytecodeNode(), frame, result);
        }
    }

    @Operation
    @ConstantOperand(type = LocalAccessor.class, name = "destination")
    public static final class FileIsTerminal {
        @Specialization public static void apply(VirtualFrame frame, LocalAccessor destination,
                long fd, Object state, @Bind("$node") Node node) {
            TupleResults.requireVoidCarrier(state);
            long result = CoreManagedFiles.current(node).isTerminal(fd, ForeignSafety.SAFE);
            destination.setLong(((BytecodeRoot) node.getRootNode()).getBytecodeNode(), frame, result);
        }
    }

    @Operation
    @ConstantOperand(type = LocalAccessor.class, name = "destination")
    public static final class FileDeviceType {
        @Specialization public static void apply(VirtualFrame frame, LocalAccessor destination,
                long fd, Object state, @Bind("$node") Node node) {
            TupleResults.requireVoidCarrier(state);
            long result = CoreManagedFiles.current(node).deviceType(fd, ForeignSafety.SAFE);
            destination.setLong(((BytecodeRoot) node.getRootNode()).getBytecodeNode(), frame, result);
        }
    }

    @Operation
    @ConstantOperand(type = LocalAccessor.class, name = "destination")
    public static final class OriginalLocale {
        @Specialization public static void apply(VirtualFrame frame, LocalAccessor destination,
                Object state, @Bind("$node") Node node) {
            TupleResults.requireVoidCarrier(state);
            ManagedAddress result = CoreOriginalStdio.iconv(node).localeEncoding();
            destination.setObject(((BytecodeRoot) node.getRootNode()).getBytecodeNode(), frame, result);
        }
    }

    @Operation
    @ConstantOperand(type = LocalAccessor.class, name = "destination")
    public static final class OriginalChdir {
        @Specialization public static void apply(VirtualFrame frame, LocalAccessor destination,
                ManagedAddress path, Object state, @Bind("$node") Node node) {
            TupleResults.requireVoidCarrier(state);
            long result = CoreOriginalStdio.current(node).changeDirectory(path);
            destination.setInt(((BytecodeRoot) node.getRootNode()).getBytecodeNode(), frame, (int) result);
        }
    }

    @Operation
    @ConstantOperand(type = LocalAccessor.class, name = "destination")
    @ConstantOperand(type = OriginalStdioOp.class, name = "operation")
    public static final class OriginalWindowsEncoding {
        @Specialization public static void apply(VirtualFrame frame, LocalAccessor destination,
                OriginalStdioOp operation, long number, long value, ManagedAddress address, Object state, @Bind Node node) {
            TupleResults.requireVoidCarrier(state);
            WindowsCodePages windows = WindowsCodePages.current(node);
            BytecodeNode bytecode = ((BytecodeRoot) node.getRootNode()).getBytecodeNode();
            if (operation == OriginalStdioOp.WINDOWS_ERROR_MESSAGE)
                destination.setObject(bytecode, frame, windows.message(number));
            else if (operation == OriginalStdioOp.LOCAL_FREE)
                destination.setObject(bytecode, frame, windows.localFree(address));
            else if (operation == OriginalStdioOp.MAP_ERRNO) windows.setErrno();
            else {
                long result = operation == OriginalStdioOp.LAST_ERROR ? windows.error()
                    : operation == OriginalStdioOp.CODE_PAGE_INFO ? windows.info(number, address)
                    : operation == OriginalStdioOp.DBCS_LEAD_BYTE ? windows.leadByte(number, value)
                    : operation == OriginalStdioOp.MAP_ERRNO_VALUE ? windows.mapErrno(number)
                    : windows.codePage(operation == OriginalStdioOp.CONSOLE_CODE_PAGE);
                if (operation.getNarrowResult() != null) destination.setInt(bytecode, frame, (int) result);
                else destination.setLong(bytecode, frame, result);
            }
        }
    }

    @Operation
    @ConstantOperand(type = LocalAccessor.class, name = "destination")
    @ConstantOperand(type = OriginalStdioOp.class, name = "operation")
    public static final class OriginalUnix {
        @Specialization public static void apply(VirtualFrame frame, LocalAccessor destination,
                OriginalStdioOp operation, @Variadic Object[] arguments, @Bind Node node) {
            TupleResults.requireVoidCarrier(arguments[arguments.length - 1]);
            long result = NativeUnix.execute(operation, java.util.Arrays.copyOf(arguments, arguments.length - 1));
            var bytecode = ((BytecodeRoot) node.getRootNode()).getBytecodeNode();
            if (operation.getNarrowResult() == null) destination.setLong(bytecode, frame, result);
            else destination.setInt(bytecode, frame, (int) result);
        }
    }

    // The Truffle Bytecode DSL delegates to shared marshalling.
    @Operation
    @ConstantOperand(type = LocalAccessor.class, name = "destination")
    public static final class OriginalWindowsMultiByte {
        @Specialization public static void apply(VirtualFrame frame, LocalAccessor destination,
                long codePage, long flags, ManagedAddress input, long count, ManagedAddress output, long capacity,
                Object state, @Bind Node node) {
            TupleResults.requireVoidCarrier(state);
            long result = WindowsCodePages.current(node).multiByte(codePage, flags, input, count, output, capacity);
            destination.setInt(((BytecodeRoot) node.getRootNode()).getBytecodeNode(), frame, (int) result);
        }
    }

    @Operation
    @ConstantOperand(type = LocalAccessor.class, name = "destination")
    @ConstantOperand(type = ForeignSafety.class, name = "safety")
    public static final class OriginalWindowsWideChar {
        @Specialization public static void apply(VirtualFrame frame, LocalAccessor destination, ForeignSafety safety,
                long codePage, long flags, ManagedAddress input, long count, ManagedAddress output, long capacity,
                ManagedAddress defaultChar, ManagedAddress usedDefault, Object state, @Bind Node node) {
            TupleResults.requireVoidCarrier(state);
            long result = WindowsCodePages.current(node).wideChar(codePage, flags, input, count, output, capacity, defaultChar, usedDefault, safety);
            destination.setInt(((BytecodeRoot) node.getRootNode()).getBytecodeNode(), frame, (int) result);
        }
    }

    @Operation
    @ConstantOperand(type = LocalAccessor.class, name = "destination")
    @ConstantOperand(type = OriginalStdioOp.class, name = "operation")
    public static final class OriginalWindowsDirectory {
        @Specialization public static void apply(VirtualFrame frame, LocalAccessor destination,
                OriginalStdioOp operation, ManagedAddress first, ManagedAddress output, Object state, @Bind Node node) {
            TupleResults.requireVoidCarrier(state);
            WindowsDirectoryStreams streams = WindowsDirectoryStreams.current(node);
            if (operation == OriginalStdioOp.FIND_FIRST) {
                ManagedAddress result = streams.first(first, output);
                destination.setObject(((BytecodeRoot) node.getRootNode()).getBytecodeNode(), frame, result);
            } else {
                long result = operation == OriginalStdioOp.FIND_NEXT ? streams.next(first, output)
                    : operation == OriginalStdioOp.FIND_CLOSE ? streams.closeSearch(first) : streams.error();
                if (operation.getNarrowResult() != null) destination.setInt(((BytecodeRoot) node.getRootNode()).getBytecodeNode(), frame, (int) result);
                else destination.setLong(((BytecodeRoot) node.getRootNode()).getBytecodeNode(), frame, result);
            }
        }
    }

    @Operation
    @ConstantOperand(type = LocalAccessor.class, name = "destination")
    @ConstantOperand(type = OriginalStdioOp.class, name = "operation")
    public static final class OriginalDirectoryPointer {
        @Specialization public static void apply(VirtualFrame frame, LocalAccessor destination,
                OriginalStdioOp operation, ManagedAddress address, Object state, @Bind("$node") Node node) {
            TupleResults.requireVoidCarrier(state);
            ManagedAddress result = operation == OriginalStdioOp.OPENDIR
                ? CoreOriginalStdio.current(node).openDirectory(address)
                : CoreOriginalStdio.directories(node).name(address);
            destination.setObject(((BytecodeRoot) node.getRootNode()).getBytecodeNode(), frame, result);
        }
    }

    @Operation
    @ConstantOperand(type = LocalAccessor.class, name = "destination")
    public static final class OriginalFdopendir {
        @Specialization public static void apply(VirtualFrame frame, LocalAccessor destination,
                long fd, Object state, @Bind("$node") Node node) {
            TupleResults.requireVoidCarrier(state);
            ManagedAddress result = CoreOriginalStdio.current(node).openDirectoryFd(fd);
            destination.setObject(((BytecodeRoot) node.getRootNode()).getBytecodeNode(), frame, result);
        }
    }

    @Operation
    @ConstantOperand(type = LocalAccessor.class, name = "destination")
    public static final class OriginalReaddir {
        @Specialization public static void apply(VirtualFrame frame, LocalAccessor destination,
                ManagedAddress stream, ManagedAddress output, Object state, @Bind("$node") Node node) {
            TupleResults.requireVoidCarrier(state);
            long result = CoreOriginalStdio.directories(node).read(stream, output);
            destination.setInt(((BytecodeRoot) node.getRootNode()).getBytecodeNode(), frame, (int) result);
        }
    }

    @Operation
    @ConstantOperand(type = LocalAccessor.class, name = "destination")
    @ConstantOperand(type = OriginalStdioOp.class, name = "operation")
    public static final class OriginalDirectoryRelease {
        @Specialization public static void apply(VirtualFrame frame, LocalAccessor destination,
                OriginalStdioOp operation, ManagedAddress address, Object state, @Bind("$node") Node node) {
            TupleResults.requireVoidCarrier(state);
            if (operation == OriginalStdioOp.CLOSEDIR) {
                long result = CoreOriginalStdio.directories(node).closeStream(address);
                destination.setInt(((BytecodeRoot) node.getRootNode()).getBytecodeNode(), frame, (int) result);
            } else CoreOriginalStdio.directories(node).freeEntry(address);
        }
    }

    @Operation
    @ConstantOperand(type = LocalAccessor.class, name = "destination")
    public static final class OriginalGetcwd {
        @Specialization public static void apply(VirtualFrame frame, LocalAccessor destination,
                ManagedAddress output, long capacity, Object state, @Bind("$node") Node node) {
            TupleResults.requireVoidCarrier(state);
            ManagedAddress result = CoreOriginalStdio.current(node).currentDirectory(output, capacity);
            destination.setObject(((BytecodeRoot) node.getRootNode()).getBytecodeNode(), frame, result);
        }
    }

    @Operation
    @ConstantOperand(type = OriginalStdioOp.class, name = "operation")
    @ConstantOperand(type = LocalAccessor.class, name = "destination")
    public static final class OriginalPathPair {
        @Specialization public static void apply(VirtualFrame frame, OriginalStdioOp operation, LocalAccessor destination,
                ManagedAddress target, ManagedAddress path, Object state, @Bind("$node") Node node) {
            TupleResults.requireVoidCarrier(state);
            long result = CoreOriginalStdio.current(node).pathPair(operation, target, path);
            destination.setInt(((BytecodeRoot) node.getRootNode()).getBytecodeNode(), frame, (int) result);
        }
    }

    @Operation
    @ConstantOperand(type = LocalAccessor.class, name = "destination")
    public static final class OriginalReadlink {
        @Specialization public static void apply(VirtualFrame frame, LocalAccessor destination,
                ManagedAddress path, ManagedAddress output, long capacity, Object state, @Bind("$node") Node node) {
            TupleResults.requireVoidCarrier(state);
            long result = CoreOriginalStdio.current(node).readlink(path, output, capacity);
            destination.setInt(((BytecodeRoot) node.getRootNode()).getBytecodeNode(), frame, (int) result);
        }
    }

    @Operation
    @ConstantOperand(type = LocalAccessor.class, name = "destination")
    public static final class OriginalFstatAt {
        @Specialization public static void apply(VirtualFrame frame, LocalAccessor destination,
                long fd, ManagedAddress path, ManagedAddress image, long flags, Object state, @Bind("$node") Node node) {
            TupleResults.requireVoidCarrier(state);
            long result = CoreOriginalStdio.current(node).statAt(fd, path, image, flags);
            destination.setInt(((BytecodeRoot) node.getRootNode()).getBytecodeNode(), frame, (int) result);
        }
    }

    @Operation
    @ConstantOperand(type = LocalAccessor.class, name = "destination")
    public static final class OriginalUnlinkAt {
        @Specialization public static void apply(VirtualFrame frame, LocalAccessor destination,
                long fd, ManagedAddress path, long flags, Object state, @Bind("$node") Node node) {
            TupleResults.requireVoidCarrier(state);
            long result = CoreOriginalStdio.current(node).unlinkAt(fd, path, flags);
            destination.setInt(((BytecodeRoot) node.getRootNode()).getBytecodeNode(), frame, (int) result);
        }
    }

    @Operation
    @ConstantOperand(type = LocalAccessor.class, name = "destination")
    public static final class OriginalPathAccess {
        @Specialization public static void apply(VirtualFrame frame, LocalAccessor destination,
                ManagedAddress path, long mode, Object state, @Bind("$node") Node node) {
            TupleResults.requireVoidCarrier(state);
            long result = CoreOriginalStdio.current(node).access(path, mode);
            destination.setInt(((BytecodeRoot) node.getRootNode()).getBytecodeNode(), frame, (int) result);
        }
    }

    @Operation
    @ConstantOperand(type = LocalAccessor.class, name = "destination")
    @ConstantOperand(type = OriginalStdioOp.class, name = "operation")
    public static final class OriginalPathMode {
        @Specialization public static void apply(VirtualFrame frame, LocalAccessor destination, OriginalStdioOp operation,
                ManagedAddress path, long mode, Object state, @Bind("$node") Node node) {
            TupleResults.requireVoidCarrier(state);
            long result = CoreOriginalStdio.current(node).pathMode(operation, path, mode);
            destination.setInt(((BytecodeRoot) node.getRootNode()).getBytecodeNode(), frame, (int) result);
        }
    }

    @Operation
    @ConstantOperand(type = LocalAccessor.class, name = "destination")
    @ConstantOperand(type = OriginalStdioOp.class, name = "operation")
    public static final class OriginalPathStat {
        @Specialization public static void apply(VirtualFrame frame, LocalAccessor destination, OriginalStdioOp operation,
                ManagedAddress path, ManagedAddress image, Object state, @Bind("$node") Node node) {
            TupleResults.requireVoidCarrier(state);
            long result = CoreOriginalStdio.current(node).pathStat(operation, path, image);
            destination.setInt(((BytecodeRoot) node.getRootNode()).getBytecodeNode(), frame, (int) result);
        }
    }

    @Operation
    @ConstantOperand(type = LocalAccessor.class, name = "destination")
    public static final class OriginalIconvOpen {
        @Specialization public static void apply(VirtualFrame frame, LocalAccessor destination,
                ManagedAddress to, ManagedAddress from, Object state, @Bind("$node") Node node) {
            TupleResults.requireVoidCarrier(state);
            long result = CoreOriginalStdio.iconv(node).open(to, from);
            destination.setLong(((BytecodeRoot) node.getRootNode()).getBytecodeNode(), frame, result);
        }
    }

    @Operation
    @ConstantOperand(type = LocalAccessor.class, name = "destination")
    public static final class OriginalIconvClose {
        @Specialization public static void apply(VirtualFrame frame, LocalAccessor destination,
                long handle, Object state, @Bind("$node") Node node) {
            TupleResults.requireVoidCarrier(state);
            long result = CoreOriginalStdio.iconv(node).close(handle);
            destination.setInt(((BytecodeRoot) node.getRootNode()).getBytecodeNode(), frame, (int) result);
        }
    }

    @Operation
    @ConstantOperand(type = LocalAccessor.class, name = "destination")
    @ConstantOperand(type = OriginalStdioOp.class, name = "operation")
    public static final class OriginalIconv {
        @Specialization public static void apply(VirtualFrame frame, LocalAccessor destination, OriginalStdioOp operation,
                long handle, ManagedAddress input, ManagedAddress inputCount,
                ManagedAddress output, ManagedAddress outputCount, Object state, @Bind("$node") Node node) {
            TupleResults.requireVoidCarrier(state);
            long result = CoreOriginalStdio.iconv(node).convert(handle, input, inputCount, output, outputCount);
            if (operation.getNarrowResult() != null) destination.setInt(((BytecodeRoot) node.getRootNode()).getBytecodeNode(), frame, (int) result);
            else destination.setLong(((BytecodeRoot) node.getRootNode()).getBytecodeNode(), frame, result);
        }
    }

    @Operation
    @ConstantOperand(type = BytecodeVectorSlots.class, name = "slots")
    public static final class WriteVectorSlots {
        @Specialization public static void write(VirtualFrame frame, BytecodeVectorSlots slots,
                Object value, @Bind("$node") Node node) {
            slots.write(frame, (BytecodeRoot) node.getRootNode(), value);
        }
    }

    @Operation
    @ConstantOperand(type = BytecodeVectorSlots.class, name = "slots")
    public static final class ReadVectorSlots {
        @Specialization public static Object read(VirtualFrame frame, BytecodeVectorSlots slots,
                @Bind("$node") Node node) {
            return slots.read(frame, (BytecodeRoot) node.getRootNode());
        }
    }

    @Operation
    @ConstantOperand(type = BytecodeTypedInputSlots.class, name = "slots")
    public static final class RestoreTypedInput {
        @Specialization public static void restore(VirtualFrame frame, BytecodeTypedInputSlots slots,
                @Bind("$node") Node node) {
            slots.enter(frame, (BytecodeRoot) node.getRootNode());
        }
    }

    @Operation
    @ConstantOperand(type = BytecodeTypedInputSlots.class, name = "slots")
    public static final class RestoreTypedTail {
        @Specialization public static void restore(VirtualFrame frame, BytecodeTypedInputSlots slots,
                TailCall transfer, @Bind("$node") Node node) {
            slots.tail(frame, (BytecodeRoot) node.getRootNode(), transfer);
        }
    }

    /** Exact zero-prefix self classification; cloned roots retain the same body identity. */
    @Operation
    @ConstantOperand(type = int.class, name = "arity")
    public static final class IsTypedSelf {
        @Specialization(guards = "function.target == cachedTarget", limit = "3")
        public static boolean cached(int arity, Closure function, @Bind("$node") Node node,
                @Cached("function.target") RootCallTarget cachedTarget,
                @Cached("isSelf(node, cachedTarget)") boolean cachedSelf) {
            return cachedSelf && exact(arity, function);
        }
        @Specialization(replaces = "cached")
        public static boolean generic(int arity, Closure function, @Bind("$node") Node node) {
            return exact(arity, function) && IsSelf.isSelf(node, function.target);
        }
        public static boolean exact(int arity, Closure function) {
            return function.arity == arity && function.suppliedCount == 0 &&
                    function.supplied.length == 0 && function.typedSupplied == null;
        }
        public static boolean isSelf(Node node, RootCallTarget target) {
            return IsSelf.isSelf(node, target);
        }
    }

    @Operation
    @ConstantOperand(type = BytecodeTypedInputSlots.class, name = "slots")
    @ConstantOperand(type = BytecodeInputSource.class, name = "source")
    @ConstantOperand(type = Metrics.class, name = "metrics")
    public static final class TransferTypedSelf {
        @Specialization public static void transfer(VirtualFrame frame, BytecodeTypedInputSlots slots,
                BytecodeInputSource source, Metrics metrics, Closure function, @Bind("$node") Node node) {
            slots.self(frame, (BytecodeRoot) node.getRootNode(), source, function);
            if (metrics.getEnabled()) metrics.incrementSelfTailReentries();
        }
    }

    /** Only the function is a stack operand; aggregate fields stay in typed locals. */
    @Operation
    @ConstantOperand(type = BytecodeInputSource.class, name = "source")
    @ConstantOperand(type = boolean.class, name = "tail")
    @ConstantOperand(type = Metrics.class, name = "metrics")
    public static final class ApplyTypedInput {
        @Specialization public static Object apply(VirtualFrame frame, BytecodeInputSource source, boolean tail,
                Metrics metrics, Closure function, @Bind("$node") Node node,
                @Cached(value = "create(source, tail, metrics)", neverDefault = true) InputDispatch dispatch) {
            try {
                return tailResult(dispatch.execute(frame, function, null), function,
                        source.getLayout().getLogicalArity(), tail);
            } catch (AstCapture cut) {
                CompilerDirectives.transferToInterpreter();
                return captureTypedInput(frame.materialize(), function, source, null, cut, node, tail);
            } catch (TailCall transfer) {
                if (!tail || !((GuestRoot) node.getRootNode()).isSelf(transfer.getTarget())) throw transfer;
                if (metrics.getEnabled()) metrics.incrementSelfTailReentries();
                return transfer;
            } finally {
                BytecodeTypedInputSlots.clearSource(source, frame, (BytecodeRoot) node.getRootNode());
            }
        }
        public static InputDispatch create(BytecodeInputSource source, boolean tail, Metrics metrics) {
            return new InputDispatch(source, source.getLayout().getLogicalArity(), tail, metrics, null, 0);
        }
    }

    @Operation
    @ConstantOperand(type = BytecodeInputSource.class, name = "source")
    @ConstantOperand(type = BytecodeTupleSlots.class, name = "destination")
    @ConstantOperand(type = boolean.class, name = "tail")
    @ConstantOperand(type = Metrics.class, name = "metrics")
    public static final class ApplyTypedInputTuple {
        @Specialization public static Object apply(VirtualFrame frame, BytecodeInputSource source,
                BytecodeTupleSlots destination, boolean tail, Metrics metrics,
                Closure function, @Bind("$node") Node node,
                @Cached(value = "create(source, destination, tail, metrics)", neverDefault = true) InputDispatch dispatch) {
            boolean checkpoint = destination.getCapturesYield();
            MaskingState callerMask = checkpoint ? SynchronousMasking.current(node) : null;
            try {
                Object result = dispatch.execute(frame, function, null);
                if (checkpoint && SynchronousMasking.current(node) != callerMask) {
                    SynchronousMasking.set(node, callerMask);
                    throw new IllegalStateException("Completed typed tuple application did not restore its caller mask");
                }
                return result;
            } catch (AstCapture cut) {
                CompilerDirectives.transferToInterpreter();
                return captureTypedInput(frame.materialize(), function, source, destination, cut, node, tail);
            } catch (TailCall transfer) {
                if (!tail || !((GuestRoot) node.getRootNode()).isSelf(transfer.getTarget())) throw transfer;
                if (metrics.getEnabled()) metrics.incrementSelfTailReentries();
                return transfer;
            } catch (TupleCallYield yielded) {
                if (!checkpoint) throw yielded;
                if (tail) return tailTupleYield(function, source.getLayout().getLogicalArity(),
                        destination, yielded, node, callerMask);
                throw captureTupleCall(function, source.getLayout().getLogicalArity(),
                        destination, yielded, node, callerMask);
            } finally {
                BytecodeTypedInputSlots.clearSource(source, frame, (BytecodeRoot) node.getRootNode());
            }
        }
        public static InputDispatch create(BytecodeInputSource source, BytecodeTupleSlots destination,
                boolean tail, Metrics metrics) {
            return new InputDispatch(source, source.getLayout().getLogicalArity(), tail, metrics,
                    destination.getCapturesYield() ? new ContinuationTupleDestination(destination) : destination, 0);
        }
    }

    @TruffleBoundary(transferToInterpreterOnException = false)
    private static Object captureTypedInput(com.oracle.truffle.api.frame.MaterializedFrame frame, Closure function,
            BytecodeInputSource source, BytecodeTupleSlots destination, AstCapture cut, Node node, boolean tail) {
        BytecodeRoot root = (BytecodeRoot) node.getRootNode();
        // The operation's finally clears its scratch; the callback owns this cold copy.
        var savedFrame = DelimitedContinuations.copyContinuationFrame(frame);
        cut.enclose(steps -> new TypedInputResume(function, source, destination, root, steps));
        AstContinuation saved = cut.freeze(root, savedFrame);
        if (tail) return new AstTailYield(saved, root.getCallTarget());
        MaskingState mask = SynchronousMasking.current(node);
        throw new CapturedCallSuspension(new CallSegment(saved, mask, mask, destination == null ? null : destination.getShape()));
    }
    private static final class TypedInputResume implements AstResumeStep {
        private final Closure function;
        private final BytecodeInputSource source;
        private final BytecodeTupleSlots destination;
        private final BytecodeRoot root;
        private final java.util.ArrayDeque<AstResumeStep> steps;
        TypedInputResume(Closure function, BytecodeInputSource source, BytecodeTupleSlots destination, BytecodeRoot root, java.util.ArrayDeque<AstResumeStep> steps) {
            this.function = function; this.source = source; this.destination = destination; this.root = root; this.steps = steps;
        }
        @Override public Object resume(VirtualFrame frame, Object input) {
            boolean suspended = false;
            try {
                Object result;
                try { result = AstContinuations.resumeAstSteps(frame, steps, input); }
                catch (TupleCallYield yielded) {
                    // The operation's original catch scope unwound with strict preparation.
                    MaskingState mask = SynchronousMasking.current(root);
                    var captured = (CapturedCallSuspension) captureTupleCall(function, source.getLayout().getLogicalArity(),
                        destination, yielded, root, mask);
                    CallSegment segment = captured.getSegment();
                    throw new AstCapture(new CallSegmentSuspended(segment), mask).append(new AstResumeStep() {
                        @Override public Object resume(VirtualFrame saved, Object value) {
                            destination.consume(saved, root, AstControl.resumeChild(segment, root, value, this));
                            return null;
                        }
                    });
                }
                return destination == null ? result : TupleResults.ownedTupleResult(destination.finish(frame, root.getBytecodeNode()), destination.getShape());
            } catch (AstCapture cut) {
                suspended = true;
                throw cut.enclose(remaining -> new TypedInputResume(function, source, destination, root, remaining));
            } finally { if (!suspended) BytecodeTypedInputSlots.clearSource(source, frame, root); }
        }
    }

    /** Java declarations are required by the Truffle Bytecode DSL processor. */
    @Operation public static final class Prefetch {
        @Specialization public static Object hint(Object ignored, long offset, Object state) {
            TupleResults.requireVoidCarrier(state);
            return thc.runtime.Unit.INSTANCE;
        }
    }
    @Operation
    @ConstantOperand(type = TraceOp.class, name = "operation")
    public static final class TraceEvent {
        @Specialization public static Object trace(TraceOp operation, ManagedAddress address,
                long count, Object state, @Bind("$node") Node node) {
            TupleResults.requireVoidCarrier(state);
            RtsDiagnostics.trace(node, operation, address, count);
            return thc.runtime.Unit.INSTANCE;
        }
    }
    @Operation public static final class Touch {
        @Specialization public static Object preserve(Object kept, Object state) {
            return thc.runtime.Touch.preserve(kept, state);
        }
    }

    /** Invoke exactly one logical State argument, with a fence after real return/throw. */
    @Operation
    @ConstantOperand(type = Metrics.class, name = "metrics")
    public static final class KeepAlive {
        @Specialization public static Object apply(VirtualFrame frame, Metrics metrics,
                Object kept, Object state, Object function,
                @Cached(value = "create(metrics)", neverDefault = true) Dispatch dispatch,
                @Cached(value = "createForce(metrics)", neverDefault = true) Force force) {
            ManagedByteArray.requireState(state);
            try { return dispatch.execute(frame, RequireClosure.require(force.execute(frame, function)), new Object[]{thc.runtime.Unit.INSTANCE}); }
            finally { java.lang.ref.Reference.reachabilityFence(kept); }
        }
        public static Dispatch create(Metrics metrics) { return Dispatch.create(1, false, metrics); }
        public static Force createForce(Metrics metrics) { return new Force(metrics); }
    }

    @Operation
    @ConstantOperand(type = BytecodeTupleSlots.class, name = "destination")
    @ConstantOperand(type = Metrics.class, name = "metrics")
    public static final class KeepAliveTuple {
        @Specialization public static void apply(VirtualFrame frame, BytecodeTupleSlots destination,
                Metrics metrics, Object kept, Object state, Object function,
                @Cached(value = "create(destination, metrics)", neverDefault = true) TupleDispatch dispatch,
                @Cached(value = "createForce(metrics)", neverDefault = true) Force force) {
            ManagedByteArray.requireState(state);
            try { dispatch.execute(frame, RequireClosure.require(force.execute(frame, function)), new Object[]{thc.runtime.Unit.INSTANCE}); }
            finally { java.lang.ref.Reference.reachabilityFence(kept); }
        }
        public static TupleDispatch create(BytecodeTupleSlots destination, Metrics metrics) {
            return new TupleDispatch(destination, metrics, 1, false);
        }
        public static Force createForce(Metrics metrics) { return new Force(metrics); }
    }

    /** Tuple operands are scalar inputs; each dispatch arm consumes into typed locals. */
    @Operation
    @ConstantOperand(type = BytecodeTupleSlots.class, name = "destination")
    @ConstantOperand(type = int.class, name = "arity")
    @ConstantOperand(type = Metrics.class, name = "metrics")
    public static final class ApplyTuple {
        @Specialization public static void apply(VirtualFrame frame, BytecodeTupleSlots destination, int arity, Metrics metrics,
                Closure function, @Variadic Object[] arguments,
                @Cached(value = "create(destination, arity, metrics)", neverDefault = true) TupleDispatch dispatch) {
            dispatch.execute(frame, function, arguments);
        }
        public static TupleDispatch create(BytecodeTupleSlots destination, int arity, Metrics metrics) {
            return new TupleDispatch(destination, metrics, arity, false);
        }
    }

    /** Private non-tail tuple checkpoint. Ordinary calls still use ApplyTuple. */
    @Operation
    @ConstantOperand(type = BytecodeTupleSlots.class, name = "destination")
    @ConstantOperand(type = int.class, name = "arity")
    @ConstantOperand(type = Metrics.class, name = "metrics")
    public static final class ApplyTupleCheckpoint {
        @Specialization public static void apply(VirtualFrame frame, BytecodeTupleSlots destination, int arity, Metrics metrics,
                Closure function, @Variadic Object[] arguments, @Bind Node node,
                @Cached(value = "create(destination, arity, metrics)", neverDefault = true) TupleDispatch dispatch) {
            MaskingState callerMask = SynchronousMasking.current(node);
            try {
                dispatch.execute(frame, function, arguments);
                if (SynchronousMasking.current(node) != callerMask) {
                    SynchronousMasking.set(node, callerMask);
                    throw new IllegalStateException("Completed tuple application did not restore its caller mask");
                }
            }
            catch (TupleCallYield yielded) { throw captureTupleCall(function, arity, destination, yielded, node, callerMask); }
        }
        public static TupleDispatch create(BytecodeTupleSlots destination, int arity, Metrics metrics) {
            return new TupleDispatch(new ContinuationTupleDestination(destination), metrics, arity, false);
        }
    }

    /** Only an exact callee tuple root may become a resumable cold call segment. */
    @TruffleBoundary(transferToInterpreterOnException = false)
    private static RuntimeException captureTupleCall(Closure function, int arity, BytecodeTupleSlots destination,
            TupleCallYield yielded, Node node, MaskingState callerMask) {
        try {
            SavedGuestContinuation continuation = yielded.getContinuation();
            Object source = continuation.getSourceRoot();
            RootCallTarget target = yielded.getTail() ? yielded.getTailTarget() : function.target;
            if (function.arity != arity || target == null || !(source instanceof GuestRoot callee) ||
                    !callee.isSelf(target) ||
                    !callee.hasTupleResult(destination.getShape()) ||
                    !AsyncContinuations.isYieldMarker(continuation.getYielded()))
                throw new IllegalStateException("Tuple application returned an unrelated continuation");
            MaskingState parked = continuation.getYielded() instanceof CallSegmentSuspended suspended
                    ? suspended.getParkedActiveMask() : null;
            if (parked != null && SynchronousMasking.current(node) != callerMask)
                throw new IllegalStateException("Parked tuple application did not restore its caller mask");
            MaskingState active = parked != null ? parked : SynchronousMasking.current(node);
            return new CapturedCallSuspension(new CallSegment(continuation.getIdentity(), active, callerMask,
                    destination.getShape()));
        } finally { SynchronousMasking.set(node, callerMask); }
    }

    /** An exact tuple tail has no caller suffix and can return its trusted callee continuation. */
    @TruffleBoundary(transferToInterpreterOnException = false)
    private static Object tailTupleYield(Closure function, int arity, BytecodeTupleSlots destination,
            TupleCallYield yielded, Node node, MaskingState callerMask) {
        try {
            SavedGuestContinuation continuation = yielded.getContinuation();
            Object source = continuation.getSourceRoot();
            RootCallTarget target = yielded.getTail() ? yielded.getTailTarget() : function.target;
            if (function.arity != arity || target == null || !(source instanceof GuestRoot callee) ||
                    !callee.isSelf(target) || !callee.hasTupleResult(destination.getShape()) ||
                    !AsyncContinuations.isYieldMarker(continuation.getYielded()))
                throw new IllegalStateException("Tuple tail returned an unrelated continuation");
            if (SynchronousMasking.current(node) != callerMask)
                throw new IllegalStateException("Parked tuple tail did not restore its caller mask");
            return continuation.getIdentity() instanceof ContinuationResult bytecode
                    ? new TailYield(bytecode, target) : new AstTailYield(continuation, target);
        } finally { SynchronousMasking.set(node, callerMask); }
    }

    @Operation
    @ConstantOperand(type = BytecodeTupleSlots.class, name = "destination")
    @ConstantOperand(type = int.class, name = "arity")
    @ConstantOperand(type = Metrics.class, name = "metrics")
    public static final class TailApplyTuple {
        @Specialization public static Object apply(VirtualFrame frame, BytecodeTupleSlots destination, int arity, Metrics metrics,
                Closure function, @Variadic Object[] arguments, @Bind("$node") Node node,
                @Cached(value = "create(destination, arity, metrics)", neverDefault = true) TupleDispatch dispatch) {
            MaskingState callerMask = destination.getCapturesYield() ? SynchronousMasking.current(node) : null;
            try {
                dispatch.execute(frame, function, arguments);
                return null;
            } catch (TupleCallYield yielded) {
                return tailTupleYield(function, arity, destination, yielded, node, callerMask);
            } catch (TailCall transfer) {
                if (!((GuestRoot) node.getRootNode()).isSelf(transfer.getTarget())) throw transfer;
                if (metrics.getEnabled()) metrics.incrementSelfTailReentries();
                return transfer;
            }
        }
        public static TupleDispatch create(BytecodeTupleSlots destination, int arity, Metrics metrics) {
            return new TupleDispatch(destination.getCapturesYield()
                    ? new ContinuationTupleDestination(destination) : destination, metrics, arity, true);
        }
    }

    @Operation
    @ConstantOperand(type = BytecodeTupleSlots.class, name = "destination")
    @ConstantOperand(type = ArgumentLayout.class, name = "layout")
    @ConstantOperand(type = Metrics.class, name = "metrics")
    public static final class ApplyCompactTuple {
        @Specialization public static void apply(VirtualFrame frame, BytecodeTupleSlots destination, ArgumentLayout layout, Metrics metrics,
                Closure function, @Variadic Object[] arguments,
                @Cached(value = "create(destination, layout, metrics)", neverDefault = true) TupleDispatch dispatch) {
            dispatch.execute(frame, function, arguments);
        }
        public static TupleDispatch create(BytecodeTupleSlots destination, ArgumentLayout layout, Metrics metrics) {
            return new TupleDispatch(destination, metrics, layout.getLogicalArity(), false, layout);
        }
    }

    @Operation
    @ConstantOperand(type = BytecodeTupleSlots.class, name = "destination")
    @ConstantOperand(type = ArgumentLayout.class, name = "layout")
    @ConstantOperand(type = Metrics.class, name = "metrics")
    public static final class ApplyCompactTupleCheckpoint {
        @Specialization public static void apply(VirtualFrame frame, BytecodeTupleSlots destination,
                ArgumentLayout layout, Metrics metrics, Closure function, @Variadic Object[] arguments, @Bind Node node,
                @Cached(value = "create(destination, layout, metrics)", neverDefault = true) TupleDispatch dispatch) {
            MaskingState callerMask = SynchronousMasking.current(node);
            try {
                dispatch.execute(frame, function, arguments);
                if (SynchronousMasking.current(node) != callerMask) {
                    SynchronousMasking.set(node, callerMask);
                    throw new IllegalStateException("Completed compact tuple application did not restore its caller mask");
                }
            }
            catch (TupleCallYield yielded) {
                throw captureTupleCall(function, layout.getLogicalArity(), destination, yielded, node, callerMask);
            }
        }
        public static TupleDispatch create(BytecodeTupleSlots destination, ArgumentLayout layout, Metrics metrics) {
            return new TupleDispatch(new ContinuationTupleDestination(destination), metrics,
                    layout.getLogicalArity(), false, layout);
        }
    }

    @Operation
    @ConstantOperand(type = BytecodeTupleSlots.class, name = "destination")
    @ConstantOperand(type = ArgumentLayout.class, name = "layout")
    @ConstantOperand(type = Metrics.class, name = "metrics")
    public static final class TailApplyCompactTuple {
        @Specialization public static Object apply(VirtualFrame frame, BytecodeTupleSlots destination, ArgumentLayout layout, Metrics metrics,
                Closure function, @Variadic Object[] arguments, @Bind("$node") Node node,
                @Cached(value = "create(destination, layout, metrics)", neverDefault = true) TupleDispatch dispatch) {
            MaskingState callerMask = destination.getCapturesYield() ? SynchronousMasking.current(node) : null;
            try {
                dispatch.execute(frame, function, arguments);
                return null;
            } catch (TupleCallYield yielded) {
                return tailTupleYield(function, layout.getLogicalArity(), destination,
                        yielded, node, callerMask);
            } catch (TailCall transfer) {
                if (!((GuestRoot) node.getRootNode()).isSelf(transfer.getTarget())) throw transfer;
                if (metrics.getEnabled()) metrics.incrementSelfTailReentries();
                return transfer;
            }
        }
        public static TupleDispatch create(BytecodeTupleSlots destination, ArgumentLayout layout, Metrics metrics) {
            return new TupleDispatch(destination.getCapturesYield()
                    ? new ContinuationTupleDestination(destination) : destination,
                    metrics, layout.getLogicalArity(), true, layout);
        }
    }

    @Operation
    @ConstantOperand(type = BytecodeTupleSlots.class, name = "source")
    public static final class FinishTuple {
        @Specialization public static Object finish(VirtualFrame frame, BytecodeTupleSlots source,
                @Bind("$node") Node node) {
            return source.finish(frame, ((BytecodeRoot) node.getRootNode()).getBytecodeNode());
        }
    }

    @Operation
    public static final class RequireClosure {
        @Specialization public static Closure require(Object value) {
            if (value instanceof Closure closure) return closure;
            throw fail("Application of a non-function");
        }
    }

    /** Exact tail calls have no caller suffix; a nested yield carries its actual callee identity. */
    private static Object tailResult(Object result, Closure function, int supplied, boolean tail) {
        if (!tail || function.arity != supplied) return result;
        if (result instanceof ContinuationResult continuation) return new TailYield(continuation, function.target);
        if (result instanceof SavedGuestContinuation continuation) return new AstTailYield(continuation, function.target);
        return result;
    }

    /** Logical arity includes a PAP's remaining formals, not its physical prefix width. */
    @Operation
    public static final class ClosureArity {
        @Specialization public static long read(Closure function) { return function.arity; }
    }

    /** The saved-call protocol compares arity before consuming a logical prefix. */
    @Operation
    @ConstantOperand(type = int.class, name = "count")
    @ConstantOperand(type = boolean.class, name = "less")
    public static final class SavedCallArity {
        @Specialization public static boolean test(int count, boolean less, Closure function) {
            return less ? function.arity < count : function.arity == count;
        }
    }

    /** Checked reference identities give restored formals a concrete Graal stamp. */
    @Operation
    public static final class RequireData {
        @Specialization public static DataValue require(Object value) {
            if (value instanceof DataValue data) return data;
            throw fail("Expected constructor value");
        }
    }

    @Operation
    public static final class RequireAddress {
        @Specialization public static ManagedAddress require(Object value) {
            if (value instanceof ManagedAddress address) return address;
            throw fail("Expected a managed literal Addr#");
        }
    }

    @Operation
    @ConstantOperand(type = int.class, name = "arity")
    @ConstantOperand(type = boolean.class, name = "tail")
    @ConstantOperand(type = Metrics.class, name = "metrics")
    @ConstantOperand(type = boolean[].class, name = "evaluatedArguments")
    public static final class Apply {
        @Specialization public static Object apply(VirtualFrame frame, int arity, boolean tail,
                Metrics metrics, boolean[] evaluatedArguments, Closure function, @Variadic Object[] arguments,
                @Bind("$node") Node node,
                @Cached(value = "createDispatch(arity, tail, metrics, evaluatedArguments)", neverDefault = true) PreparedDispatch dispatch) {
            try {
                return tailResult(dispatch.execute(frame, function, arguments), function, arity, tail);
            } catch (TailCall call) {
                // A -> B -> ... -> A unwinds to the owning activation. The compiler
                // consumes this internal result and restores locals before a real
                // bytecode backedge. Never intercept a call with pending non-tail work.
                if (!tail || !((GuestRoot) node.getRootNode()).isSelf(call.getTarget())) throw call;
                if (metrics.getEnabled()) metrics.incrementSelfTailReentries();
                return call;
            }
        }
        public static PreparedDispatch createDispatch(int arity, boolean tail, Metrics metrics, boolean[] evaluatedArguments) {
            return new PreparedDispatch(arity, tail, metrics, evaluatedArguments, null);
        }
    }

    @Operation
    @ConstantOperand(type = ArgumentLayout.class, name = "layout")
    @ConstantOperand(type = boolean.class, name = "tail")
    @ConstantOperand(type = Metrics.class, name = "metrics")
    @ConstantOperand(type = boolean[].class, name = "evaluatedArguments")
    public static final class ApplyCompact {
        @Specialization public static Object apply(VirtualFrame frame, ArgumentLayout layout, boolean tail,
                Metrics metrics, boolean[] evaluatedArguments, Closure function, @Variadic Object[] arguments,
                @Bind("$node") Node node,
                @Cached(value = "createDispatch(layout, tail, metrics, evaluatedArguments)", neverDefault = true) PreparedDispatch dispatch) {
            try {
                return tailResult(dispatch.execute(frame, function, arguments), function,
                        layout.getLogicalArity(), tail);
            } catch (TailCall call) {
                // A -> B -> ... -> A unwinds to the owning activation. The compiler
                // consumes this internal result and restores locals before a real
                // bytecode backedge. Never intercept a call with pending non-tail work.
                if (!tail || !((GuestRoot) node.getRootNode()).isSelf(call.getTarget())) throw call;
                if (metrics.getEnabled()) metrics.incrementSelfTailReentries();
                return call;
            }
        }
        public static PreparedDispatch createDispatch(ArgumentLayout layout, boolean tail, Metrics metrics, boolean[] evaluatedArguments) {
            return new PreparedDispatch(layout.getLogicalArity(), tail, metrics, evaluatedArguments, layout);
        }
    }

    /** Internal control result of a matching-root tail bounce, never a guest value. */
    @Operation
    @ConstantOperand(type = boolean.class, name = "yielded")
    public static final class IsTailReentry {
        @Specialization public static boolean test(boolean yielded, Object value) {
            return yielded ? value instanceof TailYield || value instanceof AstTailYield : value instanceof TailCall;
        }
    }

    @Operation
    @ConstantOperand(type = int.class, name = "index")
    public static final class TailArgument {
        @Specialization public static Object read(int index, TailCall call) { return call.getArgs()[index]; }
    }

    @Operation
    @ConstantOperand(type = int.class, name = "arity")
    @ConstantOperand(type = int.class, name = "formalArity")
    public static final class IsSelf {
        // Classify the target after the same bounded guards used by application.
        // This lets PE fold the self classification after the guards instead
        // of walking target/root identity fields on every execution.
        @Specialization(guards = {"function.target == cachedTarget", "function.arity == cachedArity",
                "function.supplied.length == cachedPrefixSize"}, limit = "3")
        public static boolean cached(int arity, int formalArity, Closure function,
                @Bind("$node") Node node,
                @Cached("function.target") RootCallTarget cachedTarget,
                @Cached("function.arity") int cachedArity,
                @Cached("function.supplied.length") int cachedPrefixSize,
                @Cached("isSelf(node, cachedTarget)") boolean cachedSelf) {
            return cachedSelf && cachedArity == arity && cachedPrefixSize + arity == formalArity;
        }

        @Specialization(replaces = "cached")
        public static boolean generic(int arity, int formalArity, Closure function,
                @Bind("$node") Node node) {
            return function.arity == arity && function.supplied.length + arity == formalArity &&
                    isSelf(node, function.target);
        }

        public static boolean isSelf(Node node, RootCallTarget target) {
            // Cloned roots preserve GuestRoot.bodyIdentity, so cached results
            // remain valid for clones while environments stay ordinary operands.
            return ((GuestRoot) node.getRootNode()).isSelf(target);
        }
    }

    @Operation
    public static final class ClosureEnvironment {
        @Specialization public static CapturedFrame environment(Closure function) { return function.environment; }
    }

    @Operation
    @ConstantOperand(type = int.class, name = "index")
    public static final class ReadSupplied {
        @Specialization public static Object read(int index, Closure function) { return function.supplied[index]; }
    }

    @Operation
    @ConstantOperand(type = DataLayout.class, name = "layout")
    public static final class Construct {
        @Specialization public static DataValue create(DataLayout layout, @Variadic Object[] fields) {
            return layout.create(fields);
        }
    }

    /** Vector heap fields copy lanes from raw-vector locals before publication. */
    @Operation
    @ConstantOperand(type = DataLayout.class, name = "layout")
    public static final class AllocateData {
        @Specialization public static DataValue allocate(DataLayout layout) { return layout.allocate(); }
    }

    @Operation
    @ConstantOperand(type = DataLayout.class, name = "layout")
    @ConstantOperand(type = int.class, name = "index")
    public static final class InitializeDataScalar {
        @Specialization(guards = "layout.isInt(index)")
        public static void integer(DataLayout layout, int index, DataValue value, int field) {
            layout.initializeInt(value, index, field);
        }
        @Specialization(guards = "layout.isLong(index)")
        public static void number(DataLayout layout, int index, DataValue value, long field) {
            layout.initializeLong(value, index, field);
        }
        @Specialization(guards = "layout.isFloat(index)")
        public static void floating(DataLayout layout, int index, DataValue value, float field) {
            layout.initializeFloat(value, index, field);
        }
        @Specialization(guards = "layout.isDouble(index)")
        public static void doubleValue(DataLayout layout, int index, DataValue value, double field) {
            layout.initializeDouble(value, index, field);
        }
        @Specialization(replaces = {"integer", "number", "floating", "doubleValue"})
        public static void object(DataLayout layout, int index, DataValue value, Object field) {
            layout.initialize(value, index, field);
        }
    }

    /** A constant descriptor lets partial evaluation see each bytecode local. */
    public static final class DataVectorTransfer {
        public final DataLayout layout;
        public final int index;
        public final boolean initialize;
        @CompilerDirectives.CompilationFinal(dimensions = 1)
        public final LocalAccessor[] lanes;
        public DataVectorTransfer(DataLayout layout, int index, LocalAccessor[] lanes, boolean initialize) {
            this.layout = layout;
            this.index = index;
            this.lanes = lanes;
            this.initialize = initialize;
        }
    }

    @Operation
    @ConstantOperand(type = DataVectorTransfer.class, name = "descriptor")
    public static final class TransferDataVector {
        @Specialization public static void transfer(VirtualFrame frame, DataVectorTransfer descriptor,
                DataValue value, @Bind("$node") Node node) {
            BytecodeNode bytecode = ((BytecodeRoot) node.getRootNode()).getBytecodeNode();
            if (descriptor.initialize) descriptor.layout.initializeVector(value, descriptor.index, bytecode,
                    frame, descriptor.lanes, 0);
            else descriptor.layout.restoreVector(value, descriptor.index, bytecode, frame, descriptor.lanes, 0);
        }
    }

    @Operation
    @ConstantOperand(type = DataLayout.class, name = "layout")
    public static final class MatchData {
        @Specialization public static boolean matches(DataLayout layout, Object value) {
            return layout.matches(value);
        }
    }

    /** Only emitted after checked restoration of a proved-data case binder. */
    @Operation
    @ConstantOperand(type = DataLayout.class, name = "layout")
    public static final class MatchDataValue {
        @Specialization public static boolean matches(DataLayout layout, DataValue value) {
            return layout.matches(value);
        }
    }

    @Operation
    @ConstantOperand(type = Object.class, name = "literal")
    public static final class MatchLiteral {
        @Specialization public static boolean integer(Object literal, int value) {
            return literal instanceof Integer number && number.intValue() == value;
        }
        @Specialization public static boolean number(Object literal, long value) {
            return literal instanceof Long number && number.longValue() == value;
        }
        @Specialization(guards = {"!isLong(value)", "!isInt(value)"}) public static boolean object(Object literal, Object value) {
            // Lowering rejects floating/BigNat alternatives. The remaining
            // ManagedAddress literal carrier has identity equality, as in AST
            // cases; do not expose arbitrary Object.equals to guest compilation.
            return literal == value;
        }
        public static boolean isInt(Object value) { return value instanceof Integer; }
        public static boolean isLong(Object value) { return value instanceof Long; }
    }

    /** Internal ordering of exact integral case labels, including Word# bit patterns. */
    @Operation
    @ConstantOperand(type = long.class, name = "pivot")
    public static final class LiteralBelow {
        @Specialization public static boolean test(long pivot, long value) { return value < pivot; }
    }

    /** Exact wide-integral guard for a statically prepared recovery decision. */
    @Operation
    @ConstantOperand(type = long.class, name = "literal")
    public static final class LiteralEqual {
        @Specialization public static boolean test(long literal, Object value) { return literal == (Long) value; }
    }

    @Operation
    @ConstantOperand(type = DataLayout.class, name = "layout")
    @ConstantOperand(type = int.class, name = "index")
    public static final class ReadDataField {
        @Specialization(guards = "layout.isInt(index)")
        public static int integer(DataLayout layout, int index, DataValue value) { return layout.readInt(value, index); }
        @Specialization(guards = "layout.isFloat(index)")
        public static float floating(DataLayout layout, int index, DataValue value) { return layout.readFloat(value, index); }
        @Specialization(guards = "layout.isDouble(index)")
        public static double doubleValue(DataLayout layout, int index, DataValue value) { return layout.readDouble(value, index); }
        @Specialization(guards = "layout.isLong(index)")
        public static long number(DataLayout layout, int index, DataValue value) { return layout.readLong(value, index); }
        @Specialization(guards = {"!layout.isInt(index)", "!layout.isLong(index)", "!layout.isFloat(index)", "!layout.isDouble(index)"})
        public static Object object(DataLayout layout, int index, DataValue value) { return layout.read(value, index); }
    }

    /** Restore an immutable constructor slot without observing a DSL specialization first. */
    @Operation
    @ConstantOperand(type = DataLayout.class, name = "layout")
    @ConstantOperand(type = int.class, name = "index")
    @ConstantOperand(type = LocalAccessor.class, name = "destination")
    public static final class RestoreDataScalar {
        @Specialization public static void restore(VirtualFrame frame, DataLayout layout, int index,
                LocalAccessor destination, DataValue value, @Bind("$bytecodeNode") BytecodeNode bytecode) {
            if (layout.isInt(index)) destination.setInt(bytecode, frame, layout.readInt(value, index));
            else if (layout.isLong(index)) destination.setLong(bytecode, frame, layout.readLong(value, index));
            else if (layout.isFloat(index)) destination.setFloat(bytecode, frame, layout.readFloat(value, index));
            else if (layout.isDouble(index)) destination.setDouble(bytecode, frame, layout.readDouble(value, index));
            else destination.setObject(bytecode, frame, layout.read(value, index));
        }
    }

    @Operation
    @ConstantOperand(type = String.class, name = "message")
    @ConstantOperand(type = Metrics.class, name = "metrics")
    public static final class Unsupported {
        @Specialization public static Object trap(String message, Metrics metrics) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            metrics.incrementUnsupportedTraps();
            throw fail("Diagnostic unsupported path reached: " + message);
        }
    }

    @Operation
    @ConstantOperand(type = String.class, name = "message")
    @ConstantOperand(type = Metrics.class, name = "metrics")
    public static final class UnsupportedForeign {
        @Specialization public static Object trap(String message, Metrics metrics) {
            return UnsupportedForeignCall.trap(message, metrics);
        }
    }

    @Operation
    public static final class FailCase {
        @Specialization public static Object failCase() { throw fail("Non-exhaustive Core case"); }
    }

    @Operation
    @ConstantOperand(type = boolean.class, name = "someException")
    public static final class Raise {
        @Specialization public static Object raise(boolean someException, Object payload, @Bind("$node") Node node) {
            return throwGuest(payload, node, someException);
        }
        // A guest raise is expected control flow, not a failed caller speculation.
        @TruffleBoundary(transferToInterpreterOnException = false) private static Object throwGuest(Object payload, Node node, boolean someException) { throw new GuestException(payload, node, someException); }
    }

    @Operation
    @ConstantOperand(type = boolean.class, name = "someException")
    public static final class RaiseIO {
        @Specialization public static void raise(boolean someException, Object payload, Object state, @Bind("$node") Node node) {
            TupleResults.requireVoidCarrier(state);
            throw new GuestException(payload, node, someException);
        }
    }

    @Operation public static final class RequireIOState {
        @Specialization public static void check(Object state) { TupleResults.requireVoidCarrier(state); }
    }

    /** Truffle Bytecode DSL declarations share transaction algorithms and callback
     * boundaries with the AST. */
    @Operation
    @ConstantOperand(type = STMOp.class, name = "operation")
    @ConstantOperand(type = BytecodeTupleSlots.class, name = "destination")
    @ConstantOperand(type = Metrics.class, name = "metrics")
    @ConstantOperand(type = boolean.class, name = "async")
    public static final class InvokeSTM {
        @Specialization public static void run(VirtualFrame frame, STMOp operation,
                BytecodeTupleSlots destination, Metrics metrics, boolean async, Object action, Object alternative,
                Object nested, Object state, Object blocked,
                @Bind Node node,
                @Cached(value = "create(operation, destination, metrics, async)", neverDefault = true) STMCall call) {
            TupleResults.requireVoidCarrier(state);
            try {
                Object result = call.execute(frame, action, alternative, nested, blocked);
                if (call.captures()) destination.consume(frame, node, result);
            }
            catch (AstCapture cut) { throw captureSTM(frame.materialize(), destination, cut, node); }
        }
        @TruffleBoundary(transferToInterpreterOnException = false)
        private static RuntimeException captureSTM(com.oracle.truffle.api.frame.MaterializedFrame frame,
                BytecodeTupleSlots destination, AstCapture cut, Node node) {
            if (cut.asyncRequest() != null) return new STMRestart(cut.asyncRequest());
            MaskingState mask = SynchronousMasking.current(node);
            AstContinuation saved = cut.freeze((GuestRoot) node.getRootNode(), frame);
            return new CapturedCallSuspension(new CallSegment(saved, mask, mask, destination.getShape()));
        }
        public static STMCall create(STMOp operation, BytecodeTupleSlots destination, Metrics metrics, boolean async) {
            return new STMCall(operation, destination, metrics, async);
        }
    }
    /** Only an internal scope completion is resumed here; external STMRestart reaches the retry owner. */
    @Operation public static final class STMScopeSuspension {
        @Specialization public static Object capture(AbstractTruffleException failure) {
            if (failure instanceof CapturedCallSuspension captured) return CallSuspensionOnly.captured(captured.getSegment());
            throw failure;
        }
    }
    @Operation
    @ConstantOperand(type = STMOp.class, name = "operation")
    @ConstantOperand(type = LocalAccessor.class, name = "destination")
    public static final class TVarAccess {
        @Specialization public static void run(VirtualFrame frame, STMOp operation, LocalAccessor destination,
                Object value, Object state, @Bind Node node) {
            TupleResults.requireVoidCarrier(state);
            ManagedSTM stm = thc.Language.currentState(node).stm;
            Object result = switch (operation) {
                case NEW -> stm.newTVar(value);
                case READ -> stm.read(value);
                case READ_IO -> stm.readIO(value);
                case RETRY -> stm.retry();
                default -> throw new IllegalStateException("Not a TVar tuple operation");
            };
            destination.setObject(((BytecodeRoot) node.getRootNode()).getBytecodeNode(), frame, result);
        }
    }
    @Operation public static final class WriteTVar {
        @Specialization public static Object run(Object cell, Object value, Object state, @Bind Node node) {
            TupleResults.requireVoidCarrier(state);
            thc.Language.currentState(node).stm.write(cell, value);
            return thc.runtime.Unit.INSTANCE;
        }
    }

    /** Called inside a DSL TryCatch; its typed tuple destination is unchanged. */
    @Operation
    @ConstantOperand(type = BytecodeTupleSlots.class, name = "destination")
    @ConstantOperand(type = Metrics.class, name = "metrics")
    public static final class InvokeIOAction {
        @Specialization public static void run(VirtualFrame frame, BytecodeTupleSlots destination, Metrics metrics,
                Object action, Object prior,
                @Cached(value = "createAction(destination, metrics)", neverDefault = true) TupleDispatch actionCall,
                @Cached(value = "createForce(metrics)", neverDefault = true) Force force) {
            try {
                actionCall.execute(frame, RequireClosure.require(force.execute(frame, action)),
                        new Object[]{thc.runtime.Unit.INSTANCE});
            } catch (RuntimeException | Error failure) {
                // Generated DSL finally handlers see Truffle exceptions, but
                // host faults and control transfers bypass them. Preserve the
                // old Java finally contract on just that exceptional edge.
                if (prior instanceof MaskingState mask && !(failure instanceof AbstractTruffleException))
                    SynchronousMasking.set(force, mask);
                throw failure;
            }
        }
        public static TupleDispatch createAction(BytecodeTupleSlots destination, Metrics metrics) {
            return new TupleDispatch(destination, metrics, 1, false);
        }
        public static Force createForce(Metrics metrics) { return new Force(metrics); }
    }

    /** Private checkpoint variant: a yielded action is captured before tuple destination consumption. */
    @Operation
    @ConstantOperand(type = BytecodeTupleSlots.class, name = "destination")
    @ConstantOperand(type = Metrics.class, name = "metrics")
    public static final class InvokeIOActionCheckpoint {
        @Specialization public static void run(VirtualFrame frame, BytecodeTupleSlots destination, Metrics metrics,
                Object action, boolean caughtIOAction,
                @Bind Node node,
                @Cached(value = "createAction(destination, metrics)", neverDefault = true) TupleDispatch actionCall,
                @Cached(value = "createForce(metrics)", neverDefault = true) Force force) {
            Closure closure = RequireClosure.require(force.execute(frame, action));
            MaskingState callerMask = SynchronousMasking.current(node);
            try {
                actionCall.execute(frame, closure, new Object[]{thc.runtime.Unit.INSTANCE});
            } catch (TupleCallYield yielded) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                try {
                    SavedGuestContinuation continuation = yielded.getContinuation();
                    Object source = continuation.getSourceRoot();
                    RootCallTarget target = yielded.getTail() ? yielded.getTailTarget() : closure.target;
                    if (closure.arity != 1 || target == null || !(source instanceof GuestRoot callee) ||
                            !callee.isSelf(target) ||
                            !callee.hasTupleResult(destination.getShape()) ||
                            !AsyncContinuations.isYieldMarker(continuation.getYielded()))
                        throw new IllegalStateException("IO action returned an unrelated tuple continuation");
                    MaskingState parked = continuation.getYielded() instanceof CallSegmentSuspended suspended
                            ? suspended.getParkedActiveMask() : null;
                    if (parked != null && SynchronousMasking.current(node) != callerMask)
                        throw new IllegalStateException("Parked IO action did not restore its caller mask");
                    MaskingState active = parked != null ? parked : SynchronousMasking.current(node);
                    AsyncContinuations.deliverIfCaught(continuation, caughtIOAction, node);
                    throw new CapturedCallSuspension(new CallSegment(continuation.getIdentity(), active, callerMask,
                            destination.getShape(), caughtIOAction));
                } finally { SynchronousMasking.set(node, callerMask); }
            }
        }
        public static TupleDispatch createAction(BytecodeTupleSlots destination, Metrics metrics) {
            return new TupleDispatch(new ContinuationTupleDestination(destination), metrics, 1, false);
        }
        public static Force createForce(Metrics metrics) { return new Force(metrics); }
    }

    /** The private mask-action edge shares exact action capture but owns host-fault cleanup. */
    @Operation
    @ConstantOperand(type = BytecodeTupleSlots.class, name = "destination")
    @ConstantOperand(type = Metrics.class, name = "metrics")
    public static final class InvokeMaskedIOActionCheckpoint {
        @Specialization public static void run(VirtualFrame frame, BytecodeTupleSlots destination, Metrics metrics,
                Object action, MaskingState prior, @Bind Node node,
                @Cached(value = "createAction(destination, metrics)", neverDefault = true) TupleDispatch actionCall,
                @Cached(value = "createForce(metrics)", neverDefault = true) Force force) {
            try {
                InvokeIOActionCheckpoint.run(frame, destination, metrics, action, false, node, actionCall, force);
            } catch (RuntimeException | Error failure) {
                if (!(failure instanceof AbstractTruffleException)) SynchronousMasking.set(node, prior);
                throw failure;
            }
        }
        public static TupleDispatch createAction(BytecodeTupleSlots destination, Metrics metrics) {
            return InvokeIOActionCheckpoint.createAction(destination, metrics);
        }
        public static Force createForce(Metrics metrics) { return new Force(metrics); }
    }

    /** Copies a completed, owned tuple into the original caller frame exactly once. */
    @Operation
    @ConstantOperand(type = BytecodeTupleSlots.class, name = "destination")
    public static final class ResumeIOAction {
        @Specialization public static void resume(VirtualFrame frame, BytecodeTupleSlots destination,
                CallSegmentSuspended suspended, ChildResume resumed, @Bind Node node) {
            if (resumed.getFailure() != null) throw resumed.takeFailure();
            CallSegment segment = suspended.getSegment();
            if (segment.getTupleShape() != destination.getShape() || segment.getState() != 2 ||
                    segment.getValue() != resumed.getValue() || !(resumed.getValue() instanceof HandoffStorage owned))
                throw new IllegalStateException("IO action continuation lost its tuple update");
            destination.consume(frame, node, owned);
            resumed.takeValue();
        }
        @Specialization public static void deliver(BytecodeTupleSlots destination,
                CallSegmentSuspended suspended, PrivateIOUnwind delivery) {
            CallSegment segment = suspended.getSegment();
            if (delivery.getAction() != segment || !segment.getCaughtIOAction() ||
                    segment.getTupleShape() != destination.getShape())
                throw new IllegalStateException("Async delivery requires the exact captured catch# action");
            throw new CapturedAsyncDelivery(delivery.getPayload(), delivery.getRequest());
        }
        @Fallback public static void malformed(BytecodeTupleSlots destination, Object suspended, Object resumed) {
            throw new IllegalStateException("IO action continuation requires an owned ChildResume tuple");
        }
    }

    /** A cold tuple segment owns its producer result before crossing threads. */
    @Operation
    @ConstantOperand(type = BytecodeTupleSlots.class, name = "destination")
    public static final class ResumeTupleApplication {
        @Specialization public static void resumeDelimited(VirtualFrame frame, BytecodeTupleSlots destination,
                DelimitedCut cut, DelimitedResume resumed, @Bind Node node) {
            destination.consume(frame, node, resumed.get());
        }
        @Specialization public static void resume(VirtualFrame frame, BytecodeTupleSlots destination,
                CallSegmentSuspended suspended, ChildResume resumed, @Bind Node node) {
            if (resumed.getFailure() != null) throw resumed.takeFailure();
            CallSegment segment = suspended.getSegment();
            if (segment.getCaughtIOAction() || segment.getTupleShape() != destination.getShape() ||
                    segment.getState() != 2 || segment.getValue() != resumed.getValue() ||
                    !(resumed.getValue() instanceof HandoffStorage owned))
                throw new IllegalStateException("Tuple application continuation lost its result update");
            destination.consume(frame, node, owned);
            resumed.takeValue();
        }
        @Fallback public static void malformed(BytecodeTupleSlots destination, Object suspended, Object resumed) {
            throw new IllegalStateException("Tuple application continuation requires an owned ChildResume tuple");
        }
    }

    /** The bytecode handler admits only synchronous Haskell guest exceptions. */
    @Operation public static final class RequireGuestFailure {
        @Specialization public static Object payload(AbstractTruffleException failure) {
            if (failure instanceof GuestException guest) return guest.getPayload();
            throw failure;
        }
    }

    @Operation
    @ConstantOperand(type = Language.class, name = "language")
    @ConstantOperand(type = Metrics.class, name = "metrics")
    public static final class NewGhcBCO {
        @Specialization public static Closure create(Language language, Metrics metrics, Object code,
                Object literals, Object pointers, long arity, Object bitmap, Object state, @Bind Node node) {
            return GhcBCO.create(node, language, metrics, code, literals, pointers, arity, bitmap, state);
        }
    }

    @Operation public static final class MkApUpd0 {
        @Specialization public static Thunk create(Object value, @Bind Node node) {
            return GhcBCO.updating(node, value);
        }
    }

    @Operation public static final class NewPromptTag {
        @Specialization public static PromptTag create(Object state, @Bind Node node) {
            TupleResults.requireVoidCarrier(state);
            return new PromptTag(Language.currentState(node));
        }
    }

    @Operation
    @ConstantOperand(type = TupleShape.class, name = "shape")
    public static final class CaptureDelimited {
        @Specialization public static DelimitedCut capture(TupleShape shape, Object tag, Object handler,
                Object state, @Bind Node node) {
            TupleResults.requireVoidCarrier(state);
            return new DelimitedCut(DelimitedControl.tag(node, tag), handler, shape,
                    SynchronousMasking.current(node), node);
        }
    }

    @Operation public static final class DelimitedOnly {
        @Specialization public static Object capture(AbstractTruffleException failure) {
            if (failure instanceof DelimitedCut cut) return cut;
            if (failure instanceof CapturedCallSuspension captured) return CallSuspensionOnly.captured(captured.getSegment());
            throw failure;
        }
    }

    @Operation
    @ConstantOperand(type = BytecodeTupleSlots.class, name = "destination")
    public static final class ConsumeDelimited {
        @Specialization public static void consume(VirtualFrame frame, BytecodeTupleSlots destination,
                Object result, @Bind Node node) {
            destination.consume(frame, node, result instanceof DelimitedResume resumed ? resumed.get() : result);
        }
    }

    /** The Bytecode DSL delegates to the shared call site. */
    @Operation
    @ConstantOperand(type = String.class, name = "name")
    @ConstantOperand(type = TupleShape.class, name = "shape")
    @ConstantOperand(type = Language.class, name = "language")
    @ConstantOperand(type = Metrics.class, name = "metrics")
    public static final class DelimitedBoundary {
        @Specialization public static Object invoke(VirtualFrame frame, String name, TupleShape shape,
                Language language, Metrics metrics, Object first, Object second, Object state,
                @Cached(value = "create(language, metrics)", neverDefault = true) DelimitedActionSite site) {
            try { return switch (name) {
                case "annotateStack#" -> site.annotated(frame, first, second, state, shape);
                case "prompt#" -> site.prompt(frame, first, second, state, shape);
                case "catch#" -> site.caught(frame, first, second, state, shape);
                case "maskAsyncExceptions#" -> site.masked(frame, first, state, shape, MaskingState.MASKED_INTERRUPTIBLE);
                case "maskUninterruptible#" -> site.masked(frame, first, state, shape, MaskingState.MASKED_UNINTERRUPTIBLE);
                default -> site.masked(frame, first, state, shape, MaskingState.UNMASKED);
            }; } catch (AstCapture cut) {
                MaskingState mask = SynchronousMasking.current(site);
                AstContinuation saved = cut.freeze((GuestRoot) site.getRootNode(), frame.materialize());
                throw new CapturedCallSuspension(new CallSegment(saved, mask, mask, shape));
            }
        }
        public static DelimitedActionSite create(Language language, Metrics metrics) {
            return new DelimitedActionSite(language, metrics);
        }
    }

    /** catch# acknowledges both direct self-delivery and captured async delivery. */
    @Operation public static final class RequireCaughtIOFailure {
        @Specialization public static Object payload(AbstractTruffleException failure) {
            if (failure instanceof AsyncDelivery delivered) {
                delivered.getRequest().acknowledge();
                return delivered.getRequest().getPayload();
            }
            if (failure instanceof CapturedAsyncDelivery delivered) {
                if (delivered.getRequest() != null) delivered.getRequest().acknowledge();
                return delivered.getPayload();
            }
            return RequireGuestFailure.payload(failure);
        }
    }

    @Operation
    @ConstantOperand(type = BytecodeTupleSlots.class, name = "destination")
    @ConstantOperand(type = Metrics.class, name = "metrics")
    public static final class InvokeIOHandler {
        @Specialization public static void run(VirtualFrame frame, BytecodeTupleSlots destination, Metrics metrics,
                Object handler, Object payload, MaskingState prior,
                @Cached(value = "createHandler(destination, metrics)", neverDefault = true) TupleDispatch handlerCall,
                @Cached(value = "createForce(metrics)", neverDefault = true) Force force) {
            try {
                handlerCall.execute(frame, RequireClosure.require(force.execute(frame, handler)),
                        new Object[]{payload, thc.runtime.Unit.INSTANCE});
            } catch (RuntimeException | Error failure) {
                if (!(failure instanceof AbstractTruffleException)) SynchronousMasking.set(force, prior);
                throw failure;
            }
        }
        public static TupleDispatch createHandler(BytecodeTupleSlots destination, Metrics metrics) {
            return new TupleDispatch(destination, metrics, 2, false);
        }
        public static Force createForce(Metrics metrics) { return new Force(metrics); }
    }

    /** Private checkpoint variant: retain the original handler's tuple and mask update. */
    @Operation
    @ConstantOperand(type = BytecodeTupleSlots.class, name = "destination")
    @ConstantOperand(type = Metrics.class, name = "metrics")
    public static final class InvokeIOHandlerCheckpoint {
        @Specialization public static void run(VirtualFrame frame, BytecodeTupleSlots destination, Metrics metrics,
                Object handler, Object payload, MaskingState prior,
                @Bind Node node,
                @Cached(value = "createHandler(destination, metrics)", neverDefault = true) TupleDispatch handlerCall,
                @Cached(value = "createForce(metrics)", neverDefault = true) Force force) {
            MaskingState callerMask = SynchronousMasking.current(node);
            Closure closure = null;
            try {
                closure = RequireClosure.require(force.execute(frame, handler));
                handlerCall.execute(frame, closure, new Object[]{payload, thc.runtime.Unit.INSTANCE});
            } catch (TupleCallYield yielded) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                boolean captured = false;
                try {
                    SavedGuestContinuation continuation = yielded.getContinuation();
                    Object source = continuation.getSourceRoot();
                    RootCallTarget target = yielded.getTail() ? yielded.getTailTarget() : closure == null ? null : closure.target;
                    if (closure == null || closure.arity != 2 || target == null || !(source instanceof GuestRoot callee) ||
                            !callee.isSelf(target) ||
                            !callee.hasTupleResult(destination.getShape()) ||
                            !AsyncContinuations.isYieldMarker(continuation.getYielded()))
                        throw new IllegalStateException("IO handler returned an unrelated tuple continuation");
                    MaskingState parked = continuation.getYielded() instanceof CallSegmentSuspended suspended
                            ? suspended.getParkedActiveMask() : null;
                    if (parked != null && SynchronousMasking.current(node) != callerMask)
                        throw new IllegalStateException("Parked IO handler did not restore its caller mask");
                    MaskingState active = parked != null ? parked : SynchronousMasking.current(node);
                    CapturedCallSuspension escape = new CapturedCallSuspension(new CallSegment(continuation.getIdentity(),
                            active, callerMask, destination.getShape(), false));
                    captured = true;
                    throw escape;
                } finally { SynchronousMasking.set(node, captured ? callerMask : prior); }
            } catch (RuntimeException | Error failure) {
                if (!(failure instanceof AbstractTruffleException)) SynchronousMasking.set(node, prior);
                throw failure;
            }
        }
        public static TupleDispatch createHandler(BytecodeTupleSlots destination, Metrics metrics) {
            return new TupleDispatch(new ContinuationTupleDestination(destination), metrics, 2, false);
        }
        public static Force createForce(Metrics metrics) { return new Force(metrics); }
    }

    /** Return the caller mask so the DSL TryFinally can restore it. */
    @Operation public static final class EnterHandlerMask {
        @Specialization public static MaskingState enter(@Bind("$node") Node node) {
            MaskingState prior = SynchronousMasking.current(node);
            if (prior == MaskingState.UNMASKED)
                SynchronousMasking.set(node, MaskingState.MASKED_INTERRUPTIBLE);
            return prior;
        }
    }

    @Operation
    @ConstantOperand(type = MaskingState.class, name = "target")
    public static final class EnterMask {
        @Specialization public static MaskingState enter(MaskingState target, @Bind("$node") Node node) {
            MaskingState prior = SynchronousMasking.current(node);
            SynchronousMasking.set(node, target);
            return prior;
        }
    }

    @Operation public static final class RestoreMask {
        @Specialization public static void restore(MaskingState prior, @Bind("$node") Node node) {
            SynchronousMasking.set(node, prior);
        }
    }

    @Operation
    @ConstantOperand(type = LocalAccessor.class, name = "destination")
    public static final class GetMaskingState {
        @Specialization public static void run(VirtualFrame frame, LocalAccessor destination, Object state,
                @Bind("$node") Node node) {
            TupleResults.requireVoidCarrier(state);
            destination.setLong(((BytecodeRoot) node.getRootNode()).getBytecodeNode(), frame,
                    SynchronousMasking.current(node).getTag());
        }
    }

    @Operation
    @ConstantOperand(type = LocalAccessor.class, name = "destination")
    public static final class GetCurrentCCS {
        @Specialization public static void run(VirtualFrame frame, LocalAccessor destination, Object state,
                @Bind("$node") Node node) {
            TupleResults.requireVoidCarrier(state);
            destination.setObject(((BytecodeRoot) node.getRootNode()).getBytecodeNode(), frame,
                    ManagedAddress.nullAddress());
        }
    }

    @Operation public static final class ClosureSize {
        @Specialization public static long inspect(Object value) { return ClosureInspection.size(value); }
    }
    @Operation
    @ConstantOperand(type = LocalAccessor.class, name = "info")
    @ConstantOperand(type = LocalAccessor.class, name = "bytes")
    @ConstantOperand(type = LocalAccessor.class, name = "pointers")
    public static final class UnpackClosure {
        @Specialization public static void inspect(VirtualFrame frame, LocalAccessor info,
                LocalAccessor bytes, LocalAccessor pointers, Object value, @Bind("$node") Node node) {
            ClosureImage image = ClosureInspection.image(value);
            BytecodeNode bytecode = ((BytecodeRoot) node.getRootNode()).getBytecodeNode();
            info.setObject(bytecode, frame, thc.Language.currentState(node).closureInfo.address(image.getDescriptor()));
            bytes.setObject(bytecode, frame, ManagedByteArray.fromFreshBytes(image.getBytes()));
            pointers.setObject(bytecode, frame, image.getPointers());
        }
    }
    @Operation
    @ConstantOperand(type = LocalAccessor.class, name = "flag")
    @ConstantOperand(type = LocalAccessor.class, name = "value")
    public static final class GetApStackVal {
        @Specialization public static void inspect(VirtualFrame frame, LocalAccessor flag,
                LocalAccessor value, Object closure, long offset, @Bind("$node") Node node) {
            BytecodeNode bytecode = ((BytecodeRoot) node.getRootNode()).getBytecodeNode();
            flag.setLong(bytecode, frame, 0);
            value.setObject(bytecode, frame, closure);
        }
    }
    @Operation
    @ConstantOperand(type = LocalAccessor.class, name = "destination")
    public static final class WhereFrom {
        @Specialization public static void inspect(VirtualFrame frame, LocalAccessor destination,
                ManagedAddress buffer, Object state, @Bind("$node") Node node) {
            TupleResults.requireVoidCarrier(state);
            destination.setLong(((BytecodeRoot) node.getRootNode()).getBytecodeNode(), frame, 0);
        }
    }

    /** Force already grants each suspended thunk one evaluator across guest threads. */
    @Operation public static final class NoDuplicate {
        @Specialization public static Object preserve(Object state) {
            TupleResults.requireVoidCarrier(state);
            return thc.runtime.Unit.INSTANCE;
        }
    }

    @Operation public static final class YieldThread {
        @Specialization public static Object giveWay(Object state) {
            TupleResults.requireVoidCarrier(state);
            CoreYield.giveWay();
            return thc.runtime.Unit.INSTANCE;
        }
    }

    @Operation public static final class AddressToInt {
        @Specialization public static long convert(ManagedAddress address) { return address.toNativeBits(); }
        @Fallback public static long invalid(Object address) { throw fail("Expected an Addr# carrier"); }
    }
    @Operation public static final class IntToAddress {
        @Specialization public static ManagedAddress convert(long bits) { return NativeAddresses.current(null).recover(bits); }
        @Fallback public static ManagedAddress invalid(Object bits) { throw fail("Expected primitive Long"); }
    }
    @Operation public static final class AddressPlus {
        @Specialization public static ManagedAddress plus(ManagedAddress address, long displacement) { return address.plus(displacement); }
        @Fallback public static ManagedAddress invalid(Object address, Object displacement) {
            if (!(address instanceof ManagedAddress)) throw fail("Expected a managed literal Addr#");
            throw fail("Expected primitive Long");
        }
    }
    @Operation public static final class AddressMinus {
        @Specialization public static long subtract(ManagedAddress left, ManagedAddress right) { return left.difference(right); }
    }
    @Operation public static final class AddressRemainder {
        @Specialization public static long remainder(ManagedAddress address, long divisor) { return address.remainder(divisor); }
    }
    @Operation @ConstantOperand(type = boolean.class, name = "signed")
    public static final class AddressIndexByte {
        @Specialization public static int index(boolean signed, Object address, Object displacement) {
            if (!(address instanceof ManagedAddress managed)) throw fail("Expected a managed literal Addr#");
            if (!(displacement instanceof Long offset)) throw fail("Expected primitive Long");
            int value = managed.readWord8Int(offset);
            return signed ? (byte) value : value;
        }
    }
    @Operation @ConstantOperand(type = ManagedAddressRead.class, name = "operation")
    public static final class AddressIndexManagedScalar {
        // The constant operation selects the carrier; execution does not need
        // a previously observed DSL specialization to establish that fact.
        @Specialization public static Object index(ManagedAddressRead operation, Object address, Object element) {
            if (!(address instanceof ManagedAddress managed)) throw fail("Expected a managed literal Addr#");
            if (!(element instanceof Long offset)) throw fail("Expected primitive Long");
            if (operation.isInt()) return operation.readInt(managed, offset);
            return operation.read(managed, offset);
        }
    }
    @Operation public static final class AddressEqual {
        @Specialization public static long compare(ManagedAddress left, ManagedAddress right) {
            return left.sameLocation(right) ? 1L : 0L;
        }
        @Fallback public static long invalid(Object left, Object right) {
            throw fail("Expected managed Addr# operands");
        }
    }
    @Operation public static final class AddressNotEqual {
        @Specialization public static long compare(ManagedAddress left, ManagedAddress right) {
            return left.sameLocation(right) ? 0L : 1L;
        }
        @Fallback public static long invalid(Object left, Object right) {
            throw fail("Expected managed Addr# operands");
        }
    }

    @Operation
    @ConstantOperand(type = ManagedAddressOrder.class, name = "order")
    public static final class AddressOrder {
        @Specialization public static long compare(ManagedAddressOrder order,
                ManagedAddress left, ManagedAddress right) {
            return order.accepts(left.compareWithinAllocation(right)) ? 1L : 0L;
        }
        @Fallback public static long invalid(ManagedAddressOrder order, Object left, Object right) {
            throw fail("Expected managed Addr# operands");
        }
    }

    @Operation public static final class SparkEnabled {
        @Specialization public static boolean enabled(@Bind Node node) { return SparkPool.current(node).isEnabled(); }
    }
    @Operation public static final class ParSpark {
        @Specialization public static void hint(Object payload, @Bind Node node) { SparkPool.current(node).hint(node, payload); }
    }
    @Operation
    @ConstantOperand(type = LocalAccessor.class, name = "destination")
    public static final class SparkCount {
        @Specialization public static void count(VirtualFrame frame, LocalAccessor destination, @Bind Node node) {
            var bytecode = ((BytecodeRoot) node.getRootNode()).getBytecodeNode();
            destination.setLong(bytecode, frame, SparkPool.current(node).count());
        }
    }
    @Operation
    @ConstantOperand(type = LocalAccessor.class, name = "flag")
    @ConstantOperand(type = LocalAccessor.class, name = "payload")
    public static final class GetSpark {
        @Specialization public static void get(VirtualFrame frame, LocalAccessor flag, LocalAccessor payload,
                Object empty, @Bind Node node) {
            var thunk = SparkPool.current(node).poll();
            var bytecode = ((BytecodeRoot) node.getRootNode()).getBytecodeNode();
            flag.setLong(bytecode, frame, thunk == null ? 0L : 1L);
            payload.setObject(bytecode, frame, thunk == null ? empty : thunk);
        }
    }
    @Operation public static final class PrepareThreadDelay {
        @Specialization public static ThreadDelayToken prepare(long microseconds, Object state, @Bind Node node) {
            TupleResults.requireVoidCarrier(state);
            return new ThreadDelayToken(GuestThreads.current(node), microseconds);
        }
    }

    @Operation @ConstantOperand(type = boolean.class, name = "async")
    public static final class AwaitThreadDelay {
        @Specialization public static void await(boolean async, ThreadDelayToken token, @Bind Node node) {
            token.await(node, async, CompilerDirectives.inCompiledCode());
        }
    }

    @Operation @ConstantOperand(type = boolean.class, name = "other")
    public static final class SetThreadAllocationCounter {
        @Specialization public static void set(boolean other, long value, Object target, Object state, @Bind Node node) {
            TupleResults.requireVoidCarrier(state);
            GuestThreads threads = GuestThreads.current(node);
            if (other && !(target instanceof GuestThreadId)) throw fail("Allocation counter requires ThreadId#");
            threads.setAllocationCounter(value, other ? (GuestThreadId) target : threads.currentIdentity());
        }
    }

    @Operation
    @ConstantOperand(type = boolean.class, name = "writing")
    public static final class PrepareFileWait {
        @Specialization public static Object prepare(boolean writing, long fd, Object state,
                @Bind("$node") Node node) {
            return FileWaitPrimitives.prepareFileWait(fd, state, writing, node);
        }
    }

    @Operation
    @ConstantOperand(type = GlobalBinding.class, name = "payload")
    @ConstantOperand(type = boolean.class, name = "async")
    public static final class AwaitFileWait {
        @Specialization public static void await(GlobalBinding payload, boolean async, Object token,
                @Bind("$node") Node node) {
            // The native wait crosses a boundary; sample before leaving this
            // resumable bytecode operation, then tag only a claimed request.
            boolean compiledAtCut = CompilerDirectives.inCompiledCode();
            FileWaitPrimitives.awaitFileWait(token, payload, async, compiledAtCut, node);
        }
    }

    @Operation
    @ConstantOperand(type = LocalAccessor.class, name = "destination")
    public static final class NewMVar {
        @Specialization public static void create(VirtualFrame frame, LocalAccessor destination,
                Object state, @Bind("$node") Node node) {
            TupleResults.requireVoidCarrier(state);
            destination.setObject(((BytecodeRoot) node.getRootNode()).getBytecodeNode(), frame, new ManagedMVar());
        }
    }
    @Operation
    @ConstantOperand(type = LocalAccessor.class, name = "destination")
    @ConstantOperand(type = boolean.class, name = "remove")
    @ConstantOperand(type = boolean.class, name = "async")
    public static final class ReadMVar {
        @Specialization public static void read(VirtualFrame frame, LocalAccessor destination, boolean remove, boolean async,
                Object value, Object state, Object pending, Object blocked, @Bind("$node") Node node) {
            ManagedMVar cell = ManagedMVar.require(value);
            TupleResults.requireVoidCarrier(state);
            Object result = pending instanceof PendingWait wait ? wait.resume() : remove ? cell.take(node, async, blocked) : cell.read(node, async, blocked);
            destination.setObject(((BytecodeRoot) node.getRootNode()).getBytecodeNode(), frame, result);
        }
    }
    @Operation
    @ConstantOperand(type = LocalAccessor.class, name = "flag")
    @ConstantOperand(type = LocalAccessor.class, name = "destination")
    @ConstantOperand(type = boolean.class, name = "remove")
    public static final class TryReadMVar {
        @Specialization public static void read(VirtualFrame frame, LocalAccessor flag, LocalAccessor destination,
                boolean remove, Object value, Object state, @Bind("$node") Node node) {
            ManagedMVar cell = ManagedMVar.require(value);
            TupleResults.requireVoidCarrier(state);
            MVarReadResult result = remove ? cell.tryTake() : cell.tryRead();
            BytecodeNode bytecode = ((BytecodeRoot) node.getRootNode()).getBytecodeNode();
            flag.setLong(bytecode, frame, result.getPresent() ? 1L : 0L);
            destination.setObject(bytecode, frame, result.getValue());
        }
    }
    @Operation
    @ConstantOperand(type = boolean.class, name = "async")
    public static final class PutMVar {
        @Specialization public static Object put(boolean async, Object reference, Object value, Object state, Object pending, Object blocked,
                @Bind("$node") Node node) {
            ManagedMVar cell = ManagedMVar.require(reference);
            TupleResults.requireVoidCarrier(state);
            if (pending instanceof PendingWait wait) wait.resume(); else cell.put(value, node, async, blocked);
            return thc.runtime.Unit.INSTANCE;
        }
    }
    @Operation
    @ConstantOperand(type = LocalAccessor.class, name = "destination")
    public static final class TryPutMVar {
        @Specialization public static void put(VirtualFrame frame, LocalAccessor destination,
                Object reference, Object value, Object state, @Bind("$node") Node node) {
            ManagedMVar cell = ManagedMVar.require(reference);
            TupleResults.requireVoidCarrier(state);
            long result = cell.tryPut(value) ? 1L : 0L;
            destination.setLong(((BytecodeRoot) node.getRootNode()).getBytecodeNode(), frame, result);
        }
    }
    @Operation
    @ConstantOperand(type = LocalAccessor.class, name = "destination")
    public static final class IsEmptyMVar {
        @Specialization public static void empty(VirtualFrame frame, LocalAccessor destination,
                Object value, Object state, @Bind("$node") Node node) {
            ManagedMVar cell = ManagedMVar.require(value);
            TupleResults.requireVoidCarrier(state);
            long result = cell.isEmpty() ? 1L : 0L;
            destination.setLong(((BytecodeRoot) node.getRootNode()).getBytecodeNode(), frame, result);
        }
    }

    @Operation
    @ConstantOperand(type = LocalAccessor.class, name = "destination")
    public static final class NewMutVar {
        @Specialization public static void create(VirtualFrame frame, LocalAccessor destination,
                Object value, Object state, @Bind("$node") Node node) {
            TupleResults.requireVoidCarrier(state);
            destination.setObject(((BytecodeRoot) node.getRootNode()).getBytecodeNode(), frame, new ManagedMutVar(value));
        }
    }
    @Operation
    @ConstantOperand(type = LocalAccessor.class, name = "addressDestination")
    @ConstantOperand(type = LocalAccessor.class, name = "sizeDestination")
    @ConstantOperand(type = boolean.class, name = "first")
    public static final class ReadCompactBlock {
        @Specialization public static void execute(VirtualFrame frame, LocalAccessor addressDestination,
                LocalAccessor sizeDestination, boolean first, Object region, Object previous, Object state,
                @Bind("$node") Node node) {
            TupleResults.requireVoidCarrier(state);
            Language.State context = Language.currentState(node);
            ManagedCompact compact = context.compactRegions.require(region);
            if (!first && !(previous instanceof ManagedAddress)) throw fail("Expected a compact block Addr#");
            ManagedAddress address = first ? context.compactImages.first(compact)
                : context.compactImages.next(compact, (ManagedAddress) previous);
            BytecodeNode bytecode = ((BytecodeRoot) node.getRootNode()).getBytecodeNode();
            addressDestination.setObject(bytecode, frame, address);
            sizeDestination.setLong(bytecode, frame, address == ManagedAddress.nullAddress() ? 0L : address.availableBytes());
        }
    }
    @Operation
    @ConstantOperand(type = LocalAccessor.class, name = "destination")
    public static final class AllocateCompactBlock {
        @Specialization public static void execute(VirtualFrame frame, LocalAccessor destination,
                long size, ManagedAddress previous, Object state, @Bind("$node") Node node) {
            TupleResults.requireVoidCarrier(state);
            destination.setObject(((BytecodeRoot) node.getRootNode()).getBytecodeNode(), frame,
                    Language.currentState(node).compactImages.allocate(size, previous));
        }
    }
    @Operation
    @ConstantOperand(type = LocalAccessor.class, name = "regionDestination")
    @ConstantOperand(type = LocalAccessor.class, name = "rootDestination")
    public static final class FixupCompact {
        @Specialization public static void execute(VirtualFrame frame, LocalAccessor regionDestination,
                LocalAccessor rootDestination, ManagedAddress first, ManagedAddress oldRoot, Object state,
                @Bind("$node") Node node) {
            TupleResults.requireVoidCarrier(state);
            CompactImages.Fixed fixed = Language.currentState(node).compactImages.fixup(first, oldRoot);
            BytecodeNode bytecode = ((BytecodeRoot) node.getRootNode()).getBytecodeNode();
            regionDestination.setObject(bytecode, frame, fixed.getRegion());
            rootDestination.setObject(bytecode, frame, fixed.getRoot());
        }
    }
    @Operation
    @ConstantOperand(type = LocalAccessor.class, name = "destination")
    @ConstantOperand(type = boolean.class, name = "fromAddress")
    public static final class ObjectAddress {
        @Specialization public static void execute(VirtualFrame frame, LocalAccessor destination,
                boolean fromAddress, Object value, Object state, @Bind("$node") Node node) {
            TupleResults.requireVoidCarrier(state);
            HeapAddresses heap = Language.currentState(node).heapAddresses;
            if (fromAddress && !(value instanceof ManagedAddress)) throw fail("Expected a guest heap Addr#");
            destination.setObject(((BytecodeRoot) node.getRootNode()).getBytecodeNode(), frame,
                    fromAddress ? heap.dereference((ManagedAddress) value) : heap.address(value));
        }
    }
    @Operation
    @ConstantOperand(type = LocalAccessor.class, name = "destination")
    @ConstantOperand(type = CompactOp.class, name = "operation")
    public static final class InspectCompact {
        @Specialization public static void execute(VirtualFrame frame, LocalAccessor destination,
                CompactOp operation, Object argument, Object state, @Bind("$node") Node node) {
            TupleResults.requireVoidCarrier(state);
            ManagedCompacts registry = Language.currentState(node).compactRegions;
            BytecodeNode bytecode = ((BytecodeRoot) node.getRootNode()).getBytecodeNode();
            if (operation == CompactOp.NEW) {
                if (!(argument instanceof Long)) throw new RuntimeFault("Expected compact allocation size");
                destination.setObject(bytecode, frame, new ManagedCompact(registry, (Long) argument));
            } else if (operation == CompactOp.SIZE) {
                destination.setLong(bytecode, frame, registry.require(argument).size());
            } else {
                destination.setLong(bytecode, frame, registry.containsAny(argument) ? 1L : 0L);
            }
        }
    }
    @Operation public static final class ResizeCompact {
        @Specialization public static Object execute(Object reference, long size, Object state, @Bind("$node") Node node) {
            TupleResults.requireVoidCarrier(state);
            Language.currentState(node).compactRegions.require(reference).resize(size);
            return thc.runtime.Unit.INSTANCE;
        }
    }
    @Operation
    @ConstantOperand(type = LocalAccessor.class, name = "destination")
    public static final class ContainsCompact {
        @Specialization public static void execute(VirtualFrame frame, LocalAccessor destination,
                Object reference, Object value, Object state, @Bind("$node") Node node) {
            TupleResults.requireVoidCarrier(state);
            ManagedCompacts registry = Language.currentState(node).compactRegions;
            destination.setLong(((BytecodeRoot) node.getRootNode()).getBytecodeNode(), frame,
                    registry.contains(registry.require(reference), value) ? 1L : 0L);
        }
    }
    @Operation
    @ConstantOperand(type = boolean.class, name = "sharing")
    @ConstantOperand(type = Metrics.class, name = "metrics")
    @ConstantOperand(type = GlobalBinding[].class, name = "failures")
    @ConstantOperand(type = boolean.class, name = "async")
    public static final class AddCompact {
        protected static CompactCopyNode create(Metrics metrics, GlobalBinding[] failures, boolean async) {
            return new CompactCopyNode(metrics, failures, async);
        }
        @Specialization public static Object execute(VirtualFrame frame,
                boolean sharing, Metrics metrics, GlobalBinding[] failures, boolean async,
                Object reference, Object value, Object state, @Bind("$node") Node node,
                @Cached(value = "create(metrics, failures, async)", neverDefault = true) CompactCopyNode copier) {
            TupleResults.requireVoidCarrier(state);
            ManagedCompact region = Language.currentState(node).compactRegions.require(reference);
            try { return copier.execute(frame, region, value, sharing); }
            catch (AstCapture cut) { throw captured(frame.materialize(), cut, node); }
        }
        @TruffleBoundary(transferToInterpreterOnException = false)
        private static CapturedCallSuspension captured(com.oracle.truffle.api.frame.MaterializedFrame frame,
                AstCapture cut, Node node) {
            MaskingState mask = SynchronousMasking.current(node);
            return new CapturedCallSuspension(new CallSegment(cut.freeze((GuestRoot) node.getRootNode(), frame), mask, mask, null));
        }
    }
    @Operation
    @ConstantOperand(type = LocalAccessor.class, name = "destination")
    public static final class ReadMutVar {
        @Specialization public static void read(VirtualFrame frame, LocalAccessor destination,
                Object value, Object state, @Bind("$node") Node node) {
            ManagedMutVar cell = ManagedMutVar.require(value);
            TupleResults.requireVoidCarrier(state);
            destination.setObject(((BytecodeRoot) node.getRootNode()).getBytecodeNode(), frame, cell.getValue());
        }
    }
    @Operation
    @ConstantOperand(type = LocalAccessor.class, name = "destination")
    public static final class SwapMutVar {
        @Specialization public static void swap(VirtualFrame frame, LocalAccessor destination,
                Object reference, Object replacement, Object state, @Bind("$node") Node node) {
            ManagedMutVar cell = ManagedMutVar.require(reference);
            TupleResults.requireVoidCarrier(state);
            destination.setObject(((BytecodeRoot) node.getRootNode()).getBytecodeNode(), frame,
                    cell.exchange(replacement));
        }
    }
    @Operation
    @ConstantOperand(type = LocalAccessor.class, name = "flagDestination")
    @ConstantOperand(type = LocalAccessor.class, name = "valueDestination")
    public static final class CasMutVar {
        @Specialization public static void compare(VirtualFrame frame, LocalAccessor flagDestination,
                LocalAccessor valueDestination, Object reference, Object expected, Object replacement,
                Object state, @Bind("$node") Node node) {
            ManagedMutVar cell = ManagedMutVar.require(reference);
            TupleResults.requireVoidCarrier(state);
            Object witness = cell.compareExchange(expected, replacement);
            boolean success = witness == expected;
            BytecodeNode bytecode = ((BytecodeRoot) node.getRootNode()).getBytecodeNode();
            flagDestination.setLong(bytecode, frame, success ? 0L : 1L);
            valueDestination.setObject(bytecode, frame, success ? replacement : witness);
        }
    }
    @Operation
    @ConstantOperand(type = LocalAccessor.class, name = "oldDestination")
    @ConstantOperand(type = LocalAccessor.class, name = "resultDestination")
    @ConstantOperand(type = Language.class, name = "language")
    @ConstantOperand(type = Metrics.class, name = "metrics")
    @ConstantOperand(type = boolean.class, name = "async")
    @ConstantOperand(type = boolean.class, name = "selectFirst")
    public static final class ModifyMutVar2 {
        protected static MutVarModifySite create(Language language, Metrics metrics, boolean async, boolean selectFirst) {
            return MutVarModifySite.create(language, metrics, async, selectFirst);
        }
        @Specialization public static void modify(VirtualFrame frame, LocalAccessor oldDestination,
                LocalAccessor resultDestination, Language language, Metrics metrics, boolean async, boolean selectFirst,
                Object reference, Object function, Object state, @Bind("$node") Node node,
                @Cached(value = "create(language, metrics, async, selectFirst)", neverDefault = true) MutVarModifySite site) {
            ManagedMutVar cell = ManagedMutVar.require(reference);
            TupleResults.requireVoidCarrier(state);
            ModifiedMutVar modified = cell.modify(function, site);
            BytecodeNode bytecode = ((BytecodeRoot) node.getRootNode()).getBytecodeNode();
            oldDestination.setObject(bytecode, frame, modified.getOld());
            resultDestination.setObject(bytecode, frame, modified.getResult());
        }
    }
    @Operation public static final class WriteMutVar {
        @Specialization public static Object write(Object reference, Object value, Object state) {
            ManagedMutVar cell = ManagedMutVar.require(reference);
            TupleResults.requireVoidCarrier(state);
            cell.setValue(value);
            return thc.runtime.Unit.INSTANCE;
        }
    }

    @Operation
    @ConstantOperand(type = LocalAccessor.class, name = "destination")
    public static final class MakeWeak {
        @Specialization public static void apply(VirtualFrame frame, LocalAccessor destination,
                Object key, Object value, Object action, Object state, Object runner, @Bind("$node") Node node) {
            TupleResults.requireVoidCarrier(state);
            if (action == null) throw fail("mkWeak# requires a finalizer carrier");
            Object weak = ManagedWeaks.current(node).make(key, value, action, runner);
            destination.setObject(((BytecodeRoot) node.getRootNode()).getBytecodeNode(), frame, weak);
        }
    }
    @Operation
    @ConstantOperand(type = LocalAccessor.class, name = "destination")
    public static final class MakeWeakPlain {
        @Specialization public static void apply(VirtualFrame frame, LocalAccessor destination,
                Object key, Object value, Object state, @Bind("$node") Node node) {
            TupleResults.requireVoidCarrier(state);
            Object weak = ManagedWeaks.current(node).make(key, value, null, null);
            destination.setObject(((BytecodeRoot) node.getRootNode()).getBytecodeNode(), frame, weak);
        }
    }
    @Operation
    @ConstantOperand(type = LocalAccessor.class, name = "flagDestination")
    @ConstantOperand(type = LocalAccessor.class, name = "valueDestination")
    @ConstantOperand(type = boolean.class, name = "finalize")
    public static final class ObserveWeak {
        @Specialization public static void apply(VirtualFrame frame, LocalAccessor flagDestination,
                LocalAccessor valueDestination, boolean finalize, Object weak, Object state, @Bind("$node") Node node) {
            TupleResults.requireVoidCarrier(state);
            ManagedWeaks registry = ManagedWeaks.current(node);
            WeakResult result = finalize ? registry.finalize(weak) : registry.dereference(weak);
            BytecodeNode bytecode = ((BytecodeRoot) node.getRootNode()).getBytecodeNode();
            flagDestination.setLong(bytecode, frame, result.getFlag());
            valueDestination.setObject(bytecode, frame, result.getValue());
        }
    }

    @Operation
    @ConstantOperand(type = LocalAccessor.class, name = "destination")
    public static final class AddCFinalizerToWeak {
        @Specialization public static void apply(VirtualFrame frame, LocalAccessor destination,
                ManagedAddress function, ManagedAddress address, long flag, ManagedAddress environment,
                Object weak, Object state, @Bind("$node") Node node) {
            TupleResults.requireVoidCarrier(state);
            long added = ManagedWeaks.current(node).addCFinalizer(function, address, flag, environment, weak,
                    SulongCbits.current(node));
            destination.setLong(((BytecodeRoot) node.getRootNode()).getBytecodeNode(), frame, added);
        }
    }

    @Operation
    @ConstantOperand(type = LocalAccessor.class, name = "destination")
    public static final class MakeStableName {
        @Specialization public static void make(VirtualFrame frame, LocalAccessor destination,
                Object value, Object state, @Bind Node node) {
            TupleResults.requireVoidCarrier(state);
            destination.setObject(((BytecodeRoot) node.getRootNode()).getBytecodeNode(), frame,
                    StableNames.current(node).make(value));
        }
    }
    @Operation public static final class HashStableName {
        @Specialization public static long hash(Object value, @Bind Node node) {
            return StableNames.current(node).hash(value);
        }
    }

    @Operation
    @ConstantOperand(type = LocalAccessor.class, name = "destination")
    @ConstantOperand(type = StablePointerOp.class, name = "operation")
    public static final class StablePointerTuple {
        @Specialization public static void apply(VirtualFrame frame, LocalAccessor destination,
                StablePointerOp operation, Object value, Object state, @Bind("$node") Node node) {
            TupleResults.requireVoidCarrier(state);
            Object result;
            if (operation == StablePointerOp.MAKE) result = StablePointers.current(node).make(value);
            else if (operation == StablePointerOp.DEREFERENCE) {
                if (!(value instanceof ManagedAddress address)) throw fail("Expected opaque StablePtr#");
                result = StablePointers.current(node).dereference(address);
            } else throw fail("Invalid StablePtr# tuple operation");
            destination.setObject(((BytecodeRoot) node.getRootNode()).getBytecodeNode(), frame, result);
        }
    }
    @Operation public static final class FreeStablePointer {
        @Specialization public static void free(ManagedAddress address, Object state, @Bind("$node") Node node) {
            TupleResults.requireVoidCarrier(state);
            StablePointers.current(node).free(address);
        }
        @Fallback public static void invalid(Object address, Object state) { throw fail("Expected opaque StablePtr#"); }
    }
    @Operation
    @ConstantOperand(type = LocalAccessor.class, name = "destination")
    @ConstantOperand(type = SharedCAFStore.class, name = "store")
    public static final class RtsSharedCAFStore {
        @Specialization public static void apply(VirtualFrame frame, LocalAccessor destination,
                thc.runtime.SharedCAFStore store, ManagedAddress candidate, Object state, @Bind("$node") Node node) {
            TupleResults.requireVoidCarrier(state);
            ManagedAddress result = StablePointers.current(node).getOrSetSharedCAF(store, candidate);
            destination.setObject(((BytecodeRoot) node.getRootNode()).getBytecodeNode(), frame, result);
        }
        @Fallback public static void invalid(VirtualFrame frame, LocalAccessor destination,
                thc.runtime.SharedCAFStore store, Object candidate, Object state) {
            throw fail("Expected managed Addr# for RTS shared CAF store");
        }
    }
    @Operation
    @ConstantOperand(type = LocalAccessor.class, name = "destination")
    @ConstantOperand(type = boolean.class, name = "applied")
    public static final class CpuAffinityQuery {
        @Specialization public static void query(VirtualFrame frame, LocalAccessor destination, boolean applied,
                Object state, @Bind("$node") Node node) {
            TupleResults.requireVoidCarrier(state);
            GuestThreads threads = GuestThreads.current(node);
            destination.setInt(((BytecodeRoot) node.getRootNode()).getBytecodeNode(), frame,
                    applied ? (threads.currentIdentity().getAffinityApplied() ? 1 : 0)
                            : threads.getCpuAffinity().getMode().ordinal());
        }
    }
    @Operation
    @ConstantOperand(type = LocalAccessor.class, name = "destination")
    public static final class RuntimeServiceQuery {
        @Specialization public static void query(VirtualFrame frame, LocalAccessor destination,
                int selector, long index, long detail, Object state, @Bind("$node") Node node) {
            TupleResults.requireVoidCarrier(state);
            destination.setLong(((BytecodeRoot) node.getRootNode()).getBytecodeNode(), frame,
                    RuntimeServices.query(node, 2, selector, index, detail));
        }
    }
    @Operation
    @ConstantOperand(type = LocalAccessor.class, name = "destination")
    public static final class RuntimeServiceControl {
        @Specialization public static void control(VirtualFrame frame, LocalAccessor destination,
                int selector, long setting, Object state, @Bind("$node") Node node) {
            TupleResults.requireVoidCarrier(state);
            destination.setLong(((BytecodeRoot) node.getRootNode()).getBytecodeNode(), frame,
                    RuntimeServices.control(node, selector, setting));
        }
    }
    @Operation
    @ConstantOperand(type = LocalAccessor.class, name = "destination")
    public static final class ExceptionText {
        @Specialization public static void text(VirtualFrame frame, LocalAccessor destination,
                ManagedAddress handle, int selector, long index, Object state,
                @Cached(value = "new()", neverDefault = true) ForeignExceptionAccess access,
                @Bind("$node") Node node) {
            destination.setLong(((BytecodeRoot) node.getRootNode()).getBytecodeNode(), frame,
                    access.text(handle, selector, index, state));
        }
    }
    @Operation
    @ConstantOperand(type = LocalAccessor.class, name = "destination")
    public static final class RuntimeServiceTrace {
        @Specialization public static void trace(VirtualFrame frame, LocalAccessor destination,
                int operation, long token, ManagedAddress address, long length, Object state,
                @Bind("$node") Node node) {
            TupleResults.requireVoidCarrier(state);
            destination.setLong(((BytecodeRoot) node.getRootNode()).getBytecodeNode(), frame,
                    RuntimeServices.trace(node, operation, token, address, length));
        }
    }
    @Operation
    @ConstantOperand(type = LocalAccessor.class, name = "destination")
    @ConstantOperand(type = GcForeignOp.class, name = "operation")
    public static final class GcForeignQuery {
        @Specialization public static void query(VirtualFrame frame, LocalAccessor destination,
                GcForeignOp operation, Object state, @Bind("$node") Node node) {
            TupleResults.requireVoidCarrier(state);
            destination.setLong(((BytecodeRoot) node.getRootNode()).getBytecodeNode(), frame, operation.invoke());
        }
    }
    @Operation
    @ConstantOperand(type = GcForeignOp.class, name = "operation")
    public static final class RequestGarbageCollection {
        @Specialization public static void collect(GcForeignOp operation, Object state) {
            TupleResults.requireVoidCarrier(state);
            operation.invoke();
        }
    }
    @Operation
    public static final class IgnoreHeapSizeHint {
        @Specialization public static void ignore(long heapBytes, Object state) {
            // The native compiler heap-size advisory has no per-context JVM
            // equivalent. Evaluate both arguments but leave global VM policy alone.
            TupleResults.requireVoidCarrier(state);
        }
    }
    @Operation
    public static final class UnavailableRtsStats {
        @Specialization public static void unavailable(ManagedAddress address, Object state) {
            TupleResults.requireVoidCarrier(state);
            GcForeignOp.STATS.invoke();
        }
    }
    @Operation
    @ConstantOperand(type = LocalAccessor.class, name = "destination")
    @ConstantOperand(type = RtsEventForeignOp.class, name = "operation")
    public static final class RtsEventQuery {
        @Specialization public static void query(VirtualFrame frame, LocalAccessor destination,
                RtsEventForeignOp operation, Object state, @Bind("$node") Node node) {
            TupleResults.requireVoidCarrier(state);
            long result = operation.invoke(node, 0L);
            BytecodeNode bytecode = ((BytecodeRoot) node.getRootNode()).getBytecodeNode();
            if (operation == RtsEventForeignOp.PROCESSORS) destination.setInt(bytecode, frame, (int) result);
            else destination.setLong(bytecode, frame, result);
        }
    }
    @Operation
    public static final class SetNumCapabilities {
        @Specialization public static void set(int count, Object state, @Bind("$node") Node node) {
            TupleResults.requireVoidCarrier(state);
            RtsEventForeignOp.CAPABILITIES.invoke(node, Integer.toUnsignedLong(count));
        }
    }
    /** Original thread queries. Neither capability support nor accounting enforces a limit. */
    @Operation
    @ConstantOperand(type = LocalAccessor.class, name = "destination")
    @ConstantOperand(type = boolean.class, name = "allocationCounter")
    public static final class BoundThreadSupport {
        @Specialization public static void query(VirtualFrame frame, LocalAccessor destination, boolean allocationCounter,
                Object state, @Bind("$node") Node node) {
            TupleResults.requireVoidCarrier(state);
            destination.setLong(((BytecodeRoot) node.getRootNode()).getBytecodeNode(), frame,
                    allocationCounter ? GuestThreads.current(node).allocationCounter() : 0L);
        }
    }
    @Operation
    @ConstantOperand(type = LocalAccessor.class, name = "destination")
    public static final class OriginalCStringLength {
        @Specialization public static void length(VirtualFrame frame, LocalAccessor destination,
                ManagedAddress address, Object state, @Bind("$node") Node node) {
            TupleResults.requireVoidCarrier(state);
            destination.setLong(((BytecodeRoot) node.getRootNode()).getBytecodeNode(), frame,
                    address.cStringLength());
        }
        @Fallback public static void invalid(VirtualFrame frame, LocalAccessor destination,
                Object address, Object state) {
            throw fail("Expected owned Addr#/State# for original strlen");
        }
    }
    @Operation
    @ConstantOperand(type = LocalAccessor.class, name = "destination")
    public static final class EnvironmentGet {
        @Specialization public static void get(VirtualFrame frame, LocalAccessor destination,
                ManagedAddress name, Object state, @Bind("$node") Node node) {
            TupleResults.requireVoidCarrier(state);
            destination.setObject(((BytecodeRoot) node.getRootNode()).getBytecodeNode(), frame,
                    GuestEnvironment.current(node).get(name));
        }
    }
    @Operation
    @ConstantOperand(type = EnvironmentOp.class, name = "operation")
    @ConstantOperand(type = LocalAccessor.class, name = "destination")
    public static final class EnvironmentChange {
        @Specialization public static void change(VirtualFrame frame, EnvironmentOp operation,
                LocalAccessor destination, @Variadic Object[] arguments, @Bind("$node") Node node) {
            TupleResults.requireVoidCarrier(arguments[arguments.length - 1]);
            GuestEnvironment environment = GuestEnvironment.current(node);
            long result = switch (operation) {
                case PUT -> environment.put((ManagedAddress) arguments[0]);
                case UNSET -> environment.unset((ManagedAddress) arguments[0]);
                case SET -> environment.set((ManagedAddress) arguments[0], (ManagedAddress) arguments[1], (int) arguments[2]);
                case CLEAR -> environment.clear();
                default -> throw fail("Expected environment mutation");
            };
            var bytecode = ((BytecodeRoot) node.getRootNode()).getBytecodeNode();
            if (operation == EnvironmentOp.CLEAR) destination.setLong(bytecode, frame, result);
            else destination.setInt(bytecode, frame, (int) result);
        }
    }
    @Operation
    @ConstantOperand(type = LocalAccessor.class, name = "destination")
    public static final class EnvironmentEnumerate {
        @Specialization public static void get(VirtualFrame frame, LocalAccessor destination,
                Object state, @Bind("$node") Node node) {
            TupleResults.requireVoidCarrier(state);
            destination.setObject(((BytecodeRoot) node.getRootNode()).getBytecodeNode(), frame,
                    GuestEnvironment.current(node).environ());
        }
    }
    /** Exact RTS queries whose selected THC policy is fixed during lowering. */
    @Operation
    @ConstantOperand(type = LocalAccessor.class, name = "destination")
    @ConstantOperand(type = long.class, name = "value")
    public static final class OriginalRtsConstant {
        @Specialization public static void query(VirtualFrame frame, LocalAccessor destination, long value,
                Object state, @Bind("$node") Node node) {
            TupleResults.requireVoidCarrier(state);
            destination.setLong(((BytecodeRoot) node.getRootNode()).getBytecodeNode(), frame, value);
        }
    }
    @Operation
    @ConstantOperand(type = RtsDiagnosticOp.class, name = "operation")
    public static final class RtsDiagnostic {
        @Specialization public static void report(RtsDiagnosticOp operation, Object first,
                Object second, Object state, @Bind("$node") Node node) {
            TupleResults.requireVoidCarrier(state);
            RtsDiagnostics.report(node, operation, first, second);
        }
    }

    @Operation
    @ConstantOperand(type = RtsShutdownOp.class, name = "operation")
    public static final class ShutdownRuntime {
        @Specialization public static void shutdown(RtsShutdownOp operation, int code, int fast, Object state,
                @Bind("$node") Node node) {
            CoreRtsShutdown.shutdown(node, operation, code, fast, state);
        }
    }

    @Operation
    public static final class GetProgramArguments {
        @Specialization public static void get(ManagedAddress argc, ManagedAddress argv,
                Object state, @Bind Node node) {
            TupleResults.requireVoidCarrier(state);
            GuestArguments.current(node).get(argc, argv);
        }
    }

    @Operation
    public static final class SetProgramArguments {
        @Specialization public static void set(int argc, ManagedAddress argv,
                Object state, @Bind Node node) {
            TupleResults.requireVoidCarrier(state);
            GuestArguments.current(node).set(argc, argv);
        }
    }

    @Operation
    @ConstantOperand(type = LocalAccessor.class, name = "destination")
    public static final class InstallProcessSignal {
        @Specialization public static void install(VirtualFrame frame, LocalAccessor destination,
                int signal, int action, ManagedAddress mask, Object state, @Bind("$node") Node node) {
            TupleResults.requireVoidCarrier(state);
            long result = ManagedSignals.install(node, signal, action, mask);
            destination.setInt(((BytecodeRoot) node.getRootNode()).getBytecodeNode(), frame, (int) result);
        }
        @Fallback public static void invalid(VirtualFrame frame, LocalAccessor destination,
                Object signal, Object action, Object mask, Object state) {
            throw fail("Expected exact CInt/CInt/Addr#/State# signal operands");
        }
    }
    @Operation
    @ConstantOperand(type = LocalAccessor.class, name = "destination")
    @ConstantOperand(type = ThreadIdForeignOp.class, name = "operation")
    public static final class ThreadIdForeign {
        @Specialization public static void query(VirtualFrame frame, LocalAccessor destination, ThreadIdForeignOp operation,
                Object first, Object second, Object state, @Bind("$node") Node node) {
            TupleResults.requireVoidCarrier(state);
            long value = operation.execute(node, first, second);
            var bytecode = ((BytecodeRoot) node.getRootNode()).getBytecodeNode();
            if (operation == ThreadIdForeignOp.ID) destination.setLong(bytecode, frame, value);
            else destination.setInt(bytecode, frame, (int) value);
        }
    }
    @Operation public static final class RegisterMainThread {
        @Specialization public static void register(Object weak, Object state, @Bind("$node") Node node) {
            TupleResults.requireVoidCarrier(state);
            CoreMainThreadForeign.register(node, weak);
        }
    }
    @Operation public static final class EqualStablePointers {
        @Specialization public static long equal(ManagedAddress left, ManagedAddress right, @Bind("$node") Node node) {
            return StablePointers.current(node).equal(left, right) ? 1L : 0L;
        }
        @Fallback public static long invalid(Object left, Object right) { throw fail("Expected opaque StablePtr# operands"); }
    }

    @Operation
    @ConstantOperand(type = LocalAccessor.class, name = "destination")
    public static final class NewArray {
        @Specialization public static void create(VirtualFrame frame, LocalAccessor destination,
                long size, Object initial, Object state, @Bind("$node") Node node) {
            TupleResults.requireVoidCarrier(state);
            destination.setObject(((BytecodeRoot) node.getRootNode()).getBytecodeNode(), frame, ManagedArray.allocate(size, initial));
        }
    }
    @Operation
    @ConstantOperand(type = LocalAccessor.class, name = "destination")
    public static final class ReadArray {
        @Specialization public static void read(VirtualFrame frame, LocalAccessor destination,
                Object value, long index, Object state, @Bind("$node") Node node) {
            Object[] array = ManagedArray.require(value);
            TupleResults.requireVoidCarrier(state);
            destination.setObject(((BytecodeRoot) node.getRootNode()).getBytecodeNode(), frame, ManagedArray.read(array, index));
        }
    }
    @Operation
    @ConstantOperand(type = LocalAccessor.class, name = "flagDestination")
    @ConstantOperand(type = LocalAccessor.class, name = "valueDestination")
    public static final class CasArray {
        @Specialization public static void compare(VirtualFrame frame, LocalAccessor flagDestination,
                LocalAccessor valueDestination, Object reference, long index, Object expected, Object replacement,
                Object state, @Bind("$node") Node node) {
            Object[] array = ManagedArray.require(reference);
            TupleResults.requireVoidCarrier(state);
            Object witness = ManagedArray.compareExchange(array, index, expected, replacement);
            boolean success = witness == expected;
            BytecodeNode bytecode = ((BytecodeRoot) node.getRootNode()).getBytecodeNode();
            flagDestination.setLong(bytecode, frame, success ? 0L : 1L);
            valueDestination.setObject(bytecode, frame, success ? replacement : witness);
        }
    }
    @Operation public static final class WriteArray {
        @Specialization public static Object write(Object reference, long index, Object value, Object state) {
            Object[] array = ManagedArray.require(reference);
            TupleResults.requireVoidCarrier(state);
            ManagedArray.write(array, index, value);
            return thc.runtime.Unit.INSTANCE;
        }
    }
    @Operation
    @ConstantOperand(type = LocalAccessor.class, name = "destination")
    @ConstantOperand(type = boolean.class, name = "freeze")
    public static final class FreezeArray {
        @Specialization public static void freeze(VirtualFrame frame, LocalAccessor destination,
                boolean freeze, Object reference, Object state, @Bind("$node") Node node) {
            Object[] array = ManagedArray.require(reference);
            TupleResults.requireVoidCarrier(state);
            destination.setObject(((BytecodeRoot) node.getRootNode()).getBytecodeNode(), frame,
                    freeze ? ManagedArray.freeze(array) : ManagedArray.thaw(array));
        }
    }
    @Operation
    @ConstantOperand(type = LocalAccessor.class, name = "destination")
    public static final class IndexArray {
        @Specialization public static void index(VirtualFrame frame, LocalAccessor destination,
                Object reference, long index, @Bind("$node") Node node) {
            destination.setObject(((BytecodeRoot) node.getRootNode()).getBytecodeNode(), frame,
                    ManagedArray.read(ManagedArray.require(reference), index));
        }
    }

    @Operation public static final class SizeArray {
        @Specialization public static long size(Object reference) {
            return ManagedArray.size(ManagedArray.require(reference));
        }
    }
    @Operation
    @ConstantOperand(type = boolean.class, name = "mutableSource")
    public static final class TransferArray {
        @Specialization public static Object copy(boolean mutableSource, Object source, long sourceOffset,
                Object destination, long destinationOffset, long count, Object state) {
            Object[] from = ManagedArray.require(source);
            Object[] to = ManagedArray.require(destination);
            TupleResults.requireVoidCarrier(state);
            ManagedArray.copy(from, sourceOffset, to, destinationOffset, count, mutableSource);
            return thc.runtime.Unit.INSTANCE;
        }
    }
    @Operation public static final class CloneArray {
        @Specialization public static Object clone(Object reference, long offset, long count) {
            return ManagedArray.freeze(ManagedArray.slice(ManagedArray.require(reference), offset, count));
        }
    }
    @Operation
    @ConstantOperand(type = LocalAccessor.class, name = "destination")
    @ConstantOperand(type = boolean.class, name = "freeze")
    public static final class CopyArraySlice {
        @Specialization public static void copy(VirtualFrame frame, LocalAccessor destination,
                boolean freeze, Object reference, long offset, long count, Object state, @Bind("$node") Node node) {
            Object[] array = ManagedArray.require(reference);
            TupleResults.requireVoidCarrier(state);
            Object[] copy = ManagedArray.slice(array, offset, count);
            destination.setObject(((BytecodeRoot) node.getRootNode()).getBytecodeNode(), frame,
                    freeze ? ManagedArray.freeze(copy) : copy);
        }
    }

    @Operation
    @ConstantOperand(type = LocalAccessor.class, name = "destination")
    public static final class NewSmallArray {
        @Specialization public static void create(VirtualFrame frame, LocalAccessor destination,
                long size, Object initial, Object state, @Bind("$node") Node node) {
            TupleResults.requireVoidCarrier(state);
            destination.setObject(((BytecodeRoot) node.getRootNode()).getBytecodeNode(), frame,
                    ManagedSmallArray.allocate(size, initial));
        }
    }
    @Operation
    @ConstantOperand(type = LocalAccessor.class, name = "destination")
    public static final class ReadSmallArray {
        @Specialization public static void read(VirtualFrame frame, LocalAccessor destination,
                Object reference, long index, Object state, @Bind("$node") Node node) {
            SmallArrayStorage array = ManagedSmallArray.require(reference);
            TupleResults.requireVoidCarrier(state);
            destination.setObject(((BytecodeRoot) node.getRootNode()).getBytecodeNode(), frame,
                    ManagedSmallArray.read(array, index));
        }
    }
    @Operation
    @ConstantOperand(type = LocalAccessor.class, name = "flagDestination")
    @ConstantOperand(type = LocalAccessor.class, name = "valueDestination")
    public static final class CasSmallArray {
        @Specialization public static void compare(VirtualFrame frame, LocalAccessor flagDestination,
                LocalAccessor valueDestination, Object reference, long index, Object expected, Object replacement,
                Object state, @Bind("$node") Node node) {
            SmallArrayStorage array = ManagedSmallArray.require(reference);
            TupleResults.requireVoidCarrier(state);
            Object witness = ManagedSmallArray.compareExchange(array, index, expected, replacement);
            boolean success = witness == expected;
            BytecodeNode bytecode = ((BytecodeRoot) node.getRootNode()).getBytecodeNode();
            flagDestination.setLong(bytecode, frame, success ? 0L : 1L);
            valueDestination.setObject(bytecode, frame, success ? replacement : witness);
        }
    }
    @Operation public static final class WriteSmallArray {
        @Specialization public static Object write(Object reference, long index, Object value, Object state) {
            SmallArrayStorage array = ManagedSmallArray.require(reference);
            TupleResults.requireVoidCarrier(state);
            ManagedSmallArray.write(array, index, value);
            return thc.runtime.Unit.INSTANCE;
        }
    }
    @Operation
    @ConstantOperand(type = LocalAccessor.class, name = "destination")
    public static final class IndexSmallArray {
        @Specialization public static void index(VirtualFrame frame, LocalAccessor destination,
                Object reference, long index, @Bind("$node") Node node) {
            destination.setObject(((BytecodeRoot) node.getRootNode()).getBytecodeNode(), frame,
                    ManagedSmallArray.read(ManagedSmallArray.require(reference), index));
        }
    }
    @Operation
    @ConstantOperand(type = LocalAccessor.class, name = "destination")
    @ConstantOperand(type = boolean.class, name = "freeze")
    public static final class FreezeSmallArray {
        @Specialization public static void freeze(VirtualFrame frame, LocalAccessor destination,
                boolean freeze, Object reference, Object state, @Bind("$node") Node node) {
            SmallArrayStorage array = ManagedSmallArray.require(reference);
            TupleResults.requireVoidCarrier(state);
            destination.setObject(((BytecodeRoot) node.getRootNode()).getBytecodeNode(), frame,
                    freeze ? ManagedSmallArray.freeze(array) : ManagedSmallArray.thaw(array));
        }
    }
    @Operation public static final class SizeSmallArray {
        @Specialization public static long size(Object reference) {
            return ManagedSmallArray.size(ManagedSmallArray.require(reference));
        }
    }
    @Operation
    @ConstantOperand(type = LocalAccessor.class, name = "destination")
    public static final class GetSizeSmallMutableArray {
        @Specialization public static void size(VirtualFrame frame, LocalAccessor destination,
                Object reference, Object state, @Bind("$node") Node node) {
            SmallArrayStorage array = ManagedSmallArray.require(reference);
            TupleResults.requireVoidCarrier(state);
            destination.setLong(((BytecodeRoot) node.getRootNode()).getBytecodeNode(), frame,
                    ManagedSmallArray.size(array));
        }
    }
    @Operation public static final class CloneSmallArray {
        @Specialization public static Object clone(Object reference, long offset, long count) {
            return ManagedSmallArray.freeze(ManagedSmallArray.slice(ManagedSmallArray.require(reference), offset, count));
        }
    }
    @Operation
    @ConstantOperand(type = LocalAccessor.class, name = "destination")
    @ConstantOperand(type = boolean.class, name = "freeze")
    public static final class CopySmallArraySlice {
        @Specialization public static void clone(VirtualFrame frame, LocalAccessor destination,
                boolean freeze, Object reference, long offset, long count, Object state, @Bind("$node") Node node) {
            SmallArrayStorage array = ManagedSmallArray.require(reference);
            TupleResults.requireVoidCarrier(state);
            SmallArrayStorage copy = ManagedSmallArray.slice(array, offset, count);
            destination.setObject(((BytecodeRoot) node.getRootNode()).getBytecodeNode(), frame,
                    freeze ? ManagedSmallArray.freeze(copy) : copy);
        }
    }
    @Operation
    @ConstantOperand(type = boolean.class, name = "mutableSource")
    public static final class TransferSmallArray {
        @Specialization public static Object copy(boolean mutableSource, Object source, long sourceOffset,
                Object destination, long destinationOffset, long count, Object state) {
            SmallArrayStorage from = ManagedSmallArray.require(source);
            SmallArrayStorage to = ManagedSmallArray.require(destination);
            TupleResults.requireVoidCarrier(state);
            ManagedSmallArray.copy(from, sourceOffset, to, destinationOffset, count, mutableSource);
            return thc.runtime.Unit.INSTANCE;
        }
    }

    /** State operands are evaluated before each effect; only the array has a tuple slot. */
    @Operation
    @ConstantOperand(type = LocalAccessor.class, name = "destination")
    public static final class NewByteArray {
        @Specialization public static void allocate(VirtualFrame frame, LocalAccessor destination,
                long size, Object state, @Bind("$node") Node node) {
            ManagedByteArray.requireState(state);
            Object array = ManagedByteArray.allocateGuest(size, Language.currentState(node).getNativeByteArrays());
            destination.setObject(((BytecodeRoot) node.getRootNode()).getBytecodeNode(), frame, array);
        }
    }
    @Operation
    @ConstantOperand(type = boolean.class, name = "shrink")
    @ConstantOperand(type = LocalAccessor.class, name = "destination")
    public static final class ResizeByteArray {
        @Specialization public static void resize(VirtualFrame frame, boolean shrink, LocalAccessor destination,
                Object value, long size, Object state, @Bind("$node") Node node) {
            Object array = value;
            ManagedByteArray.requireState(state);
            if (shrink) ManagedByteArray.shrinkGuest(array, size);
            else {
                Object result = ManagedByteArray.resizeGuest(array, size, Language.currentState(node).getNativeByteArrays());
                destination.setObject(((BytecodeRoot) node.getRootNode()).getBytecodeNode(), frame, result);
            }
        }
    }
    @Operation
    @ConstantOperand(type = LocalAccessor.class, name = "destination")
    public static final class FreezeByteArray {
        @Specialization public static void freeze(VirtualFrame frame, LocalAccessor destination,
                Object value, Object state, @Bind("$node") Node node) {
            Object array = value;
            ManagedByteArray.requireState(state);
            destination.setObject(((BytecodeRoot) node.getRootNode()).getBytecodeNode(), frame, ManagedByteArray.freezeGuest(array));
        }
    }
    @Operation public static final class WriteByteArray {
        @Specialization public static Object write(Object value, long offset, int byteValue, Object state) {
            ManagedByteArray.requireState(state);
            ManagedByteArray.writeGuest(value, offset, byteValue);
            return thc.runtime.Unit.INSTANCE;
        }
        @Specialization public static Object writeChar(Object value, long offset, long byteValue, Object state) {
            ManagedByteArray.requireState(state);
            ManagedByteArray.writeGuest(value, offset, (int) byteValue);
            return thc.runtime.Unit.INSTANCE;
        }
    }
    @Operation public static final class CopyByteArray {
        @Specialization public static Object copy(Object source, long sourceOffset, Object destination,
                long destinationOffset, long count, Object state) {
            ManagedByteArray.requireState(state);
            ManagedByteArray.copyGuest(source, sourceOffset, destination, destinationOffset, count, false, false);
            return thc.runtime.Unit.INSTANCE;
        }
    }
    @Operation public static final class SetByteArray {
        @Specialization public static Object set(Object value, long offset, long count, long byteValue, Object state) {
            ManagedByteArray.requireState(state);
            ManagedByteArray.fillGuest(value, offset, count, byteValue);
            return thc.runtime.Unit.INSTANCE;
        }
    }
    @Operation
    @ConstantOperand(type = boolean.class, name = "nonOverlapping")
    public static final class CopyMutableByteArray {
        @Specialization public static Object copy(boolean nonOverlapping, Object source, long sourceOffset,
                Object destination, long destinationOffset, long count, Object state) {
            ManagedByteArray.requireState(state);
            ManagedByteArray.copyGuest(source, sourceOffset, destination, destinationOffset, count, true, nonOverlapping);
            return thc.runtime.Unit.INSTANCE;
        }
    }
    @Operation public static final class CompareByteArrays {
        @Specialization public static long compare(Object first, long firstOffset, Object second,
                long secondOffset, long count) {
            return ManagedByteArray.compareGuest(first, firstOffset, second, secondOffset, count);
        }
    }
    @Operation
    @ConstantOperand(type = LocalAccessor.class, name = "destination")
    public static final class GetSizeMutableByteArray {
        @Specialization public static void size(VirtualFrame frame, LocalAccessor destination,
                Object value, Object state, @Bind("$node") Node node) {
            ManagedByteArray.requireState(state);
            long result = ManagedByteArray.sizeGuest(value);
            destination.setLong(((BytecodeRoot) node.getRootNode()).getBytecodeNode(), frame, result);
        }
    }
    @Operation public static final class SizeByteArray {
        @Specialization public static long size(Object value) { return ManagedByteArray.sizeGuest(value); }
    }
    @Operation
    @ConstantOperand(type = boolean.class, name = "weak")
    public static final class PinnedByteArray {
        @Specialization public static long query(boolean weak, ManagedAllocation value) { return (weak ? value.hasNativeStorage() : value.isPinned()) ? 1L : 0L; }
        @Specialization public static long query(boolean weak, byte[] value) { return 0L; }
        @Fallback public static long invalid(boolean weak, Object value) { throw fail("Expected a managed ByteArray#"); }
    }
    @Operation public static final class ShrinkSmallArray {
        @Specialization public static Object shrink(Object reference, long size, Object state) {
            SmallArrayStorage array = ManagedSmallArray.require(reference);
            TupleResults.requireVoidCarrier(state);
            array.shrink(size);
            return thc.runtime.Unit.INSTANCE;
        }
    }
    @Operation
    @ConstantOperand(type = boolean.class, name = "unsigned")
    @ConstantOperand(type = LocalAccessor.class, name = "destination")
    public static final class ReadByteArray {
        @Specialization public static void read(VirtualFrame frame, boolean unsigned, LocalAccessor destination,
                Object value, long index, Object state, @Bind("$node") Node node) {
            ManagedByteArray.requireState(state);
            int result = ManagedByteArray.readGuest(value, index, unsigned);
            destination.setInt(((BytecodeRoot) node.getRootNode()).getBytecodeNode(), frame, result);
        }
    }

    @Operation
    @ConstantOperand(type = LocalAccessor.class, name = "destination")
    public static final class ReadCharArray {
        @Specialization public static void read(VirtualFrame frame, LocalAccessor destination,
                Object value, long index, Object state, @Bind("$node") Node node) {
            ManagedByteArray.requireState(state);
            long result = ManagedByteArray.readGuest(value, index, true);
            destination.setLong(((BytecodeRoot) node.getRootNode()).getBytecodeNode(), frame, result);
        }
    }
    @Operation public static final class IndexSignedByteArray {
        @Specialization public static int index(Object value, long offset) {
            return ManagedByteArray.readGuest(value, offset, false);
        }
    }
    @Operation public static final class IndexByteArray {
        @Specialization public static int index(Object value, long offset) { return ManagedByteArray.readGuest(value, offset, true); }
    }
    @Operation public static final class IndexCharArray {
        @Specialization public static long index(Object value, long offset) {
            return ManagedByteArray.readGuest(value, offset, true);
        }
    }
    /** Char# keeps its machine-word carrier independently of four-byte storage. */
    @Operation
    @ConstantOperand(type = boolean.class, name = "byteOffset")
    @ConstantOperand(type = LocalAccessor.class, name = "destination")
    public static final class ReadWideCharArray {
        @Specialization public static void read(VirtualFrame frame, boolean byteOffset, LocalAccessor destination,
                Object value, long index, Object state, @Bind("$node") Node node) {
            ManagedByteArray.requireState(state);
            int bits = byteOffset ? ManagedByteArray.readInt32ByteOffsetGuest(value, index, true)
                : ManagedByteArray.readInt32Guest(value, index, true);
            destination.setLong(((BytecodeRoot) node.getRootNode()).getBytecodeNode(), frame, Integer.toUnsignedLong(bits));
        }
    }
    @Operation @ConstantOperand(type = boolean.class, name = "byteOffset")
    public static final class WriteWideCharArray {
        @Specialization public static Object write(boolean byteOffset, Object value, long index, long character, Object state) {
            ManagedByteArray.requireState(state);
            if (byteOffset) ManagedByteArray.writeInt32ByteOffsetGuest(value, index, (int) character);
            else ManagedByteArray.writeInt32Guest(value, index, (int) character);
            return thc.runtime.Unit.INSTANCE;
        }
    }
    @Operation @ConstantOperand(type = boolean.class, name = "byteOffset")
    public static final class IndexWideCharArray {
        @Specialization public static long index(boolean byteOffset, Object value, long index) {
            int bits = byteOffset ? ManagedByteArray.readInt32ByteOffsetGuest(value, index, true)
                : ManagedByteArray.readInt32Guest(value, index, true);
            return Integer.toUnsignedLong(bits);
        }
    }
    /** Typed machine-Int array read. Java is required by the Bytecode DSL. */
    @Operation
    @ConstantOperand(type = boolean.class, name = "byteOffset")
    @ConstantOperand(type = LocalAccessor.class, name = "destination")
    public static final class IntArrayAccess {
        @Specialization public static void read(VirtualFrame frame, boolean byteOffset, LocalAccessor destination,
                Object value, long index, Object state, @Bind("$node") Node node) {
            ManagedByteArray.requireState(state);
            long result = ManagedByteArray.readIntGuest(value, index, byteOffset);
            destination.setLong(((BytecodeRoot) node.getRootNode()).getBytecodeNode(), frame, result);
        }
    }
    @Operation
    @ConstantOperand(type = AtomicIntArrayOp.class, name = "operation")
    @ConstantOperand(type = LocalAccessor.class, name = "destination")
    public static final class AtomicIntArray {
        @Specialization public static void accessInt(VirtualFrame frame, AtomicIntArrayOp operation, LocalAccessor destination,
                Object value, long index, int operand, int replacement, Object state, @Bind("$node") Node node) {
            ManagedByteArray.requireState(state);
            destination.setInt(((BytecodeRoot) node.getRootNode()).getBytecodeNode(), frame,
                operation.executeInt(value, index, operand, replacement));
        }
        @Specialization public static void access(VirtualFrame frame, AtomicIntArrayOp operation, LocalAccessor destination,
                Object value, long index, long operand, long replacement, Object state, @Bind("$node") Node node) {
            ManagedByteArray.requireState(state);
            long result = operation.execute(value, index, operand, replacement);
            destination.setLong(((BytecodeRoot) node.getRootNode()).getBytecodeNode(), frame, result);
        }
    }
    @Operation public static final class AtomicWriteIntArray {
        @Specialization public static Object write(Object value, long index, long operand, Object state) {
            ManagedByteArray.requireState(state);
            AtomicIntArrayOp.WRITE.execute(value, index, operand, 0L);
            return thc.runtime.Unit.INSTANCE;
        }
    }
    @Operation @ConstantOperand(type = boolean.class, name = "scalarOffset")
    @ConstantOperand(type = int.class, name = "vectorBytes")
    public static final class IndexVectorByteAddress {
        @Specialization public static ByteVector index(boolean scalarOffset, int vectorBytes, ManagedAddress address, long index) {
            return address.readVectorBytes(index, scalarOffset ? 1 : vectorBytes, vectorBytes);
        }
    }
    @Operation @ConstantOperand(type = boolean.class, name = "scalarOffset")
    @ConstantOperand(type = int.class, name = "vectorBytes")
    public static final class ReadVectorByteAddress {
        @Specialization public static ByteVector read(boolean scalarOffset, int vectorBytes, ManagedAddress address, long index, Object state) {
            ManagedByteArray.requireState(state);
            return address.readVectorBytes(index, scalarOffset ? 1 : vectorBytes, vectorBytes);
        }
    }
    @Operation @ConstantOperand(type = boolean.class, name = "scalarOffset")
    @ConstantOperand(type = int.class, name = "vectorBytes")
    public static final class WriteVectorByteAddress {
        @Specialization public static Object write(boolean scalarOffset, int vectorBytes, ManagedAddress address, long index, ByteVector vector, Object state) {
            vector = RuntimeTypes.requireByte(vector, ByteVector.SPECIES_128.withShape(VectorShape.forBitSize(vectorBytes * 8)));
            ManagedByteArray.requireState(state);
            address.writeVectorBytes(index, scalarOffset ? 1 : vectorBytes, vector, vectorBytes);
            return thc.runtime.Unit.INSTANCE;
        }
    }
    @Operation @ConstantOperand(type = boolean.class, name = "scalarOffset")
    @ConstantOperand(type = int.class, name = "vectorBytes")
    public static final class IndexVectorShortAddress {
        @Specialization public static ShortVector index(boolean scalarOffset, int vectorBytes, ManagedAddress address, long index) {
            ShortVector vector = VectorMemory.asShorts(address.readVectorBytes(index, scalarOffset ? 2 : vectorBytes, vectorBytes));
            return (java.nio.ByteOrder.nativeOrder() == java.nio.ByteOrder.LITTLE_ENDIAN ? vector : vector.lanewise(VectorOperators.REVERSE_BYTES));
        }
    }
    @Operation @ConstantOperand(type = boolean.class, name = "scalarOffset")
    @ConstantOperand(type = int.class, name = "vectorBytes")
    public static final class ReadVectorShortAddress {
        @Specialization public static ShortVector read(boolean scalarOffset, int vectorBytes, ManagedAddress address, long index, Object state) {
            ManagedByteArray.requireState(state);
            ShortVector vector = VectorMemory.asShorts(address.readVectorBytes(index, scalarOffset ? 2 : vectorBytes, vectorBytes));
            return (java.nio.ByteOrder.nativeOrder() == java.nio.ByteOrder.LITTLE_ENDIAN ? vector : vector.lanewise(VectorOperators.REVERSE_BYTES));
        }
    }
    @Operation @ConstantOperand(type = boolean.class, name = "scalarOffset")
    @ConstantOperand(type = int.class, name = "vectorBytes")
    public static final class WriteVectorShortAddress {
        @Specialization public static Object write(boolean scalarOffset, int vectorBytes, ManagedAddress address, long index, ShortVector vector, Object state) {
            vector = RuntimeTypes.requireShort(vector, ShortVector.SPECIES_128.withShape(VectorShape.forBitSize(vectorBytes * 8)));
            ManagedByteArray.requireState(state);
            address.writeVectorBytes(index, scalarOffset ? 2 : vectorBytes, VectorMemory.asBytes((java.nio.ByteOrder.nativeOrder() == java.nio.ByteOrder.LITTLE_ENDIAN ? vector : vector.lanewise(VectorOperators.REVERSE_BYTES))), vectorBytes);
            return thc.runtime.Unit.INSTANCE;
        }
    }
    @Operation @ConstantOperand(type = boolean.class, name = "scalarOffset")
    @ConstantOperand(type = int.class, name = "vectorBytes")
    public static final class IndexVectorIntAddress {
        @Specialization public static IntVector index(boolean scalarOffset, int vectorBytes, ManagedAddress address, long index) {
            IntVector vector = VectorMemory.asInts(address.readVectorBytes(index, scalarOffset ? 4 : vectorBytes, vectorBytes));
            return (java.nio.ByteOrder.nativeOrder() == java.nio.ByteOrder.LITTLE_ENDIAN ? vector : vector.lanewise(VectorOperators.REVERSE_BYTES));
        }
    }
    @Operation @ConstantOperand(type = boolean.class, name = "scalarOffset")
    @ConstantOperand(type = int.class, name = "vectorBytes")
    public static final class ReadVectorIntAddress {
        @Specialization public static IntVector read(boolean scalarOffset, int vectorBytes, ManagedAddress address, long index, Object state) {
            ManagedByteArray.requireState(state);
            IntVector vector = VectorMemory.asInts(address.readVectorBytes(index, scalarOffset ? 4 : vectorBytes, vectorBytes));
            return (java.nio.ByteOrder.nativeOrder() == java.nio.ByteOrder.LITTLE_ENDIAN ? vector : vector.lanewise(VectorOperators.REVERSE_BYTES));
        }
    }
    @Operation @ConstantOperand(type = boolean.class, name = "scalarOffset")
    @ConstantOperand(type = int.class, name = "vectorBytes")
    public static final class WriteVectorIntAddress {
        @Specialization public static Object write(boolean scalarOffset, int vectorBytes, ManagedAddress address, long index, IntVector vector, Object state) {
            vector = RuntimeTypes.requireInt(vector, IntVector.SPECIES_128.withShape(VectorShape.forBitSize(vectorBytes * 8)));
            ManagedByteArray.requireState(state);
            address.writeVectorBytes(index, scalarOffset ? 4 : vectorBytes, VectorMemory.asBytes((java.nio.ByteOrder.nativeOrder() == java.nio.ByteOrder.LITTLE_ENDIAN ? vector : vector.lanewise(VectorOperators.REVERSE_BYTES))), vectorBytes);
            return thc.runtime.Unit.INSTANCE;
        }
    }
    @Operation @ConstantOperand(type = boolean.class, name = "scalarOffset")
    @ConstantOperand(type = int.class, name = "vectorBytes")
    public static final class IndexVectorLongAddress {
        @Specialization public static LongVector index(boolean scalarOffset, int vectorBytes, ManagedAddress address, long index) {
            LongVector vector = VectorMemory.asLongs(address.readVectorBytes(index, scalarOffset ? 8 : vectorBytes, vectorBytes));
            return (java.nio.ByteOrder.nativeOrder() == java.nio.ByteOrder.LITTLE_ENDIAN ? vector : vector.lanewise(VectorOperators.REVERSE_BYTES));
        }
    }
    @Operation @ConstantOperand(type = boolean.class, name = "scalarOffset")
    @ConstantOperand(type = int.class, name = "vectorBytes")
    public static final class ReadVectorLongAddress {
        @Specialization public static LongVector read(boolean scalarOffset, int vectorBytes, ManagedAddress address, long index, Object state) {
            ManagedByteArray.requireState(state);
            LongVector vector = VectorMemory.asLongs(address.readVectorBytes(index, scalarOffset ? 8 : vectorBytes, vectorBytes));
            return (java.nio.ByteOrder.nativeOrder() == java.nio.ByteOrder.LITTLE_ENDIAN ? vector : vector.lanewise(VectorOperators.REVERSE_BYTES));
        }
    }
    @Operation @ConstantOperand(type = boolean.class, name = "scalarOffset")
    @ConstantOperand(type = int.class, name = "vectorBytes")
    public static final class WriteVectorLongAddress {
        @Specialization public static Object write(boolean scalarOffset, int vectorBytes, ManagedAddress address, long index, LongVector vector, Object state) {
            vector = RuntimeTypes.requireLong(vector, LongVector.SPECIES_128.withShape(VectorShape.forBitSize(vectorBytes * 8)));
            ManagedByteArray.requireState(state);
            address.writeVectorBytes(index, scalarOffset ? 8 : vectorBytes, VectorMemory.asBytes((java.nio.ByteOrder.nativeOrder() == java.nio.ByteOrder.LITTLE_ENDIAN ? vector : vector.lanewise(VectorOperators.REVERSE_BYTES))), vectorBytes);
            return thc.runtime.Unit.INSTANCE;
        }
    }
    @Operation @ConstantOperand(type = boolean.class, name = "scalarOffset")
    @ConstantOperand(type = int.class, name = "vectorBytes")
    public static final class IndexVectorFloatAddress {
        @Specialization public static FloatVector index(boolean scalarOffset, int vectorBytes, ManagedAddress address, long index) {
            IntVector vector = VectorMemory.asInts(address.readVectorBytes(index, scalarOffset ? 4 : vectorBytes, vectorBytes));
            return VectorMemory.asFloats((java.nio.ByteOrder.nativeOrder() == java.nio.ByteOrder.LITTLE_ENDIAN ? vector : vector.lanewise(VectorOperators.REVERSE_BYTES)));
        }
    }
    @Operation @ConstantOperand(type = boolean.class, name = "scalarOffset")
    @ConstantOperand(type = int.class, name = "vectorBytes")
    public static final class ReadVectorFloatAddress {
        @Specialization public static FloatVector read(boolean scalarOffset, int vectorBytes, ManagedAddress address, long index, Object state) {
            ManagedByteArray.requireState(state);
            IntVector vector = VectorMemory.asInts(address.readVectorBytes(index, scalarOffset ? 4 : vectorBytes, vectorBytes));
            return VectorMemory.asFloats((java.nio.ByteOrder.nativeOrder() == java.nio.ByteOrder.LITTLE_ENDIAN ? vector : vector.lanewise(VectorOperators.REVERSE_BYTES)));
        }
    }
    @Operation @ConstantOperand(type = boolean.class, name = "scalarOffset")
    @ConstantOperand(type = int.class, name = "vectorBytes")
    public static final class WriteVectorFloatAddress {
        @Specialization public static Object write(boolean scalarOffset, int vectorBytes, ManagedAddress address, long index, FloatVector vector, Object state) {
            vector = RuntimeTypes.requireFloat(vector, FloatVector.SPECIES_128.withShape(VectorShape.forBitSize(vectorBytes * 8)));
            ManagedByteArray.requireState(state);
            IntVector bits = VectorMemory.asInts(vector);
            address.writeVectorBytes(index, scalarOffset ? 4 : vectorBytes, VectorMemory.asBytes((java.nio.ByteOrder.nativeOrder() == java.nio.ByteOrder.LITTLE_ENDIAN ? bits : bits.lanewise(VectorOperators.REVERSE_BYTES))), vectorBytes);
            return thc.runtime.Unit.INSTANCE;
        }
    }
    @Operation @ConstantOperand(type = boolean.class, name = "scalarOffset")
    @ConstantOperand(type = int.class, name = "vectorBytes")
    public static final class IndexVectorDoubleAddress {
        @Specialization public static DoubleVector index(boolean scalarOffset, int vectorBytes, ManagedAddress address, long index) {
            LongVector vector = VectorMemory.asLongs(address.readVectorBytes(index, scalarOffset ? 8 : vectorBytes, vectorBytes));
            return VectorMemory.asDoubles((java.nio.ByteOrder.nativeOrder() == java.nio.ByteOrder.LITTLE_ENDIAN ? vector : vector.lanewise(VectorOperators.REVERSE_BYTES)));
        }
    }
    @Operation @ConstantOperand(type = boolean.class, name = "scalarOffset")
    @ConstantOperand(type = int.class, name = "vectorBytes")
    public static final class ReadVectorDoubleAddress {
        @Specialization public static DoubleVector read(boolean scalarOffset, int vectorBytes, ManagedAddress address, long index, Object state) {
            ManagedByteArray.requireState(state);
            LongVector vector = VectorMemory.asLongs(address.readVectorBytes(index, scalarOffset ? 8 : vectorBytes, vectorBytes));
            return VectorMemory.asDoubles((java.nio.ByteOrder.nativeOrder() == java.nio.ByteOrder.LITTLE_ENDIAN ? vector : vector.lanewise(VectorOperators.REVERSE_BYTES)));
        }
    }
    @Operation @ConstantOperand(type = boolean.class, name = "scalarOffset")
    @ConstantOperand(type = int.class, name = "vectorBytes")
    public static final class WriteVectorDoubleAddress {
        @Specialization public static Object write(boolean scalarOffset, int vectorBytes, ManagedAddress address, long index, DoubleVector vector, Object state) {
            vector = RuntimeTypes.requireDouble(vector, DoubleVector.SPECIES_128.withShape(VectorShape.forBitSize(vectorBytes * 8)));
            ManagedByteArray.requireState(state);
            LongVector bits = VectorMemory.asLongs(vector);
            address.writeVectorBytes(index, scalarOffset ? 8 : vectorBytes, VectorMemory.asBytes((java.nio.ByteOrder.nativeOrder() == java.nio.ByteOrder.LITTLE_ENDIAN ? bits : bits.lanewise(VectorOperators.REVERSE_BYTES))), vectorBytes);
            return thc.runtime.Unit.INSTANCE;
        }
    }
    @Operation @ConstantOperand(type = boolean.class, name = "scalarOffset")
    @ConstantOperand(type = int.class, name = "vectorBytes")
    public static final class IndexVectorByteArray {
        @Specialization public static ByteVector index(boolean scalarOffset, int vectorBytes, Object value, long index) {
            return ManagedByteArray.readByteVectorGuest(value, index, scalarOffset, vectorBytes);
        }
    }
    @Operation @ConstantOperand(type = boolean.class, name = "scalarOffset")
    @ConstantOperand(type = int.class, name = "vectorBytes")
    public static final class ReadVectorByteArray {
        @Specialization public static ByteVector read(boolean scalarOffset, int vectorBytes, Object value, long index, Object state) {
            ManagedByteArray.requireState(state);
            return ManagedByteArray.readByteVectorGuest(value, index, scalarOffset, vectorBytes);
        }
    }
    @Operation @ConstantOperand(type = boolean.class, name = "scalarOffset")
    @ConstantOperand(type = int.class, name = "vectorBytes")
    public static final class WriteVectorByteArray {
        @Specialization public static Object write(boolean scalarOffset, int vectorBytes, Object value, long index, ByteVector vector, Object state) {
            vector = RuntimeTypes.requireByte(vector, ByteVector.SPECIES_128.withShape(jdk.incubator.vector.VectorShape.forBitSize(vectorBytes * 8)));
            ManagedByteArray.requireState(state);
            ManagedByteArray.writeByteVectorGuest(value, index, vector, scalarOffset, vectorBytes);
            return thc.runtime.Unit.INSTANCE;
        }
    }
    @Operation @ConstantOperand(type = boolean.class, name = "scalarOffset")
    @ConstantOperand(type = int.class, name = "vectorBytes")
    public static final class IndexVectorShortArray {
        @Specialization public static ShortVector index(boolean scalarOffset, int vectorBytes, Object value, long index) {
            return ManagedByteArray.readShortVectorGuest(value, index, scalarOffset, vectorBytes);
        }
    }
    @Operation @ConstantOperand(type = boolean.class, name = "scalarOffset")
    @ConstantOperand(type = int.class, name = "vectorBytes")
    public static final class ReadVectorShortArray {
        @Specialization public static ShortVector read(boolean scalarOffset, int vectorBytes, Object value, long index, Object state) {
            ManagedByteArray.requireState(state);
            return ManagedByteArray.readShortVectorGuest(value, index, scalarOffset, vectorBytes);
        }
    }
    @Operation @ConstantOperand(type = boolean.class, name = "scalarOffset")
    @ConstantOperand(type = int.class, name = "vectorBytes")
    public static final class WriteVectorShortArray {
        @Specialization public static Object write(boolean scalarOffset, int vectorBytes, Object value, long index, ShortVector vector, Object state) {
            vector = RuntimeTypes.requireShort(vector, ShortVector.SPECIES_128.withShape(jdk.incubator.vector.VectorShape.forBitSize(vectorBytes * 8)));
            ManagedByteArray.requireState(state);
            ManagedByteArray.writeShortVectorGuest(value, index, vector, scalarOffset, vectorBytes);
            return thc.runtime.Unit.INSTANCE;
        }
    }
    @Operation @ConstantOperand(type = boolean.class, name = "scalarOffset")
    @ConstantOperand(type = int.class, name = "vectorBytes")
    public static final class IndexVectorLongArray {
        @Specialization public static LongVector index(boolean scalarOffset, int vectorBytes, Object value, long index) {
            return ManagedByteArray.readLongVectorGuest(value, index, scalarOffset, vectorBytes);
        }
    }
    @Operation @ConstantOperand(type = boolean.class, name = "scalarOffset")
    @ConstantOperand(type = int.class, name = "vectorBytes")
    public static final class ReadVectorLongArray {
        @Specialization public static LongVector read(boolean scalarOffset, int vectorBytes, Object value, long index, Object state) {
            ManagedByteArray.requireState(state);
            return ManagedByteArray.readLongVectorGuest(value, index, scalarOffset, vectorBytes);
        }
    }
    @Operation @ConstantOperand(type = boolean.class, name = "scalarOffset")
    @ConstantOperand(type = int.class, name = "vectorBytes")
    public static final class WriteVectorLongArray {
        @Specialization public static Object write(boolean scalarOffset, int vectorBytes, Object value, long index, LongVector vector, Object state) {
            vector = RuntimeTypes.requireLong(vector, LongVector.SPECIES_128.withShape(jdk.incubator.vector.VectorShape.forBitSize(vectorBytes * 8)));
            ManagedByteArray.requireState(state);
            ManagedByteArray.writeLongVectorGuest(value, index, vector, scalarOffset, vectorBytes);
            return thc.runtime.Unit.INSTANCE;
        }
    }
    @Operation @ConstantOperand(type = boolean.class, name = "scalarOffset")
    @ConstantOperand(type = int.class, name = "vectorBytes")
    public static final class IndexVector32Array {
        @Specialization public static IntVector index(boolean scalarOffset, int vectorBytes, Object value, long index) {
            return ManagedByteArray.readInt32VectorGuest(value, index, scalarOffset, vectorBytes);
        }
    }
    @Operation @ConstantOperand(type = boolean.class, name = "scalarOffset")
    @ConstantOperand(type = int.class, name = "vectorBytes")
    public static final class ReadVector32Array {
        @Specialization public static IntVector read(boolean scalarOffset, int vectorBytes, Object value, long index, Object state) {
            ManagedByteArray.requireState(state);
            return ManagedByteArray.readInt32VectorGuest(value, index, scalarOffset, vectorBytes);
        }
    }
    @Operation @ConstantOperand(type = boolean.class, name = "scalarOffset")
    @ConstantOperand(type = int.class, name = "vectorBytes")
    public static final class WriteVector32Array {
        @Specialization public static Object write(boolean scalarOffset, int vectorBytes, Object value, long index, IntVector vector, Object state) {
            vector = RuntimeTypes.requireInt(vector, IntVector.SPECIES_128.withShape(jdk.incubator.vector.VectorShape.forBitSize(vectorBytes * 8)));
            ManagedByteArray.requireState(state);
            ManagedByteArray.writeInt32VectorGuest(value, index, vector, scalarOffset, vectorBytes);
            return thc.runtime.Unit.INSTANCE;
        }
    }
    @Operation @ConstantOperand(type = boolean.class, name = "scalarOffset")
    @ConstantOperand(type = int.class, name = "vectorBytes")
    public static final class IndexVectorWord32Array {
        @Specialization public static IntVector index(boolean scalarOffset, int vectorBytes, Object value, long index) {
            return ManagedByteArray.readWord32VectorGuest(value, index, scalarOffset, vectorBytes);
        }
    }
    @Operation @ConstantOperand(type = boolean.class, name = "scalarOffset")
    @ConstantOperand(type = int.class, name = "vectorBytes")
    public static final class ReadVectorWord32Array {
        @Specialization public static IntVector read(boolean scalarOffset, int vectorBytes, Object value, long index, Object state) {
            ManagedByteArray.requireState(state);
            return ManagedByteArray.readWord32VectorGuest(value, index, scalarOffset, vectorBytes);
        }
    }
    @Operation @ConstantOperand(type = boolean.class, name = "scalarOffset")
    @ConstantOperand(type = int.class, name = "vectorBytes")
    public static final class WriteVectorWord32Array {
        @Specialization public static Object write(boolean scalarOffset, int vectorBytes, Object value, long index, IntVector vector, Object state) {
            vector = RuntimeTypes.requireInt(vector, IntVector.SPECIES_128.withShape(jdk.incubator.vector.VectorShape.forBitSize(vectorBytes * 8)));
            ManagedByteArray.requireState(state);
            ManagedByteArray.writeWord32VectorGuest(value, index, vector, scalarOffset, vectorBytes);
            return thc.runtime.Unit.INSTANCE;
        }
    }
    @Operation @ConstantOperand(type = boolean.class, name = "scalarOffset")
    @ConstantOperand(type = int.class, name = "vectorBytes")
    public static final class IndexVectorFloatArray {
        @Specialization public static FloatVector index(boolean scalarOffset, int vectorBytes, Object value, long index) {
            return ManagedByteArray.readFloatVectorGuest(value, index, scalarOffset, vectorBytes);
        }
    }
    @Operation @ConstantOperand(type = boolean.class, name = "scalarOffset")
    @ConstantOperand(type = int.class, name = "vectorBytes")
    public static final class ReadVectorFloatArray {
        @Specialization public static FloatVector read(boolean scalarOffset, int vectorBytes, Object value, long index, Object state) {
            ManagedByteArray.requireState(state);
            return ManagedByteArray.readFloatVectorGuest(value, index, scalarOffset, vectorBytes);
        }
    }
    @Operation @ConstantOperand(type = boolean.class, name = "scalarOffset")
    @ConstantOperand(type = int.class, name = "vectorBytes")
    public static final class WriteVectorFloatArray {
        @Specialization public static Object write(boolean scalarOffset, int vectorBytes, Object value, long index, FloatVector vector, Object state) {
            vector = RuntimeTypes.requireFloat(vector, FloatVector.SPECIES_128.withShape(jdk.incubator.vector.VectorShape.forBitSize(vectorBytes * 8)));
            ManagedByteArray.requireState(state);
            ManagedByteArray.writeFloatVectorGuest(value, index, vector, scalarOffset, vectorBytes);
            return thc.runtime.Unit.INSTANCE;
        }
    }
    @Operation @ConstantOperand(type = boolean.class, name = "scalarOffset")
    @ConstantOperand(type = int.class, name = "vectorBytes")
    public static final class IndexVectorDoubleArray {
        @Specialization public static DoubleVector index(boolean scalarOffset, int vectorBytes, Object value, long index) {
            return ManagedByteArray.readDoubleVectorGuest(value, index, scalarOffset, vectorBytes);
        }
    }
    @Operation @ConstantOperand(type = boolean.class, name = "scalarOffset")
    @ConstantOperand(type = int.class, name = "vectorBytes")
    public static final class ReadVectorDoubleArray {
        @Specialization public static DoubleVector read(boolean scalarOffset, int vectorBytes, Object value, long index, Object state) {
            ManagedByteArray.requireState(state);
            return ManagedByteArray.readDoubleVectorGuest(value, index, scalarOffset, vectorBytes);
        }
    }
    @Operation @ConstantOperand(type = boolean.class, name = "scalarOffset")
    @ConstantOperand(type = int.class, name = "vectorBytes")
    public static final class WriteVectorDoubleArray {
        @Specialization public static Object write(boolean scalarOffset, int vectorBytes, Object value, long index, DoubleVector vector, Object state) {
            vector = RuntimeTypes.requireDouble(vector, DoubleVector.SPECIES_128.withShape(jdk.incubator.vector.VectorShape.forBitSize(vectorBytes * 8)));
            ManagedByteArray.requireState(state);
            ManagedByteArray.writeDoubleVectorGuest(value, index, vector, scalarOffset, vectorBytes);
            return thc.runtime.Unit.INSTANCE;
        }
    }
    @Operation @ConstantOperand(type = boolean.class, name = "byteOffset")
    public static final class WriteIntArray {
        @Specialization public static Object write(boolean byteOffset, Object value, long index, long integer, Object state) {
            ManagedByteArray.requireState(state);
            ManagedByteArray.writeIntGuest(value, index, integer, byteOffset);
            return thc.runtime.Unit.INSTANCE;
        }
    }
    @Operation @ConstantOperand(type = boolean.class, name = "byteOffset")
    public static final class IndexIntArray {
        @Specialization public static long index(boolean byteOffset, Object value, long index) {
            return ManagedByteArray.readIntGuest(value, index, byteOffset);
        }
    }
    @Operation
    @ConstantOperand(type = boolean.class, name = "byteOffset")
    @ConstantOperand(type = LocalAccessor.class, name = "destination")
    public static final class ReadDoubleArray {
        @Specialization public static void read(VirtualFrame frame, boolean byteOffset, LocalAccessor destination,
                Object value, long index, Object state, @Bind("$node") Node node) {
            ManagedByteArray.requireState(state);
            double result = byteOffset ? ManagedByteArray.readDoubleByteOffsetGuest(value, index)
                : ManagedByteArray.readDoubleGuest(value, index);
            destination.setDouble(((BytecodeRoot) node.getRootNode()).getBytecodeNode(), frame, result);
        }
    }
    @Operation @ConstantOperand(type = boolean.class, name = "byteOffset")
    public static final class WriteDoubleArray {
        @Specialization public static Object write(boolean byteOffset, Object value, long index, double number, Object state) {
            ManagedByteArray.requireState(state);
            if (byteOffset) ManagedByteArray.writeDoubleByteOffsetGuest(value, index, number);
            else ManagedByteArray.writeDoubleGuest(value, index, number);
            return thc.runtime.Unit.INSTANCE;
        }
    }
    @Operation @ConstantOperand(type = boolean.class, name = "byteOffset")
    public static final class IndexDoubleArray {
        @Specialization public static double index(boolean byteOffset, Object value, long index) {
            return byteOffset ? ManagedByteArray.readDoubleByteOffsetGuest(value, index)
                : ManagedByteArray.readDoubleGuest(value, index);
        }
    }

    @Operation
    @ConstantOperand(type = boolean.class, name = "byteOffset")
    @ConstantOperand(type = LocalAccessor.class, name = "destination")
    public static final class ReadFloatArray {
        @Specialization public static void read(VirtualFrame frame, boolean byteOffset, LocalAccessor destination,
                Object value, long index, Object state, @Bind("$node") Node node) {
            ManagedByteArray.requireState(state);
            float result = byteOffset ? ManagedByteArray.readFloatByteOffsetGuest(value, index)
                : ManagedByteArray.readFloatGuest(value, index);
            destination.setFloat(((BytecodeRoot) node.getRootNode()).getBytecodeNode(), frame, result);
        }
    }
    @Operation @ConstantOperand(type = boolean.class, name = "byteOffset")
    public static final class WriteFloatArray {
        @Specialization public static Object write(boolean byteOffset, Object value, long index, float number, Object state) {
            ManagedByteArray.requireState(state);
            if (byteOffset) ManagedByteArray.writeFloatByteOffsetGuest(value, index, number);
            else ManagedByteArray.writeFloatGuest(value, index, number);
            return thc.runtime.Unit.INSTANCE;
        }
    }
    @Operation @ConstantOperand(type = boolean.class, name = "byteOffset")
    public static final class IndexFloatArray {
        @Specialization public static float index(boolean byteOffset, Object value, long index) {
            return byteOffset ? ManagedByteArray.readFloatByteOffsetGuest(value, index)
                : ManagedByteArray.readFloatGuest(value, index);
        }
    }

    @Operation
    @ConstantOperand(type = boolean.class, name = "unsigned")
    @ConstantOperand(type = boolean.class, name = "byteOffset")
    @ConstantOperand(type = LocalAccessor.class, name = "destination")
    public static final class ReadInt16Array {
        @Specialization public static void read(VirtualFrame frame, boolean unsigned, boolean byteOffset, LocalAccessor destination,
                Object value, long index, Object state, @Bind("$node") Node node) {
            ManagedByteArray.requireState(state);
            int result = byteOffset ? ManagedByteArray.readInt16ByteOffsetGuest(value, index, unsigned)
                : ManagedByteArray.readInt16Guest(value, index, unsigned);
            destination.setInt(((BytecodeRoot) node.getRootNode()).getBytecodeNode(), frame, result);
        }
    }
    @Operation @ConstantOperand(type = boolean.class, name = "byteOffset")
    public static final class WriteInt16Array {
        @Specialization public static Object write(boolean byteOffset, Object value, long index, int integer, Object state) {
            ManagedByteArray.requireState(state);
            if (byteOffset) ManagedByteArray.writeInt16ByteOffsetGuest(value, index, integer);
            else ManagedByteArray.writeInt16Guest(value, index, integer);
            return thc.runtime.Unit.INSTANCE;
        }
    }
    @Operation @ConstantOperand(type = boolean.class, name = "unsigned")
    @ConstantOperand(type = boolean.class, name = "byteOffset")
    public static final class IndexInt16Array {
        @Specialization public static int index(boolean unsigned, boolean byteOffset, Object value, long index) {
            return byteOffset ? ManagedByteArray.readInt16ByteOffsetGuest(value, index, unsigned)
                : ManagedByteArray.readInt16Guest(value, index, unsigned);
        }
    }

    @Operation
    @ConstantOperand(type = boolean.class, name = "unsigned")
    @ConstantOperand(type = boolean.class, name = "byteOffset")
    @ConstantOperand(type = LocalAccessor.class, name = "destination")
    public static final class ReadInt32Array {
        @Specialization public static void read(VirtualFrame frame, boolean unsigned, boolean byteOffset, LocalAccessor destination,
                Object value, long index, Object state, @Bind("$node") Node node) {
            ManagedByteArray.requireState(state);
            int result = byteOffset ? ManagedByteArray.readInt32ByteOffsetGuest(value, index, unsigned)
                : ManagedByteArray.readInt32Guest(value, index, unsigned);
            destination.setInt(((BytecodeRoot) node.getRootNode()).getBytecodeNode(), frame, result);
        }
    }
    @Operation @ConstantOperand(type = boolean.class, name = "byteOffset")
    public static final class WriteInt32Array {
        @Specialization public static Object write(boolean byteOffset, Object value, long index, int integer, Object state) {
            ManagedByteArray.requireState(state);
            if (byteOffset) ManagedByteArray.writeInt32ByteOffsetGuest(value, index, integer);
            else ManagedByteArray.writeInt32Guest(value, index, integer);
            return thc.runtime.Unit.INSTANCE;
        }
    }
    @Operation @ConstantOperand(type = boolean.class, name = "unsigned") @ConstantOperand(type = boolean.class, name = "byteOffset")
    public static final class IndexInt32Array {
        @Specialization public static int index(boolean unsigned, boolean byteOffset, Object value, long index) {
            return byteOffset ? ManagedByteArray.readInt32ByteOffsetGuest(value, index, unsigned)
                : ManagedByteArray.readInt32Guest(value, index, unsigned);
        }
    }

    // GHC machine integers wrap. Comparisons return Int# 0/1, not boxed Bool.
    @Operation public static final class VectorPack {
        @Specialization public static LongVector pack(long first, long second) { return LongVector.broadcast(LongVector.SPECIES_128, first).withLane(1, second); }
    }
    @Operation public static final class VectorBroadcast {
        @Specialization public static LongVector broadcast(long value) { return LongVector.broadcast(LongVector.SPECIES_128, value); }
    }
    @Operation public static final class VectorNegate {
        @Specialization public static LongVector negate(LongVector value) {
            value = RuntimeTypes.requireLong(value, LongVector.SPECIES_128); return (value).neg(); }
    }
    @Operation @ConstantOperand(type = boolean.class, name = "subtract")
    public static final class VectorBinary {
        @Specialization public static LongVector binary(boolean subtract, LongVector first, LongVector second) {
            first = RuntimeTypes.requireLong(first, LongVector.SPECIES_128);
            second = RuntimeTypes.requireLong(second, LongVector.SPECIES_128);
            return subtract ? (first).sub(second) : (first).add(second);
        }
    }
    @Operation
    @ConstantOperand(type = LocalAccessor.class, name = "first")
    @ConstantOperand(type = LocalAccessor.class, name = "second")
    public static final class VectorUnpack {
        @Specialization public static void unpack(VirtualFrame frame, LocalAccessor first, LocalAccessor second,
                LongVector value, @Bind("$node") Node node) {
            value = RuntimeTypes.requireLong(value, LongVector.SPECIES_128);
            BytecodeNode bytecode = ((BytecodeRoot) node.getRootNode()).getBytecodeNode();
            first.setLong(bytecode, frame, value.lane(0)); second.setLong(bytecode, frame, value.lane(1));
        }
    }

    @Operation public static final class VectorWord8Pack {
        @Specialization public static ByteVector pack(int first, int second, int third, int fourth,
                int fifth, int sixth, int seventh, int eighth,
                int ninth, int tenth, int eleventh, int twelfth,
                int thirteenth, int fourteenth, int fifteenth, int sixteenth) {
            return ByteVector.broadcast(ByteVector.SPECIES_128, (byte) first).withLane(1, (byte) second).withLane(2, (byte) third).withLane(3, (byte) fourth).withLane(4, (byte) fifth).withLane(5, (byte) sixth).withLane(6, (byte) seventh).withLane(7, (byte) eighth).withLane(8, (byte) ninth).withLane(9, (byte) tenth).withLane(10, (byte) eleventh).withLane(11, (byte) twelfth).withLane(12, (byte) thirteenth).withLane(13, (byte) fourteenth).withLane(14, (byte) fifteenth).withLane(15, (byte) sixteenth);
        }
    }
    @Operation public static final class VectorWord8Broadcast {
        @Specialization public static ByteVector broadcast(int value) { return ByteVector.broadcast(ByteVector.SPECIES_128, (byte) value); }
    }
    @Operation @ConstantOperand(type = int.class, name = "operation")
    public static final class VectorWord8Binary {
        @Specialization public static ByteVector binary(int operation, ByteVector first, ByteVector second) {
            first = RuntimeTypes.requireByte(first, ByteVector.SPECIES_128);
            second = RuntimeTypes.requireByte(second, ByteVector.SPECIES_128);
            return switch (operation) {
                case 0 -> (first).add(second);
                case 1 -> (first).sub(second);
                case 2 -> (first).mul(second);
                default -> throw new RuntimeFault("Invalid Word8X16 operation");
            };
        }
    }
    @Operation
    @ConstantOperand(type = LocalAccessor.class, name = "first")
    @ConstantOperand(type = LocalAccessor.class, name = "second")
    @ConstantOperand(type = LocalAccessor.class, name = "third")
    @ConstantOperand(type = LocalAccessor.class, name = "fourth")
    @ConstantOperand(type = LocalAccessor.class, name = "fifth")
    @ConstantOperand(type = LocalAccessor.class, name = "sixth")
    @ConstantOperand(type = LocalAccessor.class, name = "seventh")
    @ConstantOperand(type = LocalAccessor.class, name = "eighth")
    @ConstantOperand(type = LocalAccessor.class, name = "ninth")
    @ConstantOperand(type = LocalAccessor.class, name = "tenth")
    @ConstantOperand(type = LocalAccessor.class, name = "eleventh")
    @ConstantOperand(type = LocalAccessor.class, name = "twelfth")
    @ConstantOperand(type = LocalAccessor.class, name = "thirteenth")
    @ConstantOperand(type = LocalAccessor.class, name = "fourteenth")
    @ConstantOperand(type = LocalAccessor.class, name = "fifteenth")
    @ConstantOperand(type = LocalAccessor.class, name = "sixteenth")
    public static final class VectorWord8Unpack {
        @Specialization public static void unpack(VirtualFrame frame, LocalAccessor first, LocalAccessor second, LocalAccessor third,
                LocalAccessor fourth, LocalAccessor fifth, LocalAccessor sixth,
                LocalAccessor seventh, LocalAccessor eighth, LocalAccessor ninth,
                LocalAccessor tenth, LocalAccessor eleventh, LocalAccessor twelfth,
                LocalAccessor thirteenth, LocalAccessor fourteenth, LocalAccessor fifteenth,
                LocalAccessor sixteenth,
                ByteVector value, @Bind("$node") Node node) {
            value = RuntimeTypes.requireByte(value, ByteVector.SPECIES_128);
            BytecodeNode bytecode = ((BytecodeRoot) node.getRootNode()).getBytecodeNode();
            first.setInt(bytecode, frame, value.lane(0) & 0xff); second.setInt(bytecode, frame, value.lane(1) & 0xff);
            third.setInt(bytecode, frame, value.lane(2) & 0xff); fourth.setInt(bytecode, frame, value.lane(3) & 0xff);
            fifth.setInt(bytecode, frame, value.lane(4) & 0xff); sixth.setInt(bytecode, frame, value.lane(5) & 0xff);
            seventh.setInt(bytecode, frame, value.lane(6) & 0xff); eighth.setInt(bytecode, frame, value.lane(7) & 0xff);
            ninth.setInt(bytecode, frame, value.lane(8) & 0xff); tenth.setInt(bytecode, frame, value.lane(9) & 0xff);
            eleventh.setInt(bytecode, frame, value.lane(10) & 0xff); twelfth.setInt(bytecode, frame, value.lane(11) & 0xff);
            thirteenth.setInt(bytecode, frame, value.lane(12) & 0xff); fourteenth.setInt(bytecode, frame, value.lane(13) & 0xff);
            fifteenth.setInt(bytecode, frame, value.lane(14) & 0xff); sixteenth.setInt(bytecode, frame, value.lane(15) & 0xff);
        }
    }

    @Operation public static final class Vector8Pack {
        @Specialization public static ByteVector pack(int first, int second, int third, int fourth,
                int fifth, int sixth, int seventh, int eighth,
                int ninth, int tenth, int eleventh, int twelfth,
                int thirteenth, int fourteenth, int fifteenth, int sixteenth) {
            return ByteVector.broadcast(ByteVector.SPECIES_128, (byte) first).withLane(1, (byte) second).withLane(2, (byte) third).withLane(3, (byte) fourth).withLane(4, (byte) fifth).withLane(5, (byte) sixth).withLane(6, (byte) seventh).withLane(7, (byte) eighth).withLane(8, (byte) ninth).withLane(9, (byte) tenth).withLane(10, (byte) eleventh).withLane(11, (byte) twelfth).withLane(12, (byte) thirteenth).withLane(13, (byte) fourteenth).withLane(14, (byte) fifteenth).withLane(15, (byte) sixteenth);
        }
    }
    @Operation public static final class Vector8Broadcast {
        @Specialization public static ByteVector broadcast(int value) { return ByteVector.broadcast(ByteVector.SPECIES_128, (byte) value); }
    }
    @Operation public static final class Vector8Negate {
        @Specialization public static ByteVector negate(ByteVector value) {
            value = RuntimeTypes.requireByte(value, ByteVector.SPECIES_128); return (value).neg(); }
    }
    @Operation @ConstantOperand(type = int.class, name = "operation")
    public static final class Vector8Binary {
        @Specialization public static ByteVector binary(int operation, ByteVector first, ByteVector second) {
            first = RuntimeTypes.requireByte(first, ByteVector.SPECIES_128);
            second = RuntimeTypes.requireByte(second, ByteVector.SPECIES_128);
            return switch (operation) {
                case 0 -> (first).add(second);
                case 1 -> (first).sub(second);
                case 2 -> (first).mul(second);
                default -> throw new RuntimeFault("Invalid Int8X16 operation");
            };
        }
    }
    @Operation
    @ConstantOperand(type = LocalAccessor.class, name = "first")
    @ConstantOperand(type = LocalAccessor.class, name = "second")
    @ConstantOperand(type = LocalAccessor.class, name = "third")
    @ConstantOperand(type = LocalAccessor.class, name = "fourth")
    @ConstantOperand(type = LocalAccessor.class, name = "fifth")
    @ConstantOperand(type = LocalAccessor.class, name = "sixth")
    @ConstantOperand(type = LocalAccessor.class, name = "seventh")
    @ConstantOperand(type = LocalAccessor.class, name = "eighth")
    @ConstantOperand(type = LocalAccessor.class, name = "ninth")
    @ConstantOperand(type = LocalAccessor.class, name = "tenth")
    @ConstantOperand(type = LocalAccessor.class, name = "eleventh")
    @ConstantOperand(type = LocalAccessor.class, name = "twelfth")
    @ConstantOperand(type = LocalAccessor.class, name = "thirteenth")
    @ConstantOperand(type = LocalAccessor.class, name = "fourteenth")
    @ConstantOperand(type = LocalAccessor.class, name = "fifteenth")
    @ConstantOperand(type = LocalAccessor.class, name = "sixteenth")
    public static final class Vector8Unpack {
        @Specialization public static void unpack(VirtualFrame frame, LocalAccessor first, LocalAccessor second, LocalAccessor third,
                LocalAccessor fourth, LocalAccessor fifth, LocalAccessor sixth,
                LocalAccessor seventh, LocalAccessor eighth, LocalAccessor ninth,
                LocalAccessor tenth, LocalAccessor eleventh, LocalAccessor twelfth,
                LocalAccessor thirteenth, LocalAccessor fourteenth, LocalAccessor fifteenth,
                LocalAccessor sixteenth,
                ByteVector value, @Bind("$node") Node node) {
            value = RuntimeTypes.requireByte(value, ByteVector.SPECIES_128);
            BytecodeNode bytecode = ((BytecodeRoot) node.getRootNode()).getBytecodeNode();
            first.setInt(bytecode, frame, value.lane(0)); second.setInt(bytecode, frame, value.lane(1));
            third.setInt(bytecode, frame, value.lane(2)); fourth.setInt(bytecode, frame, value.lane(3));
            fifth.setInt(bytecode, frame, value.lane(4)); sixth.setInt(bytecode, frame, value.lane(5));
            seventh.setInt(bytecode, frame, value.lane(6)); eighth.setInt(bytecode, frame, value.lane(7));
            ninth.setInt(bytecode, frame, value.lane(8)); tenth.setInt(bytecode, frame, value.lane(9));
            eleventh.setInt(bytecode, frame, value.lane(10)); twelfth.setInt(bytecode, frame, value.lane(11));
            thirteenth.setInt(bytecode, frame, value.lane(12)); fourteenth.setInt(bytecode, frame, value.lane(13));
            fifteenth.setInt(bytecode, frame, value.lane(14)); sixteenth.setInt(bytecode, frame, value.lane(15));
        }
    }
    @Operation public static final class VectorWord32Pack {
        @Specialization public static IntVector pack(int first, int second, int third, int fourth) {
            return IntVector.broadcast(IntVector.SPECIES_128, (int) first).withLane(1, (int) second).withLane(2, (int) third).withLane(3, (int) fourth);
        }
    }
    @Operation public static final class VectorWord32Broadcast {
        @Specialization public static IntVector broadcast(int value) { return IntVector.broadcast(IntVector.SPECIES_128, (int) value); }
    }
    @Operation @ConstantOperand(type = int.class, name = "operation")
    public static final class VectorWord32Binary {
        @Specialization public static IntVector binary(int operation, IntVector first, IntVector second) {
            first = RuntimeTypes.requireInt(first, IntVector.SPECIES_128);
            second = RuntimeTypes.requireInt(second, IntVector.SPECIES_128);
            return switch (operation) {
                case 0 -> (first).add(second);
                case 1 -> (first).sub(second);
                case 2 -> (first).mul(second);
                default -> throw new RuntimeFault("Invalid Word32X4 operation");
            };
        }
    }
    @Operation
    @ConstantOperand(type = LocalAccessor.class, name = "first")
    @ConstantOperand(type = LocalAccessor.class, name = "second")
    @ConstantOperand(type = LocalAccessor.class, name = "third")
    @ConstantOperand(type = LocalAccessor.class, name = "fourth")
    public static final class VectorWord32Unpack {
        @Specialization public static void unpack(VirtualFrame frame, LocalAccessor first, LocalAccessor second,
                LocalAccessor third, LocalAccessor fourth, IntVector value, @Bind("$node") Node node) {
            value = RuntimeTypes.requireInt(value, IntVector.SPECIES_128);
            BytecodeNode bytecode = ((BytecodeRoot) node.getRootNode()).getBytecodeNode();
            first.setInt(bytecode, frame, value.lane(0)); second.setInt(bytecode, frame, value.lane(1));
            third.setInt(bytecode, frame, value.lane(2)); fourth.setInt(bytecode, frame, value.lane(3));
        }
    }
    @Operation public static final class VectorWord16Pack {
        @Specialization public static ShortVector pack(int first, int second, int third, int fourth,
                int fifth, int sixth, int seventh, int eighth) {
            return ShortVector.broadcast(ShortVector.SPECIES_128, (short) first).withLane(1, (short) second).withLane(2, (short) third).withLane(3, (short) fourth).withLane(4, (short) fifth).withLane(5, (short) sixth).withLane(6, (short) seventh).withLane(7, (short) eighth);
        }
    }
    @Operation public static final class VectorWord16Broadcast {
        @Specialization public static ShortVector broadcast(int value) { return ShortVector.broadcast(ShortVector.SPECIES_128, (short) value); }
    }
    @Operation @ConstantOperand(type = int.class, name = "operation")
    public static final class VectorWord16Binary {
        @Specialization public static ShortVector binary(int operation, ShortVector first, ShortVector second) {
            first = RuntimeTypes.requireShort(first, ShortVector.SPECIES_128);
            second = RuntimeTypes.requireShort(second, ShortVector.SPECIES_128);
            return switch (operation) {
                case 0 -> (first).add(second);
                case 1 -> (first).sub(second);
                case 2 -> (first).mul(second);
                default -> throw new RuntimeFault("Invalid Word16X8 operation");
            };
        }
    }
    @Operation
    @ConstantOperand(type = LocalAccessor.class, name = "first")
    @ConstantOperand(type = LocalAccessor.class, name = "second")
    @ConstantOperand(type = LocalAccessor.class, name = "third")
    @ConstantOperand(type = LocalAccessor.class, name = "fourth")
    @ConstantOperand(type = LocalAccessor.class, name = "fifth")
    @ConstantOperand(type = LocalAccessor.class, name = "sixth")
    @ConstantOperand(type = LocalAccessor.class, name = "seventh")
    @ConstantOperand(type = LocalAccessor.class, name = "eighth")
    public static final class VectorWord16Unpack {
        @Specialization public static void unpack(VirtualFrame frame, LocalAccessor first, LocalAccessor second,
                LocalAccessor third, LocalAccessor fourth, LocalAccessor fifth, LocalAccessor sixth,
                LocalAccessor seventh, LocalAccessor eighth, ShortVector value, @Bind("$node") Node node) {
            value = RuntimeTypes.requireShort(value, ShortVector.SPECIES_128);
            BytecodeNode bytecode = ((BytecodeRoot) node.getRootNode()).getBytecodeNode();
            first.setInt(bytecode, frame, value.lane(0) & 0xffff); second.setInt(bytecode, frame, value.lane(1) & 0xffff);
            third.setInt(bytecode, frame, value.lane(2) & 0xffff); fourth.setInt(bytecode, frame, value.lane(3) & 0xffff);
            fifth.setInt(bytecode, frame, value.lane(4) & 0xffff); sixth.setInt(bytecode, frame, value.lane(5) & 0xffff);
            seventh.setInt(bytecode, frame, value.lane(6) & 0xffff); eighth.setInt(bytecode, frame, value.lane(7) & 0xffff);
        }
    }

    @Operation public static final class Vector16Pack {
        @Specialization public static ShortVector pack(int first, int second, int third, int fourth,
                int fifth, int sixth, int seventh, int eighth) {
            return ShortVector.broadcast(ShortVector.SPECIES_128, (short) first).withLane(1, (short) second).withLane(2, (short) third).withLane(3, (short) fourth).withLane(4, (short) fifth).withLane(5, (short) sixth).withLane(6, (short) seventh).withLane(7, (short) eighth);
        }
    }
    @Operation public static final class Vector16Broadcast {
        @Specialization public static ShortVector broadcast(int value) { return ShortVector.broadcast(ShortVector.SPECIES_128, (short) value); }
    }
    @Operation public static final class Vector16Negate {
        @Specialization public static ShortVector negate(ShortVector value) {
            value = RuntimeTypes.requireShort(value, ShortVector.SPECIES_128); return (value).neg(); }
    }
    @Operation @ConstantOperand(type = int.class, name = "operation")
    public static final class Vector16Binary {
        @Specialization public static ShortVector binary(int operation, ShortVector first, ShortVector second) {
            first = RuntimeTypes.requireShort(first, ShortVector.SPECIES_128);
            second = RuntimeTypes.requireShort(second, ShortVector.SPECIES_128);
            return switch (operation) {
                case 0 -> (first).add(second);
                case 1 -> (first).sub(second);
                case 2 -> (first).mul(second);
                default -> throw new RuntimeFault("Invalid Int16X8 operation");
            };
        }
    }
    @Operation
    @ConstantOperand(type = LocalAccessor.class, name = "first")
    @ConstantOperand(type = LocalAccessor.class, name = "second")
    @ConstantOperand(type = LocalAccessor.class, name = "third")
    @ConstantOperand(type = LocalAccessor.class, name = "fourth")
    @ConstantOperand(type = LocalAccessor.class, name = "fifth")
    @ConstantOperand(type = LocalAccessor.class, name = "sixth")
    @ConstantOperand(type = LocalAccessor.class, name = "seventh")
    @ConstantOperand(type = LocalAccessor.class, name = "eighth")
    public static final class Vector16Unpack {
        @Specialization public static void unpack(VirtualFrame frame, LocalAccessor first, LocalAccessor second,
                LocalAccessor third, LocalAccessor fourth, LocalAccessor fifth, LocalAccessor sixth,
                LocalAccessor seventh, LocalAccessor eighth, ShortVector value, @Bind("$node") Node node) {
            value = RuntimeTypes.requireShort(value, ShortVector.SPECIES_128);
            BytecodeNode bytecode = ((BytecodeRoot) node.getRootNode()).getBytecodeNode();
            first.setInt(bytecode, frame, value.lane(0)); second.setInt(bytecode, frame, value.lane(1));
            third.setInt(bytecode, frame, value.lane(2)); fourth.setInt(bytecode, frame, value.lane(3));
            fifth.setInt(bytecode, frame, value.lane(4)); sixth.setInt(bytecode, frame, value.lane(5));
            seventh.setInt(bytecode, frame, value.lane(6)); eighth.setInt(bytecode, frame, value.lane(7));
        }
    }

    @Operation public static final class Vector32Pack {
        @Specialization public static IntVector pack(int first, int second, int third, int fourth) {
            return IntVector.broadcast(IntVector.SPECIES_128, (int) first).withLane(1, (int) second).withLane(2, (int) third).withLane(3, (int) fourth);
        }
    }
    @Operation public static final class Vector32Broadcast {
        @Specialization public static IntVector broadcast(int value) {
            int lane = (int) value;
            return IntVector.broadcast(IntVector.SPECIES_128, lane);
        }
    }
    @Operation public static final class Vector32Negate {
        @Specialization public static IntVector negate(IntVector value) {
            value = RuntimeTypes.requireInt(value, IntVector.SPECIES_128); return (value).neg(); }
    }
    @Operation public static final class Vector32Multiply {
        @Specialization public static IntVector multiply(IntVector first, IntVector second) {
            first = RuntimeTypes.requireInt(first, IntVector.SPECIES_128);
            second = RuntimeTypes.requireInt(second, IntVector.SPECIES_128); return (first).mul(second); }
    }
    @Operation @ConstantOperand(type = boolean.class, name = "subtract")
    public static final class Vector32Binary {
        @Specialization public static IntVector binary(boolean subtract, IntVector first, IntVector second) {
            first = RuntimeTypes.requireInt(first, IntVector.SPECIES_128);
            second = RuntimeTypes.requireInt(second, IntVector.SPECIES_128);
            return subtract ? (first).sub(second) : (first).add(second);
        }
    }
    @Operation
    @ConstantOperand(type = LocalAccessor.class, name = "first")
    @ConstantOperand(type = LocalAccessor.class, name = "second")
    @ConstantOperand(type = LocalAccessor.class, name = "third")
    @ConstantOperand(type = LocalAccessor.class, name = "fourth")
    public static final class Vector32Unpack {
        @Specialization public static void unpack(VirtualFrame frame, LocalAccessor first, LocalAccessor second,
                LocalAccessor third, LocalAccessor fourth, IntVector value, @Bind("$node") Node node) {
            value = RuntimeTypes.requireInt(value, IntVector.SPECIES_128);
            BytecodeNode bytecode = ((BytecodeRoot) node.getRootNode()).getBytecodeNode();
            first.setInt(bytecode, frame, value.lane(0)); second.setInt(bytecode, frame, value.lane(1));
            third.setInt(bytecode, frame, value.lane(2)); fourth.setInt(bytecode, frame, value.lane(3));
        }
    }

    @Operation public static final class VectorFloatPack {
        @Specialization public static FloatVector pack(float first, float second, float third, float fourth) {
            return FloatVector.broadcast(FloatVector.SPECIES_128, first).withLane(1, second).withLane(2, third).withLane(3, fourth);
        }
    }
    @Operation public static final class VectorFloatBroadcast {
        @Specialization public static FloatVector broadcast(float value) { return FloatVector.broadcast(FloatVector.SPECIES_128, value); }
    }
    @Operation @ConstantOperand(type = int.class, name = "operation")
    public static final class VectorFloatBinary {
        @Specialization public static FloatVector binary(int operation, FloatVector first, FloatVector second) {
            first = RuntimeTypes.requireFloat(first, FloatVector.SPECIES_128);
            second = RuntimeTypes.requireFloat(second, FloatVector.SPECIES_128);
            return switch (operation) {
                case 0 -> (first).add(second);
                case 1 -> (first).sub(second);
                case 2 -> (first).mul(second);
                default -> throw new RuntimeFault("Invalid FloatX4 operation");
            };
        }
    }
    @Operation @ConstantOperand(type = int.class, name = "operation")
    public static final class VectorFloatFused {
        @Specialization public static FloatVector apply(int operation, FloatVector first, FloatVector second, FloatVector third) {
            first = RuntimeTypes.requireFloat(first, FloatVector.SPECIES_128);
            second = RuntimeTypes.requireFloat(second, FloatVector.SPECIES_128);
            third = RuntimeTypes.requireFloat(third, FloatVector.SPECIES_128);
            if (operation < 0 || operation > 3) throw new RuntimeFault("Invalid FloatX4 fused operation");
            var left = operation >= 2 ? first.neg() : first;
            var addend = (operation & 1) != 0 ? third.neg() : third;
            return left.fma(second, addend);
        }
    }
    @Operation @ConstantOperand(type = int.class, name = "operation")
    public static final class VectorFloat8Fused {
        @Specialization public static FloatVector apply(int operation, FloatVector first, FloatVector second, FloatVector third) {
            first = RuntimeTypes.requireFloat(first, FloatVector.SPECIES_256);
            second = RuntimeTypes.requireFloat(second, FloatVector.SPECIES_256);
            third = RuntimeTypes.requireFloat(third, FloatVector.SPECIES_256);
            if (operation < 0 || operation > 3) throw new RuntimeFault("Invalid FloatX8 fused operation");
            var left = operation >= 2 ? first.neg() : first;
            var addend = (operation & 1) != 0 ? third.neg() : third;
            return left.fma(second, addend);
        }
    }
    @Operation @ConstantOperand(type = int.class, name = "operation")
    public static final class VectorFloat16Fused {
        @Specialization public static FloatVector apply(int operation, FloatVector first, FloatVector second, FloatVector third) {
            first = RuntimeTypes.requireFloat(first, FloatVector.SPECIES_512);
            second = RuntimeTypes.requireFloat(second, FloatVector.SPECIES_512);
            third = RuntimeTypes.requireFloat(third, FloatVector.SPECIES_512);
            if (operation < 0 || operation > 3) throw new RuntimeFault("Invalid FloatX16 fused operation");
            var left = operation >= 2 ? first.neg() : first;
            var addend = (operation & 1) != 0 ? third.neg() : third;
            return left.fma(second, addend);
        }
    }
    @Operation @ConstantOperand(type = int.class, name = "operation")
    public static final class VectorDouble4Fused {
        @Specialization public static DoubleVector apply(int operation, DoubleVector first, DoubleVector second, DoubleVector third) {
            first = RuntimeTypes.requireDouble(first, DoubleVector.SPECIES_256);
            second = RuntimeTypes.requireDouble(second, DoubleVector.SPECIES_256);
            third = RuntimeTypes.requireDouble(third, DoubleVector.SPECIES_256);
            if (operation < 0 || operation > 3) throw new RuntimeFault("Invalid DoubleX4 fused operation");
            var left = operation >= 2 ? first.neg() : first;
            var addend = (operation & 1) != 0 ? third.neg() : third;
            return left.fma(second, addend);
        }
    }
    @Operation @ConstantOperand(type = int.class, name = "operation")
    public static final class VectorDouble8Fused {
        @Specialization public static DoubleVector apply(int operation, DoubleVector first, DoubleVector second, DoubleVector third) {
            first = RuntimeTypes.requireDouble(first, DoubleVector.SPECIES_512);
            second = RuntimeTypes.requireDouble(second, DoubleVector.SPECIES_512);
            third = RuntimeTypes.requireDouble(third, DoubleVector.SPECIES_512);
            if (operation < 0 || operation > 3) throw new RuntimeFault("Invalid DoubleX8 fused operation");
            var left = operation >= 2 ? first.neg() : first;
            var addend = (operation & 1) != 0 ? third.neg() : third;
            return left.fma(second, addend);
        }
    }
    @Operation
    @ConstantOperand(type = LocalAccessor.class, name = "first")
    @ConstantOperand(type = LocalAccessor.class, name = "second")
    @ConstantOperand(type = LocalAccessor.class, name = "third")
    @ConstantOperand(type = LocalAccessor.class, name = "fourth")
    public static final class VectorFloatUnpack {
        @Specialization public static void unpack(VirtualFrame frame, LocalAccessor first, LocalAccessor second,
                LocalAccessor third, LocalAccessor fourth, FloatVector value, @Bind("$node") Node node) {
            value = RuntimeTypes.requireFloat(value, FloatVector.SPECIES_128);
            BytecodeNode bytecode = ((BytecodeRoot) node.getRootNode()).getBytecodeNode();
            first.setFloat(bytecode, frame, value.lane(0)); second.setFloat(bytecode, frame, value.lane(1));
            third.setFloat(bytecode, frame, value.lane(2)); fourth.setFloat(bytecode, frame, value.lane(3));
        }
    }

    @Operation public static final class VectorDoublePack {
        @Specialization public static DoubleVector pack(double first, double second) {
            return DoubleVector.broadcast(DoubleVector.SPECIES_128, first).withLane(1, second);
        }
    }
    @Operation public static final class VectorDoubleBroadcast {
        @Specialization public static DoubleVector broadcast(double value) { return DoubleVector.broadcast(DoubleVector.SPECIES_128, value); }
    }
    @Operation @ConstantOperand(type = int.class, name = "operation")
    public static final class VectorDoubleBinary {
        @Specialization public static DoubleVector binary(int operation, DoubleVector first, DoubleVector second) {
            first = RuntimeTypes.requireDouble(first, DoubleVector.SPECIES_128);
            second = RuntimeTypes.requireDouble(second, DoubleVector.SPECIES_128);
            return switch (operation) {
                case 0 -> (first).add(second);
                case 1 -> (first).sub(second);
                case 2 -> (first).mul(second);
                default -> throw new RuntimeFault("Invalid DoubleX2 operation");
            };
        }
    }
    @Operation
    @ConstantOperand(type = LocalAccessor.class, name = "first")
    @ConstantOperand(type = LocalAccessor.class, name = "second")
    public static final class VectorDoubleUnpack {
        @Specialization public static void unpack(VirtualFrame frame, LocalAccessor first, LocalAccessor second, DoubleVector value, @Bind("$node") Node node) {
            value = RuntimeTypes.requireDouble(value, DoubleVector.SPECIES_128);
            BytecodeNode bytecode = ((BytecodeRoot) node.getRootNode()).getBytecodeNode();
            first.setDouble(bytecode, frame, value.lane(0)); second.setDouble(bytecode, frame, value.lane(1));
        }
    }

    @Operation @ConstantOperand(type = int.class, name = "operation")
    public static final class VectorDoubleFused {
        @Specialization public static DoubleVector apply(int operation, DoubleVector first, DoubleVector second, DoubleVector third) {
            first = RuntimeTypes.requireDouble(first, DoubleVector.SPECIES_128);
            second = RuntimeTypes.requireDouble(second, DoubleVector.SPECIES_128);
            third = RuntimeTypes.requireDouble(third, DoubleVector.SPECIES_128);
            if (operation < 0 || operation > 3) throw new RuntimeFault("Invalid DoubleX2 fused operation");
            var left = operation >= 2 ? first.neg() : first;
            var addend = (operation & 1) != 0 ? third.neg() : third;
            return left.fma(second, addend);
        }
    }

    /** Evaluates a zero-width field for effects while producing no destination value. */
    @Operation public static final class DiscardVoid {
        @Specialization public static void discard(Object value) { TupleResults.requireVoidCarrier(value); }
    }

    @Operation public static final class Add { @Specialization public static long apply(long x, long y) { return x + y; } }
    @Operation public static final class Subtract { @Specialization public static long apply(long x, long y) { return x - y; } }
    @Operation public static final class Multiply { @Specialization public static long apply(long x, long y) { return x * y; } }
    @Operation public static final class Negate { @Specialization public static long apply(long x) { return -x; } }
    @Operation public static final class Quotient { @Specialization public static long apply(long x, long y) { return x / y; } }
    @Operation public static final class Remainder {
        @Specialization public static long apply(long x, long y) {
            // Graal 25.3.4.1 can retain a dead Phi while virtualizing a remainder
            // stored in a bytecode frame. The quotient form has the same wrapping
            // long semantics, including MIN_VALUE / -1 and division by zero.
            return x - (x / y) * y;
        }
    }
    /** No forcing or thunk-indirection traversal: compare the current operand references. */
    @Operation public static final class PointerEqual {
        @Specialization public static long apply(Object left, Object right) { return left == right ? 1L : 0L; }
    }
    @Operation public static final class Equal { @Specialization public static long apply(long x, long y) { return x == y ? 1L : 0L; } }
    @Operation public static final class NotEqual { @Specialization public static long apply(long x, long y) { return x != y ? 1L : 0L; } }
    @Operation public static final class LessThan { @Specialization public static long apply(long x, long y) { return x < y ? 1L : 0L; } }
    @Operation public static final class LessThanUnsigned { @Specialization public static long apply(long x, long y) { return Long.compareUnsigned(x, y) < 0 ? 1L : 0L; } }
    @Operation public static final class LessEqual { @Specialization public static long apply(long x, long y) { return x <= y ? 1L : 0L; } }
    @Operation public static final class LessEqualUnsigned { @Specialization public static long apply(long x, long y) { return Long.compareUnsigned(x, y) <= 0 ? 1L : 0L; } }
    @Operation public static final class GreaterThan { @Specialization public static long apply(long x, long y) { return x > y ? 1L : 0L; } }
    @Operation public static final class GreaterEqual { @Specialization public static long apply(long x, long y) { return x >= y ? 1L : 0L; } }
    @Operation public static final class BitAnd { @Specialization public static long apply(long x, long y) { return x & y; } }
    @Operation public static final class BitOr { @Specialization public static long apply(long x, long y) { return x | y; } }
    @Operation public static final class BitXor { @Specialization public static long apply(long x, long y) { return x ^ y; } }
    @Operation public static final class BitNot { @Specialization public static long apply(long x) { return ~x; } }
    @Operation public static final class CountLeadingZeros { @Specialization public static long apply(long x) { return Long.numberOfLeadingZeros(x); } }
    @Operation public static final class CountTrailingZeros { @Specialization public static long apply(long x) { return Long.numberOfTrailingZeros(x); } }
    @Operation public static final class PopulationCount { @Specialization public static long apply(long x) { return Long.bitCount(x); } }
    // Fixed shifts encode GHC bit widths; zero inputs retain width-sized clz/ctz results.
    @Operation @ConstantOperand(type = int.class, name = "shift")
    public static final class PopulationCountWidth { @Specialization public static long apply(int shift, long x) { return Long.bitCount(x & (-1L >>> shift)); } }
    @Operation @ConstantOperand(type = int.class, name = "shift")
    public static final class CountLeadingZerosWidth { @Specialization public static long apply(int shift, long x) { return Long.numberOfLeadingZeros(x & (-1L >>> shift)) - shift; } }
    @Operation @ConstantOperand(type = int.class, name = "shift")
    public static final class CountTrailingZerosWidth { @Specialization public static long apply(int shift, long x) { return Math.min(Long.numberOfTrailingZeros(x & (-1L >>> shift)), 64 - shift); } }
    @Operation @ConstantOperand(type = int.class, name = "shift")
    public static final class ByteSwapWidth { @Specialization public static long apply(int shift, long x) { return Long.reverseBytes(x) >>> shift; } }
    @Operation @ConstantOperand(type = int.class, name = "shift")
    public static final class BitReverseWidth { @Specialization public static long apply(int shift, long x) { return Long.reverse(x) >>> shift; } }
    @Operation @ConstantOperand(type = int.class, name = "shift")
    public static final class BitDepositWidth { @Specialization public static long apply(int shift, long x, long mask) {
        long widthMask = -1L >>> shift;
        return Long.expand(x & widthMask, mask & widthMask) & widthMask;
    } }
    @Operation @ConstantOperand(type = int.class, name = "shift")
    public static final class BitExtractWidth { @Specialization public static long apply(int shift, long x, long mask) {
        long widthMask = -1L >>> shift;
        return Long.compress(x & widthMask, mask & widthMask) & widthMask;
    } }
    @Operation public static final class ShiftLeft { @Specialization public static long apply(long x, long y) { return x << (int) y; } }
    @Operation public static final class ShiftRight { @Specialization public static long apply(long x, long y) { return x >> (int) y; } }
    @Operation public static final class ShiftRightUnsigned { @Specialization public static long apply(long x, long y) { return x >>> (int) y; } }
    @Operation @ConstantOperand(type = NarrowScalarOp.class, name = "operation")
    public static final class NarrowInt {
        @Specialization public static int apply(NarrowScalarOp operation, int x, int y) {
            return operation.intResult(x, y);
        }
    }
    @Operation @ConstantOperand(type = NarrowScalarOp.class, name = "operation")
    public static final class NarrowShift {
        @Specialization public static int apply(NarrowScalarOp operation, int x, long count) {
            return operation.intResult(x, (int) count);
        }
    }
    @Operation @ConstantOperand(type = NarrowScalarOp.class, name = "operation")
    public static final class NarrowFromLong {
        @Specialization public static int apply(NarrowScalarOp operation, long x) {
            return operation.intResult((int) x, 0);
        }
    }
    @Operation @ConstantOperand(type = NarrowScalarOp.class, name = "operation")
    public static final class NarrowToLong {
        @Specialization public static long apply(NarrowScalarOp operation, int x, int y) {
            return operation.longResult(x, y);
        }
    }
    // Explicit canonical GHC WordSlot projection boundaries, not implicit casts.
    @Operation @ConstantOperand(type = NarrowInteger.class, name = "integer")
    public static final class SumNarrowToWord {
        @Specialization public static long apply(NarrowInteger integer, int value) { return integer.widen(value); }
    }
    @Operation @ConstantOperand(type = NarrowInteger.class, name = "integer")
    public static final class SumWordToNarrow {
        @Specialization public static int apply(NarrowInteger integer, long value) { return integer.narrow((int) value); }
    }
    @Operation public static final class Narrow8 { @Specialization public static long apply(long x) { return (byte) x; } }
    @Operation public static final class Narrow16 { @Specialization public static long apply(long x) { return (short) x; } }
    @Operation public static final class Narrow32 { @Specialization public static long apply(long x) { return (int) x; } }
    // Constant masks are at most 0xffffffffL: every narrow unsigned result is a nonnegative Long.
    @Operation @ConstantOperand(type = long.class, name = "mask")
    public static final class NarrowWord { @Specialization public static long apply(long mask, long x) { return x & mask; } }
    @Operation public static final class QuotientUnsigned { @Specialization public static long apply(long x, long y) { return Long.divideUnsigned(x, y); } }
    @Operation public static final class RemainderUnsigned { @Specialization public static long apply(long x, long y) { return Long.remainderUnsigned(x, y); } }
    @Operation public static final class GreaterThanUnsigned { @Specialization public static long apply(long x, long y) { return Long.compareUnsigned(x, y) > 0 ? 1L : 0L; } }
    @Operation public static final class GreaterEqualUnsigned { @Specialization public static long apply(long x, long y) { return Long.compareUnsigned(x, y) >= 0 ? 1L : 0L; } }
    @Operation public static final class MultiplyIntMayOverflow { @Specialization public static long apply(long x, long y) { return Math.multiplyHigh(x, y) != ((x * y) >> 63) ? 1L : 0L; } }
    @Operation public static final class FloatAdd { @Specialization public static float apply(float x, float y) { return x + y; } }
    @Operation public static final class FloatSubtract { @Specialization public static float apply(float x, float y) { return x - y; } }
    @Operation public static final class FloatMultiply { @Specialization public static float apply(float x, float y) { return x * y; } }
    @Operation public static final class FloatDivide { @Specialization public static float apply(float x, float y) { return x / y; } }
    @Operation public static final class FloatNegate { @Specialization public static float apply(float x) { return -x; } }
    @Operation public static final class FloatSqrt { @Specialization public static float apply(float x) { return (float) Math.sqrt(x); } }
    @Operation public static final class FloatEqual { @Specialization public static long apply(float x, float y) { return x == y ? 1L : 0L; } }
    @Operation public static final class FloatNotEqual { @Specialization public static long apply(float x, float y) { return x != y ? 1L : 0L; } }
    @Operation public static final class FloatLess { @Specialization public static long apply(float x, float y) { return x < y ? 1L : 0L; } }
    @Operation public static final class FloatLessEqual { @Specialization public static long apply(float x, float y) { return x <= y ? 1L : 0L; } }
    @Operation public static final class FloatGreater { @Specialization public static long apply(float x, float y) { return x > y ? 1L : 0L; } }
    @Operation public static final class FloatGreaterEqual { @Specialization public static long apply(float x, float y) { return x >= y ? 1L : 0L; } }
    @Operation public static final class DoubleAdd { @Specialization public static double apply(double x, double y) { return x + y; } }
    @Operation public static final class DoubleSubtract { @Specialization public static double apply(double x, double y) { return x - y; } }
    @Operation public static final class DoubleMultiply { @Specialization public static double apply(double x, double y) { return x * y; } }
    @Operation public static final class DoubleDivide { @Specialization public static double apply(double x, double y) { return x / y; } }
    @Operation public static final class DoubleNegate { @Specialization public static double apply(double x) { return -x; } }
    @Operation public static final class DoubleSqrt { @Specialization public static double apply(double x) { return Math.sqrt(x); } }
    @Operation public static final class FloatFMAdd { @Specialization public static float apply(float x, float y, float z) { return Math.fma(x, y, z); } }
    @Operation public static final class FloatFMSub { @Specialization public static float apply(float x, float y, float z) { return Math.fma(x, y, -z); } }
    @Operation public static final class FloatFNMAdd { @Specialization public static float apply(float x, float y, float z) { return Math.fma(-x, y, z); } }
    @Operation public static final class FloatFNMSub { @Specialization public static float apply(float x, float y, float z) { return Math.fma(-x, y, -z); } }
    @Operation public static final class DoubleFMAdd { @Specialization public static double apply(double x, double y, double z) { return Math.fma(x, y, z); } }
    @Operation public static final class DoubleFMSub { @Specialization public static double apply(double x, double y, double z) { return Math.fma(x, y, -z); } }
    @Operation public static final class DoubleFNMAdd { @Specialization public static double apply(double x, double y, double z) { return Math.fma(-x, y, z); } }
    @Operation public static final class DoubleFNMSub { @Specialization public static double apply(double x, double y, double z) { return Math.fma(-x, y, -z); } }
    @Operation public static final class FloatAbs { @Specialization public static float apply(float x) { return Math.abs(x); } }
    @Operation public static final class FloatExp { @Specialization public static float apply(float x) { return (float) Math.exp(x); } }
    @Operation public static final class FloatExpm1 { @Specialization public static float apply(float x) { return (float) Math.expm1(x); } }
    @Operation public static final class FloatLog { @Specialization public static float apply(float x) { return (float) Math.log(x); } }
    @Operation public static final class FloatLog1p { @Specialization public static float apply(float x) { return (float) Math.log1p(x); } }
    @Operation public static final class FloatSin { @Specialization public static float apply(float x) { return (float) Math.sin(x); } }
    @Operation public static final class FloatCos { @Specialization public static float apply(float x) { return (float) Math.cos(x); } }
    @Operation public static final class FloatPower { @Specialization public static float apply(float x, float y) { return (float) Math.pow(x, y); } }
    @Operation public static final class DoubleAbs { @Specialization public static double apply(double x) { return Math.abs(x); } }
    @Operation public static final class DoubleExp { @Specialization public static double apply(double x) { return Math.exp(x); } }
    @Operation public static final class DoubleExpm1 { @Specialization public static double apply(double x) { return Math.expm1(x); } }
    @Operation public static final class DoubleLog { @Specialization public static double apply(double x) { return Math.log(x); } }
    @Operation public static final class DoubleLog1p { @Specialization public static double apply(double x) { return Math.log1p(x); } }
    @Operation public static final class DoubleSin { @Specialization public static double apply(double x) { return Math.sin(x); } }
    @Operation public static final class DoubleCos { @Specialization public static double apply(double x) { return Math.cos(x); } }
    @Operation public static final class DoublePower { @Specialization public static double apply(double x, double y) { return Math.pow(x, y); } }
    @Operation public static final class FloatTan { @Specialization public static float apply(float x) { return (float) Math.tan(x); } }
    @Operation public static final class FloatAsin { @Specialization public static float apply(float x) { return (float) Math.asin(x); } }
    @Operation public static final class FloatAcos { @Specialization public static float apply(float x) { return (float) Math.acos(x); } }
    @Operation public static final class FloatAtan { @Specialization public static float apply(float x) { return (float) Math.atan(x); } }
    @Operation public static final class FloatSinh { @Specialization public static float apply(float x) { return (float) Math.sinh(x); } }
    @Operation public static final class FloatCosh { @Specialization public static float apply(float x) { return (float) Math.cosh(x); } }
    @Operation public static final class FloatTanh { @Specialization public static float apply(float x) { return (float) Math.tanh(x); } }
    @Operation public static final class DoubleTan { @Specialization public static double apply(double x) { return Math.tan(x); } }
    @Operation public static final class DoubleAsin { @Specialization public static double apply(double x) { return Math.asin(x); } }
    @Operation public static final class DoubleAcos { @Specialization public static double apply(double x) { return Math.acos(x); } }
    @Operation public static final class DoubleAtan { @Specialization public static double apply(double x) { return Math.atan(x); } }
    @Operation public static final class DoubleSinh { @Specialization public static double apply(double x) { return Math.sinh(x); } }
    @Operation public static final class DoubleCosh { @Specialization public static double apply(double x) { return Math.cosh(x); } }
    @Operation public static final class DoubleTanh { @Specialization public static double apply(double x) { return Math.tanh(x); } }
    @Operation public static final class FloatAsinh { @Specialization public static float apply(float x) { return (float) InverseHyperbolic.asinh(x); } }
    @Operation public static final class FloatAcosh { @Specialization public static float apply(float x) { return (float) InverseHyperbolic.acosh(x); } }
    @Operation public static final class FloatAtanh { @Specialization public static float apply(float x) { return (float) InverseHyperbolic.atanh(x); } }
    @Operation public static final class FloatMin { @Specialization public static float apply(float x, float y) { return x < y ? x : y; } }
    @Operation public static final class FloatMax { @Specialization public static float apply(float x, float y) { return x > y ? x : y; } }
    @Operation public static final class DoubleAsinh { @Specialization public static double apply(double x) { return InverseHyperbolic.asinh(x); } }
    @Operation public static final class DoubleAcosh { @Specialization public static double apply(double x) { return InverseHyperbolic.acosh(x); } }
    @Operation public static final class DoubleAtanh { @Specialization public static double apply(double x) { return InverseHyperbolic.atanh(x); } }
    @Operation public static final class DoubleMin { @Specialization public static double apply(double x, double y) { return x < y ? x : y; } }
    @Operation public static final class DoubleMax { @Specialization public static double apply(double x, double y) { return x > y ? x : y; } }
    @Operation public static final class DoubleEqual { @Specialization public static long apply(double x, double y) { return x == y ? 1L : 0L; } }
    @Operation public static final class DoubleNotEqual { @Specialization public static long apply(double x, double y) { return x != y ? 1L : 0L; } }
    @Operation public static final class DoubleLess { @Specialization public static long apply(double x, double y) { return x < y ? 1L : 0L; } }
    @Operation public static final class DoubleLessEqual { @Specialization public static long apply(double x, double y) { return x <= y ? 1L : 0L; } }
    @Operation public static final class DoubleGreater { @Specialization public static long apply(double x, double y) { return x > y ? 1L : 0L; } }
    @Operation public static final class DoubleGreaterEqual { @Specialization public static long apply(double x, double y) { return x >= y ? 1L : 0L; } }
    @Operation public static final class CastFloatToWord32 { @Specialization public static int apply(float value) { return Float.floatToRawIntBits(value); } }
    @Operation public static final class CastWord32ToFloat { @Specialization public static float apply(int value) { return Float.intBitsToFloat(value); } }
    @Operation public static final class CastDoubleToWord64 { @Specialization public static long apply(double value) { return Double.doubleToRawLongBits(value); } }
    @Operation public static final class CastWord64ToDouble { @Specialization public static double apply(long value) { return Double.longBitsToDouble(value); } }
    @Operation public static final class IntToFloat { @Specialization public static float apply(long x) { return (float) x; } }
    @Operation public static final class IntToDouble { @Specialization public static double apply(long x) { return (double) x; } }
    @Operation public static final class WordToFloat { @Specialization public static float apply(long x) { return WordFloatingConversions.toFloat(x); } }
    @Operation public static final class WordToDouble { @Specialization public static double apply(long x) { return WordFloatingConversions.toDouble(x); } }
    @Operation public static final class FloatToInt { @Specialization public static long apply(float x) { return (long) x; } }
    @Operation public static final class DoubleToInt { @Specialization public static long apply(double x) { return (long) x; } }
    @Operation public static final class FloatToDouble { @Specialization public static double apply(float x) { return (double) x; } }
    @Operation public static final class DoubleToFloat { @Specialization public static float apply(double x) { return (float) x; } }
    @Operation public static final class ToFloat { @Specialization public static float apply(float x) { return x; } }
    @Operation public static final class ToDouble { @Specialization public static double apply(double x) { return x; } }
    @Operation public static final class ToInt {
        @Specialization public static int apply(int value) { return value; }
    }
    @Operation public static final class ToLong {
        @Specialization public static long apply(long x) { return x; }
        @Fallback public static long invalid(Object x) { throw fail("Expected primitive Long"); }
    }

    // Generic DSL operands make these declarations stateless. The compiler has
    // selected the exact carrier, but the actual ingress still checks it.
    @Operation
    @ConstantOperand(type = LocalAccessor.class, name = "local")
    public static final class StaticStoreLong {
        @Specialization public static void apply(VirtualFrame frame, LocalAccessor local, Object value,
                @Bind("$bytecodeNode") BytecodeNode bytecode) {
            local.setLong(bytecode, frame, staticLong(value));
        }
    }

    @Operation
    @ConstantOperand(type = LocalAccessor.class, name = "local")
    public static final class StaticLoadLong {
        @Specialization public static long apply(VirtualFrame frame, LocalAccessor local,
                @Bind("$bytecodeNode") BytecodeNode bytecode) {
            return staticLong(local.getObject(bytecode, frame));
        }
    }

    @Operation
    @ConstantOperand(type = LocalAccessor.class, name = "local")
    public static final class StaticStoreInt {
        @Specialization public static void apply(VirtualFrame frame, LocalAccessor local, Object value,
                @Bind("$bytecodeNode") BytecodeNode bytecode) {
            if (!(value instanceof Integer exact)) throw fail("Expected primitive Integer");
            local.setInt(bytecode, frame, exact);
        }
    }

    @Operation
    @ConstantOperand(type = LocalAccessor.class, name = "local")
    public static final class StaticLoadInt {
        @Specialization public static int apply(VirtualFrame frame, LocalAccessor local,
                @Bind("$bytecodeNode") BytecodeNode bytecode) {
            Object value = local.getObject(bytecode, frame);
            if (!(value instanceof Integer exact)) throw fail("Expected primitive Integer");
            return exact;
        }
    }

    @Operation
    @ConstantOperand(type = LocalAccessor.class, name = "local")
    public static final class StaticStoreFloat {
        @Specialization public static void apply(VirtualFrame frame, LocalAccessor local, Object value,
                @Bind("$bytecodeNode") BytecodeNode bytecode) {
            if (!(value instanceof Float exact)) throw fail("Expected primitive Float");
            local.setFloat(bytecode, frame, exact);
        }
    }

    @Operation
    @ConstantOperand(type = LocalAccessor.class, name = "local")
    public static final class StaticLoadFloat {
        @Specialization public static float apply(VirtualFrame frame, LocalAccessor local,
                @Bind("$bytecodeNode") BytecodeNode bytecode) {
            Object value = local.getObject(bytecode, frame);
            if (!(value instanceof Float exact)) throw fail("Expected primitive Float");
            return exact;
        }
    }

    @Operation
    @ConstantOperand(type = LocalAccessor.class, name = "local")
    public static final class StaticStoreDouble {
        @Specialization public static void apply(VirtualFrame frame, LocalAccessor local, Object value,
                @Bind("$bytecodeNode") BytecodeNode bytecode) {
            if (!(value instanceof Double exact)) throw fail("Expected primitive Double");
            local.setDouble(bytecode, frame, exact);
        }
    }

    @Operation
    @ConstantOperand(type = LocalAccessor.class, name = "local")
    public static final class StaticLoadDouble {
        @Specialization public static double apply(VirtualFrame frame, LocalAccessor local,
                @Bind("$bytecodeNode") BytecodeNode bytecode) {
            Object value = local.getObject(bytecode, frame);
            if (!(value instanceof Double exact)) throw fail("Expected primitive Double");
            return exact;
        }
    }

    /** Compiler-owned suspension scratch registers have Object carriers on every
     * write. Stateless operations also avoid first-use StoreLocal quickening. */
    @Operation
    @ConstantOperand(type = LocalAccessor.class, name = "local")
    public static final class StaticStoreObject {
        @Specialization public static void apply(VirtualFrame frame, LocalAccessor local, Object value,
                @Bind("$bytecodeNode") BytecodeNode bytecode) {
            local.setObject(bytecode, frame, value);
        }
    }

    @Operation
    @ConstantOperand(type = LocalAccessor.class, name = "local")
    public static final class StaticLoadObject {
        @Specialization public static Object apply(VirtualFrame frame, LocalAccessor local,
                @Bind("$bytecodeNode") BytecodeNode bytecode) {
            return local.getObject(bytecode, frame);
        }
    }

    @Operation
    @ConstantOperand(type = int.class, name = "operation")
    public static final class StaticLongArithmetic {
        @Specialization public static long apply(int operation, Object left, Object right) {
            long x = staticLong(left);
            long y = staticLong(right);
            return switch (operation) {
                case 0 -> x + y;
                case 1 -> x - y;
                case 2 -> x * y;
                default -> throw fail("Invalid static Long arithmetic operation");
            };
        }
    }

    private static long staticLong(Object value) {
        if (value instanceof Long result) return result;
        throw fail("Expected primitive Long");
    }

    private static RuntimeFault fail(String message) {
        CompilerDirectives.transferToInterpreterAndInvalidate();
        return new RuntimeFault(message);
    }
    // BEGIN GENERATED SIMD FAMILIES
    @Operation
    @ConstantOperand(type = BytecodeVectorLanes.class, name = "lanes")
    public static final class GeneratedInt8X32Pack {
        @Specialization public static ByteVector apply(VirtualFrame frame, BytecodeVectorLanes lanes, @Bind("$node") Node node) {
            BytecodeNode bytecode = ((BytecodeRoot) node.getRootNode()).getBytecodeNode();
            try {
                return ByteVector.broadcast(ByteVector.SPECIES_256, (byte) lanes.getSlots()[0].getInt(bytecode, frame)).withLane(1, (byte) lanes.getSlots()[1].getInt(bytecode, frame)).withLane(2, (byte) lanes.getSlots()[2].getInt(bytecode, frame)).withLane(3, (byte) lanes.getSlots()[3].getInt(bytecode, frame)).withLane(4, (byte) lanes.getSlots()[4].getInt(bytecode, frame)).withLane(5, (byte) lanes.getSlots()[5].getInt(bytecode, frame)).withLane(6, (byte) lanes.getSlots()[6].getInt(bytecode, frame)).withLane(7, (byte) lanes.getSlots()[7].getInt(bytecode, frame)).withLane(8, (byte) lanes.getSlots()[8].getInt(bytecode, frame)).withLane(9, (byte) lanes.getSlots()[9].getInt(bytecode, frame)).withLane(10, (byte) lanes.getSlots()[10].getInt(bytecode, frame)).withLane(11, (byte) lanes.getSlots()[11].getInt(bytecode, frame)).withLane(12, (byte) lanes.getSlots()[12].getInt(bytecode, frame)).withLane(13, (byte) lanes.getSlots()[13].getInt(bytecode, frame)).withLane(14, (byte) lanes.getSlots()[14].getInt(bytecode, frame)).withLane(15, (byte) lanes.getSlots()[15].getInt(bytecode, frame)).withLane(16, (byte) lanes.getSlots()[16].getInt(bytecode, frame)).withLane(17, (byte) lanes.getSlots()[17].getInt(bytecode, frame)).withLane(18, (byte) lanes.getSlots()[18].getInt(bytecode, frame)).withLane(19, (byte) lanes.getSlots()[19].getInt(bytecode, frame)).withLane(20, (byte) lanes.getSlots()[20].getInt(bytecode, frame)).withLane(21, (byte) lanes.getSlots()[21].getInt(bytecode, frame)).withLane(22, (byte) lanes.getSlots()[22].getInt(bytecode, frame)).withLane(23, (byte) lanes.getSlots()[23].getInt(bytecode, frame)).withLane(24, (byte) lanes.getSlots()[24].getInt(bytecode, frame)).withLane(25, (byte) lanes.getSlots()[25].getInt(bytecode, frame)).withLane(26, (byte) lanes.getSlots()[26].getInt(bytecode, frame)).withLane(27, (byte) lanes.getSlots()[27].getInt(bytecode, frame)).withLane(28, (byte) lanes.getSlots()[28].getInt(bytecode, frame)).withLane(29, (byte) lanes.getSlots()[29].getInt(bytecode, frame)).withLane(30, (byte) lanes.getSlots()[30].getInt(bytecode, frame)).withLane(31, (byte) lanes.getSlots()[31].getInt(bytecode, frame));
            } catch (com.oracle.truffle.api.nodes.UnexpectedResultException invalid) {
                throw new RuntimeFault("Expected primitive vector lane");
            }
        }
    }
    @Operation
    @ConstantOperand(type = BytecodeVectorLanes.class, name = "lanes")
    public static final class GeneratedInt8X32Unpack {
        @Specialization public static void apply(VirtualFrame frame, BytecodeVectorLanes lanes, ByteVector raw, @Bind("$node") Node node) {
            BytecodeNode bytecode = ((BytecodeRoot) node.getRootNode()).getBytecodeNode();
            ByteVector value = RuntimeTypes.requireByte(raw, ByteVector.SPECIES_256);
            lanes.getSlots()[0].setInt(bytecode, frame, value.lane(0));
            lanes.getSlots()[1].setInt(bytecode, frame, value.lane(1));
            lanes.getSlots()[2].setInt(bytecode, frame, value.lane(2));
            lanes.getSlots()[3].setInt(bytecode, frame, value.lane(3));
            lanes.getSlots()[4].setInt(bytecode, frame, value.lane(4));
            lanes.getSlots()[5].setInt(bytecode, frame, value.lane(5));
            lanes.getSlots()[6].setInt(bytecode, frame, value.lane(6));
            lanes.getSlots()[7].setInt(bytecode, frame, value.lane(7));
            lanes.getSlots()[8].setInt(bytecode, frame, value.lane(8));
            lanes.getSlots()[9].setInt(bytecode, frame, value.lane(9));
            lanes.getSlots()[10].setInt(bytecode, frame, value.lane(10));
            lanes.getSlots()[11].setInt(bytecode, frame, value.lane(11));
            lanes.getSlots()[12].setInt(bytecode, frame, value.lane(12));
            lanes.getSlots()[13].setInt(bytecode, frame, value.lane(13));
            lanes.getSlots()[14].setInt(bytecode, frame, value.lane(14));
            lanes.getSlots()[15].setInt(bytecode, frame, value.lane(15));
            lanes.getSlots()[16].setInt(bytecode, frame, value.lane(16));
            lanes.getSlots()[17].setInt(bytecode, frame, value.lane(17));
            lanes.getSlots()[18].setInt(bytecode, frame, value.lane(18));
            lanes.getSlots()[19].setInt(bytecode, frame, value.lane(19));
            lanes.getSlots()[20].setInt(bytecode, frame, value.lane(20));
            lanes.getSlots()[21].setInt(bytecode, frame, value.lane(21));
            lanes.getSlots()[22].setInt(bytecode, frame, value.lane(22));
            lanes.getSlots()[23].setInt(bytecode, frame, value.lane(23));
            lanes.getSlots()[24].setInt(bytecode, frame, value.lane(24));
            lanes.getSlots()[25].setInt(bytecode, frame, value.lane(25));
            lanes.getSlots()[26].setInt(bytecode, frame, value.lane(26));
            lanes.getSlots()[27].setInt(bytecode, frame, value.lane(27));
            lanes.getSlots()[28].setInt(bytecode, frame, value.lane(28));
            lanes.getSlots()[29].setInt(bytecode, frame, value.lane(29));
            lanes.getSlots()[30].setInt(bytecode, frame, value.lane(30));
            lanes.getSlots()[31].setInt(bytecode, frame, value.lane(31));
        }
    }
    @Operation public static final class GeneratedInt8X32Broadcast {
        @Specialization public static ByteVector apply(int value) { return ByteVector.broadcast(ByteVector.SPECIES_256, (byte) value); }
    }
    @Operation public static final class GeneratedInt8X32Plus {
        @Specialization public static ByteVector apply(ByteVector left, ByteVector right) { return RuntimeTypes.requireByte(left, ByteVector.SPECIES_256).add(RuntimeTypes.requireByte(right, ByteVector.SPECIES_256)); }
    }
    @Operation public static final class GeneratedInt8X32Minus {
        @Specialization public static ByteVector apply(ByteVector left, ByteVector right) { return RuntimeTypes.requireByte(left, ByteVector.SPECIES_256).sub(RuntimeTypes.requireByte(right, ByteVector.SPECIES_256)); }
    }
    @Operation public static final class GeneratedInt8X32Times {
        @Specialization public static ByteVector apply(ByteVector left, ByteVector right) { return RuntimeTypes.requireByte(left, ByteVector.SPECIES_256).mul(RuntimeTypes.requireByte(right, ByteVector.SPECIES_256)); }
    }
    @Operation public static final class GeneratedInt8X32Negate {
        @Specialization public static ByteVector apply(ByteVector value) { return RuntimeTypes.requireByte(value, ByteVector.SPECIES_256).neg(); }
    }
    @Operation public static final class GeneratedInt8X32Insert {
        @Specialization public static ByteVector apply(ByteVector vector, int value, long index) { return RuntimeTypes.requireByte(vector, ByteVector.SPECIES_256).withLane(RuntimeTypes.laneIndex(index, 32), (byte) value); }
    }
    @Operation public static final class GeneratedInt8X32Min {
        @Specialization public static ByteVector apply(ByteVector left, ByteVector right) { return RuntimeTypes.requireByte(left, ByteVector.SPECIES_256).min(RuntimeTypes.requireByte(right, ByteVector.SPECIES_256)); }
    }
    @Operation public static final class GeneratedInt8X32Max {
        @Specialization public static ByteVector apply(ByteVector left, ByteVector right) { return RuntimeTypes.requireByte(left, ByteVector.SPECIES_256).max(RuntimeTypes.requireByte(right, ByteVector.SPECIES_256)); }
    }
    @Operation public static final class GeneratedInt8X32Quot {
        @Specialization public static ByteVector apply(ByteVector left, ByteVector right) { return VectorIntegerDivision.quotByte(RuntimeTypes.requireByte(left, ByteVector.SPECIES_256), RuntimeTypes.requireByte(right, ByteVector.SPECIES_256), false); }
    }
    @Operation public static final class GeneratedInt8X32Rem {
        @Specialization public static ByteVector apply(ByteVector left, ByteVector right) { return VectorIntegerDivision.remByte(RuntimeTypes.requireByte(left, ByteVector.SPECIES_256), RuntimeTypes.requireByte(right, ByteVector.SPECIES_256), false); }
    }
    @Operation
    @ConstantOperand(type = VectorShuffle.class, name = "shuffle")
    public static final class GeneratedInt8X32Shuffle {
        @Specialization public static ByteVector apply(VectorShuffle<?> shuffle, ByteVector left, ByteVector right) {
            return RuntimeTypes.requireByte(left, ByteVector.SPECIES_256).rearrange(shuffle.check(ByteVector.SPECIES_256), RuntimeTypes.requireByte(right, ByteVector.SPECIES_256));
        }
    }
    @Operation
    @ConstantOperand(type = BytecodeVectorLanes.class, name = "lanes")
    public static final class GeneratedWord8X32Pack {
        @Specialization public static ByteVector apply(VirtualFrame frame, BytecodeVectorLanes lanes, @Bind("$node") Node node) {
            BytecodeNode bytecode = ((BytecodeRoot) node.getRootNode()).getBytecodeNode();
            try {
                return ByteVector.broadcast(ByteVector.SPECIES_256, (byte) lanes.getSlots()[0].getInt(bytecode, frame)).withLane(1, (byte) lanes.getSlots()[1].getInt(bytecode, frame)).withLane(2, (byte) lanes.getSlots()[2].getInt(bytecode, frame)).withLane(3, (byte) lanes.getSlots()[3].getInt(bytecode, frame)).withLane(4, (byte) lanes.getSlots()[4].getInt(bytecode, frame)).withLane(5, (byte) lanes.getSlots()[5].getInt(bytecode, frame)).withLane(6, (byte) lanes.getSlots()[6].getInt(bytecode, frame)).withLane(7, (byte) lanes.getSlots()[7].getInt(bytecode, frame)).withLane(8, (byte) lanes.getSlots()[8].getInt(bytecode, frame)).withLane(9, (byte) lanes.getSlots()[9].getInt(bytecode, frame)).withLane(10, (byte) lanes.getSlots()[10].getInt(bytecode, frame)).withLane(11, (byte) lanes.getSlots()[11].getInt(bytecode, frame)).withLane(12, (byte) lanes.getSlots()[12].getInt(bytecode, frame)).withLane(13, (byte) lanes.getSlots()[13].getInt(bytecode, frame)).withLane(14, (byte) lanes.getSlots()[14].getInt(bytecode, frame)).withLane(15, (byte) lanes.getSlots()[15].getInt(bytecode, frame)).withLane(16, (byte) lanes.getSlots()[16].getInt(bytecode, frame)).withLane(17, (byte) lanes.getSlots()[17].getInt(bytecode, frame)).withLane(18, (byte) lanes.getSlots()[18].getInt(bytecode, frame)).withLane(19, (byte) lanes.getSlots()[19].getInt(bytecode, frame)).withLane(20, (byte) lanes.getSlots()[20].getInt(bytecode, frame)).withLane(21, (byte) lanes.getSlots()[21].getInt(bytecode, frame)).withLane(22, (byte) lanes.getSlots()[22].getInt(bytecode, frame)).withLane(23, (byte) lanes.getSlots()[23].getInt(bytecode, frame)).withLane(24, (byte) lanes.getSlots()[24].getInt(bytecode, frame)).withLane(25, (byte) lanes.getSlots()[25].getInt(bytecode, frame)).withLane(26, (byte) lanes.getSlots()[26].getInt(bytecode, frame)).withLane(27, (byte) lanes.getSlots()[27].getInt(bytecode, frame)).withLane(28, (byte) lanes.getSlots()[28].getInt(bytecode, frame)).withLane(29, (byte) lanes.getSlots()[29].getInt(bytecode, frame)).withLane(30, (byte) lanes.getSlots()[30].getInt(bytecode, frame)).withLane(31, (byte) lanes.getSlots()[31].getInt(bytecode, frame));
            } catch (com.oracle.truffle.api.nodes.UnexpectedResultException invalid) {
                throw new RuntimeFault("Expected primitive vector lane");
            }
        }
    }
    @Operation
    @ConstantOperand(type = BytecodeVectorLanes.class, name = "lanes")
    public static final class GeneratedWord8X32Unpack {
        @Specialization public static void apply(VirtualFrame frame, BytecodeVectorLanes lanes, ByteVector raw, @Bind("$node") Node node) {
            BytecodeNode bytecode = ((BytecodeRoot) node.getRootNode()).getBytecodeNode();
            ByteVector value = RuntimeTypes.requireByte(raw, ByteVector.SPECIES_256);
            lanes.getSlots()[0].setInt(bytecode, frame, value.lane(0) & 0xff);
            lanes.getSlots()[1].setInt(bytecode, frame, value.lane(1) & 0xff);
            lanes.getSlots()[2].setInt(bytecode, frame, value.lane(2) & 0xff);
            lanes.getSlots()[3].setInt(bytecode, frame, value.lane(3) & 0xff);
            lanes.getSlots()[4].setInt(bytecode, frame, value.lane(4) & 0xff);
            lanes.getSlots()[5].setInt(bytecode, frame, value.lane(5) & 0xff);
            lanes.getSlots()[6].setInt(bytecode, frame, value.lane(6) & 0xff);
            lanes.getSlots()[7].setInt(bytecode, frame, value.lane(7) & 0xff);
            lanes.getSlots()[8].setInt(bytecode, frame, value.lane(8) & 0xff);
            lanes.getSlots()[9].setInt(bytecode, frame, value.lane(9) & 0xff);
            lanes.getSlots()[10].setInt(bytecode, frame, value.lane(10) & 0xff);
            lanes.getSlots()[11].setInt(bytecode, frame, value.lane(11) & 0xff);
            lanes.getSlots()[12].setInt(bytecode, frame, value.lane(12) & 0xff);
            lanes.getSlots()[13].setInt(bytecode, frame, value.lane(13) & 0xff);
            lanes.getSlots()[14].setInt(bytecode, frame, value.lane(14) & 0xff);
            lanes.getSlots()[15].setInt(bytecode, frame, value.lane(15) & 0xff);
            lanes.getSlots()[16].setInt(bytecode, frame, value.lane(16) & 0xff);
            lanes.getSlots()[17].setInt(bytecode, frame, value.lane(17) & 0xff);
            lanes.getSlots()[18].setInt(bytecode, frame, value.lane(18) & 0xff);
            lanes.getSlots()[19].setInt(bytecode, frame, value.lane(19) & 0xff);
            lanes.getSlots()[20].setInt(bytecode, frame, value.lane(20) & 0xff);
            lanes.getSlots()[21].setInt(bytecode, frame, value.lane(21) & 0xff);
            lanes.getSlots()[22].setInt(bytecode, frame, value.lane(22) & 0xff);
            lanes.getSlots()[23].setInt(bytecode, frame, value.lane(23) & 0xff);
            lanes.getSlots()[24].setInt(bytecode, frame, value.lane(24) & 0xff);
            lanes.getSlots()[25].setInt(bytecode, frame, value.lane(25) & 0xff);
            lanes.getSlots()[26].setInt(bytecode, frame, value.lane(26) & 0xff);
            lanes.getSlots()[27].setInt(bytecode, frame, value.lane(27) & 0xff);
            lanes.getSlots()[28].setInt(bytecode, frame, value.lane(28) & 0xff);
            lanes.getSlots()[29].setInt(bytecode, frame, value.lane(29) & 0xff);
            lanes.getSlots()[30].setInt(bytecode, frame, value.lane(30) & 0xff);
            lanes.getSlots()[31].setInt(bytecode, frame, value.lane(31) & 0xff);
        }
    }
    @Operation public static final class GeneratedWord8X32Broadcast {
        @Specialization public static ByteVector apply(int value) { return ByteVector.broadcast(ByteVector.SPECIES_256, (byte) value); }
    }
    @Operation public static final class GeneratedWord8X32Plus {
        @Specialization public static ByteVector apply(ByteVector left, ByteVector right) { return RuntimeTypes.requireByte(left, ByteVector.SPECIES_256).add(RuntimeTypes.requireByte(right, ByteVector.SPECIES_256)); }
    }
    @Operation public static final class GeneratedWord8X32Minus {
        @Specialization public static ByteVector apply(ByteVector left, ByteVector right) { return RuntimeTypes.requireByte(left, ByteVector.SPECIES_256).sub(RuntimeTypes.requireByte(right, ByteVector.SPECIES_256)); }
    }
    @Operation public static final class GeneratedWord8X32Times {
        @Specialization public static ByteVector apply(ByteVector left, ByteVector right) { return RuntimeTypes.requireByte(left, ByteVector.SPECIES_256).mul(RuntimeTypes.requireByte(right, ByteVector.SPECIES_256)); }
    }
    @Operation public static final class GeneratedWord8X32Insert {
        @Specialization public static ByteVector apply(ByteVector vector, int value, long index) { return RuntimeTypes.requireByte(vector, ByteVector.SPECIES_256).withLane(RuntimeTypes.laneIndex(index, 32), (byte) value); }
    }
    @Operation public static final class GeneratedWord8X32Min {
        @Specialization public static ByteVector apply(ByteVector left, ByteVector right) { return RuntimeTypes.requireByte(left, ByteVector.SPECIES_256).lanewise(VectorOperators.UMIN, RuntimeTypes.requireByte(right, ByteVector.SPECIES_256)); }
    }
    @Operation public static final class GeneratedWord8X32Max {
        @Specialization public static ByteVector apply(ByteVector left, ByteVector right) { return RuntimeTypes.requireByte(left, ByteVector.SPECIES_256).lanewise(VectorOperators.UMAX, RuntimeTypes.requireByte(right, ByteVector.SPECIES_256)); }
    }
    @Operation public static final class GeneratedWord8X32Quot {
        @Specialization public static ByteVector apply(ByteVector left, ByteVector right) { return VectorIntegerDivision.quotByte(RuntimeTypes.requireByte(left, ByteVector.SPECIES_256), RuntimeTypes.requireByte(right, ByteVector.SPECIES_256), true); }
    }
    @Operation public static final class GeneratedWord8X32Rem {
        @Specialization public static ByteVector apply(ByteVector left, ByteVector right) { return VectorIntegerDivision.remByte(RuntimeTypes.requireByte(left, ByteVector.SPECIES_256), RuntimeTypes.requireByte(right, ByteVector.SPECIES_256), true); }
    }
    @Operation
    @ConstantOperand(type = VectorShuffle.class, name = "shuffle")
    public static final class GeneratedWord8X32Shuffle {
        @Specialization public static ByteVector apply(VectorShuffle<?> shuffle, ByteVector left, ByteVector right) {
            return RuntimeTypes.requireByte(left, ByteVector.SPECIES_256).rearrange(shuffle.check(ByteVector.SPECIES_256), RuntimeTypes.requireByte(right, ByteVector.SPECIES_256));
        }
    }
    @Operation
    @ConstantOperand(type = BytecodeVectorLanes.class, name = "lanes")
    public static final class GeneratedInt8X64Pack {
        @Specialization public static ByteVector apply(VirtualFrame frame, BytecodeVectorLanes lanes, @Bind("$node") Node node) {
            BytecodeNode bytecode = ((BytecodeRoot) node.getRootNode()).getBytecodeNode();
            try {
                return ByteVector.broadcast(ByteVector.SPECIES_512, (byte) lanes.getSlots()[0].getInt(bytecode, frame)).withLane(1, (byte) lanes.getSlots()[1].getInt(bytecode, frame)).withLane(2, (byte) lanes.getSlots()[2].getInt(bytecode, frame)).withLane(3, (byte) lanes.getSlots()[3].getInt(bytecode, frame)).withLane(4, (byte) lanes.getSlots()[4].getInt(bytecode, frame)).withLane(5, (byte) lanes.getSlots()[5].getInt(bytecode, frame)).withLane(6, (byte) lanes.getSlots()[6].getInt(bytecode, frame)).withLane(7, (byte) lanes.getSlots()[7].getInt(bytecode, frame)).withLane(8, (byte) lanes.getSlots()[8].getInt(bytecode, frame)).withLane(9, (byte) lanes.getSlots()[9].getInt(bytecode, frame)).withLane(10, (byte) lanes.getSlots()[10].getInt(bytecode, frame)).withLane(11, (byte) lanes.getSlots()[11].getInt(bytecode, frame)).withLane(12, (byte) lanes.getSlots()[12].getInt(bytecode, frame)).withLane(13, (byte) lanes.getSlots()[13].getInt(bytecode, frame)).withLane(14, (byte) lanes.getSlots()[14].getInt(bytecode, frame)).withLane(15, (byte) lanes.getSlots()[15].getInt(bytecode, frame)).withLane(16, (byte) lanes.getSlots()[16].getInt(bytecode, frame)).withLane(17, (byte) lanes.getSlots()[17].getInt(bytecode, frame)).withLane(18, (byte) lanes.getSlots()[18].getInt(bytecode, frame)).withLane(19, (byte) lanes.getSlots()[19].getInt(bytecode, frame)).withLane(20, (byte) lanes.getSlots()[20].getInt(bytecode, frame)).withLane(21, (byte) lanes.getSlots()[21].getInt(bytecode, frame)).withLane(22, (byte) lanes.getSlots()[22].getInt(bytecode, frame)).withLane(23, (byte) lanes.getSlots()[23].getInt(bytecode, frame)).withLane(24, (byte) lanes.getSlots()[24].getInt(bytecode, frame)).withLane(25, (byte) lanes.getSlots()[25].getInt(bytecode, frame)).withLane(26, (byte) lanes.getSlots()[26].getInt(bytecode, frame)).withLane(27, (byte) lanes.getSlots()[27].getInt(bytecode, frame)).withLane(28, (byte) lanes.getSlots()[28].getInt(bytecode, frame)).withLane(29, (byte) lanes.getSlots()[29].getInt(bytecode, frame)).withLane(30, (byte) lanes.getSlots()[30].getInt(bytecode, frame)).withLane(31, (byte) lanes.getSlots()[31].getInt(bytecode, frame)).withLane(32, (byte) lanes.getSlots()[32].getInt(bytecode, frame)).withLane(33, (byte) lanes.getSlots()[33].getInt(bytecode, frame)).withLane(34, (byte) lanes.getSlots()[34].getInt(bytecode, frame)).withLane(35, (byte) lanes.getSlots()[35].getInt(bytecode, frame)).withLane(36, (byte) lanes.getSlots()[36].getInt(bytecode, frame)).withLane(37, (byte) lanes.getSlots()[37].getInt(bytecode, frame)).withLane(38, (byte) lanes.getSlots()[38].getInt(bytecode, frame)).withLane(39, (byte) lanes.getSlots()[39].getInt(bytecode, frame)).withLane(40, (byte) lanes.getSlots()[40].getInt(bytecode, frame)).withLane(41, (byte) lanes.getSlots()[41].getInt(bytecode, frame)).withLane(42, (byte) lanes.getSlots()[42].getInt(bytecode, frame)).withLane(43, (byte) lanes.getSlots()[43].getInt(bytecode, frame)).withLane(44, (byte) lanes.getSlots()[44].getInt(bytecode, frame)).withLane(45, (byte) lanes.getSlots()[45].getInt(bytecode, frame)).withLane(46, (byte) lanes.getSlots()[46].getInt(bytecode, frame)).withLane(47, (byte) lanes.getSlots()[47].getInt(bytecode, frame)).withLane(48, (byte) lanes.getSlots()[48].getInt(bytecode, frame)).withLane(49, (byte) lanes.getSlots()[49].getInt(bytecode, frame)).withLane(50, (byte) lanes.getSlots()[50].getInt(bytecode, frame)).withLane(51, (byte) lanes.getSlots()[51].getInt(bytecode, frame)).withLane(52, (byte) lanes.getSlots()[52].getInt(bytecode, frame)).withLane(53, (byte) lanes.getSlots()[53].getInt(bytecode, frame)).withLane(54, (byte) lanes.getSlots()[54].getInt(bytecode, frame)).withLane(55, (byte) lanes.getSlots()[55].getInt(bytecode, frame)).withLane(56, (byte) lanes.getSlots()[56].getInt(bytecode, frame)).withLane(57, (byte) lanes.getSlots()[57].getInt(bytecode, frame)).withLane(58, (byte) lanes.getSlots()[58].getInt(bytecode, frame)).withLane(59, (byte) lanes.getSlots()[59].getInt(bytecode, frame)).withLane(60, (byte) lanes.getSlots()[60].getInt(bytecode, frame)).withLane(61, (byte) lanes.getSlots()[61].getInt(bytecode, frame)).withLane(62, (byte) lanes.getSlots()[62].getInt(bytecode, frame)).withLane(63, (byte) lanes.getSlots()[63].getInt(bytecode, frame));
            } catch (com.oracle.truffle.api.nodes.UnexpectedResultException invalid) {
                throw new RuntimeFault("Expected primitive vector lane");
            }
        }
    }
    @Operation
    @ConstantOperand(type = BytecodeVectorLanes.class, name = "lanes")
    public static final class GeneratedInt8X64Unpack {
        @Specialization public static void apply(VirtualFrame frame, BytecodeVectorLanes lanes, ByteVector raw, @Bind("$node") Node node) {
            BytecodeNode bytecode = ((BytecodeRoot) node.getRootNode()).getBytecodeNode();
            ByteVector value = RuntimeTypes.requireByte(raw, ByteVector.SPECIES_512);
            lanes.getSlots()[0].setInt(bytecode, frame, value.lane(0));
            lanes.getSlots()[1].setInt(bytecode, frame, value.lane(1));
            lanes.getSlots()[2].setInt(bytecode, frame, value.lane(2));
            lanes.getSlots()[3].setInt(bytecode, frame, value.lane(3));
            lanes.getSlots()[4].setInt(bytecode, frame, value.lane(4));
            lanes.getSlots()[5].setInt(bytecode, frame, value.lane(5));
            lanes.getSlots()[6].setInt(bytecode, frame, value.lane(6));
            lanes.getSlots()[7].setInt(bytecode, frame, value.lane(7));
            lanes.getSlots()[8].setInt(bytecode, frame, value.lane(8));
            lanes.getSlots()[9].setInt(bytecode, frame, value.lane(9));
            lanes.getSlots()[10].setInt(bytecode, frame, value.lane(10));
            lanes.getSlots()[11].setInt(bytecode, frame, value.lane(11));
            lanes.getSlots()[12].setInt(bytecode, frame, value.lane(12));
            lanes.getSlots()[13].setInt(bytecode, frame, value.lane(13));
            lanes.getSlots()[14].setInt(bytecode, frame, value.lane(14));
            lanes.getSlots()[15].setInt(bytecode, frame, value.lane(15));
            lanes.getSlots()[16].setInt(bytecode, frame, value.lane(16));
            lanes.getSlots()[17].setInt(bytecode, frame, value.lane(17));
            lanes.getSlots()[18].setInt(bytecode, frame, value.lane(18));
            lanes.getSlots()[19].setInt(bytecode, frame, value.lane(19));
            lanes.getSlots()[20].setInt(bytecode, frame, value.lane(20));
            lanes.getSlots()[21].setInt(bytecode, frame, value.lane(21));
            lanes.getSlots()[22].setInt(bytecode, frame, value.lane(22));
            lanes.getSlots()[23].setInt(bytecode, frame, value.lane(23));
            lanes.getSlots()[24].setInt(bytecode, frame, value.lane(24));
            lanes.getSlots()[25].setInt(bytecode, frame, value.lane(25));
            lanes.getSlots()[26].setInt(bytecode, frame, value.lane(26));
            lanes.getSlots()[27].setInt(bytecode, frame, value.lane(27));
            lanes.getSlots()[28].setInt(bytecode, frame, value.lane(28));
            lanes.getSlots()[29].setInt(bytecode, frame, value.lane(29));
            lanes.getSlots()[30].setInt(bytecode, frame, value.lane(30));
            lanes.getSlots()[31].setInt(bytecode, frame, value.lane(31));
            lanes.getSlots()[32].setInt(bytecode, frame, value.lane(32));
            lanes.getSlots()[33].setInt(bytecode, frame, value.lane(33));
            lanes.getSlots()[34].setInt(bytecode, frame, value.lane(34));
            lanes.getSlots()[35].setInt(bytecode, frame, value.lane(35));
            lanes.getSlots()[36].setInt(bytecode, frame, value.lane(36));
            lanes.getSlots()[37].setInt(bytecode, frame, value.lane(37));
            lanes.getSlots()[38].setInt(bytecode, frame, value.lane(38));
            lanes.getSlots()[39].setInt(bytecode, frame, value.lane(39));
            lanes.getSlots()[40].setInt(bytecode, frame, value.lane(40));
            lanes.getSlots()[41].setInt(bytecode, frame, value.lane(41));
            lanes.getSlots()[42].setInt(bytecode, frame, value.lane(42));
            lanes.getSlots()[43].setInt(bytecode, frame, value.lane(43));
            lanes.getSlots()[44].setInt(bytecode, frame, value.lane(44));
            lanes.getSlots()[45].setInt(bytecode, frame, value.lane(45));
            lanes.getSlots()[46].setInt(bytecode, frame, value.lane(46));
            lanes.getSlots()[47].setInt(bytecode, frame, value.lane(47));
            lanes.getSlots()[48].setInt(bytecode, frame, value.lane(48));
            lanes.getSlots()[49].setInt(bytecode, frame, value.lane(49));
            lanes.getSlots()[50].setInt(bytecode, frame, value.lane(50));
            lanes.getSlots()[51].setInt(bytecode, frame, value.lane(51));
            lanes.getSlots()[52].setInt(bytecode, frame, value.lane(52));
            lanes.getSlots()[53].setInt(bytecode, frame, value.lane(53));
            lanes.getSlots()[54].setInt(bytecode, frame, value.lane(54));
            lanes.getSlots()[55].setInt(bytecode, frame, value.lane(55));
            lanes.getSlots()[56].setInt(bytecode, frame, value.lane(56));
            lanes.getSlots()[57].setInt(bytecode, frame, value.lane(57));
            lanes.getSlots()[58].setInt(bytecode, frame, value.lane(58));
            lanes.getSlots()[59].setInt(bytecode, frame, value.lane(59));
            lanes.getSlots()[60].setInt(bytecode, frame, value.lane(60));
            lanes.getSlots()[61].setInt(bytecode, frame, value.lane(61));
            lanes.getSlots()[62].setInt(bytecode, frame, value.lane(62));
            lanes.getSlots()[63].setInt(bytecode, frame, value.lane(63));
        }
    }
    @Operation public static final class GeneratedInt8X64Broadcast {
        @Specialization public static ByteVector apply(int value) { return ByteVector.broadcast(ByteVector.SPECIES_512, (byte) value); }
    }
    @Operation public static final class GeneratedInt8X64Plus {
        @Specialization public static ByteVector apply(ByteVector left, ByteVector right) { return RuntimeTypes.requireByte(left, ByteVector.SPECIES_512).add(RuntimeTypes.requireByte(right, ByteVector.SPECIES_512)); }
    }
    @Operation public static final class GeneratedInt8X64Minus {
        @Specialization public static ByteVector apply(ByteVector left, ByteVector right) { return RuntimeTypes.requireByte(left, ByteVector.SPECIES_512).sub(RuntimeTypes.requireByte(right, ByteVector.SPECIES_512)); }
    }
    @Operation public static final class GeneratedInt8X64Times {
        @Specialization public static ByteVector apply(ByteVector left, ByteVector right) { return RuntimeTypes.requireByte(left, ByteVector.SPECIES_512).mul(RuntimeTypes.requireByte(right, ByteVector.SPECIES_512)); }
    }
    @Operation public static final class GeneratedInt8X64Negate {
        @Specialization public static ByteVector apply(ByteVector value) { return RuntimeTypes.requireByte(value, ByteVector.SPECIES_512).neg(); }
    }
    @Operation public static final class GeneratedInt8X64Insert {
        @Specialization public static ByteVector apply(ByteVector vector, int value, long index) { return RuntimeTypes.requireByte(vector, ByteVector.SPECIES_512).withLane(RuntimeTypes.laneIndex(index, 64), (byte) value); }
    }
    @Operation public static final class GeneratedInt8X64Min {
        @Specialization public static ByteVector apply(ByteVector left, ByteVector right) { return RuntimeTypes.requireByte(left, ByteVector.SPECIES_512).min(RuntimeTypes.requireByte(right, ByteVector.SPECIES_512)); }
    }
    @Operation public static final class GeneratedInt8X64Max {
        @Specialization public static ByteVector apply(ByteVector left, ByteVector right) { return RuntimeTypes.requireByte(left, ByteVector.SPECIES_512).max(RuntimeTypes.requireByte(right, ByteVector.SPECIES_512)); }
    }
    @Operation public static final class GeneratedInt8X64Quot {
        @Specialization public static ByteVector apply(ByteVector left, ByteVector right) { return VectorIntegerDivision.quotByte(RuntimeTypes.requireByte(left, ByteVector.SPECIES_512), RuntimeTypes.requireByte(right, ByteVector.SPECIES_512), false); }
    }
    @Operation public static final class GeneratedInt8X64Rem {
        @Specialization public static ByteVector apply(ByteVector left, ByteVector right) { return VectorIntegerDivision.remByte(RuntimeTypes.requireByte(left, ByteVector.SPECIES_512), RuntimeTypes.requireByte(right, ByteVector.SPECIES_512), false); }
    }
    @Operation
    @ConstantOperand(type = VectorShuffle.class, name = "shuffle")
    public static final class GeneratedInt8X64Shuffle {
        @Specialization public static ByteVector apply(VectorShuffle<?> shuffle, ByteVector left, ByteVector right) {
            return RuntimeTypes.requireByte(left, ByteVector.SPECIES_512).rearrange(shuffle.check(ByteVector.SPECIES_512), RuntimeTypes.requireByte(right, ByteVector.SPECIES_512));
        }
    }
    @Operation
    @ConstantOperand(type = BytecodeVectorLanes.class, name = "lanes")
    public static final class GeneratedWord8X64Pack {
        @Specialization public static ByteVector apply(VirtualFrame frame, BytecodeVectorLanes lanes, @Bind("$node") Node node) {
            BytecodeNode bytecode = ((BytecodeRoot) node.getRootNode()).getBytecodeNode();
            try {
                return ByteVector.broadcast(ByteVector.SPECIES_512, (byte) lanes.getSlots()[0].getInt(bytecode, frame)).withLane(1, (byte) lanes.getSlots()[1].getInt(bytecode, frame)).withLane(2, (byte) lanes.getSlots()[2].getInt(bytecode, frame)).withLane(3, (byte) lanes.getSlots()[3].getInt(bytecode, frame)).withLane(4, (byte) lanes.getSlots()[4].getInt(bytecode, frame)).withLane(5, (byte) lanes.getSlots()[5].getInt(bytecode, frame)).withLane(6, (byte) lanes.getSlots()[6].getInt(bytecode, frame)).withLane(7, (byte) lanes.getSlots()[7].getInt(bytecode, frame)).withLane(8, (byte) lanes.getSlots()[8].getInt(bytecode, frame)).withLane(9, (byte) lanes.getSlots()[9].getInt(bytecode, frame)).withLane(10, (byte) lanes.getSlots()[10].getInt(bytecode, frame)).withLane(11, (byte) lanes.getSlots()[11].getInt(bytecode, frame)).withLane(12, (byte) lanes.getSlots()[12].getInt(bytecode, frame)).withLane(13, (byte) lanes.getSlots()[13].getInt(bytecode, frame)).withLane(14, (byte) lanes.getSlots()[14].getInt(bytecode, frame)).withLane(15, (byte) lanes.getSlots()[15].getInt(bytecode, frame)).withLane(16, (byte) lanes.getSlots()[16].getInt(bytecode, frame)).withLane(17, (byte) lanes.getSlots()[17].getInt(bytecode, frame)).withLane(18, (byte) lanes.getSlots()[18].getInt(bytecode, frame)).withLane(19, (byte) lanes.getSlots()[19].getInt(bytecode, frame)).withLane(20, (byte) lanes.getSlots()[20].getInt(bytecode, frame)).withLane(21, (byte) lanes.getSlots()[21].getInt(bytecode, frame)).withLane(22, (byte) lanes.getSlots()[22].getInt(bytecode, frame)).withLane(23, (byte) lanes.getSlots()[23].getInt(bytecode, frame)).withLane(24, (byte) lanes.getSlots()[24].getInt(bytecode, frame)).withLane(25, (byte) lanes.getSlots()[25].getInt(bytecode, frame)).withLane(26, (byte) lanes.getSlots()[26].getInt(bytecode, frame)).withLane(27, (byte) lanes.getSlots()[27].getInt(bytecode, frame)).withLane(28, (byte) lanes.getSlots()[28].getInt(bytecode, frame)).withLane(29, (byte) lanes.getSlots()[29].getInt(bytecode, frame)).withLane(30, (byte) lanes.getSlots()[30].getInt(bytecode, frame)).withLane(31, (byte) lanes.getSlots()[31].getInt(bytecode, frame)).withLane(32, (byte) lanes.getSlots()[32].getInt(bytecode, frame)).withLane(33, (byte) lanes.getSlots()[33].getInt(bytecode, frame)).withLane(34, (byte) lanes.getSlots()[34].getInt(bytecode, frame)).withLane(35, (byte) lanes.getSlots()[35].getInt(bytecode, frame)).withLane(36, (byte) lanes.getSlots()[36].getInt(bytecode, frame)).withLane(37, (byte) lanes.getSlots()[37].getInt(bytecode, frame)).withLane(38, (byte) lanes.getSlots()[38].getInt(bytecode, frame)).withLane(39, (byte) lanes.getSlots()[39].getInt(bytecode, frame)).withLane(40, (byte) lanes.getSlots()[40].getInt(bytecode, frame)).withLane(41, (byte) lanes.getSlots()[41].getInt(bytecode, frame)).withLane(42, (byte) lanes.getSlots()[42].getInt(bytecode, frame)).withLane(43, (byte) lanes.getSlots()[43].getInt(bytecode, frame)).withLane(44, (byte) lanes.getSlots()[44].getInt(bytecode, frame)).withLane(45, (byte) lanes.getSlots()[45].getInt(bytecode, frame)).withLane(46, (byte) lanes.getSlots()[46].getInt(bytecode, frame)).withLane(47, (byte) lanes.getSlots()[47].getInt(bytecode, frame)).withLane(48, (byte) lanes.getSlots()[48].getInt(bytecode, frame)).withLane(49, (byte) lanes.getSlots()[49].getInt(bytecode, frame)).withLane(50, (byte) lanes.getSlots()[50].getInt(bytecode, frame)).withLane(51, (byte) lanes.getSlots()[51].getInt(bytecode, frame)).withLane(52, (byte) lanes.getSlots()[52].getInt(bytecode, frame)).withLane(53, (byte) lanes.getSlots()[53].getInt(bytecode, frame)).withLane(54, (byte) lanes.getSlots()[54].getInt(bytecode, frame)).withLane(55, (byte) lanes.getSlots()[55].getInt(bytecode, frame)).withLane(56, (byte) lanes.getSlots()[56].getInt(bytecode, frame)).withLane(57, (byte) lanes.getSlots()[57].getInt(bytecode, frame)).withLane(58, (byte) lanes.getSlots()[58].getInt(bytecode, frame)).withLane(59, (byte) lanes.getSlots()[59].getInt(bytecode, frame)).withLane(60, (byte) lanes.getSlots()[60].getInt(bytecode, frame)).withLane(61, (byte) lanes.getSlots()[61].getInt(bytecode, frame)).withLane(62, (byte) lanes.getSlots()[62].getInt(bytecode, frame)).withLane(63, (byte) lanes.getSlots()[63].getInt(bytecode, frame));
            } catch (com.oracle.truffle.api.nodes.UnexpectedResultException invalid) {
                throw new RuntimeFault("Expected primitive vector lane");
            }
        }
    }
    @Operation
    @ConstantOperand(type = BytecodeVectorLanes.class, name = "lanes")
    public static final class GeneratedWord8X64Unpack {
        @Specialization public static void apply(VirtualFrame frame, BytecodeVectorLanes lanes, ByteVector raw, @Bind("$node") Node node) {
            BytecodeNode bytecode = ((BytecodeRoot) node.getRootNode()).getBytecodeNode();
            ByteVector value = RuntimeTypes.requireByte(raw, ByteVector.SPECIES_512);
            lanes.getSlots()[0].setInt(bytecode, frame, value.lane(0) & 0xff);
            lanes.getSlots()[1].setInt(bytecode, frame, value.lane(1) & 0xff);
            lanes.getSlots()[2].setInt(bytecode, frame, value.lane(2) & 0xff);
            lanes.getSlots()[3].setInt(bytecode, frame, value.lane(3) & 0xff);
            lanes.getSlots()[4].setInt(bytecode, frame, value.lane(4) & 0xff);
            lanes.getSlots()[5].setInt(bytecode, frame, value.lane(5) & 0xff);
            lanes.getSlots()[6].setInt(bytecode, frame, value.lane(6) & 0xff);
            lanes.getSlots()[7].setInt(bytecode, frame, value.lane(7) & 0xff);
            lanes.getSlots()[8].setInt(bytecode, frame, value.lane(8) & 0xff);
            lanes.getSlots()[9].setInt(bytecode, frame, value.lane(9) & 0xff);
            lanes.getSlots()[10].setInt(bytecode, frame, value.lane(10) & 0xff);
            lanes.getSlots()[11].setInt(bytecode, frame, value.lane(11) & 0xff);
            lanes.getSlots()[12].setInt(bytecode, frame, value.lane(12) & 0xff);
            lanes.getSlots()[13].setInt(bytecode, frame, value.lane(13) & 0xff);
            lanes.getSlots()[14].setInt(bytecode, frame, value.lane(14) & 0xff);
            lanes.getSlots()[15].setInt(bytecode, frame, value.lane(15) & 0xff);
            lanes.getSlots()[16].setInt(bytecode, frame, value.lane(16) & 0xff);
            lanes.getSlots()[17].setInt(bytecode, frame, value.lane(17) & 0xff);
            lanes.getSlots()[18].setInt(bytecode, frame, value.lane(18) & 0xff);
            lanes.getSlots()[19].setInt(bytecode, frame, value.lane(19) & 0xff);
            lanes.getSlots()[20].setInt(bytecode, frame, value.lane(20) & 0xff);
            lanes.getSlots()[21].setInt(bytecode, frame, value.lane(21) & 0xff);
            lanes.getSlots()[22].setInt(bytecode, frame, value.lane(22) & 0xff);
            lanes.getSlots()[23].setInt(bytecode, frame, value.lane(23) & 0xff);
            lanes.getSlots()[24].setInt(bytecode, frame, value.lane(24) & 0xff);
            lanes.getSlots()[25].setInt(bytecode, frame, value.lane(25) & 0xff);
            lanes.getSlots()[26].setInt(bytecode, frame, value.lane(26) & 0xff);
            lanes.getSlots()[27].setInt(bytecode, frame, value.lane(27) & 0xff);
            lanes.getSlots()[28].setInt(bytecode, frame, value.lane(28) & 0xff);
            lanes.getSlots()[29].setInt(bytecode, frame, value.lane(29) & 0xff);
            lanes.getSlots()[30].setInt(bytecode, frame, value.lane(30) & 0xff);
            lanes.getSlots()[31].setInt(bytecode, frame, value.lane(31) & 0xff);
            lanes.getSlots()[32].setInt(bytecode, frame, value.lane(32) & 0xff);
            lanes.getSlots()[33].setInt(bytecode, frame, value.lane(33) & 0xff);
            lanes.getSlots()[34].setInt(bytecode, frame, value.lane(34) & 0xff);
            lanes.getSlots()[35].setInt(bytecode, frame, value.lane(35) & 0xff);
            lanes.getSlots()[36].setInt(bytecode, frame, value.lane(36) & 0xff);
            lanes.getSlots()[37].setInt(bytecode, frame, value.lane(37) & 0xff);
            lanes.getSlots()[38].setInt(bytecode, frame, value.lane(38) & 0xff);
            lanes.getSlots()[39].setInt(bytecode, frame, value.lane(39) & 0xff);
            lanes.getSlots()[40].setInt(bytecode, frame, value.lane(40) & 0xff);
            lanes.getSlots()[41].setInt(bytecode, frame, value.lane(41) & 0xff);
            lanes.getSlots()[42].setInt(bytecode, frame, value.lane(42) & 0xff);
            lanes.getSlots()[43].setInt(bytecode, frame, value.lane(43) & 0xff);
            lanes.getSlots()[44].setInt(bytecode, frame, value.lane(44) & 0xff);
            lanes.getSlots()[45].setInt(bytecode, frame, value.lane(45) & 0xff);
            lanes.getSlots()[46].setInt(bytecode, frame, value.lane(46) & 0xff);
            lanes.getSlots()[47].setInt(bytecode, frame, value.lane(47) & 0xff);
            lanes.getSlots()[48].setInt(bytecode, frame, value.lane(48) & 0xff);
            lanes.getSlots()[49].setInt(bytecode, frame, value.lane(49) & 0xff);
            lanes.getSlots()[50].setInt(bytecode, frame, value.lane(50) & 0xff);
            lanes.getSlots()[51].setInt(bytecode, frame, value.lane(51) & 0xff);
            lanes.getSlots()[52].setInt(bytecode, frame, value.lane(52) & 0xff);
            lanes.getSlots()[53].setInt(bytecode, frame, value.lane(53) & 0xff);
            lanes.getSlots()[54].setInt(bytecode, frame, value.lane(54) & 0xff);
            lanes.getSlots()[55].setInt(bytecode, frame, value.lane(55) & 0xff);
            lanes.getSlots()[56].setInt(bytecode, frame, value.lane(56) & 0xff);
            lanes.getSlots()[57].setInt(bytecode, frame, value.lane(57) & 0xff);
            lanes.getSlots()[58].setInt(bytecode, frame, value.lane(58) & 0xff);
            lanes.getSlots()[59].setInt(bytecode, frame, value.lane(59) & 0xff);
            lanes.getSlots()[60].setInt(bytecode, frame, value.lane(60) & 0xff);
            lanes.getSlots()[61].setInt(bytecode, frame, value.lane(61) & 0xff);
            lanes.getSlots()[62].setInt(bytecode, frame, value.lane(62) & 0xff);
            lanes.getSlots()[63].setInt(bytecode, frame, value.lane(63) & 0xff);
        }
    }
    @Operation public static final class GeneratedWord8X64Broadcast {
        @Specialization public static ByteVector apply(int value) { return ByteVector.broadcast(ByteVector.SPECIES_512, (byte) value); }
    }
    @Operation public static final class GeneratedWord8X64Plus {
        @Specialization public static ByteVector apply(ByteVector left, ByteVector right) { return RuntimeTypes.requireByte(left, ByteVector.SPECIES_512).add(RuntimeTypes.requireByte(right, ByteVector.SPECIES_512)); }
    }
    @Operation public static final class GeneratedWord8X64Minus {
        @Specialization public static ByteVector apply(ByteVector left, ByteVector right) { return RuntimeTypes.requireByte(left, ByteVector.SPECIES_512).sub(RuntimeTypes.requireByte(right, ByteVector.SPECIES_512)); }
    }
    @Operation public static final class GeneratedWord8X64Times {
        @Specialization public static ByteVector apply(ByteVector left, ByteVector right) { return RuntimeTypes.requireByte(left, ByteVector.SPECIES_512).mul(RuntimeTypes.requireByte(right, ByteVector.SPECIES_512)); }
    }
    @Operation public static final class GeneratedWord8X64Insert {
        @Specialization public static ByteVector apply(ByteVector vector, int value, long index) { return RuntimeTypes.requireByte(vector, ByteVector.SPECIES_512).withLane(RuntimeTypes.laneIndex(index, 64), (byte) value); }
    }
    @Operation public static final class GeneratedWord8X64Min {
        @Specialization public static ByteVector apply(ByteVector left, ByteVector right) { return RuntimeTypes.requireByte(left, ByteVector.SPECIES_512).lanewise(VectorOperators.UMIN, RuntimeTypes.requireByte(right, ByteVector.SPECIES_512)); }
    }
    @Operation public static final class GeneratedWord8X64Max {
        @Specialization public static ByteVector apply(ByteVector left, ByteVector right) { return RuntimeTypes.requireByte(left, ByteVector.SPECIES_512).lanewise(VectorOperators.UMAX, RuntimeTypes.requireByte(right, ByteVector.SPECIES_512)); }
    }
    @Operation public static final class GeneratedWord8X64Quot {
        @Specialization public static ByteVector apply(ByteVector left, ByteVector right) { return VectorIntegerDivision.quotByte(RuntimeTypes.requireByte(left, ByteVector.SPECIES_512), RuntimeTypes.requireByte(right, ByteVector.SPECIES_512), true); }
    }
    @Operation public static final class GeneratedWord8X64Rem {
        @Specialization public static ByteVector apply(ByteVector left, ByteVector right) { return VectorIntegerDivision.remByte(RuntimeTypes.requireByte(left, ByteVector.SPECIES_512), RuntimeTypes.requireByte(right, ByteVector.SPECIES_512), true); }
    }
    @Operation
    @ConstantOperand(type = VectorShuffle.class, name = "shuffle")
    public static final class GeneratedWord8X64Shuffle {
        @Specialization public static ByteVector apply(VectorShuffle<?> shuffle, ByteVector left, ByteVector right) {
            return RuntimeTypes.requireByte(left, ByteVector.SPECIES_512).rearrange(shuffle.check(ByteVector.SPECIES_512), RuntimeTypes.requireByte(right, ByteVector.SPECIES_512));
        }
    }
    @Operation
    @ConstantOperand(type = BytecodeVectorLanes.class, name = "lanes")
    public static final class GeneratedInt16X32Pack {
        @Specialization public static ShortVector apply(VirtualFrame frame, BytecodeVectorLanes lanes, @Bind("$node") Node node) {
            BytecodeNode bytecode = ((BytecodeRoot) node.getRootNode()).getBytecodeNode();
            try {
                return ShortVector.broadcast(ShortVector.SPECIES_512, (short) lanes.getSlots()[0].getInt(bytecode, frame)).withLane(1, (short) lanes.getSlots()[1].getInt(bytecode, frame)).withLane(2, (short) lanes.getSlots()[2].getInt(bytecode, frame)).withLane(3, (short) lanes.getSlots()[3].getInt(bytecode, frame)).withLane(4, (short) lanes.getSlots()[4].getInt(bytecode, frame)).withLane(5, (short) lanes.getSlots()[5].getInt(bytecode, frame)).withLane(6, (short) lanes.getSlots()[6].getInt(bytecode, frame)).withLane(7, (short) lanes.getSlots()[7].getInt(bytecode, frame)).withLane(8, (short) lanes.getSlots()[8].getInt(bytecode, frame)).withLane(9, (short) lanes.getSlots()[9].getInt(bytecode, frame)).withLane(10, (short) lanes.getSlots()[10].getInt(bytecode, frame)).withLane(11, (short) lanes.getSlots()[11].getInt(bytecode, frame)).withLane(12, (short) lanes.getSlots()[12].getInt(bytecode, frame)).withLane(13, (short) lanes.getSlots()[13].getInt(bytecode, frame)).withLane(14, (short) lanes.getSlots()[14].getInt(bytecode, frame)).withLane(15, (short) lanes.getSlots()[15].getInt(bytecode, frame)).withLane(16, (short) lanes.getSlots()[16].getInt(bytecode, frame)).withLane(17, (short) lanes.getSlots()[17].getInt(bytecode, frame)).withLane(18, (short) lanes.getSlots()[18].getInt(bytecode, frame)).withLane(19, (short) lanes.getSlots()[19].getInt(bytecode, frame)).withLane(20, (short) lanes.getSlots()[20].getInt(bytecode, frame)).withLane(21, (short) lanes.getSlots()[21].getInt(bytecode, frame)).withLane(22, (short) lanes.getSlots()[22].getInt(bytecode, frame)).withLane(23, (short) lanes.getSlots()[23].getInt(bytecode, frame)).withLane(24, (short) lanes.getSlots()[24].getInt(bytecode, frame)).withLane(25, (short) lanes.getSlots()[25].getInt(bytecode, frame)).withLane(26, (short) lanes.getSlots()[26].getInt(bytecode, frame)).withLane(27, (short) lanes.getSlots()[27].getInt(bytecode, frame)).withLane(28, (short) lanes.getSlots()[28].getInt(bytecode, frame)).withLane(29, (short) lanes.getSlots()[29].getInt(bytecode, frame)).withLane(30, (short) lanes.getSlots()[30].getInt(bytecode, frame)).withLane(31, (short) lanes.getSlots()[31].getInt(bytecode, frame));
            } catch (com.oracle.truffle.api.nodes.UnexpectedResultException invalid) {
                throw new RuntimeFault("Expected primitive vector lane");
            }
        }
    }
    @Operation
    @ConstantOperand(type = BytecodeVectorLanes.class, name = "lanes")
    public static final class GeneratedInt16X32Unpack {
        @Specialization public static void apply(VirtualFrame frame, BytecodeVectorLanes lanes, ShortVector raw, @Bind("$node") Node node) {
            BytecodeNode bytecode = ((BytecodeRoot) node.getRootNode()).getBytecodeNode();
            ShortVector value = RuntimeTypes.requireShort(raw, ShortVector.SPECIES_512);
            lanes.getSlots()[0].setInt(bytecode, frame, value.lane(0));
            lanes.getSlots()[1].setInt(bytecode, frame, value.lane(1));
            lanes.getSlots()[2].setInt(bytecode, frame, value.lane(2));
            lanes.getSlots()[3].setInt(bytecode, frame, value.lane(3));
            lanes.getSlots()[4].setInt(bytecode, frame, value.lane(4));
            lanes.getSlots()[5].setInt(bytecode, frame, value.lane(5));
            lanes.getSlots()[6].setInt(bytecode, frame, value.lane(6));
            lanes.getSlots()[7].setInt(bytecode, frame, value.lane(7));
            lanes.getSlots()[8].setInt(bytecode, frame, value.lane(8));
            lanes.getSlots()[9].setInt(bytecode, frame, value.lane(9));
            lanes.getSlots()[10].setInt(bytecode, frame, value.lane(10));
            lanes.getSlots()[11].setInt(bytecode, frame, value.lane(11));
            lanes.getSlots()[12].setInt(bytecode, frame, value.lane(12));
            lanes.getSlots()[13].setInt(bytecode, frame, value.lane(13));
            lanes.getSlots()[14].setInt(bytecode, frame, value.lane(14));
            lanes.getSlots()[15].setInt(bytecode, frame, value.lane(15));
            lanes.getSlots()[16].setInt(bytecode, frame, value.lane(16));
            lanes.getSlots()[17].setInt(bytecode, frame, value.lane(17));
            lanes.getSlots()[18].setInt(bytecode, frame, value.lane(18));
            lanes.getSlots()[19].setInt(bytecode, frame, value.lane(19));
            lanes.getSlots()[20].setInt(bytecode, frame, value.lane(20));
            lanes.getSlots()[21].setInt(bytecode, frame, value.lane(21));
            lanes.getSlots()[22].setInt(bytecode, frame, value.lane(22));
            lanes.getSlots()[23].setInt(bytecode, frame, value.lane(23));
            lanes.getSlots()[24].setInt(bytecode, frame, value.lane(24));
            lanes.getSlots()[25].setInt(bytecode, frame, value.lane(25));
            lanes.getSlots()[26].setInt(bytecode, frame, value.lane(26));
            lanes.getSlots()[27].setInt(bytecode, frame, value.lane(27));
            lanes.getSlots()[28].setInt(bytecode, frame, value.lane(28));
            lanes.getSlots()[29].setInt(bytecode, frame, value.lane(29));
            lanes.getSlots()[30].setInt(bytecode, frame, value.lane(30));
            lanes.getSlots()[31].setInt(bytecode, frame, value.lane(31));
        }
    }
    @Operation public static final class GeneratedInt16X32Broadcast {
        @Specialization public static ShortVector apply(int value) { return ShortVector.broadcast(ShortVector.SPECIES_512, (short) value); }
    }
    @Operation public static final class GeneratedInt16X32Plus {
        @Specialization public static ShortVector apply(ShortVector left, ShortVector right) { return RuntimeTypes.requireShort(left, ShortVector.SPECIES_512).add(RuntimeTypes.requireShort(right, ShortVector.SPECIES_512)); }
    }
    @Operation public static final class GeneratedInt16X32Minus {
        @Specialization public static ShortVector apply(ShortVector left, ShortVector right) { return RuntimeTypes.requireShort(left, ShortVector.SPECIES_512).sub(RuntimeTypes.requireShort(right, ShortVector.SPECIES_512)); }
    }
    @Operation public static final class GeneratedInt16X32Times {
        @Specialization public static ShortVector apply(ShortVector left, ShortVector right) { return RuntimeTypes.requireShort(left, ShortVector.SPECIES_512).mul(RuntimeTypes.requireShort(right, ShortVector.SPECIES_512)); }
    }
    @Operation public static final class GeneratedInt16X32Negate {
        @Specialization public static ShortVector apply(ShortVector value) { return RuntimeTypes.requireShort(value, ShortVector.SPECIES_512).neg(); }
    }
    @Operation public static final class GeneratedInt16X32Insert {
        @Specialization public static ShortVector apply(ShortVector vector, int value, long index) { return RuntimeTypes.requireShort(vector, ShortVector.SPECIES_512).withLane(RuntimeTypes.laneIndex(index, 32), (short) value); }
    }
    @Operation public static final class GeneratedInt16X32Min {
        @Specialization public static ShortVector apply(ShortVector left, ShortVector right) { return RuntimeTypes.requireShort(left, ShortVector.SPECIES_512).min(RuntimeTypes.requireShort(right, ShortVector.SPECIES_512)); }
    }
    @Operation public static final class GeneratedInt16X32Max {
        @Specialization public static ShortVector apply(ShortVector left, ShortVector right) { return RuntimeTypes.requireShort(left, ShortVector.SPECIES_512).max(RuntimeTypes.requireShort(right, ShortVector.SPECIES_512)); }
    }
    @Operation public static final class GeneratedInt16X32Quot {
        @Specialization public static ShortVector apply(ShortVector left, ShortVector right) { return VectorIntegerDivision.quotShort(RuntimeTypes.requireShort(left, ShortVector.SPECIES_512), RuntimeTypes.requireShort(right, ShortVector.SPECIES_512), false); }
    }
    @Operation public static final class GeneratedInt16X32Rem {
        @Specialization public static ShortVector apply(ShortVector left, ShortVector right) { return VectorIntegerDivision.remShort(RuntimeTypes.requireShort(left, ShortVector.SPECIES_512), RuntimeTypes.requireShort(right, ShortVector.SPECIES_512), false); }
    }
    @Operation
    @ConstantOperand(type = VectorShuffle.class, name = "shuffle")
    public static final class GeneratedInt16X32Shuffle {
        @Specialization public static ShortVector apply(VectorShuffle<?> shuffle, ShortVector left, ShortVector right) {
            return RuntimeTypes.requireShort(left, ShortVector.SPECIES_512).rearrange(shuffle.check(ShortVector.SPECIES_512), RuntimeTypes.requireShort(right, ShortVector.SPECIES_512));
        }
    }
    @Operation
    @ConstantOperand(type = BytecodeVectorLanes.class, name = "lanes")
    public static final class GeneratedWord16X32Pack {
        @Specialization public static ShortVector apply(VirtualFrame frame, BytecodeVectorLanes lanes, @Bind("$node") Node node) {
            BytecodeNode bytecode = ((BytecodeRoot) node.getRootNode()).getBytecodeNode();
            try {
                return ShortVector.broadcast(ShortVector.SPECIES_512, (short) lanes.getSlots()[0].getInt(bytecode, frame)).withLane(1, (short) lanes.getSlots()[1].getInt(bytecode, frame)).withLane(2, (short) lanes.getSlots()[2].getInt(bytecode, frame)).withLane(3, (short) lanes.getSlots()[3].getInt(bytecode, frame)).withLane(4, (short) lanes.getSlots()[4].getInt(bytecode, frame)).withLane(5, (short) lanes.getSlots()[5].getInt(bytecode, frame)).withLane(6, (short) lanes.getSlots()[6].getInt(bytecode, frame)).withLane(7, (short) lanes.getSlots()[7].getInt(bytecode, frame)).withLane(8, (short) lanes.getSlots()[8].getInt(bytecode, frame)).withLane(9, (short) lanes.getSlots()[9].getInt(bytecode, frame)).withLane(10, (short) lanes.getSlots()[10].getInt(bytecode, frame)).withLane(11, (short) lanes.getSlots()[11].getInt(bytecode, frame)).withLane(12, (short) lanes.getSlots()[12].getInt(bytecode, frame)).withLane(13, (short) lanes.getSlots()[13].getInt(bytecode, frame)).withLane(14, (short) lanes.getSlots()[14].getInt(bytecode, frame)).withLane(15, (short) lanes.getSlots()[15].getInt(bytecode, frame)).withLane(16, (short) lanes.getSlots()[16].getInt(bytecode, frame)).withLane(17, (short) lanes.getSlots()[17].getInt(bytecode, frame)).withLane(18, (short) lanes.getSlots()[18].getInt(bytecode, frame)).withLane(19, (short) lanes.getSlots()[19].getInt(bytecode, frame)).withLane(20, (short) lanes.getSlots()[20].getInt(bytecode, frame)).withLane(21, (short) lanes.getSlots()[21].getInt(bytecode, frame)).withLane(22, (short) lanes.getSlots()[22].getInt(bytecode, frame)).withLane(23, (short) lanes.getSlots()[23].getInt(bytecode, frame)).withLane(24, (short) lanes.getSlots()[24].getInt(bytecode, frame)).withLane(25, (short) lanes.getSlots()[25].getInt(bytecode, frame)).withLane(26, (short) lanes.getSlots()[26].getInt(bytecode, frame)).withLane(27, (short) lanes.getSlots()[27].getInt(bytecode, frame)).withLane(28, (short) lanes.getSlots()[28].getInt(bytecode, frame)).withLane(29, (short) lanes.getSlots()[29].getInt(bytecode, frame)).withLane(30, (short) lanes.getSlots()[30].getInt(bytecode, frame)).withLane(31, (short) lanes.getSlots()[31].getInt(bytecode, frame));
            } catch (com.oracle.truffle.api.nodes.UnexpectedResultException invalid) {
                throw new RuntimeFault("Expected primitive vector lane");
            }
        }
    }
    @Operation
    @ConstantOperand(type = BytecodeVectorLanes.class, name = "lanes")
    public static final class GeneratedWord16X32Unpack {
        @Specialization public static void apply(VirtualFrame frame, BytecodeVectorLanes lanes, ShortVector raw, @Bind("$node") Node node) {
            BytecodeNode bytecode = ((BytecodeRoot) node.getRootNode()).getBytecodeNode();
            ShortVector value = RuntimeTypes.requireShort(raw, ShortVector.SPECIES_512);
            lanes.getSlots()[0].setInt(bytecode, frame, value.lane(0) & 0xffff);
            lanes.getSlots()[1].setInt(bytecode, frame, value.lane(1) & 0xffff);
            lanes.getSlots()[2].setInt(bytecode, frame, value.lane(2) & 0xffff);
            lanes.getSlots()[3].setInt(bytecode, frame, value.lane(3) & 0xffff);
            lanes.getSlots()[4].setInt(bytecode, frame, value.lane(4) & 0xffff);
            lanes.getSlots()[5].setInt(bytecode, frame, value.lane(5) & 0xffff);
            lanes.getSlots()[6].setInt(bytecode, frame, value.lane(6) & 0xffff);
            lanes.getSlots()[7].setInt(bytecode, frame, value.lane(7) & 0xffff);
            lanes.getSlots()[8].setInt(bytecode, frame, value.lane(8) & 0xffff);
            lanes.getSlots()[9].setInt(bytecode, frame, value.lane(9) & 0xffff);
            lanes.getSlots()[10].setInt(bytecode, frame, value.lane(10) & 0xffff);
            lanes.getSlots()[11].setInt(bytecode, frame, value.lane(11) & 0xffff);
            lanes.getSlots()[12].setInt(bytecode, frame, value.lane(12) & 0xffff);
            lanes.getSlots()[13].setInt(bytecode, frame, value.lane(13) & 0xffff);
            lanes.getSlots()[14].setInt(bytecode, frame, value.lane(14) & 0xffff);
            lanes.getSlots()[15].setInt(bytecode, frame, value.lane(15) & 0xffff);
            lanes.getSlots()[16].setInt(bytecode, frame, value.lane(16) & 0xffff);
            lanes.getSlots()[17].setInt(bytecode, frame, value.lane(17) & 0xffff);
            lanes.getSlots()[18].setInt(bytecode, frame, value.lane(18) & 0xffff);
            lanes.getSlots()[19].setInt(bytecode, frame, value.lane(19) & 0xffff);
            lanes.getSlots()[20].setInt(bytecode, frame, value.lane(20) & 0xffff);
            lanes.getSlots()[21].setInt(bytecode, frame, value.lane(21) & 0xffff);
            lanes.getSlots()[22].setInt(bytecode, frame, value.lane(22) & 0xffff);
            lanes.getSlots()[23].setInt(bytecode, frame, value.lane(23) & 0xffff);
            lanes.getSlots()[24].setInt(bytecode, frame, value.lane(24) & 0xffff);
            lanes.getSlots()[25].setInt(bytecode, frame, value.lane(25) & 0xffff);
            lanes.getSlots()[26].setInt(bytecode, frame, value.lane(26) & 0xffff);
            lanes.getSlots()[27].setInt(bytecode, frame, value.lane(27) & 0xffff);
            lanes.getSlots()[28].setInt(bytecode, frame, value.lane(28) & 0xffff);
            lanes.getSlots()[29].setInt(bytecode, frame, value.lane(29) & 0xffff);
            lanes.getSlots()[30].setInt(bytecode, frame, value.lane(30) & 0xffff);
            lanes.getSlots()[31].setInt(bytecode, frame, value.lane(31) & 0xffff);
        }
    }
    @Operation public static final class GeneratedWord16X32Broadcast {
        @Specialization public static ShortVector apply(int value) { return ShortVector.broadcast(ShortVector.SPECIES_512, (short) value); }
    }
    @Operation public static final class GeneratedWord16X32Plus {
        @Specialization public static ShortVector apply(ShortVector left, ShortVector right) { return RuntimeTypes.requireShort(left, ShortVector.SPECIES_512).add(RuntimeTypes.requireShort(right, ShortVector.SPECIES_512)); }
    }
    @Operation public static final class GeneratedWord16X32Minus {
        @Specialization public static ShortVector apply(ShortVector left, ShortVector right) { return RuntimeTypes.requireShort(left, ShortVector.SPECIES_512).sub(RuntimeTypes.requireShort(right, ShortVector.SPECIES_512)); }
    }
    @Operation public static final class GeneratedWord16X32Times {
        @Specialization public static ShortVector apply(ShortVector left, ShortVector right) { return RuntimeTypes.requireShort(left, ShortVector.SPECIES_512).mul(RuntimeTypes.requireShort(right, ShortVector.SPECIES_512)); }
    }
    @Operation public static final class GeneratedWord16X32Insert {
        @Specialization public static ShortVector apply(ShortVector vector, int value, long index) { return RuntimeTypes.requireShort(vector, ShortVector.SPECIES_512).withLane(RuntimeTypes.laneIndex(index, 32), (short) value); }
    }
    @Operation public static final class GeneratedWord16X32Min {
        @Specialization public static ShortVector apply(ShortVector left, ShortVector right) { return RuntimeTypes.requireShort(left, ShortVector.SPECIES_512).lanewise(VectorOperators.UMIN, RuntimeTypes.requireShort(right, ShortVector.SPECIES_512)); }
    }
    @Operation public static final class GeneratedWord16X32Max {
        @Specialization public static ShortVector apply(ShortVector left, ShortVector right) { return RuntimeTypes.requireShort(left, ShortVector.SPECIES_512).lanewise(VectorOperators.UMAX, RuntimeTypes.requireShort(right, ShortVector.SPECIES_512)); }
    }
    @Operation public static final class GeneratedWord16X32Quot {
        @Specialization public static ShortVector apply(ShortVector left, ShortVector right) { return VectorIntegerDivision.quotShort(RuntimeTypes.requireShort(left, ShortVector.SPECIES_512), RuntimeTypes.requireShort(right, ShortVector.SPECIES_512), true); }
    }
    @Operation public static final class GeneratedWord16X32Rem {
        @Specialization public static ShortVector apply(ShortVector left, ShortVector right) { return VectorIntegerDivision.remShort(RuntimeTypes.requireShort(left, ShortVector.SPECIES_512), RuntimeTypes.requireShort(right, ShortVector.SPECIES_512), true); }
    }
    @Operation
    @ConstantOperand(type = VectorShuffle.class, name = "shuffle")
    public static final class GeneratedWord16X32Shuffle {
        @Specialization public static ShortVector apply(VectorShuffle<?> shuffle, ShortVector left, ShortVector right) {
            return RuntimeTypes.requireShort(left, ShortVector.SPECIES_512).rearrange(shuffle.check(ShortVector.SPECIES_512), RuntimeTypes.requireShort(right, ShortVector.SPECIES_512));
        }
    }
    @Operation public static final class GeneratedWord64X2Pack {
        @Specialization public static LongVector apply(long lane0, long lane1) { return LongVector.broadcast(LongVector.SPECIES_128, lane0).withLane(1, lane1); }
    }
    @Operation
    @ConstantOperand(type = LocalAccessor.class, name = "lane0")
    @ConstantOperand(type = LocalAccessor.class, name = "lane1")
    public static final class GeneratedWord64X2Unpack {
        @Specialization public static void apply(VirtualFrame frame, LocalAccessor lane0, LocalAccessor lane1, LongVector raw, @Bind("$node") Node node) {
            BytecodeNode bytecode = ((BytecodeRoot) node.getRootNode()).getBytecodeNode();
            LongVector value = RuntimeTypes.requireLong(raw, LongVector.SPECIES_128);
            lane0.setLong(bytecode, frame, value.lane(0));
            lane1.setLong(bytecode, frame, value.lane(1));
        }
    }
    @Operation public static final class GeneratedWord64X2Broadcast {
        @Specialization public static LongVector apply(long value) { return LongVector.broadcast(LongVector.SPECIES_128, value); }
    }
    @Operation public static final class GeneratedWord64X2Plus {
        @Specialization public static LongVector apply(LongVector left, LongVector right) { return RuntimeTypes.requireLong(left, LongVector.SPECIES_128).add(RuntimeTypes.requireLong(right, LongVector.SPECIES_128)); }
    }
    @Operation public static final class GeneratedWord64X2Minus {
        @Specialization public static LongVector apply(LongVector left, LongVector right) { return RuntimeTypes.requireLong(left, LongVector.SPECIES_128).sub(RuntimeTypes.requireLong(right, LongVector.SPECIES_128)); }
    }
    @Operation public static final class GeneratedWord64X2Times {
        @Specialization public static LongVector apply(LongVector left, LongVector right) { return RuntimeTypes.requireLong(left, LongVector.SPECIES_128).mul(RuntimeTypes.requireLong(right, LongVector.SPECIES_128)); }
    }
    @Operation public static final class GeneratedWord64X2Insert {
        @Specialization public static LongVector apply(LongVector vector, long value, long index) { return RuntimeTypes.requireLong(vector, LongVector.SPECIES_128).withLane(RuntimeTypes.laneIndex(index, 2), value); }
    }
    @Operation public static final class GeneratedWord64X2Min {
        @Specialization public static LongVector apply(LongVector left, LongVector right) { return RuntimeTypes.requireLong(left, LongVector.SPECIES_128).lanewise(VectorOperators.UMIN, RuntimeTypes.requireLong(right, LongVector.SPECIES_128)); }
    }
    @Operation public static final class GeneratedWord64X2Max {
        @Specialization public static LongVector apply(LongVector left, LongVector right) { return RuntimeTypes.requireLong(left, LongVector.SPECIES_128).lanewise(VectorOperators.UMAX, RuntimeTypes.requireLong(right, LongVector.SPECIES_128)); }
    }
    @Operation public static final class GeneratedWord64X2Quot {
        @Specialization public static LongVector apply(LongVector left, LongVector right) { return VectorIntegerDivision.quotLong(RuntimeTypes.requireLong(left, LongVector.SPECIES_128), RuntimeTypes.requireLong(right, LongVector.SPECIES_128), true); }
    }
    @Operation public static final class GeneratedWord64X2Rem {
        @Specialization public static LongVector apply(LongVector left, LongVector right) { return VectorIntegerDivision.remLong(RuntimeTypes.requireLong(left, LongVector.SPECIES_128), RuntimeTypes.requireLong(right, LongVector.SPECIES_128), true); }
    }
    @Operation
    @ConstantOperand(type = VectorShuffle.class, name = "shuffle")
    public static final class GeneratedWord64X2Shuffle {
        @Specialization public static LongVector apply(VectorShuffle<?> shuffle, LongVector left, LongVector right) {
            return RuntimeTypes.requireLong(left, LongVector.SPECIES_128).rearrange(shuffle.check(LongVector.SPECIES_128), RuntimeTypes.requireLong(right, LongVector.SPECIES_128));
        }
    }
    @Operation public static final class GeneratedWord32X8Pack {
        @Specialization public static IntVector apply(int lane0, int lane1, int lane2, int lane3, int lane4, int lane5, int lane6, int lane7) { return IntVector.broadcast(IntVector.SPECIES_256, (int) lane0).withLane(1, (int) lane1).withLane(2, (int) lane2).withLane(3, (int) lane3).withLane(4, (int) lane4).withLane(5, (int) lane5).withLane(6, (int) lane6).withLane(7, (int) lane7); }
    }
    @Operation
    @ConstantOperand(type = LocalAccessor.class, name = "lane0")
    @ConstantOperand(type = LocalAccessor.class, name = "lane1")
    @ConstantOperand(type = LocalAccessor.class, name = "lane2")
    @ConstantOperand(type = LocalAccessor.class, name = "lane3")
    @ConstantOperand(type = LocalAccessor.class, name = "lane4")
    @ConstantOperand(type = LocalAccessor.class, name = "lane5")
    @ConstantOperand(type = LocalAccessor.class, name = "lane6")
    @ConstantOperand(type = LocalAccessor.class, name = "lane7")
    public static final class GeneratedWord32X8Unpack {
        @Specialization public static void apply(VirtualFrame frame, LocalAccessor lane0, LocalAccessor lane1, LocalAccessor lane2, LocalAccessor lane3, LocalAccessor lane4, LocalAccessor lane5, LocalAccessor lane6, LocalAccessor lane7, IntVector raw, @Bind("$node") Node node) {
            BytecodeNode bytecode = ((BytecodeRoot) node.getRootNode()).getBytecodeNode();
            IntVector value = RuntimeTypes.requireInt(raw, IntVector.SPECIES_256);
            lane0.setInt(bytecode, frame, value.lane(0));
            lane1.setInt(bytecode, frame, value.lane(1));
            lane2.setInt(bytecode, frame, value.lane(2));
            lane3.setInt(bytecode, frame, value.lane(3));
            lane4.setInt(bytecode, frame, value.lane(4));
            lane5.setInt(bytecode, frame, value.lane(5));
            lane6.setInt(bytecode, frame, value.lane(6));
            lane7.setInt(bytecode, frame, value.lane(7));
        }
    }
    @Operation public static final class GeneratedWord32X8Broadcast {
        @Specialization public static IntVector apply(int value) { return IntVector.broadcast(IntVector.SPECIES_256, (int) value); }
    }
    @Operation public static final class GeneratedWord32X8Plus {
        @Specialization public static IntVector apply(IntVector left, IntVector right) { return RuntimeTypes.requireInt(left, IntVector.SPECIES_256).add(RuntimeTypes.requireInt(right, IntVector.SPECIES_256)); }
    }
    @Operation public static final class GeneratedWord32X8Minus {
        @Specialization public static IntVector apply(IntVector left, IntVector right) { return RuntimeTypes.requireInt(left, IntVector.SPECIES_256).sub(RuntimeTypes.requireInt(right, IntVector.SPECIES_256)); }
    }
    @Operation public static final class GeneratedWord32X8Times {
        @Specialization public static IntVector apply(IntVector left, IntVector right) { return RuntimeTypes.requireInt(left, IntVector.SPECIES_256).mul(RuntimeTypes.requireInt(right, IntVector.SPECIES_256)); }
    }
    @Operation public static final class GeneratedWord32X8Insert {
        @Specialization public static IntVector apply(IntVector vector, int value, long index) { return RuntimeTypes.requireInt(vector, IntVector.SPECIES_256).withLane(RuntimeTypes.laneIndex(index, 8), (int) value); }
    }
    @Operation public static final class GeneratedWord32X8Min {
        @Specialization public static IntVector apply(IntVector left, IntVector right) { return RuntimeTypes.requireInt(left, IntVector.SPECIES_256).lanewise(VectorOperators.UMIN, RuntimeTypes.requireInt(right, IntVector.SPECIES_256)); }
    }
    @Operation public static final class GeneratedWord32X8Max {
        @Specialization public static IntVector apply(IntVector left, IntVector right) { return RuntimeTypes.requireInt(left, IntVector.SPECIES_256).lanewise(VectorOperators.UMAX, RuntimeTypes.requireInt(right, IntVector.SPECIES_256)); }
    }
    @Operation public static final class GeneratedWord32X8Quot {
        @Specialization public static IntVector apply(IntVector left, IntVector right) { return VectorIntegerDivision.quotInt(RuntimeTypes.requireInt(left, IntVector.SPECIES_256), RuntimeTypes.requireInt(right, IntVector.SPECIES_256), true); }
    }
    @Operation public static final class GeneratedWord32X8Rem {
        @Specialization public static IntVector apply(IntVector left, IntVector right) { return VectorIntegerDivision.remInt(RuntimeTypes.requireInt(left, IntVector.SPECIES_256), RuntimeTypes.requireInt(right, IntVector.SPECIES_256), true); }
    }
    @Operation
    @ConstantOperand(type = VectorShuffle.class, name = "shuffle")
    public static final class GeneratedWord32X8Shuffle {
        @Specialization public static IntVector apply(VectorShuffle<?> shuffle, IntVector left, IntVector right) {
            return RuntimeTypes.requireInt(left, IntVector.SPECIES_256).rearrange(shuffle.check(IntVector.SPECIES_256), RuntimeTypes.requireInt(right, IntVector.SPECIES_256));
        }
    }
    @Operation public static final class GeneratedInt32X8Pack {
        @Specialization public static IntVector apply(int lane0, int lane1, int lane2, int lane3, int lane4, int lane5, int lane6, int lane7) { return IntVector.broadcast(IntVector.SPECIES_256, (int) lane0).withLane(1, (int) lane1).withLane(2, (int) lane2).withLane(3, (int) lane3).withLane(4, (int) lane4).withLane(5, (int) lane5).withLane(6, (int) lane6).withLane(7, (int) lane7); }
    }
    @Operation
    @ConstantOperand(type = LocalAccessor.class, name = "lane0")
    @ConstantOperand(type = LocalAccessor.class, name = "lane1")
    @ConstantOperand(type = LocalAccessor.class, name = "lane2")
    @ConstantOperand(type = LocalAccessor.class, name = "lane3")
    @ConstantOperand(type = LocalAccessor.class, name = "lane4")
    @ConstantOperand(type = LocalAccessor.class, name = "lane5")
    @ConstantOperand(type = LocalAccessor.class, name = "lane6")
    @ConstantOperand(type = LocalAccessor.class, name = "lane7")
    public static final class GeneratedInt32X8Unpack {
        @Specialization public static void apply(VirtualFrame frame, LocalAccessor lane0, LocalAccessor lane1, LocalAccessor lane2, LocalAccessor lane3, LocalAccessor lane4, LocalAccessor lane5, LocalAccessor lane6, LocalAccessor lane7, IntVector raw, @Bind("$node") Node node) {
            BytecodeNode bytecode = ((BytecodeRoot) node.getRootNode()).getBytecodeNode();
            IntVector value = RuntimeTypes.requireInt(raw, IntVector.SPECIES_256);
            lane0.setInt(bytecode, frame, value.lane(0));
            lane1.setInt(bytecode, frame, value.lane(1));
            lane2.setInt(bytecode, frame, value.lane(2));
            lane3.setInt(bytecode, frame, value.lane(3));
            lane4.setInt(bytecode, frame, value.lane(4));
            lane5.setInt(bytecode, frame, value.lane(5));
            lane6.setInt(bytecode, frame, value.lane(6));
            lane7.setInt(bytecode, frame, value.lane(7));
        }
    }
    @Operation public static final class GeneratedInt32X8Broadcast {
        @Specialization public static IntVector apply(int value) { return IntVector.broadcast(IntVector.SPECIES_256, (int) value); }
    }
    @Operation public static final class GeneratedInt32X8Plus {
        @Specialization public static IntVector apply(IntVector left, IntVector right) { return RuntimeTypes.requireInt(left, IntVector.SPECIES_256).add(RuntimeTypes.requireInt(right, IntVector.SPECIES_256)); }
    }
    @Operation public static final class GeneratedInt32X8Minus {
        @Specialization public static IntVector apply(IntVector left, IntVector right) { return RuntimeTypes.requireInt(left, IntVector.SPECIES_256).sub(RuntimeTypes.requireInt(right, IntVector.SPECIES_256)); }
    }
    @Operation public static final class GeneratedInt32X8Times {
        @Specialization public static IntVector apply(IntVector left, IntVector right) { return RuntimeTypes.requireInt(left, IntVector.SPECIES_256).mul(RuntimeTypes.requireInt(right, IntVector.SPECIES_256)); }
    }
    @Operation public static final class GeneratedInt32X8Negate {
        @Specialization public static IntVector apply(IntVector value) { return RuntimeTypes.requireInt(value, IntVector.SPECIES_256).neg(); }
    }
    @Operation public static final class GeneratedInt32X8Insert {
        @Specialization public static IntVector apply(IntVector vector, int value, long index) { return RuntimeTypes.requireInt(vector, IntVector.SPECIES_256).withLane(RuntimeTypes.laneIndex(index, 8), (int) value); }
    }
    @Operation public static final class GeneratedInt32X8Min {
        @Specialization public static IntVector apply(IntVector left, IntVector right) { return RuntimeTypes.requireInt(left, IntVector.SPECIES_256).min(RuntimeTypes.requireInt(right, IntVector.SPECIES_256)); }
    }
    @Operation public static final class GeneratedInt32X8Max {
        @Specialization public static IntVector apply(IntVector left, IntVector right) { return RuntimeTypes.requireInt(left, IntVector.SPECIES_256).max(RuntimeTypes.requireInt(right, IntVector.SPECIES_256)); }
    }
    @Operation public static final class GeneratedInt32X8Quot {
        @Specialization public static IntVector apply(IntVector left, IntVector right) { return VectorIntegerDivision.quotInt(RuntimeTypes.requireInt(left, IntVector.SPECIES_256), RuntimeTypes.requireInt(right, IntVector.SPECIES_256), false); }
    }
    @Operation public static final class GeneratedInt32X8Rem {
        @Specialization public static IntVector apply(IntVector left, IntVector right) { return VectorIntegerDivision.remInt(RuntimeTypes.requireInt(left, IntVector.SPECIES_256), RuntimeTypes.requireInt(right, IntVector.SPECIES_256), false); }
    }
    @Operation
    @ConstantOperand(type = VectorShuffle.class, name = "shuffle")
    public static final class GeneratedInt32X8Shuffle {
        @Specialization public static IntVector apply(VectorShuffle<?> shuffle, IntVector left, IntVector right) {
            return RuntimeTypes.requireInt(left, IntVector.SPECIES_256).rearrange(shuffle.check(IntVector.SPECIES_256), RuntimeTypes.requireInt(right, IntVector.SPECIES_256));
        }
    }
    @Operation
    @ConstantOperand(type = BytecodeVectorLanes.class, name = "lanes")
    public static final class GeneratedInt32X16Pack {
        @Specialization public static IntVector apply(VirtualFrame frame, BytecodeVectorLanes lanes, @Bind("$node") Node node) {
            BytecodeNode bytecode = ((BytecodeRoot) node.getRootNode()).getBytecodeNode();
            try {
                return IntVector.broadcast(IntVector.SPECIES_512, (int) lanes.getSlots()[0].getInt(bytecode, frame)).withLane(1, (int) lanes.getSlots()[1].getInt(bytecode, frame)).withLane(2, (int) lanes.getSlots()[2].getInt(bytecode, frame)).withLane(3, (int) lanes.getSlots()[3].getInt(bytecode, frame)).withLane(4, (int) lanes.getSlots()[4].getInt(bytecode, frame)).withLane(5, (int) lanes.getSlots()[5].getInt(bytecode, frame)).withLane(6, (int) lanes.getSlots()[6].getInt(bytecode, frame)).withLane(7, (int) lanes.getSlots()[7].getInt(bytecode, frame)).withLane(8, (int) lanes.getSlots()[8].getInt(bytecode, frame)).withLane(9, (int) lanes.getSlots()[9].getInt(bytecode, frame)).withLane(10, (int) lanes.getSlots()[10].getInt(bytecode, frame)).withLane(11, (int) lanes.getSlots()[11].getInt(bytecode, frame)).withLane(12, (int) lanes.getSlots()[12].getInt(bytecode, frame)).withLane(13, (int) lanes.getSlots()[13].getInt(bytecode, frame)).withLane(14, (int) lanes.getSlots()[14].getInt(bytecode, frame)).withLane(15, (int) lanes.getSlots()[15].getInt(bytecode, frame));
            } catch (com.oracle.truffle.api.nodes.UnexpectedResultException invalid) {
                throw new RuntimeFault("Expected primitive vector lane");
            }
        }
    }
    @Operation
    @ConstantOperand(type = BytecodeVectorLanes.class, name = "lanes")
    public static final class GeneratedInt32X16Unpack {
        @Specialization public static void apply(VirtualFrame frame, BytecodeVectorLanes lanes, IntVector raw, @Bind("$node") Node node) {
            BytecodeNode bytecode = ((BytecodeRoot) node.getRootNode()).getBytecodeNode();
            IntVector value = RuntimeTypes.requireInt(raw, IntVector.SPECIES_512);
            lanes.getSlots()[0].setInt(bytecode, frame, value.lane(0));
            lanes.getSlots()[1].setInt(bytecode, frame, value.lane(1));
            lanes.getSlots()[2].setInt(bytecode, frame, value.lane(2));
            lanes.getSlots()[3].setInt(bytecode, frame, value.lane(3));
            lanes.getSlots()[4].setInt(bytecode, frame, value.lane(4));
            lanes.getSlots()[5].setInt(bytecode, frame, value.lane(5));
            lanes.getSlots()[6].setInt(bytecode, frame, value.lane(6));
            lanes.getSlots()[7].setInt(bytecode, frame, value.lane(7));
            lanes.getSlots()[8].setInt(bytecode, frame, value.lane(8));
            lanes.getSlots()[9].setInt(bytecode, frame, value.lane(9));
            lanes.getSlots()[10].setInt(bytecode, frame, value.lane(10));
            lanes.getSlots()[11].setInt(bytecode, frame, value.lane(11));
            lanes.getSlots()[12].setInt(bytecode, frame, value.lane(12));
            lanes.getSlots()[13].setInt(bytecode, frame, value.lane(13));
            lanes.getSlots()[14].setInt(bytecode, frame, value.lane(14));
            lanes.getSlots()[15].setInt(bytecode, frame, value.lane(15));
        }
    }
    @Operation public static final class GeneratedInt32X16Broadcast {
        @Specialization public static IntVector apply(int value) { return IntVector.broadcast(IntVector.SPECIES_512, (int) value); }
    }
    @Operation public static final class GeneratedInt32X16Plus {
        @Specialization public static IntVector apply(IntVector left, IntVector right) { return RuntimeTypes.requireInt(left, IntVector.SPECIES_512).add(RuntimeTypes.requireInt(right, IntVector.SPECIES_512)); }
    }
    @Operation public static final class GeneratedInt32X16Minus {
        @Specialization public static IntVector apply(IntVector left, IntVector right) { return RuntimeTypes.requireInt(left, IntVector.SPECIES_512).sub(RuntimeTypes.requireInt(right, IntVector.SPECIES_512)); }
    }
    @Operation public static final class GeneratedInt32X16Times {
        @Specialization public static IntVector apply(IntVector left, IntVector right) { return RuntimeTypes.requireInt(left, IntVector.SPECIES_512).mul(RuntimeTypes.requireInt(right, IntVector.SPECIES_512)); }
    }
    @Operation public static final class GeneratedInt32X16Negate {
        @Specialization public static IntVector apply(IntVector value) { return RuntimeTypes.requireInt(value, IntVector.SPECIES_512).neg(); }
    }
    @Operation public static final class GeneratedInt32X16Insert {
        @Specialization public static IntVector apply(IntVector vector, int value, long index) { return RuntimeTypes.requireInt(vector, IntVector.SPECIES_512).withLane(RuntimeTypes.laneIndex(index, 16), (int) value); }
    }
    @Operation public static final class GeneratedInt32X16Min {
        @Specialization public static IntVector apply(IntVector left, IntVector right) { return RuntimeTypes.requireInt(left, IntVector.SPECIES_512).min(RuntimeTypes.requireInt(right, IntVector.SPECIES_512)); }
    }
    @Operation public static final class GeneratedInt32X16Max {
        @Specialization public static IntVector apply(IntVector left, IntVector right) { return RuntimeTypes.requireInt(left, IntVector.SPECIES_512).max(RuntimeTypes.requireInt(right, IntVector.SPECIES_512)); }
    }
    @Operation public static final class GeneratedInt32X16Quot {
        @Specialization public static IntVector apply(IntVector left, IntVector right) { return VectorIntegerDivision.quotInt(RuntimeTypes.requireInt(left, IntVector.SPECIES_512), RuntimeTypes.requireInt(right, IntVector.SPECIES_512), false); }
    }
    @Operation public static final class GeneratedInt32X16Rem {
        @Specialization public static IntVector apply(IntVector left, IntVector right) { return VectorIntegerDivision.remInt(RuntimeTypes.requireInt(left, IntVector.SPECIES_512), RuntimeTypes.requireInt(right, IntVector.SPECIES_512), false); }
    }
    @Operation
    @ConstantOperand(type = VectorShuffle.class, name = "shuffle")
    public static final class GeneratedInt32X16Shuffle {
        @Specialization public static IntVector apply(VectorShuffle<?> shuffle, IntVector left, IntVector right) {
            return RuntimeTypes.requireInt(left, IntVector.SPECIES_512).rearrange(shuffle.check(IntVector.SPECIES_512), RuntimeTypes.requireInt(right, IntVector.SPECIES_512));
        }
    }
    @Operation public static final class GeneratedInt64X2Times {
        @Specialization public static LongVector apply(LongVector left, LongVector right) { return RuntimeTypes.requireLong(left, LongVector.SPECIES_128).mul(RuntimeTypes.requireLong(right, LongVector.SPECIES_128)); }
    }
    @Operation public static final class GeneratedInt64X2Insert {
        @Specialization public static LongVector apply(LongVector vector, long value, long index) { return RuntimeTypes.requireLong(vector, LongVector.SPECIES_128).withLane(RuntimeTypes.laneIndex(index, 2), value); }
    }
    @Operation public static final class GeneratedInt64X2Min {
        @Specialization public static LongVector apply(LongVector left, LongVector right) { return RuntimeTypes.requireLong(left, LongVector.SPECIES_128).min(RuntimeTypes.requireLong(right, LongVector.SPECIES_128)); }
    }
    @Operation public static final class GeneratedInt64X2Max {
        @Specialization public static LongVector apply(LongVector left, LongVector right) { return RuntimeTypes.requireLong(left, LongVector.SPECIES_128).max(RuntimeTypes.requireLong(right, LongVector.SPECIES_128)); }
    }
    @Operation public static final class GeneratedInt64X2Quot {
        @Specialization public static LongVector apply(LongVector left, LongVector right) { return VectorIntegerDivision.quotLong(RuntimeTypes.requireLong(left, LongVector.SPECIES_128), RuntimeTypes.requireLong(right, LongVector.SPECIES_128), false); }
    }
    @Operation public static final class GeneratedInt64X2Rem {
        @Specialization public static LongVector apply(LongVector left, LongVector right) { return VectorIntegerDivision.remLong(RuntimeTypes.requireLong(left, LongVector.SPECIES_128), RuntimeTypes.requireLong(right, LongVector.SPECIES_128), false); }
    }
    @Operation
    @ConstantOperand(type = VectorShuffle.class, name = "shuffle")
    public static final class GeneratedInt64X2Shuffle {
        @Specialization public static LongVector apply(VectorShuffle<?> shuffle, LongVector left, LongVector right) {
            return RuntimeTypes.requireLong(left, LongVector.SPECIES_128).rearrange(shuffle.check(LongVector.SPECIES_128), RuntimeTypes.requireLong(right, LongVector.SPECIES_128));
        }
    }
    @Operation public static final class GeneratedFloatX4Negate {
        @Specialization public static FloatVector apply(FloatVector value) { return RuntimeTypes.requireFloat(value, FloatVector.SPECIES_128).neg(); }
    }
    @Operation public static final class GeneratedFloatX4Divide {
        @Specialization public static FloatVector apply(FloatVector left, FloatVector right) { return RuntimeTypes.requireFloat(left, FloatVector.SPECIES_128).div(RuntimeTypes.requireFloat(right, FloatVector.SPECIES_128)); }
    }
    @Operation public static final class GeneratedFloatX4Insert {
        @Specialization public static FloatVector apply(FloatVector vector, float value, long index) { return RuntimeTypes.requireFloat(vector, FloatVector.SPECIES_128).withLane(RuntimeTypes.laneIndex(index, 4), value); }
    }
    @Operation public static final class GeneratedFloatX4Min {
        @Specialization public static FloatVector apply(FloatVector left, FloatVector right) { return RuntimeTypes.requireFloat(left, FloatVector.SPECIES_128).min(RuntimeTypes.requireFloat(right, FloatVector.SPECIES_128)); }
    }
    @Operation public static final class GeneratedFloatX4Max {
        @Specialization public static FloatVector apply(FloatVector left, FloatVector right) { return RuntimeTypes.requireFloat(left, FloatVector.SPECIES_128).max(RuntimeTypes.requireFloat(right, FloatVector.SPECIES_128)); }
    }
    @Operation
    @ConstantOperand(type = VectorShuffle.class, name = "shuffle")
    public static final class GeneratedFloatX4Shuffle {
        @Specialization public static FloatVector apply(VectorShuffle<?> shuffle, FloatVector left, FloatVector right) {
            return RuntimeTypes.requireFloat(left, FloatVector.SPECIES_128).rearrange(shuffle.check(FloatVector.SPECIES_128), RuntimeTypes.requireFloat(right, FloatVector.SPECIES_128));
        }
    }
    @Operation public static final class GeneratedDoubleX2Negate {
        @Specialization public static DoubleVector apply(DoubleVector value) { return RuntimeTypes.requireDouble(value, DoubleVector.SPECIES_128).neg(); }
    }
    @Operation public static final class GeneratedDoubleX2Divide {
        @Specialization public static DoubleVector apply(DoubleVector left, DoubleVector right) { return RuntimeTypes.requireDouble(left, DoubleVector.SPECIES_128).div(RuntimeTypes.requireDouble(right, DoubleVector.SPECIES_128)); }
    }
    @Operation public static final class GeneratedDoubleX2Insert {
        @Specialization public static DoubleVector apply(DoubleVector vector, double value, long index) { return RuntimeTypes.requireDouble(vector, DoubleVector.SPECIES_128).withLane(RuntimeTypes.laneIndex(index, 2), value); }
    }
    @Operation public static final class GeneratedDoubleX2Min {
        @Specialization public static DoubleVector apply(DoubleVector left, DoubleVector right) { return RuntimeTypes.requireDouble(left, DoubleVector.SPECIES_128).min(RuntimeTypes.requireDouble(right, DoubleVector.SPECIES_128)); }
    }
    @Operation public static final class GeneratedDoubleX2Max {
        @Specialization public static DoubleVector apply(DoubleVector left, DoubleVector right) { return RuntimeTypes.requireDouble(left, DoubleVector.SPECIES_128).max(RuntimeTypes.requireDouble(right, DoubleVector.SPECIES_128)); }
    }
    @Operation
    @ConstantOperand(type = VectorShuffle.class, name = "shuffle")
    public static final class GeneratedDoubleX2Shuffle {
        @Specialization public static DoubleVector apply(VectorShuffle<?> shuffle, DoubleVector left, DoubleVector right) {
            return RuntimeTypes.requireDouble(left, DoubleVector.SPECIES_128).rearrange(shuffle.check(DoubleVector.SPECIES_128), RuntimeTypes.requireDouble(right, DoubleVector.SPECIES_128));
        }
    }
    @Operation public static final class GeneratedFloatX8Pack {
        @Specialization public static FloatVector apply(float lane0, float lane1, float lane2, float lane3, float lane4, float lane5, float lane6, float lane7) { return FloatVector.broadcast(FloatVector.SPECIES_256, lane0).withLane(1, lane1).withLane(2, lane2).withLane(3, lane3).withLane(4, lane4).withLane(5, lane5).withLane(6, lane6).withLane(7, lane7); }
    }
    @Operation
    @ConstantOperand(type = LocalAccessor.class, name = "lane0")
    @ConstantOperand(type = LocalAccessor.class, name = "lane1")
    @ConstantOperand(type = LocalAccessor.class, name = "lane2")
    @ConstantOperand(type = LocalAccessor.class, name = "lane3")
    @ConstantOperand(type = LocalAccessor.class, name = "lane4")
    @ConstantOperand(type = LocalAccessor.class, name = "lane5")
    @ConstantOperand(type = LocalAccessor.class, name = "lane6")
    @ConstantOperand(type = LocalAccessor.class, name = "lane7")
    public static final class GeneratedFloatX8Unpack {
        @Specialization public static void apply(VirtualFrame frame, LocalAccessor lane0, LocalAccessor lane1, LocalAccessor lane2, LocalAccessor lane3, LocalAccessor lane4, LocalAccessor lane5, LocalAccessor lane6, LocalAccessor lane7, FloatVector raw, @Bind("$node") Node node) {
            BytecodeNode bytecode = ((BytecodeRoot) node.getRootNode()).getBytecodeNode();
            FloatVector value = RuntimeTypes.requireFloat(raw, FloatVector.SPECIES_256);
            lane0.setFloat(bytecode, frame, value.lane(0));
            lane1.setFloat(bytecode, frame, value.lane(1));
            lane2.setFloat(bytecode, frame, value.lane(2));
            lane3.setFloat(bytecode, frame, value.lane(3));
            lane4.setFloat(bytecode, frame, value.lane(4));
            lane5.setFloat(bytecode, frame, value.lane(5));
            lane6.setFloat(bytecode, frame, value.lane(6));
            lane7.setFloat(bytecode, frame, value.lane(7));
        }
    }
    @Operation public static final class GeneratedFloatX8Broadcast {
        @Specialization public static FloatVector apply(float value) { return FloatVector.broadcast(FloatVector.SPECIES_256, value); }
    }
    @Operation public static final class GeneratedFloatX8Plus {
        @Specialization public static FloatVector apply(FloatVector left, FloatVector right) { return RuntimeTypes.requireFloat(left, FloatVector.SPECIES_256).add(RuntimeTypes.requireFloat(right, FloatVector.SPECIES_256)); }
    }
    @Operation public static final class GeneratedFloatX8Minus {
        @Specialization public static FloatVector apply(FloatVector left, FloatVector right) { return RuntimeTypes.requireFloat(left, FloatVector.SPECIES_256).sub(RuntimeTypes.requireFloat(right, FloatVector.SPECIES_256)); }
    }
    @Operation public static final class GeneratedFloatX8Times {
        @Specialization public static FloatVector apply(FloatVector left, FloatVector right) { return RuntimeTypes.requireFloat(left, FloatVector.SPECIES_256).mul(RuntimeTypes.requireFloat(right, FloatVector.SPECIES_256)); }
    }
    @Operation public static final class GeneratedFloatX8Negate {
        @Specialization public static FloatVector apply(FloatVector value) { return RuntimeTypes.requireFloat(value, FloatVector.SPECIES_256).neg(); }
    }
    @Operation public static final class GeneratedFloatX8Divide {
        @Specialization public static FloatVector apply(FloatVector left, FloatVector right) { return RuntimeTypes.requireFloat(left, FloatVector.SPECIES_256).div(RuntimeTypes.requireFloat(right, FloatVector.SPECIES_256)); }
    }
    @Operation public static final class GeneratedFloatX8Insert {
        @Specialization public static FloatVector apply(FloatVector vector, float value, long index) { return RuntimeTypes.requireFloat(vector, FloatVector.SPECIES_256).withLane(RuntimeTypes.laneIndex(index, 8), value); }
    }
    @Operation public static final class GeneratedFloatX8Min {
        @Specialization public static FloatVector apply(FloatVector left, FloatVector right) { return RuntimeTypes.requireFloat(left, FloatVector.SPECIES_256).min(RuntimeTypes.requireFloat(right, FloatVector.SPECIES_256)); }
    }
    @Operation public static final class GeneratedFloatX8Max {
        @Specialization public static FloatVector apply(FloatVector left, FloatVector right) { return RuntimeTypes.requireFloat(left, FloatVector.SPECIES_256).max(RuntimeTypes.requireFloat(right, FloatVector.SPECIES_256)); }
    }
    @Operation
    @ConstantOperand(type = VectorShuffle.class, name = "shuffle")
    public static final class GeneratedFloatX8Shuffle {
        @Specialization public static FloatVector apply(VectorShuffle<?> shuffle, FloatVector left, FloatVector right) {
            return RuntimeTypes.requireFloat(left, FloatVector.SPECIES_256).rearrange(shuffle.check(FloatVector.SPECIES_256), RuntimeTypes.requireFloat(right, FloatVector.SPECIES_256));
        }
    }
    @Operation public static final class GeneratedDoubleX4Pack {
        @Specialization public static DoubleVector apply(double lane0, double lane1, double lane2, double lane3) { return DoubleVector.broadcast(DoubleVector.SPECIES_256, lane0).withLane(1, lane1).withLane(2, lane2).withLane(3, lane3); }
    }
    @Operation
    @ConstantOperand(type = LocalAccessor.class, name = "lane0")
    @ConstantOperand(type = LocalAccessor.class, name = "lane1")
    @ConstantOperand(type = LocalAccessor.class, name = "lane2")
    @ConstantOperand(type = LocalAccessor.class, name = "lane3")
    public static final class GeneratedDoubleX4Unpack {
        @Specialization public static void apply(VirtualFrame frame, LocalAccessor lane0, LocalAccessor lane1, LocalAccessor lane2, LocalAccessor lane3, DoubleVector raw, @Bind("$node") Node node) {
            BytecodeNode bytecode = ((BytecodeRoot) node.getRootNode()).getBytecodeNode();
            DoubleVector value = RuntimeTypes.requireDouble(raw, DoubleVector.SPECIES_256);
            lane0.setDouble(bytecode, frame, value.lane(0));
            lane1.setDouble(bytecode, frame, value.lane(1));
            lane2.setDouble(bytecode, frame, value.lane(2));
            lane3.setDouble(bytecode, frame, value.lane(3));
        }
    }
    @Operation public static final class GeneratedDoubleX4Broadcast {
        @Specialization public static DoubleVector apply(double value) { return DoubleVector.broadcast(DoubleVector.SPECIES_256, value); }
    }
    @Operation public static final class GeneratedDoubleX4Plus {
        @Specialization public static DoubleVector apply(DoubleVector left, DoubleVector right) { return RuntimeTypes.requireDouble(left, DoubleVector.SPECIES_256).add(RuntimeTypes.requireDouble(right, DoubleVector.SPECIES_256)); }
    }
    @Operation public static final class GeneratedDoubleX4Minus {
        @Specialization public static DoubleVector apply(DoubleVector left, DoubleVector right) { return RuntimeTypes.requireDouble(left, DoubleVector.SPECIES_256).sub(RuntimeTypes.requireDouble(right, DoubleVector.SPECIES_256)); }
    }
    @Operation public static final class GeneratedDoubleX4Times {
        @Specialization public static DoubleVector apply(DoubleVector left, DoubleVector right) { return RuntimeTypes.requireDouble(left, DoubleVector.SPECIES_256).mul(RuntimeTypes.requireDouble(right, DoubleVector.SPECIES_256)); }
    }
    @Operation public static final class GeneratedDoubleX4Negate {
        @Specialization public static DoubleVector apply(DoubleVector value) { return RuntimeTypes.requireDouble(value, DoubleVector.SPECIES_256).neg(); }
    }
    @Operation public static final class GeneratedDoubleX4Divide {
        @Specialization public static DoubleVector apply(DoubleVector left, DoubleVector right) { return RuntimeTypes.requireDouble(left, DoubleVector.SPECIES_256).div(RuntimeTypes.requireDouble(right, DoubleVector.SPECIES_256)); }
    }
    @Operation public static final class GeneratedDoubleX4Insert {
        @Specialization public static DoubleVector apply(DoubleVector vector, double value, long index) { return RuntimeTypes.requireDouble(vector, DoubleVector.SPECIES_256).withLane(RuntimeTypes.laneIndex(index, 4), value); }
    }
    @Operation public static final class GeneratedDoubleX4Min {
        @Specialization public static DoubleVector apply(DoubleVector left, DoubleVector right) { return RuntimeTypes.requireDouble(left, DoubleVector.SPECIES_256).min(RuntimeTypes.requireDouble(right, DoubleVector.SPECIES_256)); }
    }
    @Operation public static final class GeneratedDoubleX4Max {
        @Specialization public static DoubleVector apply(DoubleVector left, DoubleVector right) { return RuntimeTypes.requireDouble(left, DoubleVector.SPECIES_256).max(RuntimeTypes.requireDouble(right, DoubleVector.SPECIES_256)); }
    }
    @Operation
    @ConstantOperand(type = VectorShuffle.class, name = "shuffle")
    public static final class GeneratedDoubleX4Shuffle {
        @Specialization public static DoubleVector apply(VectorShuffle<?> shuffle, DoubleVector left, DoubleVector right) {
            return RuntimeTypes.requireDouble(left, DoubleVector.SPECIES_256).rearrange(shuffle.check(DoubleVector.SPECIES_256), RuntimeTypes.requireDouble(right, DoubleVector.SPECIES_256));
        }
    }
    @Operation public static final class GeneratedInt64X4Pack {
        @Specialization public static LongVector apply(long lane0, long lane1, long lane2, long lane3) { return LongVector.broadcast(LongVector.SPECIES_256, lane0).withLane(1, lane1).withLane(2, lane2).withLane(3, lane3); }
    }
    @Operation
    @ConstantOperand(type = LocalAccessor.class, name = "lane0")
    @ConstantOperand(type = LocalAccessor.class, name = "lane1")
    @ConstantOperand(type = LocalAccessor.class, name = "lane2")
    @ConstantOperand(type = LocalAccessor.class, name = "lane3")
    public static final class GeneratedInt64X4Unpack {
        @Specialization public static void apply(VirtualFrame frame, LocalAccessor lane0, LocalAccessor lane1, LocalAccessor lane2, LocalAccessor lane3, LongVector raw, @Bind("$node") Node node) {
            BytecodeNode bytecode = ((BytecodeRoot) node.getRootNode()).getBytecodeNode();
            LongVector value = RuntimeTypes.requireLong(raw, LongVector.SPECIES_256);
            lane0.setLong(bytecode, frame, value.lane(0));
            lane1.setLong(bytecode, frame, value.lane(1));
            lane2.setLong(bytecode, frame, value.lane(2));
            lane3.setLong(bytecode, frame, value.lane(3));
        }
    }
    @Operation public static final class GeneratedInt64X4Broadcast {
        @Specialization public static LongVector apply(long value) { return LongVector.broadcast(LongVector.SPECIES_256, value); }
    }
    @Operation public static final class GeneratedInt64X4Plus {
        @Specialization public static LongVector apply(LongVector left, LongVector right) { return RuntimeTypes.requireLong(left, LongVector.SPECIES_256).add(RuntimeTypes.requireLong(right, LongVector.SPECIES_256)); }
    }
    @Operation public static final class GeneratedInt64X4Minus {
        @Specialization public static LongVector apply(LongVector left, LongVector right) { return RuntimeTypes.requireLong(left, LongVector.SPECIES_256).sub(RuntimeTypes.requireLong(right, LongVector.SPECIES_256)); }
    }
    @Operation public static final class GeneratedInt64X4Times {
        @Specialization public static LongVector apply(LongVector left, LongVector right) { return RuntimeTypes.requireLong(left, LongVector.SPECIES_256).mul(RuntimeTypes.requireLong(right, LongVector.SPECIES_256)); }
    }
    @Operation public static final class GeneratedInt64X4Negate {
        @Specialization public static LongVector apply(LongVector value) { return RuntimeTypes.requireLong(value, LongVector.SPECIES_256).neg(); }
    }
    @Operation public static final class GeneratedInt64X4Insert {
        @Specialization public static LongVector apply(LongVector vector, long value, long index) { return RuntimeTypes.requireLong(vector, LongVector.SPECIES_256).withLane(RuntimeTypes.laneIndex(index, 4), value); }
    }
    @Operation public static final class GeneratedInt64X4Min {
        @Specialization public static LongVector apply(LongVector left, LongVector right) { return RuntimeTypes.requireLong(left, LongVector.SPECIES_256).min(RuntimeTypes.requireLong(right, LongVector.SPECIES_256)); }
    }
    @Operation public static final class GeneratedInt64X4Max {
        @Specialization public static LongVector apply(LongVector left, LongVector right) { return RuntimeTypes.requireLong(left, LongVector.SPECIES_256).max(RuntimeTypes.requireLong(right, LongVector.SPECIES_256)); }
    }
    @Operation public static final class GeneratedInt64X4Quot {
        @Specialization public static LongVector apply(LongVector left, LongVector right) { return VectorIntegerDivision.quotLong(RuntimeTypes.requireLong(left, LongVector.SPECIES_256), RuntimeTypes.requireLong(right, LongVector.SPECIES_256), false); }
    }
    @Operation public static final class GeneratedInt64X4Rem {
        @Specialization public static LongVector apply(LongVector left, LongVector right) { return VectorIntegerDivision.remLong(RuntimeTypes.requireLong(left, LongVector.SPECIES_256), RuntimeTypes.requireLong(right, LongVector.SPECIES_256), false); }
    }
    @Operation
    @ConstantOperand(type = VectorShuffle.class, name = "shuffle")
    public static final class GeneratedInt64X4Shuffle {
        @Specialization public static LongVector apply(VectorShuffle<?> shuffle, LongVector left, LongVector right) {
            return RuntimeTypes.requireLong(left, LongVector.SPECIES_256).rearrange(shuffle.check(LongVector.SPECIES_256), RuntimeTypes.requireLong(right, LongVector.SPECIES_256));
        }
    }
    @Operation public static final class GeneratedInt64X8Pack {
        @Specialization public static LongVector apply(long lane0, long lane1, long lane2, long lane3, long lane4, long lane5, long lane6, long lane7) { return LongVector.broadcast(LongVector.SPECIES_512, lane0).withLane(1, lane1).withLane(2, lane2).withLane(3, lane3).withLane(4, lane4).withLane(5, lane5).withLane(6, lane6).withLane(7, lane7); }
    }
    @Operation
    @ConstantOperand(type = LocalAccessor.class, name = "lane0")
    @ConstantOperand(type = LocalAccessor.class, name = "lane1")
    @ConstantOperand(type = LocalAccessor.class, name = "lane2")
    @ConstantOperand(type = LocalAccessor.class, name = "lane3")
    @ConstantOperand(type = LocalAccessor.class, name = "lane4")
    @ConstantOperand(type = LocalAccessor.class, name = "lane5")
    @ConstantOperand(type = LocalAccessor.class, name = "lane6")
    @ConstantOperand(type = LocalAccessor.class, name = "lane7")
    public static final class GeneratedInt64X8Unpack {
        @Specialization public static void apply(VirtualFrame frame, LocalAccessor lane0, LocalAccessor lane1, LocalAccessor lane2, LocalAccessor lane3, LocalAccessor lane4, LocalAccessor lane5, LocalAccessor lane6, LocalAccessor lane7, LongVector raw, @Bind("$node") Node node) {
            BytecodeNode bytecode = ((BytecodeRoot) node.getRootNode()).getBytecodeNode();
            LongVector value = RuntimeTypes.requireLong(raw, LongVector.SPECIES_512);
            lane0.setLong(bytecode, frame, value.lane(0));
            lane1.setLong(bytecode, frame, value.lane(1));
            lane2.setLong(bytecode, frame, value.lane(2));
            lane3.setLong(bytecode, frame, value.lane(3));
            lane4.setLong(bytecode, frame, value.lane(4));
            lane5.setLong(bytecode, frame, value.lane(5));
            lane6.setLong(bytecode, frame, value.lane(6));
            lane7.setLong(bytecode, frame, value.lane(7));
        }
    }
    @Operation public static final class GeneratedInt64X8Broadcast {
        @Specialization public static LongVector apply(long value) { return LongVector.broadcast(LongVector.SPECIES_512, value); }
    }
    @Operation public static final class GeneratedInt64X8Plus {
        @Specialization public static LongVector apply(LongVector left, LongVector right) { return RuntimeTypes.requireLong(left, LongVector.SPECIES_512).add(RuntimeTypes.requireLong(right, LongVector.SPECIES_512)); }
    }
    @Operation public static final class GeneratedInt64X8Minus {
        @Specialization public static LongVector apply(LongVector left, LongVector right) { return RuntimeTypes.requireLong(left, LongVector.SPECIES_512).sub(RuntimeTypes.requireLong(right, LongVector.SPECIES_512)); }
    }
    @Operation public static final class GeneratedInt64X8Times {
        @Specialization public static LongVector apply(LongVector left, LongVector right) { return RuntimeTypes.requireLong(left, LongVector.SPECIES_512).mul(RuntimeTypes.requireLong(right, LongVector.SPECIES_512)); }
    }
    @Operation public static final class GeneratedInt64X8Negate {
        @Specialization public static LongVector apply(LongVector value) { return RuntimeTypes.requireLong(value, LongVector.SPECIES_512).neg(); }
    }
    @Operation public static final class GeneratedInt64X8Insert {
        @Specialization public static LongVector apply(LongVector vector, long value, long index) { return RuntimeTypes.requireLong(vector, LongVector.SPECIES_512).withLane(RuntimeTypes.laneIndex(index, 8), value); }
    }
    @Operation public static final class GeneratedInt64X8Min {
        @Specialization public static LongVector apply(LongVector left, LongVector right) { return RuntimeTypes.requireLong(left, LongVector.SPECIES_512).min(RuntimeTypes.requireLong(right, LongVector.SPECIES_512)); }
    }
    @Operation public static final class GeneratedInt64X8Max {
        @Specialization public static LongVector apply(LongVector left, LongVector right) { return RuntimeTypes.requireLong(left, LongVector.SPECIES_512).max(RuntimeTypes.requireLong(right, LongVector.SPECIES_512)); }
    }
    @Operation public static final class GeneratedInt64X8Quot {
        @Specialization public static LongVector apply(LongVector left, LongVector right) { return VectorIntegerDivision.quotLong(RuntimeTypes.requireLong(left, LongVector.SPECIES_512), RuntimeTypes.requireLong(right, LongVector.SPECIES_512), false); }
    }
    @Operation public static final class GeneratedInt64X8Rem {
        @Specialization public static LongVector apply(LongVector left, LongVector right) { return VectorIntegerDivision.remLong(RuntimeTypes.requireLong(left, LongVector.SPECIES_512), RuntimeTypes.requireLong(right, LongVector.SPECIES_512), false); }
    }
    @Operation
    @ConstantOperand(type = VectorShuffle.class, name = "shuffle")
    public static final class GeneratedInt64X8Shuffle {
        @Specialization public static LongVector apply(VectorShuffle<?> shuffle, LongVector left, LongVector right) {
            return RuntimeTypes.requireLong(left, LongVector.SPECIES_512).rearrange(shuffle.check(LongVector.SPECIES_512), RuntimeTypes.requireLong(right, LongVector.SPECIES_512));
        }
    }
    @Operation public static final class GeneratedWord64X4Pack {
        @Specialization public static LongVector apply(long lane0, long lane1, long lane2, long lane3) { return LongVector.broadcast(LongVector.SPECIES_256, lane0).withLane(1, lane1).withLane(2, lane2).withLane(3, lane3); }
    }
    @Operation
    @ConstantOperand(type = LocalAccessor.class, name = "lane0")
    @ConstantOperand(type = LocalAccessor.class, name = "lane1")
    @ConstantOperand(type = LocalAccessor.class, name = "lane2")
    @ConstantOperand(type = LocalAccessor.class, name = "lane3")
    public static final class GeneratedWord64X4Unpack {
        @Specialization public static void apply(VirtualFrame frame, LocalAccessor lane0, LocalAccessor lane1, LocalAccessor lane2, LocalAccessor lane3, LongVector raw, @Bind("$node") Node node) {
            BytecodeNode bytecode = ((BytecodeRoot) node.getRootNode()).getBytecodeNode();
            LongVector value = RuntimeTypes.requireLong(raw, LongVector.SPECIES_256);
            lane0.setLong(bytecode, frame, value.lane(0));
            lane1.setLong(bytecode, frame, value.lane(1));
            lane2.setLong(bytecode, frame, value.lane(2));
            lane3.setLong(bytecode, frame, value.lane(3));
        }
    }
    @Operation public static final class GeneratedWord64X4Broadcast {
        @Specialization public static LongVector apply(long value) { return LongVector.broadcast(LongVector.SPECIES_256, value); }
    }
    @Operation public static final class GeneratedWord64X4Plus {
        @Specialization public static LongVector apply(LongVector left, LongVector right) { return RuntimeTypes.requireLong(left, LongVector.SPECIES_256).add(RuntimeTypes.requireLong(right, LongVector.SPECIES_256)); }
    }
    @Operation public static final class GeneratedWord64X4Minus {
        @Specialization public static LongVector apply(LongVector left, LongVector right) { return RuntimeTypes.requireLong(left, LongVector.SPECIES_256).sub(RuntimeTypes.requireLong(right, LongVector.SPECIES_256)); }
    }
    @Operation public static final class GeneratedWord64X4Times {
        @Specialization public static LongVector apply(LongVector left, LongVector right) { return RuntimeTypes.requireLong(left, LongVector.SPECIES_256).mul(RuntimeTypes.requireLong(right, LongVector.SPECIES_256)); }
    }
    @Operation public static final class GeneratedWord64X4Insert {
        @Specialization public static LongVector apply(LongVector vector, long value, long index) { return RuntimeTypes.requireLong(vector, LongVector.SPECIES_256).withLane(RuntimeTypes.laneIndex(index, 4), value); }
    }
    @Operation public static final class GeneratedWord64X4Min {
        @Specialization public static LongVector apply(LongVector left, LongVector right) { return RuntimeTypes.requireLong(left, LongVector.SPECIES_256).lanewise(VectorOperators.UMIN, RuntimeTypes.requireLong(right, LongVector.SPECIES_256)); }
    }
    @Operation public static final class GeneratedWord64X4Max {
        @Specialization public static LongVector apply(LongVector left, LongVector right) { return RuntimeTypes.requireLong(left, LongVector.SPECIES_256).lanewise(VectorOperators.UMAX, RuntimeTypes.requireLong(right, LongVector.SPECIES_256)); }
    }
    @Operation public static final class GeneratedWord64X4Quot {
        @Specialization public static LongVector apply(LongVector left, LongVector right) { return VectorIntegerDivision.quotLong(RuntimeTypes.requireLong(left, LongVector.SPECIES_256), RuntimeTypes.requireLong(right, LongVector.SPECIES_256), true); }
    }
    @Operation public static final class GeneratedWord64X4Rem {
        @Specialization public static LongVector apply(LongVector left, LongVector right) { return VectorIntegerDivision.remLong(RuntimeTypes.requireLong(left, LongVector.SPECIES_256), RuntimeTypes.requireLong(right, LongVector.SPECIES_256), true); }
    }
    @Operation
    @ConstantOperand(type = VectorShuffle.class, name = "shuffle")
    public static final class GeneratedWord64X4Shuffle {
        @Specialization public static LongVector apply(VectorShuffle<?> shuffle, LongVector left, LongVector right) {
            return RuntimeTypes.requireLong(left, LongVector.SPECIES_256).rearrange(shuffle.check(LongVector.SPECIES_256), RuntimeTypes.requireLong(right, LongVector.SPECIES_256));
        }
    }
    @Operation public static final class GeneratedWord64X8Pack {
        @Specialization public static LongVector apply(long lane0, long lane1, long lane2, long lane3, long lane4, long lane5, long lane6, long lane7) { return LongVector.broadcast(LongVector.SPECIES_512, lane0).withLane(1, lane1).withLane(2, lane2).withLane(3, lane3).withLane(4, lane4).withLane(5, lane5).withLane(6, lane6).withLane(7, lane7); }
    }
    @Operation
    @ConstantOperand(type = LocalAccessor.class, name = "lane0")
    @ConstantOperand(type = LocalAccessor.class, name = "lane1")
    @ConstantOperand(type = LocalAccessor.class, name = "lane2")
    @ConstantOperand(type = LocalAccessor.class, name = "lane3")
    @ConstantOperand(type = LocalAccessor.class, name = "lane4")
    @ConstantOperand(type = LocalAccessor.class, name = "lane5")
    @ConstantOperand(type = LocalAccessor.class, name = "lane6")
    @ConstantOperand(type = LocalAccessor.class, name = "lane7")
    public static final class GeneratedWord64X8Unpack {
        @Specialization public static void apply(VirtualFrame frame, LocalAccessor lane0, LocalAccessor lane1, LocalAccessor lane2, LocalAccessor lane3, LocalAccessor lane4, LocalAccessor lane5, LocalAccessor lane6, LocalAccessor lane7, LongVector raw, @Bind("$node") Node node) {
            BytecodeNode bytecode = ((BytecodeRoot) node.getRootNode()).getBytecodeNode();
            LongVector value = RuntimeTypes.requireLong(raw, LongVector.SPECIES_512);
            lane0.setLong(bytecode, frame, value.lane(0));
            lane1.setLong(bytecode, frame, value.lane(1));
            lane2.setLong(bytecode, frame, value.lane(2));
            lane3.setLong(bytecode, frame, value.lane(3));
            lane4.setLong(bytecode, frame, value.lane(4));
            lane5.setLong(bytecode, frame, value.lane(5));
            lane6.setLong(bytecode, frame, value.lane(6));
            lane7.setLong(bytecode, frame, value.lane(7));
        }
    }
    @Operation public static final class GeneratedWord64X8Broadcast {
        @Specialization public static LongVector apply(long value) { return LongVector.broadcast(LongVector.SPECIES_512, value); }
    }
    @Operation public static final class GeneratedWord64X8Plus {
        @Specialization public static LongVector apply(LongVector left, LongVector right) { return RuntimeTypes.requireLong(left, LongVector.SPECIES_512).add(RuntimeTypes.requireLong(right, LongVector.SPECIES_512)); }
    }
    @Operation public static final class GeneratedWord64X8Minus {
        @Specialization public static LongVector apply(LongVector left, LongVector right) { return RuntimeTypes.requireLong(left, LongVector.SPECIES_512).sub(RuntimeTypes.requireLong(right, LongVector.SPECIES_512)); }
    }
    @Operation public static final class GeneratedWord64X8Times {
        @Specialization public static LongVector apply(LongVector left, LongVector right) { return RuntimeTypes.requireLong(left, LongVector.SPECIES_512).mul(RuntimeTypes.requireLong(right, LongVector.SPECIES_512)); }
    }
    @Operation public static final class GeneratedWord64X8Insert {
        @Specialization public static LongVector apply(LongVector vector, long value, long index) { return RuntimeTypes.requireLong(vector, LongVector.SPECIES_512).withLane(RuntimeTypes.laneIndex(index, 8), value); }
    }
    @Operation public static final class GeneratedWord64X8Min {
        @Specialization public static LongVector apply(LongVector left, LongVector right) { return RuntimeTypes.requireLong(left, LongVector.SPECIES_512).lanewise(VectorOperators.UMIN, RuntimeTypes.requireLong(right, LongVector.SPECIES_512)); }
    }
    @Operation public static final class GeneratedWord64X8Max {
        @Specialization public static LongVector apply(LongVector left, LongVector right) { return RuntimeTypes.requireLong(left, LongVector.SPECIES_512).lanewise(VectorOperators.UMAX, RuntimeTypes.requireLong(right, LongVector.SPECIES_512)); }
    }
    @Operation public static final class GeneratedWord64X8Quot {
        @Specialization public static LongVector apply(LongVector left, LongVector right) { return VectorIntegerDivision.quotLong(RuntimeTypes.requireLong(left, LongVector.SPECIES_512), RuntimeTypes.requireLong(right, LongVector.SPECIES_512), true); }
    }
    @Operation public static final class GeneratedWord64X8Rem {
        @Specialization public static LongVector apply(LongVector left, LongVector right) { return VectorIntegerDivision.remLong(RuntimeTypes.requireLong(left, LongVector.SPECIES_512), RuntimeTypes.requireLong(right, LongVector.SPECIES_512), true); }
    }
    @Operation
    @ConstantOperand(type = VectorShuffle.class, name = "shuffle")
    public static final class GeneratedWord64X8Shuffle {
        @Specialization public static LongVector apply(VectorShuffle<?> shuffle, LongVector left, LongVector right) {
            return RuntimeTypes.requireLong(left, LongVector.SPECIES_512).rearrange(shuffle.check(LongVector.SPECIES_512), RuntimeTypes.requireLong(right, LongVector.SPECIES_512));
        }
    }
    @Operation
    @ConstantOperand(type = BytecodeVectorLanes.class, name = "lanes")
    public static final class GeneratedWord32X16Pack {
        @Specialization public static IntVector apply(VirtualFrame frame, BytecodeVectorLanes lanes, @Bind("$node") Node node) {
            BytecodeNode bytecode = ((BytecodeRoot) node.getRootNode()).getBytecodeNode();
            try {
                return IntVector.broadcast(IntVector.SPECIES_512, (int) lanes.getSlots()[0].getInt(bytecode, frame)).withLane(1, (int) lanes.getSlots()[1].getInt(bytecode, frame)).withLane(2, (int) lanes.getSlots()[2].getInt(bytecode, frame)).withLane(3, (int) lanes.getSlots()[3].getInt(bytecode, frame)).withLane(4, (int) lanes.getSlots()[4].getInt(bytecode, frame)).withLane(5, (int) lanes.getSlots()[5].getInt(bytecode, frame)).withLane(6, (int) lanes.getSlots()[6].getInt(bytecode, frame)).withLane(7, (int) lanes.getSlots()[7].getInt(bytecode, frame)).withLane(8, (int) lanes.getSlots()[8].getInt(bytecode, frame)).withLane(9, (int) lanes.getSlots()[9].getInt(bytecode, frame)).withLane(10, (int) lanes.getSlots()[10].getInt(bytecode, frame)).withLane(11, (int) lanes.getSlots()[11].getInt(bytecode, frame)).withLane(12, (int) lanes.getSlots()[12].getInt(bytecode, frame)).withLane(13, (int) lanes.getSlots()[13].getInt(bytecode, frame)).withLane(14, (int) lanes.getSlots()[14].getInt(bytecode, frame)).withLane(15, (int) lanes.getSlots()[15].getInt(bytecode, frame));
            } catch (com.oracle.truffle.api.nodes.UnexpectedResultException invalid) {
                throw new RuntimeFault("Expected primitive vector lane");
            }
        }
    }
    @Operation
    @ConstantOperand(type = BytecodeVectorLanes.class, name = "lanes")
    public static final class GeneratedWord32X16Unpack {
        @Specialization public static void apply(VirtualFrame frame, BytecodeVectorLanes lanes, IntVector raw, @Bind("$node") Node node) {
            BytecodeNode bytecode = ((BytecodeRoot) node.getRootNode()).getBytecodeNode();
            IntVector value = RuntimeTypes.requireInt(raw, IntVector.SPECIES_512);
            lanes.getSlots()[0].setInt(bytecode, frame, value.lane(0));
            lanes.getSlots()[1].setInt(bytecode, frame, value.lane(1));
            lanes.getSlots()[2].setInt(bytecode, frame, value.lane(2));
            lanes.getSlots()[3].setInt(bytecode, frame, value.lane(3));
            lanes.getSlots()[4].setInt(bytecode, frame, value.lane(4));
            lanes.getSlots()[5].setInt(bytecode, frame, value.lane(5));
            lanes.getSlots()[6].setInt(bytecode, frame, value.lane(6));
            lanes.getSlots()[7].setInt(bytecode, frame, value.lane(7));
            lanes.getSlots()[8].setInt(bytecode, frame, value.lane(8));
            lanes.getSlots()[9].setInt(bytecode, frame, value.lane(9));
            lanes.getSlots()[10].setInt(bytecode, frame, value.lane(10));
            lanes.getSlots()[11].setInt(bytecode, frame, value.lane(11));
            lanes.getSlots()[12].setInt(bytecode, frame, value.lane(12));
            lanes.getSlots()[13].setInt(bytecode, frame, value.lane(13));
            lanes.getSlots()[14].setInt(bytecode, frame, value.lane(14));
            lanes.getSlots()[15].setInt(bytecode, frame, value.lane(15));
        }
    }
    @Operation public static final class GeneratedWord32X16Broadcast {
        @Specialization public static IntVector apply(int value) { return IntVector.broadcast(IntVector.SPECIES_512, (int) value); }
    }
    @Operation public static final class GeneratedWord32X16Plus {
        @Specialization public static IntVector apply(IntVector left, IntVector right) { return RuntimeTypes.requireInt(left, IntVector.SPECIES_512).add(RuntimeTypes.requireInt(right, IntVector.SPECIES_512)); }
    }
    @Operation public static final class GeneratedWord32X16Minus {
        @Specialization public static IntVector apply(IntVector left, IntVector right) { return RuntimeTypes.requireInt(left, IntVector.SPECIES_512).sub(RuntimeTypes.requireInt(right, IntVector.SPECIES_512)); }
    }
    @Operation public static final class GeneratedWord32X16Times {
        @Specialization public static IntVector apply(IntVector left, IntVector right) { return RuntimeTypes.requireInt(left, IntVector.SPECIES_512).mul(RuntimeTypes.requireInt(right, IntVector.SPECIES_512)); }
    }
    @Operation public static final class GeneratedWord32X16Insert {
        @Specialization public static IntVector apply(IntVector vector, int value, long index) { return RuntimeTypes.requireInt(vector, IntVector.SPECIES_512).withLane(RuntimeTypes.laneIndex(index, 16), (int) value); }
    }
    @Operation public static final class GeneratedWord32X16Min {
        @Specialization public static IntVector apply(IntVector left, IntVector right) { return RuntimeTypes.requireInt(left, IntVector.SPECIES_512).lanewise(VectorOperators.UMIN, RuntimeTypes.requireInt(right, IntVector.SPECIES_512)); }
    }
    @Operation public static final class GeneratedWord32X16Max {
        @Specialization public static IntVector apply(IntVector left, IntVector right) { return RuntimeTypes.requireInt(left, IntVector.SPECIES_512).lanewise(VectorOperators.UMAX, RuntimeTypes.requireInt(right, IntVector.SPECIES_512)); }
    }
    @Operation public static final class GeneratedWord32X16Quot {
        @Specialization public static IntVector apply(IntVector left, IntVector right) { return VectorIntegerDivision.quotInt(RuntimeTypes.requireInt(left, IntVector.SPECIES_512), RuntimeTypes.requireInt(right, IntVector.SPECIES_512), true); }
    }
    @Operation public static final class GeneratedWord32X16Rem {
        @Specialization public static IntVector apply(IntVector left, IntVector right) { return VectorIntegerDivision.remInt(RuntimeTypes.requireInt(left, IntVector.SPECIES_512), RuntimeTypes.requireInt(right, IntVector.SPECIES_512), true); }
    }
    @Operation
    @ConstantOperand(type = VectorShuffle.class, name = "shuffle")
    public static final class GeneratedWord32X16Shuffle {
        @Specialization public static IntVector apply(VectorShuffle<?> shuffle, IntVector left, IntVector right) {
            return RuntimeTypes.requireInt(left, IntVector.SPECIES_512).rearrange(shuffle.check(IntVector.SPECIES_512), RuntimeTypes.requireInt(right, IntVector.SPECIES_512));
        }
    }
    @Operation
    @ConstantOperand(type = BytecodeVectorLanes.class, name = "lanes")
    public static final class GeneratedFloatX16Pack {
        @Specialization public static FloatVector apply(VirtualFrame frame, BytecodeVectorLanes lanes, @Bind("$node") Node node) {
            BytecodeNode bytecode = ((BytecodeRoot) node.getRootNode()).getBytecodeNode();
            try {
                return FloatVector.broadcast(FloatVector.SPECIES_512, lanes.getSlots()[0].getFloat(bytecode, frame)).withLane(1, lanes.getSlots()[1].getFloat(bytecode, frame)).withLane(2, lanes.getSlots()[2].getFloat(bytecode, frame)).withLane(3, lanes.getSlots()[3].getFloat(bytecode, frame)).withLane(4, lanes.getSlots()[4].getFloat(bytecode, frame)).withLane(5, lanes.getSlots()[5].getFloat(bytecode, frame)).withLane(6, lanes.getSlots()[6].getFloat(bytecode, frame)).withLane(7, lanes.getSlots()[7].getFloat(bytecode, frame)).withLane(8, lanes.getSlots()[8].getFloat(bytecode, frame)).withLane(9, lanes.getSlots()[9].getFloat(bytecode, frame)).withLane(10, lanes.getSlots()[10].getFloat(bytecode, frame)).withLane(11, lanes.getSlots()[11].getFloat(bytecode, frame)).withLane(12, lanes.getSlots()[12].getFloat(bytecode, frame)).withLane(13, lanes.getSlots()[13].getFloat(bytecode, frame)).withLane(14, lanes.getSlots()[14].getFloat(bytecode, frame)).withLane(15, lanes.getSlots()[15].getFloat(bytecode, frame));
            } catch (com.oracle.truffle.api.nodes.UnexpectedResultException invalid) {
                throw new RuntimeFault("Expected primitive vector lane");
            }
        }
    }
    @Operation
    @ConstantOperand(type = BytecodeVectorLanes.class, name = "lanes")
    public static final class GeneratedFloatX16Unpack {
        @Specialization public static void apply(VirtualFrame frame, BytecodeVectorLanes lanes, FloatVector raw, @Bind("$node") Node node) {
            BytecodeNode bytecode = ((BytecodeRoot) node.getRootNode()).getBytecodeNode();
            FloatVector value = RuntimeTypes.requireFloat(raw, FloatVector.SPECIES_512);
            lanes.getSlots()[0].setFloat(bytecode, frame, value.lane(0));
            lanes.getSlots()[1].setFloat(bytecode, frame, value.lane(1));
            lanes.getSlots()[2].setFloat(bytecode, frame, value.lane(2));
            lanes.getSlots()[3].setFloat(bytecode, frame, value.lane(3));
            lanes.getSlots()[4].setFloat(bytecode, frame, value.lane(4));
            lanes.getSlots()[5].setFloat(bytecode, frame, value.lane(5));
            lanes.getSlots()[6].setFloat(bytecode, frame, value.lane(6));
            lanes.getSlots()[7].setFloat(bytecode, frame, value.lane(7));
            lanes.getSlots()[8].setFloat(bytecode, frame, value.lane(8));
            lanes.getSlots()[9].setFloat(bytecode, frame, value.lane(9));
            lanes.getSlots()[10].setFloat(bytecode, frame, value.lane(10));
            lanes.getSlots()[11].setFloat(bytecode, frame, value.lane(11));
            lanes.getSlots()[12].setFloat(bytecode, frame, value.lane(12));
            lanes.getSlots()[13].setFloat(bytecode, frame, value.lane(13));
            lanes.getSlots()[14].setFloat(bytecode, frame, value.lane(14));
            lanes.getSlots()[15].setFloat(bytecode, frame, value.lane(15));
        }
    }
    @Operation public static final class GeneratedFloatX16Broadcast {
        @Specialization public static FloatVector apply(float value) { return FloatVector.broadcast(FloatVector.SPECIES_512, value); }
    }
    @Operation public static final class GeneratedFloatX16Plus {
        @Specialization public static FloatVector apply(FloatVector left, FloatVector right) { return RuntimeTypes.requireFloat(left, FloatVector.SPECIES_512).add(RuntimeTypes.requireFloat(right, FloatVector.SPECIES_512)); }
    }
    @Operation public static final class GeneratedFloatX16Minus {
        @Specialization public static FloatVector apply(FloatVector left, FloatVector right) { return RuntimeTypes.requireFloat(left, FloatVector.SPECIES_512).sub(RuntimeTypes.requireFloat(right, FloatVector.SPECIES_512)); }
    }
    @Operation public static final class GeneratedFloatX16Times {
        @Specialization public static FloatVector apply(FloatVector left, FloatVector right) { return RuntimeTypes.requireFloat(left, FloatVector.SPECIES_512).mul(RuntimeTypes.requireFloat(right, FloatVector.SPECIES_512)); }
    }
    @Operation public static final class GeneratedFloatX16Negate {
        @Specialization public static FloatVector apply(FloatVector value) { return RuntimeTypes.requireFloat(value, FloatVector.SPECIES_512).neg(); }
    }
    @Operation public static final class GeneratedFloatX16Divide {
        @Specialization public static FloatVector apply(FloatVector left, FloatVector right) { return RuntimeTypes.requireFloat(left, FloatVector.SPECIES_512).div(RuntimeTypes.requireFloat(right, FloatVector.SPECIES_512)); }
    }
    @Operation public static final class GeneratedFloatX16Insert {
        @Specialization public static FloatVector apply(FloatVector vector, float value, long index) { return RuntimeTypes.requireFloat(vector, FloatVector.SPECIES_512).withLane(RuntimeTypes.laneIndex(index, 16), value); }
    }
    @Operation public static final class GeneratedFloatX16Min {
        @Specialization public static FloatVector apply(FloatVector left, FloatVector right) { return RuntimeTypes.requireFloat(left, FloatVector.SPECIES_512).min(RuntimeTypes.requireFloat(right, FloatVector.SPECIES_512)); }
    }
    @Operation public static final class GeneratedFloatX16Max {
        @Specialization public static FloatVector apply(FloatVector left, FloatVector right) { return RuntimeTypes.requireFloat(left, FloatVector.SPECIES_512).max(RuntimeTypes.requireFloat(right, FloatVector.SPECIES_512)); }
    }
    @Operation
    @ConstantOperand(type = VectorShuffle.class, name = "shuffle")
    public static final class GeneratedFloatX16Shuffle {
        @Specialization public static FloatVector apply(VectorShuffle<?> shuffle, FloatVector left, FloatVector right) {
            return RuntimeTypes.requireFloat(left, FloatVector.SPECIES_512).rearrange(shuffle.check(FloatVector.SPECIES_512), RuntimeTypes.requireFloat(right, FloatVector.SPECIES_512));
        }
    }
    @Operation public static final class GeneratedDoubleX8Pack {
        @Specialization public static DoubleVector apply(double lane0, double lane1, double lane2, double lane3, double lane4, double lane5, double lane6, double lane7) { return DoubleVector.broadcast(DoubleVector.SPECIES_512, lane0).withLane(1, lane1).withLane(2, lane2).withLane(3, lane3).withLane(4, lane4).withLane(5, lane5).withLane(6, lane6).withLane(7, lane7); }
    }
    @Operation
    @ConstantOperand(type = LocalAccessor.class, name = "lane0")
    @ConstantOperand(type = LocalAccessor.class, name = "lane1")
    @ConstantOperand(type = LocalAccessor.class, name = "lane2")
    @ConstantOperand(type = LocalAccessor.class, name = "lane3")
    @ConstantOperand(type = LocalAccessor.class, name = "lane4")
    @ConstantOperand(type = LocalAccessor.class, name = "lane5")
    @ConstantOperand(type = LocalAccessor.class, name = "lane6")
    @ConstantOperand(type = LocalAccessor.class, name = "lane7")
    public static final class GeneratedDoubleX8Unpack {
        @Specialization public static void apply(VirtualFrame frame, LocalAccessor lane0, LocalAccessor lane1, LocalAccessor lane2, LocalAccessor lane3, LocalAccessor lane4, LocalAccessor lane5, LocalAccessor lane6, LocalAccessor lane7, DoubleVector raw, @Bind("$node") Node node) {
            BytecodeNode bytecode = ((BytecodeRoot) node.getRootNode()).getBytecodeNode();
            DoubleVector value = RuntimeTypes.requireDouble(raw, DoubleVector.SPECIES_512);
            lane0.setDouble(bytecode, frame, value.lane(0));
            lane1.setDouble(bytecode, frame, value.lane(1));
            lane2.setDouble(bytecode, frame, value.lane(2));
            lane3.setDouble(bytecode, frame, value.lane(3));
            lane4.setDouble(bytecode, frame, value.lane(4));
            lane5.setDouble(bytecode, frame, value.lane(5));
            lane6.setDouble(bytecode, frame, value.lane(6));
            lane7.setDouble(bytecode, frame, value.lane(7));
        }
    }
    @Operation public static final class GeneratedDoubleX8Broadcast {
        @Specialization public static DoubleVector apply(double value) { return DoubleVector.broadcast(DoubleVector.SPECIES_512, value); }
    }
    @Operation public static final class GeneratedDoubleX8Plus {
        @Specialization public static DoubleVector apply(DoubleVector left, DoubleVector right) { return RuntimeTypes.requireDouble(left, DoubleVector.SPECIES_512).add(RuntimeTypes.requireDouble(right, DoubleVector.SPECIES_512)); }
    }
    @Operation public static final class GeneratedDoubleX8Minus {
        @Specialization public static DoubleVector apply(DoubleVector left, DoubleVector right) { return RuntimeTypes.requireDouble(left, DoubleVector.SPECIES_512).sub(RuntimeTypes.requireDouble(right, DoubleVector.SPECIES_512)); }
    }
    @Operation public static final class GeneratedDoubleX8Times {
        @Specialization public static DoubleVector apply(DoubleVector left, DoubleVector right) { return RuntimeTypes.requireDouble(left, DoubleVector.SPECIES_512).mul(RuntimeTypes.requireDouble(right, DoubleVector.SPECIES_512)); }
    }
    @Operation public static final class GeneratedDoubleX8Negate {
        @Specialization public static DoubleVector apply(DoubleVector value) { return RuntimeTypes.requireDouble(value, DoubleVector.SPECIES_512).neg(); }
    }
    @Operation public static final class GeneratedDoubleX8Divide {
        @Specialization public static DoubleVector apply(DoubleVector left, DoubleVector right) { return RuntimeTypes.requireDouble(left, DoubleVector.SPECIES_512).div(RuntimeTypes.requireDouble(right, DoubleVector.SPECIES_512)); }
    }
    @Operation public static final class GeneratedDoubleX8Insert {
        @Specialization public static DoubleVector apply(DoubleVector vector, double value, long index) { return RuntimeTypes.requireDouble(vector, DoubleVector.SPECIES_512).withLane(RuntimeTypes.laneIndex(index, 8), value); }
    }
    @Operation public static final class GeneratedDoubleX8Min {
        @Specialization public static DoubleVector apply(DoubleVector left, DoubleVector right) { return RuntimeTypes.requireDouble(left, DoubleVector.SPECIES_512).min(RuntimeTypes.requireDouble(right, DoubleVector.SPECIES_512)); }
    }
    @Operation public static final class GeneratedDoubleX8Max {
        @Specialization public static DoubleVector apply(DoubleVector left, DoubleVector right) { return RuntimeTypes.requireDouble(left, DoubleVector.SPECIES_512).max(RuntimeTypes.requireDouble(right, DoubleVector.SPECIES_512)); }
    }
    @Operation
    @ConstantOperand(type = VectorShuffle.class, name = "shuffle")
    public static final class GeneratedDoubleX8Shuffle {
        @Specialization public static DoubleVector apply(VectorShuffle<?> shuffle, DoubleVector left, DoubleVector right) {
            return RuntimeTypes.requireDouble(left, DoubleVector.SPECIES_512).rearrange(shuffle.check(DoubleVector.SPECIES_512), RuntimeTypes.requireDouble(right, DoubleVector.SPECIES_512));
        }
    }
    @Operation public static final class GeneratedInt8X16Insert {
        @Specialization public static ByteVector apply(ByteVector vector, int value, long index) { return RuntimeTypes.requireByte(vector, ByteVector.SPECIES_128).withLane(RuntimeTypes.laneIndex(index, 16), (byte) value); }
    }
    @Operation public static final class GeneratedInt8X16Min {
        @Specialization public static ByteVector apply(ByteVector left, ByteVector right) { return RuntimeTypes.requireByte(left, ByteVector.SPECIES_128).min(RuntimeTypes.requireByte(right, ByteVector.SPECIES_128)); }
    }
    @Operation public static final class GeneratedInt8X16Max {
        @Specialization public static ByteVector apply(ByteVector left, ByteVector right) { return RuntimeTypes.requireByte(left, ByteVector.SPECIES_128).max(RuntimeTypes.requireByte(right, ByteVector.SPECIES_128)); }
    }
    @Operation public static final class GeneratedInt8X16Quot {
        @Specialization public static ByteVector apply(ByteVector left, ByteVector right) { return VectorIntegerDivision.quotByte(RuntimeTypes.requireByte(left, ByteVector.SPECIES_128), RuntimeTypes.requireByte(right, ByteVector.SPECIES_128), false); }
    }
    @Operation public static final class GeneratedInt8X16Rem {
        @Specialization public static ByteVector apply(ByteVector left, ByteVector right) { return VectorIntegerDivision.remByte(RuntimeTypes.requireByte(left, ByteVector.SPECIES_128), RuntimeTypes.requireByte(right, ByteVector.SPECIES_128), false); }
    }
    @Operation
    @ConstantOperand(type = VectorShuffle.class, name = "shuffle")
    public static final class GeneratedInt8X16Shuffle {
        @Specialization public static ByteVector apply(VectorShuffle<?> shuffle, ByteVector left, ByteVector right) {
            return RuntimeTypes.requireByte(left, ByteVector.SPECIES_128).rearrange(shuffle.check(ByteVector.SPECIES_128), RuntimeTypes.requireByte(right, ByteVector.SPECIES_128));
        }
    }
    @Operation public static final class GeneratedInt16X8Insert {
        @Specialization public static ShortVector apply(ShortVector vector, int value, long index) { return RuntimeTypes.requireShort(vector, ShortVector.SPECIES_128).withLane(RuntimeTypes.laneIndex(index, 8), (short) value); }
    }
    @Operation public static final class GeneratedInt16X8Min {
        @Specialization public static ShortVector apply(ShortVector left, ShortVector right) { return RuntimeTypes.requireShort(left, ShortVector.SPECIES_128).min(RuntimeTypes.requireShort(right, ShortVector.SPECIES_128)); }
    }
    @Operation public static final class GeneratedInt16X8Max {
        @Specialization public static ShortVector apply(ShortVector left, ShortVector right) { return RuntimeTypes.requireShort(left, ShortVector.SPECIES_128).max(RuntimeTypes.requireShort(right, ShortVector.SPECIES_128)); }
    }
    @Operation public static final class GeneratedInt16X8Quot {
        @Specialization public static ShortVector apply(ShortVector left, ShortVector right) { return VectorIntegerDivision.quotShort(RuntimeTypes.requireShort(left, ShortVector.SPECIES_128), RuntimeTypes.requireShort(right, ShortVector.SPECIES_128), false); }
    }
    @Operation public static final class GeneratedInt16X8Rem {
        @Specialization public static ShortVector apply(ShortVector left, ShortVector right) { return VectorIntegerDivision.remShort(RuntimeTypes.requireShort(left, ShortVector.SPECIES_128), RuntimeTypes.requireShort(right, ShortVector.SPECIES_128), false); }
    }
    @Operation
    @ConstantOperand(type = VectorShuffle.class, name = "shuffle")
    public static final class GeneratedInt16X8Shuffle {
        @Specialization public static ShortVector apply(VectorShuffle<?> shuffle, ShortVector left, ShortVector right) {
            return RuntimeTypes.requireShort(left, ShortVector.SPECIES_128).rearrange(shuffle.check(ShortVector.SPECIES_128), RuntimeTypes.requireShort(right, ShortVector.SPECIES_128));
        }
    }
    @Operation public static final class GeneratedInt32X4Insert {
        @Specialization public static IntVector apply(IntVector vector, int value, long index) { return RuntimeTypes.requireInt(vector, IntVector.SPECIES_128).withLane(RuntimeTypes.laneIndex(index, 4), (int) value); }
    }
    @Operation public static final class GeneratedInt32X4Min {
        @Specialization public static IntVector apply(IntVector left, IntVector right) { return RuntimeTypes.requireInt(left, IntVector.SPECIES_128).min(RuntimeTypes.requireInt(right, IntVector.SPECIES_128)); }
    }
    @Operation public static final class GeneratedInt32X4Max {
        @Specialization public static IntVector apply(IntVector left, IntVector right) { return RuntimeTypes.requireInt(left, IntVector.SPECIES_128).max(RuntimeTypes.requireInt(right, IntVector.SPECIES_128)); }
    }
    @Operation public static final class GeneratedInt32X4Quot {
        @Specialization public static IntVector apply(IntVector left, IntVector right) { return VectorIntegerDivision.quotInt(RuntimeTypes.requireInt(left, IntVector.SPECIES_128), RuntimeTypes.requireInt(right, IntVector.SPECIES_128), false); }
    }
    @Operation public static final class GeneratedInt32X4Rem {
        @Specialization public static IntVector apply(IntVector left, IntVector right) { return VectorIntegerDivision.remInt(RuntimeTypes.requireInt(left, IntVector.SPECIES_128), RuntimeTypes.requireInt(right, IntVector.SPECIES_128), false); }
    }
    @Operation
    @ConstantOperand(type = VectorShuffle.class, name = "shuffle")
    public static final class GeneratedInt32X4Shuffle {
        @Specialization public static IntVector apply(VectorShuffle<?> shuffle, IntVector left, IntVector right) {
            return RuntimeTypes.requireInt(left, IntVector.SPECIES_128).rearrange(shuffle.check(IntVector.SPECIES_128), RuntimeTypes.requireInt(right, IntVector.SPECIES_128));
        }
    }
    @Operation public static final class GeneratedWord8X16Insert {
        @Specialization public static ByteVector apply(ByteVector vector, int value, long index) { return RuntimeTypes.requireByte(vector, ByteVector.SPECIES_128).withLane(RuntimeTypes.laneIndex(index, 16), (byte) value); }
    }
    @Operation public static final class GeneratedWord8X16Min {
        @Specialization public static ByteVector apply(ByteVector left, ByteVector right) { return RuntimeTypes.requireByte(left, ByteVector.SPECIES_128).lanewise(VectorOperators.UMIN, RuntimeTypes.requireByte(right, ByteVector.SPECIES_128)); }
    }
    @Operation public static final class GeneratedWord8X16Max {
        @Specialization public static ByteVector apply(ByteVector left, ByteVector right) { return RuntimeTypes.requireByte(left, ByteVector.SPECIES_128).lanewise(VectorOperators.UMAX, RuntimeTypes.requireByte(right, ByteVector.SPECIES_128)); }
    }
    @Operation public static final class GeneratedWord8X16Quot {
        @Specialization public static ByteVector apply(ByteVector left, ByteVector right) { return VectorIntegerDivision.quotByte(RuntimeTypes.requireByte(left, ByteVector.SPECIES_128), RuntimeTypes.requireByte(right, ByteVector.SPECIES_128), true); }
    }
    @Operation public static final class GeneratedWord8X16Rem {
        @Specialization public static ByteVector apply(ByteVector left, ByteVector right) { return VectorIntegerDivision.remByte(RuntimeTypes.requireByte(left, ByteVector.SPECIES_128), RuntimeTypes.requireByte(right, ByteVector.SPECIES_128), true); }
    }
    @Operation
    @ConstantOperand(type = VectorShuffle.class, name = "shuffle")
    public static final class GeneratedWord8X16Shuffle {
        @Specialization public static ByteVector apply(VectorShuffle<?> shuffle, ByteVector left, ByteVector right) {
            return RuntimeTypes.requireByte(left, ByteVector.SPECIES_128).rearrange(shuffle.check(ByteVector.SPECIES_128), RuntimeTypes.requireByte(right, ByteVector.SPECIES_128));
        }
    }
    @Operation public static final class GeneratedWord16X8Insert {
        @Specialization public static ShortVector apply(ShortVector vector, int value, long index) { return RuntimeTypes.requireShort(vector, ShortVector.SPECIES_128).withLane(RuntimeTypes.laneIndex(index, 8), (short) value); }
    }
    @Operation public static final class GeneratedWord16X8Min {
        @Specialization public static ShortVector apply(ShortVector left, ShortVector right) { return RuntimeTypes.requireShort(left, ShortVector.SPECIES_128).lanewise(VectorOperators.UMIN, RuntimeTypes.requireShort(right, ShortVector.SPECIES_128)); }
    }
    @Operation public static final class GeneratedWord16X8Max {
        @Specialization public static ShortVector apply(ShortVector left, ShortVector right) { return RuntimeTypes.requireShort(left, ShortVector.SPECIES_128).lanewise(VectorOperators.UMAX, RuntimeTypes.requireShort(right, ShortVector.SPECIES_128)); }
    }
    @Operation public static final class GeneratedWord16X8Quot {
        @Specialization public static ShortVector apply(ShortVector left, ShortVector right) { return VectorIntegerDivision.quotShort(RuntimeTypes.requireShort(left, ShortVector.SPECIES_128), RuntimeTypes.requireShort(right, ShortVector.SPECIES_128), true); }
    }
    @Operation public static final class GeneratedWord16X8Rem {
        @Specialization public static ShortVector apply(ShortVector left, ShortVector right) { return VectorIntegerDivision.remShort(RuntimeTypes.requireShort(left, ShortVector.SPECIES_128), RuntimeTypes.requireShort(right, ShortVector.SPECIES_128), true); }
    }
    @Operation
    @ConstantOperand(type = VectorShuffle.class, name = "shuffle")
    public static final class GeneratedWord16X8Shuffle {
        @Specialization public static ShortVector apply(VectorShuffle<?> shuffle, ShortVector left, ShortVector right) {
            return RuntimeTypes.requireShort(left, ShortVector.SPECIES_128).rearrange(shuffle.check(ShortVector.SPECIES_128), RuntimeTypes.requireShort(right, ShortVector.SPECIES_128));
        }
    }
    @Operation public static final class GeneratedWord32X4Insert {
        @Specialization public static IntVector apply(IntVector vector, int value, long index) { return RuntimeTypes.requireInt(vector, IntVector.SPECIES_128).withLane(RuntimeTypes.laneIndex(index, 4), (int) value); }
    }
    @Operation public static final class GeneratedWord32X4Min {
        @Specialization public static IntVector apply(IntVector left, IntVector right) { return RuntimeTypes.requireInt(left, IntVector.SPECIES_128).lanewise(VectorOperators.UMIN, RuntimeTypes.requireInt(right, IntVector.SPECIES_128)); }
    }
    @Operation public static final class GeneratedWord32X4Max {
        @Specialization public static IntVector apply(IntVector left, IntVector right) { return RuntimeTypes.requireInt(left, IntVector.SPECIES_128).lanewise(VectorOperators.UMAX, RuntimeTypes.requireInt(right, IntVector.SPECIES_128)); }
    }
    @Operation public static final class GeneratedWord32X4Quot {
        @Specialization public static IntVector apply(IntVector left, IntVector right) { return VectorIntegerDivision.quotInt(RuntimeTypes.requireInt(left, IntVector.SPECIES_128), RuntimeTypes.requireInt(right, IntVector.SPECIES_128), true); }
    }
    @Operation public static final class GeneratedWord32X4Rem {
        @Specialization public static IntVector apply(IntVector left, IntVector right) { return VectorIntegerDivision.remInt(RuntimeTypes.requireInt(left, IntVector.SPECIES_128), RuntimeTypes.requireInt(right, IntVector.SPECIES_128), true); }
    }
    @Operation
    @ConstantOperand(type = VectorShuffle.class, name = "shuffle")
    public static final class GeneratedWord32X4Shuffle {
        @Specialization public static IntVector apply(VectorShuffle<?> shuffle, IntVector left, IntVector right) {
            return RuntimeTypes.requireInt(left, IntVector.SPECIES_128).rearrange(shuffle.check(IntVector.SPECIES_128), RuntimeTypes.requireInt(right, IntVector.SPECIES_128));
        }
    }
    @Operation
    @ConstantOperand(type = BytecodeVectorLanes.class, name = "lanes")
    public static final class GeneratedInt16X16Pack {
        @Specialization public static ShortVector apply(VirtualFrame frame, BytecodeVectorLanes lanes, @Bind("$node") Node node) {
            BytecodeNode bytecode = ((BytecodeRoot) node.getRootNode()).getBytecodeNode();
            try {
                return ShortVector.broadcast(ShortVector.SPECIES_256, (short) lanes.getSlots()[0].getInt(bytecode, frame)).withLane(1, (short) lanes.getSlots()[1].getInt(bytecode, frame)).withLane(2, (short) lanes.getSlots()[2].getInt(bytecode, frame)).withLane(3, (short) lanes.getSlots()[3].getInt(bytecode, frame)).withLane(4, (short) lanes.getSlots()[4].getInt(bytecode, frame)).withLane(5, (short) lanes.getSlots()[5].getInt(bytecode, frame)).withLane(6, (short) lanes.getSlots()[6].getInt(bytecode, frame)).withLane(7, (short) lanes.getSlots()[7].getInt(bytecode, frame)).withLane(8, (short) lanes.getSlots()[8].getInt(bytecode, frame)).withLane(9, (short) lanes.getSlots()[9].getInt(bytecode, frame)).withLane(10, (short) lanes.getSlots()[10].getInt(bytecode, frame)).withLane(11, (short) lanes.getSlots()[11].getInt(bytecode, frame)).withLane(12, (short) lanes.getSlots()[12].getInt(bytecode, frame)).withLane(13, (short) lanes.getSlots()[13].getInt(bytecode, frame)).withLane(14, (short) lanes.getSlots()[14].getInt(bytecode, frame)).withLane(15, (short) lanes.getSlots()[15].getInt(bytecode, frame));
            } catch (com.oracle.truffle.api.nodes.UnexpectedResultException invalid) {
                throw new RuntimeFault("Expected primitive vector lane");
            }
        }
    }
    @Operation
    @ConstantOperand(type = BytecodeVectorLanes.class, name = "lanes")
    public static final class GeneratedInt16X16Unpack {
        @Specialization public static void apply(VirtualFrame frame, BytecodeVectorLanes lanes, ShortVector raw, @Bind("$node") Node node) {
            BytecodeNode bytecode = ((BytecodeRoot) node.getRootNode()).getBytecodeNode();
            ShortVector value = RuntimeTypes.requireShort(raw, ShortVector.SPECIES_256);
            lanes.getSlots()[0].setInt(bytecode, frame, value.lane(0));
            lanes.getSlots()[1].setInt(bytecode, frame, value.lane(1));
            lanes.getSlots()[2].setInt(bytecode, frame, value.lane(2));
            lanes.getSlots()[3].setInt(bytecode, frame, value.lane(3));
            lanes.getSlots()[4].setInt(bytecode, frame, value.lane(4));
            lanes.getSlots()[5].setInt(bytecode, frame, value.lane(5));
            lanes.getSlots()[6].setInt(bytecode, frame, value.lane(6));
            lanes.getSlots()[7].setInt(bytecode, frame, value.lane(7));
            lanes.getSlots()[8].setInt(bytecode, frame, value.lane(8));
            lanes.getSlots()[9].setInt(bytecode, frame, value.lane(9));
            lanes.getSlots()[10].setInt(bytecode, frame, value.lane(10));
            lanes.getSlots()[11].setInt(bytecode, frame, value.lane(11));
            lanes.getSlots()[12].setInt(bytecode, frame, value.lane(12));
            lanes.getSlots()[13].setInt(bytecode, frame, value.lane(13));
            lanes.getSlots()[14].setInt(bytecode, frame, value.lane(14));
            lanes.getSlots()[15].setInt(bytecode, frame, value.lane(15));
        }
    }
    @Operation public static final class GeneratedInt16X16Broadcast {
        @Specialization public static ShortVector apply(int value) { return ShortVector.broadcast(ShortVector.SPECIES_256, (short) value); }
    }
    @Operation public static final class GeneratedInt16X16Plus {
        @Specialization public static ShortVector apply(ShortVector left, ShortVector right) { return RuntimeTypes.requireShort(left, ShortVector.SPECIES_256).add(RuntimeTypes.requireShort(right, ShortVector.SPECIES_256)); }
    }
    @Operation public static final class GeneratedInt16X16Minus {
        @Specialization public static ShortVector apply(ShortVector left, ShortVector right) { return RuntimeTypes.requireShort(left, ShortVector.SPECIES_256).sub(RuntimeTypes.requireShort(right, ShortVector.SPECIES_256)); }
    }
    @Operation public static final class GeneratedInt16X16Times {
        @Specialization public static ShortVector apply(ShortVector left, ShortVector right) { return RuntimeTypes.requireShort(left, ShortVector.SPECIES_256).mul(RuntimeTypes.requireShort(right, ShortVector.SPECIES_256)); }
    }
    @Operation public static final class GeneratedInt16X16Negate {
        @Specialization public static ShortVector apply(ShortVector value) { return RuntimeTypes.requireShort(value, ShortVector.SPECIES_256).neg(); }
    }
    @Operation public static final class GeneratedInt16X16Insert {
        @Specialization public static ShortVector apply(ShortVector vector, int value, long index) { return RuntimeTypes.requireShort(vector, ShortVector.SPECIES_256).withLane(RuntimeTypes.laneIndex(index, 16), (short) value); }
    }
    @Operation public static final class GeneratedInt16X16Min {
        @Specialization public static ShortVector apply(ShortVector left, ShortVector right) { return RuntimeTypes.requireShort(left, ShortVector.SPECIES_256).min(RuntimeTypes.requireShort(right, ShortVector.SPECIES_256)); }
    }
    @Operation public static final class GeneratedInt16X16Max {
        @Specialization public static ShortVector apply(ShortVector left, ShortVector right) { return RuntimeTypes.requireShort(left, ShortVector.SPECIES_256).max(RuntimeTypes.requireShort(right, ShortVector.SPECIES_256)); }
    }
    @Operation public static final class GeneratedInt16X16Quot {
        @Specialization public static ShortVector apply(ShortVector left, ShortVector right) { return VectorIntegerDivision.quotShort(RuntimeTypes.requireShort(left, ShortVector.SPECIES_256), RuntimeTypes.requireShort(right, ShortVector.SPECIES_256), false); }
    }
    @Operation public static final class GeneratedInt16X16Rem {
        @Specialization public static ShortVector apply(ShortVector left, ShortVector right) { return VectorIntegerDivision.remShort(RuntimeTypes.requireShort(left, ShortVector.SPECIES_256), RuntimeTypes.requireShort(right, ShortVector.SPECIES_256), false); }
    }
    @Operation
    @ConstantOperand(type = VectorShuffle.class, name = "shuffle")
    public static final class GeneratedInt16X16Shuffle {
        @Specialization public static ShortVector apply(VectorShuffle<?> shuffle, ShortVector left, ShortVector right) {
            return RuntimeTypes.requireShort(left, ShortVector.SPECIES_256).rearrange(shuffle.check(ShortVector.SPECIES_256), RuntimeTypes.requireShort(right, ShortVector.SPECIES_256));
        }
    }
    @Operation
    @ConstantOperand(type = BytecodeVectorLanes.class, name = "lanes")
    public static final class GeneratedWord16X16Pack {
        @Specialization public static ShortVector apply(VirtualFrame frame, BytecodeVectorLanes lanes, @Bind("$node") Node node) {
            BytecodeNode bytecode = ((BytecodeRoot) node.getRootNode()).getBytecodeNode();
            try {
                return ShortVector.broadcast(ShortVector.SPECIES_256, (short) lanes.getSlots()[0].getInt(bytecode, frame)).withLane(1, (short) lanes.getSlots()[1].getInt(bytecode, frame)).withLane(2, (short) lanes.getSlots()[2].getInt(bytecode, frame)).withLane(3, (short) lanes.getSlots()[3].getInt(bytecode, frame)).withLane(4, (short) lanes.getSlots()[4].getInt(bytecode, frame)).withLane(5, (short) lanes.getSlots()[5].getInt(bytecode, frame)).withLane(6, (short) lanes.getSlots()[6].getInt(bytecode, frame)).withLane(7, (short) lanes.getSlots()[7].getInt(bytecode, frame)).withLane(8, (short) lanes.getSlots()[8].getInt(bytecode, frame)).withLane(9, (short) lanes.getSlots()[9].getInt(bytecode, frame)).withLane(10, (short) lanes.getSlots()[10].getInt(bytecode, frame)).withLane(11, (short) lanes.getSlots()[11].getInt(bytecode, frame)).withLane(12, (short) lanes.getSlots()[12].getInt(bytecode, frame)).withLane(13, (short) lanes.getSlots()[13].getInt(bytecode, frame)).withLane(14, (short) lanes.getSlots()[14].getInt(bytecode, frame)).withLane(15, (short) lanes.getSlots()[15].getInt(bytecode, frame));
            } catch (com.oracle.truffle.api.nodes.UnexpectedResultException invalid) {
                throw new RuntimeFault("Expected primitive vector lane");
            }
        }
    }
    @Operation
    @ConstantOperand(type = BytecodeVectorLanes.class, name = "lanes")
    public static final class GeneratedWord16X16Unpack {
        @Specialization public static void apply(VirtualFrame frame, BytecodeVectorLanes lanes, ShortVector raw, @Bind("$node") Node node) {
            BytecodeNode bytecode = ((BytecodeRoot) node.getRootNode()).getBytecodeNode();
            ShortVector value = RuntimeTypes.requireShort(raw, ShortVector.SPECIES_256);
            lanes.getSlots()[0].setInt(bytecode, frame, value.lane(0) & 0xffff);
            lanes.getSlots()[1].setInt(bytecode, frame, value.lane(1) & 0xffff);
            lanes.getSlots()[2].setInt(bytecode, frame, value.lane(2) & 0xffff);
            lanes.getSlots()[3].setInt(bytecode, frame, value.lane(3) & 0xffff);
            lanes.getSlots()[4].setInt(bytecode, frame, value.lane(4) & 0xffff);
            lanes.getSlots()[5].setInt(bytecode, frame, value.lane(5) & 0xffff);
            lanes.getSlots()[6].setInt(bytecode, frame, value.lane(6) & 0xffff);
            lanes.getSlots()[7].setInt(bytecode, frame, value.lane(7) & 0xffff);
            lanes.getSlots()[8].setInt(bytecode, frame, value.lane(8) & 0xffff);
            lanes.getSlots()[9].setInt(bytecode, frame, value.lane(9) & 0xffff);
            lanes.getSlots()[10].setInt(bytecode, frame, value.lane(10) & 0xffff);
            lanes.getSlots()[11].setInt(bytecode, frame, value.lane(11) & 0xffff);
            lanes.getSlots()[12].setInt(bytecode, frame, value.lane(12) & 0xffff);
            lanes.getSlots()[13].setInt(bytecode, frame, value.lane(13) & 0xffff);
            lanes.getSlots()[14].setInt(bytecode, frame, value.lane(14) & 0xffff);
            lanes.getSlots()[15].setInt(bytecode, frame, value.lane(15) & 0xffff);
        }
    }
    @Operation public static final class GeneratedWord16X16Broadcast {
        @Specialization public static ShortVector apply(int value) { return ShortVector.broadcast(ShortVector.SPECIES_256, (short) value); }
    }
    @Operation public static final class GeneratedWord16X16Plus {
        @Specialization public static ShortVector apply(ShortVector left, ShortVector right) { return RuntimeTypes.requireShort(left, ShortVector.SPECIES_256).add(RuntimeTypes.requireShort(right, ShortVector.SPECIES_256)); }
    }
    @Operation public static final class GeneratedWord16X16Minus {
        @Specialization public static ShortVector apply(ShortVector left, ShortVector right) { return RuntimeTypes.requireShort(left, ShortVector.SPECIES_256).sub(RuntimeTypes.requireShort(right, ShortVector.SPECIES_256)); }
    }
    @Operation public static final class GeneratedWord16X16Times {
        @Specialization public static ShortVector apply(ShortVector left, ShortVector right) { return RuntimeTypes.requireShort(left, ShortVector.SPECIES_256).mul(RuntimeTypes.requireShort(right, ShortVector.SPECIES_256)); }
    }
    @Operation public static final class GeneratedWord16X16Insert {
        @Specialization public static ShortVector apply(ShortVector vector, int value, long index) { return RuntimeTypes.requireShort(vector, ShortVector.SPECIES_256).withLane(RuntimeTypes.laneIndex(index, 16), (short) value); }
    }
    @Operation public static final class GeneratedWord16X16Min {
        @Specialization public static ShortVector apply(ShortVector left, ShortVector right) { return RuntimeTypes.requireShort(left, ShortVector.SPECIES_256).lanewise(VectorOperators.UMIN, RuntimeTypes.requireShort(right, ShortVector.SPECIES_256)); }
    }
    @Operation public static final class GeneratedWord16X16Max {
        @Specialization public static ShortVector apply(ShortVector left, ShortVector right) { return RuntimeTypes.requireShort(left, ShortVector.SPECIES_256).lanewise(VectorOperators.UMAX, RuntimeTypes.requireShort(right, ShortVector.SPECIES_256)); }
    }
    @Operation public static final class GeneratedWord16X16Quot {
        @Specialization public static ShortVector apply(ShortVector left, ShortVector right) { return VectorIntegerDivision.quotShort(RuntimeTypes.requireShort(left, ShortVector.SPECIES_256), RuntimeTypes.requireShort(right, ShortVector.SPECIES_256), true); }
    }
    @Operation public static final class GeneratedWord16X16Rem {
        @Specialization public static ShortVector apply(ShortVector left, ShortVector right) { return VectorIntegerDivision.remShort(RuntimeTypes.requireShort(left, ShortVector.SPECIES_256), RuntimeTypes.requireShort(right, ShortVector.SPECIES_256), true); }
    }
    @Operation
    @ConstantOperand(type = VectorShuffle.class, name = "shuffle")
    public static final class GeneratedWord16X16Shuffle {
        @Specialization public static ShortVector apply(VectorShuffle<?> shuffle, ShortVector left, ShortVector right) {
            return RuntimeTypes.requireShort(left, ShortVector.SPECIES_256).rearrange(shuffle.check(ShortVector.SPECIES_256), RuntimeTypes.requireShort(right, ShortVector.SPECIES_256));
        }
    }
    // END GENERATED SIMD FAMILIES
}
