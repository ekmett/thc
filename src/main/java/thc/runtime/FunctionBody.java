// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.frame.MaterializedFrame;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.UnexpectedResultException;
import static thc.runtime.RuntimeFault.fault;

final class FunctionBody extends Node {
    @Child private Expr value;
    private final boolean initializer;
    private final boolean rawResult;
    private final TupleShape tuple;
    @CompilationFinal(dimensions = 1) private final int[] tupleSlots;
    private final CoreKind resultKind;
    private final boolean exactInt;
    @CompilationFinal private boolean genericResult;
    FunctionBody(Expr expression, Metrics metrics, CoreRepresentation result, TupleShape tuple, int[] tupleSlots) {
        this(expression, metrics, result, tuple, tupleSlots, false);
    }
    FunctionBody(Expr expression, Metrics metrics, CoreRepresentation result, TupleShape tuple, int[] tupleSlots, boolean initializer) {
        this(expression, metrics, result, tuple, tupleSlots, initializer, false);
    }
    /** Returning a lifted value does not demand it. */
    FunctionBody(Expr expression, Metrics metrics, CoreRepresentation result, TupleShape tuple, int[] tupleSlots,
                 boolean initializer, boolean rawResult) {
        this.initializer = initializer; this.rawResult = rawResult;
        value = expression;
        this.tuple = tuple; this.tupleSlots = tupleSlots;
        if (tuple != null) value.prepareTuple(tupleSlots, 0);
        CoreRepresentation effective = result.getKind() == CoreKind.UNKNOWN ? expression.getRepresentation() : result;
        // A data/function type says what a demanded value will be, not that the
        // returned reference is already a DataValue/Closure rather than a thunk.
        resultKind = !expression.getRepresentation().getEvaluated() &&
            (effective.getKind() == CoreKind.DATA || effective.getKind() == CoreKind.CLOSURE)
            ? CoreKind.OBJECT : effective.getKind();
        exactInt = effective.isInt();
        boolean exactLong = resultKind == CoreKind.LONG && !exactInt;
        genericResult = result.getPresent() && !exactLong;
    }
    // Normal Core locals carry guest values, never the raw suspension carriers
    // accepted by internal application/demand adapters.
    boolean needsStackDriver() { return rawResult || value.mayEnterGuest(); }
    /** Keep a primitive body until the mandatory Object-returning root/call boundary. */
    Object execute(VirtualFrame frame) {
        if (rawResult) return value.execute(frame);
        TupleShape shape = tuple;
        if (shape != null) {
            try { value.executeTuple(frame, tupleSlots, 0); }
            catch (DelimitedCut cut) {
                if (!DelimitedControl.INSTANCE.enabled(this)) throw cut;
                throw cut.append(frame, new DelimitedStep() {
                    @Override public Object resume(MaterializedFrame frame, DelimitedResume input,
                                                   MaskingState ambient, DelimitedStep outerMask) {
                        input.get();
                        return TupleResults.ownedTupleResult(shape.finish(frame, tupleSlots, false), shape);
                    }
                });
            } catch (AstCapture cut) {
                throw cut.append(new AstResumeStep() {
                    @Override public Object resume(VirtualFrame frame, Object input) {
                        if (input != null) throw fault("Invalid AST tuple resume value");
                        return shape.finish(frame, tupleSlots, false);
                    }
                });
            }
            // Both continuation protocols can retain a carrier in an owned frame.
            return shape.finish(frame, tupleSlots, AstControl.captures(this) || DelimitedControl.INSTANCE.enabled(this));
        }
        if (initializer) return value.execute(frame);
        if (exactInt) return value.executeRequiredInt(frame);
        if (resultKind == CoreKind.LONG) return value.executeRequiredLong(frame);
        if (resultKind == CoreKind.FLOAT) return value.executeRequiredFloat(frame);
        if (resultKind == CoreKind.DOUBLE) return value.executeRequiredDouble(frame);
        if (resultKind == CoreKind.DATA) return value.executeRequiredDataValue(frame);
        if (resultKind == CoreKind.CLOSURE) return value.executeRequiredClosure(frame);
        if (resultKind == CoreKind.ADDRESS) return value.executeRequiredAddress(frame);
        if (genericResult) return value.execute(frame);
        try { return value.executeLong(frame); }
        catch (UnexpectedResultException unexpected) {
            genericResult = true;
            return unexpected.getResult();
        }
    }
    long executeLong(VirtualFrame frame) { return value.executeRequiredLong(frame); }
    int executeInt(VirtualFrame frame) { return value.executeRequiredInt(frame); }
}
