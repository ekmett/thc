// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime

import com.oracle.truffle.api.Assumption
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
import com.oracle.truffle.api.TruffleLanguage
import com.oracle.truffle.api.TruffleSafepoint
import com.oracle.truffle.api.interop.InteropLibrary
import com.oracle.truffle.api.exception.AbstractTruffleException
import com.oracle.truffle.api.source.Source
import org.graalvm.polyglot.io.ByteSequence
import thc.Language
import thc.PackageScalarLink
import thc.PackageScalarSignature
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.lang.ref.Reference
import java.lang.foreign.MemorySegment
import java.util.function.LongSupplier

/** Component C globals and entrypoints live in one owning Truffle context. */
internal class PackageScalarLibraries(private val env: TruffleLanguage.Env) {
    private class Loaded(val link: PackageScalarLink, val task: FutureTask<Map<String, PackageScalarFunction>>)
    private val libraries = HashMap<String, Loaded>()
    private val alive = Assumption.create("THC package C libraries are open")
    private var closed = false
    private val interop = InteropLibrary.getUncached()
    private val pointerOperations = FutureTask {
        val bytes = PackageScalarLibraries::class.java.getResourceAsStream("/thc/cbits/package-pointer.bc")
            ?.use { it.readBytes() } ?: fault("Missing package pointer bridge")
        val library = env.parseInternal(Source.newBuilder("llvm", ByteSequence.create(bytes), "package-pointer.bc")
            .build()).call()
        listOf("offset", "equal", "compare", "difference", "overlap", "read", "write", "read_address", "write_address", "strlen", "copy", "fill")
            .associateWith { interop.readMember(library, "thc_package_pointer_$it") }
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
                    "${link.componentSha256}.${if (link.format == "llvm-embedded-elf") "so" else "bc"}").build()).call()
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
        return memory("offset", base, offset)!!
    }

    /** Operate on the actual Sulong pointer, including managed pointer carriers.
     * The request width/count is not a claim of malloc ownership or extent. */
    @TruffleBoundary
    fun memory(operation: String, vararg arguments: Any?): Any? {
        val owner = current()
        if (!alive.isValid) fault("Package C library registry is closed")
        pointerOperations.run()
        val function = await(pointerOperations).getValue(operation)
        val previous = owner.threads.enterForeign()
        try { return interop.execute(function, *arguments) }
        finally { owner.threads.leaveForeign(previous); Reference.reachabilityFence(arguments) }
    }

    @TruffleBoundary fun transport(address: ManagedAddress): Any {
        val owner = current()
        address.returnedAddress()?.let { return it.transport() }
        if (address === ManagedAddress.nullAddress()) return PackageNativePointer(0L, null)
        if (address.stableHandle() != null) return owner.stablePointers.nativeTransport(address)
        address.requireByteRegion(0)
        if (address.hasNativeStorage() || address.nativeImageKey() != null)
            return PackageNativePointer(address.toNativeBits(), null)
        return pointer(CbitsBuffer(address.cbitsBuffer(), address.cbitsWritable(),
            LongSupplier { address.cbitsSize() }, identity = address.cbitsStorageKey()), address.cbitsOffset())
    }

    /** Numeric comparison grants no byte access. Only genuine native pointers
     * may compare against raw native bits; managed carriers keep their identity. */
    @TruffleBoundary fun comparisonTransport(address: ManagedAddress): Any {
        current()
        address.numericBits()?.let { return PackageNativePointer(it, null) }
        return transport(address)
    }

    /** Recover only a relative, in-range alias of an actual argument carrier.
     * The pure subtraction cannot project unrelated managed objects; that case
     * remains unknown. Equality after displacement independently checks identity. */
    @TruffleBoundary fun managedAliasOffset(result: Any, argument: Any, offset: Long, size: Long): Long? {
        current()
        val relative = try { interop.asLong(memory("difference", result, argument)) }
        catch (_: AbstractTruffleException) { return null }
        val absolute = try { Math.addExact(offset, relative) } catch (_: ArithmeticException) { return null }
        if (absolute < 0 || absolute > size) return null
        return relative.takeIf { interop.asInt(memory("equal", result, pointer(argument, relative))) != 0 }
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

/** A genuine C pointer retains its Sulong carrier and any known checked backing.
 * Unknown external storage follows C's lifetime contract: neither this tag nor
 * library availability invents an allocation extent or a matching deallocator. */
internal class PackageReturnedAddress(val owner: Language.State, private val alive: Assumption,
    val carrier: Any, val backing: ManagedAddress?, private val displacement: Long = 0L) {
    fun requireCurrent() {
        if (Language.currentState() !== owner) fault("Returned package C pointer belongs to another context")
        if (!owner.env.isNativeAccessAllowed) fault("Returned package C pointer requires native access")
        if (!alive.isValid) fault("Returned package C pointer registry is closed")
    }
    @TruffleBoundary fun transport(): Any {
        requireCurrent()
        // A returned alias may originally refer to a call-scoped native
        // transport. Re-project its retained, checked backing, not that lease.
        backing?.let { address ->
            address.requireByteRegion(0)
            if (address.hasNativeStorage() || address.nativeImageKey() != null)
                return PackageNativePointer(address.toNativeBits(), null)
        }
        return if (displacement == 0L) carrier else owner.packageCbits.pointer(carrier, displacement)
    }
    @TruffleBoundary fun bits(): Long {
        requireCurrent()
        backing?.let { return it.toNativeBits() }
        val pointer = transport()
        val interop = InteropLibrary.getUncached()
        if (!interop.isPointer(pointer)) fault("Returned managed C pointer has no native address bits")
        return interop.asPointer(pointer)
    }
    @TruffleBoundary fun isNative(): Boolean {
        requireCurrent()
        return InteropLibrary.getUncached().isPointer(transport())
    }
    /** A single IO request's window, not an allocation-size or ownership claim. */
    @TruffleBoundary fun <T> withNativeWindow(count: Long, writable: Boolean, body: (MemorySegment) -> T): T {
        requireRange(0, count, writable)
        val segment = MemorySegment.ofAddress(bits()).reinterpret(count)
        try { return body(segment) } finally { Reference.reachabilityFence(this) }
    }
    @TruffleBoundary fun compare(other: ManagedAddress, operation: String): Long {
        requireCurrent()
        return ManagedAddress.withNativeBorrows(listOf(ManagedAddress.fromReturnedAddress(this), other)) {
            InteropLibrary.getUncached().asLong(owner.packageCbits.memory(operation, transport(), owner.packageCbits.comparisonTransport(other)))
        }
    }
    fun requireRange(offset: Long, count: Long, writable: Boolean = false) {
        requireCurrent()
        if (count < 0) fault("Negative returned C pointer access count")
        val relative = try { Math.addExact(displacement, offset).also { Math.addExact(it, count) } }
        catch (_: ArithmeticException) { fault("Returned C pointer access offset overflow") }
        backing?.let { it.requireRange(offset, count, writable); return }
        val interop = InteropLibrary.getUncached()
        if (interop.isPointer(carrier)) {
            val base = interop.asPointer(carrier)
            val start = base + relative
            if (relative >= 0 && java.lang.Long.compareUnsigned(start, base) < 0 ||
                relative < 0 && java.lang.Long.compareUnsigned(start, base) > 0 ||
                java.lang.Long.compareUnsigned(start + count, start) < 0 || start == 0L && count != 0L)
                fault("Returned C pointer access wraps native address space")
        }
    }
    @TruffleBoundary fun overlaps(count: Long, other: ManagedAddress, otherCount: Long): Boolean {
        requireRange(0, count); other.requireRange(0, otherCount)
        return InteropLibrary.getUncached().asInt(owner.packageCbits.memory("overlap", transport(), count,
            owner.packageCbits.transport(other), otherCount)) != 0
    }
    @TruffleBoundary fun read(offset: Long, width: Int): Long {
        requireRange(offset, width.toLong())
        return InteropLibrary.getUncached().asLong(owner.packageCbits.memory("read", transport(), offset, width))
    }
    @TruffleBoundary fun write(offset: Long, width: Int, value: Long) {
        requireRange(offset, width.toLong(), true)
        owner.packageCbits.memory("write", transport(), offset, width, value)
    }
    @TruffleBoundary fun cStringLength(): Long {
        requireCurrent()
        backing?.let { return it.cStringLength() }
        return InteropLibrary.getUncached().asLong(owner.packageCbits.memory("strlen", transport())).also {
            if (it < 0) fault("Returned C string length exceeds signed count domain")
        }
    }
    @TruffleBoundary fun copyOut(count: Long): ByteArray {
        requireRange(0, count)
        if (count > Int.MAX_VALUE) fault("Returned C copy exceeds managed array size")
        val bytes = ByteArray(count.toInt())
        copyOutTo(bytes, 0, count)
        return bytes
    }
    @TruffleBoundary fun copyOutTo(bytes: ByteArray, offset: Long, count: Long) {
        requireRange(0, count)
        if (count != 0L) owner.packageCbits.memory("copy",
            owner.packageCbits.pointer(CbitsBuffer(bytes, true), offset), transport(), count)
    }
    @TruffleBoundary fun copyIn(bytes: ByteArray) = copyInFrom(bytes, 0, bytes.size.toLong())
    @TruffleBoundary fun copyInFrom(bytes: ByteArray, offset: Long, count: Long) {
        requireRange(0, count, true)
        if (count != 0L) owner.packageCbits.memory("copy", transport(),
            owner.packageCbits.pointer(CbitsBuffer(bytes, false), offset), count)
    }
    @TruffleBoundary fun fill(count: Long, value: Long) {
        requireRange(0, count, true)
        if (count != 0L) owner.packageCbits.memory("fill", transport(), count, value.toInt() and 255)
    }
    @TruffleBoundary fun readAddress(offset: Long): ManagedAddress {
        requireRange(offset, 8)
        val result = owner.packageCbits.memory("read_address", transport(), offset)!!
        val interop = InteropLibrary.getUncached()
        if (interop.isNull(result)) return ManagedAddress.nullAddress()
        val bits = if (interop.isPointer(result)) interop.asPointer(result) else null
        val known = bits?.let { owner.stablePointers.recoverToken(it) ?: owner.nativeAllocations.recoverAddress(it)
            ?: owner.nativeAddresses.recover(it).takeIf { address -> address.nativeImageKey() != null || address.hasNativeStorage() } }
        return known ?: ManagedAddress.fromReturnedAddress(PackageReturnedAddress(owner, alive, result, null))
    }
    @TruffleBoundary fun writeAddress(offset: Long, address: ManagedAddress) {
        requireRange(offset, 8, true)
        address.withNativeBorrow {
            val pointer = owner.packageCbits.transport(address)
            owner.packageCbits.memory("write_address", transport(), offset, pointer)
        }
    }
    // Backing arithmetic can dispatch through ManagedAddress.plus again. Keep
    // this uncommon foreign-provenance path outside recursive partial evaluation;
    // ordinary managed and pinned address arithmetic remains compiled.
    @TruffleBoundary
    fun plus(displacement: Long): PackageReturnedAddress {
        requireCurrent()
        if (displacement == 0L) return this
        val offset = try { Math.addExact(this.displacement, displacement) }
            catch (_: ArithmeticException) { fault("Returned C pointer offset overflow") }
        return PackageReturnedAddress(owner, alive, carrier, backing?.plus(displacement), offset)
    }
}
