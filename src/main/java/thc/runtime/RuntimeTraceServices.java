// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.Assumption;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicLong;
import static thc.runtime.RuntimeServiceStatus.fault;

/** Context-owned tracing shared by THC.Trace and the original GHC trace primops.
 *
 * Sink 0 is disabled, 1 is the embedding context's stderr, 2 is JFR, and 3 is
 * both. Selecting JFR neither creates a recording nor enables an event in an
 * existing recording. JFR-only emission returns Disabled when no recording
 * enables thc.RuntimeTrace. With both sinks, a successful stderr write suffices
 * when JFR is disabled. An I/O error returns Unavailable (a stream may already
 * have accepted part of the record); the caller must not retry a span end.
 *
 * Span IDs are process-unique positive tokens, accepted only by their creating
 * context. Ends consume the token even when diagnostics were disabled meanwhile.
 * Each emission uses the current sink; changing it can leave unmatched records
 * in an individual sink. Close discards open spans without emitting fake ends.
 * Structured names are exact UTF-8, including embedded NUL, limited to 1 MiB per input.
 * Original GHC primops instead retain their CString/exact-count byte contracts;
 * their void result cannot report sink availability or emission status.
 * Live span names are additionally bounded to 16 MiB / 4096 spans per context.
 */
public final class RuntimeTraceServices implements AutoCloseable {
    public static final long MAX_INPUT_BYTES = 1024L * 1024;
    private static final String HEX = "0123456789abcdef";
    private static final AtomicLong contextIds = new AtomicLong();
    private static final AtomicLong spanIds = new AtomicLong();
    private record Span(String name, long byteLength, long started) {}

    private final OutputStream output;
    private final RuntimeTraceJfr jfr;
    private final int maxActiveSpans;
    private final long maxRetainedNameBytes;
    private final long contextId = nextPositive(contextIds, "trace context");
    private final HashMap<Long, Span> spans = new HashMap<>();
    private long retainedNameBytes;
    private final Assumption initiallyDisabled = Assumption.create("THC original tracing initially disabled");
    private volatile int sink;
    private boolean closed;

    public RuntimeTraceServices(OutputStream output) { this(output, JvmRuntimeTraceJfr.INSTANCE); }
    public RuntimeTraceServices(OutputStream output, RuntimeTraceJfr jfr) {
        this(output, jfr, 4096, 16L * 1024 * 1024);
    }
    public RuntimeTraceServices(OutputStream output, RuntimeTraceJfr jfr,
                                int maxActiveSpans, long maxRetainedNameBytes) {
        this.output = output;
        this.jfr = jfr;
        this.maxActiveSpans = maxActiveSpans;
        this.maxRetainedNameBytes = maxRetainedNameBytes;
    }

    @TruffleBoundary
    public synchronized long query(int selector, long index, long detail) {
        if (index != 0L || detail != 0L) throw fault("Trace queries require zero indices");
        if (selector < 500 || selector > 501) throw fault("Unknown runtime trace query " + selector);
        if (closed) return RuntimeServiceStatus.UNAVAILABLE;
        return selector == 500 ? sink : jfr.support() == 0L ? 3L : 1L;
    }

    @TruffleBoundary
    public synchronized long control(int selector, long setting) {
        if (selector != 500) throw fault("Unknown runtime trace control " + selector);
        if (setting < 0L || setting > 3L) throw fault("Trace sink must be between zero and three");
        if (closed) return RuntimeServiceStatus.UNAVAILABLE;
        if ((setting & 2L) != 0L) {
            long support = jfr.support();
            if (support != 0L) return support;
        }
        sink = (int) setting;
        if (sink != 0) initiallyDisabled.invalidate();
        return 0L;
    }

    /** Initial off can specialize; after enabling, sink changes remain observable. */
    public boolean isPrimopDisabled() { return initiallyDisabled.isValid() || sink == 0; }

