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
import com.oracle.truffle.api.utilities.TriState
import com.oracle.truffle.api.source.Source
import org.graalvm.polyglot.io.ByteSequence
import java.lang.ref.WeakReference
import java.lang.ref.Reference
import java.util.WeakHashMap
import java.util.function.LongSupplier
import java.util.function.Supplier
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.lang.foreign.MemorySegment
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
    private val textTask = FutureTask { load(env, "text") }
    private val waitStatusTask = FutureTask { load(env, "wait-status") }
    internal fun waitStatus(operation: OriginalStdioOp, status: Int): Long {
        if (System.getProperty("os.name") != "Linux" || System.getProperty("os.arch") !in setOf("amd64", "x86_64"))
            fault("Original unix wait status currently requires Linux x86_64")
        waitStatusTask.run()
        val library = try {
            if (waitStatusTask.isDone) waitStatusTask.get()
            else TruffleSafepoint.setBlockedThreadInterruptibleFunction(null,
                TruffleSafepoint.InterruptibleFunction<FutureTask<Any>, Any> { it.get() }, waitStatusTask)
        } catch (failure: ExecutionException) { throw (failure.cause ?: failure) }
        val result = interop.execute(interop.readMember(library, "thc_wait_${operation.name}"), status)
        if (!interop.fitsInInt(result)) fault("Original unix wait-status result is not CInt")
        return interop.asInt(result).toLong()
    }
    internal fun textFunction(operation: TextForeignOp): Any {
        if (System.getProperty("os.name") != "Linux" || System.getProperty("os.arch") !in setOf("amd64", "x86_64"))
            fault("Original text cbits currently require Linux x86_64")
        textTask.run()
        val library = try {
            if (textTask.isDone) textTask.get()
            else TruffleSafepoint.setBlockedThreadInterruptibleFunction(null,
                TruffleSafepoint.InterruptibleFunction<FutureTask<Any>, Any> { it.get() }, textTask)
        } catch (failure: ExecutionException) { throw (failure.cause ?: failure) }
        return interop.readMember(library, operation.symbol)
    }
    internal fun text(function: Any, operation: TextForeignOp, bytes: CbitsBuffer, offset: Long, length: Long, count: Long): Long {
        val argument = if (operation == TextForeignOp.MEMCHR) count.toByte() else count
        val result = executeWithOwners(function, bytes, offset, length, argument)
        if (!interop.fitsInLong(result)) fault("Original text result is not ssize_t")
        return interop.asLong(result)
    }
    internal fun textReverse(function: Any, destination: CbitsBuffer, source: CbitsBuffer, offset: Long, length: Long) {
        executeWithOwners(function, destination, source, offset, length)
    }
    private val utf8Task = FutureTask { load(env, "bytestring-utf8") }
    internal fun utf8Function(): Any {
        if (System.getProperty("os.name") != "Linux" || System.getProperty("os.arch") !in setOf("amd64", "x86_64"))
            fault("Original ByteString UTF-8 cbits currently require Linux x86_64")
        utf8Task.run()
        val library = try {
            if (utf8Task.isDone) utf8Task.get()
            else TruffleSafepoint.setBlockedThreadInterruptibleFunction(null,
                TruffleSafepoint.InterruptibleFunction<FutureTask<Any>, Any> { it.get() }, utf8Task)
        } catch (failure: ExecutionException) { throw (failure.cause ?: failure) }
        return interop.readMember(library, "bytestring_is_valid_utf8")
    }
    internal fun utf8Validate(function: Any, bytes: Any, length: Long): Long {
        val result = executeWithOwners(function, bytes, length)
        if (!interop.fitsInInt(result)) fault("Original ByteString UTF-8 result is not CInt")
        val value = interop.asInt(result)
        if (value !in 0..1) fault("Original ByteString UTF-8 result is not boolean")
        return value.toLong()
    }
    private val finalizerTask = FutureTask {
        val original = if (windows) null else load(env, "libdw-unavailable")
        listOf("libdwPoolRelease", "backtraceFree").associateWith { symbol ->
            if (windows) return@associateWith CFinalizerFunction(this, symbol, WindowsLibdwFinalizers.function(symbol))
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
        if (Language.currentState(null).cbits() !== this) fault("C finalizer belongs to another THC context")
        if (function.symbol == "free") {
            Language.currentState(null).nativeAllocations.free(address)
            return
        }
        if (windows) {
            val threads = Language.currentState(null).threads
            val previous = threads.enterForeign()
            try { WindowsLibdwFinalizers.invoke(function.callable as java.lang.invoke.MethodHandle, address) }
            finally { threads.leaveForeign(previous); Reference.reachabilityFence(address) }
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
    private val foreignErrno = ConcurrentHashMap<Pair<String, String>, Any>()
    private val linkedForeign = ConcurrentHashMap<Pair<String, String>, ForeignBitcode>()

    private fun sameLink(first: ForeignBitcode, second: ForeignBitcode) =
        first.unit == second.unit && first.module == second.module && first.target == second.target &&
            first.symbols == second.symbols && first.abi == second.abi && first.bytes.contentEquals(second.bytes)

    /** Parse and resolve every declared CAPI symbol before a guest entry runs. */
    fun link(record: ForeignBitcode) {
        val key = record.unit to record.module
        linkedForeign[key]?.let { require(sameLink(it, record)) { "Conflicting CAPI library identity" }; return }
        val library = env.parseInternal(Source.newBuilder("llvm", ByteSequence.create(record.bytes),
            "${record.unit}-${record.module}.bc").build()).call()
        val resolved = record.symbols.associateWith { symbol ->
            require(interop.isMemberReadable(library, symbol)) { "Missing compiled original CAPI symbol: $symbol" }
            interop.readMember(library, symbol)
        }
        require(interop.isMemberReadable(library, "thc_capi_errno")) { "CAPI library lacks errno bridge" }
        val errno = interop.readMember(library, "thc_capi_errno")
        synchronized(this) {
            linkedForeign[key]?.let { require(sameLink(it, record)) { "Conflicting CAPI library identity" }; return }
            require(resolved.keys.none { foreign.containsKey(record.unit to it) }) { "Conflicting CAPI symbol owner" }
            for ((symbol, function) in resolved) {
                foreign[record.unit to symbol] = function
                foreignErrno[record.unit to symbol] = errno
            }
            linkedForeign[key] = record
        }
    }

    private fun foreignFunction(unit: String, symbol: String): Any = foreign[unit to symbol]
        ?: fault("Unlinked original CAPI target: $unit:$symbol")

    fun capiZero(unit: String, symbol: String, timeClock: Boolean = false): Long {
        val value = interop.execute(foreignFunction(unit, symbol))
        if (timeClock) {
            if (!interop.fitsInInt(value)) fault("CAPI clock identifier is not a CInt: $symbol")
            return interop.asInt(value).toLong()
        }
        if (!interop.fitsInLong(value)) fault("CAPI result is not a machine word: $symbol")
        return interop.asLong(value)
    }

    fun capiWordAddress(unit: String, symbol: String, word: Long, address: ManagedAddress): CapiResult =
        capiWordAddress(CapiCall(unit, symbol, false), word, address)

    fun capiWordAddress(call: CapiCall, word: Long, address: ManagedAddress): CapiResult {
        val clock: Any = if (call.timeClock) {
            val value = packageScalarInt32(word)
            // Linux negative IDs encode host descriptors/processes. -1 is the
            // reserved invalid clock, useful for the original errno contract.
            if (value < -1) fault("Dynamic time clock IDs require an owned descriptor or guest CPU-clock translation")
            value
        } else word
        if (call.timeClock && call.resolution && address === ManagedAddress.nullAddress()) {
            val result = interop.execute(foreignFunction(call.unit, call.symbol), clock,
                NativeLimbScope.Pointer(MemorySegment.NULL, 0))
            if (!interop.fitsInInt(result)) fault("CAPI result is not a CInt: ${call.symbol}")
            val value = interop.asInt(result).toLong()
            if (value != 0L && value != -1L) fault("CAPI clock status is outside the POSIX result domain")
            return CapiResult(value, if (value < 0) capiErrno(call.unit, call.symbol) else 0L)
        }
        // The original wrapper calls libc with a host-owned native timespec.
        // Its arena closes even if a guest copyback or context cancellation throws.
        val invoke = {
            address.requireByteRegion(16, true)
            if (!call.timeClock) address.cbitsSegment()
            NativeLimbScope().use { scope ->
                val native = if (!call.timeClock && address.cbitsOwner()?.isPinned == true) scope.borrow(address, 16) else scope.allocate(16)
                val result = interop.execute(foreignFunction(call.unit, call.symbol), clock, native)
                if (!interop.fitsInInt(result)) fault("CAPI result is not a CInt: ${call.symbol}")
                val value = interop.asInt(result).toLong()
                if (value != 0L && value != -1L) fault("CAPI clock status is outside the POSIX result domain")
                val errno = if (value < 0) capiErrno(call.unit, call.symbol) else 0L
                if (value == 0L && !native.aliases(address)) {
                    if (call.timeClock) {
                        address.writeNativeScalar(0, 8, native.readWord(0))
                        address.writeNativeScalar(1, 8, native.readWord(1))
                    } else native.copyTo(address.cbitsSegment(), address.cbitsOffset(), 16)
                }
                CapiResult(value, errno)
            }
        }
        // A guest allocation may be shrunk by another host thread; hold its
        // owner through preflight, C call, and copyback. This exact CAPI
        // archive has no callbacks, so no guest execution occurs under it.
        return address.withNativeBorrow {
            val owner = address.cbitsOwner()
            if (owner == null) invoke() else synchronized(owner) { invoke() }
        }
    }

    fun capiErrno(unit: String, symbol: String): Long {
        val function = foreignErrno[unit to symbol] ?: fault("No linked CAPI errno domain")
        val value = interop.execute(function)
        if (!interop.fitsInInt(value)) fault("CAPI errno is not a CInt")
        return interop.asInt(value).toLong()
    }

    @Synchronized internal fun buffer(address: ManagedAddress, allowNativePointer: Boolean = true): CbitsBuffer {
        val bytes = address.cbitsBuffer()
        val owner = address.cbitsOwner()
        val key = address.cbitsStorageKey()
        if (!allowNativePointer) return CbitsBuffer(bytes, address.cbitsWritable(), CbitsBufferSize { address.cbitsSize() })
        return buffers[key]?.get() ?: CbitsBuffer(bytes, address.cbitsWritable(),
            CbitsBufferSize { address.cbitsSize() }, 0,
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
            CbitsBufferSize { address.cbitsSize() }, address.cbitsOffset(), nativeImage,
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

/** A live buffer bound, kept separate from unrelated host/compiler numeric callbacks. */
internal fun interface CbitsBufferSize { fun size(): Long }

/** Byte interop over the original allocation; immutable views may acquire an owned native image. */
@ExportLibrary(InteropLibrary::class)
internal class CbitsBuffer @JvmOverloads constructor(bytes: ByteBuffer, private val writable: Boolean,
    private val logicalSize: CbitsBufferSize = CbitsBufferSize { bytes.capacity().toLong() },
    private val baseOffset: Long = 0,
    private val nativeImage: Supplier<NativeReadOnlyPointer>? = null,
    private val nativeAddress: LongSupplier? = null,
    private val identity: Any = bytes) : TruffleObject {
    @JvmOverloads constructor(bytes: ByteArray, writable: Boolean,
        logicalSize: CbitsBufferSize = CbitsBufferSize { bytes.size.toLong() }, baseOffset: Long = 0,
        nativeImage: Supplier<NativeReadOnlyPointer>? = null, identity: Any = bytes) :
        this(ByteBuffer.wrap(bytes), writable, logicalSize, baseOffset, nativeImage, identity = identity)
    private val little = bytes.duplicate().order(ByteOrder.LITTLE_ENDIAN)
    private val big = bytes.duplicate().order(ByteOrder.BIG_ENDIAN)
    private var pointer: NativeReadOnlyPointer? = null

    init {
        require(!writable || nativeImage == null) { "Mutable C buffers cannot use immutable native images" }
        require(baseOffset >= 0 && baseOffset <= logicalSize.size()) { "C buffer address exceeds its allocation" }
    }

    /** New transport views are still the same original allocation, including
     * read-only and writable aliases. No native address is fabricated. */
    @ExportMessage fun isIdenticalOrUndefined(other: Any): TriState =
        if (other is CbitsBuffer) TriState.valueOf(identity === other.identity && baseOffset == other.baseOffset)
        else TriState.UNDEFINED
    @ExportMessage fun identityHashCode(): Int = 31 * System.identityHashCode(identity) + baseOffset.hashCode()

    @Synchronized @ExportMessage fun isPointer(): Boolean = nativeAddress != null || pointer?.isPointer() == true
    @Synchronized @ExportMessage @TruffleBoundary fun toNative() {
        if (nativeImage != null && pointer == null) pointer = nativeImage.get()
    }
    @Synchronized @ExportMessage @TruffleBoundary @Throws(UnsupportedMessageException::class)
    fun asPointer(): Long {
        if (!isPointer()) throw UnsupportedMessageException.create()
        return (nativeAddress?.asLong ?: pointer!!.asPointer()) + baseOffset
    }
    @ExportMessage fun hasBufferElements(): Boolean = true
    @ExportMessage fun isBufferWritable(): Boolean = writable
    @ExportMessage fun getBufferSize(): Long = maxOf(0L, logicalSize.size() - baseOffset)

    private fun index(offset: Long, width: Int): Int {
        if (offset < 0 || offset > logicalSize.size() - baseOffset - width)
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
