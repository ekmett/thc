// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc

import java.util.Collections
import java.util.concurrent.CancellationException

/** Immutable source of ONE binding RHS, independent of JSON spans or store IDs.
 *
 * Header access never asks for a body. The decoder may return an immutable lazy
 * projection: materialization does not require parsing unused fields. It owns
 * the original source snapshot/lifetime and must preserve exact header values.
 * Executable nodes, lowered targets, CAFs and guest failures do not belong here:
 * those are per-program/Context state even when source is shared by an Engine.
 */
internal class CoreBindingBody(val header: Header, private val decode: () -> List<Any?>) : AbstractList<Any?>() {
    class Header(val fieldCount: Int, fields: Map<Int, Any?>, val containsDelimitedControl: Boolean) {
        val fields: Map<Int, Any?> = Collections.unmodifiableMap(LinkedHashMap(fields))
        val tag: String = fields[0] as? String ?: error("Core body header lacks its exact opcode")
        init {
            require(fieldCount > 0 && fields.keys.all { it in 0 until fieldCount }) { "Invalid Core body header extent" }
        }
    }

    private var decoded: Result<List<Any?>>? = null
    private var decoding = false
    private var attempts = 0L
    override val size get() = header.fieldCount
    @Synchronized fun decodeAttempts(): Long = attempts
    @Synchronized fun isMaterialized(): Boolean = decoded?.isSuccess == true

    override fun get(index: Int): Any? {
        if (index !in 0 until size) throw IndexOutOfBoundsException(index)
        return if (header.fields.containsKey(index)) header.fields[index] else materialize()[index]
    }

    /** Publish one immutable result or ordinary decode failure. Closing an input
     * owner cannot invalidate already owned decoded values; an undecoded owner's
     * closed-input error remains an ordinary stable failure. Fatal VM errors,
     * interruption and cancellation are not cached as malformed Core.
     */
    @Synchronized fun materialize(): List<Any?> {
        decoded?.let { return it.getOrThrow() }
        check(!decoding) { "Recursive Core source decoding" }
        decoding = true
        attempts++
        try {
            val body = decode()
            require(body !== this && body.size == size && body.firstOrNull() == header.tag) {
                "Core body differs from its indexed header"
            }
            // This protects the outer list interface without copying/traversing
            // children. The decoder supplies immutable source-owned children.
            val result = Collections.unmodifiableList(body)
            decoded = Result.success(result)
            return result
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            throw interrupted
        } catch (failure: Exception) {
            decoded = Result.failure(failure)
            throw failure
        } finally {
            decoding = false
        }
    }
}
