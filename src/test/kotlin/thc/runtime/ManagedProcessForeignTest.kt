// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import thc.Language
import java.nio.file.Files
import java.nio.file.Path

/** ABI/provider transaction tests. Genuine Core execution belongs to the
 * interpreter integration tests, not this direct adapter harness. */
@EnabledOnOs(OS.LINUX)
@EnabledIfSystemProperty(named = "os.arch", matches = "amd64|x86_64")
@Timeout(30)
class ManagedProcessForeignTest {
    @TempDir lateinit var directory: Path
    private val nil get() = ManagedAddress.nullAddress()
    private fun inside(allowed: Boolean = true, body: () -> Unit) {
        NativeFileProvider.createContext(emptySet(), allowProcesses = allowed).use { context ->
            context.enter()
            try { body() } finally { context.leave() }
        }
    }
    private fun bytes(value: ByteArray): ManagedAddress = Language.currentState().nativeAllocations.malloc(value.size.toLong() + 1).also {
        value.forEachIndexed { index, byte -> it.writeWord8(index.toLong(), byte.toLong()) }
        it.writeWord8(value.size.toLong(), 0)
    }
    private fun string(value: String) = bytes(value.toByteArray())
    private fun vector(vararg values: String) = Language.currentState().nativeAllocations.malloc((values.size.toLong() + 1) * 8).also {
        values.forEachIndexed { index, value -> it.writeAddressElementIndex(index.toLong(), string(value)) }
        it.writeAddressElementIndex(values.size.toLong(), nil)
    }
    private fun cell(width: Long = 4) = Language.currentState().nativeAllocations.malloc(width)
    private fun text(address: ManagedAddress) = ByteArray(address.cStringLength().toInt()) { address.readWord8(it.toLong()).toByte() }.toString(Charsets.UTF_8)
    private fun int(address: ManagedAddress) = ManagedAddressRead.INT32.read(address, 0)
    private fun create(command: ManagedAddress, environment: ManagedAddress = nil, cwd: ManagedAddress = nil,
        streams: LongArray = longArrayOf(-2, -2, -2), outputs: List<ManagedAddress> = List(3) { cell() }, failure: ManagedAddress = cell(8)): Long =
        ManagedProcessForeign.current(null).execute(ProcessOp.CREATE, arrayOf(command, cwd, environment,
            streams[0], streams[1], streams[2], outputs[0], outputs[1], outputs[2], nil, nil, 0L, failure, Unit))
    private fun status(operation: ProcessOp, pid: Long, output: ManagedAddress = cell()) =
        ManagedProcessForeign.current(null).execute(operation, if (operation == ProcessOp.TERMINATE) arrayOf(pid, Unit) else arrayOf(pid, output, Unit))

    @Test fun realChildPipeEnvironmentAndCwdReachTheOriginalAbi() = inside {
        val state = Language.currentState()
        state.environment.put(string("PATH=/bin"))
        state.environment.put(bytes(byteArrayOf(82, 65, 87, 61, -1, -2)))
        val snapshot = state.environment.snapshotForProcess()
        assertArrayEquals(byteArrayOf(82, 65, 87, 61, -1, -2), snapshot.entries.first { it.take(4) == listOf<Byte>(82, 65, 87, 61) })
        assertArrayEquals("/bin".toByteArray(), snapshot.searchPath)
        NativeFileProvider.current().changeDirectory(NativeDirectoryOwner.pathBytes(directory))
        Files.writeString(directory.resolve("value"), "cwd-data")
        val outputs = List(3) { cell().also { it.writeNativeScalar(0, 4, 991) } }
        val failure = cell(8)
        val pid = create(vector("sh", "-c", "printf '%s:%s:' \"\$THC_VALUE\" \"$$\"; /bin/cat value"),
            vector("THC_VALUE=child", "PATH=/unavailable-child-path"), streams = longArrayOf(-2, -1, -2), outputs = outputs, failure = failure)
        assertTrue(pid > 0)
        assertSame(nil, failure.readAddressElementIndex(0))
        assertEquals(991, int(outputs[0])); assertEquals(991, int(outputs[2]))
        val fd = int(outputs[1])
        val received = cell(64)
        val content = StringBuilder()
        while (true) {
            val size = state.stdio.read(fd, received, 64)
            assertTrue(size >= 0)
            if (size == 0L) break
            content.append(ByteArray(size.toInt()) { received.readWord8(it.toLong()).toByte() }.toString(Charsets.UTF_8))
        }
        assertEquals("child:$pid:cwd-data", content.toString())
        val code = cell()
        assertEquals(0L, status(ProcessOp.WAIT, pid, code))
        assertEquals(0L, int(code))
        assertEquals(0L, state.files.close(fd))
    }

