// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.lang.invoke.MethodHandle

/** Support is not a promise that the OS will accept a particular request. */
internal enum class CpuAffinityMode { UNAVAILABLE, ADVISORY, PINNED }

internal interface NativeCpuAffinity {
    val count: Int
    val mode: CpuAffinityMode
    fun bindCurrent(index: Int): AutoCloseable?
    fun resetCurrent(): AutoCloseable?
}

/** A context snapshots capacity before any guest pinning, not once per fork.
 * Logical capability indices are dense, even when OS CPU IDs are sparse. */
internal class CpuAffinity internal constructor(
    private val native: NativeCpuAffinity?, availableProcessors: Int
) {
    val count: Int = minOf(native?.count ?: Int.MAX_VALUE, availableProcessors.coerceAtLeast(1))
    val mode: CpuAffinityMode get() = native?.mode ?: CpuAffinityMode.UNAVAILABLE

    @TruffleBoundary fun bindCurrent(capability: Long): AutoCloseable? =
        native?.bindCurrent(Math.floorMod(capability, count.toLong()).toInt())

    @TruffleBoundary fun resetCurrent(): AutoCloseable? = native?.resetCurrent()

    companion object {
        fun discover(nativeAccess: Boolean): CpuAffinity {
            val available = Runtime.getRuntime().availableProcessors()
            val native = if (!nativeAccess || Thread.currentThread().isVirtual) null else when {
                System.getProperty("os.name").startsWith("Linux") -> LinuxCpuAffinity.discover()
                System.getProperty("os.name").startsWith("Windows") -> WindowsCpuAffinity.discover()
                else -> null
            }
            if (native != null) optionalAffinity { CompilerCpuAffinity.install(native::resetCurrent) }
            return CpuAffinity(native, available)
        }
    }
}

/** Only the current platform thread is changed: pid=0 is NOT a Java thread ID.
 * The kernel intersects these masks with online CPUs and cpuset restrictions. */
internal class LinuxCpuAffinity private constructor(
    private val get: MethodHandle, private val set: MethodHandle,
    private val original: ByteArray, private val cpus: IntArray
) : NativeCpuAffinity {
    override val count: Int get() = cpus.size
    override val mode = CpuAffinityMode.PINNED

    internal fun currentMask(): ByteArray? = optionalAffinity {
        Arena.ofConfined().use { arena ->
            val mask = arena.allocate(original.size.toLong(), 8)
            if (get.invokeExact(0, mask.byteSize(), mask) as Int == 0)
                mask.toArray(ValueLayout.JAVA_BYTE) else null
        }
    }

    private fun install(mask: ByteArray): Boolean = Arena.ofConfined().use { arena ->
        val segment = arena.allocate(mask.size.toLong(), 8)
        segment.copyFrom(MemorySegment.ofArray(mask))
        set.invokeExact(0, segment.byteSize(), segment) as Int == 0
    }

    private fun change(mask: ByteArray): AutoCloseable? = optionalAffinity {
        val owner = Thread.currentThread()
        if (owner.isVirtual) return@optionalAffinity null
        val previous = currentMask() ?: return@optionalAffinity null
        if (!install(mask)) return@optionalAffinity null
        object : AutoCloseable {
            private var closed = false
            override fun close() {
                // Never accidentally change another carrier on a misplaced close.
                if (closed || Thread.currentThread() !== owner) return
                closed = true
                optionalAffinity { install(previous) }
            }
        }
    }

    override fun bindCurrent(index: Int): AutoCloseable? {
        val cpu = cpus.getOrNull(index) ?: return null
        val mask = ByteArray(original.size)
        mask[cpu / 8] = (1 shl (cpu % 8)).toByte()
        return change(mask)
    }

    override fun resetCurrent(): AutoCloseable? = change(original)

    companion object {
        fun discover(): LinuxCpuAffinity? = optionalAffinity {
            if (!System.getProperty("os.name").startsWith("Linux") || Thread.currentThread().isVirtual)
                return@optionalAffinity null
            val linker = Linker.nativeLinker()
            // THC's supported Linux targets are 64-bit: pid_t=int, size_t=long.
            if (ValueLayout.ADDRESS.byteSize() != 8L) return@optionalAffinity null
            val signature = FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
                ValueLayout.JAVA_LONG, ValueLayout.ADDRESS)
            val get = linker.downcallHandle(linker.defaultLookup().find("sched_getaffinity").orElseThrow(), signature)
            val set = linker.downcallHandle(linker.defaultLookup().find("sched_setaffinity").orElseThrow(), signature)
            Arena.ofConfined().use { arena ->
                // Grow past cpu_set_t's usual 1024 bits on large kernels. A
                // denied/unavailable query remains a bounded best-effort failure.
                var size = 128L
                while (size <= 128 * 1024L) {
                    val mask = arena.allocate(size, 8)
                    if (get.invokeExact(0, size, mask) as Int == 0) {
                        val bytes = mask.toArray(ValueLayout.JAVA_BYTE)
                        val cpus = (0 until bytes.size * 8).filter {
                            bytes[it / 8].toInt() and (1 shl (it % 8)) != 0
                        }.toIntArray()
                        return@optionalAffinity if (cpus.isEmpty()) null else LinuxCpuAffinity(get, set, bytes, cpus)
                    }
                    size *= 2
                }
                null
            }
        }
    }
}

/** Native access denial and missing platform APIs cannot make forkOn# fail.
 * Do not swallow VM failures or Truffle cancellation/control-flow errors. */
internal inline fun <T> optionalAffinity(action: () -> T?): T? = try { action() }
catch (_: Exception) { null }
catch (_: LinkageError) { null }
