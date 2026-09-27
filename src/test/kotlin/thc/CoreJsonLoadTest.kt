// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

@file:Suppress("UNCHECKED_CAST")
package thc

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import org.graalvm.polyglot.Context
import org.graalvm.polyglot.Engine
import org.graalvm.polyglot.Source
import org.graalvm.polyglot.Value
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** Native-produced sidecar fixture: no Kotlin reference encoder or fallback in this route. */
class CoreJsonLoadTest {
    @TempDir lateinit var directory: Path
    private fun fixture(name: String): Path {
        val path = directory.resolve(name)
        javaClass.getResourceAsStream("/core/$name")!!.use { Files.copy(it, path) }
        return path
    }
    private fun request(json: Path, index: Path, backend: String, async: Boolean = false): String =
        CoreModules.request(listOf(json.toString()), "synthetic:LazyJson.entry", backend = backend,
            sourceNotesEnabled = false, asyncExceptions = async,
            jsonSidecars = mapOf(json.toString() to index.toString()))
    private fun statistics(value: Value) = Json.parse(value.getMember("diagnostics").asString()) as Map<String, Any?>
    private fun count(value: Value, key: String) = (statistics(value).getValue(key) as Number).toLong()
    private fun digest(path: Path) = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))
        .joinToString("") { "%02x".format(it) }

    @Test fun nativeSidecarLoadsEntryThenPreparesEachUntouchedCalleeOnce() {
        val json = fixture("lazy-json-module.json")
        val index = fixture("lazy-json-module.idx")
        assertEquals("0d9dacecb7b3e8b617e01f7eca52a5017aba4a922d994c79f89416229841b1b6", digest(json))
        assertEquals("942c653564c3f767c0877d80befc725940c1d437cc4a8a24af91bd4867cbb158", digest(index))
        for (backend in listOf("ast", "bytecode")) for (async in listOf(false, true)) executionContext().use { context ->
            val serialized = request(json, index, backend, async)
            assertFalse(serialized.contains("unused body is deliberately"), "the host request must not embed Core bodies")
            val value = context.eval("thc", serialized)
            assertEquals(1L, count(value, "jsonBodyMaterializations"))
            assertEquals(1L, count(value, "loweredRootCount"))
            assertEquals(1L, count(value, "hostEntryRootCount"))
            assertEquals(1L, count(value, "initializedBindingCount"))
            assertEquals(4L, count(value, "jsonBindingHeaders"), "all binding IDs are still indexed eagerly")
            assertEquals(Files.size(json), count(value, "jsonSourceBytes"))
            assertEquals(Files.size(index), count(value, "jsonSidecarBytes"))
            assertEquals(Files.size(json), count(value, "jsonSourceFileBytesRead"))
            assertTrue(count(value, "jsonSourceHashBytesScanned") >= Files.size(json))
            assertTrue(count(value, "jsonLinkingExpressionViews") > 0, "do not hide strict dependency traversal")
            assertTrue(count(value, "jsonLinkingScalarDecodes") > 0)
            assertTrue(count(value, "jsonDecodedSpanCount") > 0, "header/link scalars really are decoded before entry")
            println("indexed pre-entry $backend/$async ${Json.stringify(statistics(value))}")
            assertEquals(1L, value.execute(0L).asLong())
            assertEquals(2L, count(value, "jsonBodyMaterializations"))
            assertEquals(2L, count(value, "loweredRootCount"))
            assertEquals(2L, count(value, "initializedBindingCount"))
            println("indexed first-callee $backend/$async ${Json.stringify(statistics(value))}")
            val decoded = count(value, "jsonDecodedSpanCount")
            assertEquals(1L, value.execute(0L).asLong())
            assertEquals(decoded, count(value, "jsonDecodedSpanCount"))
            assertEquals(7L, value.execute(5L).asLong())
            assertEquals(3L, count(value, "jsonBodyMaterializations"))
            assertEquals(3L, count(value, "loweredRootCount"))
        }
    }

    @Test fun sourceSnapshotSurvivesReplacementButNewAdmissionChecksBothFiles() {
        val json = fixture("lazy-json-module.json")
        val index = fixture("lazy-json-module.idx")
        val serialized = request(json, index, "bytecode")
        executionContext().use { context ->
            val value = context.eval("thc", serialized)
            Files.writeString(json, "{}")
            assertEquals(7L, value.execute(5L).asLong(), "cold callee uses pinned bytes, not the replaced pathname")
            assertThrows(IllegalArgumentException::class.java) {
                CoreModules.visitRequestModules(Json.parse(serialized) as Map<String, Any?>) { }
            }
            Files.write(index, byteArrayOf(0))
            val error = assertThrows(IllegalArgumentException::class.java) {
                CoreModules.visitRequestModules(Json.parse(serialized) as Map<String, Any?>) { }
            }
            assertTrue(error.message.orEmpty().contains("sidecar changed"))
            assertEquals(1L, value.execute(0L).asLong())
        }
    }

    @Test fun aGuestCannotForgeTheHostFilesystemCapabilityOrMixProtocols() {
        val json = fixture("lazy-json-module.json")
        val index = fixture("lazy-json-module.idx")
        val input = Json.parse(request(json, index, "ast")) as Map<String, Any?>
        val descriptor = (input["indexedModuleFiles"] as List<Map<String, Any?>>).single()
        for (altered in listOf(descriptor + ("capability" to "forged"),
                descriptor + ("path" to directory.resolve("not-authorized.json").toString()),
                descriptor + ("sidecar" to directory.resolve("not-authorized.idx").toString()))) {
            val error = assertThrows(IllegalArgumentException::class.java) {
                CoreModules.visitRequestModules(input + ("indexedModuleFiles" to listOf(altered))) { }
            }
            assertTrue(error.message.orEmpty().contains("capability"), error.message)
        }
        assertThrows(IllegalArgumentException::class.java) {
            CoreModules.visitRequestModules(input + ("modules" to emptyList<Any>())) { }
        }
    }

    @Test fun sharedEngineOwnsSourceWhileProgramsAndClosingContextsRemainSeparate() {
        val json = fixture("lazy-json-module.json")
        val index = fixture("lazy-json-module.idx")
        for (backend in listOf("ast", "bytecode")) Engine.newBuilder().allowExperimentalOptions(true)
            .option("engine.BackgroundCompilation", "false").build().use { engine ->
                val shared = Source.newBuilder("thc", request(json, index, backend), "indexed-$backend").cached(true).build()
                Context.newBuilder("thc").engine(engine).build().use { second ->
                    lateinit var other: Value
                    Context.newBuilder("thc").engine(engine).build().use { first ->
                        val value = first.eval(shared)
                        other = second.eval(shared)
                        assertEquals(1L, count(value, "loweredRootCount"))
                        assertEquals(1L, count(other, "loweredRootCount"))
                        assertEquals(1L, value.execute(0L).asLong())
                        assertEquals(2L, count(value, "loweredRootCount"))
                        assertEquals(1L, count(other, "loweredRootCount"), "source reuse must not share executable roots")
                    }
                    assertEquals(7L, other.execute(5L).asLong(), "closing one Context must not close an Engine-owned source")
                    assertEquals(2L, count(other, "loweredRootCount"))
                }
            }
    }
}
