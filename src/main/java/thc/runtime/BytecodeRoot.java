package thc.runtime;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.bytecode.BytecodeRootNode;
import com.oracle.truffle.api.bytecode.BytecodeNode;
import com.oracle.truffle.api.bytecode.LocalAccessor;
import com.oracle.truffle.api.bytecode.ConstantOperand;
import com.oracle.truffle.api.bytecode.GenerateBytecode;
import com.oracle.truffle.api.bytecode.Operation;
import com.oracle.truffle.api.bytecode.Variadic;
import com.oracle.truffle.api.dsl.Bind;
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.Fallback;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.Node;
import thc.Language;

/** Concrete Core instructions sharing the AST backend's heap and application ABI. */
// An explicit compile request must work after the first ordinary invocation, even
// when Core proofs eliminate every operation that otherwise forces the cached tier.
@GenerateBytecode(languageClass = Language.class, enableUncachedInterpreter = true,
        defaultUncachedThreshold = "0", boxingEliminationTypes = {long.class, float.class, double.class, boolean.class})
public abstract class BytecodeRoot extends GuestRoot implements BytecodeRootNode {
    private String label = "bytecode";

    protected BytecodeRoot(Language language, FrameDescriptor descriptor) {
        super(language, descriptor);
    }

    public final void setLabel(String label) { this.label = label; }
    @Override public final String getName() { return label; }
    @Override public final String toString() { return label; }
    @Override public final long bloom(VirtualFrame frame) {
        // Self backedges restore DSL locals, leaving the incoming ancestry intact.
        return (long) frame.getArguments()[0] | mask;
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
            this.closed = captureLayout == null ? new Closure(null, ApplicationKt.getNO_PAP_ARGUMENTS(), arity, target) : null;
        }
    }

    @Operation
    @ConstantOperand(type = Metrics.class, name = "metrics")
    public static final class EnterRoot {
        @Specialization public static void enter(Metrics metrics) {
            if (metrics.getEnabled() && CompilerDirectives.inCompiledCode()) {
                metrics.setCompiledEntries(metrics.getCompiledEntries() + 1);
            }
        }
    }

    @Operation
    @ConstantOperand(type = Metrics.class, name = "metrics")
    public static final class JoinTransfer {
        @Specialization public static void record(Metrics metrics) {
            if (metrics.getEnabled()) metrics.setLocalJoinTransfers(metrics.getLocalJoinTransfers() + 1);
        }
    }

    @Operation
    @ConstantOperand(type = GlobalBinding.class, name = "binding")
    public static final class ReadGlobal {
        @Specialization public static Object read(GlobalBinding binding) { return binding.read(); }
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
            return new Closure(template.captureLayout.captureValues(values), ApplicationKt.getNO_PAP_ARGUMENTS(), template.arity, template.target);
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

    @Operation
    @ConstantOperand(type = CaptureLayout.class, name = "layout")
    @ConstantOperand(type = int.class, name = "index")
    public static final class CaptureRead {
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

    @Operation(forceCached = true)
    @ConstantOperand(type = Metrics.class, name = "metrics")
    public static final class ForceValue {
        @Specialization public static float floating(Metrics metrics, float value) { return value; }
        @Specialization public static double doubleValue(Metrics metrics, double value) { return value; }
        @Specialization public static long number(Metrics metrics, long value) { return value; }
        @Specialization public static boolean bool(Metrics metrics, boolean value) { return value; }
        @Specialization(replaces = {"number", "bool", "floating", "doubleValue"})
        public static Object force(VirtualFrame frame, Metrics metrics, Object value,
                @Cached(value = "createForce(metrics)", neverDefault = true) Force force) {
            return force.execute(frame, value);
        }
        public static Force createForce(Metrics metrics) { return new Force(metrics); }
    }

    /** A successful force updates this activation's mutable binding, not its final capture property. */
    @Operation(forceCached = true)
    @ConstantOperand(type = Metrics.class, name = "metrics")
    @ConstantOperand(type = LocalAccessor.class, name = "local")
    @ConstantOperand(type = boolean.class, name = "cell")
    public static final class ForceLocal {
        @Specialization public static float floating(Metrics metrics, LocalAccessor local, boolean cell, float value) { return value; }
        @Specialization public static double doubleValue(Metrics metrics, LocalAccessor local, boolean cell, double value) { return value; }
        @Specialization public static long number(Metrics metrics, LocalAccessor local, boolean cell, long value) { return value; }
        @Specialization public static boolean bool(Metrics metrics, LocalAccessor local, boolean cell, boolean value) { return value; }
        @Specialization(replaces = {"number", "bool", "floating", "doubleValue"})
        public static Object force(VirtualFrame frame, Metrics metrics, LocalAccessor local, boolean cell, Object binding,
                @Bind("$node") Node node,
                @Cached(value = "createForce(metrics)", neverDefault = true) Force force) {
            // The compiler knows whether this lexical binding retains a recursive
            // cell. Ordinary formals, fields and published values need no cell test.
            Object original = cell ? ReadCellIfNeeded.read(binding) : binding;
            Object result = force.execute(frame, original);
            if (original instanceof Thunk thunk) {
                if (cell) {
                    ProgramKt.updateForcedCell((RecCell) binding, thunk, result);
                } else {
                    BytecodeNode bytecode = ((BytecodeRoot) node.getRootNode()).getBytecodeNode();
                    if (local.getObject(bytecode, frame) == thunk) {
                        if (result instanceof Long number) local.setLong(bytecode, frame, number);
                        else if (result instanceof Float floating) local.setFloat(bytecode, frame, floating);
                        else if (result instanceof Double doubleValue) local.setDouble(bytecode, frame, doubleValue);
                        else if (result instanceof Boolean bool) local.setBoolean(bytecode, frame, bool);
                        else local.setObject(bytecode, frame, result);
                    }
                }
            }
            return result;
        }
        public static Force createForce(Metrics metrics) { return new Force(metrics); }
    }

    @Operation
    public static final class CheckSumTag {
        @Specialization public static long execute(long value) { return SumShape.INSTANCE.checkedTag(value); }
    }

    @Operation
    @ConstantOperand(type = EnumFamily.class, name = "family")
    public static final class TagToEnum {
        @Specialization public static DataValue select(EnumFamily family, long tag) { return family.select(tag); }
    }

    /** Saturated tuple arithmetic never constructs a result carrier or payload array. */
    @Operation
    @ConstantOperand(type = TupleArithmeticOp.class, name = "operation")
    @ConstantOperand(type = LocalAccessor.class, name = "first")
    @ConstantOperand(type = LocalAccessor.class, name = "second")
    public static final class TupleArithmetic {
        @Specialization public static void execute(VirtualFrame frame, TupleArithmeticOp operation,
                LocalAccessor first, LocalAccessor second, long left, long right, @Bind("$node") Node node) {
            long a = operation.first(left, right);
            long b = operation.second(left, right);
            BytecodeNode bytecode = ((BytecodeRoot) node.getRootNode()).getBytecodeNode();
            first.setLong(bytecode, frame, a);
            second.setLong(bytecode, frame, b);
        }
    }

    /** Tuple operands are scalar inputs; each dispatch arm consumes into typed locals. */
    @Operation(forceCached = true)
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

    @Operation(forceCached = true)
    @ConstantOperand(type = BytecodeTupleSlots.class, name = "destination")
    @ConstantOperand(type = int.class, name = "arity")
    @ConstantOperand(type = Metrics.class, name = "metrics")
    public static final class TailApplyTuple {
        @Specialization public static Object apply(VirtualFrame frame, BytecodeTupleSlots destination, int arity, Metrics metrics,
                Closure function, @Variadic Object[] arguments, @Bind("$node") Node node,
                @Cached(value = "create(destination, arity, metrics)", neverDefault = true) TupleDispatch dispatch) {
            try {
                dispatch.execute(frame, function, arguments);
                return null;
            } catch (TailCall transfer) {
                if (!((GuestRoot) node.getRootNode()).isSelf(transfer.getTarget())) throw transfer;
                if (metrics.getEnabled()) metrics.setSelfTailReentries(metrics.getSelfTailReentries() + 1);
                return transfer;
            }
        }
        public static TupleDispatch create(BytecodeTupleSlots destination, int arity, Metrics metrics) {
            return new TupleDispatch(destination, metrics, arity, true);
        }
    }

    @Operation(forceCached = true)
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

    @Operation(forceCached = true)
    @ConstantOperand(type = BytecodeTupleSlots.class, name = "destination")
    @ConstantOperand(type = ArgumentLayout.class, name = "layout")
    @ConstantOperand(type = Metrics.class, name = "metrics")
    public static final class TailApplyCompactTuple {
        @Specialization public static Object apply(VirtualFrame frame, BytecodeTupleSlots destination, ArgumentLayout layout, Metrics metrics,
                Closure function, @Variadic Object[] arguments, @Bind("$node") Node node,
                @Cached(value = "create(destination, layout, metrics)", neverDefault = true) TupleDispatch dispatch) {
            try {
                dispatch.execute(frame, function, arguments);
                return null;
            } catch (TailCall transfer) {
                if (!((GuestRoot) node.getRootNode()).isSelf(transfer.getTarget())) throw transfer;
                if (metrics.getEnabled()) metrics.setSelfTailReentries(metrics.getSelfTailReentries() + 1);
                return transfer;
            }
        }
        public static TupleDispatch create(BytecodeTupleSlots destination, ArgumentLayout layout, Metrics metrics) {
            return new TupleDispatch(destination, metrics, layout.getLogicalArity(), true, layout);
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
        @Specialization public static LiteralAddress require(Object value) {
            if (value instanceof LiteralAddress address) return address;
            throw fail("Expected a managed literal Addr#");
        }
    }

    @Operation(forceCached = true)
    @ConstantOperand(type = int.class, name = "arity")
    @ConstantOperand(type = boolean.class, name = "tail")
    @ConstantOperand(type = Metrics.class, name = "metrics")
    @ConstantOperand(type = boolean[].class, name = "evaluatedArguments")
    public static final class Apply {
        @Specialization public static Object apply(VirtualFrame frame, int arity, boolean tail,
                Metrics metrics, boolean[] evaluatedArguments, Closure function, @Variadic Object[] arguments,
                @Bind("$node") Node node,
                @Cached(value = "createDispatch(arity, tail, metrics, evaluatedArguments)", neverDefault = true) Dispatch dispatch) {
            try {
                return dispatch.execute(frame, function, arguments);
            } catch (TailCall call) {
                // A -> B -> ... -> A unwinds to the owning activation. The compiler
                // consumes this internal result and restores locals before a real
                // bytecode backedge. Never intercept a call with pending non-tail work.
                if (!tail || !((GuestRoot) node.getRootNode()).isSelf(call.getTarget())) throw call;
                if (metrics.getEnabled()) metrics.setSelfTailReentries(metrics.getSelfTailReentries() + 1);
                return call;
            }
        }
        public static Dispatch createDispatch(int arity, boolean tail, Metrics metrics, boolean[] evaluatedArguments) {
            return Dispatch.Companion.create(arity, tail, metrics, evaluatedArguments);
        }
    }

    @Operation(forceCached = true)
    @ConstantOperand(type = ArgumentLayout.class, name = "layout")
    @ConstantOperand(type = boolean.class, name = "tail")
    @ConstantOperand(type = Metrics.class, name = "metrics")
    @ConstantOperand(type = boolean[].class, name = "evaluatedArguments")
    public static final class ApplyCompact {
        @Specialization public static Object apply(VirtualFrame frame, ArgumentLayout layout, boolean tail,
                Metrics metrics, boolean[] evaluatedArguments, Closure function, @Variadic Object[] arguments,
                @Bind("$node") Node node,
                @Cached(value = "createDispatch(layout, tail, metrics, evaluatedArguments)", neverDefault = true) Dispatch dispatch) {
            try {
                return dispatch.execute(frame, function, arguments);
            } catch (TailCall call) {
                // A -> B -> ... -> A unwinds to the owning activation. The compiler
                // consumes this internal result and restores locals before a real
                // bytecode backedge. Never intercept a call with pending non-tail work.
                if (!tail || !((GuestRoot) node.getRootNode()).isSelf(call.getTarget())) throw call;
                if (metrics.getEnabled()) metrics.setSelfTailReentries(metrics.getSelfTailReentries() + 1);
                return call;
            }
        }
        public static Dispatch createDispatch(ArgumentLayout layout, boolean tail, Metrics metrics, boolean[] evaluatedArguments) {
            return Dispatch.Companion.create(layout.getLogicalArity(), tail, metrics, evaluatedArguments, layout);
        }
    }

    /** Internal control result of a matching-root tail bounce, never a guest value. */
    @Operation
    public static final class IsTailReentry {
        @Specialization public static boolean test(Object value) { return value instanceof TailCall; }
    }

    @Operation
    @ConstantOperand(type = int.class, name = "index")
    public static final class TailArgument {
        @Specialization public static Object read(int index, TailCall call) { return call.getArgs()[index]; }
    }

    @Operation(forceCached = true)
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
        @Specialization public static boolean number(Object literal, long value) {
            return literal instanceof Long number && number.longValue() == value;
        }
        @Specialization(guards = "!isLong(value)") public static boolean object(Object literal, Object value) {
            return java.util.Objects.equals(literal, value);
        }
        public static boolean isLong(Object value) { return value instanceof Long; }
    }

    @Operation
    @ConstantOperand(type = DataLayout.class, name = "layout")
    @ConstantOperand(type = int.class, name = "index")
    public static final class ReadDataField {
        @Specialization(guards = "layout.isFloat(index)")
        public static float floating(DataLayout layout, int index, DataValue value) { return layout.readFloat(value, index); }
        @Specialization(guards = "layout.isDouble(index)")
        public static double doubleValue(DataLayout layout, int index, DataValue value) { return layout.readDouble(value, index); }
        @Specialization(guards = "layout.isLong(index)")
        public static long number(DataLayout layout, int index, DataValue value) { return layout.readLong(value, index); }
        @Specialization(guards = {"!layout.isLong(index)", "!layout.isFloat(index)", "!layout.isDouble(index)"})
        public static Object object(DataLayout layout, int index, DataValue value) { return layout.read(value, index); }
    }

    @Operation
    @ConstantOperand(type = String.class, name = "message")
    @ConstantOperand(type = Metrics.class, name = "metrics")
    public static final class Unsupported {
        @Specialization public static Object trap(String message, Metrics metrics) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            metrics.setUnsupportedTraps(metrics.getUnsupportedTraps() + 1);
            throw fail("Diagnostic unsupported path reached: " + message);
        }
    }

    @Operation
    public static final class FailCase {
        @Specialization public static Object failCase() { throw fail("Non-exhaustive Core case"); }
    }

    @Operation
    public static final class Raise {
        @Specialization public static Object raise(Object payload, @Bind("$node") Node node) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            return throwGuest(payload, node);
        }
        @TruffleBoundary private static Object throwGuest(Object payload, Node node) { throw new GuestException(payload, node); }
    }

    @Operation public static final class AddressPlus {
        @Specialization public static LiteralAddress plus(LiteralAddress address, long displacement) { return address.plus(displacement); }
        @Fallback public static LiteralAddress invalid(Object address, Object displacement) {
            if (!(address instanceof LiteralAddress)) throw fail("Expected a managed literal Addr#");
            throw fail("Expected primitive Long");
        }
    }
    @Operation public static final class AddressIndexChar {
        @Specialization public static long index(LiteralAddress address, long displacement) { return address.indexChar(displacement); }
        @Fallback public static long invalid(Object address, Object displacement) {
            if (!(address instanceof LiteralAddress)) throw fail("Expected a managed literal Addr#");
            throw fail("Expected primitive Long");
        }
    }

    @Operation
    @ConstantOperand(type = LocalAccessor.class, name = "destination")
    public static final class NewMutVar {
        @Specialization public static void create(VirtualFrame frame, LocalAccessor destination,
                Object value, Object state, @Bind("$node") Node node) {
            TupleResultsKt.requireVoidCarrier(state);
            destination.setObject(((BytecodeRoot) node.getRootNode()).getBytecodeNode(), frame, new ManagedMutVar(value));
        }
    }
    @Operation
    @ConstantOperand(type = LocalAccessor.class, name = "destination")
    public static final class ReadMutVar {
        @Specialization public static void read(VirtualFrame frame, LocalAccessor destination,
                Object value, Object state, @Bind("$node") Node node) {
            ManagedMutVar cell = ManagedMutVar.require(value);
            TupleResultsKt.requireVoidCarrier(state);
            destination.setObject(((BytecodeRoot) node.getRootNode()).getBytecodeNode(), frame, cell.getValue());
        }
    }
    @Operation public static final class WriteMutVar {
        @Specialization public static Object write(Object reference, Object value, Object state) {
            ManagedMutVar cell = ManagedMutVar.require(reference);
            TupleResultsKt.requireVoidCarrier(state);
            cell.setValue(value);
            return kotlin.Unit.INSTANCE;
        }
    }

    @Operation
    @ConstantOperand(type = LocalAccessor.class, name = "destination")
    public static final class NewArray {
        @Specialization public static void create(VirtualFrame frame, LocalAccessor destination,
                long size, Object initial, Object state, @Bind("$node") Node node) {
            TupleResultsKt.requireVoidCarrier(state);
            destination.setObject(((BytecodeRoot) node.getRootNode()).getBytecodeNode(), frame, ManagedArray.allocate(size, initial));
        }
    }
    @Operation
    @ConstantOperand(type = LocalAccessor.class, name = "destination")
    public static final class ReadArray {
        @Specialization public static void read(VirtualFrame frame, LocalAccessor destination,
                Object value, long index, Object state, @Bind("$node") Node node) {
            Object[] array = ManagedArray.require(value);
            TupleResultsKt.requireVoidCarrier(state);
            destination.setObject(((BytecodeRoot) node.getRootNode()).getBytecodeNode(), frame, ManagedArray.read(array, index));
        }
    }
    @Operation public static final class WriteArray {
        @Specialization public static Object write(Object reference, long index, Object value, Object state) {
            Object[] array = ManagedArray.require(reference);
            TupleResultsKt.requireVoidCarrier(state);
            ManagedArray.write(array, index, value);
            return kotlin.Unit.INSTANCE;
        }
    }
    @Operation
    @ConstantOperand(type = LocalAccessor.class, name = "destination")
    public static final class FreezeArray {
        @Specialization public static void freeze(VirtualFrame frame, LocalAccessor destination,
                Object reference, Object state, @Bind("$node") Node node) {
            Object[] array = ManagedArray.require(reference);
            TupleResultsKt.requireVoidCarrier(state);
            destination.setObject(((BytecodeRoot) node.getRootNode()).getBytecodeNode(), frame, ManagedArray.freeze(array));
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

    /** State operands are evaluated before each effect; only the array has a tuple slot. */
    @Operation
    @ConstantOperand(type = LocalAccessor.class, name = "destination")
    public static final class NewByteArray {
        @Specialization public static void allocate(VirtualFrame frame, LocalAccessor destination,
                long size, Object state, @Bind("$node") Node node) {
            ManagedByteArray.requireState(state);
            byte[] array = ManagedByteArray.allocate(size);
            destination.setObject(((BytecodeRoot) node.getRootNode()).getBytecodeNode(), frame, array);
        }
    }
    @Operation
    @ConstantOperand(type = LocalAccessor.class, name = "destination")
    public static final class FreezeByteArray {
        @Specialization public static void freeze(VirtualFrame frame, LocalAccessor destination,
                Object value, Object state, @Bind("$node") Node node) {
            byte[] array = ManagedByteArray.require(value);
            ManagedByteArray.requireState(state);
            destination.setObject(((BytecodeRoot) node.getRootNode()).getBytecodeNode(), frame, ManagedByteArray.freeze(array));
        }
    }
    @Operation public static final class WriteByteArray {
        @Specialization public static Object write(Object value, long offset, long byteValue, Object state) {
            byte[] array = ManagedByteArray.require(value);
            ManagedByteArray.requireState(state);
            ManagedByteArray.write(array, offset, byteValue);
            return kotlin.Unit.INSTANCE;
        }
    }
    @Operation public static final class CopyByteArray {
        @Specialization public static Object copy(Object source, long sourceOffset, Object destination,
                long destinationOffset, long count, Object state) {
            byte[] from = ManagedByteArray.require(source);
            byte[] to = ManagedByteArray.require(destination);
            ManagedByteArray.requireState(state);
            ManagedByteArray.copy(from, sourceOffset, to, destinationOffset, count);
            return kotlin.Unit.INSTANCE;
        }
    }
    @Operation public static final class SizeByteArray {
        @Specialization public static long size(Object value) { return ManagedByteArray.size(ManagedByteArray.require(value)); }
    }
    @Operation public static final class IndexByteArray {
        @Specialization public static long index(Object value, long offset) { return ManagedByteArray.read(ManagedByteArray.require(value), offset); }
    }
    @Operation
    @ConstantOperand(type = LocalAccessor.class, name = "destination")
    public static final class ReadIntArray {
        @Specialization public static void read(VirtualFrame frame, LocalAccessor destination,
                Object value, long index, Object state, @Bind("$node") Node node) {
            byte[] array = ManagedByteArray.require(value);
            ManagedByteArray.requireState(state);
            long result = ManagedIntArray.read(array, index);
            destination.setLong(((BytecodeRoot) node.getRootNode()).getBytecodeNode(), frame, result);
        }
    }
    @Operation public static final class WriteIntArray {
        @Specialization public static Object write(Object value, long index, long integer, Object state) {
            byte[] array = ManagedByteArray.require(value);
            ManagedByteArray.requireState(state);
            ManagedIntArray.write(array, index, integer);
            return kotlin.Unit.INSTANCE;
        }
    }
    @Operation public static final class IndexIntArray {
        @Specialization public static long index(Object value, long index) {
            return ManagedIntArray.read(ManagedByteArray.require(value), index);
        }
    }
    @Operation
    @ConstantOperand(type = LocalAccessor.class, name = "destination")
    public static final class ReadDoubleArray {
        @Specialization public static void read(VirtualFrame frame, LocalAccessor destination,
                Object value, long index, Object state, @Bind("$node") Node node) {
            byte[] array = ManagedByteArray.require(value);
            ManagedByteArray.requireState(state);
            double result = ManagedDoubleArray.read(array, index);
            destination.setDouble(((BytecodeRoot) node.getRootNode()).getBytecodeNode(), frame, result);
        }
    }
    @Operation public static final class WriteDoubleArray {
        @Specialization public static Object write(Object value, long index, double number, Object state) {
            byte[] array = ManagedByteArray.require(value);
            ManagedByteArray.requireState(state);
            ManagedDoubleArray.write(array, index, number);
            return kotlin.Unit.INSTANCE;
        }
    }
    @Operation public static final class IndexDoubleArray {
        @Specialization public static double index(Object value, long index) {
            return ManagedDoubleArray.read(ManagedByteArray.require(value), index);
        }
    }

    @Operation
    @ConstantOperand(type = LocalAccessor.class, name = "destination")
    public static final class ReadFloatArray {
        @Specialization public static void read(VirtualFrame frame, LocalAccessor destination,
                Object value, long index, Object state, @Bind("$node") Node node) {
            byte[] array = ManagedByteArray.require(value);
            ManagedByteArray.requireState(state);
            float result = ManagedFloatArray.read(array, index);
            destination.setFloat(((BytecodeRoot) node.getRootNode()).getBytecodeNode(), frame, result);
        }
    }
    @Operation public static final class WriteFloatArray {
        @Specialization public static Object write(Object value, long index, float number, Object state) {
            byte[] array = ManagedByteArray.require(value);
            ManagedByteArray.requireState(state);
            ManagedFloatArray.write(array, index, number);
            return kotlin.Unit.INSTANCE;
        }
    }
    @Operation public static final class IndexFloatArray {
        @Specialization public static float index(Object value, long index) {
            return ManagedFloatArray.read(ManagedByteArray.require(value), index);
        }
    }

    @Operation
    @ConstantOperand(type = boolean.class, name = "unsigned")
    @ConstantOperand(type = LocalAccessor.class, name = "destination")
    public static final class ReadInt16Array {
        @Specialization public static void read(VirtualFrame frame, boolean unsigned, LocalAccessor destination,
                Object value, long index, Object state, @Bind("$node") Node node) {
            byte[] array = ManagedByteArray.require(value);
            ManagedByteArray.requireState(state);
            long result = unsigned ? ManagedInt16Array.readUnsigned(array, index) : ManagedInt16Array.readSigned(array, index);
            destination.setLong(((BytecodeRoot) node.getRootNode()).getBytecodeNode(), frame, result);
        }
    }
    @Operation public static final class WriteInt16Array {
        @Specialization public static Object write(Object value, long index, long integer, Object state) {
            byte[] array = ManagedByteArray.require(value);
            ManagedByteArray.requireState(state);
            ManagedInt16Array.write(array, index, integer);
            return kotlin.Unit.INSTANCE;
        }
    }
    @Operation @ConstantOperand(type = boolean.class, name = "unsigned")
    public static final class IndexInt16Array {
        @Specialization public static long index(boolean unsigned, Object value, long index) {
            byte[] array = ManagedByteArray.require(value);
            return unsigned ? ManagedInt16Array.readUnsigned(array, index) : ManagedInt16Array.readSigned(array, index);
        }
    }

    @Operation
    @ConstantOperand(type = boolean.class, name = "unsigned")
    @ConstantOperand(type = LocalAccessor.class, name = "destination")
    public static final class ReadInt32Array {
        @Specialization public static void read(VirtualFrame frame, boolean unsigned, LocalAccessor destination,
                Object value, long index, Object state, @Bind("$node") Node node) {
            byte[] array = ManagedByteArray.require(value);
            ManagedByteArray.requireState(state);
            long result = unsigned ? ManagedInt32Array.readUnsigned(array, index) : ManagedInt32Array.readSigned(array, index);
            destination.setLong(((BytecodeRoot) node.getRootNode()).getBytecodeNode(), frame, result);
        }
    }
    @Operation public static final class WriteInt32Array {
        @Specialization public static Object write(Object value, long index, long integer, Object state) {
            byte[] array = ManagedByteArray.require(value);
            ManagedByteArray.requireState(state);
            ManagedInt32Array.write(array, index, integer);
            return kotlin.Unit.INSTANCE;
        }
    }
    @Operation @ConstantOperand(type = boolean.class, name = "unsigned")
    public static final class IndexInt32Array {
        @Specialization public static long index(boolean unsigned, Object value, long index) {
            byte[] array = ManagedByteArray.require(value);
            return unsigned ? ManagedInt32Array.readUnsigned(array, index) : ManagedInt32Array.readSigned(array, index);
        }
    }

    // GHC machine integers wrap. Comparisons return Int# 0/1, not boxed Bool.
    @Operation public static final class VectorPack {
        @Specialization public static Int64X2 pack(long first, long second) { return new Int64X2(first, second); }
    }
    @Operation public static final class VectorBroadcast {
        @Specialization public static Int64X2 broadcast(long value) { return new Int64X2(value, value); }
    }
    @Operation public static final class VectorNegate {
        @Specialization public static Int64X2 negate(Int64X2 value) { return Int64X2.negate(value); }
    }
    @Operation @ConstantOperand(type = boolean.class, name = "subtract")
    public static final class VectorBinary {
        @Specialization public static Int64X2 binary(boolean subtract, Int64X2 first, Int64X2 second) {
            return subtract ? Int64X2.subtract(first, second) : Int64X2.add(first, second);
        }
    }
    @Operation
    @ConstantOperand(type = LocalAccessor.class, name = "first")
    @ConstantOperand(type = LocalAccessor.class, name = "second")
    public static final class VectorUnpack {
        @Specialization public static void unpack(VirtualFrame frame, LocalAccessor first, LocalAccessor second,
                Int64X2 value, @Bind("$node") Node node) {
            BytecodeNode bytecode = ((BytecodeRoot) node.getRootNode()).getBytecodeNode();
            first.setLong(bytecode, frame, value.first); second.setLong(bytecode, frame, value.second);
        }
    }

    @Operation public static final class Vector16Pack {
        @Specialization public static Int16X8 pack(long first, long second, long third, long fourth,
                long fifth, long sixth, long seventh, long eighth) {
            return new Int16X8((short) first, (short) second, (short) third, (short) fourth,
                (short) fifth, (short) sixth, (short) seventh, (short) eighth);
        }
    }
    @Operation public static final class Vector16Broadcast {
        @Specialization public static Int16X8 broadcast(long value) { return Int16X8.broadcast((short) value); }
    }
    @Operation public static final class Vector16Negate {
        @Specialization public static Int16X8 negate(Int16X8 value) { return Int16X8.negate(value); }
    }
    @Operation @ConstantOperand(type = int.class, name = "operation")
    public static final class Vector16Binary {
        @Specialization public static Int16X8 binary(int operation, Int16X8 first, Int16X8 second) {
            return switch (operation) {
                case 0 -> Int16X8.add(first, second);
                case 1 -> Int16X8.subtract(first, second);
                case 2 -> Int16X8.multiply(first, second);
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
                LocalAccessor seventh, LocalAccessor eighth, Int16X8 value, @Bind("$node") Node node) {
            BytecodeNode bytecode = ((BytecodeRoot) node.getRootNode()).getBytecodeNode();
            first.setLong(bytecode, frame, value.first); second.setLong(bytecode, frame, value.second);
            third.setLong(bytecode, frame, value.third); fourth.setLong(bytecode, frame, value.fourth);
            fifth.setLong(bytecode, frame, value.fifth); sixth.setLong(bytecode, frame, value.sixth);
            seventh.setLong(bytecode, frame, value.seventh); eighth.setLong(bytecode, frame, value.eighth);
        }
    }

    @Operation public static final class Vector32Pack {
        @Specialization public static Int32X4 pack(long first, long second, long third, long fourth) {
            return new Int32X4((int) first, (int) second, (int) third, (int) fourth);
        }
    }
    @Operation public static final class Vector32Broadcast {
        @Specialization public static Int32X4 broadcast(long value) {
            int lane = (int) value;
            return new Int32X4(lane, lane, lane, lane);
        }
    }
    @Operation public static final class Vector32Negate {
        @Specialization public static Int32X4 negate(Int32X4 value) { return Int32X4.negate(value); }
    }
    @Operation @ConstantOperand(type = boolean.class, name = "subtract")
    public static final class Vector32Binary {
        @Specialization public static Int32X4 binary(boolean subtract, Int32X4 first, Int32X4 second) {
            return subtract ? Int32X4.subtract(first, second) : Int32X4.add(first, second);
        }
    }
    @Operation
    @ConstantOperand(type = LocalAccessor.class, name = "first")
    @ConstantOperand(type = LocalAccessor.class, name = "second")
    @ConstantOperand(type = LocalAccessor.class, name = "third")
    @ConstantOperand(type = LocalAccessor.class, name = "fourth")
    public static final class Vector32Unpack {
        @Specialization public static void unpack(VirtualFrame frame, LocalAccessor first, LocalAccessor second,
                LocalAccessor third, LocalAccessor fourth, Int32X4 value, @Bind("$node") Node node) {
            BytecodeNode bytecode = ((BytecodeRoot) node.getRootNode()).getBytecodeNode();
            first.setLong(bytecode, frame, value.first); second.setLong(bytecode, frame, value.second);
            third.setLong(bytecode, frame, value.third); fourth.setLong(bytecode, frame, value.fourth);
        }
    }

    @Operation public static final class VectorFloatPack {
        @Specialization public static FloatX4 pack(float first, float second, float third, float fourth) {
            return FloatX4.pack(first, second, third, fourth);
        }
    }
    @Operation public static final class VectorFloatBroadcast {
        @Specialization public static FloatX4 broadcast(float value) { return FloatX4.broadcast(value); }
    }
    @Operation @ConstantOperand(type = int.class, name = "operation")
    public static final class VectorFloatBinary {
        @Specialization public static FloatX4 binary(int operation, FloatX4 first, FloatX4 second) {
            return switch (operation) {
                case 0 -> FloatX4.add(first, second);
                case 1 -> FloatX4.subtract(first, second);
                case 2 -> FloatX4.multiply(first, second);
                default -> throw new RuntimeFault("Invalid FloatX4 operation");
            };
        }
    }
    @Operation
    @ConstantOperand(type = LocalAccessor.class, name = "first")
    @ConstantOperand(type = LocalAccessor.class, name = "second")
    @ConstantOperand(type = LocalAccessor.class, name = "third")
    @ConstantOperand(type = LocalAccessor.class, name = "fourth")
    public static final class VectorFloatUnpack {
        @Specialization public static void unpack(VirtualFrame frame, LocalAccessor first, LocalAccessor second,
                LocalAccessor third, LocalAccessor fourth, FloatX4 value, @Bind("$node") Node node) {
            BytecodeNode bytecode = ((BytecodeRoot) node.getRootNode()).getBytecodeNode();
            first.setFloat(bytecode, frame, value.lane(0)); second.setFloat(bytecode, frame, value.lane(1));
            third.setFloat(bytecode, frame, value.lane(2)); fourth.setFloat(bytecode, frame, value.lane(3));
        }
    }

    @Operation public static final class VectorDoublePack {
        @Specialization public static DoubleX2 pack(double first, double second) {
            return DoubleX2.pack(first, second);
        }
    }
    @Operation public static final class VectorDoubleBroadcast {
        @Specialization public static DoubleX2 broadcast(double value) { return DoubleX2.broadcast(value); }
    }
    @Operation @ConstantOperand(type = int.class, name = "operation")
    public static final class VectorDoubleBinary {
        @Specialization public static DoubleX2 binary(int operation, DoubleX2 first, DoubleX2 second) {
            return switch (operation) {
                case 0 -> DoubleX2.add(first, second);
                case 1 -> DoubleX2.subtract(first, second);
                case 2 -> DoubleX2.multiply(first, second);
                default -> throw new RuntimeFault("Invalid DoubleX2 operation");
            };
        }
    }
    @Operation
    @ConstantOperand(type = LocalAccessor.class, name = "first")
    @ConstantOperand(type = LocalAccessor.class, name = "second")
    public static final class VectorDoubleUnpack {
        @Specialization public static void unpack(VirtualFrame frame, LocalAccessor first, LocalAccessor second, DoubleX2 value, @Bind("$node") Node node) {
            BytecodeNode bytecode = ((BytecodeRoot) node.getRootNode()).getBytecodeNode();
            first.setDouble(bytecode, frame, value.lane(0)); second.setDouble(bytecode, frame, value.lane(1));
        }
    }

    /** Evaluates a zero-width field for effects while producing no destination value. */
    @Operation public static final class DiscardVoid {
        @Specialization public static void discard(Object value) { TupleResultsKt.requireVoidCarrier(value); }
    }

    @Operation public static final class Add { @Specialization public static long apply(long x, long y) { return x + y; } }
    @Operation public static final class Subtract { @Specialization public static long apply(long x, long y) { return x - y; } }
    @Operation public static final class Multiply { @Specialization public static long apply(long x, long y) { return x * y; } }
    @Operation public static final class Negate { @Specialization public static long apply(long x) { return -x; } }
    @Operation public static final class Quotient { @Specialization public static long apply(long x, long y) { return x / y; } }
    @Operation public static final class Remainder { @Specialization public static long apply(long x, long y) { return x % y; } }
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
    @Operation public static final class ShiftLeft { @Specialization public static long apply(long x, long y) { return x << (int) y; } }
    @Operation public static final class ShiftRight { @Specialization public static long apply(long x, long y) { return x >> (int) y; } }
    @Operation public static final class ShiftRightUnsigned { @Specialization public static long apply(long x, long y) { return x >>> (int) y; } }
    @Operation public static final class Narrow8 { @Specialization public static long apply(long x) { return (byte) x; } }
    @Operation public static final class Narrow16 { @Specialization public static long apply(long x) { return (short) x; } }
    @Operation public static final class Narrow32 { @Specialization public static long apply(long x) { return (int) x; } }
    // Constant masks are at most 0xffffffffL: every narrow unsigned result is a nonnegative Long.
    @Operation @ConstantOperand(type = long.class, name = "mask")
    public static final class NarrowWord { @Specialization public static long apply(long mask, long x) { return x & mask; } }
    @Operation @ConstantOperand(type = long.class, name = "mask")
    public static final class AddNarrowWord { @Specialization public static long apply(long mask, long x, long y) { return (x + y) & mask; } }
    @Operation @ConstantOperand(type = long.class, name = "mask")
    public static final class SubtractNarrowWord { @Specialization public static long apply(long mask, long x, long y) { return (x - y) & mask; } }
    @Operation @ConstantOperand(type = long.class, name = "mask")
    public static final class MultiplyNarrowWord { @Specialization public static long apply(long mask, long x, long y) { return (x * y) & mask; } }
    @Operation @ConstantOperand(type = long.class, name = "mask")
    public static final class LessThanNarrowWord { @Specialization public static long apply(long mask, long x, long y) { return (x & mask) < (y & mask) ? 1L : 0L; } }
    @Operation @ConstantOperand(type = long.class, name = "mask")
    public static final class LessEqualNarrowWord { @Specialization public static long apply(long mask, long x, long y) { return (x & mask) <= (y & mask) ? 1L : 0L; } }
    @Operation public static final class QuotientUnsigned { @Specialization public static long apply(long x, long y) { return Long.divideUnsigned(x, y); } }
    @Operation public static final class RemainderUnsigned { @Specialization public static long apply(long x, long y) { return Long.remainderUnsigned(x, y); } }
    @Operation public static final class GreaterThanUnsigned { @Specialization public static long apply(long x, long y) { return Long.compareUnsigned(x, y) > 0 ? 1L : 0L; } }
    @Operation public static final class GreaterEqualUnsigned { @Specialization public static long apply(long x, long y) { return Long.compareUnsigned(x, y) >= 0 ? 1L : 0L; } }
    @Operation @ConstantOperand(type = long.class, name = "mask")
    public static final class QuotientNarrowWord { @Specialization public static long apply(long mask, long x, long y) { return (x & mask) / (y & mask); } }
    @Operation @ConstantOperand(type = long.class, name = "mask")
    public static final class RemainderNarrowWord { @Specialization public static long apply(long mask, long x, long y) { return (x & mask) % (y & mask); } }
    @Operation @ConstantOperand(type = long.class, name = "mask")
    public static final class EqualNarrowWord { @Specialization public static long apply(long mask, long x, long y) { return (x & mask) == (y & mask) ? 1L : 0L; } }
    @Operation @ConstantOperand(type = long.class, name = "mask")
    public static final class NotEqualNarrowWord { @Specialization public static long apply(long mask, long x, long y) { return (x & mask) != (y & mask) ? 1L : 0L; } }
    @Operation @ConstantOperand(type = long.class, name = "mask")
    public static final class GreaterThanNarrowWord { @Specialization public static long apply(long mask, long x, long y) { return (x & mask) > (y & mask) ? 1L : 0L; } }
    @Operation @ConstantOperand(type = long.class, name = "mask")
    public static final class GreaterEqualNarrowWord { @Specialization public static long apply(long mask, long x, long y) { return (x & mask) >= (y & mask) ? 1L : 0L; } }
    @Operation @ConstantOperand(type = long.class, name = "mask")
    public static final class BitAndNarrowWord { @Specialization public static long apply(long mask, long x, long y) { return (x & y) & mask; } }
    @Operation @ConstantOperand(type = long.class, name = "mask")
    public static final class BitOrNarrowWord { @Specialization public static long apply(long mask, long x, long y) { return (x | y) & mask; } }
    @Operation @ConstantOperand(type = long.class, name = "mask")
    public static final class BitXorNarrowWord { @Specialization public static long apply(long mask, long x, long y) { return (x ^ y) & mask; } }
    @Operation @ConstantOperand(type = long.class, name = "mask")
    public static final class BitNotNarrowWord { @Specialization public static long apply(long mask, long x) { return ~x & mask; } }
    @Operation @ConstantOperand(type = long.class, name = "mask")
    public static final class ShiftLeftNarrowWord { @Specialization public static long apply(long mask, long x, long y) { return (x << (int) y) & mask; } }
    @Operation @ConstantOperand(type = long.class, name = "mask")
    public static final class ShiftRightNarrowWord { @Specialization public static long apply(long mask, long x, long y) { return (x & mask) >>> (int) y; } }
    // A constant shift truncates to the primop width, then sign-extends its Long carrier.
    private static long signedNarrow(long value, int shift) { return (value << shift) >> shift; }
    @Operation @ConstantOperand(type = int.class, name = "shift")
    public static final class NegateNarrowInt { @Specialization public static long apply(int shift, long x) { return signedNarrow(-x, shift); } }
    @Operation @ConstantOperand(type = int.class, name = "shift")
    public static final class AddNarrowInt { @Specialization public static long apply(int shift, long x, long y) { return signedNarrow(x + y, shift); } }
    @Operation @ConstantOperand(type = int.class, name = "shift")
    public static final class SubtractNarrowInt { @Specialization public static long apply(int shift, long x, long y) { return signedNarrow(x - y, shift); } }
    @Operation @ConstantOperand(type = int.class, name = "shift")
    public static final class MultiplyNarrowInt { @Specialization public static long apply(int shift, long x, long y) { return signedNarrow(x * y, shift); } }
    @Operation @ConstantOperand(type = int.class, name = "shift")
    public static final class QuotientNarrowInt { @Specialization public static long apply(int shift, long x, long y) { return signedNarrow(signedNarrow(x, shift) / signedNarrow(y, shift), shift); } }
    @Operation @ConstantOperand(type = int.class, name = "shift")
    public static final class RemainderNarrowInt { @Specialization public static long apply(int shift, long x, long y) { return signedNarrow(signedNarrow(x, shift) % signedNarrow(y, shift), shift); } }
    @Operation @ConstantOperand(type = int.class, name = "shift")
    public static final class EqualNarrowInt { @Specialization public static long apply(int shift, long x, long y) { return signedNarrow(x, shift) == signedNarrow(y, shift) ? 1L : 0L; } }
    @Operation @ConstantOperand(type = int.class, name = "shift")
    public static final class NotEqualNarrowInt { @Specialization public static long apply(int shift, long x, long y) { return signedNarrow(x, shift) != signedNarrow(y, shift) ? 1L : 0L; } }
    @Operation @ConstantOperand(type = int.class, name = "shift")
    public static final class LessThanNarrowInt { @Specialization public static long apply(int shift, long x, long y) { return signedNarrow(x, shift) < signedNarrow(y, shift) ? 1L : 0L; } }
    @Operation @ConstantOperand(type = int.class, name = "shift")
    public static final class LessEqualNarrowInt { @Specialization public static long apply(int shift, long x, long y) { return signedNarrow(x, shift) <= signedNarrow(y, shift) ? 1L : 0L; } }
    @Operation @ConstantOperand(type = int.class, name = "shift")
    public static final class GreaterThanNarrowInt { @Specialization public static long apply(int shift, long x, long y) { return signedNarrow(x, shift) > signedNarrow(y, shift) ? 1L : 0L; } }
    @Operation @ConstantOperand(type = int.class, name = "shift")
    public static final class GreaterEqualNarrowInt { @Specialization public static long apply(int shift, long x, long y) { return signedNarrow(x, shift) >= signedNarrow(y, shift) ? 1L : 0L; } }
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
    @Operation public static final class DoubleEqual { @Specialization public static long apply(double x, double y) { return x == y ? 1L : 0L; } }
    @Operation public static final class DoubleNotEqual { @Specialization public static long apply(double x, double y) { return x != y ? 1L : 0L; } }
    @Operation public static final class DoubleLess { @Specialization public static long apply(double x, double y) { return x < y ? 1L : 0L; } }
    @Operation public static final class DoubleLessEqual { @Specialization public static long apply(double x, double y) { return x <= y ? 1L : 0L; } }
    @Operation public static final class DoubleGreater { @Specialization public static long apply(double x, double y) { return x > y ? 1L : 0L; } }
    @Operation public static final class DoubleGreaterEqual { @Specialization public static long apply(double x, double y) { return x >= y ? 1L : 0L; } }
    @Operation public static final class IntToFloat { @Specialization public static float apply(long x) { return (float) x; } }
    @Operation public static final class IntToDouble { @Specialization public static double apply(long x) { return (double) x; } }
    @Operation public static final class FloatToInt { @Specialization public static long apply(float x) { return (long) x; } }
    @Operation public static final class DoubleToInt { @Specialization public static long apply(double x) { return (long) x; } }
    @Operation public static final class FloatToDouble { @Specialization public static double apply(float x) { return (double) x; } }
    @Operation public static final class DoubleToFloat { @Specialization public static float apply(double x) { return (float) x; } }
    @Operation public static final class ToFloat { @Specialization public static float apply(float x) { return x; } }
    @Operation public static final class ToDouble { @Specialization public static double apply(double x) { return x; } }
    @Operation public static final class ToLong { @Specialization public static long apply(long x) { return x; } }

    private static RuntimeFault fail(String message) {
        CompilerDirectives.transferToInterpreterAndInvalidate();
        return new RuntimeFault(message);
    }
}
