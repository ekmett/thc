// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime

import com.oracle.truffle.api.TruffleLanguage
import com.oracle.truffle.api.frame.VirtualFrame
import com.oracle.truffle.api.nodes.RootNode
import org.graalvm.polyglot.Context
import org.graalvm.polyglot.io.IOAccess
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import thc.*
import java.nio.file.Files
import java.nio.file.Path

/** Native DLL lookup must not depend on guest cwd/file authority. The buffer
 * remains real context-owned Sulong C storage, not a managed replacement. */
@EnabledOnOs(OS.WINDOWS)
class WindowsSulongLibraryLookupTest {
    @TempDir lateinit var scratch: Path

    private fun context(native: Boolean = true): Context = Context.newBuilder("thc")
        .allowIO(IOAccess.NONE).allowNativeAccess(native)
        .withContextProfile(ContextProfile.SYNCHRONOUS_TEST).build()

    private fun <T> entered(context: Context, action: (Language.State) -> T): T {
        context.initialize("thc")
        context.enter()
        try {
            val owner = Language.currentState()
            owner.threads.enterCurrent()
            try { return action(owner) }
            finally { owner.threads.leaveCurrent() }
        } finally { context.leave() }
    }

    private class Buffer(language: Language, call: PackageScalarCall) : RootNode(language) {
        @Child private var access = PackageScalarAccess(call)
        override fun execute(frame: VirtualFrame): Any = access.executeAddress(emptyArray(), Unit)
    }

    private fun buffer(owner: Language.State): ManagedAddress {
        val symbol = "thc_package_pointer_test_buffer"
        val signature = PackageScalarSignature(symbol, symbol, emptyList(), "AddrRep")
        val bytes = javaClass.getResourceAsStream("/thc/cbits/package-pointer.bc")!!.use { it.readBytes() }
        val link = PackageScalarLink("windows-loader-control", "unused", "windows-loader-control", "", bytes, listOf(signature))
        owner.packageCbits.link(link)
        val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
        return Buffer(language, PackageScalarCall(link, signature)).callTarget.call() as ManagedAddress
    }

    @Test fun nativeDependencyLoadingLeavesGuestCwdAndFileAccessDenied() {
        val marker = Files.writeString(scratch.resolve("private.txt"), "host-only")
        context().use { context -> entered(context) { owner ->
            fun denied() {
                assertThrows(SecurityException::class.java) { owner.env.currentWorkingDirectory }
                assertThrows(SecurityException::class.java) { owner.env.getPublicTruffleFile(marker.toString()).readAllBytes() }
            }
            denied()
            val pointer = buffer(owner)
            assertNotNull(pointer.returnedAddress())
            assertNull(pointer.returnedAddress()!!.backing)
            assertNull(pointer.nativeAllocation())
            assertThrows(RuntimeFault::class.java) { pointer.availableBytes() }
            pointer.fill(32, 90)
            assertArrayEquals(ByteArray(32) { 90 }, ByteArray(32).also { pointer.copyToByteArray(it, 0, 32) })
            denied()
        } }
    }

    @Test fun realStaticBuffersRemainIsolatedAndRejectForeignContextUse() {
        context().use { first -> context().use { second ->
            val left = entered(first) { owner -> buffer(owner).also { it.fill(32, 17) } }
            val right = entered(second) { owner ->
                buffer(owner).also {
                    assertArrayEquals(ByteArray(32), ByteArray(32).also { bytes -> it.copyToByteArray(bytes, 0, 32) })
                    it.fill(32, 34)
                    assertThrows(RuntimeFault::class.java) { left.readWord8(0) }
                }
            }
            entered(first) {
                assertEquals(17L, left.readWord8(0))
                assertThrows(RuntimeFault::class.java) { right.readWord8(0) }
            }
            entered(second) { assertEquals(34L, right.readWord8(0)) }
        } }
    }

    @Test fun nativeLibraryLoadingStillRequiresNativeAuthority() {
        context(native = false).use { context -> entered(context) { owner ->
            val failure = assertThrows(RuntimeFault::class.java) { buffer(owner) }
            assertTrue(failure.message!!.contains("requires native access"))
        } }
    }
}
