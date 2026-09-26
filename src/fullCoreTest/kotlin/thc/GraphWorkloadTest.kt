// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
@file:Suppress("UNCHECKED_CAST")
package thc

import com.oracle.truffle.api.TruffleLanguage
import org.graalvm.polyglot.Context
import org.graalvm.polyglot.Source
import org.graalvm.polyglot.Value
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.File
import java.security.MessageDigest

/** Explicit source-library proof suite: native GHC, independent shortest-distance relaxation and actual strict Core
 * agree; a retained boot-library frontier is not a successful execution test. */
class GraphWorkloadTest {
    private val root = File(System.getProperty("thc.projectRoot"))
    private val directory = "build/graph-bfs"
    private val entries = listOf("graphChecksum", "graphReachable", "graphDistanceTotal", "graphDistanceAt", "graphControl")
    private val sizes = listOf(Long.MIN_VALUE, -17L, -1L) + (0L..20L) +
        listOf(31L, 32L, 63L, 64L, 127L, 128L, 255L, 256L, 257L, 511L, 512L, 513L, Long.MAX_VALUE)
    private val requests = entries.take(3).flatMap { entry -> sizes.map { entry to it } } +
        ((0L..12L) + listOf(31L, 64L, 127L, 256L, 512L)).flatMap { n ->
            val indices = if (n <= 12) (0L..n + 1).toList()
                else listOf(0L, 1L, n / 2, n - n / 4 - 1, n - n / 4, n - 1, n, n + 1)
            indices.map { "graphDistanceAt" to (n * 1024 + it) }
        } + (0L..9L).flatMap { test -> (0L..19L).map { "graphControl" to (test * 32 + it) } }
    private data class Row(val entry: String, val input: Long, val result: Long)

    private fun graph(input: Long): Map<Int, List<Int>> {
        val n = input.coerceIn(0, 512).toInt()
        val cut = n - n / 4
        return (0 until n).associate { i ->
            val first = if (i < cut) 0 else cut
            val limit = if (i < cut) cut else n
            val next = if (i + 1 < limit) i + 1 else first
            val stride = 1 + ((37 * i + 11) and 7)
            val jump = if (i + stride < limit) i + stride else first
            (2 * i - n) to listOf(next, jump, i, next).map { 2 * it - n }
        }
    }

