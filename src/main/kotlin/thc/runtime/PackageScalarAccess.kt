// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime

import com.oracle.truffle.api.CompilerDirectives
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
import com.oracle.truffle.api.interop.InteropLibrary
import com.oracle.truffle.api.nodes.ExplodeLoop
import com.oracle.truffle.api.nodes.Node
import thc.Language

/** Convert directly into the interop carrier without an intermediate boxed Long. */
internal fun packageScalarInt32(value: Long): Int {
    if (value != value.toInt().toLong()) fault("Package C Int32 argument is out of range")
    return value.toInt()
}

/** One adopted call site for an exact component ABI in the current EXCLUSIVE context policy. */
internal class PackageScalarAccess(private val call: PackageScalarCall) : Node() {
    @Volatile @CompilationFinal private var cached: PackageScalarFunction? = null
    @Child private var calls = InteropLibrary.getFactory().createDispatched(1)
    @Child private var numbers = InteropLibrary.getFactory().createDispatched(1)

    private fun function(): PackageScalarFunction {
        // Check the entered context even when a host misuses a root from another context.
        val owner = Language.currentState()
        val entry = cached ?: initialize(owner)
        if (entry.owner !== owner) fault("Package C call site belongs to another context")
        if (!entry.alive.isValid) fault("Package C library registry is closed")
        return entry
    }

    @TruffleBoundary
    private fun initialize(owner: Language.State): PackageScalarFunction {
        val resolved = owner.packageCbits.resolve(call.link, call.signature)
        return synchronized(this) {
            cached ?: run {
                CompilerDirectives.transferToInterpreterAndInvalidate()
                cached = resolved
                resolved
            }
        }
    }

    @ExplodeLoop private fun prepare(arguments: Array<Any?>, state: Any?): PackageScalarFunction {
        requireVoidCarrier(state)
        if (arguments.size != call.arguments.size) fault("Package C argument count mismatch")
        for (index in arguments.indices) {
            val valid = when (call.arguments[index]) {
                "Int32Rep" -> arguments[index] is Int
                "Int64Rep" -> arguments[index] is Long
                "FloatRep" -> arguments[index] is Float
                "DoubleRep" -> arguments[index] is Double
                else -> false
            }
            if (!valid) fault("Package C argument differs from its scalar carrier")
        }
        return function()
    }

    fun executeLong(arguments: Array<Any?>, state: Any?): Long {
        if (call.result != "Int32Rep" && call.result != "Int64Rep") fault("Package C result is not an integer ABI")
        val entry = prepare(arguments, state)
        val threads = entry.owner.threads
        val previous = threads.enterForeign()
        try {
            val result = Calls.interop(calls, entry.receiver, arguments)
            return if (call.result == "Int32Rep") {
                if (!numbers.fitsInInt(result)) fault("Package C result is not Int32")
                numbers.asInt(result).toLong()
            } else {
                if (!numbers.fitsInLong(result)) fault("Package C result is not Int64")
                numbers.asLong(result)
            }
        } finally { threads.leaveForeign(previous) }
    }

    fun executeFloat(arguments: Array<Any?>, state: Any?): Float {
        if (call.result != "FloatRep") fault("Package C result is not a Float ABI")
        val entry = prepare(arguments, state)
        val threads = entry.owner.threads
        val previous = threads.enterForeign()
        try {
            val result = Calls.interop(calls, entry.receiver, arguments)
            if (!numbers.fitsInFloat(result)) fault("Package C result is not Float")
            return numbers.asFloat(result)
        } finally { threads.leaveForeign(previous) }
    }

    fun executeDouble(arguments: Array<Any?>, state: Any?): Double {
        if (call.result != "DoubleRep") fault("Package C result is not a Double ABI")
        val entry = prepare(arguments, state)
        val threads = entry.owner.threads
        val previous = threads.enterForeign()
        try {
            val result = Calls.interop(calls, entry.receiver, arguments)
            if (!numbers.fitsInDouble(result)) fault("Package C result is not Double")
            return numbers.asDouble(result)
        } finally { threads.leaveForeign(previous) }
    }
}
