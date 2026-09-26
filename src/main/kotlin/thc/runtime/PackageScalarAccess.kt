// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime

import com.oracle.truffle.api.CompilerDirectives
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
import com.oracle.truffle.api.interop.InteropLibrary
import com.oracle.truffle.api.interop.TruffleObject
import com.oracle.truffle.api.interop.UnsupportedMessageException
import com.oracle.truffle.api.library.ExportLibrary
import com.oracle.truffle.api.library.ExportMessage
import com.oracle.truffle.api.nodes.ExplodeLoop
import com.oracle.truffle.api.nodes.Node
import thc.Language
import java.lang.ref.Reference
import java.util.IdentityHashMap
import java.util.function.LongSupplier
import java.util.function.Supplier

/** Convert directly into the interop carrier without an intermediate boxed Long. */
internal fun packageScalarInt32(value: Long): Int {
    if (value != value.toInt().toLong()) fault("Package C Int32 argument is out of range")
    return value.toInt()
}

/** C receives the declared width; unsigned narrowing preserves all low bits. */
internal fun packageCInteger(rep: String, value: Long): Any = when (rep) {
    "Int8Rep" -> value.toByte().also { if (it.toLong() != value) fault("Package C Int8 argument is out of range") }
    "Word8Rep" -> value.toByte().also { if (value !in 0..255L) fault("Package C Word8 argument is out of range") }
    "Int16Rep" -> value.toShort().also { if (it.toLong() != value) fault("Package C Int16 argument is out of range") }
    "Word16Rep" -> value.toShort().also { if (value !in 0..65535L) fault("Package C Word16 argument is out of range") }
    "Int32Rep" -> packageScalarInt32(value)
    "Word32Rep" -> value.toInt().also { if (value !in 0..0xffff_ffffL) fault("Package C Word32 argument is out of range") }
    else -> fault("Package C argument is not a narrow integer")
}