    // Bellman-Ford-style synchronous edge relaxation, not the Haskell FIFO or
    // its discovery-set algorithm. A round never consumes its own new distances.
    private fun distances(graph: Map<Int, List<Int>>, start: Int): Map<Int, Int> {
        if (start !in graph) return emptyMap()
        var current = mapOf(start to 0)
        val vertices = graph.keys + graph.values.flatten()
        repeat(vertices.size) {
            val next = current.toMutableMap()
            for ((source, edges) in graph) {
                val distance = current[source] ?: continue
                for (destination in edges) if (distance + 1 < (next[destination] ?: Int.MAX_VALUE))
                    next[destination] = distance + 1
            }
            if (next == current) return current
            current = next
        }
        error("Positive unit-edge distances did not reach a fixed point")
    }
    private fun controls(index: Int): Pair<Map<Int, List<Int>>, Int> = when (index) {
        0 -> emptyMap<Int, List<Int>>() to 0
        1 -> mapOf(0 to emptyList<Int>()) to 0
        2 -> mapOf(0 to listOf(1), 1 to listOf(2), 2 to emptyList()) to 0
        3 -> mapOf(0 to listOf(1, 2), 1 to listOf(3), 2 to listOf(3), 3 to emptyList()) to 0
        4 -> mapOf(-3 to listOf(-3, 7, 7), 7 to listOf(-3)) to -3
        5 -> mapOf(-1 to listOf(2), 2 to emptyList(), 9 to listOf(10), 10 to emptyList()) to -1
        6 -> mapOf(0 to listOf(1)) to 0
        7 -> mapOf(0 to listOf(1)) to 99
        8 -> mapOf(0 to listOf(1, 3), 1 to listOf(2), 2 to listOf(3), 3 to emptyList()) to 0
        9 -> mapOf(0 to listOf(1), 1 to listOf(2, 0), 2 to listOf(0)) to 2
        else -> error("Unknown control graph")
    }
    private fun checksum(found: Map<Int, Int>): Long = (found.entries.sumOf { (vertex, distance) ->
        (vertex.toLong() and 65535) * 257 + (distance + 1L) * (1 + (vertex.toLong() and 1023))
    } + 17 * found.size + 31L * found.values.sum()) and 2147483647
    private fun expected(entry: String, input: Long): Long {
        if (entry == "graphControl") {
            val (graph, start) = controls((input / 32).toInt())
            val found = distances(graph, start)
            return when (val field = (input and 31).toInt()) {
                0 -> found.size.toLong()
                1 -> found.values.sum().toLong()
                2 -> checksum(found)
                else -> found[field - 6]?.toLong() ?: -1
            }
        }
        val n = (if (entry == "graphDistanceAt") input / 1024 else input).coerceIn(0, 512).toInt()
        val found = distances(graph(n.toLong()), -n)
        return when (entry) {
            "graphChecksum" -> checksum(found)
            "graphReachable" -> found.size.toLong()
            "graphDistanceTotal" -> found.values.sum().toLong()
            "graphDistanceAt" -> found[2 * (input and 1023).toInt() - n]?.toLong() ?: -1
            else -> error("Unknown graph entry")
        }
    }
    private fun rows(text: String): List<Row> {
        val rows = text.lineSequence().filter(String::isNotEmpty).map {
            val parts = it.split('\t'); require(parts.size == 3)
            Row(parts[0], parts[1].toLong(), parts[2].toLong())
        }.toList()
        require(rows.map { it.entry to it.input } == requests) { "Changed graph oracle input order/domain" }
        for (row in rows) require(row.result == expected(row.entry, row.input)) { "Native graph/model mismatch: $row" }
        return rows
    }
    private fun read(path: String) = Json.parse(File(root, path).readText()) as Map<String, Any?>
    private fun digest(path: String): String {
        val file = root.toPath().resolve(path).normalize()
        require(file.startsWith(root.toPath()) && !File(path).isAbsolute) { "Nonlocal graph evidence: $path" }
        val digest = MessageDigest.getInstance("SHA-256")
        file.toFile().inputStream().buffered().use { input ->
            val buffer = ByteArray(65536)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest()
            .joinToString("") { "%02x".format(it.toInt() and 255) }
    }
    private fun evidence(): Map<String, Any?> {
        val manifest = read("$directory/manifest.json")
        assertEquals(1L, manifest["schema"]); assertEquals("9.14.1", manifest["ghc"])
        assertEquals(entries, manifest["entries"]); assertEquals(requests.size.toLong(), manifest["nativeRows"])
        for (kind in listOf("inputHashes", "artifactHashes")) {
            val hashes = manifest[kind] as Map<String, String>; assertTrue(hashes.isNotEmpty())
            for ((path, expected) in hashes) assertEquals(expected, digest(path), "Stale graph $kind: $path")
        }
        val inputHashes = manifest["inputHashes"] as Map<String, String>
        val artifacts = manifest["artifactHashes"] as Map<String, String>
        for (path in listOf("examples/THC/GraphWorkload.hs", "examples/LibraryOracle.hs", "test/haskell-fixtures/GraphFixtures.hs",
            "test/haskell-fixtures/FixtureSupport.hs", "test/haskell-fixtures/Main.hs", "thc.cabal", "compiler/export.sh",
            "compiler/export-boot.py", "scripts/audit-core.py", "scripts/core-capabilities.json")) assertTrue(path in inputHashes)
        for (path in listOf("$directory/oracle.tsv", "$directory/inputs.tsv", "$directory/boot/boot-provenance.json"))
            assertTrue(path in artifacts)
        assertEquals("b1c1127ff57b6f844d0b30cea54a62c01ca146a49ed4953485be1af389a94bd8",
            artifacts["vendor/archives/containers-0.8.tar.gz"])
        val container = manifest["containers"] as Map<String, Any?>
        assertEquals(artifacts["vendor/archives/containers-0.8.tar.gz"], container["sha256"])
        assertEquals(emptyList<Any>(), container["sourcePatches"])
        assertEquals(false, container["sourceNotes"])
        val sources = container["sourceHashes"] as Map<String, String>
        val sourceRoot = container["root"] as String
        for (path in listOf("LICENSE", "src/Data/Sequence/Internal.hs", "src/Data/IntSet/Internal.hs", "src/Data/IntMap/Strict/Internal.hs"))
            assertTrue("$sourceRoot/$path" in sources, path)
        for ((path, hash) in sources) { assertTrue(path.startsWith("$sourceRoot/")); assertEquals(hash, artifacts[path]) }
        val boot = read("$directory/boot/boot-provenance.json")
        assertEquals(boot, manifest["bootProvenance"])
        assertEquals("ghc-9.14.1-release", boot["ghcTag"]); assertEquals(emptyList<Any>(), boot["sourcePatches"])
        for (source in boot["sources"] as List<Map<String, String>>)
            assertEquals(source["sha256"], inputHashes[source.getValue("path")])
        val original = manifest["originalInterfaces"] as Map<String, Any?>
        assertEquals(emptyList<Any>(), original["sourcePatches"])
        val originalModules = original["modules"] as List<String>
        assertEquals(listOf("GHC.Internal.Classes", "GHC.Internal.List"), originalModules.map { read(it)["module"] })
        for (path in originalModules) {
            assertTrue(path in artifacts)
            val module = read(path)
            assertEquals("ghc-internal", module["unit"])
            assertEquals("optimized-Core-after-Tidy-before-CorePrep", module["boundary"])
        }
        val interfaceProvenance = original["provenance"] as String
        assertTrue(interfaceProvenance in artifacts)
        val inventory = read(interfaceProvenance)["interfaces"] as List<Map<String, Any?>>
        for (source in original["sources"] as List<Map<String, String>>) {
            assertEquals(source["sha256"], artifacts[source.getValue("copy")])
            assertTrue(inventory.any { it["module"] == source["module"] && it["interface"] == source["interface"] && it["completeCore"] == true })
        }
        val stages = manifest["stages"] as List<Map<String, Any?>>
        assertEquals(listOf("post"), stages.map { it["stage"] })
        for (stage in stages) {
            val modules = stage["modules"] as List<String>
            assertEquals(modules.size, modules.toSet().size)
            assertTrue(modules.any { it.endsWith("/THC.GraphWorkload.json") })
            assertTrue(modules.any { it.endsWith("/Data.Sequence.Internal.json") })
            assertTrue(modules.any { it.endsWith("/Data.IntSet.Internal.json") })
            assertTrue(modules.any { it.endsWith("/Data.IntMap.Internal.json") })
            assertTrue(modules.containsAll(originalModules))
            modules.forEach { assertTrue(it in artifacts, "Unfingerprinted Core module $it") }
            val audits = stage["audits"] as Map<String, String>; assertEquals(entries.toSet(), audits.keys)
            for ((entry, path) in audits) {
                assertTrue(path in artifacts)
                val audit = read(path)
                assertEquals(listOf("main:THC.GraphWorkload.$entry"), audit["roots"])
                assertEquals(true, audit["accepted"], "Strict graph frontier retained at $path: ${audit["missingGlobals"]}; ${audit["issues"]}")
                assertEquals(emptyList<Any>(), audit["missingGlobals"]); assertEquals(emptyList<Any>(), audit["issues"])
            }
            assertEquals(true, stage["strictAccepted"])
        }
        assertEquals(true, manifest["strictAccepted"])
        return manifest
    }

    @Test fun smallGraphControlsHaveIndependentExactShortestDistances() {
        val expected = listOf(emptyMap(), mapOf(0 to 0), mapOf(0 to 0, 1 to 1, 2 to 2),
            mapOf(0 to 0, 1 to 1, 2 to 1, 3 to 2), mapOf(-3 to 0, 7 to 1), mapOf(-1 to 0, 2 to 1),
            mapOf(0 to 0, 1 to 1), emptyMap(), mapOf(0 to 0, 1 to 1, 2 to 2, 3 to 1), mapOf(2 to 0, 0 to 1, 1 to 2))
        for (index in expected.indices) {
            val (graph, start) = controls(index)
            assertEquals(expected[index], distances(graph, start), "control $index")
        }
        for (n in 0..512) assertEquals(n - n / 4, distances(graph(n.toLong()), -n).size)
    }
    @Test fun nativeRowsMatchModelAndRejectMissingReorderedOrAlteredRows() {
        evidence()
        val text = File(root, "$directory/oracle.tsv").readText()
        assertEquals(requests.size, rows(text).size)
        val lines = text.trimEnd().lines()
        for (bad in listOf(lines.drop(1), lines.reversed(), lines + lines.first(),
            listOf(lines.first().substringBeforeLast('\t') + "\t99999999") + lines.drop(1)))
            assertThrows(IllegalArgumentException::class.java) { rows(bad.joinToString("\n")) }
    }
    private fun context(compiled: Boolean, inlining: Boolean) = Context.newBuilder("thc").allowExperimentalOptions(true)
        .option("engine.Compilation", compiled.toString()).option("compiler.Inlining", inlining.toString())
        .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false")
        .option("engine.CompilationFailureAction", "Throw").option("engine.SingleTierCompilationThreshold", "10000000").build()
    private fun diagnostics(function: Value) = Json.parse(function.getMember("diagnostics").asString()) as Map<String, Any?>
    private fun compiledEntries(function: Value) = (diagnostics(function).getValue("compiledEntries") as Number).toLong()
    private fun clean(function: Value, backend: String) {
        val state = diagnostics(function)
        assertEquals(backend, state["backend"]); assertEquals("reject-at-load", state["unsupportedPolicy"])
        assertEquals(emptyList<Any>(), state["deferredUnsupported"])
        for (counter in listOf("unsupportedTraps", "blackholes")) assertEquals(0L, (state[counter] as Number).toLong())
        val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
        val pools = language.handoffState.get()
        assertEquals(0, pools.arguments.depth); assertEquals(0, pools.results.depth)
        assertEquals(0, pools.arguments.retainedReferences()); assertEquals(0, pools.results.retainedReferences())
    }
    @Test fun strictOriginalClosureExecutesAllNativeRowsOnBothBackends() = execute(false, false)
    @Test fun firstCompiledGraphCallsWithoutInliningMatchNative() = execute(true, false)
    @Test fun firstCompiledGraphCallsWithInliningMatchNative() = execute(true, true)
    private fun execute(compiled: Boolean, inlining: Boolean) {
        val manifest = evidence()
        val rows = rows(File(root, "$directory/oracle.tsv").readText()).groupBy { it.entry }
        for (stage in manifest["stages"] as List<Map<String, Any?>>) for (backend in listOf("ast", "bytecode"))
            for (entry in if (compiled) listOf("graphChecksum", "graphControl") else entries) context(compiled, inlining).use { context ->
                val modules = (stage["modules"] as List<String>).map { File(root, it).path }
                val source = Source.newBuilder("thc", CoreModules.request(modules, entry,
                    instrument = true, diagnosticUnsupported = false, backend = backend), "graph:${stage["stage"]}:$entry")
                    .cached(false).buildLiteral()
                context.enter()
                try {
                    val function = context.eval(source)
                    val cases = rows.getValue(entry)
                    for (row in cases) assertEquals(row.result, function.execute(row.input).asLong(), "$backend/$row")
                    clean(function, backend)
                    if (compiled) {
                        assertTrue(function.invokeMember("compile").asBoolean())
                        // No post-install settling calls or retries. Recursive
                        // graph/library calls have data-dependent entry counts;
                        // this proves installed guest activity, not an exact +1.
                        for (row in cases.asReversed()) {
                            val before = compiledEntries(function)
                            assertEquals(row.result, function.execute(row.input).asLong(), "compiled $backend/$row")
                            assertTrue(compiledEntries(function) > before, "First installed graph entry: $backend/$row")
                            clean(function, backend)
                        }
                    } else assertEquals(0L, compiledEntries(function))
                } finally { context.leave() }
            }
    }
}
