// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
import com.oracle.truffle.api.TruffleLanguage
import com.oracle.truffle.api.TruffleSafepoint
import com.oracle.truffle.api.interop.InteropLibrary
import com.oracle.truffle.api.source.Source
import org.graalvm.polyglot.io.ByteSequence
import thc.Language
import thc.PackageScalarLink
import thc.PackageScalarSignature
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask

/** Component C globals and entrypoints live in one owning Truffle context. */
internal class PackageScalarLibraries(private val env: TruffleLanguage.Env) {
    private class Loaded(val link: PackageScalarLink, val task: FutureTask<Map<String, Any>>)
    private val libraries = HashMap<String, Loaded>()
    private var closed = false
    private val interop = InteropLibrary.getUncached()

    private fun current() {
        if (Language.currentState().env !== env) fault("Package C library belongs to another context")
        if (!env.isNativeAccessAllowed) fault("Package C bitcode requires native access")
    }

    @TruffleBoundary
    fun link(link: PackageScalarLink) {
        current()
        val selected = synchronized(this) {
            if (closed) fault("Package C library registry is closed")
            if (libraries.values.any { it.link.unit != link.unit && it.link.componentSha256 == link.componentSha256 })
                fault("Package C entry namespace belongs to another unit: ${link.componentSha256}")
            libraries[link.unit]?.also {
                if (!it.link.same(link)) fault("Conflicting package C component identity: ${link.unit}")
            } ?: Loaded(link, FutureTask {
                val library = env.parseInternal(Source.newBuilder("llvm", ByteSequence.create(link.bytes),
                    "${link.componentSha256}.bc").build()).call()
                link.abi.associate { signature ->
                    if (!interop.isMemberReadable(library, signature.entry))
                        fault("Missing package C entry: ${signature.entry}")
                    val function = interop.readMember(library, signature.entry)
                    if (!interop.isExecutable(function)) fault("Package C entry is not executable")
                    signature.symbol to function
                }
            }).also { libraries[link.unit] = it }
        }
        // A FutureTask publishes once; neither LLVM parsing nor waiting holds the registry lock.
        selected.task.run()
        await(selected.task)
    }

    private fun await(task: FutureTask<Map<String, Any>>): Map<String, Any> = try {
        if (task.isDone) task.get()
        else TruffleSafepoint.setBlockedThreadInterruptibleFunction(null,
            TruffleSafepoint.InterruptibleFunction<FutureTask<Map<String, Any>>, Map<String, Any>> { it.get() }, task)
    } catch (failure: ExecutionException) { throw (failure.cause ?: failure) }

    @TruffleBoundary(transferToInterpreterOnException = false)
    fun call(link: PackageScalarLink, signature: PackageScalarSignature, values: Array<Any?>): Any {
        current()
        if (values.size != signature.arguments.size) fault("Package C argument count mismatch")
        val arguments = Array<Any>(values.size) { index ->
            when (signature.arguments[index]) {
                "Int32Rep" -> {
                    val value = values[index] as? Long ?: fault("Package C Int32 argument carrier")
                    if (value != value.toInt().toLong()) fault("Package C Int32 argument is out of range")
                    value.toInt()
                }
                "Int64Rep" -> values[index] as? Long ?: fault("Package C Int64 argument carrier")
                "FloatRep" -> values[index] as? Float ?: fault("Package C Float argument carrier")
                "DoubleRep" -> values[index] as? Double ?: fault("Package C Double argument carrier")
                else -> fault("Unsupported package C argument representation")
            }
        }
        val selected = synchronized(this) {
            if (closed) fault("Package C library registry is closed")
            libraries[link.unit]?.also {
                if (!it.link.same(link) || signature !in it.link.abi)
                    fault("Package C call differs from its registered component ABI")
            } ?: fault("Unlinked package C component: ${link.unit}")
        }
        val function = await(selected.task).getValue(signature.symbol)
        val threads = Language.currentState().threads
        val previous = threads.enterForeign()
        try {
            val result = interop.execute(function, *arguments)
            return when (signature.result) {
                "Int32Rep" -> {
                    if (!interop.fitsInInt(result)) fault("Package C result is not Int32")
                    interop.asInt(result).toLong()
                }
                "Int64Rep" -> {
                    if (!interop.fitsInLong(result)) fault("Package C result is not Int64")
                    interop.asLong(result)
                }
                "FloatRep" -> {
                    if (!interop.fitsInFloat(result)) fault("Package C result is not Float")
                    interop.asFloat(result)
                }
                "DoubleRep" -> {
                    if (!interop.fitsInDouble(result)) fault("Package C result is not Double")
                    interop.asDouble(result)
                }
                else -> fault("Unsupported package C result representation")
            }
        } finally { threads.leaveForeign(previous) }
    }

    @Synchronized fun close() { closed = true; libraries.clear() }
}
