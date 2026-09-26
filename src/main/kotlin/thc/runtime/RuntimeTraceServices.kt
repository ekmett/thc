// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
import jdk.jfr.Category
import jdk.jfr.Event
import jdk.jfr.FlightRecorder
import jdk.jfr.Label
import jdk.jfr.Name
import jdk.jfr.StackTrace
import jdk.jfr.Timespan
import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.concurrent.atomic.AtomicLong

/** Context-owned structured diagnostics, independent of GHC's trace primops.
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
 * Names are exact UTF-8, including embedded NUL, limited to 1 MiB per input.
 * Live span names are additionally bounded to 16 MiB / 4096 spans per context.
 */
internal class RuntimeTraceServices(
    private val output: OutputStream,
    private val jfr: RuntimeTraceJfr = JvmRuntimeTraceJfr,
    private val maxActiveSpans: Int = 4096,
    private val maxRetainedNameBytes: Long = 16L * 1024 * 1024
) : AutoCloseable {
    private data class Span(val name: String, val byteLength: Long, val started: Long)
    private val contextId = nextPositive(contextIds, "trace context")
    private val spans = HashMap<Long, Span>()
    private var retainedNameBytes = 0L
    private var sink = 0
    private var closed = false

    @TruffleBoundary @Synchronized
    fun query(selector: Int, index: Long, detail: Long): Long {
        if (index != 0L || detail != 0L) fault("Trace queries require zero indices")
        if (selector !in 500..501) fault("Unknown runtime trace query $selector")
        if (closed) return RuntimeServiceStatus.UNAVAILABLE
        return if (selector == 500) sink.toLong() else if (jfr.support() == 0L) 3L else 1L
    }

    @TruffleBoundary @Synchronized
    fun control(selector: Int, setting: Long): Long {
        if (selector != 500) fault("Unknown runtime trace control $selector")
        if (setting !in 0L..3L) fault("Trace sink must be between zero and three")
        if (closed) return RuntimeServiceStatus.UNAVAILABLE
        if (setting and 2L != 0L) {
            val support = jfr.support()
            if (support != 0L) return support
        }
        sink = setting.toInt()
        return 0L
    }

    @TruffleBoundary
    fun emit(operation: Int, token: Long, address: ManagedAddress, length: Long): Long {
        if (operation !in 0..3) fault("Unknown runtime trace operation $operation")
        if (operation <= 1 && token != 0L || operation >= 2 && token <= 0L)
            fault("Invalid runtime trace span token")
        if (length !in 0L..MAX_INPUT_BYTES) fault("Runtime trace text exceeds the 1 MiB input limit")
        if (operation >= 2 && length != 0L) fault("Ending a trace span requires an empty payload")
        // Decode the entire payload before output, token allocation or registry
        // mutation, including when the configured sink is disabled.
        val name = decode(address, length)
        synchronized(this) {
            if (closed) return RuntimeServiceStatus.UNAVAILABLE
            if (operation >= 2) {
                val span = spans.remove(token) ?: fault("Trace span belongs to another context or was already ended")
                retainedNameBytes -= span.byteLength
                if (sink == 0) return RuntimeServiceStatus.DISABLED
                return publish(if (operation == 2) "end" else "exception", token, span.name,
                    (System.nanoTime() - span.started).coerceAtLeast(0L))
            }
            if (sink == 0) return RuntimeServiceStatus.DISABLED
            if (operation == 0) return publish("event", 0L, name, 0L)
            if (spans.size >= maxActiveSpans || length > maxRetainedNameBytes - retainedNameBytes)
                return RuntimeServiceStatus.UNAVAILABLE
            val id = nextPositive(spanIds, "trace span")
            val span = Span(name, length, System.nanoTime())
            val result = publish("begin", id, name, 0L)
            if (result != 0L) return result
            spans[id] = span
            retainedNameBytes += length
            return id
        }
    }

    private fun publish(phase: String, token: Long, name: String, elapsedNanos: Long): Long {
        if (sink and 1 != 0) {
            val record = "{\"thc\":\"trace\",\"context\":$contextId,\"thread\":${Thread.currentThread().threadId()}," +
                "\"phase\":\"$phase\",\"span\":$token,\"name\":${jsonString(name)},\"elapsedNanos\":$elapsedNanos}\n"
            try {
                synchronized(output) {
                    output.write(record.toByteArray(Charsets.UTF_8))
                    output.flush()
                }
            } catch (_: IOException) { return RuntimeServiceStatus.UNAVAILABLE }
            catch (_: SecurityException) { return RuntimeServiceStatus.DENIED }
        }
        if (sink and 2 != 0) {
            val result = jfr.emit(contextId, phase, token, name, elapsedNanos)
            if (result != 0L && !(result == RuntimeServiceStatus.DISABLED && sink and 1 != 0)) return result
        }
        return 0L
    }

    @TruffleBoundary @Synchronized
    override fun close() {
        closed = true
        sink = 0
        spans.clear()
        retainedNameBytes = 0L
    }

    companion object {
        const val MAX_INPUT_BYTES = 1024L * 1024
        private val contextIds = AtomicLong()
        private val spanIds = AtomicLong()

        private fun nextPositive(counter: AtomicLong, what: String): Long {
            // Never wrap and accidentally accept an old token after exhaustion.
            while (true) {
                val previous = counter.get()
                if (previous == Long.MAX_VALUE) fault("Runtime $what identifiers exhausted")
                if (counter.compareAndSet(previous, previous + 1)) return previous + 1
            }
        }

        private fun decode(address: ManagedAddress, length: Long): String {
            if (length == 0L) return ""
            val bytes = ByteArray(length.toInt())
            address.withNativeBorrow {
                address.requireByteRegion(length)
                address.copyToByteArray(bytes, 0, length)
            }
            return try {
                Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
            } catch (_: CharacterCodingException) { fault("Invalid UTF-8 at the runtime trace boundary") }
        }

        private fun jsonString(value: String): String = buildString {
            append('"')
            for (character in value) when (character) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                in '\u0000'..'\u001f', '\u007f', '\u2028', '\u2029' -> {
                    append("\\u")
                    append(character.code.toString(16).padStart(4, '0'))
                }
                else -> append(character)
            }
            append('"')
        }
    }
}

