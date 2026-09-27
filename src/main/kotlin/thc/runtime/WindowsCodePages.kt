// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
import com.oracle.truffle.api.nodes.Node
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemoryLayout
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout.*
import thc.Json
import thc.Language

/** Original ghc-internal Windows encoding/error imports. No filesystem authority
 * is granted here. Windows owns code-page tables, conversion flags and localized
 * messages; the context owns captured last-error and every returned allocation. */
internal class WindowsCodePages(private val context: Language.State) {
    internal val lastError = CarrierLocal(0L)

    private fun current() {
        if (Language.currentState() !== context) fault("Windows encoding service belongs to another context")
        if (!context.env.isNativeAccessAllowed) throw SecurityException("Windows encoding requires native access")
        Abi.requireLayout()
    }
    private inline fun <T> foreign(action: () -> T): T {
        val previous = context.threads.enterForeign()
        try { return action() } finally { context.threads.leaveForeign(previous) }
    }
    @TruffleBoundary fun error(): Long { current(); return lastError.get() }
    @TruffleBoundary fun codePage(console: Boolean): Long {
        current()
        if (!console) return foreign { (Api.ansi.invokeExact() as Int).toLong() and 0xffff_ffffL }
        return Arena.ofConfined().use { arena ->
            val error = arena.allocate(Api.capture)
            val result = foreign { Api.console.invokeExact(error) as Int }
            lastError.set(Api.error(error))
            result.toLong() and 0xffff_ffffL
        }
    }
    @TruffleBoundary fun leadByte(codePage: Long, value: Long): Long {
        current()
        return Arena.ofConfined().use { arena ->
            val error = arena.allocate(Api.capture)
            val result = foreign { Api.lead.invokeExact(error, codePage.toInt(), value.toByte()) as Int }
            lastError.set(Api.error(error))
            result.toLong()
        }
    }

    private data class Region(val address: ManagedAddress, val bytes: Long, val writable: Boolean = false)
    private class Image(val base: ManagedAddress, var first: Long, var end: Long) {
        lateinit var segment: MemorySegment
    }

