// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;
import com.oracle.truffle.api.frame.MaterializedFrame;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.ControlFlowException;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.bytecode.ContinuationResult;
import java.util.List;
import java.util.Map;
import thc.Language;
import static thc.runtime.RuntimeServiceStatus.fault;

public final class DelimitedControl {
    public static final DelimitedControl INSTANCE = new DelimitedControl();
    private DelimitedControl() {}
    public static AsyncDelivery asyncFailure(AstCapture cut, Node node) {
        AsyncRequest request = cut.asyncRequest();
        if (request != null) return new AsyncDelivery(request, node);
        throw new UnsupportedCore("Delimited continuation encountered a non-delivery asynchronous scheduling cut");
    }
    public static void asyncResult(Object result, Node node) {
        SavedGuestContinuation saved = switch (result) {
            case TailYield tail -> SavedGuestContinuations.savedGuestContinuation(tail.getContinuation());
            case AstTailYield tail -> tail.getContinuation();
            case null, default -> SavedGuestContinuations.savedGuestContinuation(result);
        };
        if (saved == null) return;
        AsyncRequest request = saved.asyncRequest();
        if (request != null) throw new AsyncDelivery(request, node);
        throw new UnsupportedCore("Delimited continuation encountered a non-delivery asynchronous scheduling cut");
    }
    @TruffleBoundary public static DelimitedCut tupleCut(DelimitedCut cut, MaterializedFrame frame, TupleDestination destination, Node node) {
        return tupleCut(cut, frame, destination, node, destination.getShape());
    }
    @TruffleBoundary public static DelimitedCut tupleCut(DelimitedCut cut, MaterializedFrame frame, TupleDestination destination, Node node, TupleShape producer) {
        DelimitedFrame pending = cut.getFrames().isEmpty() ? null : cut.getFrames().getLast();
        if (destination instanceof AstTupleDestination && !(pending != null && pending.getFrame() == frame &&
            pending.getStep() instanceof DelimitedPendingApplication application && application.getDestination() == destination))
            cut.append(frame, new DelimitedTupleStep(destination, node, producer));
        return cut;
    }
    public static boolean enabled(Node node) { return node.getRootNode() instanceof GuestRoot root && root.getDelimitedControlEnabled(); }
    @TruffleBoundary public static boolean rootEnabled(GuestRoot root) {
        return switch (root) {
            case FunctionRoot ast -> ast.getEnableDelimited$org_intelligence_thc();
            case BytecodeRoot bytecode -> bytecode.isDelimitedEnabled();
            case DelimitedContinuationRoot _ -> true;
            default -> false;
        };
    }
    public static boolean contains(Object value) {
        if (value instanceof Map<?, ?> map) {
            for (Object item : map.values()) if (contains(item)) return true;
        } else if (value instanceof List<?> list) {
            if (list.size() >= 2 && "prim".equals(list.get(0)) && ("prompt#".equals(list.get(1)) || "control0#".equals(list.get(1)))) return true;
            for (Object item : list) if (contains(item)) return true;
        }
        return false;
    }
    private static boolean state(CoreRepresentation proof) { return proof.getKind() == CoreKind.VOID && !proof.isAggregate() && !proof.isVector(); }
    public static void validate(String name, List<CoreRepresentation> arguments, List<?> flags, CoreRepresentation result) {
        List<CoreRepresentation> fields = result.getComponents();
        int arity = name.equals("newPromptTag#") ? 1 : 3;
        if (arguments.size() != arity || !flags.equals(arity == 1 ? List.of(false) : List.of(false, true, false)) ||
            !state(arguments.getLast()) || !result.isTuple() || fields == null || fields.size() != 2 || !state(fields.getFirst()) ||
            arity == 3 && (arguments.get(0).getKind() != CoreKind.OBJECT || arguments.get(1).getKind() != CoreKind.CLOSURE) ||
            name.equals("newPromptTag#") && fields.get(1).getKind() != CoreKind.OBJECT)
            throw new RuntimeFault(name + ": expected prompt identity, action, State# and tuple carriers");
        TupleShape.Companion.validate(result);
    }
    public static PromptTag tag(Node node, Object value) {
        if (!(value instanceof PromptTag tag)) throw fault("Expected PromptTag# carrier");
        Language.State owner = Language.currentState(node);
        if (owner != tag.getOwner()) throw fault("Prompt tag belongs to another context");
        if (owner.stm.hasTransaction()) throw fault("STM transaction frames do not support explicit delimited capture");
        return tag;
    }
    public static void captureBytecode(Object result, TupleShape shape) {
        ContinuationResult saved = switch (result) {
            case TailYield tail -> tail.getContinuation();
            case ContinuationResult continuation -> continuation;
            case null, default -> null;
        };
        if (saved == null || !(saved.getResult() instanceof DelimitedCut cut)) return;
        cut.append(saved.getFrame(), new DelimitedBytecodeStep(saved, shape)); throw cut;
    }
}
