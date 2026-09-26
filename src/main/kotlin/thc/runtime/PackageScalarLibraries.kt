// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime

import com.oracle.truffle.api.Assumption
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
    private class Loaded(val link: PackageScalarLink, val task: FutureTask<Map<String, PackageScalarFunction>>)
    private val libraries = HashMap<String, Loaded>()
    private val alive = Assumption.create("THC package C libraries are open")
    private var closed = false
    private val interop = InteropLibrary.getUncached()
    private val pointerOffset = FutureTask {
        val bytes = PackageScalarLibraries::class.java.getResourceAsStream("/thc/cbits/package-pointer.bc")
            ?.use { it.readBytes() } ?: fault("Missing package pointer bridge")
        val library = env.parseInternal(Source.newBuilder("llvm", ByteSequence.create(bytes), "package-pointer.bc")
            .build()).call()
        interop.readMember(library, "thc_package_pointer_offset")
    }

    private fun current(): Language.State {
        val owner = Language.currentState()
        if (owner.env !== env) fault("Package C library belongs to another context")
        if (!env.isNativeAccessAllowed) fault("Package C bitcode requires native access")
        return owner
    }

    @TruffleBoundary
    fun link(link: PackageScalarLink) {
        val owner = current()
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
                    signature.entry to PackageScalarFunction(owner, signature, function, alive)
                }
            }).also { libraries[link.unit] = it }
        }
        // A FutureTask publishes once; neither LLVM parsing nor waiting holds the registry lock.
        selected.task.run()
        await(selected.task)
    }

    private fun <T> await(task: FutureTask<T>): T = try {
        if (task.isDone) task.get()
        else TruffleSafepoint.setBlockedThreadInterruptibleFunction(null,
            TruffleSafepoint.InterruptibleFunction<FutureTask<T>, T> { it.get() }, task)
    } catch (failure: ExecutionException) { throw (failure.cause ?: failure) }

    /** Return Sulong's pointer carrier, including its actual allocation-relative
     * offset. No Sulong implementation classes or reflective access are needed. */
    @TruffleBoundary
    fun pointer(base: Any, offset: Long): Any {
        current()
        if (!alive.isValid) fault("Package C library registry is closed")
        pointerOffset.run()
        return interop.execute(await(pointerOffset), base, offset)
    }

    /** Resolve only on a call site's first execution; no registry work spans the foreign call. */
    @TruffleBoundary
    fun resolve(link: PackageScalarLink, signature: PackageScalarSignature): PackageScalarFunction {
        current()
        val selected = synchronized(this) {
            if (closed) fault("Package C library registry is closed")
            libraries[link.unit]?.also {
                if (!it.link.same(link) || signature !in it.link.abi)
                    fault("Package C call differs from its registered component ABI")
            } ?: fault("Unlinked package C component: ${link.unit}")
        }
        return await(selected.task).getValue(signature.entry)
    }

    @Synchronized fun close() { closed = true; alive.invalidate(); libraries.clear() }
}

/** A context owns the callable and its lifetime; adopted interop nodes belong to call sites. */
internal class PackageScalarFunction(val owner: Language.State, val signature: PackageScalarSignature,
    val receiver: Any, val alive: Assumption)