    /** A synchronous snapshot per allocation preserves all pointer aliases,
     * including identical input/output pointers rejected by Win32. Validate and
     * lock every region before the OS call; publish even partial failure writes.
     * Native owners retain their existing ordered lifetime borrows. */
    private fun <T> buffers(addresses: List<ManagedAddress>, describe: () -> List<Region>,
                            action: (Arena, List<MemorySegment>) -> T): T =
        ManagedAddress.withNativeBorrows(addresses) {
            val owners = addresses.mapNotNull { it.cbitsOwner() }.distinct()
                .sortedWith { a, b -> Integer.compareUnsigned(System.identityHashCode(a), System.identityHashCode(b)) }
            fun execute(): T {
                val regions = describe()
                val images = mutableListOf<Image>()
                val selected = regions.map { region ->
                    if (region.address === ManagedAddress.nullAddress()) null else {
                        region.address.requireByteRegion(region.bytes, region.writable)
                        val offset = region.address.cbitsOffset()
                        val base = region.address.plus(-offset)
                        val image = images.firstOrNull { it.base.sameLocation(base) }
                            ?: Image(base, offset, offset + region.bytes).also(images::add)
                        image.first = minOf(image.first, offset)
                        image.end = maxOf(image.end, offset + region.bytes)
                        image
                    }
                }
                return Arena.ofConfined().use { arena ->
                    images.forEach { it.segment = arena.allocate(maxOf(1, it.end - it.first), 8) }
                    val pointers = regions.mapIndexed { index, region ->
                        selected[index]?.let { image ->
                            val pointer = image.segment.asSlice(region.address.cbitsOffset() - image.first)
                            bytes(region.address, region.bytes) { pointer.asSlice(0, region.bytes).copyFrom(it) }
                            pointer
                        } ?: MemorySegment.NULL
                    }
                    val result = action(arena, pointers)
                    regions.forEachIndexed { index, region ->
                        if (region.writable && selected[index] != null)
                            bytes(region.address, region.bytes) { it.copyFrom(pointers[index].asSlice(0, region.bytes)) }
                    }
                    result
                }
            }
            fun locked(index: Int): T = if (index == owners.size) execute()
                else synchronized(owners[index]) { locked(index + 1) }
            if (owners.zipWithNext().any { (a, b) -> System.identityHashCode(a) == System.identityHashCode(b) })
                synchronized(LOCK_ORDER) { locked(0) } else locked(0)
        }
    private inline fun bytes(address: ManagedAddress, count: Long, crossinline action: (MemorySegment) -> Unit) {
        if (address.hasNativeStorage()) address.withNativeSegment { action(it.asSlice(0, count)) }
        else action(address.cbitsSegment().asSlice(address.cbitsOffset(), count))
    }
    private fun inputBytes(address: ManagedAddress, count: Int, width: Int): Long {
        if (address === ManagedAddress.nullAddress()) fault("Windows conversion requires an input buffer")
        if (count < -1) fault("Windows conversion input length must be nonnegative or -1")
        if (count != -1) return maxOf(0, count.toLong()) * width
        // A -1 count includes the terminator. Never let native code scan past the
        // actual guest allocation when it is missing.
        val available = address.availableBytes()
        var position = 0L
        while (position <= available - width) {
            var zero = true
            repeat(width) { if (address.readWord8(position + it) != 0L) zero = false }
            position += width
            if (zero) return position
        }
        fault("Unterminated Windows conversion input")
    }
    @TruffleBoundary fun info(codePage: Long, output: ManagedAddress): Long {
        current()
        if (output === ManagedAddress.nullAddress()) fault("GetCPInfo requires a writable CPINFO buffer")
        return buffers(listOf(output), { listOf(Region(output, Abi.fieldBytes, true)) }) { arena, pointers ->
            // GHC's original CPINFO Storable allocates 18 field bytes; the native
            // C object is 20 bytes including tail padding. Never overrun GHC.
            val data = arena.allocate(Abi.infoBytes, Abi.infoAlignment).also { it.asSlice(0, Abi.fieldBytes).copyFrom(pointers[0]) }
            val error = arena.allocate(Api.capture)
            val result = foreign { Api.info.invokeExact(error, codePage.toInt(), data) as Int }
            lastError.set(Api.error(error))
            if (result != 0) pointers[0].asSlice(0, Abi.fieldBytes).copyFrom(data.asSlice(0, Abi.fieldBytes))
            result.toLong()
        }
    }
    @TruffleBoundary fun multiByte(codePage: Long, flags: Long, input: ManagedAddress, count: Long,
                                  output: ManagedAddress, capacity: Long): Long {
        current()
        return buffers(listOf(input, output), {
            if (capacity.toInt() < 0) fault("Windows conversion output capacity must be nonnegative")
            val sourceBytes = inputBytes(input, count.toInt(), 1)
            listOf(Region(input, sourceBytes), Region(output, maxOf(0, capacity.toInt().toLong()) * 2, true))
        }) { arena, pointers ->
            val error = arena.allocate(Api.capture)
            val result = foreign { Api.multi.invokeExact(error, codePage.toInt(), flags.toInt(), pointers[0],
                count.toInt(), pointers[1], capacity.toInt()) as Int }
            lastError.set(Api.error(error))
            result.toLong()
        }
    }
    @TruffleBoundary fun wideChar(codePage: Long, flags: Long, input: ManagedAddress, count: Long,
                                 output: ManagedAddress, capacity: Long, defaultChar: ManagedAddress,
                                 usedDefault: ManagedAddress): Long {
        current()
        return buffers(listOf(input, output, defaultChar, usedDefault), {
            if (capacity.toInt() < 0) fault("Windows conversion output capacity must be nonnegative")
            val sourceBytes = inputBytes(input, count.toInt(), 2)
            val defaultBytes = if (defaultChar === ManagedAddress.nullAddress()) 0L else {
                defaultChar.requireByteRegion(1)
                if (leadByte(codePage, defaultChar.readWord8(0)) != 0L) 2L else 1L
            }
            listOf(Region(input, sourceBytes), Region(output, maxOf(0, capacity.toInt().toLong()), true),
                Region(defaultChar, defaultBytes), Region(usedDefault, 4, true))
        }) { arena, pointers ->
            val error = arena.allocate(Api.capture)
            val result = foreign { Api.wide.invokeExact(error, codePage.toInt(), flags.toInt(), pointers[0],
                count.toInt(), pointers[1], capacity.toInt(), pointers[2], pointers[3]) as Int }
            lastError.set(Api.error(error))
            result.toLong()
        }
    }
    /** Exact ordered mapping in GHC 9.14.1 cbits/Win32Utils.c. In particular,
     * the first ERROR_INVALID_HANDLE row (EBADF) wins over its duplicate. */
    @TruffleBoundary fun mapErrno(error: Long): Long {
        current()
        val value = error and 0xffff_ffffL
        val name = when (value) {
            2L, 3L, 15L, 18L, 53L, 67L, 161L, 206L -> "ENOENT"
            4L -> "EMFILE"
            5L, 16L, 33L, 65L, 82L, 83L, 108L, 132L, 158L, 167L -> "EACCES"
            6L, 114L, 130L -> "EBADF"
            7L, 8L, 9L, 1816L -> "ENOMEM"
            10L -> "E2BIG"
            11L -> "ENOEXEC"
            17L -> "EXDEV"
            80L, 183L -> "EEXIST"
            89L, 164L, 215L -> "EAGAIN"
            109L, 232L -> "EPIPE"
            112L -> "ENOSPC"
            128L, 129L -> "ECHILD"
            145L -> "ENOTEMPTY"
            in 19L..36L -> "EACCES"
            in 188L..202L -> "ENOEXEC"
            else -> "EINVAL"
        }
        return Abi.errno.getValue(name)
    }
    @TruffleBoundary fun setErrno() { current(); context.stdio.captureForeignErrno(mapErrno(lastError.get())) }
    @TruffleBoundary fun message(errorCode: Long): ManagedAddress {
        current()
        return Arena.ofConfined().use { arena ->
            val resultPointer = arena.allocate(ADDRESS)
            val error = arena.allocate(Api.capture)
            val count = foreign { Api.message.invokeExact(error, Abi.messageFlags.toInt(), MemorySegment.NULL,
                errorCode.toInt(), Abi.language.toInt(), resultPointer, 0, MemorySegment.NULL) as Int }
            lastError.set(Api.error(error))
            if (count == 0) ManagedAddress.nullAddress() else {
                val pointer = resultPointer.get(ADDRESS, 0)
                // Adoption consumes the OS allocation, including cleanup if
                // creating or publishing its managed owner fails.
                context.nativeAllocations.adoptWindowsLocal(pointer, ((count.toLong() and 0xffff_ffffL) + 1) * 2)
            }
        }
    }
    @TruffleBoundary fun localFree(address: ManagedAddress): ManagedAddress {
        current()
        context.nativeAllocations.free(address, ManagedNativeAllocations.Allocator.WINDOWS_LOCAL)
        return ManagedAddress.nullAddress()
    }

