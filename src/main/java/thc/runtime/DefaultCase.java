// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.UnexpectedResultException;

final class DefaultCase extends Case {
    DefaultCase(Expr scrutinee, int binder, Alternative[] alternatives, Metrics metrics, CoreRepresentation proof, boolean delimited) {
        super(scrutinee, binder, alternatives, metrics, proof, delimited);
    }
    @Override public Object execute(VirtualFrame frame) {
        prepare(frame); return selected(frame, alternatives[alternatives.length - 1]).execute(frame);
    }
    @Override public int executeInt(VirtualFrame frame) throws UnexpectedResultException {
        prepare(frame); return selected(frame, alternatives[alternatives.length - 1]).executeInt(frame);
    }
    @Override public long executeLong(VirtualFrame frame) throws UnexpectedResultException {
        prepare(frame); return selected(frame, alternatives[alternatives.length - 1]).executeLong(frame);
    }
    @Override public float executeFloat(VirtualFrame frame) throws UnexpectedResultException {
        prepare(frame); return selected(frame, alternatives[alternatives.length - 1]).executeFloat(frame);
    }
    @Override public double executeDouble(VirtualFrame frame) throws UnexpectedResultException {
        prepare(frame); return selected(frame, alternatives[alternatives.length - 1]).executeDouble(frame);
    }
    @Override public Closure executeClosure(VirtualFrame frame) throws UnexpectedResultException {
        prepare(frame); return selected(frame, alternatives[alternatives.length - 1]).executeClosure(frame);
    }
    @Override public DataValue executeDataValue(VirtualFrame frame) throws UnexpectedResultException {
        prepare(frame); return selected(frame, alternatives[alternatives.length - 1]).executeDataValue(frame);
    }
    @Override public ManagedAddress executeAddress(VirtualFrame frame) throws UnexpectedResultException {
        prepare(frame); return selected(frame, alternatives[alternatives.length - 1]).executeAddress(frame);
    }
}
