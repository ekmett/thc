// SPDX-FileCopyrightText: 2026 Edward Kmett <ekmett@gmail.com>
// SPDX-License-Identifier: BSD-2-Clause OR Apache-2.0
package com.oracle.svm.core.jam;

import static jdk.graal.compiler.core.common.spi.ForeignCallDescriptor.CallSideEffect.HAS_SIDE_EFFECT;

import org.graalvm.word.LocationIdentity;
import org.graalvm.word.Pointer;
import com.oracle.svm.core.graal.meta.SubstrateForeignCallsProvider;
import com.oracle.svm.core.snippets.SnippetRuntime;
import com.oracle.svm.core.snippets.SnippetRuntime.SubstrateForeignCallDescriptor;
import com.oracle.svm.core.snippets.SubstrateForeignCallTarget;
import com.oracle.svm.shared.Uninterruptible;
import jdk.graal.compiler.core.common.spi.ForeignCallDescriptor;
import jdk.graal.compiler.graph.Node.ConstantNodeParameter;
import jdk.graal.compiler.graph.Node.NodeIntrinsic;
import jdk.graal.compiler.nodes.extended.ForeignCallNode;

/** Native TLS operations that also lower inside entry and exit snippets. */
final class JamThreadContext {
    private static final SubstrateForeignCallDescriptor ENTER = SnippetRuntime.findForeignCall(JamThreadContext.class, "enterNative", HAS_SIDE_EFFECT, LocationIdentity.any());
    private static final SubstrateForeignCallDescriptor LEAVE = SnippetRuntime.findForeignCall(JamThreadContext.class, "leaveNative", HAS_SIDE_EFFECT, LocationIdentity.any());

    static void registerForeignCalls(SubstrateForeignCallsProvider calls) {
        calls.register(ENTER, LEAVE);
    }

    @Uninterruptible(reason = Uninterruptible.CALLED_FROM_UNINTERRUPTIBLE_CODE, mayBeInlined = true)
    static void enter(Pointer scope) { call(ENTER, scope); }

    @Uninterruptible(reason = Uninterruptible.CALLED_FROM_UNINTERRUPTIBLE_CODE, mayBeInlined = true)
    static void leave(Pointer scope) { call(LEAVE, scope); }

    @NodeIntrinsic(ForeignCallNode.class)
    private static native void call(@ConstantNodeParameter ForeignCallDescriptor descriptor, Pointer scope);

    @SubstrateForeignCallTarget(stubCallingConvention = false, fullyUninterruptible = true)
    @Uninterruptible(reason = "Bind Jam without a safepoint or a native status transition.")
    private static void enterNative(Pointer scope) { JamNative.threadEnter(scope); }

    @SubstrateForeignCallTarget(stubCallingConvention = false, fullyUninterruptible = true)
    @Uninterruptible(reason = "Restore Jam's enclosing context before native status is published.")
    private static void leaveNative(Pointer scope) { JamNative.threadLeave(scope); }
}