/** Provider boundary keeps optional JFR availability and denied controls testable. */
internal interface RuntimeTraceJfr {
    fun support(): Long
    fun emit(contextId: Long, phase: String, token: Long, name: String, elapsedNanos: Long): Long
}

internal object JvmRuntimeTraceJfr : RuntimeTraceJfr {
    override fun support(): Long = try {
        if (FlightRecorder.isAvailable()) 0L else RuntimeServiceStatus.UNSUPPORTED
    } catch (_: SecurityException) { RuntimeServiceStatus.DENIED }

    override fun emit(contextId: Long, phase: String, token: Long, name: String, elapsedNanos: Long): Long {
        val available = support()
        if (available != 0L) return available
        return try {
            val event = RuntimeTraceEvent()
            if (!event.isEnabled) return RuntimeServiceStatus.DISABLED
            event.contextId = contextId
            event.phase = phase
            event.spanId = token
            event.message = name
            event.elapsedNanos = elapsedNanos
            event.commit()
            0L
        } catch (_: SecurityException) { RuntimeServiceStatus.DENIED }
        catch (_: IllegalStateException) { RuntimeServiceStatus.UNAVAILABLE }
    }
}

/** JFR's eventThread is the emitter's Java thread; IDs are not GHC TSO addresses. */
@Name("thc.RuntimeTrace")
@Label("THC structured trace")
@Category("THC")
@StackTrace(false)
internal class RuntimeTraceEvent : Event() {
    @JvmField var contextId = 0L
    @JvmField var phase = ""
    @JvmField var spanId = 0L
    @JvmField var message = ""
    @JvmField @Timespan(Timespan.NANOSECONDS) var elapsedNanos = 0L
}