    internal object Abi {
        private val fields: Map<*, *> by lazy {
            check(WindowsDirectoryStreams.supportedHost()) { "Windows encoding ABI requires Windows x86_64" }
            val document = WindowsCodePages::class.java.getResourceAsStream("/thc/native/windows-directory-abi.json")
                ?.bufferedReader()?.use { Json.parse(it.readText()) as Map<*, *> } ?: fault("Missing native Windows ABI")
            val fields = document["layout"] as? Map<*, *> ?: fault("Malformed native Windows ABI")
            check(document["schema"] == 1L && document["architecture"] == "x86_64" &&
                fields["pointerBytes"] == 8L && fields["wcharBytes"] == 2L && fields["boolBytes"] == 4L &&
                fields["dwordBytes"] == 4L && fields["cpInfoFieldBytes"] == 18L &&
                fields["cpInfoMaxOffset"] == 0L && fields["cpInfoDefaultOffset"] == 4L && fields["cpInfoLeadOffset"] == 6L)
            fields
        }
        fun requireLayout() { fields }
        val infoBytes get() = fields["cpInfoBytes"] as Long
        val infoAlignment get() = fields["cpInfoAlignment"] as Long
        val fieldBytes get() = fields["cpInfoFieldBytes"] as Long
        val messageFlags get() = fields["formatMessageFlags"] as Long
        val language get() = fields["defaultLanguage"] as Long
        val errno: Map<String, Long> get() = (fields["errno"] as Map<*, *>).entries.associate { it.key as String to it.value as Long }
    }
    internal object Api {
        private val linker = Linker.nativeLinker()
        private val lookup = SymbolLookup.libraryLookup("kernel32.dll", Arena.global())
        val capture = Linker.Option.captureStateLayout()
        private val offset = capture.byteOffset(MemoryLayout.PathElement.groupElement("GetLastError"))
        private fun call(name: String, result: MemoryLayout, vararg arguments: MemoryLayout) =
            linker.downcallHandle(lookup.find(name).orElseThrow(), FunctionDescriptor.of(result, *arguments),
                Linker.Option.captureCallState("GetLastError"))
        val ansi = linker.downcallHandle(lookup.find("GetACP").orElseThrow(), FunctionDescriptor.of(JAVA_INT))
        val console = call("GetConsoleCP", JAVA_INT)
        val lead = call("IsDBCSLeadByteEx", JAVA_INT, JAVA_INT, JAVA_BYTE)
        val info = call("GetCPInfo", JAVA_INT, JAVA_INT, ADDRESS)
        val multi = call("MultiByteToWideChar", JAVA_INT, JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT)
        val wide = call("WideCharToMultiByte", JAVA_INT, JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, ADDRESS)
        val message = call("FormatMessageW", JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT, ADDRESS)
        val localFree = call("LocalFree", ADDRESS, ADDRESS)
        fun error(storage: MemorySegment) = storage.get(JAVA_INT, offset).toLong() and 0xffff_ffffL
    }
    companion object {
        private val LOCK_ORDER = Any()
        @JvmStatic fun current(node: Node?): WindowsCodePages = Language.currentState(node).windowsCodePages
        internal fun releaseLocal(pointer: MemorySegment) {
            Arena.ofConfined().use { arena ->
                val error = arena.allocate(Api.capture)
                val result = Api.localFree.invokeExact(error, pointer) as MemorySegment
                if (result.address() != 0L) fault("LocalFree failed: " + Api.error(error))
            }
        }
    }
}
