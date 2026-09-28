// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
import com.oracle.truffle.api.nodes.Node
import java.nio.channels.ClosedChannelException
import thc.Language

/** ABI marshalling and declared activation. Interpreter hooks own the completed
 * result/errno continuation and delivery cut; no process effect is retried here. */
internal class ManagedProcessForeign {
    private class InterruptedWait : RuntimeException(null, null, false, false)
    private val context = Language.currentState()
    private val failures = ProcessFailureStage.entries.associateWith { stage ->
        if (stage == ProcessFailureStage.NONE) ManagedAddress.nullAddress() else {
            val bytes = stage.operation.toByteArray(Charsets.US_ASCII) + byteArrayOf(0)
            ManagedAddress.fromHex(bytes.joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') })
                .also { it.toNativeBits() } // Stage immutable native images before any launch effect.
        }
    }

    companion object {
        @JvmStatic fun current(node: Node?): ManagedProcessForeign = Language.currentState(node).files.processForeign
    }

    /** Exact declaration activation. Native cancellation only observes pending
     * work; the interpreter claims it after saving the returned scalar/errno. */
    @TruffleBoundary fun invoke(operation: ProcessOp, arguments: Array<Any?>, node: Node): Long {
        val threads = context.threads
        val previous = threads.enterForeign(if (operation == ProcessOp.WAIT) ForeignSafety.INTERRUPTIBLE else ForeignSafety.UNSAFE)
        return try {
            try {
                execute(operation, arguments, node, if (operation == ProcessOp.WAIT) ({
                    if (threads.interruptibleForeignPending()) throw InterruptedWait()
                }) else null)
            } catch (_: InterruptedWait) {
                // No waitpid/reap occurred and the caller's output is untouched.
                context.stdio.captureForeignErrno(4) // Linux EINTR, matching this transport.
                -1L
            }
        } finally { threads.leaveForeign(previous) }
    }

    private fun cint(value: Any?): Int {
        val number = value as? Long ?: fault("Original process call requires a Long scalar carrier")
        if (number != number.toInt().toLong()) fault("Original process call requires a signed CInt")
        return number.toInt()
    }
    private fun pointer(value: Any?): ManagedAddress = value as? ManagedAddress
        ?: fault("Original process call requires an address carrier")
    private fun string(address: ManagedAddress): ByteArray = address.withNativeBorrow {
        val length = address.cStringLength()
        if (length >= Int.MAX_VALUE) fault("Process string exceeds managed byte capacity")
        ByteArray(length.toInt()) { address.readWord8(it.toLong()).toByte() }
    }
    private fun vector(address: ManagedAddress): List<ByteArray> = address.withNativeBorrow {
        val values = mutableListOf<ByteArray>()
        var index = 0L
        while (true) {
            val entry = address.readAddressElementIndex(index++)
            if (entry === ManagedAddress.nullAddress()) break
            values.add(string(entry))
        }
        values
    }
    private fun pointerCell(address: ManagedAddress) {
        address.requireRange(0, 8, true)
        if (address.nativeAllocation() == null &&
            (address.cbitsOwner()?.addressWidth != 8 || address.cbitsOffset() % 8 != 0L))
            fault("Process failure pointer requires aligned LP64 pointer storage")
    }
    private fun <T> outputs(regions: List<Pair<ManagedAddress, Long>>, action: () -> T): T =
        ManagedAddress.withNativeBorrows(regions.map { it.first }) {
            for (i in regions.indices) for (j in 0 until i)
                if (regions[i].first.overlaps(0, regions[i].second, regions[j].first, 0, regions[j].second))
                    fault("Process output cells must be disjoint")
            // Managed pointer/byte allocations cannot resize during publication.
            // Native allocations above use their existing lifetime borrows.
            val owners = regions.mapNotNull { it.first.cbitsOwner() }.distinct()
                .sortedWith { a, b -> Integer.compareUnsigned(System.identityHashCode(a), System.identityHashCode(b)) }
            fun locked(index: Int): T = if (index == owners.size) action()
                else synchronized(owners[index]) { locked(index + 1) }
            val collision = owners.zipWithNext().any { (a, b) -> System.identityHashCode(a) == System.identityHashCode(b) }
            if (collision) synchronized(OUTPUT_LOCK_ORDER) { locked(0) } else locked(0)
        }

    @TruffleBoundary fun execute(operation: ProcessOp, arguments: Array<Any?>, node: Node? = null,
        beforeBlock: (() -> Unit)? = null): Long {
        if (Language.currentState(node) !== context) fault("Process ABI adapter belongs to another context")
        if (arguments.size != operation.arguments.size) fault("Original process call arity mismatch")
        requireVoidCarrier(arguments.last())
        if (operation == ProcessOp.CREATE) return create(arguments)
        val pid = cint(arguments[0])
        if (operation == ProcessOp.TERMINATE) {
            val result = context.files.processOperation(operation, pid, node, beforeBlock)
            context.stdio.nativeError(result.errno.toLong())
            return result.status.toLong()
        }
        val destination = pointer(arguments[1])
        return outputs(listOf(destination to 4L)) {
            destination.requireByteRegion(4, true)
            val result = context.files.processOperation(operation, pid, node, beforeBlock)
            result.exitCode?.let { destination.writeNativeScalar(0, 4, it.toLong()) }
            context.stdio.nativeError(result.errno.toLong())
            result.status.toLong()
        }
    }

    private fun create(values: Array<Any?>): Long {
        val arguments = vector(pointer(values[0]))
        val cwd = pointer(values[1]).takeUnless { it === ManagedAddress.nullAddress() }?.let(::string)
        val parentEnvironment = context.environment.snapshotForProcess()
        val environment = pointer(values[2]).let {
            if (it === ManagedAddress.nullAddress()) parentEnvironment.entries else vector(it)
        }
        val streams = IntArray(3) { cint(values[it + 3]) }
        val destinations = List(3) { pointer(values[it + 6]) }
        fun credential(index: Int): Long? = pointer(values[index]).takeUnless { it === ManagedAddress.nullAddress() }?.let {
            it.withNativeBorrow { it.requireByteRegion(4); Integer.toUnsignedLong(ManagedAddressRead.WORD32.readInt(it, 0)) }
        }
        val group = credential(9)
        val user = credential(10)
        val flags = cint(values[11])
        val failure = pointer(values[12])
        val regions = listOf(failure to 8L) + destinations.indices.filter { streams[it] == -1 }.map { destinations[it] to 4L }
        return outputs(regions) {
            pointerCell(failure)
            for (index in streams.indices) if (streams[index] == -1) destinations[index].requireByteRegion(4, true)
            failure.writeAddressElementIndex(0, ManagedAddress.nullAddress())
            fun rejected(error: Int, stage: ProcessFailureStage): Long {
                failure.writeAddressElementIndex(0, failures.getValue(stage))
                context.stdio.nativeError(error.toLong())
                return -1
            }
            try {
                context.files.launchProcess(arguments, environment, cwd, streams, flags, group, user, parentEnvironment.searchPath) { _, returned ->
                    for (index in streams.indices) if (streams[index] == -1)
                        destinations[index].writeNativeScalar(0, 4, returned[index].toLong())
                }.toLong()
            } catch (error: ProcessSpawnException) {
                rejected(error.errno, error.stage)
            } catch (error: NativeFileException) {
                rejected(error.errno, ProcessFailureStage.ARGUMENTS)
            } catch (_: ClosedChannelException) {
                rejected(9, ProcessFailureStage.ARGUMENTS)
            } catch (_: UnsupportedOperationException) {
                rejected(95, ProcessFailureStage.ARGUMENTS)
            }
        }
    }
}

// Multi-output managed allocations share a deterministic lock acquisition cut.
private val OUTPUT_LOCK_ORDER = Any()
