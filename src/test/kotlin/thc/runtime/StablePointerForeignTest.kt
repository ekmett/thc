// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime

import com.oracle.truffle.api.TruffleLanguage
import com.oracle.truffle.api.frame.VirtualFrame
import com.oracle.truffle.api.nodes.RootNode
import org.graalvm.polyglot.Context
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import thc.Language
import thc.PackageScalarLink
import thc.PackageScalarSignature
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat

/** The identical C source also belongs to the ordinary GHC/Cabal native oracle. */
class StablePointerForeignTest {
    @TempDir lateinit var directory: Path
    private fun compile(native: Boolean): Path {
        val source = Path.of(System.getProperty("thc.projectRoot"), "test/fixtures/run-stableptr-ffi/cbits/stable.c")
        val output = directory.resolve(if (native) "stable.so" else "stable.bc")
        val flags = if (native) listOf("-shared", "-fPIC") else listOf("-emit-llvm", "-c")
        val target = if (System.getProperty("os.name") == "Linux") listOf("--target=" +
            (if (System.getProperty("os.arch") == "amd64") "x86_64" else System.getProperty("os.arch")) + "-unknown-linux-gnu") else emptyList()
        val process = ProcessBuilder(listOf(System.getenv("THC_CLANG") ?: "clang", "-O1") + target + flags +
            listOf(source.toString(), "-o", output.toString())).redirectErrorStream(true).start()
        val log = process.inputStream.bufferedReader().readText()
        assertEquals(0, process.waitFor(), log)
        return output
    }
    private fun context(native: Boolean = true) = Context.newBuilder("thc").allowNativeAccess(native)
        .allowExperimentalOptions(true).option("engine.BackgroundCompilation", "false").build()
    private class Entry(language: Language, private val call: PackageScalarCall) : RootNode(language) {
        @Child private var access = PackageScalarAccess(call)
        override fun execute(frame: VirtualFrame): Any = when (call.result) {
            "AddrRep" -> access.executeAddress(frame.arguments, Unit)
            "void" -> { access.executeVoid(frame.arguments, Unit); Unit }
            else -> access.executeLong(frame.arguments, Unit)
        }
    }
    @Test fun sulongStoresReturnsAndComparesOpaqueStablePointersAcrossCalls() {
        val bytes = Files.readAllBytes(compile(false))
        val sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
        val abi = listOf(
            PackageScalarSignature("stable_store", "stable_store", listOf("AddrRep"), "void"),
            PackageScalarSignature("stable_load", "stable_load", emptyList(), "AddrRep"),
            PackageScalarSignature("stable_identity", "stable_identity", listOf("AddrRep"), "AddrRep"),
            PackageScalarSignature("stable_equal", "stable_equal", listOf("AddrRep", "AddrRep"), "Int32Rep"),
            PackageScalarSignature("stable_clear", "stable_clear", emptyList(), "void"),
            PackageScalarSignature("stable_unknown", "stable_unknown", emptyList(), "AddrRep"))
        val link = PackageScalarLink("stable-ffi-control", "test-host", sha, sha, bytes, abi)
        context().use { context ->
            context.initialize("thc"); context.enter()
            try {
                val state = Language.currentState()
                val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                state.packageCbits.link(link)
                val calls = abi.associate { it.symbol to Entry(language, PackageScalarCall(link, it)).callTarget }
                assertSame(ManagedAddress.nullAddress(), calls.getValue("stable_identity").call(ManagedAddress.nullAddress()))
                val pinned = PinnedMemory.allocate(16, 64)
                val pinnedAddress = ManagedAddress.fromAllocation(pinned).plus(7)
                val pinnedBits = pinned.nativeSegment()!!.address()
                val returnedPinned = calls.getValue("stable_identity").call(pinnedAddress) as ManagedAddress
                assertEquals(pinnedBits + 7, returnedPinned.toNativeBits())
                assertEquals(pinnedBits, pinned.nativeSegment()!!.address())
                assertThrows(RuntimeFault::class.java) { returnedPinned.readWord8(0) }
                val heap = ManagedAllocation.mutable(16, 8)
                assertThrows(RuntimeFault::class.java) {
                    calls.getValue("stable_identity").call(ManagedAddress.fromAllocation(heap))
                }
                assertFalse(heap.isPinned)
                assertNull(heap.nativeSegment())
                if (System.getProperty("os.name") == "Linux" && System.getProperty("os.arch") in setOf("amd64", "x86_64")) {
                    val allocation = state.nativeAllocations.malloc(8)
                    try {
                        val returnedAllocation = calls.getValue("stable_identity").call(allocation) as ManagedAddress
                        assertEquals(allocation.toNativeBits(), returnedAllocation.toNativeBits())
                        assertThrows(RuntimeFault::class.java) { returnedAllocation.readWord8(0) }
                    } finally { state.nativeAllocations.free(allocation) }
                }
                val referent = Any()
                val first = state.stablePointers.make(referent)
                val second = state.stablePointers.make(referent)
                calls.getValue("stable_store").call(first)
                assertEquals(1L, calls.getValue("stable_equal").call(first, first))
                assertEquals(0L, calls.getValue("stable_equal").call(first, second))
                val returned = calls.getValue("stable_load").call() as ManagedAddress
                assertSame(referent, state.stablePointers.dereference(returned))
                val again = calls.getValue("stable_identity").call(returned) as ManagedAddress
                assertTrue(state.stablePointers.equal(first, again))
                assertThrows(RuntimeFault::class.java) { again.readWord8(0) }
                assertThrows(RuntimeFault::class.java) { again.plus(0) }
                // A completely unrelated C pointer must not acquire byte-storage authority.
                val unrelated = calls.getValue("stable_unknown").call() as ManagedAddress
                assertThrows(RuntimeFault::class.java) { unrelated.readWord8(0) }
                assertThrows(RuntimeFault::class.java) { state.stablePointers.dereference(unrelated) }
                calls.getValue("stable_clear").call()
                assertSame(ManagedAddress.nullAddress(), calls.getValue("stable_load").call())
                val token = state.stablePointers.nativeTransport(first)
                state.stablePointers.free(first)
                assertFalse(token.isPointer())
                assertNull(state.stablePointers.recoverToken(token.bits))
                assertThrows(RuntimeFault::class.java) { state.stablePointers.dereference(returned) }
                assertThrows(RuntimeFault::class.java) { calls.getValue("stable_store").call(first) }
                state.stablePointers.free(second)
            } finally { context.leave() }
        }
    }

