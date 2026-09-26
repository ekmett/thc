// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime

import com.oracle.truffle.api.TruffleLanguage
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
import com.oracle.truffle.api.interop.InteropLibrary
import com.oracle.truffle.api.interop.InvalidBufferOffsetException
import com.oracle.truffle.api.interop.TruffleObject
import com.oracle.truffle.api.interop.UnsupportedMessageException
import com.oracle.truffle.api.library.ExportLibrary
import com.oracle.truffle.api.library.ExportMessage
import com.oracle.truffle.api.source.Source
import org.graalvm.polyglot.io.ByteSequence
import java.lang.ref.WeakReference
import java.lang.ref.Reference
import java.util.WeakHashMap
import java.util.function.LongSupplier
import java.util.function.Supplier
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.FutureTask
import java.util.concurrent.ExecutionException
import com.oracle.truffle.api.TruffleSafepoint
import com.oracle.truffle.api.nodes.Node
import java.util.concurrent.ConcurrentHashMap
import thc.ForeignBitcode
import thc.Language

/** Context-owned original C code and checked managed/native allocation views. */
internal class SulongCbits(private val env: TruffleLanguage.Env) {
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
        val system = System.getProperty("os.name").let {
            when { it.startsWith("Mac") -> "Darwin"; it.startsWith("Windows") -> "Windows"; else -> it }
        }
        fun architecture(value: String) = when (value.lowercase()) {
            "arm64" -> "aarch64"
            "amd64" -> "x86_64"
            else -> value
        }
        if (manifest["system"] != system || architecture(manifest["architecture"] as String) != architecture(System.getProperty("os.arch")))
            fault("Original C bitcode does not match this runtime platform")
    }
    private val windows = System.getProperty("os.name").startsWith("Windows")
    private val library by lazy { load(env, "md5") }
    private val iconvTask = FutureTask { load(env, "iconv") }
    private val strerrorTask = FutureTask { load(env, "strerror") }
    private val strerrorLocaleTask = FutureTask { load(env, "strerror-locale") }
    private val finalizerTask = FutureTask {
        val original = load(env, "libdw-unavailable")
        listOf("libdwPoolRelease", "backtraceFree").associateWith { symbol ->
            if (!interop.isMemberReadable(original, symbol)) fault("Missing original RTS finalizer: $symbol")
            val callable = interop.readMember(original, symbol)
            if (!interop.isExecutable(callable)) fault("Original RTS finalizer is not callable: $symbol")
            CFinalizerFunction(this, symbol, callable)
        }
    }
    private val ownedFree = CFinalizerFunction(this, "free", null)
    private fun originalFinalizers(): Map<String, CFinalizerFunction> {
        finalizerTask.run()
        return try {
            if (finalizerTask.isDone) finalizerTask.get()
            else TruffleSafepoint.setBlockedThreadInterruptibleFunction(null,
                TruffleSafepoint.InterruptibleFunction<FutureTask<Map<String, CFinalizerFunction>>,
                    Map<String, CFinalizerFunction>> { it.get() }, finalizerTask)
        } catch (failure: ExecutionException) { throw (failure.cause ?: failure) }
    }
    /** &free consumes only a context-owned malloc base; the libdw labels are original C. */
    internal fun finalizerLabel(symbol: String): ManagedAddress =
        ManagedAddress.fromCFinalizer((if (symbol == "free") ownedFree else originalFinalizers()[symbol])
            ?: fault("Unsupported original C function label: $symbol"))

    internal fun invokeFinalizer(function: CFinalizerFunction, address: ManagedAddress) {
        function.requireOwner(this)
        if (function.symbol == "free") {
            Language.currentState(null).nativeAllocations.free(address)
            return
        }
        val pointer = if (address === ManagedAddress.nullAddress()) 0L else {
            address.requireByteRegion(0)
            pointerTransport(address)
        }
        val threads = Language.currentState(null).threads
        val previous = threads.enterForeign()
        try { executeWithOwners(function.callable ?: fault("Missing original C finalizer"), pointer) }
        finally {
            threads.leaveForeign(previous)
            Reference.reachabilityFence(address)
        }
    }
    internal fun strerrorLibrary(): Any {
        strerrorTask.run()
        return try {
            if (strerrorTask.isDone) strerrorTask.get()
            else TruffleSafepoint.setBlockedThreadInterruptibleFunction(null,
                TruffleSafepoint.InterruptibleFunction<FutureTask<Any>, Any> { it.get() }, strerrorTask)
        } catch (failure: ExecutionException) { throw (failure.cause ?: failure) }
    }
    internal fun strerrorLocaleLibrary(): Any {
        strerrorLocaleTask.run()
        return try {
            if (strerrorLocaleTask.isDone) strerrorLocaleTask.get()
            else TruffleSafepoint.setBlockedThreadInterruptibleFunction(null,
                TruffleSafepoint.InterruptibleFunction<FutureTask<Any>, Any> { it.get() }, strerrorLocaleTask)
        } catch (failure: ExecutionException) { throw (failure.cause ?: failure) }
    }
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
    private val init by lazy { interop.readMember(library, "thc_md5_init") }
    private val update by lazy { interop.readMember(library, "thc_md5_update") }
    private val finish by lazy { interop.readMember(library, "thc_md5_final") }
    // Arrays have identity equality. Both sides are weak: a cached view must not
    // keep its weak key alive. A live LLVM pointer strongly retains its view.
    private val buffers = WeakHashMap<Any, WeakReference<CbitsBuffer>>()
    private val foreign = ConcurrentHashMap<Pair<String, String>, Any>()
    @Volatile private var foreignErrno: Any? = null
    @Volatile private var linkedForeign: ForeignBitcode? = null

    private fun sameLink(first: ForeignBitcode, second: ForeignBitcode) =
        first.unit == second.unit && first.module == second.module && first.target == second.target &&
            first.symbols == second.symbols && first.abi == second.abi && first.bytes.contentEquals(second.bytes)

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
        val errno = interop.readMember(library, "thc_capi_errno")
        synchronized(this) {
            linkedForeign?.let { require(sameLink(it, record)) { "Conflicting CAPI library identity" }; return }
            foreignErrno = errno
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
        // The original wrapper calls libc with a host-owned native timespec.
        // Its arena closes even if a guest copyback or context cancellation throws.
        val invoke = {
            address.requireRange(0, 16, writable = true)
            address.cbitsSegment()
            NativeLimbScope().use { scope ->
                val native = if (address.cbitsOwner()?.isPinned == true) scope.borrow(address, 16) else scope.allocate(16)
                val result = interop.execute(foreignFunction(unit, symbol), word, native)
                if (!interop.fitsInInt(result)) fault("CAPI result is not a CInt: $symbol")
                val value = interop.asInt(result).toLong()
                if (value != 0L && value != -1L) fault("CAPI clock status is outside the POSIX result domain")
                val errno = if (value < 0) capiErrno() else 0L
                if (value == 0L && !native.aliases(address))
                    native.copyTo(address.cbitsSegment(), address.cbitsOffset(), 16)
                CapiResult(value, errno)
            }
        }
        // A guest allocation may be shrunk by another host thread; hold its
        // owner through preflight, C call, and copyback. This exact CAPI
        // archive has no callbacks, so no guest execution occurs under it.
        val owner = address.cbitsOwner()
        return if (owner == null) invoke() else synchronized(owner) { invoke() }
    }

    fun capiErrno(): Long {
        val function = foreignErrno ?: fault("No linked CAPI errno domain")
        val value = interop.execute(function)
        if (!interop.fitsInInt(value)) fault("CAPI errno is not a CInt")
        return interop.asInt(value).toLong()
    }

    @Synchronized internal fun buffer(address: ManagedAddress, allowNativePointer: Boolean = true): CbitsBuffer {
        val bytes = address.cbitsBuffer()
        val owner = address.cbitsOwner()
        val key = address.cbitsStorageKey()
        if (!allowNativePointer) return CbitsBuffer(bytes, address.cbitsWritable(), LongSupplier { address.cbitsSize() })
        return buffers[key]?.get() ?: CbitsBuffer(bytes, address.cbitsWritable(),
            LongSupplier { address.cbitsSize() }, 0,
            if (address.nativeImageKey() == null) null else Supplier {
                val registry = NativeAddresses.current(null)
                registry.project(address)
                registry.transport(address) ?: fault("Missing immutable native image")
            }, if (owner?.isPinned == true) LongSupplier { address.toNativeBits() - address.cbitsOffset() } else null
        ).also { buffers[key] = WeakReference(it) }
    }
    private fun executeWithOwners(function: Any, vararg arguments: Any): Any? = try {
        interop.execute(function, *arguments)
    } finally {
        // Sulong may discard a managed pointer wrapper after extracting its bits.
        // Retain every owner until the complete native call has returned.
        arguments.forEach { Reference.reachabilityFence(it) }
    }
    private fun transport(address: ManagedAddress): Any =
        NativeAddresses.current(null).transport(address) ?: buffer(address)
    /** A C callback receives Addr# itself, unlike Cbits calls with a separate offset. */
    internal fun pointerTransport(address: ManagedAddress, allowNativePointer: Boolean = true): CbitsBuffer {
        address.requireByteRegion(0)
        val nativeImage = if (!allowNativePointer || address.nativeImageKey() == null) null else Supplier {
            val registry = NativeAddresses.current(null)
            registry.project(address)
            registry.transport(address) ?: fault("Missing immutable callback pointer image")
        }
        return CbitsBuffer(address.cbitsBuffer(), address.cbitsWritable(),
            LongSupplier { address.cbitsSize() }, address.cbitsOffset(), nativeImage,
            if (allowNativePointer && address.cbitsOwner()?.isPinned == true)
                LongSupplier { address.toNativeBits() - address.cbitsOffset() } else null)
    }
    fun init(context: ManagedAddress) {
        if (windows) WindowsMd5.init(context)
        else executeWithOwners(init, transport(context), context.cbitsOffset())
    }
    fun update(context: ManagedAddress, input: ManagedAddress, length: Int) {
        if (windows) WindowsMd5.update(context, input, length)
        else executeWithOwners(update, transport(context), context.cbitsOffset(), transport(input), input.cbitsOffset(), length)
    }
    fun finish(output: ManagedAddress, context: ManagedAddress) {
        if (windows) WindowsMd5.finish(output, context)
        else executeWithOwners(finish, transport(output), output.cbitsOffset(), transport(context), context.cbitsOffset())
    }
    companion object {
        @JvmStatic fun current(node: Node?): SulongCbits = Language.currentState(node).cbits()
    }
}

