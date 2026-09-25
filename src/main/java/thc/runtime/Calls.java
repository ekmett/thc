// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime;

import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.interop.InteropException;
import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.nodes.DirectCallNode;
import com.oracle.truffle.api.nodes.IndirectCallNode;

/**
 * Internal Java bridge that avoids Kotlin's defensive spread-argument array copy.
 * Callers supply the runtime's exact argument array and target convention; this
 * class neither converts host values nor establishes a stable embedding ABI.
 * Host applications should use THC's polyglot entry-loading helpers instead.
 */
public final class Calls {
    private Calls() {}
    public static Object direct(DirectCallNode node, Object[] args) { return node.call(args); }
    public static Object indirect(IndirectCallNode node, CallTarget target, Object[] args) { return node.call(target, args); }
    public static Object target(CallTarget target, Object[] args) { return target.call(args); }
    public static Object interop(InteropLibrary library, Object receiver, Object[] args) throws InteropException {
        return library.execute(receiver, args);
    }
}
