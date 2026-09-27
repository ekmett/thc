// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import org.graalvm.polyglot.Engine
import org.graalvm.polyglot.Context
import org.graalvm.polyglot.Source
import org.graalvm.polyglot.Value
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class CoreUnitLoadTest {
    @TempDir lateinit var directory: Path
    private val boundary = "optimized-Core-after-Tidy-before-CorePrep"
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun literal(value: Int) = listOf("lit", "int", value.toString())
    private fun call(id: String, argument: Any?) = listOf("app", listOf("var", id), listOf(argument), listOf(false))
    private fun choice(zero: Any?, nonzero: Any?) = listOf("case", listOf("var", "x"), "scrutinee",
        listOf(listOf("lit", listOf("int", "0"), emptyList<Any>(), zero), listOf("default", null, emptyList<Any>(), nonzero)))
    private fun binding(id: String, body: Any?) = mapOf("id" to id, "name" to id.substringAfterLast('.'),
        "type" to "Int# -> Int#", "lifted" to true, "arity" to 1,
        "expr" to listOf("lam", listOf(mapOf("id" to "x", "name" to "x", "type" to "Int#", "lifted" to false, "coercion" to false)), body))

    /** Model writer for focused runtime tests, not the production publisher. */
    private fun unit(name: String, body: Any?, padding: String = "", diagnostics: Boolean = false): Map<String, Any?> {
        val id = "u$name:$name.entry"
        val ignored = if (diagnostics) "\"sourceCore\":${Json.stringify("x".repeat(1024 * 1024))}," else ""
        val prefix = "{${ignored}\"schema\":1,\"ghc\":\"9.14.1\",\"unit\":\"u$name\",\"module\":\"$name\",\"boundary\":\"$boundary\",\"bindings\":"
        val expr = Json.stringify(listOf(binding(id, body), binding("u$name:$name.unused", listOf("unsupported", padding))))
        val notes = mapOf("sourceFiles" to listOf(mapOf("id" to "source", "content" to "original source")))
        val suffix = ",\"constructors\":[]" + if (diagnostics)
            ",\"groups\":${Json.stringify(List(10000) { id })},\"sourceFiles\":${Json.stringify(notes["sourceFiles"])}}" else "}"
        val original = (prefix + expr + suffix).toByteArray()
        val metadata = Json.stringify(mapOf("schema" to 1, "ghc" to "9.14.1", "unit" to "u$name",
            "module" to name, "boundary" to boundary, "constructors" to emptyList<Any>())).toByteArray()
        val source = if (diagnostics) Json.stringify(notes).toByteArray() else byteArrayOf()
        val bytes = original + byteArrayOf(10) + metadata + byteArrayOf(10) + source
        val json = directory.resolve("$name.jsons")
        val symbols = directory.resolve("$name.symbols")
        Files.write(json, bytes)
        val first = prefix.toByteArray().size + 1
        val unused = prefix.toByteArray().size + 1 + Json.stringify(binding(id, body)).toByteArray().size + 1
        val rows = "$id $first\nu$name:$name.unused $unused\n".toByteArray()
        Files.write(symbols, rows)
        return mapOf("id" to "u$name", "depends" to emptyList<String>(),
            "json" to mapOf("path" to json.toString(), "sha256" to hash(bytes)),
            "symbols" to mapOf("path" to symbols.toString(), "sha256" to hash(rows)),
            "modules" to listOf(mapOf("name" to name, "path" to "$name.json", "sha256" to hash(original),
                "boundary" to boundary, "start" to 0, "end" to original.size,
                "bindingsStart" to prefix.toByteArray().size,
                "bindingsEnd" to prefix.toByteArray().size + expr.toByteArray().size,
                "metadataStart" to original.size + 1, "metadataEnd" to original.size + 1 + metadata.size,
                "containsDelimitedControl" to false, "registrationObligations" to false, "mainAlias" to false,
                "packageScalarDeclarations" to false) + if (diagnostics) mapOf(
                    "sourceMetadataStart" to original.size + metadata.size + 2,
                    "sourceMetadataEnd" to bytes.size) else emptyMap()))
    }
    private fun fixture(badB: Boolean = false, cycle: Boolean = false): Path {
        val a = unit("A", choice(literal(7), call("uB:B.entry", listOf("var", "x"))))
        val b = unit("B", if (badB) listOf("unsupported", "demanded bad B")
            else if (cycle) call("uA:A.entry", literal(0))
            else choice(call("uC:C.entry", literal(0)), listOf("app", listOf("prim", "+#"),
                listOf(listOf("var", "x"), literal(1)), listOf(false, false))))
        val c = unit("C", listOf("unsupported", "untouched C"), "x".repeat(1024 * 1024))
        val manifest = directory.resolve("packages.json")
        Files.writeString(manifest, Json.stringify(mapOf("format" to "thc-core-packages", "schema" to 1,
            "ghc" to "9.14.1", "units" to listOf(a, b, c))))
        return manifest
    }
    private fun request(path: Path, backend: String, async: Boolean = false, verify: Boolean = false) =
        CoreModules.request(listOf("@$path"), "uA:A.entry", backend = backend, sourceNotesEnabled = false,
            asyncExceptions = async, verifyArtifacts = verify)
    private fun count(value: Value, field: String) = ((Json.parse(value.getMember("diagnostics").asString()) as Map<*, *>)[field] as Number).toLong()

    @Test fun explicitLooseConsumersKeepPairedDependenciesCold() {
        val manifest = fixture()
        val plain = directory.resolve("Main.json")
        Files.writeString(plain, Json.stringify(mapOf("schema" to 1, "ghc" to "9.14.1", "unit" to "main",
            "module" to "Main", "boundary" to boundary, "constructors" to emptyList<Any>(),
            "bindings" to listOf(binding("main:Main.entry", choice(literal(41), call("uB:B.entry", listOf("var", "x"))))))))
        val nativeJson = directory.resolve("native.json")
        val nativeIndex = directory.resolve("native.idx")
        javaClass.getResourceAsStream("/core/lazy-json-module.json")!!.use { Files.copy(it, nativeJson) }
        javaClass.getResourceAsStream("/core/lazy-json-module.idx")!!.use { Files.copy(it, nativeIndex) }
        for (backend in listOf("ast", "bytecode")) for (async in listOf(false, true)) {
            for (order in listOf(listOf(plain.toString(), "@$manifest"), listOf("@$manifest", plain.toString()))) {
                executionContext().use { context ->
                    val entry = context.eval("thc", CoreModules.request(order, "main:Main.entry", backend = backend,
                        asyncExceptions = async, sourceNotesEnabled = false))
                    assertEquals(0L, count(entry, "coreUnitSourceOpens"))
                    assertEquals(41L, entry.execute(0).asLong())
                    assertEquals(0L, count(entry, "coreUnitSourceOpens"))
                    assertEquals(2L, entry.execute(1).asLong())
                    assertEquals(1L, count(entry, "coreUnitDecodedBindings"))
                    val reads = count(entry, "coreUnitSourceByteReads")
                    assertEquals(3L, entry.execute(2).asLong())
                    assertEquals(reads, count(entry, "coreUnitSourceByteReads"))
                }
            }
            executionContext().use { context ->
                // This is the actual native-produced sidecar used by the loose
                // loader controls, not a runtime reference-index fallback.
                val entry = context.eval("thc", CoreModules.request(listOf(nativeJson.toString(), "@$manifest"),
                    "synthetic:LazyJson.entry", backend = backend, asyncExceptions = async, sourceNotesEnabled = false,
                    jsonSidecars = mapOf(nativeJson.toString() to nativeIndex.toString())))
                assertEquals(0L, count(entry, "coreUnitSourceOpens"))
                assertEquals(1L, count(entry, "jsonBodyMaterializations"))
                assertEquals(7L, entry.execute(5).asLong())
                assertEquals(2L, count(entry, "jsonBodyMaterializations"))
                assertEquals(1L, entry.execute(0).asLong())
                assertEquals(3L, count(entry, "jsonBodyMaterializations"))
                assertEquals(0L, count(entry, "coreUnitSourceOpens"))
            }
        }
    }

    @Test fun looseModuleCollisionsFailWithoutOpeningPackageSources() {
        val manifest = fixture()
        val loose = directory.resolve("duplicate.json")
        Files.writeString(loose, Json.stringify(mapOf("schema" to 1, "ghc" to "9.14.1", "unit" to "uA",
            "module" to "A", "boundary" to boundary, "bindings" to listOf(binding("uA:A.entry", literal(99))))))
        Files.delete(directory.resolve("A.jsons"))
        for (backend in listOf("ast", "bytecode")) executionContext().use { context ->
            val failure = assertThrows(org.graalvm.polyglot.PolyglotException::class.java) {
                context.eval("thc", CoreModules.request(listOf(loose.toString(), "@$manifest"), "uA:A.entry", backend = backend))
            }
            assertTrue(failure.message.orEmpty().contains("Duplicate GHC module"), failure.message)
            context.enter()
            try { assertTrue(Language.currentState().coreUnitPrograms.isEmpty()) } finally { context.leave() }
        }
    }

    @Test fun foreignHeadsUseExactSymbolMembershipWithoutDecodingShadowBodies() {
        val state = mapOf("kind" to "void", "primReps" to emptyList<String>(), "evaluated" to true)
        val address = mapOf("kind" to "address", "primReps" to listOf("AddrRep"), "evaluated" to true)
        val closure = mapOf("kind" to "closure", "primReps" to listOf("BoxedRep (Just Lifted)"), "evaluated" to true)
        val result = mapOf("kind" to "unknown", "primReps" to emptyList<String>(), "evaluated" to true,
            "aggregate" to "unboxed-tuple", "components" to listOf(state))
        val descriptor = mapOf("schema" to 1, "target" to mapOf("kind" to "static", "symbol" to "getProgArgv",
            "unit" to "ghc-internal", "isFunction" to true), "convention" to "ccall", "safety" to "unsafe",
            "arity" to 3, "suppliedArity" to 3, "argumentReps" to listOf(address, address, state).map { it + ("evaluated" to false) },
            "resultRep" to (result + ("evaluated" to false)))
        val nil = listOf("lit", "null-addr", "0", mapOf("rep" to address))
        for (affinity in listOf(false, true)) for (shadow in listOf(false, true)) {
            // Both heads have a known module owner. Only 'unused' has a row;
            // its unsupported body must not be parsed just to reject shadowing.
            val head = if (shadow) "uB:B.unused" else "uB:B.originalFCall"
            val selectedResult = if (affinity) result + mapOf("primReps" to listOf("Int32Rep"), "components" to listOf(
                state, mapOf("kind" to "long", "primReps" to listOf("Int32Rep"), "evaluated" to true))) else result
            val selectedDescriptor = if (affinity) descriptor + mapOf("target" to mapOf("kind" to "static",
                "symbol" to "thc_cpu_affinity_v1_support", "unit" to "ghc-internal", "isFunction" to true),
                "arity" to 1, "suppliedArity" to 1, "argumentReps" to listOf(state + ("evaluated" to false)),
                "resultRep" to (selectedResult + ("evaluated" to false))) else descriptor
            val args = (if (affinity) emptyList() else listOf(nil, nil)) + listOf(listOf("void", mapOf("rep" to state)))
            val foreign = listOf("app", listOf("var", head, mapOf("rep" to closure)),
                args, List(args.size) { false }, false, false,
                mapOf("rep" to selectedResult, "foreignCall" to selectedDescriptor))
            val body = listOf("case", foreign, "state-tuple", listOf(listOf("default", null, emptyList<String>(), literal(7))),
                mapOf("rep" to mapOf("kind" to "long", "primReps" to listOf("IntRep"), "evaluated" to true),
                    "binder" to mapOf("id" to "state-tuple", "name" to "state-tuple", "lifted" to false, "rep" to selectedResult)))
            val units = listOf(unit("A", body), unit("B", literal(0)))
            val manifest = directory.resolve("foreign-$shadow.json")
            Files.writeString(manifest, Json.stringify(mapOf("format" to "thc-core-packages", "schema" to 1,
                "ghc" to "9.14.1", "units" to units)))
            // A membership probe needs only B's symbol directory.
            Files.delete(directory.resolve("B.jsons"))
            for (backend in listOf("ast", "bytecode")) executionContext().use { context ->
                if (shadow) {
                    val failure = assertThrows(org.graalvm.polyglot.PolyglotException::class.java) {
                        context.eval("thc", request(manifest, backend))
                    }
                    assertTrue(failure.message.orEmpty().contains("unresolved"), failure.message)
                } else {
                    val entry = context.eval("thc", request(manifest, backend))
                    assertEquals(1L, count(entry, "coreUnitSourceOpens"))
                    assertEquals(2L, count(entry, "coreUnitDirectoryOpens"))
                    assertEquals(1L, count(entry, "coreUnitDecodedBindings"))
                    if (affinity) assertEquals(7L, entry.execute(0).asLong())
                }
            }
        }
    }

    @Test fun projectedMetadataSkipsOriginalPrettyCoreGroupsAndDisabledSourceTables() {
        val unit = unit("A", literal(7), diagnostics = true)
        val document = Json.parse(Json.stringify(mapOf("format" to "thc-core-packages", "schema" to 1,
            "ghc" to "9.14.1", "units" to listOf(unit)))) as Map<*, *>
        val index = CoreUnitDirectory.read(document)!!
        for (notes in listOf(false, true)) index.open(false, notes).use { sources ->
            val metadata = sources.metadata(index.modules.single())
            assertFalse(metadata.containsKey("sourceCore"))
            assertFalse(metadata.containsKey("groups"))
            assertEquals(notes, metadata.containsKey("sourceFiles"))
            assertEquals(0L, sources.counters().single().statistics().decodedBindings)
            assertTrue(sources.counters().single().statistics().metadataBytes < 1024)
            assertTrue(sources.counters().single().statistics().sourceByteReads < 1024)
        }
        index.open(true, false).use { sources ->
            sources.verifyModule(index.modules.single())
            assertTrue(sources.counters().single().statistics().verifiedModuleBytes > 1024 * 1024)
            val reads = sources.counters().single().statistics().sourceByteReads
            sources.verifyModule(index.modules.single())
            assertEquals(reads, sources.counters().single().statistics().sourceByteReads)
        }
    }

    @Test fun modulelessLegacyUnitsKeepIdentityWithoutOpeningTheirZip() {
        val manifest = fixture()
        @Suppress("UNCHECKED_CAST")
        val original = Json.parse(Files.readString(manifest)) as Map<String, Any?>
        val empty = mapOf("id" to "reexports-only", "depends" to listOf("uA"),
            "bundle" to mapOf("path" to directory.resolve("not-present.zip").toString(), "sha256" to "a".repeat(64)),
            "modules" to emptyList<Any>())
        val noBundle = mapOf("id" to "empty-dependency", "depends" to listOf("reexports-only"), "modules" to emptyList<Any>())
        val updated = original + ("units" to (listOf(empty, noBundle) + (original["units"] as List<*>)))
        Files.writeString(manifest, Json.stringify(updated))
        val index = CoreUnitDirectory.read(Json.parse(Json.stringify(updated)) as Map<*, *>)!!
        assertEquals(listOf("uA"), index.units.first().depends)
        assertNotNull(index.units.first().legacyBundle)
        assertEquals("empty-dependency", index.units[1].id)
        assertNull(index.units[1].json)
        for (backend in listOf("ast", "bytecode")) executionContext().use { context ->
            val entry = context.eval("thc", request(manifest, backend))
            assertEquals(7L, entry.execute(0).asLong())
            assertEquals(1L, count(entry, "coreUnitSourceOpens"))
        }
        val malformed = updated + ("units" to (listOf(empty + ("json" to empty["bundle"])) +
            (original["units"] as List<*>)))
        assertThrows(IllegalArgumentException::class.java) {
            CoreUnitDirectory.read(Json.parse(Json.stringify(malformed)) as Map<*, *>)
        }
    }

    @Test fun coldReferencesDoNotOpenOtherUnitsAndFirstDemandReusesTheBinding() {
        val manifest = fixture()
        for (backend in listOf("ast", "bytecode")) for (async in listOf(false, true)) executionContext().use { context ->
            val entry = context.eval("thc", request(manifest, backend, async))
            assertEquals(1L, count(entry, "coreUnitSourceOpens"))
            assertEquals(1L, count(entry, "coreUnitDecodedModules"))
            assertEquals(1L, count(entry, "coreUnitDecodedBindings"))
            assertEquals(1L, count(entry, "loweredRootCount"))
            assertEquals(7L, entry.execute(0).asLong())
            assertEquals(1L, count(entry, "coreUnitSourceOpens"))
            assertEquals(2L, entry.execute(1).asLong())
            assertEquals(2L, count(entry, "coreUnitSourceOpens"))
            assertEquals(2L, count(entry, "coreUnitDecodedBindings"))
            val reads = count(entry, "coreUnitSourceByteReads")
            assertEquals(3L, entry.execute(2).asLong())
            assertEquals(reads, count(entry, "coreUnitSourceByteReads"))
            assertEquals(0L, count(entry, "coreUnitHashBytesScanned"))
        }
    }
    @Test fun missingOrBadColdUnitFailsOnlyAtDemandAndDoesNotTouchThirdUnit() {
        val manifest = fixture(badB = true)
        Files.delete(directory.resolve("C.jsons"))
        Files.delete(directory.resolve("C.symbols"))
        for (backend in listOf("ast", "bytecode")) executionContext().use { context ->
            val entry = context.eval("thc", request(manifest, backend))
            assertEquals(7L, entry.execute(0).asLong())
            val failure = assertThrows(org.graalvm.polyglot.PolyglotException::class.java) { entry.execute(1) }
            assertTrue(failure.message.orEmpty().contains("unsupported", ignoreCase = true))
            val decoded = count(entry, "coreUnitDecodedBindings")
            assertThrows(org.graalvm.polyglot.PolyglotException::class.java) { entry.execute(1) }
            assertEquals(decoded, count(entry, "coreUnitDecodedBindings"))
            assertEquals(2L, count(entry, "coreUnitSourceOpens"))
        }
    }
    @Test fun mutualFunctionReferencesPrepareWithoutRecursingThroughColdBodies() {
        val manifest = fixture(cycle = true)
        for (backend in listOf("ast", "bytecode")) executionContext().use { context ->
            val entry = context.eval("thc", request(manifest, backend))
            assertEquals(7L, entry.execute(1).asLong())
            assertEquals(2L, count(entry, "coreUnitDecodedBindings"))
        }
    }
    @Test fun sharedMappingsKeepDecodedContextStateSeparateAndSurviveClosingEitherContext() {
        val manifest = fixture()
        Engine.create().use { engine ->
            val source = Source.newBuilder("thc", request(manifest, "bytecode"), "unit-model").cached(true).build()
            val first = Context.newBuilder("thc").engine(engine).build()
            val second = Context.newBuilder("thc").engine(engine).build()
            try {
                val a = first.eval(source)
                val b = second.eval(source)
                assertEquals(2L, count(a, "coreUnitPhysicalMappingOpens"))
                assertEquals(0L, count(a, "coreUnitMappingCacheHits"))
                assertEquals(0L, count(b, "coreUnitPhysicalMappingOpens"))
                assertEquals(2L, count(b, "coreUnitMappingCacheHits"))
                assertEquals(2L, a.execute(1).asLong())
                assertEquals(1L, count(b, "coreUnitDecodedBindings"))
                first.close()
                assertEquals(3L, b.execute(2).asLong())
                assertEquals(2L, count(b, "coreUnitDecodedBindings"))
            } finally { first.close(); second.close() }
        }
        executionContext().use { context ->
            val entry = context.eval("thc", request(manifest, "bytecode"))
            assertEquals(0L, count(entry, "coreUnitPhysicalMappingOpens"), "closed contexts release leases into bounded idle reuse")
            assertEquals(2L, count(entry, "coreUnitMappingCacheHits"))
            assertEquals(1L, count(entry, "coreUnitDecodedBindings"), "decoded state is not process-shared")
            assertEquals(7L, entry.execute(0).asLong())
        }
    }
}
