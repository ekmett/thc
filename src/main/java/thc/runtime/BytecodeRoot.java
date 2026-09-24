// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

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
import com.oracle.truffle.api.exception.AbstractTruffleException;
import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.Node;
import thc.Language;

/** Concrete Core instructions sharing the AST backend's heap and application ABI. */
// An explicit compile request must work after the first ordinary invocation, even
// when Core proofs eliminate every operation that otherwise forces the cached tier.
@GenerateBytecode(languageClass = Language.class, enableYield = true, enableUncachedInterpreter = true,
        defaultUncachedThreshold = "0", boxingEliminationTypes = {long.class, float.class, double.class, boolean.class})
public abstract class BytecodeRoot extends GuestRoot implements BytecodeRootNode {
    private String label = "bytecode";
    @CompilerDirectives.CompilationFinal private LocalAccessor typedBloom;
    public final void configureTypedBloom(LocalAccessor bloom) { typedBloom = bloom; }

    protected BytecodeRoot(Language language, FrameDescriptor descriptor) {
        super(language, descriptor);
    }

    public final void setLabel(String label) { this.label = label; }
    @Override public final String getName() { return label; }
    @Override public final String toString() { return label; }
    @Override public final long bloom(VirtualFrame frame) {
        // Self backedges restore DSL locals, leaving the incoming ancestry intact.
        if (typedBloom == null) return (long) frame.getArguments()[0] | mask;
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
            this.closed = captureLayout == null ? new Closure(null, ApplicationKt.getNO_PAP_ARGUMENTS(), arity, target) : null;
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

    @Operation(forceCached = true)
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

    @Operation(forceCached = true)
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

    @Operation(forceCached = true)
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

    @Operation(forceCached = true)
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

    @Operation(forceCached = true)
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

    @Operation(forceCached = true)
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
            publish(frame, local, cell, binding, original, result, node);
            return result;
        }
        public static Force createForce(Metrics metrics) { return new Force(metrics); }
        static void publish(VirtualFrame frame, LocalAccessor local, boolean cell, Object binding,
                Object original, Object result, Node node) {
            if (!(original instanceof Thunk thunk)) return;
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
            if (resumed.getFailure() != null) throw resumed.getFailure();
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
            return resumed.getValue();
        }
        @Fallback public static Object malformed(LocalAccessor local, boolean cell, Object suspended, Object resumed) {
            throw new IllegalStateException("Forced-local continuation requires ChildResume");
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

    @Operation
    public static final class CheckSumTag {
        @Specialization public static long execute(long value) { return SumShape.INSTANCE.checkedTag(value); }
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
            return ManagedAddress.Companion.fromGuestByteArray(array);
        }
    }

    @Operation
    @ConstantOperand(type = LocalAccessor.class, name = "destination")
    public static final class ReadWord8OffAddr {
        @Specialization public static void read(VirtualFrame frame, LocalAccessor destination,
                ManagedAddress address, long offset, Object state, @Bind("$node") Node node) {
            ManagedByteArray.requireState(state);
            long value = address.readWord8(offset);
            destination.setLong(((BytecodeRoot) node.getRootNode()).getBytecodeNode(), frame, value);
        }
    }

    @Operation
    @ConstantOperand(type = LocalAccessor.class, name = "destination")
    public static final class ReadAddrOffAddr {
        @Specialization public static void read(VirtualFrame frame, LocalAccessor destination,
                ManagedAddress address, long offset, Object state, @Bind("$node") Node node) {
            ManagedByteArray.requireState(state);
            destination.setObject(((BytecodeRoot) node.getRootNode()).getBytecodeNode(), frame,
                address.readAddressElementIndex(offset));
        }
    }

    @Operation
    public static final class IndexAddrOffAddr {
        @Specialization public static ManagedAddress read(ManagedAddress address, long index) {
            return address.readAddressElementIndex(index);
        }
    }

    @Operation
    public static final class IndexAddrArray {
        @Specialization public static ManagedAddress read(Object array, long index) {
            return PinnedMemory.readAddressArray(array, index);
        }
    }

    @Operation
    @ConstantOperand(type = LocalAccessor.class, name = "destination")
    public static final class ReadAddrArray {
        @Specialization public static void read(VirtualFrame frame, LocalAccessor destination,
                Object array, long index, Object state, @Bind("$node") Node node) {
            ManagedByteArray.requireState(state);
            destination.setObject(((BytecodeRoot) node.getRootNode()).getBytecodeNode(), frame,
                PinnedMemory.readAddressArray(array, index));
        }
    }

    @Operation
    @ConstantOperand(type = ManagedAddressRead.class, name = "operation")
    @ConstantOperand(type = LocalAccessor.class, name = "destination")
    public static final class ReadManagedAddress {
        @Specialization public static void read(VirtualFrame frame, ManagedAddressRead operation,
                LocalAccessor destination, ManagedAddress address, long offset, Object state,
                @Bind("$node") Node node) {
            ManagedByteArray.requireState(state);
            long value = operation.read(address, offset);
            destination.setLong(((BytecodeRoot) node.getRootNode()).getBytecodeNode(), frame, value);
        }
    }

    @Operation
    public static final class WriteWord8OffAddr {
        @Specialization public static Object write(ManagedAddress address, long offset, long value, Object state) {
            ManagedByteArray.requireState(state);
            address.writeWord8(offset, value);
            return kotlin.Unit.INSTANCE;
        }
    }

    @Operation
    public static final class WriteAddrOffAddr {
        @Specialization public static Object write(ManagedAddress address, long offset,
                ManagedAddress value, Object state) {
            ManagedByteArray.requireState(state);
            address.writeAddressElementIndex(offset, value);
            return kotlin.Unit.INSTANCE;
        }
    }

    @Operation
    public static final class WriteAddrArray {
        @Specialization public static Object write(Object array, long index,
                ManagedAddress value, Object state) {
            ManagedByteArray.requireState(state);
            PinnedMemory.writeAddressArray(array, index, value);
            return kotlin.Unit.INSTANCE;
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
            kotlin.Pair<ManagedAddress, ManagedAddress> result = ManagedStackRuntime.frameInfo(snapshot, offset, layout);
            BytecodeNode bytecode = ((BytecodeRoot) node.getRootNode()).getBytecodeNode();
            standard.setObject(bytecode, frame, result.getFirst());
            key.setObject(bytecode, frame, result.getSecond());
        }
    }

    @Operation
    @ConstantOperand(type = TargetLayout.class, name = "layout")
    @ConstantOperand(type = LocalAccessor.class, name = "destination")
    public static final class OriginalStackLookupIpe {
        @Specialization public static void apply(VirtualFrame frame, TargetLayout layout,
                LocalAccessor destination, ManagedAddress key, ManagedAddress output, Object state,
                @Bind("$node") Node node) {
            TupleResultsKt.requireVoidCarrier(state);
            long result = ManagedStackRuntime.lookupIpe(key, output, layout);
            destination.setLong(((BytecodeRoot) node.getRootNode()).getBytecodeNode(), frame, result);
        }
    }

    @Operation
    @ConstantOperand(type = LocalAccessor.class, name = "destination")
    public static final class OriginalStdioWrite {
        @Specialization public static void apply(VirtualFrame frame, LocalAccessor destination,
                long fd, ManagedAddress address, long count, Object state, @Bind("$node") Node node) {
            TupleResultsKt.requireVoidCarrier(state);
            long result = CoreOriginalStdio.current(node).write(fd, address, count);
            destination.setLong(((BytecodeRoot) node.getRootNode()).getBytecodeNode(), frame, result);
        }
    }

    @Operation
    @ConstantOperand(type = LocalAccessor.class, name = "destination")
    public static final class OriginalStdioErrno {
        @Specialization public static void apply(VirtualFrame frame, LocalAccessor destination,
                Object state, @Bind("$node") Node node) {
            TupleResultsKt.requireVoidCarrier(state);
            long result = CoreOriginalStdio.current(node).errno();
            destination.setLong(((BytecodeRoot) node.getRootNode()).getBytecodeNode(), frame, result);
        }
    }

    @Operation
    @ConstantOperand(type = LocalAccessor.class, name = "destination")
    public static final class FileOpen {
        @Specialization public static void apply(VirtualFrame frame, LocalAccessor destination,
                ManagedAddress path, long mode, Object state, @Bind("$node") Node node) {
            TupleResultsKt.requireVoidCarrier(state);
            long result = CoreManagedFiles.current(node).open(path, mode);
            destination.setLong(((BytecodeRoot) node.getRootNode()).getBytecodeNode(), frame, result);
        }
    }

