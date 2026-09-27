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
import java.util.IdentityHashMap
import org.graalvm.polyglot.Context
import org.graalvm.polyglot.io.IOAccess
import thc.ContextProfile
import thc.FfiMode
import thc.Json
import thc.Language
import thc.withContextProfile
import thc.withFfiMode

/** The original Win32 package's search API. Search HANDLEs are opaque managed
 * identities, never guest-provided native pointers. The registry serializes
 * first/next/close and disposal; caller-owned find-data bytes survive FindClose.
 * Native filesystem authority is installed only by the fixed factory below. */
internal class WindowsDirectoryStreams private constructor(private val context: Language.State) : java.io.Closeable {
    private val handles = IdentityHashMap<ManagedAllocation, MemorySegment>()
    private val lastError get() = context.windowsCodePages.lastError
    private var disposed = false

    private fun current() {
        if (Language.currentState() !== context) fault("Windows directory handle belongs to another context")
        if (disposed) fault("Windows directory registry is closed")
    }
    private fun key(address: ManagedAddress): ManagedAllocation {
        if (address.cbitsOffset() != 0L) fault("Windows search handle requires its exact opaque base")
        return address.cbitsOwner() ?: fault("Windows search handle has no managed identity")
    }
    private fun handle(address: ManagedAddress): MemorySegment =
        handles[key(address)] ?: fault("Unknown, closed or cross-context Windows search handle")

    private inline fun <T> foreign(action: () -> T): T {
        val previous = context.threads.enterForeign()
        try { return action() } finally { context.threads.leaveForeign(previous) }
    }

    /** Keep a caller allocation stable through validation, native execution and
     * publication. Invalid output must not advance or acquire a native search. */
    private inline fun <T> output(address: ManagedAddress, crossinline action: () -> T): T =
        address.withNativeBorrow {
            val checked = {
                address.requireByteRegion(Abi.size, writable = true)
                action()
            }
            address.cbitsOwner()?.let { synchronized(it) { checked() } } ?: checked()
        }

    private fun query(path: ManagedAddress): String = path.withNativeBorrow {
        val read = {
            val result = StringBuilder()
            val available = path.availableBytes()
            var index = 0L
            var terminated = false
            while (index + 1 < available) {
                val unit = path.readWord8(index) or (path.readWord8(index + 1) shl 8)
                if (unit == 0L) { terminated = true; break }
                result.append(unit.toInt().toChar())
                index += 2
            }
            if (!terminated) fault("Unterminated UTF-16 Windows directory query")
            val supplied = result.toString()
            // Extended namespace paths disable Win32 normalization. A slash
            // here must reach the original API unchanged, including errors.
            if (supplied.startsWith("\\\\?\\")) supplied else supplied.replace('/', '\\')
        }
        val value = path.cbitsOwner()?.let { synchronized(it) { read() } } ?: read()
        // directory's furnishPath normally supplies an absolute extended path.
        // Relative queries use the context CWD without changing process CWD.
        if (value.isEmpty() || value.startsWith("\\\\") ||
            Regex("^[A-Za-z]:\\\\").containsMatchIn(value)) value
        else {
            if (Regex("^[A-Za-z]:").containsMatchIn(value))
                fault("Drive-relative Windows queries require directory's absolute furnishPath")
            val cwd = context.env.currentWorkingDirectory.path
            if (value.startsWith("\\")) java.nio.file.Path.of(cwd).root.toString() + value.drop(1)
            else cwd.trimEnd('\\', '/') + "\\" + value
        }
    }

    @Synchronized @TruffleBoundary fun first(path: ManagedAddress, destination: ManagedAddress): ManagedAddress {
        current()
        val name = query(path)
        return output(destination) {
            Arena.ofConfined().use { arena ->
                val text = arena.allocate((name.length + 1L) * 2, 2)
                name.forEachIndexed { index, value -> text.set(JAVA_CHAR, index * 2L, value) }
                val data = arena.allocate(Abi.size, Abi.alignment)
                val error = arena.allocate(Api.capture)
                // Allocate identity before acquiring the OS resource.
                val token = ManagedAddress.fromAllocation(ManagedAllocation.immutable(ByteArray(0), 8, staticImage = true))
                val native = foreign { Api.first.invokeExact(error, text, data) as MemorySegment }
                lastError.set(Api.error(error))
                if (native.address() == -1L) invalidHandle()
                else try {
                    destination.copyFromByteArray(data.toArray(JAVA_BYTE), 0, Abi.size)
                    handles[key(token)] = native
                    token
                } catch (failure: Throwable) {
                    try { Api.close.invokeExact(error, native) as Int }
                    catch (closing: Throwable) { failure.addSuppressed(closing) }
                    throw failure
                }
            }
        }
    }

    @Synchronized @TruffleBoundary fun next(address: ManagedAddress, destination: ManagedAddress): Long {
        current()
        val native = handle(address)
        return output(destination) {
            Arena.ofConfined().use { arena ->
                val data = arena.allocate(Abi.size, Abi.alignment)
                val error = arena.allocate(Api.capture)
                val result = foreign { Api.next.invokeExact(error, native, data) as Int }
                lastError.set(Api.error(error))
                if (result != 0) destination.copyFromByteArray(data.toArray(JAVA_BYTE), 0, Abi.size)
                result.toLong()
            }
        }
    }