    @Test fun failureAndBadOutputsHaveNoLaunchedChildOrPublishedDescriptors() = inside {
        val state = Language.currentState()
        val outputs = List(3) { cell().also { it.writeNativeScalar(0, 4, 991) } }
        val failure = cell(8)
        assertEquals(-1L, create(vector("/definitely-missing-thc-command"), streams = longArrayOf(-1, -1, -1), outputs = outputs, failure = failure))
        assertEquals("posix_spawnp", text(failure.readAddressElementIndex(0)))
        assertTrue(outputs.all { int(it) == 991L })
        val marker = directory.resolve("unexpected")
        val command = vector("/bin/sh", "-c", "touch '${marker}'")
        assertThrows(RuntimeFault::class.java) {
            create(command, streams = longArrayOf(-1, -1, -2), outputs = listOf(outputs[0], outputs[0], outputs[2]))
        }
        assertThrows(RuntimeFault::class.java) {
            create(command, streams = longArrayOf(-1, -2, -2), outputs = listOf(nil, outputs[1], outputs[2]))
        }
        assertFalse(Files.exists(marker))
        assertEquals(0L, state.files.errorKind())
    }

    @Test fun pollWaitAndTerminationPreserveOriginalOutputAndErrnoRules() = inside {
        val state = Language.currentState()
        val outputs = List(3) { cell() }
        val pid = create(vector("/bin/sh", "-c", "printf R; read line; exit 23"),
            streams = longArrayOf(-1, -1, -2), outputs = outputs)
        val ready = cell(1)
        assertEquals(1L, state.stdio.read(int(outputs[1]), ready, 1))
        assertEquals('R'.code.toLong(), ready.readWord8(0))
        val code = cell().also { it.writeNativeScalar(0, 4, 991) }
        assertEquals(0L, status(ProcessOp.POLL, pid, code)); assertEquals(0L, int(code))
        assertEquals(1L, status(ProcessOp.TERMINATE, pid))
        assertEquals(0L, status(ProcessOp.WAIT, pid, code)); assertEquals(-15L, int(code))
        assertEquals(1L, status(ProcessOp.POLL, pid, code)); assertEquals(0L, int(code))
        assertEquals(10L, state.stdio.errno())
        code.writeNativeScalar(0, 4, 991)
        assertEquals(-1L, status(ProcessOp.WAIT, pid, code)); assertEquals(991L, int(code))
        assertEquals(10L, state.stdio.errno())
        assertEquals(0L, status(ProcessOp.TERMINATE, pid)); assertEquals(3L, state.stdio.errno())
        assertEquals(0L, state.files.close(int(outputs[0])))
        assertEquals(0L, state.files.close(int(outputs[1])))
    }

    @Test fun failedDescriptorPublicationAbortsOnlyTheNewOwnedLaunch() = inside {
        val files = Language.currentState().files
        var pid = -1
        var returned = IntArray(0)
        class PublicationFailure : RuntimeException()
        assertThrows(PublicationFailure::class.java) {
            files.launchProcess(listOf("/bin/sh", "-c", "read line").map { it.toByteArray() }, emptyList(),
                null, intArrayOf(-1, -1, -1), 0, null, null, null) { child, descriptors ->
                pid = child; returned = descriptors.copyOf(); throw PublicationFailure()
            }
        }
        assertTrue(pid > 0)
        assertFalse(ProcessHandle.of(pid.toLong()).map { it.isAlive }.orElse(false))
        for (fd in returned) assertEquals(-1L, files.close(fd.toLong()), "Unpublished guest descriptor cannot remain installed")
        val next = create(vector("/bin/true"))
        assertTrue(next > 0)
        assertEquals(0L, status(ProcessOp.WAIT, next))
    }

    @Test fun explicitProcessGrantIsRequiredEvenWithNativeFiles() = inside(false) {
        assertThrows(SecurityException::class.java) { create(vector("/bin/true")) }
    }
}
