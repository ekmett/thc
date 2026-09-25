// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime

import com.oracle.truffle.api.TruffleLanguage
import com.oracle.truffle.api.interop.InteropLibrary
import com.oracle.truffle.api.source.Source
import org.graalvm.polyglot.io.ByteSequence
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import java.util.function.LongSupplier
import java.util.concurrent.FutureTask
import java.util.concurrent.ExecutionException
import com.oracle.truffle.api.TruffleSafepoint

/** Context-owned original C code and allocation views. No process addresses escape. */
internal class SulongCbits(env: TruffleLanguage.Env) {
    private val interop = InteropLibrary.getUncached()
    private fun load(env: TruffleLanguage.Env, name: String): Any {
        val bytes = SulongCbits::class.java.getResourceAsStream("/thc/cbits/$name.bc")?.use { it.readBytes() }
            ?: fault("Missing compiled original C resource: $name")
        return env.parseInternal(Source.newBuilder("llvm", ByteSequence.create(bytes), "$name.bc").build()).call()
    }
    init {
        val manifest = SulongCbits::class.java.getResourceAsStream("/thc/cbits/manifest.json")?.use {
            thc.Json.parse(it.reader().readText()) as Map<*, *>
        } ?: fault("Missing original C build manifest")
        val system = System.getProperty("os.name").let { if (it.startsWith("Mac")) "Darwin" else it }
        fun architecture(value: String) = when (value.lowercase()) {
            "arm64" -> "aarch64"
            "amd64" -> "x86_64"
            else -> value
        }
        if (manifest["system"] != system || architecture(manifest["architecture"] as String) != architecture(System.getProperty("os.arch")))
            fault("Original C bitcode does not match this runtime platform")
    }
    private val library = load(env, "md5")
    private val iconvTask = FutureTask { load(env, "iconv") }
    internal fun iconvLibrary(): Any {
        if (System.getProperty("os.name") != "Linux")
            fault("Original native iconv currently requires the Linux GNU LP64 host ABI")
        iconvTask.run() // FutureTask publishes once; no cache lock spans guest parsing.
        return try {
            if (iconvTask.isDone) iconvTask.get()
            else TruffleSafepoint.setBlockedThreadInterruptibleFunction(null,
                TruffleSafepoint.InterruptibleFunction<FutureTask<Any>, Any> { it.get() }, iconvTask)
        } catch (failure: ExecutionException) { throw (failure.cause ?: failure) }
    }
    private val init = interop.readMember(library, "thc_md5_init")
    private val update = interop.readMember(library, "thc_md5_update")
    private val finish = interop.readMember(library, "thc_md5_final")
    // Arrays have identity equality. Both sides are weak: a cached view must not
    // keep its weak key alive. A live LLVM pointer strongly retains its view.
    private val buffers = WeakHashMap<Any, WeakReference<CbitsBuffer>>()

    @Synchronized internal fun buffer(address: ManagedAddress): CbitsBuffer {
        val bytes = address.cbitsBacking()
        val owner = address.cbitsOwner()
        val key = owner ?: bytes
        return buffers[key]?.get() ?: (if (owner == null) CbitsBuffer(bytes, address.cbitsWritable())
            else CbitsBuffer(bytes, address.cbitsWritable(), LongSupplier { address.cbitsSize() })).also {
            buffers[key] = WeakReference(it)
        }
    }
    fun init(context: ManagedAddress) {
        interop.execute(init, buffer(context), context.cbitsOffset())
    }
    fun update(context: ManagedAddress, input: ManagedAddress, length: Int) {
        interop.execute(update, buffer(context), context.cbitsOffset(), buffer(input), input.cbitsOffset(), length)
    }
    fun finish(output: ManagedAddress, context: ManagedAddress) {
        interop.execute(finish, buffer(output), output.cbitsOffset(), buffer(context), context.cbitsOffset())
    }
}
