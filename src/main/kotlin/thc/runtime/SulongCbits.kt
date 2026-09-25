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
import java.util.concurrent.ConcurrentHashMap
import thc.ForeignBitcode

/** Context-owned original C code and allocation views. No process addresses escape. */
internal class SulongCbits(env: TruffleLanguage.Env) {
    internal data class CapiResult(val value: Long, val errno: Long)
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
    private val init = interop.readMember(library, "thc_md5_init")
    private val update = interop.readMember(library, "thc_md5_update")
    private val finish = interop.readMember(library, "thc_md5_final")
    // Arrays have identity equality. Both sides are weak: a cached view must not
    // keep its weak key alive. A live LLVM pointer strongly retains its view.
    private val buffers = WeakHashMap<Any, WeakReference<CbitsBuffer>>()
    private val foreign = ConcurrentHashMap<Pair<String, String>, Any>()
    @Volatile private var foreignErrno: Any? = null
    @Volatile private var foreignAlloc: Any? = null
    @Volatile private var foreignCopy: Any? = null
    @Volatile private var foreignFree: Any? = null
    @Volatile private var linkedForeign: ForeignBitcode? = null

    private fun sameLink(first: ForeignBitcode, second: ForeignBitcode) =
        first.unit == second.unit && first.module == second.module && first.target == second.target &&
            first.symbols == second.symbols && first.bytes.contentEquals(second.bytes)

    /** Parse and resolve every declared CAPI symbol before a guest entry runs. */
    fun link(record: ForeignBitcode) {
        linkedForeign?.let { require(sameLink(it, record)) { "Conflicting CAPI library identity" }; return }
        val library = env.parseInternal(Source.newBuilder("llvm", ByteSequence.create(record.bytes),
            "${record.unit}-${record.module}.bc").build()).call()
        val resolved = record.symbols.associateWith { symbol ->
            require(interop.isMemberReadable(library, symbol)) { "Missing compiled original CAPI symbol: $symbol" }
            interop.readMember(library, symbol)
        }
        require(interop.isMemberReadable(library, "thc_capi_errno")) { "CAPI library lacks errno bridge" }
        for (name in listOf("thc_capi_alloc_timespec", "thc_capi_copy_timespec", "thc_capi_free_timespec"))
            require(interop.isMemberReadable(library, name)) { "CAPI library lacks native pointer bridge: $name" }
        val errno = interop.readMember(library, "thc_capi_errno")
        val allocation = interop.readMember(library, "thc_capi_alloc_timespec")
        val copy = interop.readMember(library, "thc_capi_copy_timespec")
        val free = interop.readMember(library, "thc_capi_free_timespec")
        synchronized(this) {
            linkedForeign?.let { require(sameLink(it, record)) { "Conflicting CAPI library identity" }; return }
            foreignErrno = errno
            foreignAlloc = allocation
            foreignCopy = copy
            foreignFree = free
            for ((symbol, function) in resolved) foreign[record.unit to symbol] = function
            linkedForeign = record
        }
    }

    private fun foreignFunction(unit: String, symbol: String): Any = foreign[unit to symbol]
        ?: fault("Unlinked original CAPI target: $unit:$symbol")

    fun capiZero(unit: String, symbol: String): Long {
        val value = interop.execute(foreignFunction(unit, symbol))
        if (!interop.fitsInLong(value)) fault("CAPI result is not a machine word: $symbol")
        return interop.asLong(value)
    }

    fun capiWordAddress(unit: String, symbol: String, word: Long, address: ManagedAddress): CapiResult {
        // The original wrapper calls libc with a malloc-owned timespec. Only
        // the completed result crosses into the checked managed Addr# view.
        val invoke = {
            address.requireRange(0, 16, writable = true)
            val pointer = CbitsBuffer(address.cbitsBacking(), address.cbitsWritable(),
                LongSupplier { address.cbitsSize() }, address.cbitsOffset())
            val alloc = foreignAlloc ?: fault("No linked CAPI native allocation bridge")
            val copy = foreignCopy ?: fault("No linked CAPI native copy bridge")
            val free = foreignFree ?: fault("No linked CAPI native release bridge")
            val native = interop.execute(alloc)
            if (interop.isNull(native)) CapiResult(-1, capiErrno()) else try {
                val result = interop.execute(foreignFunction(unit, symbol), word, native)
                if (!interop.fitsInInt(result)) fault("CAPI result is not a CInt: $symbol")
                val value = interop.asInt(result).toLong()
                val errno = if (value < 0) capiErrno() else 0L
                if (value == 0L) interop.execute(copy, native, pointer)
                CapiResult(value, errno)
            } finally { interop.execute(free, native) }
        }
        // A guest allocation may be shrunk by another host thread; hold its
        // owner through preflight, C call, copyback, and native release.
        val owner = address.cbitsOwner()
        return if (owner == null) invoke() else synchronized(owner) { invoke() }
    }

    fun capiErrno(): Long {
        val function = foreignErrno ?: fault("No linked CAPI errno domain")
        val value = interop.execute(function)
        if (!interop.fitsInInt(value)) fault("CAPI errno is not a CInt")
        return interop.asInt(value).toLong()
    }

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
