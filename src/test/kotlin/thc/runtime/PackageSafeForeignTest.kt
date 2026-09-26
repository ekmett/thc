// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime

import com.oracle.truffle.api.TruffleLanguage
import com.oracle.truffle.api.frame.VirtualFrame
import com.oracle.truffle.api.interop.ArityException
import com.oracle.truffle.api.nodes.RootNode
import org.graalvm.polyglot.Context
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import thc.*
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Actual C boundary controls; unchanged erf acquisition is checked separately. */
class PackageSafeForeignTest {
    @TempDir lateinit var directory: Path

    private fun library(): PackageScalarLink {
        val source = directory.resolve("safe.c")
        val output = directory.resolve("safe.bc")
        Files.writeString(source, """
            #include <stdatomic.h>
            static _Atomic int phase;
            int started(void) { return atomic_load(&phase); }
            void release(void) { atomic_store(&phase, 2); }
            double wait_value(double value) {
              atomic_store(&phase, 1);
              for (unsigned i = 0; i < 1000000000; ++i)
                if (atomic_load(&phase) == 2) return value;
              return -123.0;
            }
            double requires_argument(double value) { return value; }
        """.trimIndent())
        val cpu = if (System.getProperty("os.arch") == "amd64") "x86_64" else System.getProperty("os.arch")
        val target = if (System.getProperty("os.name") == "Linux") "$cpu-unknown-linux-gnu" else "$cpu-apple-darwin"
        val process = ProcessBuilder(System.getenv("THC_CLANG") ?: "clang", "--target=$target", "-std=c11",
            "-O1", "-emit-llvm", "-c", source.toString(), "-o", output.toString()).redirectErrorStream(true).start()
        val diagnostic = process.inputStream.bufferedReader().readText()
        assertEquals(0, process.waitFor(), diagnostic)
        val bytes = Files.readAllBytes(output)
        val hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
        val abi = listOf(
            PackageScalarSignature("started", "started", emptyList(), "Int32Rep", safety = "safe"),
            PackageScalarSignature("release", "release", emptyList(), "void", safety = "safe"),
            PackageScalarSignature("wait_value", "wait_value", listOf("DoubleRep"), "DoubleRep", safety = "safe"),
            // Deliberately inconsistent control: no forged typed Core is admitted.
            // The runtime interop call throws inside the foreign extent.
            PackageScalarSignature("requires_argument", "requires_argument", emptyList(), "DoubleRep", safety = "safe"))
        return PackageScalarLink("safe-boundary-control", target, hash, hash, bytes, abi)
    }

    private class Entry(language: Language, private val call: PackageScalarCall) : RootNode(language) {
        @Child private var access = PackageScalarAccess(call)
        override fun execute(frame: VirtualFrame): Any = when (call.result) {
            "DoubleRep" -> access.executeDouble(frame.arguments, Unit)
            "Int32Rep" -> access.executeLong(frame.arguments, Unit)
            "void" -> { access.executeVoid(frame.arguments, Unit); Unit }
            else -> error("Unexpected safe control ABI")
        }
    }

    @Test fun safeScalarDefersAsyncUntilReturnWhileOtherGuestCallsAndGcProgress() {
        val link = library()
        Context.newBuilder("thc").allowNativeAccess(true).withContextProfile(ContextProfile.SYNCHRONOUS_TEST)
            .build().use { context ->
                context.initialize("thc"); context.enter()
                try {
                    val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                    val owner = Language.currentState()
                    owner.packageCbits.link(link)
                    val entries = link.abi.associate { it.symbol to Entry(language, PackageScalarCall(link, it)).callTarget }
                    val target = entries.getValue("wait_value")
                    val identity = AtomicLong()
                    val pending = AtomicReference<AsyncRequest>()
                    val failure = AtomicReference<Throwable>()
                    val worker = Thread {
                        context.enter()
                        identity.set(owner.threads.enterCurrent())
                        try {
                            assertEquals(0.5, target.call(0.5))
                            val request = owner.threads.poll(target.rootNode)
                            assertSame(pending.get(), request, "delivery becomes possible at the returning guest cut")
                            request!!.acknowledge()
                        } catch (problem: Throwable) { failure.set(problem) }
                        finally { owner.threads.leaveCurrent(); context.leave() }
                    }
                    worker.start()
                    try {
                        val deadline = System.nanoTime() + 5_000_000_000L
                        while (entries.getValue("started").call() != 1L && System.nanoTime() < deadline) Thread.sleep(1)
                        assertEquals(1L, entries.getValue("started").call(), "other guest call observes the active C loop")
                        val request = owner.threads.send(identity.get(), "after safe return")
                        pending.set(request)
                        System.gc()
                        assertEquals(AsyncRequestState.PENDING, request.state, "no guest delivery from the opaque foreign frame")
                    } finally {
                        entries.getValue("release").call()
                        worker.join(5000)
                    }
                    assertFalse(worker.isAlive, "scalar safe return must release the Java carrier")
                    failure.get()?.let { throw it }
                    assertEquals(AsyncRequestState.ACKNOWLEDGED, pending.get().state)
                } finally { context.leave() }
            }
    }

    @Test fun failingInteropRestoresGuestAndNestedForeignPermissions() {
        val link = library()
        Context.newBuilder("thc").allowNativeAccess(true).withContextProfile(ContextProfile.SYNCHRONOUS_TEST)
            .build().use { context ->
                context.initialize("thc"); context.enter()
                try {
                    val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                    val owner = Language.currentState()
                    owner.packageCbits.link(link)
                    val signature = link.abi.single { it.symbol == "requires_argument" }
                    val target = Entry(language, PackageScalarCall(link, signature)).callTarget
                    val identity = owner.threads.enterCurrent()
                    try {
                        val request = owner.threads.send(identity, "pending across failed call")
                        val outer = owner.threads.enterForeign()
                        try {
                            assertThrows(ArityException::class.java) { target.call() }
                            assertNull(owner.threads.poll(target.rootNode))
                            assertEquals(AsyncRequestState.PENDING, request.state)
                        } finally { owner.threads.leaveForeign(outer) }
                        assertSame(request, owner.threads.poll(target.rootNode))
                        request.acknowledge()
                    } finally { owner.threads.leaveCurrent() }
                    val restored = owner.threads.enterForeign()
                    try { assertEquals(GuestThreads.DeliveryPermission.NONE, restored) }
                    finally { owner.threads.leaveForeign(restored) }
                } finally { context.leave() }
            }
    }
}
