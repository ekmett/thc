// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime

import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemoryLayout
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout.*
import java.lang.invoke.MethodHandle

/** Windows CPU Sets are advisory selection, not hard processor affinity.
 * We never change process defaults or a thread's hard group affinity. In
 * particular, a Get/SetThreadGroupAffinity roundtrip cannot restore the default
 * all-group affinity on Windows 11. Until full hard eligibility can be queried,
 * multi-group processes decline support rather than silently truncating to 64
 * CPUs. A process restricted to one group (including a nonzero group) is valid.
 * See https://learn.microsoft.com/windows/win32/procthread/cpu-sets . */
internal class WindowsCpuAffinity private constructor(
    private val api: Api,
    private val cpus: List<Cpu>,
    private val originalSelection: IntArray,
) : NativeCpuAffinity {
    override val count: Int get() = cpus.size
    override val mode: CpuAffinityMode get() = CpuAffinityMode.ADVISORY

    override fun bindCurrent(index: Int): AutoCloseable? = available {
        if (Thread.currentThread().isVirtual) return@available null
        val cpu = cpus.getOrNull(index) ?: return@available null
        // Restrictions can change after the context snapshot. Do not override a
        // newly restricted process default or select outside the current mask.
        val hard = api.hardEligibility() ?: return@available null
        val defaults = api.ids(api.processSets, api.process()) ?: return@available null
        val current = api.cpuSets() ?: return@available null
        if (!eligible(cpu, hard.first, hard.second, defaults) ||
            current.none { it == cpu }) return@available null
        changeTo(intArrayOf(cpu.id))
    }

    override fun resetCurrent(): AutoCloseable? = available {
        if (Thread.currentThread().isVirtual) null else changeTo(originalSelection)
    }

    private fun changeTo(selection: IntArray): AutoCloseable? {
        val owner = Thread.currentThread()
        val previous = api.ids(api.threadSets, api.thread()) ?: return null
        if (!api.select(selection)) return null
        return object : AutoCloseable {
            private var closed = false
            override fun close() {
                // Pseudo-handles identify the CALLING native thread. Never use
                // one from a different Java thread or a migrating virtual thread.
                if (closed || Thread.currentThread() !== owner) return
                closed = true
                available { api.select(previous) }
            }
        }
    }

    internal data class Cpu(val id: Int, val group: Int, val index: Int)

    companion object {
        private val platformApi: Api? by lazy { available { Api() } }

        fun discover(): WindowsCpuAffinity? = available {
            if (!System.getProperty("os.name", "").startsWith("Windows") ||
                ADDRESS.byteSize() != 8L || Thread.currentThread().isVirtual) return@available null
            val api = platformApi ?: return@available null
            val hard = api.hardEligibility() ?: return@available null
            val original = api.ids(api.threadSets, api.thread()) ?: return@available null
            val defaults = api.ids(api.processSets, api.process()) ?: return@available null
            val selected = if (original.isNotEmpty()) original else defaults
            val cpus = api.cpuSets()?.filter { eligible(it, hard.first, hard.second, selected) }
                ?.sortedWith(compareBy(Cpu::group, Cpu::index)) ?: return@available null
            if (cpus.isEmpty()) null else WindowsCpuAffinity(api, cpus, original)
        }

        internal fun eligible(cpu: Cpu, group: Int, mask: Long, selected: IntArray): Boolean =
            cpu.group == group && cpu.index in 0..63 && mask and (1L shl cpu.index) != 0L &&
                (selected.isEmpty() || cpu.id in selected)

        /** The SDK explicitly requires advancing by Size, not a fixed stride. */
        internal fun decodeCpuSets(bytes: MemorySegment): List<Cpu>? {
            val result = ArrayList<Cpu>()
            var offset = 0L
            while (offset < bytes.byteSize()) {
                if (bytes.byteSize() - offset < 8) return null
                val size = bytes.get(JAVA_INT_UNALIGNED, offset).toLong() and 0xffffffffL
                if (size < 8 || size > bytes.byteSize() - offset) return null
                if (bytes.get(JAVA_INT_UNALIGNED, offset + 4) == 0) {
                    if (size < 32) return null
                    val flags = bytes.get(JAVA_BYTE, offset + 19).toInt() and 255
                    // Allocated CPU sets reserved for another process are not
                    // eligible. Parked CPUs remain valid; the OS may unpark them.
                    if (flags and 2 == 0 || flags and 4 != 0) {
                        val index = bytes.get(JAVA_BYTE, offset + 14).toInt() and 255
                        if (index > 63) return null
                        result.add(Cpu(bytes.get(JAVA_INT_UNALIGNED, offset + 8),
                            bytes.get(JAVA_SHORT_UNALIGNED, offset + 12).toInt() and 65535, index))
                    }
                }
                offset += size
            }
            if (result.map { it.id }.distinct().size != result.size ||
                result.map { it.group to it.index }.distinct().size != result.size) return null
            return result
        }

        private inline fun <T> available(action: () -> T): T? = try { action() }
            catch (_: Exception) { null }
            catch (_: LinkageError) { null }
    }

    private class Api {
        private val linker = Linker.nativeLinker()
        private val lookup = SymbolLookup.libraryLookup("kernel32.dll", Arena.global())
        private fun function(name: String, result: MemoryLayout, vararg args: MemoryLayout): MethodHandle =
            linker.downcallHandle(lookup.find(name).orElseThrow(), FunctionDescriptor.of(result, *args))
        private val currentThread = function("GetCurrentThread", ADDRESS)
        private val currentProcess = function("GetCurrentProcess", ADDRESS)
        private val groups = function("GetProcessGroupAffinity", JAVA_INT, ADDRESS, ADDRESS, ADDRESS)
        private val processMask = function("GetProcessAffinityMask", JAVA_INT, ADDRESS, ADDRESS, ADDRESS)
        private val threadMask = function("GetThreadGroupAffinity", JAVA_INT, ADDRESS, ADDRESS)
        private val systemSets = function("GetSystemCpuSetInformation", JAVA_INT,
            ADDRESS, JAVA_INT, ADDRESS, ADDRESS, JAVA_INT)
        val threadSets = function("GetThreadSelectedCpuSets", JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, ADDRESS)
        val processSets = function("GetProcessDefaultCpuSets", JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, ADDRESS)
        private val setThreadSets = function("SetThreadSelectedCpuSets", JAVA_INT, ADDRESS, ADDRESS, JAVA_INT)
        fun thread(): MemorySegment = currentThread.invokeExact() as MemorySegment
        fun process(): MemorySegment = currentProcess.invokeExact() as MemorySegment

        fun hardEligibility(): Pair<Int, Long>? = Arena.ofConfined().use { arena ->
            val count = arena.allocate(JAVA_SHORT).also { it.set(JAVA_SHORT, 0, 1) }
            val group = arena.allocate(JAVA_SHORT)
            if ((groups.invokeExact(process(), count, group) as Int) == 0 ||
                count.get(JAVA_SHORT, 0).toInt() != 1) return null
            val processBits = arena.allocate(JAVA_LONG)
            val system = arena.allocate(JAVA_LONG)
            val threadBits = arena.allocate(16, 8)
            if ((processMask.invokeExact(process(), processBits, system) as Int) == 0 ||
                (threadMask.invokeExact(thread(), threadBits) as Int) == 0) return null
            val selectedGroup = group.get(JAVA_SHORT, 0).toInt() and 65535
            if (threadBits.get(JAVA_SHORT, 8).toInt() and 65535 != selectedGroup) return null
            val mask = processBits.get(JAVA_LONG, 0) and system.get(JAVA_LONG, 0) and threadBits.get(JAVA_LONG, 0)
            if (mask == 0L) null else selectedGroup to mask
        }

        fun ids(query: MethodHandle, handle: MemorySegment): IntArray? = Arena.ofConfined().use { arena ->
            val count = arena.allocate(JAVA_INT)
            val first = query.invokeExact(handle, MemorySegment.NULL, 0, count) as Int
            val required = count.get(JAVA_INT, 0)
            if (required == 0) return if (first != 0) IntArray(0) else null
            if (required !in 1..65536) return null
            val ids = arena.allocate(required.toLong() * 4, 4)
            if ((query.invokeExact(handle, ids, required, count) as Int) == 0) return null
            val actual = count.get(JAVA_INT, 0)
            if (actual !in 0..required) null else IntArray(actual) { ids.getAtIndex(JAVA_INT, it.toLong()) }
        }

        fun cpuSets(): List<Cpu>? = Arena.ofConfined().use { arena ->
            val size = arena.allocate(JAVA_INT)
            systemSets.invokeExact(MemorySegment.NULL, 0, size, process(), 0) as Int
            val required = size.get(JAVA_INT, 0)
            if (required !in 1..(16 * 1024 * 1024)) return null
            val bytes = arena.allocate(required.toLong(), 8)
            if ((systemSets.invokeExact(bytes, required, size, process(), 0) as Int) == 0) return null
            val actual = size.get(JAVA_INT, 0)
            if (actual !in 1..required) null else decodeCpuSets(bytes.asSlice(0, actual.toLong()))
        }

        fun select(ids: IntArray): Boolean = Arena.ofConfined().use { arena ->
            val memory = if (ids.isEmpty()) MemorySegment.NULL else arena.allocateFrom(JAVA_INT, *ids)
            (setThreadSets.invokeExact(thread(), memory, ids.size) as Int) != 0
        }
    }
}