/** An opaque, context-owned C label; &free delegates to checked native ownership. */
internal class CFinalizerFunction internal constructor(
    private val owner: SulongCbits, val symbol: String, internal val callable: Any?
) {
    fun requireOwner(provider: SulongCbits) {
        if (provider !== owner) fault("C function label belongs to another THC context")
    }
    fun invoke(address: ManagedAddress) = owner.invokeFinalizer(this, address)
}

/** Byte interop over the original allocation; immutable views may acquire an owned native image. */
@ExportLibrary(InteropLibrary::class)
internal class CbitsBuffer @JvmOverloads constructor(bytes: ByteBuffer, private val writable: Boolean,
    private val logicalSize: LongSupplier = LongSupplier { bytes.capacity().toLong() },
    private val baseOffset: Long = 0,
    private val nativeImage: Supplier<NativeReadOnlyPointer>? = null,
    private val nativeAddress: LongSupplier? = null) : TruffleObject {
    @JvmOverloads constructor(bytes: ByteArray, writable: Boolean,
        logicalSize: LongSupplier = LongSupplier { bytes.size.toLong() }, baseOffset: Long = 0,
        nativeImage: Supplier<NativeReadOnlyPointer>? = null) :
        this(ByteBuffer.wrap(bytes), writable, logicalSize, baseOffset, nativeImage)
    private val little = bytes.duplicate().order(ByteOrder.LITTLE_ENDIAN)
    private val big = bytes.duplicate().order(ByteOrder.BIG_ENDIAN)
    private var pointer: NativeReadOnlyPointer? = null

    init {
        require(!writable || nativeImage == null) { "Mutable C buffers cannot use immutable native images" }
        require(baseOffset >= 0 && baseOffset <= logicalSize.asLong) { "C buffer address exceeds its allocation" }
    }

    @Synchronized @ExportMessage fun isPointer(): Boolean = nativeAddress != null || pointer?.isPointer() == true
    @Synchronized @ExportMessage fun toNative() {
        if (nativeImage != null && pointer == null) pointer = nativeImage.get()
    }
    @Synchronized @ExportMessage @Throws(UnsupportedMessageException::class)
    fun asPointer(): Long {
        if (!isPointer()) throw UnsupportedMessageException.create()
        return (nativeAddress?.asLong ?: pointer!!.asPointer()) + baseOffset
    }
    @ExportMessage fun hasBufferElements(): Boolean = true
    @ExportMessage fun isBufferWritable(): Boolean = writable
    @ExportMessage fun getBufferSize(): Long = maxOf(0L, logicalSize.asLong - baseOffset)

    private fun index(offset: Long, width: Int): Int {
        if (offset < 0 || offset > logicalSize.asLong - baseOffset - width)
            throw InvalidBufferOffsetException.create(offset, width.toLong())
        return Math.toIntExact(baseOffset + offset)
    }
    private fun requireWritable() { if (!writable) throw UnsupportedMessageException.create() }
    private fun view(order: ByteOrder): ByteBuffer = if (order == ByteOrder.LITTLE_ENDIAN) little else big

    @ExportMessage @TruffleBoundary @Throws(InvalidBufferOffsetException::class)
    fun readBuffer(offset: Long, destination: ByteArray, destinationOffset: Int, length: Int) {
        little.get(index(offset, length), destination, destinationOffset, length)
    }
    @ExportMessage @Throws(InvalidBufferOffsetException::class)
    fun readBufferByte(offset: Long): Byte = little.get(index(offset, 1))
    @ExportMessage @Throws(InvalidBufferOffsetException::class)
    fun readBufferShort(order: ByteOrder, offset: Long): Short = view(order).getShort(index(offset, 2))
    @ExportMessage @Throws(InvalidBufferOffsetException::class)
    fun readBufferInt(order: ByteOrder, offset: Long): Int = view(order).getInt(index(offset, 4))
    @ExportMessage @Throws(InvalidBufferOffsetException::class)
    fun readBufferLong(order: ByteOrder, offset: Long): Long = view(order).getLong(index(offset, 8))
    @ExportMessage @Throws(InvalidBufferOffsetException::class)
    fun readBufferFloat(order: ByteOrder, offset: Long): Float = view(order).getFloat(index(offset, 4))
    @ExportMessage @Throws(InvalidBufferOffsetException::class)
    fun readBufferDouble(order: ByteOrder, offset: Long): Double = view(order).getDouble(index(offset, 8))

    @ExportMessage @Throws(InvalidBufferOffsetException::class, UnsupportedMessageException::class)
    fun writeBufferByte(offset: Long, value: Byte) { requireWritable(); little.put(index(offset, 1), value) }
    @ExportMessage @Throws(InvalidBufferOffsetException::class, UnsupportedMessageException::class)
    fun writeBufferShort(order: ByteOrder, offset: Long, value: Short) { requireWritable(); view(order).putShort(index(offset, 2), value) }
    @ExportMessage @Throws(InvalidBufferOffsetException::class, UnsupportedMessageException::class)
    fun writeBufferInt(order: ByteOrder, offset: Long, value: Int) { requireWritable(); view(order).putInt(index(offset, 4), value) }
    @ExportMessage @Throws(InvalidBufferOffsetException::class, UnsupportedMessageException::class)
    fun writeBufferLong(order: ByteOrder, offset: Long, value: Long) { requireWritable(); view(order).putLong(index(offset, 8), value) }
    @ExportMessage @Throws(InvalidBufferOffsetException::class, UnsupportedMessageException::class)
    fun writeBufferFloat(order: ByteOrder, offset: Long, value: Float) { requireWritable(); view(order).putFloat(index(offset, 4), value) }
    @ExportMessage @Throws(InvalidBufferOffsetException::class, UnsupportedMessageException::class)
    fun writeBufferDouble(order: ByteOrder, offset: Long, value: Double) { requireWritable(); view(order).putDouble(index(offset, 8), value) }
}

internal object CFinalizerLabels {
    fun fromCore(symbol: String, proof: CoreRepresentation?): ManagedAddress {
        if (proof?.present != true || proof.kind != CoreKind.ADDRESS || proof.isAggregate ||
            proof.isVector || proof.primReps != listOf("AddrRep"))
            fault("Original C function label requires exact AddrRep proof")
        return Language.currentState(null).cbits().finalizerLabel(symbol)
    }
}
