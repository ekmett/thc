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
    private static final String HEX = "0123456789abcdef";
    @TruffleBoundary public static void trace(Node node, TraceOp operation, ManagedAddress address, long count) {
        var context = Language.currentState(node);
        var previous = context.getThreads().enterForeign(ForeignSafety.UNSAFE);
        try {
            byte[] bytes;
            if (operation == TraceOp.BINARY) {
                if (count < 0 || count > Integer.MAX_VALUE) throw RuntimeFault.fault("Invalid binary trace length");
                bytes = count == 0 ? new byte[0] : address.withNativeBorrow(() -> {
                    address.requireByteRegion(count, false);
                    byte[] result = new byte[(int) count];
                    for (int i = 0; i < result.length; i++) result[i] = (byte) address.readWord8(i);
                    return result;
                });
            } else bytes = address.withNativeBorrow(() -> cstring(address));
            var output = context.getEnv().err();
            synchronized (output) {
                try {
                    output.write(("[thc trace " + operation.getLabel() + "] ").getBytes(StandardCharsets.US_ASCII));
                    for (byte item : bytes) {
                        int value = item & 255;
                        if (operation == TraceOp.BINARY) { output.write(HEX.charAt(value >>> 4)); output.write(HEX.charAt(value & 15)); }
                        else if (value == 92) { output.write(92); output.write(92); }
                        else if (value < 32 || value == 127) {
                            output.write(92); output.write(120); output.write(HEX.charAt(value >>> 4)); output.write(HEX.charAt(value & 15));
                        } else output.write(value);
                    }
                    output.write(10);
                } catch (IOException ignored) { /* Void RTS hooks do not raise guest IO errors. */ }
                try { output.flush(); } catch (IOException ignored) { /* Same void hook contract. */ }
            }
        } finally { context.getThreads().leaveForeign(previous); }
    }
    @TruffleBoundary public static void report(Node node, RtsDiagnosticOp operation, Object first, Object second) {
        var context = Language.currentState(node);
        var previous = context.getThreads().enterForeign(ForeignSafety.UNSAFE);
        try {
            byte[] message;
            if (operation == RtsDiagnosticOp.STACK) {
                if (!(first instanceof GuestThreadId thread)) throw RuntimeFault.fault("Stack overflow report requires ThreadId#");
                if (thread.getOwner() != context.getThreads()) throw RuntimeFault.fault("ThreadId# belongs to another guest context");
                message = ("Stack space overflow (THC guest Java thread " + thread.getJavaId() + "; JVM stack limit unavailable).").getBytes(StandardCharsets.US_ASCII);
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
    private static byte[] cstring(ManagedAddress address) {
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