    @Test @EnabledOnOs(OS.LINUX) @EnabledIfSystemProperty(named = "os.arch", matches = "amd64|x86_64")
    fun hostNativeCStoresAndReturnsTheSameTokenAndNativeCellsRecoverIt() {
        Arena.ofConfined().use { libraryLifetime ->
            val library = SymbolLookup.libraryLookup(compile(true), libraryLifetime)
            val linker = Linker.nativeLinker()
            val store = linker.downcallHandle(library.find("stable_store").orElseThrow(), FunctionDescriptor.ofVoid(ValueLayout.ADDRESS))
            val load = linker.downcallHandle(library.find("stable_load").orElseThrow(), FunctionDescriptor.of(ValueLayout.ADDRESS))
            val equal = linker.downcallHandle(library.find("stable_equal").orElseThrow(), FunctionDescriptor.of(ValueLayout.JAVA_INT,
                ValueLayout.ADDRESS, ValueLayout.ADDRESS))
            val clear = linker.downcallHandle(library.find("stable_clear").orElseThrow(), FunctionDescriptor.ofVoid())
            context().use { context ->
                context.initialize("thc"); context.enter()
                try {
                    val state = Language.currentState()
                    val value = Any()
                    val pointer = state.stablePointers.make(value)
                    val bits = pointer.toNativeBits()
                    assertSame(value, state.stablePointers.dereference(state.nativeAddresses.recover(bits)))
                    val native = MemorySegment.ofAddress(bits)
                    store.invokeWithArguments(native)
                    assertEquals(1, equal.invokeWithArguments(native, native))
                    val returned = load.invokeWithArguments() as MemorySegment
                    assertEquals(bits, returned.address())
                    assertSame(value, state.stablePointers.dereference(state.stablePointers.recoverToken(returned.address())!!))
                    val cell = state.nativeAllocations.malloc(8)
                    try {
                        cell.writeAddressElementIndex(0, pointer)
                        assertSame(value, state.stablePointers.dereference(cell.readAddressElementIndex(0)))
                    } finally { state.nativeAllocations.free(cell) }
                    clear.invokeWithArguments()
                    state.stablePointers.free(pointer)
                    assertNull(state.stablePointers.recoverToken(bits))
                    assertThrows(RuntimeFault::class.java) { state.stablePointers.dereference(state.nativeAddresses.recover(bits)) }
                } finally { context.leave() }
            }
        }
    }

    @Test fun nativeAccessContextOwnershipAndDisposalRemainExplicit() {
        context(false).use { denied ->
            denied.initialize("thc"); denied.enter()
            try {
                val registry = Language.currentState().stablePointers
                val pointer = registry.make(Any())
                assertThrows(RuntimeFault::class.java) { registry.nativeTransport(pointer) }
                registry.free(pointer)
            } finally { denied.leave() }
        }
        val first = context()
        first.initialize("thc"); first.enter()
        val registry = Language.currentState().stablePointers
        val pointer = registry.make(Any())
        val token = registry.nativeTransport(pointer)
        first.leave()
        context().use { other ->
            other.initialize("thc"); other.enter()
            try {
                val second = Language.currentState().stablePointers
                val local = second.make(Any())
                assertNotEquals(token.bits, second.nativeToken(local), "simultaneously live contexts have distinct native identities")
                assertNull(second.recoverToken(token.bits))
                assertThrows(RuntimeFault::class.java) { second.nativeTransport(pointer) }
                assertThrows(RuntimeFault::class.java) { token.asPointer() }
                second.free(local)
            } finally { other.leave() }
        }
        first.close()
        assertFalse(token.isPointer())
        assertThrows(RuntimeFault::class.java) { registry.dereference(pointer) }
    }
}