    @Synchronized @TruffleBoundary fun closeSearch(address: ManagedAddress): Long {
        current()
        val native = handle(address)
        return Arena.ofConfined().use { arena ->
            val error = arena.allocate(Api.capture)
            val result = foreign { Api.close.invokeExact(error, native) as Int }
            lastError.set(Api.error(error))
            if (result != 0) handles.remove(key(address))
            result.toLong()
        }
    }

    @TruffleBoundary fun error(): Long { current(); return lastError.get() }
    @Synchronized internal fun liveCount(): Int = handles.size
    @Synchronized override fun close() {
        if (disposed) return
        disposed = true
        var failure: Throwable? = null
        Arena.ofConfined().use { arena ->
            val error = arena.allocate(Api.capture)
            for (native in handles.values) try {
                if ((Api.close.invokeExact(error, native) as Int) == 0)
                    throw IllegalStateException("FindClose failed during context disposal: " + Api.error(error))
            } catch (closing: Throwable) {
                if (failure == null) failure = closing else failure.addSuppressed(closing)
            }
        }
        handles.clear()
        failure?.let { throw it }
    }

    internal object Abi {
        private val fields: Map<*, *> by lazy {
            check(supportedHost()) { "Windows directory ABI requires Windows x86_64" }
            val document = WindowsDirectoryStreams::class.java.getResourceAsStream("/thc/native/windows-directory-abi.json")
                ?.bufferedReader()?.use { Json.parse(it.readText()) as Map<*, *> }
                ?: fault("Missing native Windows directory ABI")
            val fields = document["layout"] as? Map<*, *> ?: fault("Malformed Windows directory ABI")
            check(document["schema"] == 1L && document["architecture"] == "x86_64" &&
                fields["pointerBytes"] == 8L && fields["wcharBytes"] == 2L &&
                fields["boolBytes"] == 4L && fields["dwordBytes"] == 4L)
            fields
        }
        val size: Long get() = fields["findDataBytes"] as Long
        val alignment: Long get() = fields["findDataAlignment"] as Long
        val nameOffset: Long get() = fields["nameOffset"] as Long
        val nameUnits: Long get() = fields["nameUnits"] as Long
        val noMoreFiles: Long get() = fields["noMoreFiles"] as Long
    }

    private object Api {
        private val linker = Linker.nativeLinker()
        private val lookup = SymbolLookup.libraryLookup("kernel32.dll", Arena.global())
        val capture = Linker.Option.captureStateLayout()
        private val errorOffset = capture.byteOffset(MemoryLayout.PathElement.groupElement("GetLastError"))
        private val captureError = Linker.Option.captureCallState("GetLastError")
        private fun call(name: String, result: MemoryLayout, vararg args: MemoryLayout) =
            linker.downcallHandle(lookup.find(name).orElseThrow(), FunctionDescriptor.of(result, *args), captureError)
        val first = call("FindFirstFileW", ADDRESS, ADDRESS, ADDRESS)
        val next = call("FindNextFileW", JAVA_INT, ADDRESS, ADDRESS)
        val close = call("FindClose", JAVA_INT, ADDRESS)
        fun error(storage: MemorySegment): Long = storage.get(JAVA_INT, errorOffset).toLong() and 0xffff_ffffL
    }

    companion object {
        @JvmName("supportedHost")
        internal fun supportedHost(): Boolean = System.getProperty("os.name").startsWith("Windows") &&
            System.getProperty("os.arch") in setOf("amd64", "x86_64")
        internal fun invalidHandle(): ManagedAddress = ManagedAddress.unownedNumeric(-1L)
        @JvmStatic fun current(node: Node): WindowsDirectoryStreams =
            Language.currentState(node).windowsDirectories
                ?: throw SecurityException("Windows directory scanning requires the fixed-filesystem NativeIO context")

        internal fun createContext(profile: ContextProfile = ContextProfile.NATIVE,
                                   ffiMode: FfiMode = FfiMode.NATIVE): Context {
            check(supportedHost()) { "Windows directory scanning requires native Windows x86_64" }
            // A built Context cannot have its filesystem replaced after this
            // authority is installed. Never authenticate custom wrappers.
            val context = Context.newBuilder("thc").allowNativeAccess(true)
                .allowIO(IOAccess.newBuilder().allowHostFileAccess(true).build())
                .withContextProfile(profile).withFfiMode(ffiMode).build()
            try {
                context.initialize("thc"); context.enter()
                try {
                    val state = Language.currentState()
                    state.windowsDirectories = WindowsDirectoryStreams(state).also { state.env.registerOnDispose(it) }
                } finally { context.leave() }
                return context
            } catch (failure: Throwable) {
                try { context.close() } catch (closing: Throwable) { failure.addSuppressed(closing) }
                throw failure
            }
        }
    }
}
