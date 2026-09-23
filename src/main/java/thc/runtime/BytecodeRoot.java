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
        defaultUncachedThreshold = "0", boxingEliminationTypes = {long.class, boolean.class})
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
        @Specialization public static long number(Metrics metrics, long value) { return value; }
        @Specialization public static boolean bool(Metrics metrics, boolean value) { return value; }
        @Specialization(replaces = {"number", "bool"})
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
        @Specialization public static long number(Metrics metrics, LocalAccessor local, boolean cell, long value) { return value; }
        @Specialization public static boolean bool(Metrics metrics, LocalAccessor local, boolean cell, boolean value) { return value; }
        @Specialization(replaces = {"number", "bool"})
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
                        else if (result instanceof Boolean bool) local.setBoolean(bytecode, frame, bool);
                        else local.setObject(bytecode, frame, result);
                    }
                }
            }
            return result;
        }
        public static Force createForce(Metrics metrics) { return new Force(metrics); }
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
        @Specialization(guards = "layout.isLong(index)")
        public static long number(DataLayout layout, int index, DataValue value) { return layout.readLong(value, index); }
        @Specialization(guards = "!layout.isLong(index)")
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

    // GHC machine integers wrap. Comparisons return Int# 0/1, not boxed Bool.
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
    @Operation public static final class ToLong { @Specialization public static long apply(long x) { return x; } }

    private static RuntimeFault fail(String message) {
        CompilerDirectives.transferToInterpreterAndInvalidate();
        return new RuntimeFault(message);
    }
}
