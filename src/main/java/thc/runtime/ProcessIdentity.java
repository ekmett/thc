// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.nodes.Node;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import thc.Language;
import static thc.runtime.RuntimeServiceStatus.fault;

/** Live libc process identity; cached handles never cache the returned identity. */
public final class ProcessIdentity {
    private ProcessIdentity() {}

    @TruffleBoundary public static long query(Node node, OriginalStdioOp operation) {
        if (!operation.getProcessIdentity()) throw fault("Invalid original process identity operation");
        var state = Language.currentState(node);
        if (!state.getEnv().isNativeAccessAllowed()) throw fault("Original process identity requires native access");
        // The host probe also asserts signed 32-bit pid_t and unsigned 32-bit uid_t.
        try { StdioHostAbi.load(); }
        catch (java.io.IOException failure) { throw propagate(failure); }
        var threads = state.getThreads$org_intelligence_thc();
        var previous = threads.enterForeign(ForeignSafety.UNSAFE);
        try {
            return operation == OriginalStdioOp.GET_PID ? (long) (int) Libc.GET_PID.invokeExact()
                : Integer.toUnsignedLong((int) Libc.GET_EUID.invokeExact());
        } catch (Throwable failure) { throw propagate(failure); }
        finally { threads.leaveForeign(previous); }
    }

    private static final class Libc {
        private static final Linker LINKER = Linker.nativeLinker();
        private static final FunctionDescriptor SIGNATURE = FunctionDescriptor.of(ValueLayout.JAVA_INT);
        private static final MethodHandle GET_PID = LINKER.downcallHandle(LINKER.defaultLookup().find("getpid").orElseThrow(), SIGNATURE);
        private static final MethodHandle GET_EUID = LINKER.downcallHandle(LINKER.defaultLookup().find("geteuid").orElseThrow(), SIGNATURE);
    }

    @SuppressWarnings("unchecked") private static <E extends Throwable> RuntimeException propagate(Throwable failure) throws E { throw (E) failure; }
}
