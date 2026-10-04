// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.nodes.Node;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import thc.Language;

/** Context stderr diagnostics, not native GHC TSO allocation or binary eventlog records. */
public final class RtsDiagnostics {
    private RtsDiagnostics() {}
    public static void trace(Node node, TraceOp operation, ManagedAddress address, long count) {
        var context = Language.currentState(node);
        if (context.getRuntimeTrace().isPrimopDisabled()) return;
        traceEnabled(context, operation, address, count);
    }
    @TruffleBoundary private static void traceEnabled(Language.State context, TraceOp operation, ManagedAddress address, long count) {
        var previous = context.getThreads().enterForeign(ForeignSafety.UNSAFE);
        try { context.getRuntimeTrace().emitPrimop(operation, address, count); }
        finally { context.getThreads().leaveForeign(previous); }
    }
    @TruffleBoundary public static void report(Node node, RtsDiagnosticOp operation, Object first, Object second) {
        var context = Language.currentState(node);
        var stackThread = operation == RtsDiagnosticOp.STACK ? context.getThreads().requireIdentity(first) : null;
        var previous = context.getThreads().enterForeign(ForeignSafety.UNSAFE);
        try {
            byte[] message;
            if (operation == RtsDiagnosticOp.STACK) {
                message = ("Stack space overflow (THC guest Java thread " + stackThread.getJavaId() + "; JVM stack limit unavailable).").getBytes(StandardCharsets.US_ASCII);
            } else if (operation == RtsDiagnosticOp.HEAP) {
                message = ("Heap exhausted; JVM maximum heap size is " + Runtime.getRuntime().maxMemory() + " bytes.").getBytes(StandardCharsets.US_ASCII);
            } else {
                if (!(first instanceof ManagedAddress format)) throw RuntimeFault.fault(operation.getSymbol() + " requires a format Addr#");
                if (!(second instanceof ManagedAddress text)) throw RuntimeFault.fault(operation.getSymbol() + " requires a message Addr#");
                message = format.withNativeBorrows(text, () -> {
                    // Only original Debug.Trace and TopHandler/Conc.Sync CString formats are admitted.
                    byte[] expected = operation == RtsDiagnosticOp.DEBUG ? new byte[] {37, 115, 10} : new byte[] {37, 115};
                    if (!Arrays.equals(cstring(format), expected)) throw RuntimeFault.fault(operation.getSymbol() + " supports only its original CString format");
                    return cstring(text);
                });
            }
            // debugBelch2's format supplies LF; errorBelch2 appends LF. Both emit exactly one here.
            var output = context.getEnv().err();
            synchronized (output) {
                try { output.write(message); output.write(10); } catch (IOException ignored) { /* Void hook. */ }
                try { output.flush(); } catch (IOException ignored) { /* Void hook. */ }
            }
        } finally { context.getThreads().leaveForeign(previous); }
    }
    static byte[] cstring(ManagedAddress address) {
        long available = address.availableBytes();
        long length = 0;
        while (length < available && address.readWord8(length) != 0) length++;
        if (length == available) throw RuntimeFault.fault("Unterminated diagnostic CString");
        if (length > Integer.MAX_VALUE) throw RuntimeFault.fault("Diagnostic CString exceeds managed array capacity");
        address.requireByteRegion(length + 1, false);
        byte[] bytes = new byte[(int) length];
        for (int i = 0; i < bytes.length; i++) bytes[i] = (byte) address.readWord8(i);
        return bytes;
    }
}
