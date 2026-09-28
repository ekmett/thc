// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

@file:Suppress("UNCHECKED_CAST")
package thc

import thc.Main.loadEntry
import thc.Main.executionContext
import thc.Main.defaultBackend

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** Synthetic Core with sidecars from the native producer, never a reference fallback. */
class CoreJsonConsumersTest {
    @TempDir lateinit var directory: Path
    private fun resource(name: String) = javaClass.getResourceAsStream("/core/$name")!!.use { it.readBytes() }
    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }
    private fun file(name: String) = directory.resolve(name).also { Files.write(it, resource(name)) }
    private fun support(indexed: Boolean = true): Path {
        val json = file("lazy-json-package.json")
        val index = file("lazy-json-package.idx")
        val module = mapOf("name" to "LazyJson", "path" to json.fileName.toString(),
            "boundary" to "optimized-Core-after-Tidy-before-CorePrep", "sha256" to digest(Files.readAllBytes(json))) +
            if (indexed) mapOf("index" to mapOf("path" to index.fileName.toString(),
                "sha256" to digest(Files.readAllBytes(index)))) else emptyMap()
        return directory.resolve("packages.json").also { Files.writeString(it, Json.stringify(mapOf(
            "format" to "thc-core-packages", "schema" to 1, "ghc" to "9.14.1",
            "units" to listOf(mapOf("id" to "synthetic", "depends" to emptyList<String>(), "modules" to listOf(module)))))) }
    }
    private fun consumers(): LinkedHashMap<String, String> = linkedMapOf(
        file("indexed-consumer.json").toString() to file("indexed-consumer.idx").toString(),
        file("indexed-interface-closure.json").toString() to file("indexed-interface-closure.idx").toString())
    private fun request(paths: List<String>, pairs: Map<String, String>, verifyArtifacts: Boolean = false) = CoreModules.request(paths, "main:Main.entry", true, false, thc.Main.defaultBackend(), false, false, null, null, pairs, verifyArtifacts)
    private fun document(request: String) = Json.parse(request) as Map<String, Any?>
    private fun modules(request: String): List<Map<String, Any?>> = ArrayList<Map<String, Any?>>().also { result ->
        CoreModules.visitRequestModules(document(request), result::add)
    }

    @Test fun nativePairsMixWithLegacyOrIndexedSupportAndKeepInterfaceOwnershipAndOrder() {
        val pairs = consumers()
        assertEquals("d2a141b1f35127040ee3358a3332444c4f92b866de3a03854e085b6b979e4b7a",
            digest(resource("indexed-consumer.json")))
        assertEquals("a5171ad8533443aa5844e8099faf399f1803ba25fba18a5a2ed0f5db6f2ffb50",
            digest(resource("indexed-consumer.idx")))
        assertEquals("c7fb8e4a188e3c62d505225f4769a96d7dac4375882d0d23c9e26ee6cc8dec78",
            digest(resource("indexed-interface-closure.json")))
        assertEquals("5ea63e376321c6532d7cf10289459fdfe13c4764859dea07beed42f02bc9afb5",
            digest(resource("indexed-interface-closure.idx")))
        for (indexed in listOf(false, true)) {
            val manifest = support(indexed)
            for (order in listOf(pairs.keys.toList(), pairs.keys.reversed())) {
                val paths = listOf(order[0], "@$manifest", order[1])
                val serialized = request(paths, pairs)
                val input = document(serialized)
                assertFalse(input.containsKey("consumerModules"))
                assertFalse(input.containsKey("modules"))
                val selected = modules(serialized)
                val expected = listOf("synthetic") + order.map {
                    if (it.endsWith("indexed-consumer.json")) "main" else "dependency-closure"
                }
                assertEquals(expected, selected.map { it["unit"] })
                val closure = selected.single { it["unit"] == "dependency-closure" }
                assertEquals("actual-interface-unfoldings", closure["boundary"])
                assertEquals("dependency:Hidden.cold", (closure["bindings"] as List<Map<String, Any?>>).single()["id"])
                assertEquals(listOf("synthetic:LazyJson"), closure["providedModules"])
                CoreModules.merge(selected) // exact provided owner and original binding IDs remain admissible
                val legacy = modules(CoreModules.request(paths, "main:Main.entry", true, false, thc.Main.defaultBackend(), false))
                assertEquals(expected, legacy.map { it["unit"] }, "legacy relative consumer order")
                for (backend in listOf("ast", "bytecode")) for (async in listOf(false, true)) {
                    executionContext().use { context ->
                        val value = loadEntry(context, paths, "main:Main.entry", true, backend, false, null, async, pairs)
                        fun count(key: String) = ((Json.parse(value.getMember("diagnostics").asString()) as Map<*, *>)[key] as Number).toLong()
                        assertEquals(1L, count("jsonBodyMaterializations"))
                        assertEquals(1L, count("loweredRootCount"))
                        assertEquals(10L, value.execute(7L).asLong(), "$indexed/$order/$backend/$async")
                        assertEquals(2L, count("jsonBodyMaterializations"))
                        assertEquals(2L, count("loweredRootCount"))
                    }
                }
            }
        }
    }

    @Test fun requestRequiresExactLooseInputCoverageAndRejectsDuplicateProtocols() {
        val pairs = consumers()
        val manifest = support()
        val paths = pairs.keys.toList() + "@$manifest"
        for (invalid in listOf(pairs - pairs.keys.first(), pairs + ("unlisted.json" to "unlisted.idx"),
                pairs + ("@$manifest" to pairs.values.first()))) {
            assertThrows(IllegalArgumentException::class.java) { request(paths, invalid) }
        }
        assertThrows(IllegalArgumentException::class.java) { request(paths + "@$manifest", pairs) }
        assertThrows(IllegalArgumentException::class.java) { request(paths + paths.first(), pairs) }
        val alias = directory.resolve(".").resolve("indexed-consumer.json").toString()
        assertThrows(IllegalArgumentException::class.java) { request(paths + alias, pairs + (alias to pairs.values.first())) }
        val input = document(request(paths, pairs))
        for (field in listOf("modules", "consumerModules", "targetLayout")) {
            assertThrows(IllegalArgumentException::class.java) {
                CoreModules.visitRequestModules(input + (field to emptyList<Any>())) { }
            }
        }
        val files = input["indexedModuleFiles"] as List<*>
        assertThrows(IllegalArgumentException::class.java) {
            CoreModules.visitRequestModules(input + ("indexedModuleFiles" to files + files.first())) { }
        }
    }

    @Test fun mixedReplayChecksBothCapabilitiesManifestAndPairIdentityAndPinsAdmittedBytes() {
        val pairs = consumers()
        val manifest = support()
        val paths = pairs.keys.toList() + "@$manifest"
        val serialized = request(paths, pairs, verifyArtifacts = true)
        val input = document(serialized)
        for (changed in listOf(input + ("packageCapability" to "forged"),
                input + ("foreignExceptionBridgeUnit" to "forged"))) {
            assertThrows(IllegalArgumentException::class.java) { CoreModules.visitRequestModules(changed) { } }
        }
        val wrongPairs = pairs.toMutableMap().also { it[it.keys.first()] = it.values.last() }
        assertThrows(IllegalArgumentException::class.java) { modules(request(paths, wrongPairs)) }
        executionContext().use { context ->
            val value = context.eval("thc", serialized)
            val closure = Path.of(pairs.keys.last())
            Files.writeString(closure, "{}")
            assertEquals(10L, value.execute(7L).asLong(), "cold helper uses the admitted source snapshot")
            assertThrows(IllegalArgumentException::class.java) { modules(serialized) }
            consumers()
            Files.write(Path.of(pairs.values.last()), byteArrayOf(0))
            assertThrows(IllegalArgumentException::class.java) { modules(serialized) }
            consumers()
            Files.writeString(manifest, Files.readString(manifest) + " ")
            assertThrows(IllegalArgumentException::class.java) { modules(serialized) }
        }
    }

    @Test fun consumersRemainSubjectToDuplicateDefinitionAndExactProvidedOwnerChecks() {
        val pairs = consumers()
        val selected = modules(request(pairs.keys.toList() + "@${support()}", pairs))
        val closure = selected.last()
        assertThrows(IllegalArgumentException::class.java) { CoreModules.merge(selected + closure) }
        assertThrows(IllegalArgumentException::class.java) { CoreModules.merge(selected.dropLast(1) +
            (closure + ("providedModules" to listOf("forged:LazyJson")))) }
        assertThrows(IllegalArgumentException::class.java) { CoreModules.merge(selected.dropLast(1) +
            (closure + ("unit" to "forged"))) }
    }
}
