// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.RootNode;
import java.util.List;
import thc.Language;
/** Exact program-owned Ptr/Int32 layouts preserve constructor case identity. */
public final class SignalDispatchRoot extends ContextRoot {
    private static final String POINTER = "ghc-internal:GHC.Internal.Ptr.Ptr";
    private static final String SIGNAL = "ghc-internal:GHC.Internal.Int.I32#";
    private final Object action;
    private final DataLayout pointer, signal;
    private final CoreRepresentation result;
    private final TupleShape shape;
    @Child private Force force = new Force(new Metrics(false), true);
    @Child private Force unitForce = new Force(new Metrics(false), true);
    @Child private TupleDispatch dispatch;
    public SignalDispatchRoot(Language language, ExecutableProgram program) {
        super(language, new FrameLayout().build());
        action = program.entryValue(CoreSignalForeign.dispatcher);
        prepareLayouts(program);
        pointer = program.constructorLayout(POINTER);
        signal = program.constructorLayout(SIGNAL);
        result = new CoreRepresentation(CoreKind.UNKNOWN, false, true, List.of("BoxedRep (Just Lifted)"), List.of(
            new CoreRepresentation(CoreKind.VOID, true, true, List.of(), null, null, null, null, null),
            new CoreRepresentation(CoreKind.DATA, false, true, List.of("BoxedRep (Just Lifted)"), null, null, null, null, null)), null, null, null, null);
        shape = new TupleShape(result, language);
        dispatch = new TupleDispatch(new IoResultDestination(shape, language, true), new Metrics(false), 3, false);
    }
    /** The service boxes these arguments even when the guest dispatcher ignores them. */
    static void prepareLayouts(ExecutableProgram program) {
        var pointer = program.constructorLayout(POINTER);
        var signal = program.constructorLayout(SIGNAL);
        if (pointer.getArity() != 1 || !pointer.hasFieldRepresentation(0, "AddrRep") || signal.getArity() != 1 || !signal.hasFieldRepresentation(0, "Int32Rep"))
            throw RuntimeFault.fault("Original signal dispatcher requires exact Ptr/Int32 constructor layouts");
    }
    @Override public Object execute(VirtualFrame frame) {
        frame.setLong(FrameLayout.BLOOM_FILTER, 0L);
        var closure = Applications.requireClosure(force.execute(frame, action));
        var target = closure.target.getRootNode();
        boolean captures = switch (target) { case FunctionRoot function -> function.getCapturesContinuations$org_intelligence_thc();
            case BytecodeRoot bytecode -> bytecode.isAsyncEnabled(); default -> false; };
        if (!captures || !(target instanceof GuestRoot guest) || guest.getTupleResult() == null || !guest.getTupleResult().matches(shape))
            throw RuntimeFault.fault("Signal dispatcher requires a continuation-capable IO unit tuple");
        var boxedPointer = pointer.create(new Object[]{frame.getArguments()[0]});
        var number = frame.getArguments()[1];
        if (number == null) {
            CompilerDirectives.transferToInterpreter();
            throw new NullPointerException("Signal number must not be null");
        }
        var boxedSignal = signal.createInt(((Long) number).intValue());
        dispatch.execute(frame, closure, new Object[]{boxedPointer, boxedSignal, thc.runtime.Unit.INSTANCE});
        if (!(unitForce.execute(frame, frame.getObject(FrameLayout.TAIL_RESULT)) instanceof DataValue unit))
            throw RuntimeFault.fault("Signal dispatcher did not return boxed unit");
        validateUnit(unit); return thc.runtime.Unit.INSTANCE;
    }
    @TruffleBoundary private void validateUnit(DataValue unit) {
        if (!"ghc-internal:GHC.Internal.Tuple.()".equals(unit.getLayout().getId()) || unit.getLayout().getArity() != 0)
            throw RuntimeFault.fault("Signal dispatcher did not return boxed unit");
    }
    @Override public String getName() { return "THC original process signal dispatcher"; }
}
