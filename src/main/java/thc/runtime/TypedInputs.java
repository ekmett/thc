// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.oracle.truffle.api.nodes.Node;
import thc.Language;
import java.util.Arrays;
import java.util.function.Function;
import static thc.runtime.RuntimeServiceStatus.fault;

public final class TypedInputs {
    private TypedInputs() {}
    private static final ScalarArrayInputSource scalarPrefixSource = new ScalarArrayInputSource(null);
    @CompilerDirectives.TruffleBoundary public static void discardTypedInput(Language language, HandoffStorage input) {
        switch (input.getInputMode()) {
            case 1 -> language.getHandoffState().get().getArguments().release(input);
            case 2, 3 -> input.getLayout().clearReferences(input);
        }
        input.setInputMode(0);
    }
    public static void writeInputReference(VirtualFrame frame, int slot, Object value) { FrameAccess.writeObject(frame, slot, value); }
    public static boolean supportsTypedSelf(ArgumentLayout formal, boolean[] strict, ArgumentLayout actual) {
        if (formal == null || !formal.getRequiresTyped() || formal.getLogicalArity() != actual.getLogicalArity()) return false;
        for (int i = 0; i < strict.length; i++) if (strict[i] && !formal.isTyped(i) && !actual.proof(i).getEvaluated()) return false;
        return true;
    }
    public static void copyInputFields(HandoffStorage source, HandoffStorage destination, int sourceOffset, int targetOffset, int count) {
        copyInputFields(source, destination, sourceOffset, targetOffset, count, source.getLayout(), destination.getLayout());
    }
    @ExplodeLoop public static void copyInputFields(HandoffStorage source, HandoffStorage destination, int sourceOffset, int targetOffset,
            int count, HandoffLayout from, HandoffLayout into) {
        for (int i = 0; i < count; i++) {
            int s = sourceOffset + i, d = targetOffset + i;
            if (into.isInt(d)) into.setInt(destination, d, from.getInt(source, s));
            else if (into.isLong(d)) into.setLong(destination, d, from.getLong(source, s));
            else if (into.isFloat(d)) into.setFloat(destination, d, from.getFloat(source, s));
            else if (into.isDouble(d)) into.setDouble(destination, d, from.getDouble(source, s));
            else into.setObject(destination, d, from.getObject(source, s));
        }
    }
    public static Closure typedPap(Closure function, TypedInputLayout input, InputSource source,
            VirtualFrame frame, Node node, Object[] values, int offset, int count, int oldCount, int remainingArity) {
        check(count < remainingArity);
        int prefixWidth = input.getLogical().offset(oldCount);
        int sourceOffset = ArgumentLayout.offset(source.getLayout(), offset);
        int sourceWidth = ArgumentLayout.offset(source.getLayout(), offset + count) - sourceOffset;
        HandoffLayout layout = input.prefix(oldCount + count);
        HandoffStorage storage = layout.create();
        if (function.typedSupplied != null) copyInputFields(function.typedSupplied, storage, 0, 0, prefixWidth, input.prefix(oldCount), layout);
        else {
            check(prefixWidth == function.supplied.length);
            scalarPrefixSource.copy(frame, node, function.supplied, 0, storage, 0, prefixWidth, layout);
        }
        source.copy(frame, node, values, sourceOffset, storage, prefixWidth, sourceWidth, layout);
        return new Closure(function.environment, Closure.NO_PAP_ARGUMENTS, remainingArity - count, function.target, oldCount + count, storage);
    }
    // Functional entry points for non-node callers.
    // Hot direct and indirect call sites inline these ownership scopes explicitly.
    public static Object invokeTypedInput(RootCallTarget target, HandoffStorage input, Function<Object[], Object> action) {
        var root = target.getRootNode();
        if (root == null) {
            CompilerDirectives.transferToInterpreter();
            throw new NullPointerException("null cannot be cast to non-null type thc.runtime.GuestRoot");
        }
        TypedInputLayout layout = ((GuestRoot) root).getTypedInput();
        if (layout == null) throw fault("Target has no typed input entry");
        long generation = input.getGeneration();
        try { return action.apply(new Object[] {input}); }
        finally { GenericTypedInputs.releaseGenericInput(layout, input, generation); }
    }
    public static Object invokeTypedInput(TypedInputLayout layout, HandoffStorage input, Function<Object[], Object> action) {
        long generation = input.getGeneration();
        try { return action.apply(new Object[] {input}); }
        finally { layout.releaseIfOwned(input, generation); }
    }
    @ExplodeLoop static HandoffStorage prepareInput(VirtualFrame frame, Node node, Closure function, TypedInputLayout input,
            InputSource source, Object[] values, int logicalOffset, int count, Force force, int prefixCount, int[] strictPositions) {
        int prefixWidth = input.getLogical().offset(prefixCount);
        boolean strictPrefix = false;
        for (int i : strictPositions) if (i < prefixCount) strictPrefix = true;
        Object[] overrides = strictPrefix ? new Object[prefixWidth] : null;
        for (int i : strictPositions) {
            int physical = input.getLogical().offset(i);
            if (i < prefixCount) {
                HandoffStorage supplied = function.typedSupplied;
                Object raw = supplied != null ? input.prefix(prefixCount).getObject(supplied, physical) : function.supplied[physical];
                overrides[physical] = force.execute(frame, raw);
            } else {
                int position = ArgumentLayout.offset(source.getLayout(), logicalOffset + i - prefixCount);
                CoreRepresentation actual = source.getPhysicalProofs() == null ? null : source.getPhysicalProofs()[position];
                if (actual != null && (actual.isInt() || actual.isLong() || actual.isFloat() || actual.isDouble())) continue;
                source.setReference(frame, node, values, position, force.execute(frame, source.reference(frame, node, values, position)));
            }
        }
        HandoffStorage loan;
        if (CompilerDirectives.inCompiledCode()) { loan = input.getPacket().create(); loan.setInputMode(2); }
        else { loan = input.state().getArguments().acquire(input.getPacket()); loan.setInputMode(1); }
        try {
            input.getPacket().setLong(loan, 0, 0L);
            if (input.getHasEnvironment()) input.getPacket().setObject(loan, 1, function.environment);
            if (function.typedSupplied != null)
                copyInputFields(function.typedSupplied, loan, 0, input.getHeader(), prefixWidth, input.prefix(prefixCount), input.getPacket());
            else {
                check(function.supplied.length == prefixWidth);
                scalarPrefixSource.copy(frame, node, function.supplied, 0, loan, input.getHeader(), prefixWidth, input.getPacket());
            }
            int from = ArgumentLayout.offset(source.getLayout(), logicalOffset);
            int width = ArgumentLayout.offset(source.getLayout(), logicalOffset + count) - from;
            source.copy(frame, node, values, from, loan, input.getHeader() + prefixWidth, width, input.getPacket());
            if (overrides != null) for (int i : strictPositions) if (i < prefixCount) {
                int physical = input.getLogical().offset(i);
                input.getPacket().setObject(loan, input.getHeader() + physical, overrides[physical]);
            }
            return loan;
        } catch (Throwable failure) { input.release(loan); throw failure; }
    }
    @CompilerDirectives.TruffleBoundary public static int[] strictInputPositions(GuestRoot root, TypedInputLayout input) {
        if (root instanceof BytecodeRoot bytecode && bytecode.isAsyncEnabled() ||
            root instanceof FunctionRoot ast && ast.getCapturesContinuations$org_intelligence_thc()) return new int[0];
        boolean[] strict = root.getEntryStrict();
        int[] positions = new int[strict.length];
        int count = 0;
        for (int i = 0; i < strict.length; i++) if (strict[i] && !input.getLogical().isTyped(i) &&
            input.getPacket().isObject(input.getHeader() + input.getLogical().offset(i))) positions[count++] = i;
        return Arrays.copyOf(positions, count);
    }
    public static void checkInputResult(GuestRoot root, TupleDestination destination, boolean exact) {
        if (exact && destination == null && root.getTupleResult() != null) throw fault("Aggregate call target requires a typed result destination");
        if (exact && destination != null && (root.getTupleResult() == null || !root.getTupleResult().matches(destination.getShape())))
            throw fault("Aggregate call target result shape mismatch");
        if (!exact && root.getTupleResult() != null) throw fault("Cannot overapply an unboxed aggregate");
    }
    static void checkTypedTail(VirtualFrame frame, Node node, RootCallTarget target, HandoffStorage loan, HandoffLayout packet, Metrics metrics) {
        GuestRoot source = node.getRootNode() instanceof GuestRoot root ? root : null;
        GuestRoot targetRoot = ColdCallChecks.guestRoot(target.getRootNode());
        long mask = source == null ? 0L : source.bloom(frame);
        if (source == null || (mask & targetRoot.mask) == targetRoot.mask) {
            if (metrics.getEnabled()) {
                metrics.incrementTailBounces();
                HandoffState state = ColdCallChecks.typedInput(targetRoot.getTypedInput()).state();
                state.setTailTransfers(state.getTailTransfers() + 1);
            }
            if (loan.getInputMode() == 2) loan.setInputMode(3);
            throw new TailCall(target, Closure.NO_PAP_ARGUMENTS, loan);
        }
        packet.setLong(loan, 0, mask);
    }
    @ExplodeLoop private static Object[] scalarValues(VirtualFrame frame, Node node, InputSource source, Object[] values, int start, int count) {
        Object[] result = new Object[ArgumentLayout.offset(source.getLayout(), start + count) - ArgumentLayout.offset(source.getLayout(), start)];
        int to = 0;
        for (int i = start; i < start + count; i++) {
            CoreRepresentation proof = source.getLayout() == null ? null : source.getLayout().proof(i);
            if (proof != null && proof.isEmptyTuple()) continue;
            if (proof != null && proof.isTypedTransport()) throw fault("Typed input cannot enter a scalar packet");
            int from = ArgumentLayout.offset(source.getLayout(), i);
            if (proof != null && proof.isLong()) result[to++] = source.readLong(frame, node, values, from);
            else if (proof != null && proof.isFloat()) result[to++] = source.readFloat(frame, node, values, from);
            else if (proof != null && proof.isDouble()) result[to++] = source.readDouble(frame, node, values, from);
            else result[to++] = source.reference(frame, node, values, from);
        }
        return result;
    }
    static Object[] scalarPacket(VirtualFrame frame, Node node, Closure function, InputSource source, Object[] values, int start, int count, int genericMaximum) {
        check(function.typedSupplied == null);
        Object[] args = genericMaximum >= 0 ? GenericTypedInputs.genericScalarValues(frame, node, source, values, genericMaximum, start, count) :
            scalarValues(frame, node, source, values, start, count);
        int skip = function.environment == null ? 1 : 2;
        Object[] packet = new Object[skip + function.supplied.length + args.length];
        if (function.environment != null) packet[1] = function.environment;
        System.arraycopy(function.supplied, 0, packet, skip, function.supplied.length);
        System.arraycopy(args, 0, packet, skip + function.supplied.length, args.length);
        return packet;
    }
    static Closure legacyPap(VirtualFrame frame, Node node, Closure function, InputSource source, Object[] values, int start, int count) {
        Object[] args = scalarValues(frame, node, source, values, start, count);
        return function.papCompact(args, 0, args.length, count);
    }
    static Closure legacyGenericPap(VirtualFrame frame, Node node, Closure function, InputSource source, Object[] values, int maximum, int offset, int count) {
        Object[] args = GenericTypedInputs.genericScalarValues(frame, node, source, values, maximum, offset, count);
        return function.papCompact(args, 0, args.length, count);
    }
    private static void check(boolean condition) { if (!condition) throw new IllegalStateException("Check failed."); }
}
