// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime

import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Machine-code process transport, including cleanup after LLVM disposal. */
internal object NativeProcessApi {
    private val library = run {
        val file = Files.createTempFile("thc-process-", ".so")
        try {
            NativeProcessApi::class.java.getResourceAsStream("/thc/native/native-process-api.so").use { input ->
                checkNotNull(input) { "Missing native process bridge" }
                Files.copy(input, file, StandardCopyOption.REPLACE_EXISTING)
            }
            SymbolLookup.libraryLookup(file, Arena.global())
        } finally { Files.deleteIfExists(file) }
    }
    private val linker = Linker.nativeLinker()
    private val int = ValueLayout.JAVA_INT
    private val ptr = ValueLayout.ADDRESS
    private fun function(name: String, vararg parameters: java.lang.foreign.MemoryLayout) =
        linker.downcallHandle(library.find("thc_process_$name").orElseThrow(),
            FunctionDescriptor.of(int, *parameters))
    private val spawn = function("spawn", ptr, ptr, int, ptr, ptr, int, ptr, ptr)
    private val poll = function("poll", int, ptr)
    private val terminate = function("terminate", int, ptr)
    private val dispose = function("dispose", int)

    private fun Arena.string(bytes: ByteArray): MemorySegment {
        require(bytes.none { it == 0.toByte() }) { "Embedded NUL in process string" }
        return allocate(bytes.size.toLong() + 1).also { it.asSlice(0, bytes.size.toLong()).copyFrom(MemorySegment.ofArray(bytes)) }
    }
    private fun Arena.vector(strings: List<ByteArray>): MemorySegment =
        allocate((strings.size.toLong() + 1) * 8, 8).also { vector ->
            for ((index, bytes) in strings.withIndex()) vector.setAtIndex(ptr, index.toLong(), string(bytes))
        }

    /** The caller receives every returned native owner in preallocated slots. */
    fun spawn(arguments: List<ByteArray>, environment: List<ByteArray>, directory: Int,
              cwd: ByteArray?, descriptors: IntArray, flags: Int, searchPath: ByteArray?, result: MemorySegment): Int =
        Arena.ofConfined().use { arena ->
            require(descriptors.size == 3)
            val argv = arena.vector(arguments)
            val env = arena.vector(environment)
            val path = cwd?.let { arena.string(it) } ?: MemorySegment.NULL
            val search = searchPath?.let { arena.string(it) } ?: MemorySegment.NULL
            val streams = arena.allocate(12, 4)
            descriptors.forEachIndexed { index, fd -> streams.setAtIndex(int, index.toLong(), fd) }
            spawn.invokeExact(argv, env, directory, path, streams, flags, search, result) as Int
        }

    /** status/exit are initialized even for failure, as in getProcessExitCode. */
    fun poll(pidfd: Int): ProcessResult = Arena.ofConfined().use { arena ->
        val result = arena.allocate(8, 4)
        val error = poll.invokeExact(pidfd, result) as Int
        ProcessResult(if (error == 0) result.get(int, 0) else -1, result.get(int, 4), error)
    }

    fun terminate(pidfd: Int): ProcessResult = Arena.ofConfined().use { arena ->
        val error = arena.allocate(int)
        val status = terminate.invokeExact(pidfd, error) as Int
        ProcessResult(status, null, error.get(int, 0))
    }

    fun dispose(pidfd: Int) {
        val error = dispose.invokeExact(pidfd) as Int
        if (error != 0) throw NativeFileException("process disposal", error)
    }
}

/** errno == 0 means leave the guest's sticky errno unchanged. A null exitCode
 * means do not write the caller's output cell (not a fabricated exit status). */
internal data class ProcessResult(val status: Int, val exitCode: Int?, val errno: Int)