    @TruffleBoundary
    public synchronized void emitPrimop(TraceOp operation, ManagedAddress address, long count) {
        if (closed || sink == 0) return;
        byte[] bytes;
        if (operation == TraceOp.BINARY) {
            if (count < 0 || count > Integer.MAX_VALUE) throw fault("Invalid binary trace length");
            bytes = count == 0 ? new byte[0] : address.withNativeBorrow(() -> {
                address.requireByteRegion(count, false);
                byte[] result = new byte[(int) count];
                address.copyToByteArray(result, 0L, count);
                return result;
            });
        } else bytes = address.withNativeBorrow(() -> RtsDiagnostics.cstring(address));
        if ((sink & 1) != 0) {
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
                } catch (IOException ignored) { /* Original void RTS hooks do not raise guest IO errors. */ }
                try { output.flush(); } catch (IOException ignored) { /* Same void hook contract. */ }
            }
        }
        if ((sink & 2) != 0) {
            String payload = HexFormat.of().formatHex(bytes);
            String message = operation == TraceOp.BINARY ? payload : new String(bytes, StandardCharsets.UTF_8);
            jfr.emit(contextId, operation.getLabel(), 0L, message, 0L, payload);
        }
    }

    @TruffleBoundary
    public long emit(int operation, long token, ManagedAddress address, long length) {
        if (operation < 0 || operation > 3) throw fault("Unknown runtime trace operation " + operation);
        if ((operation <= 1 && token != 0L) || (operation >= 2 && token <= 0L))
            throw fault("Invalid runtime trace span token");
        if (length < 0L || length > MAX_INPUT_BYTES) throw fault("Runtime trace text exceeds the 1 MiB input limit");
        if (operation >= 2 && length != 0L) throw fault("Ending a trace span requires an empty payload");
        // Decode the entire payload before output, token allocation or registry
        // mutation, including when the configured sink is disabled.
        String name = decode(address, length);
        synchronized (this) {
            if (closed) return RuntimeServiceStatus.UNAVAILABLE;
            if (operation >= 2) {
                var span = spans.remove(token);
                if (span == null) throw fault("Trace span belongs to another context or was already ended");
                retainedNameBytes -= span.byteLength();
                if (sink == 0) return RuntimeServiceStatus.DISABLED;
                return publish(operation == 2 ? "end" : "exception", token, span.name(),
                    Math.max(System.nanoTime() - span.started(), 0L));
            }
            if (sink == 0) return RuntimeServiceStatus.DISABLED;
            if (operation == 0) return publish("event", 0L, name, 0L);
            if (spans.size() >= maxActiveSpans || length > maxRetainedNameBytes - retainedNameBytes)
                return RuntimeServiceStatus.UNAVAILABLE;
            long id = nextPositive(spanIds, "trace span");
            var span = new Span(name, length, System.nanoTime());
            long result = publish("begin", id, name, 0L);
            if (result != 0L) return result;
            spans.put(id, span);
            retainedNameBytes += length;
            return id;
        }
    }

    private long publish(String phase, long token, String name, long elapsedNanos) {
        if ((sink & 1) != 0) {
            String record = "{\"thc\":\"trace\",\"context\":" + contextId + ",\"thread\":" + Thread.currentThread().threadId() +
                ",\"phase\":\"" + phase + "\",\"span\":" + token + ",\"name\":" + jsonString(name) +
                ",\"elapsedNanos\":" + elapsedNanos + "}\n";
            try {
                synchronized (output) {
                    output.write(record.getBytes(StandardCharsets.UTF_8));
                    output.flush();
                }
            } catch (IOException ignored) { return RuntimeServiceStatus.UNAVAILABLE; }
            catch (SecurityException ignored) { return RuntimeServiceStatus.DENIED; }
        }
        if ((sink & 2) != 0) {
            long result = jfr.emit(contextId, phase, token, name, elapsedNanos, "");
            if (result != 0L && !(result == RuntimeServiceStatus.DISABLED && (sink & 1) != 0)) return result;
        }
        return 0L;
    }

    @Override
    @TruffleBoundary
    public synchronized void close() {
        closed = true;
        sink = 0;
        spans.clear();
        retainedNameBytes = 0L;
    }

    private static long nextPositive(AtomicLong counter, String what) {
        // Never wrap and accidentally accept an old token after exhaustion.
        while (true) {
            long previous = counter.get();
            if (previous == Long.MAX_VALUE) throw fault("Runtime " + what + " identifiers exhausted");
            if (counter.compareAndSet(previous, previous + 1)) return previous + 1;
        }
    }

    private static String decode(ManagedAddress address, long length) {
        if (length == 0L) return "";
        byte[] bytes = new byte[(int) length];
        var nativeAllocation = address.nativeAllocation();
        // Keep the complete checked copy inside one native lifetime, without
        // a capturing callback for the borrow body.
        try (var borrow = nativeAllocation == null ? null : nativeAllocation.borrow()) {
            address.requireByteRegion(length, false);
            address.copyToByteArray(bytes, 0L, length);
        }
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException ignored) { throw fault("Invalid UTF-8 at the runtime trace boundary"); }
    }

    private static String jsonString(String value) {
        var result = new StringBuilder();
        result.append('"');
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            switch (character) {
                case '"' -> result.append("\\\"");
                case '\\' -> result.append("\\\\");
                default -> {
                    if (character <= '\u001f' || character == '\u007f' || character == '\u2028' || character == '\u2029') {
                        result.append("\\u");
                        String hex = Integer.toHexString(character);
                        for (int padding = hex.length(); padding < 4; padding++) result.append('0');
                        result.append(hex);
                    } else result.append(character);
                }
            }
        }
        return result.append('"').toString();
    }
}