    @Operation(forceCached = true)
    @ConstantOperand(type = LocalAccessor.class, name = "destination")
    public static final class CloneMyStack {
        @Specialization public static void capture(VirtualFrame frame, LocalAccessor destination,
                Object state, @Bind("$node") Node node) {
            TupleResultsKt.requireVoidCarrier(state);
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
            TupleResultsKt.requireVoidCarrier(state);
            long result = CoreManagedFiles.current(node).read(fd, address, count);
            destination.setLong(((BytecodeRoot) node.getRootNode()).getBytecodeNode(), frame, result);
        }
    }

    @Operation
    @ConstantOperand(type = LocalAccessor.class, name = "destination")
    public static final class FileWrite {
        @Specialization public static void apply(VirtualFrame frame, LocalAccessor destination,
                long fd, ManagedAddress address, long count, Object state, @Bind("$node") Node node) {
            TupleResultsKt.requireVoidCarrier(state);
            long result = CoreManagedFiles.current(node).write(fd, address, count);
            destination.setLong(((BytecodeRoot) node.getRootNode()).getBytecodeNode(), frame, result);
        }
    }

    @Operation
    @ConstantOperand(type = LocalAccessor.class, name = "destination")
    public static final class FileClose {
        @Specialization public static void apply(VirtualFrame frame, LocalAccessor destination,
                long fd, Object state, @Bind("$node") Node node) {
            TupleResultsKt.requireVoidCarrier(state);
            long result = CoreManagedFiles.current(node).close(fd);
            destination.setLong(((BytecodeRoot) node.getRootNode()).getBytecodeNode(), frame, result);
        }
    }

    @Operation
    @ConstantOperand(type = LocalAccessor.class, name = "destination")
    public static final class FileErrorKind {
        @Specialization public static void apply(VirtualFrame frame, LocalAccessor destination,
                Object state, @Bind("$node") Node node) {
            TupleResultsKt.requireVoidCarrier(state);
            long result = CoreManagedFiles.current(node).errorKind();
            destination.setLong(((BytecodeRoot) node.getRootNode()).getBytecodeNode(), frame, result);
        }
    }

    @Operation
    @ConstantOperand(type = LocalAccessor.class, name = "destination")
    public static final class FileErrorMessage {
        @Specialization public static void apply(VirtualFrame frame, LocalAccessor destination,
                Object state, @Bind("$node") Node node) {
            TupleResultsKt.requireVoidCarrier(state);
            ManagedAddress result = CoreManagedFiles.current(node).errorMessage();
            destination.setObject(((BytecodeRoot) node.getRootNode()).getBytecodeNode(), frame, result);
        }
    }

    @Operation
    @ConstantOperand(type = LocalAccessor.class, name = "destination")
    public static final class FileSeek {
        @Specialization public static void apply(VirtualFrame frame, LocalAccessor destination,
                long fd, long offset, long mode, Object state, @Bind("$node") Node node) {
            TupleResultsKt.requireVoidCarrier(state);
            long result = CoreManagedFiles.current(node).seek(fd, offset, mode);
            destination.setLong(((BytecodeRoot) node.getRootNode()).getBytecodeNode(), frame, result);
        }
    }

    @Operation
    @ConstantOperand(type = LocalAccessor.class, name = "destination")
    public static final class FileSize {
        @Specialization public static void apply(VirtualFrame frame, LocalAccessor destination,
                long fd, Object state, @Bind("$node") Node node) {
            TupleResultsKt.requireVoidCarrier(state);
            long result = CoreManagedFiles.current(node).size(fd);
            destination.setLong(((BytecodeRoot) node.getRootNode()).getBytecodeNode(), frame, result);
        }
    }

    @Operation
    @ConstantOperand(type = LocalAccessor.class, name = "destination")
    public static final class FileSetSize {
        @Specialization public static void apply(VirtualFrame frame, LocalAccessor destination,
                long fd, long length, Object state, @Bind("$node") Node node) {
            TupleResultsKt.requireVoidCarrier(state);
            long result = CoreManagedFiles.current(node).setSize(fd, length);
            destination.setLong(((BytecodeRoot) node.getRootNode()).getBytecodeNode(), frame, result);
        }
    }

    @Operation
    @ConstantOperand(type = LocalAccessor.class, name = "destination")
    public static final class FileIsTerminal {
        @Specialization public static void apply(VirtualFrame frame, LocalAccessor destination,
                long fd, Object state, @Bind("$node") Node node) {
            TupleResultsKt.requireVoidCarrier(state);
            long result = CoreManagedFiles.current(node).isTerminal(fd);
            destination.setLong(((BytecodeRoot) node.getRootNode()).getBytecodeNode(), frame, result);
        }
    }

    @Operation
    @ConstantOperand(type = LocalAccessor.class, name = "destination")
    public static final class FileDeviceType {
        @Specialization public static void apply(VirtualFrame frame, LocalAccessor destination,
                long fd, Object state, @Bind("$node") Node node) {
            TupleResultsKt.requireVoidCarrier(state);
            long result = CoreManagedFiles.current(node).deviceType(fd);
            destination.setLong(((BytecodeRoot) node.getRootNode()).getBytecodeNode(), frame, result);
        }
    }

    @Operation
    public static final class Md5Init {
        @Specialization public static void apply(ManagedAddress context, Object state) {
            ManagedByteArray.requireState(state);
            ManagedMd5.INSTANCE.init(context);
        }
    }

    @Operation
    public static final class Md5Update {
        @Specialization public static void apply(ManagedAddress context, ManagedAddress input, long length, Object state) {
            ManagedByteArray.requireState(state);
            ManagedMd5.INSTANCE.update(context, input, length);
        }
    }

