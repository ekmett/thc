// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

@file:Suppress("UNCHECKED_CAST")
package thc.runtime

import com.oracle.truffle.api.TruffleLanguage
import org.graalvm.polyglot.Context
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import thc.CoreModules
import thc.EntryValue
import thc.Json
import thc.Language
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class FetchAddIntArrayTest {
    private val root = File(System.getProperty("thc.projectRoot"))
    private val directory = File(root, "build/fetch-add-int-array")
    private fun json(file: File) = Json.parse(file.readText()) as Map<String, Any?>
    private fun digest(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
        .joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun context() = Context.newBuilder("thc").allowExperimentalOptions(true)
        .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false")
        .option("engine.CompilationFailureAction", "Throw").build()

    private data class Row(val initial: Long, val first: Long, val second: Long, val result: Long)
    private fun model(initial: Long, first: Long, second: Long): Long {
        val old2 = initial + first
        val final = old2 + second
        return initial + 17L * old2 + 31L * final
    }
    private fun rows(): List<Row> {
        val expected = listOf(Long.MIN_VALUE, -7L, 0L, 1L, Long.MAX_VALUE).flatMap { initial ->
            listOf(-2L, 0L, 1L, Long.MAX_VALUE).flatMap { first ->
                listOf(-1L, 0L, 3L).map { second ->
                    Row(initial, first, second, model(initial, first, second))
                }
            }
        }
        val actual = File(directory, "oracle.tsv").readLines().map { line ->
            val fields = line.split('\t')
            assertEquals(4, fields.size)
            Row(fields[0].toLong(), fields[1].toLong(), fields[2].toLong(), fields[3].toLong())
        }
        assertEquals(expected, actual, "Native old-value/wraparound oracle")
        return actual
    }

    @Test fun nativeFetchAddRunsInCompiledAstAndBytecode() {
        val manifest = json(File(directory, "manifest.json"))
        assertEquals(1L, manifest["schema"])
        assertEquals("9.14.1", manifest["ghc"])
        assertEquals(listOf("fetchComposite"), manifest["entries"])
        for (kind in listOf("inputHashes", "artifactHashes"))
            for ((path, hash) in manifest[kind] as Map<String, String>)
                assertEquals(hash, digest(File(root, path)), "Stale fetch-add $kind: $path")
        val cases = rows()
        assertEquals(cases.size.toLong(), (manifest["nativeRows"] as Number).toLong())
        for ((stage, paths) in manifest["stages"] as Map<String, List<String>>) {
            val merged = CoreModules.merge(paths.map { json(File(root, it)) })
            val audit = json(File(directory, "$stage/fetchComposite.audit.json"))
            assertEquals(true, audit["accepted"], stage)
            assertEquals(emptyList<Any>(), audit["issues"])
            assertEquals(emptyList<Any>(), audit["missingGlobals"])
            assertTrue((audit["primitives"] as List<Map<String, Any?>>)
                .map { it["name"] }.contains("fetchAddIntArray#"))
            for (backend in listOf("ast", "bytecode")) context().use { context ->
                context.initialize("thc"); context.enter()
                try {
                    val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                    val linked = CoreModules.reachable(merged, "fetchComposite") + ("instrument" to true)
                    val program = if (backend == "ast") Program(language, linked)
                        else BytecodeProgram(language, linked)
                    val host = program.hostEntryTarget(3)
                    val entry = context.asValue(EntryValue(program, "fetchComposite", 3))
                    for (row in cases)
                        assertEquals(row.result, entry.execute(row.initial, row.first, row.second).asLong(),
                            "$stage/$backend/$row")
                    assertTrue(entry.invokeMember("compile").asBoolean(), "$stage/$backend install")
                    for (row in cases.asReversed()) {
                        val before = (program.diagnostics().getValue("compiledEntries") as Number).toLong()
                        assertEquals(row.result, entry.execute(row.initial, row.first, row.second).asLong(),
                            "$stage/$backend compiled $row")
                        assertTrue((program.diagnostics().getValue("compiledEntries") as Number).toLong() > before,
                            "$stage/$backend $row compiled")
                        assertEquals(true, host.javaClass.getMethod("isValidLastTier").invoke(host))
                    }
                    assertEquals(0L, (program.diagnostics().getValue("unsupportedTraps") as Number).toLong())
                    assertEquals(0, language.handoffState.get().results.depth)
                } finally { context.leave() }
            }
        }
    }

    @Test fun concurrentUpdatesHaveDistinctOldValuesAndFullBounds() {
        val owner = ManagedByteArray.allocateGuest(16)
        ManagedByteArray.writeIntGuest(owner, 0, 313)
        ManagedByteArray.writeIntGuest(owner, 1, 0)
        val threads = 4
        val perThread = 500
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(threads)
        try {
            val results = (0 until threads).map {
                pool.submit<List<Long>> {
                    assertTrue(start.await(10, TimeUnit.SECONDS))
                    (0 until perThread).map { ManagedByteArray.fetchAddIntGuest(owner, 1, 1) }
                }
            }
            start.countDown()
            val oldValues = results.flatMap { it.get(10, TimeUnit.SECONDS) }
            assertEquals((0 until threads * perThread).map(Int::toLong), oldValues.sorted())
            assertEquals((threads * perThread).toLong(), ManagedByteArray.readIntGuest(owner, 1))
            assertEquals(313L, ManagedByteArray.readIntGuest(owner, 0))
        } finally { pool.shutdownNow() }
        owner.shrink(8)
        assertThrows(RuntimeFault::class.java) { ManagedByteArray.fetchAddIntGuest(owner, 1, 1) }
        assertEquals(313L, ManagedByteArray.readIntGuest(owner, 0))
        assertThrows(RuntimeFault::class.java) { ManagedByteArray.fetchAddIntGuest(ByteArray(8), 0, 1) }
        val pointerOwner = ManagedByteArray.allocateGuest(8)
        val address = ManagedAddress.fromAllocation(pointerOwner)
        pointerOwner.writeAddressByteOffset(0, address)
        assertThrows(RuntimeFault::class.java) { ManagedByteArray.fetchAddIntGuest(pointerOwner, 0, 1) }
        assertSame(address, pointerOwner.readAddressByteOffset(0))
    }
}
