// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime

import org.graalvm.polyglot.Context
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import thc.CoreModules
import thc.Json
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Both sender and target are exported Haskell running through the public parser. */
class ThreadAsyncNativeTest {
    private val root = File(System.getProperty("thc.projectRoot"))

    @Suppress("UNCHECKED_CAST")
    private fun checkReceipt() {
        val receipt = Json.parse(File(root, "build/thread-async/manifest.json").readText()) as Map<String, Any?>
        for (kind in listOf("inputHashes", "artifactHashes"))
            for ((path, expected) in receipt[kind] as Map<String, String>) {
                val bytes = MessageDigest.getInstance("SHA-256").digest(File(root, path).readBytes())
                assertEquals(expected, bytes.joinToString("") { "%02x".format(it) }, "Stale $path")
            }
        assertEquals(listOf("43", "44"), File(root, "build/thread-async/oracle.txt").readLines())
        assertEquals(listOf("5", "-1"), File(root, "build/thread-async/extra-oracle.txt").readLines())
        assertEquals(listOf("52", "53"), File(root, "build/thread-async/lazy-oracle.txt").readLines())
    }

    private fun exercise(name: String, expected: List<Long>, module: String = "ThreadAsyncAudit") {
        checkReceipt()
        for (stage in listOf("pre", "post")) {
            val context = Context.newBuilder("thc").allowExperimentalOptions(true).allowCreateThread(true)
                .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false")
                .option("engine.Splitting", "false").option("engine.CompilationFailureAction", "Throw").build()
            val executor = Executors.newSingleThreadExecutor { task ->
                Thread(task, "thc-public-thread-test").apply { isDaemon = true }
            }
            try {
                val core = File(root, "build/thread-async/$stage/core/$module.json")
                val entry = context.eval("thc", CoreModules.request(listOf(core.path), name, backend = "bytecode"))
                val result = executor.submit<List<Long>> {
                    val interpreted = entry.execute(0L).asLong()
                    assertTrue(entry.invokeMember("compile").asBoolean())
                    listOf(interpreted, entry.execute(1L).asLong())
                }
                assertEquals(expected, result.get(30, TimeUnit.SECONDS), "$stage $name")
                val diagnostics = Json.parse(entry.getMember("diagnostics").asString()) as Map<*, *>
                assertEquals(0L, (diagnostics["unsupportedTraps"] as Number).toLong())
            } finally {
                context.close(true)
                executor.shutdownNow()
            }
        }
    }

    @Test fun publicForkAndThrowResumeTheSharedThunk() = exercise("forkAndThrow", listOf(43, 44))
    @Test fun uncaughtChildDeliveryReleasesTheSender() = exercise("killUncaught", listOf(5, 6))
    @Test fun selfDirectedThrowEntersTheOriginalHandler() = exercise("selfThrow", listOf(-1, 0))
    @Test fun forkedChildOwnsAndResumesTheSharedLazyActionHead() =
        exercise("lazyFork", listOf(52, 53), "LazyForkAudit")
}
