// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

@file:Suppress("UNCHECKED_CAST")
package thc.runtime

import org.junit.jupiter.api.Assertions.*
import java.io.File
import java.security.MessageDigest
import java.util.HexFormat

/** Independent expectations; the native preparer only records observations. */
internal object OriginalStdioChecks {
    val names = listOf("originalWrite", "originalSafeWrite", "originalWriteErrno", "originalSafeWriteErrno")
    val payload = ByteArray(256) { it.toByte() }
    private val slices = listOf(0 to 0, 256 to 0, 0 to 1, 1 to 8, 10 to 1, 127 to 3, 128 to 128, 255 to 1, 0 to 256)
    fun hex(value: ByteArray): String = HexFormat.of().formatHex(value)
    fun expectedRows(): List<Map<String, Any?>> {
        val badDescriptor = StdioHostAbi.load().error(4)
        return names.flatMap { name ->
            listOf(Int.MIN_VALUE.toLong(), -1L, 1L, 2L).flatMap { fd -> slices.map { (offset, count) ->
                val failed = fd < 0
                val result = if (name.endsWith("Errno")) {
                    if (failed) badDescriptor else -count.toLong() - 2
                } else if (failed) -1L else count.toLong()
                val bytes = hex(payload.copyOfRange(offset, offset + count))
                mapOf("entry" to name, "arguments" to listOf(fd, offset.toLong(), count.toLong()), "result" to result,
                    "stdoutHex" to if (fd == 1L) bytes else "", "stderrHex" to if (fd == 2L) bytes else "")
            } }
        }
    }
    fun rows(actual: Any?): List<Map<String, Any?>> {
        val expected = expectedRows()
        assertTrue(actual is List<*>, "native rows must be a list")
        val rows = actual as List<*>
        assertEquals(expected.size, rows.size, "missing/duplicate native rows")
        expected.forEachIndexed { index, row -> assertEquals(row, rows[index], "native row $index differs from Kotlin model") }
        return expected
    }

    fun hashes(root: File, value: Any?, required: Set<String>, prefix: String? = null) {
        val base = root.canonicalFile
        val hashes = value as Map<String, String>
        assertTrue(hashes.keys.containsAll(required), "missing provenance: ${required - hashes.keys}")
        for ((path, expected) in hashes) {
            val file = File(base, path)
            assertFalse(File(path).isAbsolute, path)
            assertEquals(file.absoluteFile, file.canonicalFile, "noncanonical provenance path: $path")
            assertTrue(file.toPath().startsWith(base.toPath()), "external provenance: $path")
            if (prefix != null) assertTrue(path.startsWith(prefix), "unexpected artifact: $path")
            assertTrue(expected.matches(Regex("[0-9a-f]{64}")), path)
            assertEquals(expected, hex(MessageDigest.getInstance("SHA-256").digest(file.readBytes())), "stale fixture: $path")
        }
    }

    fun nodes(value: Any?): List<List<Any?>> = when (value) {
        is Map<*, *> -> value.values.flatMap(::nodes)
        is List<*> -> listOf(value) + value.flatMap(::nodes)
        else -> emptyList()
    }
    private fun rep(expression: Any?) = ((expression as List<*>).last() as Map<*, *>)["rep"]
    fun foreignCalls(value: Any?) = nodes(value).filter {
        it.firstOrNull() == "app" && (it.lastOrNull() as? Map<*, *>)?.containsKey("foreignCall") == true
    }

    /** Check the real exported consumer, including the immediate runRW State lambda. */
    fun module(module: Map<String, Any?>): Map<String, List<String>> {
        val bindings = module["bindings"] as List<Map<String, Any?>>
        val roots = bindings.filter { it["name"] in names }
        assertEquals(names.toSet(), roots.map { it["name"] }.toSet())
        assertEquals(4, roots.size)
        val bound = mutableSetOf<Any?>()
        fun binders(value: Any?) {
            when (value) {
                is Map<*, *> -> { if (value.containsKey("id")) bound.add(value["id"]); value.values.forEach(::binders) }
                is List<*> -> value.forEach(::binders)
            }
        }
        binders(module)
        val result = roots.associate { binding ->
            val name = binding["name"] as String
            val body = binding["expr"] as List<*>
            assertEquals(4L, binding["arity"])
            assertEquals(OriginalStdioFixtures.closure(), binding["rep"])
            assertEquals("lam", body[0], name)
            assertEquals(OriginalStdioFixtures.scalar("IntRep", false), (body.last() as Map<*, *>)["resultRep"])
            val formals = body[1] as List<Map<String, Any?>>
            assertEquals(4, formals.size)
            for ((formal, primitive) in formals.zip(listOf("IntRep", "AddrRep", "IntRep", "WordRep"))) {
                assertEquals(OriginalStdioFixtures.scalar(primitive), formal["rep"], name)
                assertEquals(false, formal["lifted"]); assertEquals(false, formal["coercion"])
            }
            assertEquals(2, nodes(body).count { it.firstOrNull() == "lam" }, name)
            val run = body[2] as List<*>
            assertEquals("app", run[0]); assertEquals(listOf(false), run[3])
            assertEquals(OriginalStdioFixtures.scalar("IntRep", false), rep(run))
            val lambda = run[1] as List<*>
            assertEquals("lam", lambda[0])
            val state = (lambda[1] as List<Map<String, Any?>>).single()
            assertEquals("State# RealWorld", state["type"])
            assertEquals(OriginalStdioFixtures.scalar(null), state["rep"])
            assertEquals(false, state["lifted"]); assertEquals(false, state["coercion"])
            val argument = (run[2] as List<*>).single() as List<*>
            assertEquals("void", argument[0]); assertEquals(OriginalStdioFixtures.scalar(null), rep(argument))
            val expected = listOf(if (name.contains("Safe")) "safe_write" else "unsafe_write") +
                if (name.endsWith("Errno")) listOf("errno") else emptyList()
            val symbols = foreignCalls(body).map { app ->
                assertEquals(7, app.size)
                val head = app[1] as List<Any?>
                CoreOriginalStdio.validateHead(head, head[1] in bound)
                val operation = CoreOriginalStdio.validate(app[6], (app[2] as List<*>).map(::rep), app[3] as List<*>, rep(app))
                assertNotNull(operation, "unsupported original foreign call in $name")
                operation!!.symbol
            }
            assertEquals(expected.map { OriginalStdioFixtures.symbols.getValue(it) }, symbols, name)
            (binding["id"] as String) to symbols
        }
        assertEquals(6, foreignCalls(module).size, "extra/missing FCall copies outside the four consumers")
        return result
    }

    fun audit(report: Map<String, Any?>, owner: String, symbols: List<String>) {
        assertEquals(true, report["accepted"])
        assertEquals(listOf(owner), report["roots"])
        assertEquals(listOf(owner), (report["reachableBindings"] as List<Map<String, Any?>>).map { it["id"] })
        for (field in listOf("issues", "missingGlobals", "runtimeExternals")) assertEquals(emptyList<Any?>(), report[field], field)
        val calls = report["foreignCalls"] as List<Map<String, Any?>>
        assertEquals(symbols.sorted(), calls.map { it["symbol"] as String }.sorted())
        assertTrue(calls.all { it["owner"] == owner })
    }
}
