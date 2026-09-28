// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.nodes.IndirectCallNode;
import com.oracle.truffle.api.nodes.Node;
import thc.runtime.Calls;
import thc.runtime.ForeignExceptionAccess;
import thc.runtime.GuestException;
import thc.runtime.Metrics;
import thc.runtime.TargetCache;

/** Direct IO dispatch and a bounded polyglot-to-guest entry boundary. */
public final class HostDispatch extends Node {
    @Child private ForeignExceptionAccess foreignExceptions = new ForeignExceptionAccess();
    @Child private TargetCache calls = new TargetCache(new Metrics(false));
    // The polyglot Value.execute root is shared across unrelated guest entries.
    // Keep its compiled graph bounded while leaving direct guest-to-guest calls
    // and explicit compilation of the stable guest entry target unchanged.
    @Child private IndirectCallNode publicCall = IndirectCallNode.create();

    public void escaping(GuestException failure) { foreignExceptions.escaping(failure); }
    public Object execute(RootCallTarget target, Object[] arguments) { return calls.call(target, arguments); }
    public Object executePublic(RootCallTarget target, Object[] arguments) { return Calls.indirect(publicCall, target, arguments); }
    public static HostDispatch create() { return new HostDispatch(); }
}