    @Operation
    public static final class Md5Final {
        @Specialization public static void apply(ManagedAddress output, ManagedAddress context, Object state) {
            ManagedByteArray.requireState(state);
            ManagedMd5.INSTANCE.finish(output, context);
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

    /** Only the function is a stack operand; aggregate fields stay in typed locals. */
    @Operation(forceCached = true)
    @ConstantOperand(type = BytecodeInputSource.class, name = "source")
    @ConstantOperand(type = boolean.class, name = "tail")
    @ConstantOperand(type = Metrics.class, name = "metrics")
    public static final class ApplyTypedInput {
        @Specialization public static Object apply(VirtualFrame frame, BytecodeInputSource source, boolean tail,
                Metrics metrics, Closure function, @Bind("$node") Node node,
                @Cached(value = "create(source, tail, metrics)", neverDefault = true) InputDispatch dispatch) {
            try {
                return dispatch.execute(frame, function, null);
            } catch (TailCall transfer) {
                if (!tail || !((GuestRoot) node.getRootNode()).isSelf(transfer.getTarget())) throw transfer;
                if (metrics.getEnabled()) metrics.incrementSelfTailReentries();
                return transfer;
            } finally {
                BytecodeTypedInputSlotsKt.clearBytecodeInputSource(source, frame, (BytecodeRoot) node.getRootNode());
            }
        }
        public static InputDispatch create(BytecodeInputSource source, boolean tail, Metrics metrics) {
            return new InputDispatch(source, source.getLayout().getLogicalArity(), tail, metrics, null, 0);
        }
    }

    @Operation(forceCached = true)
    @ConstantOperand(type = BytecodeInputSource.class, name = "source")
    @ConstantOperand(type = BytecodeTupleSlots.class, name = "destination")
    @ConstantOperand(type = boolean.class, name = "tail")
    @ConstantOperand(type = Metrics.class, name = "metrics")
    public static final class ApplyTypedInputTuple {
        @Specialization public static Object apply(VirtualFrame frame, BytecodeInputSource source,
                BytecodeTupleSlots destination, boolean tail, Metrics metrics, Closure function, @Bind("$node") Node node,
                @Cached(value = "create(source, destination, tail, metrics)", neverDefault = true) InputDispatch dispatch) {
            try {
                return dispatch.execute(frame, function, null);
            } catch (TailCall transfer) {
                if (!tail || !((GuestRoot) node.getRootNode()).isSelf(transfer.getTarget())) throw transfer;
                if (metrics.getEnabled()) metrics.incrementSelfTailReentries();
                return transfer;
            } finally {
                BytecodeTypedInputSlotsKt.clearBytecodeInputSource(source, frame, (BytecodeRoot) node.getRootNode());
            }
        }
        public static InputDispatch create(BytecodeInputSource source, BytecodeTupleSlots destination,
                boolean tail, Metrics metrics) {
            return new InputDispatch(source, source.getLayout().getLogicalArity(), tail, metrics, destination, 0);
        }
    }

    /** Invoke exactly one logical State argument, with a fence after real return/throw. */
    @Operation(forceCached = true)
    @ConstantOperand(type = Metrics.class, name = "metrics")
    public static final class KeepAlive {
        @Specialization public static Object apply(VirtualFrame frame, Metrics metrics,
                Object kept, Object state, Object function,
                @Cached(value = "create(metrics)", neverDefault = true) Dispatch dispatch,
                @Cached(value = "createForce(metrics)", neverDefault = true) Force force) {
            ManagedByteArray.requireState(state);
            try { return dispatch.execute(frame, RequireClosure.require(force.execute(frame, function)), new Object[]{kotlin.Unit.INSTANCE}); }
            finally { java.lang.ref.Reference.reachabilityFence(kept); }
        }
        public static Dispatch create(Metrics metrics) { return Dispatch.Companion.create(1, false, metrics); }
        public static Force createForce(Metrics metrics) { return new Force(metrics); }
    }

    @Operation(forceCached = true)
    @ConstantOperand(type = BytecodeTupleSlots.class, name = "destination")
    @ConstantOperand(type = Metrics.class, name = "metrics")
    public static final class KeepAliveTuple {
        @Specialization public static void apply(VirtualFrame frame, BytecodeTupleSlots destination,
                Metrics metrics, Object kept, Object state, Object function,
                @Cached(value = "create(destination, metrics)", neverDefault = true) TupleDispatch dispatch,
                @Cached(value = "createForce(metrics)", neverDefault = true) Force force) {
            ManagedByteArray.requireState(state);
            try { dispatch.execute(frame, RequireClosure.require(force.execute(frame, function)), new Object[]{kotlin.Unit.INSTANCE}); }
            finally { java.lang.ref.Reference.reachabilityFence(kept); }
        }
        public static TupleDispatch create(BytecodeTupleSlots destination, Metrics metrics) {
            return new TupleDispatch(destination, metrics, 1, false);
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
                if (metrics.getEnabled()) metrics.incrementSelfTailReentries();
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
                if (metrics.getEnabled()) metrics.incrementSelfTailReentries();
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
        @Specialization public static ManagedAddress require(Object value) {
            if (value instanceof ManagedAddress address) return address;
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
                if (metrics.getEnabled()) metrics.incrementSelfTailReentries();
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
                if (metrics.getEnabled()) metrics.incrementSelfTailReentries();
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
            metrics.incrementUnsupportedTraps();
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
            return throwGuest(payload, node);
        }
        @TruffleBoundary private static Object throwGuest(Object payload, Node node) { throw new GuestException(payload, node); }
    }

    @Operation
    public static final class RaiseIO {
        @Specialization public static void raise(Object payload, Object state, @Bind("$node") Node node) {
            TupleResultsKt.requireVoidCarrier(state);
            throw new GuestException(payload, node);
        }
    }

    @Operation public static final class RequireIOState {
        @Specialization public static void check(Object state) { TupleResultsKt.requireVoidCarrier(state); }
    }

    /** Called inside a DSL TryCatch; its typed tuple destination is unchanged. */
    @Operation(forceCached = true)
    @ConstantOperand(type = BytecodeTupleSlots.class, name = "destination")
    @ConstantOperand(type = Metrics.class, name = "metrics")
    public static final class InvokeIOAction {
        @Specialization public static void run(VirtualFrame frame, BytecodeTupleSlots destination, Metrics metrics,
                Object action, Object prior,
                @Cached(value = "createAction(destination, metrics)", neverDefault = true) TupleDispatch actionCall,
                @Cached(value = "createForce(metrics)", neverDefault = true) Force force) {
            try {
                actionCall.execute(frame, RequireClosure.require(force.execute(frame, action)),
                        new Object[]{kotlin.Unit.INSTANCE});
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

    /** The bytecode handler admits only synchronous Haskell guest exceptions. */
    @Operation public static final class RequireGuestFailure {
        @Specialization public static Object payload(AbstractTruffleException failure) {
            if (failure instanceof GuestException guest) return guest.getPayload();
            throw failure;
        }
    }

    @Operation(forceCached = true)
    @ConstantOperand(type = BytecodeTupleSlots.class, name = "destination")
    @ConstantOperand(type = Metrics.class, name = "metrics")
    public static final class InvokeIOHandler {
        @Specialization public static void run(VirtualFrame frame, BytecodeTupleSlots destination, Metrics metrics,
                Object handler, Object payload, MaskingState prior,
                @Cached(value = "createHandler(destination, metrics)", neverDefault = true) TupleDispatch handlerCall,
                @Cached(value = "createForce(metrics)", neverDefault = true) Force force) {
            try {
                handlerCall.execute(frame, RequireClosure.require(force.execute(frame, handler)),
                        new Object[]{payload, kotlin.Unit.INSTANCE});
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
            TupleResultsKt.requireVoidCarrier(state);
            destination.setLong(((BytecodeRoot) node.getRootNode()).getBytecodeNode(), frame,
                    SynchronousMasking.current(node).getTag());
        }
    }

    @Operation
    @ConstantOperand(type = LocalAccessor.class, name = "destination")
    public static final class GetCurrentCCS {
        @Specialization public static void run(VirtualFrame frame, LocalAccessor destination, Object state,
                @Bind("$node") Node node) {
            TupleResultsKt.requireVoidCarrier(state);
            destination.setObject(((BytecodeRoot) node.getRootNode()).getBytecodeNode(), frame,
                    ManagedAddress.Companion.nullAddress());
        }
    }

    /** Force already grants each suspended thunk one evaluator across guest threads. */
    @Operation public static final class NoDuplicate {
        @Specialization public static Object preserve(Object state) {
            TupleResultsKt.requireVoidCarrier(state);
            return kotlin.Unit.INSTANCE;
        }
    }

    @Operation public static final class AddressPlus {
        @Specialization public static ManagedAddress plus(ManagedAddress address, long displacement) { return address.plus(displacement); }
        @Fallback public static ManagedAddress invalid(Object address, Object displacement) {
            if (!(address instanceof ManagedAddress)) throw fail("Expected a managed literal Addr#");
            throw fail("Expected primitive Long");
        }
    }
    @Operation public static final class AddressIndexChar {
        @Specialization public static long index(ManagedAddress address, long displacement) { return address.indexChar(displacement); }
        @Fallback public static long invalid(Object address, Object displacement) {
            if (!(address instanceof ManagedAddress)) throw fail("Expected a managed literal Addr#");
            throw fail("Expected primitive Long");
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

    @Operation
    @ConstantOperand(type = LocalAccessor.class, name = "destination")
    public static final class NewMVar {
        @Specialization public static void create(VirtualFrame frame, LocalAccessor destination,
                Object state, @Bind("$node") Node node) {
            TupleResultsKt.requireVoidCarrier(state);
            destination.setObject(((BytecodeRoot) node.getRootNode()).getBytecodeNode(), frame, new ManagedMVar());
        }
    }
    @Operation
    @ConstantOperand(type = LocalAccessor.class, name = "destination")
    @ConstantOperand(type = boolean.class, name = "remove")
    public static final class ReadMVar {
        @Specialization public static void read(VirtualFrame frame, LocalAccessor destination, boolean remove,
                Object value, Object state, @Bind("$node") Node node) {
            ManagedMVar cell = ManagedMVar.require(value);
            TupleResultsKt.requireVoidCarrier(state);
            Object result = remove ? cell.take(node) : cell.read(node);
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
            TupleResultsKt.requireVoidCarrier(state);
            MVarReadResult result = remove ? cell.tryTake() : cell.tryRead();
            BytecodeNode bytecode = ((BytecodeRoot) node.getRootNode()).getBytecodeNode();
            flag.setLong(bytecode, frame, result.getPresent() ? 1L : 0L);
            destination.setObject(bytecode, frame, result.getValue());
        }
    }
    @Operation public static final class PutMVar {
        @Specialization public static Object put(Object reference, Object value, Object state,
                @Bind("$node") Node node) {
            ManagedMVar cell = ManagedMVar.require(reference);
            TupleResultsKt.requireVoidCarrier(state);
            cell.put(value, node);
            return kotlin.Unit.INSTANCE;
        }
    }
    @Operation
    @ConstantOperand(type = LocalAccessor.class, name = "destination")
    public static final class TryPutMVar {
        @Specialization public static void put(VirtualFrame frame, LocalAccessor destination,
                Object reference, Object value, Object state, @Bind("$node") Node node) {
            ManagedMVar cell = ManagedMVar.require(reference);
            TupleResultsKt.requireVoidCarrier(state);
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
            TupleResultsKt.requireVoidCarrier(state);
            long result = cell.isEmpty() ? 1L : 0L;
            destination.setLong(((BytecodeRoot) node.getRootNode()).getBytecodeNode(), frame, result);
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
            TupleResultsKt.requireVoidCarrier(state);
            ManagedArray.copy(from, sourceOffset, to, destinationOffset, count, mutableSource);
            return kotlin.Unit.INSTANCE;
        }
    }
    @Operation public static final class CloneArray {
        @Specialization public static Object clone(Object reference, long offset, long count) {
            return ManagedArray.slice(ManagedArray.require(reference), offset, count);
        }
    }
    @Operation
    @ConstantOperand(type = LocalAccessor.class, name = "destination")
    public static final class CopyArraySlice {
        @Specialization public static void copy(VirtualFrame frame, LocalAccessor destination,
                Object reference, long offset, long count, Object state, @Bind("$node") Node node) {
            Object[] array = ManagedArray.require(reference);
            TupleResultsKt.requireVoidCarrier(state);
            destination.setObject(((BytecodeRoot) node.getRootNode()).getBytecodeNode(), frame,
                    ManagedArray.slice(array, offset, count));
        }
    }

    @Operation
    @ConstantOperand(type = LocalAccessor.class, name = "destination")
    public static final class NewSmallArray {
        @Specialization public static void create(VirtualFrame frame, LocalAccessor destination,
                long size, Object initial, Object state, @Bind("$node") Node node) {
            TupleResultsKt.requireVoidCarrier(state);
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
            TupleResultsKt.requireVoidCarrier(state);
            destination.setObject(((BytecodeRoot) node.getRootNode()).getBytecodeNode(), frame,
                    ManagedSmallArray.read(array, index));
        }
    }
    @Operation public static final class WriteSmallArray {
        @Specialization public static Object write(Object reference, long index, Object value, Object state) {
            SmallArrayStorage array = ManagedSmallArray.require(reference);
            TupleResultsKt.requireVoidCarrier(state);
            ManagedSmallArray.write(array, index, value);
            return kotlin.Unit.INSTANCE;
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
    public static final class FreezeSmallArray {
        @Specialization public static void freeze(VirtualFrame frame, LocalAccessor destination,
                Object reference, Object state, @Bind("$node") Node node) {
            SmallArrayStorage array = ManagedSmallArray.require(reference);
            TupleResultsKt.requireVoidCarrier(state);
            destination.setObject(((BytecodeRoot) node.getRootNode()).getBytecodeNode(), frame,
                    ManagedSmallArray.freeze(array));
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
            TupleResultsKt.requireVoidCarrier(state);
            destination.setLong(((BytecodeRoot) node.getRootNode()).getBytecodeNode(), frame,
                    ManagedSmallArray.size(array));
        }
    }
    @Operation public static final class CloneSmallArray {
        @Specialization public static Object clone(Object reference, long offset, long count) {
            return ManagedSmallArray.slice(ManagedSmallArray.require(reference), offset, count);
        }
    }
    @Operation
    @ConstantOperand(type = LocalAccessor.class, name = "destination")
    public static final class CopySmallArraySlice {
        @Specialization public static void clone(VirtualFrame frame, LocalAccessor destination,
                Object reference, long offset, long count, Object state, @Bind("$node") Node node) {
            SmallArrayStorage array = ManagedSmallArray.require(reference);
            TupleResultsKt.requireVoidCarrier(state);
            destination.setObject(((BytecodeRoot) node.getRootNode()).getBytecodeNode(), frame,
                    ManagedSmallArray.slice(array, offset, count));
        }
    }
    @Operation
    @ConstantOperand(type = boolean.class, name = "mutableSource")
    public static final class TransferSmallArray {
        @Specialization public static Object copy(boolean mutableSource, Object source, long sourceOffset,
                Object destination, long destinationOffset, long count, Object state) {
            SmallArrayStorage from = ManagedSmallArray.require(source);
            SmallArrayStorage to = ManagedSmallArray.require(destination);
            TupleResultsKt.requireVoidCarrier(state);
            ManagedSmallArray.copy(from, sourceOffset, to, destinationOffset, count, mutableSource);
            return kotlin.Unit.INSTANCE;
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
    public static final class ResizeByteArray {
        @Specialization public static void resize(VirtualFrame frame, LocalAccessor destination,
                Object value, long size, Object state, @Bind("$node") Node node) {
            Object array = value;
            ManagedByteArray.requireState(state);
            Object result = ManagedByteArray.resizeGuest(array, size);
            destination.setObject(((BytecodeRoot) node.getRootNode()).getBytecodeNode(), frame, result);
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
        @Specialization public static Object write(Object value, long offset, long byteValue, Object state) {
            ManagedByteArray.requireState(state);
            ManagedByteArray.writeGuest(value, offset, byteValue);
            return kotlin.Unit.INSTANCE;
        }
    }
    @Operation public static final class CopyByteArray {
        @Specialization public static Object copy(Object source, long sourceOffset, Object destination,
                long destinationOffset, long count, Object state) {
            ManagedByteArray.requireState(state);
            ManagedByteArray.copyGuest(source, sourceOffset, destination, destinationOffset, count, false, false);
            return kotlin.Unit.INSTANCE;
        }
    }
    @Operation public static final class SetByteArray {
        @Specialization public static Object set(Object value, long offset, long count, long byteValue, Object state) {
            ManagedByteArray.requireState(state);
            ManagedByteArray.fillGuest(value, offset, count, byteValue);
            return kotlin.Unit.INSTANCE;
        }
    }
    @Operation
    @ConstantOperand(type = boolean.class, name = "nonOverlapping")
    public static final class CopyMutableByteArray {
        @Specialization public static Object copy(boolean nonOverlapping, Object source, long sourceOffset,
                Object destination, long destinationOffset, long count, Object state) {
            ManagedByteArray.requireState(state);
            ManagedByteArray.copyGuest(source, sourceOffset, destination, destinationOffset, count, true, nonOverlapping);
            return kotlin.Unit.INSTANCE;
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
    @ConstantOperand(type = boolean.class, name = "unsigned")
    @ConstantOperand(type = LocalAccessor.class, name = "destination")
    public static final class ReadByteArray {
        @Specialization public static void read(VirtualFrame frame, boolean unsigned, LocalAccessor destination,
                Object value, long index, Object state, @Bind("$node") Node node) {
            ManagedByteArray.requireState(state);
            long result = ManagedByteArray.readGuest(value, index, unsigned);
            destination.setLong(((BytecodeRoot) node.getRootNode()).getBytecodeNode(), frame, result);
        }
    }
    @Operation public static final class IndexSignedByteArray {
        @Specialization public static long index(Object value, long offset) {
            return ManagedByteArray.readGuest(value, offset, false);
        }
    }
    @Operation public static final class IndexByteArray {
        @Specialization public static long index(Object value, long offset) { return ManagedByteArray.readGuest(value, offset, true); }
    }
    @Operation
    @ConstantOperand(type = LocalAccessor.class, name = "destination")
    public static final class ReadIntArray {
        @Specialization public static void read(VirtualFrame frame, LocalAccessor destination,
                Object value, long index, Object state, @Bind("$node") Node node) {
            ManagedByteArray.requireState(state);
            long result = ManagedByteArray.readIntGuest(value, index);
            destination.setLong(((BytecodeRoot) node.getRootNode()).getBytecodeNode(), frame, result);
        }
    }
    @Operation @ConstantOperand(type = boolean.class, name = "scalarOffset")
    public static final class IndexVector32Array {
        @Specialization public static Int32X4 index(boolean scalarOffset, Object value, long index) {
            return Int32X4.readArray(ManagedByteArray.require(value), index, scalarOffset);
        }
    }
    @Operation @ConstantOperand(type = boolean.class, name = "scalarOffset")
    public static final class ReadVector32Array {
        @Specialization public static Int32X4 read(boolean scalarOffset, Object value, long index, Object state) {
            byte[] array = ManagedByteArray.require(value);
            ManagedByteArray.requireState(state);
            return Int32X4.readArray(array, index, scalarOffset);
        }
    }
    @Operation @ConstantOperand(type = boolean.class, name = "scalarOffset")
    public static final class WriteVector32Array {
        @Specialization public static Object write(boolean scalarOffset, Object value, long index, Int32X4 vector, Object state) {
            byte[] array = ManagedByteArray.require(value);
            ManagedByteArray.requireState(state);
            Int32X4.writeArray(array, index, vector, scalarOffset);
            return kotlin.Unit.INSTANCE;
        }
    }
    @Operation @ConstantOperand(type = boolean.class, name = "scalarOffset")
    public static final class IndexVectorWord32Array {
        @Specialization public static Word32X4 index(boolean scalarOffset, Object value, long index) {
            return Word32X4.readArray(ManagedByteArray.require(value), index, scalarOffset);
        }
    }
    @Operation @ConstantOperand(type = boolean.class, name = "scalarOffset")
    public static final class ReadVectorWord32Array {
        @Specialization public static Word32X4 read(boolean scalarOffset, Object value, long index, Object state) {
            byte[] array = ManagedByteArray.require(value);
            ManagedByteArray.requireState(state);
            return Word32X4.readArray(array, index, scalarOffset);
        }
    }
    @Operation @ConstantOperand(type = boolean.class, name = "scalarOffset")
    public static final class WriteVectorWord32Array {
        @Specialization public static Object write(boolean scalarOffset, Object value, long index, Word32X4 vector, Object state) {
            byte[] array = ManagedByteArray.require(value);
            ManagedByteArray.requireState(state);
            Word32X4.writeArray(array, index, vector, scalarOffset);
            return kotlin.Unit.INSTANCE;
        }
    }
    @Operation @ConstantOperand(type = boolean.class, name = "scalarOffset")
    public static final class IndexVectorFloatArray {
        @Specialization public static FloatX4 index(boolean scalarOffset, Object value, long index) {
            return FloatX4.readArray(ManagedByteArray.require(value), index, scalarOffset);
        }
    }
    @Operation @ConstantOperand(type = boolean.class, name = "scalarOffset")
    public static final class ReadVectorFloatArray {
        @Specialization public static FloatX4 read(boolean scalarOffset, Object value, long index, Object state) {
            byte[] array = ManagedByteArray.require(value);
            ManagedByteArray.requireState(state);
            return FloatX4.readArray(array, index, scalarOffset);
        }
    }
    @Operation @ConstantOperand(type = boolean.class, name = "scalarOffset")
    public static final class WriteVectorFloatArray {
        @Specialization public static Object write(boolean scalarOffset, Object value, long index, FloatX4 vector, Object state) {
            byte[] array = ManagedByteArray.require(value);
            ManagedByteArray.requireState(state);
            FloatX4.writeArray(array, index, vector, scalarOffset);
            return kotlin.Unit.INSTANCE;
        }
    }
    @Operation @ConstantOperand(type = boolean.class, name = "scalarOffset")
    public static final class IndexVectorDoubleArray {
        @Specialization public static DoubleX2 index(boolean scalarOffset, Object value, long index) {
            return DoubleX2.readArray(ManagedByteArray.require(value), index, scalarOffset);
        }
    }
    @Operation @ConstantOperand(type = boolean.class, name = "scalarOffset")
    public static final class ReadVectorDoubleArray {
        @Specialization public static DoubleX2 read(boolean scalarOffset, Object value, long index, Object state) {
            byte[] array = ManagedByteArray.require(value);
            ManagedByteArray.requireState(state);
            return DoubleX2.readArray(array, index, scalarOffset);
        }
    }
    @Operation @ConstantOperand(type = boolean.class, name = "scalarOffset")
    public static final class WriteVectorDoubleArray {
        @Specialization public static Object write(boolean scalarOffset, Object value, long index, DoubleX2 vector, Object state) {
            byte[] array = ManagedByteArray.require(value);
            ManagedByteArray.requireState(state);
            DoubleX2.writeArray(array, index, vector, scalarOffset);
            return kotlin.Unit.INSTANCE;
        }
    }
    @Operation public static final class WriteIntArray {
        @Specialization public static Object write(Object value, long index, long integer, Object state) {
            ManagedByteArray.requireState(state);
            ManagedByteArray.writeIntGuest(value, index, integer);
            return kotlin.Unit.INSTANCE;
        }
    }
    @Operation public static final class IndexIntArray {
        @Specialization public static long index(Object value, long index) {
            return ManagedByteArray.readIntGuest(value, index);
        }
    }
    @Operation
    @ConstantOperand(type = LocalAccessor.class, name = "destination")
    public static final class ReadDoubleArray {
        @Specialization public static void read(VirtualFrame frame, LocalAccessor destination,
                Object value, long index, Object state, @Bind("$node") Node node) {
            ManagedByteArray.requireState(state);
            double result = ManagedByteArray.readDoubleGuest(value, index);
            destination.setDouble(((BytecodeRoot) node.getRootNode()).getBytecodeNode(), frame, result);
        }
    }
    @Operation public static final class WriteDoubleArray {
        @Specialization public static Object write(Object value, long index, double number, Object state) {
            ManagedByteArray.requireState(state);
            ManagedByteArray.writeDoubleGuest(value, index, number);
            return kotlin.Unit.INSTANCE;
        }
    }
    @Operation public static final class IndexDoubleArray {
        @Specialization public static double index(Object value, long index) {
            return ManagedByteArray.readDoubleGuest(value, index);
        }
    }

    @Operation
    @ConstantOperand(type = LocalAccessor.class, name = "destination")
    public static final class ReadFloatArray {
        @Specialization public static void read(VirtualFrame frame, LocalAccessor destination,
                Object value, long index, Object state, @Bind("$node") Node node) {
            ManagedByteArray.requireState(state);
            float result = ManagedByteArray.readFloatGuest(value, index);
            destination.setFloat(((BytecodeRoot) node.getRootNode()).getBytecodeNode(), frame, result);
        }
    }
    @Operation public static final class WriteFloatArray {
        @Specialization public static Object write(Object value, long index, float number, Object state) {
            ManagedByteArray.requireState(state);
            ManagedByteArray.writeFloatGuest(value, index, number);
            return kotlin.Unit.INSTANCE;
        }
    }
    @Operation public static final class IndexFloatArray {
        @Specialization public static float index(Object value, long index) {
            return ManagedByteArray.readFloatGuest(value, index);
        }
    }

    @Operation
    @ConstantOperand(type = boolean.class, name = "unsigned")
    @ConstantOperand(type = LocalAccessor.class, name = "destination")
    public static final class ReadInt16Array {
        @Specialization public static void read(VirtualFrame frame, boolean unsigned, LocalAccessor destination,
                Object value, long index, Object state, @Bind("$node") Node node) {
            ManagedByteArray.requireState(state);
            long result = ManagedByteArray.readInt16Guest(value, index, unsigned);
            destination.setLong(((BytecodeRoot) node.getRootNode()).getBytecodeNode(), frame, result);
        }
    }
    @Operation public static final class WriteInt16Array {
        @Specialization public static Object write(Object value, long index, long integer, Object state) {
            ManagedByteArray.requireState(state);
            ManagedByteArray.writeInt16Guest(value, index, integer);
            return kotlin.Unit.INSTANCE;
        }
    }
    @Operation @ConstantOperand(type = boolean.class, name = "unsigned")
    public static final class IndexInt16Array {
        @Specialization public static long index(boolean unsigned, Object value, long index) {
            return ManagedByteArray.readInt16Guest(value, index, unsigned);
        }
    }

    @Operation
    @ConstantOperand(type = boolean.class, name = "unsigned")
    @ConstantOperand(type = LocalAccessor.class, name = "destination")
    public static final class ReadInt32Array {
        @Specialization public static void read(VirtualFrame frame, boolean unsigned, LocalAccessor destination,
                Object value, long index, Object state, @Bind("$node") Node node) {
            ManagedByteArray.requireState(state);
            long result = ManagedByteArray.readInt32Guest(value, index, unsigned);
            destination.setLong(((BytecodeRoot) node.getRootNode()).getBytecodeNode(), frame, result);
        }
    }
    @Operation public static final class WriteInt32Array {
        @Specialization public static Object write(Object value, long index, long integer, Object state) {
            ManagedByteArray.requireState(state);
            ManagedByteArray.writeInt32Guest(value, index, integer);
            return kotlin.Unit.INSTANCE;
        }
    }
    @Operation @ConstantOperand(type = boolean.class, name = "unsigned")
    public static final class IndexInt32Array {
        @Specialization public static long index(boolean unsigned, Object value, long index) {
            return ManagedByteArray.readInt32Guest(value, index, unsigned);
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

    @Operation public static final class VectorWord8Pack {
        @Specialization public static Word8X16 pack(long first, long second, long third, long fourth,
                long fifth, long sixth, long seventh, long eighth,
                long ninth, long tenth, long eleventh, long twelfth,
                long thirteenth, long fourteenth, long fifteenth, long sixteenth) {
            return new Word8X16((byte) first, (byte) second, (byte) third, (byte) fourth,
                (byte) fifth, (byte) sixth, (byte) seventh, (byte) eighth,
                (byte) ninth, (byte) tenth, (byte) eleventh, (byte) twelfth,
                (byte) thirteenth, (byte) fourteenth, (byte) fifteenth, (byte) sixteenth);
        }
    }
    @Operation public static final class VectorWord8Broadcast {
        @Specialization public static Word8X16 broadcast(long value) { return Word8X16.broadcast((byte) value); }
    }
    @Operation @ConstantOperand(type = int.class, name = "operation")
    public static final class VectorWord8Binary {
        @Specialization public static Word8X16 binary(int operation, Word8X16 first, Word8X16 second) {
            return switch (operation) {
                case 0 -> Word8X16.add(first, second);
                case 1 -> Word8X16.subtract(first, second);
                case 2 -> Word8X16.multiply(first, second);
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
                Word8X16 value, @Bind("$node") Node node) {
            BytecodeNode bytecode = ((BytecodeRoot) node.getRootNode()).getBytecodeNode();
            first.setLong(bytecode, frame, value.first & 0xffL); second.setLong(bytecode, frame, value.second & 0xffL);
            third.setLong(bytecode, frame, value.third & 0xffL); fourth.setLong(bytecode, frame, value.fourth & 0xffL);
            fifth.setLong(bytecode, frame, value.fifth & 0xffL); sixth.setLong(bytecode, frame, value.sixth & 0xffL);
            seventh.setLong(bytecode, frame, value.seventh & 0xffL); eighth.setLong(bytecode, frame, value.eighth & 0xffL);
            ninth.setLong(bytecode, frame, value.ninth & 0xffL); tenth.setLong(bytecode, frame, value.tenth & 0xffL);
            eleventh.setLong(bytecode, frame, value.eleventh & 0xffL); twelfth.setLong(bytecode, frame, value.twelfth & 0xffL);
            thirteenth.setLong(bytecode, frame, value.thirteenth & 0xffL); fourteenth.setLong(bytecode, frame, value.fourteenth & 0xffL);
            fifteenth.setLong(bytecode, frame, value.fifteenth & 0xffL); sixteenth.setLong(bytecode, frame, value.sixteenth & 0xffL);
        }
    }

    @Operation public static final class Vector8Pack {
        @Specialization public static Int8X16 pack(long first, long second, long third, long fourth,
                long fifth, long sixth, long seventh, long eighth,
                long ninth, long tenth, long eleventh, long twelfth,
                long thirteenth, long fourteenth, long fifteenth, long sixteenth) {
            return new Int8X16((byte) first, (byte) second, (byte) third, (byte) fourth,
                (byte) fifth, (byte) sixth, (byte) seventh, (byte) eighth,
                (byte) ninth, (byte) tenth, (byte) eleventh, (byte) twelfth,
                (byte) thirteenth, (byte) fourteenth, (byte) fifteenth, (byte) sixteenth);
        }
    }
    @Operation public static final class Vector8Broadcast {
        @Specialization public static Int8X16 broadcast(long value) { return Int8X16.broadcast((byte) value); }
    }
    @Operation public static final class Vector8Negate {
        @Specialization public static Int8X16 negate(Int8X16 value) { return Int8X16.negate(value); }
    }
    @Operation @ConstantOperand(type = int.class, name = "operation")
    public static final class Vector8Binary {
        @Specialization public static Int8X16 binary(int operation, Int8X16 first, Int8X16 second) {
            return switch (operation) {
                case 0 -> Int8X16.add(first, second);
                case 1 -> Int8X16.subtract(first, second);
                case 2 -> Int8X16.multiply(first, second);
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
                Int8X16 value, @Bind("$node") Node node) {
            BytecodeNode bytecode = ((BytecodeRoot) node.getRootNode()).getBytecodeNode();
            first.setLong(bytecode, frame, value.first); second.setLong(bytecode, frame, value.second);
            third.setLong(bytecode, frame, value.third); fourth.setLong(bytecode, frame, value.fourth);
            fifth.setLong(bytecode, frame, value.fifth); sixth.setLong(bytecode, frame, value.sixth);
            seventh.setLong(bytecode, frame, value.seventh); eighth.setLong(bytecode, frame, value.eighth);
            ninth.setLong(bytecode, frame, value.ninth); tenth.setLong(bytecode, frame, value.tenth);
            eleventh.setLong(bytecode, frame, value.eleventh); twelfth.setLong(bytecode, frame, value.twelfth);
            thirteenth.setLong(bytecode, frame, value.thirteenth); fourteenth.setLong(bytecode, frame, value.fourteenth);
            fifteenth.setLong(bytecode, frame, value.fifteenth); sixteenth.setLong(bytecode, frame, value.sixteenth);
        }
    }
    @Operation public static final class VectorWord32Pack {
        @Specialization public static Word32X4 pack(long first, long second, long third, long fourth) {
            return new Word32X4((int) first, (int) second, (int) third, (int) fourth);
        }
    }
    @Operation public static final class VectorWord32Broadcast {
        @Specialization public static Word32X4 broadcast(long value) { return Word32X4.broadcast((int) value); }
    }
    @Operation @ConstantOperand(type = int.class, name = "operation")
    public static final class VectorWord32Binary {
        @Specialization public static Word32X4 binary(int operation, Word32X4 first, Word32X4 second) {
            return switch (operation) {
                case 0 -> Word32X4.add(first, second);
                case 1 -> Word32X4.subtract(first, second);
                case 2 -> Word32X4.multiply(first, second);
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
                LocalAccessor third, LocalAccessor fourth, Word32X4 value, @Bind("$node") Node node) {
            BytecodeNode bytecode = ((BytecodeRoot) node.getRootNode()).getBytecodeNode();
            first.setLong(bytecode, frame, value.first & 0xffff_ffffL); second.setLong(bytecode, frame, value.second & 0xffff_ffffL);
            third.setLong(bytecode, frame, value.third & 0xffff_ffffL); fourth.setLong(bytecode, frame, value.fourth & 0xffff_ffffL);
        }
    }
    @Operation public static final class VectorWord16Pack {
        @Specialization public static Word16X8 pack(long first, long second, long third, long fourth,
                long fifth, long sixth, long seventh, long eighth) {
            return new Word16X8((short) first, (short) second, (short) third, (short) fourth,
                (short) fifth, (short) sixth, (short) seventh, (short) eighth);
        }
    }
    @Operation public static final class VectorWord16Broadcast {
        @Specialization public static Word16X8 broadcast(long value) { return Word16X8.broadcast((short) value); }
    }
    @Operation @ConstantOperand(type = int.class, name = "operation")
    public static final class VectorWord16Binary {
        @Specialization public static Word16X8 binary(int operation, Word16X8 first, Word16X8 second) {
            return switch (operation) {
                case 0 -> Word16X8.add(first, second);
                case 1 -> Word16X8.subtract(first, second);
                case 2 -> Word16X8.multiply(first, second);
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
                LocalAccessor seventh, LocalAccessor eighth, Word16X8 value, @Bind("$node") Node node) {
            BytecodeNode bytecode = ((BytecodeRoot) node.getRootNode()).getBytecodeNode();
            first.setLong(bytecode, frame, value.first & 0xffffL); second.setLong(bytecode, frame, value.second & 0xffffL);
            third.setLong(bytecode, frame, value.third & 0xffffL); fourth.setLong(bytecode, frame, value.fourth & 0xffffL);
            fifth.setLong(bytecode, frame, value.fifth & 0xffffL); sixth.setLong(bytecode, frame, value.sixth & 0xffffL);
            seventh.setLong(bytecode, frame, value.seventh & 0xffffL); eighth.setLong(bytecode, frame, value.eighth & 0xffffL);
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
    @Operation public static final class Vector32Multiply {
        @Specialization public static Int32X4 multiply(Int32X4 first, Int32X4 second) { return Int32X4.multiply(first, second); }
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
    @Operation public static final class DoubleEqual { @Specialization public static long apply(double x, double y) { return x == y ? 1L : 0L; } }
    @Operation public static final class DoubleNotEqual { @Specialization public static long apply(double x, double y) { return x != y ? 1L : 0L; } }
    @Operation public static final class DoubleLess { @Specialization public static long apply(double x, double y) { return x < y ? 1L : 0L; } }
    @Operation public static final class DoubleLessEqual { @Specialization public static long apply(double x, double y) { return x <= y ? 1L : 0L; } }
    @Operation public static final class DoubleGreater { @Specialization public static long apply(double x, double y) { return x > y ? 1L : 0L; } }
    @Operation public static final class DoubleGreaterEqual { @Specialization public static long apply(double x, double y) { return x >= y ? 1L : 0L; } }
    @Operation public static final class CastFloatToWord32 { @Specialization public static long apply(float value) { return RawBitCasts.floatToWord32(value); } }
    @Operation public static final class CastWord32ToFloat { @Specialization public static float apply(long value) { return RawBitCasts.word32ToFloat(value); } }
    @Operation public static final class CastDoubleToWord64 { @Specialization public static long apply(double value) { return RawBitCasts.doubleToWord64(value); } }
    @Operation public static final class CastWord64ToDouble { @Specialization public static double apply(long value) { return RawBitCasts.word64ToDouble(value); } }
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
    // BEGIN GENERATED SIMD FAMILIES
    @Operation public static final class GeneratedWord64X2Pack {
        @Specialization public static Word64X2 apply(long lane0, long lane1) { return new Word64X2(lane0, lane1); }
    }
    @Operation
    @ConstantOperand(type = LocalAccessor.class, name = "lane0")
    @ConstantOperand(type = LocalAccessor.class, name = "lane1")
    public static final class GeneratedWord64X2Unpack {
        @Specialization public static void apply(VirtualFrame frame, LocalAccessor lane0, LocalAccessor lane1, Word64X2 value, @Bind("$node") Node node) {
            BytecodeNode bytecode = ((BytecodeRoot) node.getRootNode()).getBytecodeNode();
            lane0.setLong(bytecode, frame, value.lane0);
            lane1.setLong(bytecode, frame, value.lane1);
        }
    }
    @Operation public static final class GeneratedWord64X2Broadcast {
        @Specialization public static Word64X2 apply(long value) { return Word64X2.broadcast(value); }
    }
    @Operation public static final class GeneratedWord64X2Plus {
        @Specialization public static Word64X2 apply(Word64X2 left, Word64X2 right) { return Word64X2.add(left, right); }
    }
    @Operation public static final class GeneratedWord64X2Minus {
        @Specialization public static Word64X2 apply(Word64X2 left, Word64X2 right) { return Word64X2.subtract(left, right); }
    }
    @Operation public static final class GeneratedWord64X2Times {
        @Specialization public static Word64X2 apply(Word64X2 left, Word64X2 right) { return Word64X2.multiply(left, right); }
    }
    @Operation public static final class GeneratedWord32X8Pack {
        @Specialization public static Word32X8 apply(long lane0, long lane1, long lane2, long lane3, long lane4, long lane5, long lane6, long lane7) { return new Word32X8((int) lane0, (int) lane1, (int) lane2, (int) lane3, (int) lane4, (int) lane5, (int) lane6, (int) lane7); }
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
        @Specialization public static void apply(VirtualFrame frame, LocalAccessor lane0, LocalAccessor lane1, LocalAccessor lane2, LocalAccessor lane3, LocalAccessor lane4, LocalAccessor lane5, LocalAccessor lane6, LocalAccessor lane7, Word32X8 value, @Bind("$node") Node node) {
            BytecodeNode bytecode = ((BytecodeRoot) node.getRootNode()).getBytecodeNode();
            lane0.setLong(bytecode, frame, value.lane0 & 0xffff_ffffL);
            lane1.setLong(bytecode, frame, value.lane1 & 0xffff_ffffL);
            lane2.setLong(bytecode, frame, value.lane2 & 0xffff_ffffL);
            lane3.setLong(bytecode, frame, value.lane3 & 0xffff_ffffL);
            lane4.setLong(bytecode, frame, value.lane4 & 0xffff_ffffL);
            lane5.setLong(bytecode, frame, value.lane5 & 0xffff_ffffL);
            lane6.setLong(bytecode, frame, value.lane6 & 0xffff_ffffL);
            lane7.setLong(bytecode, frame, value.lane7 & 0xffff_ffffL);
        }
    }
    @Operation public static final class GeneratedWord32X8Broadcast {
        @Specialization public static Word32X8 apply(long value) { return Word32X8.broadcast((int) value); }
    }
    @Operation public static final class GeneratedWord32X8Plus {
        @Specialization public static Word32X8 apply(Word32X8 left, Word32X8 right) { return Word32X8.add(left, right); }
    }
    @Operation public static final class GeneratedWord32X8Minus {
        @Specialization public static Word32X8 apply(Word32X8 left, Word32X8 right) { return Word32X8.subtract(left, right); }
    }
    @Operation public static final class GeneratedWord32X8Times {
        @Specialization public static Word32X8 apply(Word32X8 left, Word32X8 right) { return Word32X8.multiply(left, right); }
    }
    @Operation public static final class GeneratedInt32X8Pack {
        @Specialization public static Int32X8 apply(long lane0, long lane1, long lane2, long lane3, long lane4, long lane5, long lane6, long lane7) { return new Int32X8((int) lane0, (int) lane1, (int) lane2, (int) lane3, (int) lane4, (int) lane5, (int) lane6, (int) lane7); }
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
        @Specialization public static void apply(VirtualFrame frame, LocalAccessor lane0, LocalAccessor lane1, LocalAccessor lane2, LocalAccessor lane3, LocalAccessor lane4, LocalAccessor lane5, LocalAccessor lane6, LocalAccessor lane7, Int32X8 value, @Bind("$node") Node node) {
            BytecodeNode bytecode = ((BytecodeRoot) node.getRootNode()).getBytecodeNode();
            lane0.setLong(bytecode, frame, value.lane0);
            lane1.setLong(bytecode, frame, value.lane1);
            lane2.setLong(bytecode, frame, value.lane2);
            lane3.setLong(bytecode, frame, value.lane3);
            lane4.setLong(bytecode, frame, value.lane4);
            lane5.setLong(bytecode, frame, value.lane5);
            lane6.setLong(bytecode, frame, value.lane6);
            lane7.setLong(bytecode, frame, value.lane7);
        }
    }
    @Operation public static final class GeneratedInt32X8Broadcast {
        @Specialization public static Int32X8 apply(long value) { return Int32X8.broadcast((int) value); }
    }
    @Operation public static final class GeneratedInt32X8Plus {
        @Specialization public static Int32X8 apply(Int32X8 left, Int32X8 right) { return Int32X8.add(left, right); }
    }
    @Operation public static final class GeneratedInt32X8Minus {
        @Specialization public static Int32X8 apply(Int32X8 left, Int32X8 right) { return Int32X8.subtract(left, right); }
    }
    @Operation public static final class GeneratedInt32X8Times {
        @Specialization public static Int32X8 apply(Int32X8 left, Int32X8 right) { return Int32X8.multiply(left, right); }
    }
    @Operation public static final class GeneratedInt32X8Negate {
        @Specialization public static Int32X8 apply(Int32X8 value) { return Int32X8.negate(value); }
    }
    @Operation public static final class GeneratedInt32X16Pack {
        @Specialization public static Int32X16 apply(long lane0, long lane1, long lane2, long lane3, long lane4, long lane5, long lane6, long lane7, long lane8, long lane9, long lane10, long lane11, long lane12, long lane13, long lane14, long lane15) { return new Int32X16((int) lane0, (int) lane1, (int) lane2, (int) lane3, (int) lane4, (int) lane5, (int) lane6, (int) lane7, (int) lane8, (int) lane9, (int) lane10, (int) lane11, (int) lane12, (int) lane13, (int) lane14, (int) lane15); }
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
    @ConstantOperand(type = LocalAccessor.class, name = "lane8")
    @ConstantOperand(type = LocalAccessor.class, name = "lane9")
    @ConstantOperand(type = LocalAccessor.class, name = "lane10")
    @ConstantOperand(type = LocalAccessor.class, name = "lane11")
    @ConstantOperand(type = LocalAccessor.class, name = "lane12")
    @ConstantOperand(type = LocalAccessor.class, name = "lane13")
    @ConstantOperand(type = LocalAccessor.class, name = "lane14")
    @ConstantOperand(type = LocalAccessor.class, name = "lane15")
    public static final class GeneratedInt32X16Unpack {
        @Specialization public static void apply(VirtualFrame frame, LocalAccessor lane0, LocalAccessor lane1, LocalAccessor lane2, LocalAccessor lane3, LocalAccessor lane4, LocalAccessor lane5, LocalAccessor lane6, LocalAccessor lane7, LocalAccessor lane8, LocalAccessor lane9, LocalAccessor lane10, LocalAccessor lane11, LocalAccessor lane12, LocalAccessor lane13, LocalAccessor lane14, LocalAccessor lane15, Int32X16 value, @Bind("$node") Node node) {
            BytecodeNode bytecode = ((BytecodeRoot) node.getRootNode()).getBytecodeNode();
            lane0.setLong(bytecode, frame, value.lane0);
            lane1.setLong(bytecode, frame, value.lane1);
            lane2.setLong(bytecode, frame, value.lane2);
            lane3.setLong(bytecode, frame, value.lane3);
            lane4.setLong(bytecode, frame, value.lane4);
            lane5.setLong(bytecode, frame, value.lane5);
            lane6.setLong(bytecode, frame, value.lane6);
            lane7.setLong(bytecode, frame, value.lane7);
            lane8.setLong(bytecode, frame, value.lane8);
            lane9.setLong(bytecode, frame, value.lane9);
            lane10.setLong(bytecode, frame, value.lane10);
            lane11.setLong(bytecode, frame, value.lane11);
            lane12.setLong(bytecode, frame, value.lane12);
            lane13.setLong(bytecode, frame, value.lane13);
            lane14.setLong(bytecode, frame, value.lane14);
            lane15.setLong(bytecode, frame, value.lane15);
        }
    }
    @Operation public static final class GeneratedInt32X16Broadcast {
        @Specialization public static Int32X16 apply(long value) { return Int32X16.broadcast((int) value); }
    }
    @Operation public static final class GeneratedInt32X16Plus {
        @Specialization public static Int32X16 apply(Int32X16 left, Int32X16 right) { return Int32X16.add(left, right); }
    }
    @Operation public static final class GeneratedInt32X16Minus {
        @Specialization public static Int32X16 apply(Int32X16 left, Int32X16 right) { return Int32X16.subtract(left, right); }
    }
    @Operation public static final class GeneratedInt32X16Times {
        @Specialization public static Int32X16 apply(Int32X16 left, Int32X16 right) { return Int32X16.multiply(left, right); }
    }
    @Operation public static final class GeneratedInt32X16Negate {
        @Specialization public static Int32X16 apply(Int32X16 value) { return Int32X16.negate(value); }
    }
    @Operation public static final class GeneratedInt64X2Times {
        @Specialization public static Int64X2 apply(Int64X2 left, Int64X2 right) { return Int64X2.multiply(left, right); }
    }
    @Operation public static final class GeneratedFloatX4Negate {
        @Specialization public static FloatX4 apply(FloatX4 value) { return FloatX4.negate(value); }
    }
    @Operation public static final class GeneratedFloatX4Divide {
        @Specialization public static FloatX4 apply(FloatX4 left, FloatX4 right) { return FloatX4.divide(left, right); }
    }
    @Operation public static final class GeneratedDoubleX2Negate {
        @Specialization public static DoubleX2 apply(DoubleX2 value) { return DoubleX2.negate(value); }
    }
    @Operation public static final class GeneratedDoubleX2Divide {
        @Specialization public static DoubleX2 apply(DoubleX2 left, DoubleX2 right) { return DoubleX2.divide(left, right); }
    }
    @Operation public static final class GeneratedFloatX8Pack {
        @Specialization public static FloatX8 apply(float lane0, float lane1, float lane2, float lane3, float lane4, float lane5, float lane6, float lane7) { return new FloatX8(lane0, lane1, lane2, lane3, lane4, lane5, lane6, lane7); }
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
        @Specialization public static void apply(VirtualFrame frame, LocalAccessor lane0, LocalAccessor lane1, LocalAccessor lane2, LocalAccessor lane3, LocalAccessor lane4, LocalAccessor lane5, LocalAccessor lane6, LocalAccessor lane7, FloatX8 value, @Bind("$node") Node node) {
            BytecodeNode bytecode = ((BytecodeRoot) node.getRootNode()).getBytecodeNode();
            lane0.setFloat(bytecode, frame, value.lane0);
            lane1.setFloat(bytecode, frame, value.lane1);
            lane2.setFloat(bytecode, frame, value.lane2);
            lane3.setFloat(bytecode, frame, value.lane3);
            lane4.setFloat(bytecode, frame, value.lane4);
            lane5.setFloat(bytecode, frame, value.lane5);
            lane6.setFloat(bytecode, frame, value.lane6);
            lane7.setFloat(bytecode, frame, value.lane7);
        }
    }
    @Operation public static final class GeneratedFloatX8Broadcast {
        @Specialization public static FloatX8 apply(float value) { return FloatX8.broadcast(value); }
    }
    @Operation public static final class GeneratedFloatX8Plus {
        @Specialization public static FloatX8 apply(FloatX8 left, FloatX8 right) { return FloatX8.add(left, right); }
    }
    @Operation public static final class GeneratedFloatX8Minus {
        @Specialization public static FloatX8 apply(FloatX8 left, FloatX8 right) { return FloatX8.subtract(left, right); }
    }
    @Operation public static final class GeneratedFloatX8Times {
        @Specialization public static FloatX8 apply(FloatX8 left, FloatX8 right) { return FloatX8.multiply(left, right); }
    }
    @Operation public static final class GeneratedFloatX8Negate {
        @Specialization public static FloatX8 apply(FloatX8 value) { return FloatX8.negate(value); }
    }
    @Operation public static final class GeneratedFloatX8Divide {
        @Specialization public static FloatX8 apply(FloatX8 left, FloatX8 right) { return FloatX8.divide(left, right); }
    }
    @Operation public static final class GeneratedDoubleX4Pack {
        @Specialization public static DoubleX4 apply(double lane0, double lane1, double lane2, double lane3) { return new DoubleX4(lane0, lane1, lane2, lane3); }
    }
    @Operation
    @ConstantOperand(type = LocalAccessor.class, name = "lane0")
    @ConstantOperand(type = LocalAccessor.class, name = "lane1")
    @ConstantOperand(type = LocalAccessor.class, name = "lane2")
    @ConstantOperand(type = LocalAccessor.class, name = "lane3")
    public static final class GeneratedDoubleX4Unpack {
        @Specialization public static void apply(VirtualFrame frame, LocalAccessor lane0, LocalAccessor lane1, LocalAccessor lane2, LocalAccessor lane3, DoubleX4 value, @Bind("$node") Node node) {
            BytecodeNode bytecode = ((BytecodeRoot) node.getRootNode()).getBytecodeNode();
            lane0.setDouble(bytecode, frame, value.lane0);
            lane1.setDouble(bytecode, frame, value.lane1);
            lane2.setDouble(bytecode, frame, value.lane2);
            lane3.setDouble(bytecode, frame, value.lane3);
        }
    }
    @Operation public static final class GeneratedDoubleX4Broadcast {
        @Specialization public static DoubleX4 apply(double value) { return DoubleX4.broadcast(value); }
    }
    @Operation public static final class GeneratedDoubleX4Plus {
        @Specialization public static DoubleX4 apply(DoubleX4 left, DoubleX4 right) { return DoubleX4.add(left, right); }
    }
    @Operation public static final class GeneratedDoubleX4Minus {
        @Specialization public static DoubleX4 apply(DoubleX4 left, DoubleX4 right) { return DoubleX4.subtract(left, right); }
    }
    @Operation public static final class GeneratedDoubleX4Times {
        @Specialization public static DoubleX4 apply(DoubleX4 left, DoubleX4 right) { return DoubleX4.multiply(left, right); }
    }
    @Operation public static final class GeneratedDoubleX4Negate {
        @Specialization public static DoubleX4 apply(DoubleX4 value) { return DoubleX4.negate(value); }
    }
    @Operation public static final class GeneratedDoubleX4Divide {
        @Specialization public static DoubleX4 apply(DoubleX4 left, DoubleX4 right) { return DoubleX4.divide(left, right); }
    }
    // END GENERATED SIMD FAMILIES
}