/** One adopted call site for an exact component ABI in the current EXCLUSIVE context policy. */
internal class PackageScalarAccess(private val call: PackageScalarCall) : Node() {
    @field:CompilationFinal(dimensions = 1) private val argumentReps = call.arguments.copyOf()
    @Volatile @CompilationFinal private var cached: PackageScalarFunction? = null
    @Child private var calls = InteropLibrary.getFactory().createDispatched(1)
    @Child private var numbers = InteropLibrary.getFactory().createDispatched(1)
    private val pointers = argumentReps.any { it in setOf("AddrRep", "ByteArray#", "MutableByteArray#") }
    private val integerResult = when (call.result) {
        "Int8Rep", "Word8Rep", "Int16Rep", "Word16Rep", "Int32Rep", "Word32Rep",
        "IntRep", "WordRep", "Int64Rep", "Word64Rep" -> true
        else -> false
    }
    private val addressResult = call.result == "AddrRep"

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
        if (arguments.size != argumentReps.size) fault("Package C argument count mismatch")
        // The signature bounds explosion even when the caller's array length is dynamic.
        for (index in 0 until argumentReps.size) {
            val valid = when (argumentReps[index]) {
                "Int8Rep", "Word8Rep" -> arguments[index] is Byte
                "Int16Rep", "Word16Rep" -> arguments[index] is Short
                "Int32Rep", "Word32Rep" -> arguments[index] is Int
                "IntRep", "WordRep", "Int64Rep", "Word64Rep" -> arguments[index] is Long
                "FloatRep" -> arguments[index] is Float
                "DoubleRep" -> arguments[index] is Double
                "AddrRep" -> arguments[index] is ManagedAddress
                "ByteArray#", "MutableByteArray#" -> arguments[index] is ByteArray || arguments[index] is ManagedAllocation
                else -> false
            }
            if (!valid) fault("Package C argument differs from its scalar carrier")
        }
        return function()
    }

    private fun invoke(entry: PackageScalarFunction, arguments: Array<Any?>): Any? {
        val threads = entry.owner.threads
        val previous = threads.enterForeign()
        try {
            return if (pointers) invokePointers(entry, arguments)
                else normalizeResult(entry, Calls.interop(calls, entry.receiver, arguments))
        } finally {
            threads.leaveForeign(previous)
            Reference.reachabilityFence(arguments)
        }
    }

    @TruffleBoundary
    private fun invokePointers(entry: PackageScalarFunction, arguments: Array<Any?>): Any? {
        val addresses = argumentReps.mapIndexedNotNull { index, rep ->
            when (rep) {
                "AddrRep" -> index to (arguments[index] as ManagedAddress)
                "ByteArray#", "MutableByteArray#" -> index to ManagedAddress.fromGuestByteArray(arguments[index])
                else -> null
            }
        }
        // Borrow each distinct malloc owner through return, in the same order
        // as address-to-address copies. Managed views retain aliases directly.
        return ManagedAddress.withNativeBorrows(addresses.map { it.second }) {
            val converted = arguments.copyOf()
            val lease = PackagePointerLease()
            try {
                // One base object per allocation: Sulong compares allocation
                // identity plus its own pointer offset, not buffer contents.
                // Validate all views before exposing any pointer to guest C.
                val buffers = IdentityHashMap<Any, PackagePointerBuffer>()
                val views = IdentityHashMap<ManagedAddress, PackagePointerBuffer>()
                for ((index, address) in addresses) {
                    if (address === ManagedAddress.nullAddress()) continue
                    if (address.stableHandle() != null) {
                        entry.owner.stablePointers.validate(address)
                        continue
                    }
                    if (address.nativeAllocation() != null) {
                        address.requireByteRegion(0)
                        continue
                    }
                    address.requireByteRegion(0, argumentReps[index] == "MutableByteArray#")
                    val writable = argumentReps[index] != "ByteArray#" && address.cbitsWritable()
                    val key = address.nativeImageKey() ?: address.cbitsBacking()
                    val buffer = buffers.getOrPut(key) { PackagePointerBuffer(address, writable) }
                    if (buffer.address.cbitsOwner() == null && address.cbitsOwner() != null) buffer.address = address
                    buffer.writable = buffer.writable || writable
                    views[address] = buffer
                }
                for (buffer in buffers.values) {
                    val address = buffer.address
                    val nativeImage = if (address.nativeImageKey() == null) null else Supplier {
                        // project ensures the image exists; its offset-bearing
                        // result is deliberately ignored. transport returns the
                        // image base, and Sulong retains the pointer's offset.
                        entry.owner.nativeAddresses.project(address)
                        entry.owner.nativeAddresses.transport(address) ?: fault("Missing immutable C pointer image")
                    }
                    buffer.transport = CbitsBuffer(address.cbitsBacking(), buffer.writable,
                        LongSupplier { address.cbitsSize() }, 0, nativeImage)
                }
                for ((index, address) in addresses) {
                    converted[index] = when {
                        address === ManagedAddress.nullAddress() -> PackageNativePointer(0L, lease)
                        address.stableHandle() != null ->
                            entry.owner.stablePointers.nativeTransport(address)
                        address.nativeAllocation() != null -> {
                            address.requireByteRegion(0)
                            PackageNativePointer(address.toNativeBits(), lease)
                        }
                        else -> entry.owner.packageCbits.pointer(views.getValue(address).transport!!, address.cbitsOffset())
                    }
                }
                // Pointer results can alias call-scoped native transports. Read
                // their bits before releasing leases and allocation borrows.
                normalizeResult(entry, Calls.interop(calls, entry.receiver, converted))
            } finally {
                lease.open = false
                Reference.reachabilityFence(addresses)
                Reference.reachabilityFence(converted)
            }
        }
    }

    fun executeLong(arguments: Array<Any?>, state: Any?): Long {
        if (!integerResult) fault("Package C result is not an integer ABI")
        val entry = prepare(arguments, state)
        val result = invoke(entry, arguments)
        return when (call.result) {
            "Int8Rep", "Word8Rep" -> {
                if (!numbers.fitsInByte(result)) fault("Package C result is not an 8-bit integer")
                val value = numbers.asByte(result)
                if (call.result == "Word8Rep") value.toLong() and 255L else value.toLong()
            }
            "Int16Rep", "Word16Rep" -> {
                if (!numbers.fitsInShort(result)) fault("Package C result is not a 16-bit integer")
                val value = numbers.asShort(result)
                if (call.result == "Word16Rep") value.toLong() and 65535L else value.toLong()
            }
            "Int32Rep", "Word32Rep" -> {
                if (!numbers.fitsInInt(result)) fault("Package C result is not Int32")
                val value = numbers.asInt(result)
                if (call.result == "Word32Rep") Integer.toUnsignedLong(value) else value.toLong()
            }
            "IntRep", "WordRep", "Int64Rep", "Word64Rep" -> {
                if (!numbers.fitsInLong(result)) fault("Package C result is not Int64")
                numbers.asLong(result)
            }
            else -> fault("Package C result is not an integer ABI")
        }
    }

    fun executeFloat(arguments: Array<Any?>, state: Any?): Float {
        if (call.result != "FloatRep") fault("Package C result is not a Float ABI")
        val entry = prepare(arguments, state)
        val result = invoke(entry, arguments)
        if (!numbers.fitsInFloat(result)) fault("Package C result is not Float")
        return numbers.asFloat(result)
    }

    fun executeDouble(arguments: Array<Any?>, state: Any?): Double {
        if (call.result != "DoubleRep") fault("Package C result is not a Double ABI")
        val entry = prepare(arguments, state)
        val result = invoke(entry, arguments)
        if (!numbers.fitsInDouble(result)) fault("Package C result is not Double")
        return numbers.asDouble(result)
    }

    fun executeVoid(arguments: Array<Any?>, state: Any?) {
        if (call.result != "void") fault("Package C result is not void")
        invoke(prepare(arguments, state), arguments)
    }

    fun executeAddress(arguments: Array<Any?>, state: Any?): ManagedAddress {
        if (!addressResult) fault("Package C result is not a pointer ABI")
        val entry = prepare(arguments, state)
        return invoke(entry, arguments) as ManagedAddress
    }

    private fun normalizeResult(entry: PackageScalarFunction, result: Any?): Any? {
        if (!addressResult) return result
        if (numbers.isNull(result)) return ManagedAddress.nullAddress()
        if (!numbers.isPointer(result)) fault("Package C returned a non-native opaque pointer")
        val bits = numbers.asPointer(result)
        // A return type never grants ownership or permission to dereference C
        // storage. StablePtr identities are the sole recovered authority here.
        return entry.owner.stablePointers.recoverToken(bits) ?: ManagedAddress.unownedNumeric(bits)
    }
}

private class PackagePointerBuffer(var address: ManagedAddress, var writable: Boolean) {
    var transport: CbitsBuffer? = null
}

/** Native pointers cannot outlive the synchronous call's allocation borrows. */
internal class PackagePointerLease { @Volatile var open = true }

@ExportLibrary(InteropLibrary::class)
internal class PackageNativePointer(private val bits: Long, private val lease: PackagePointerLease) : TruffleObject {
    @ExportMessage fun isPointer(): Boolean = lease.open
    @ExportMessage fun asPointer(): Long {
        if (!lease.open) throw UnsupportedMessageException.create()
        return bits
    }
}
