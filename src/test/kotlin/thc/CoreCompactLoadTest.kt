// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import org.graalvm.polyglot.Engine
import org.graalvm.polyglot.Context
import org.graalvm.polyglot.Value
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class CoreCompactLoadTest {
    @TempDir lateinit var directory: Path
    @AfterEach fun releaseMappings() { CoreFileMappings.shared.evictIdleBelow(directory) }
    private val boundary = "optimized-Core-after-Tidy-before-CorePrep"
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** Small independently encoded model; genuine Haskell converter controls
     * are separate. Only the expression forms used by these tests are emitted. */
    private inner class Model(val name: String) {
        val strings = ByteArrayOutputStream()
        val data = ByteArrayOutputStream()
        val keys = ArrayList<Pair<String, Long>>()
        fun ByteArrayOutputStream.u(value: Long) {
            var remaining = value
            do {
                val byte = (remaining and 127).toInt()
                remaining = remaining ushr 7
                write(byte or if (remaining == 0L) 0 else 128)
            } while (remaining != 0L)
        }
        fun ByteArrayOutputStream.text(value: String) {
            val bytes = value.toByteArray(Charsets.UTF_8)
            u(strings.size().toLong()); u(bytes.size.toLong()); strings.write(bytes)
        }
        fun ByteArrayOutputStream.binder(ordinal: Long) {
            u(ordinal); write(byteArrayOf(0, 2, 0, 2, 0, 0, 0))
        }
        fun ByteArrayOutputStream.expr(tag: Int) { write(tag); write(ByteArray(10)) }
        fun ByteArrayOutputStream.literal(value: Long) { expr(2); write(0); u((value shl 1) xor (value shr 63)) }
        fun ByteArrayOutputStream.variable() { expr(0); write(1); u(0) }
        fun ByteArrayOutputStream.call(id: String) {
            expr(5); expr(0); write(0); text(id)
            u(1); variable(); u(1); write(byteArrayOf(2, 0, 0, 0))
        }
        fun ByteArrayOutputStream.choice(zero: ByteArrayOutputStream.() -> Unit,
                                         nonzero: ByteArrayOutputStream.() -> Unit) {
            expr(7); variable(); u(1); write(2); binder(1); u(2)
            write(2); write(0); u(0); u(0); zero()
            write(0); u(0); nonzero()
        }
        fun function(label: String, body: ByteArrayOutputStream.() -> Unit) {
            val id = "unit:$name.$label"
            keys += id to data.size().toLong()
            data.write(0); data.text(id); data.write(byteArrayOf(0, 2, 1, 1)); data.write(ByteArray(6))
            data.expr(3); data.u(1); data.binder(0); data.body()
        }
        fun write(headerName: String = name, badProvenance: Boolean = false): Map<String, Any?> {
            val facts = ByteArrayOutputStream()
            facts.u(1); facts.text("9.14.1"); facts.text("unit"); facts.text(headerName); facts.text(boundary)
            facts.write(ByteArray(6)) // provided/layout/constructors/foreign/bridge/bridgeUnit.
            facts.write(if (badProvenance) byteArrayOf(2) else ByteArray(8))
            val rows = keys.map { (id, offset) ->
                ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
                    .put(MessageDigest.getInstance("MD5").digest(id.toByteArray(Charsets.UTF_8))).putLong(offset).array()
            }.sortedWith { a, b -> java.util.Arrays.compareUnsigned(a, 0, 16, b, 0, 16) }
            val out = ByteBuffer.allocate(24 + facts.size() + data.size() + strings.size() + 3 + rows.size * 24 + 128)
                .order(ByteOrder.LITTLE_ENDIAN)
            out.put(byteArrayOf(84, 72, 67, 67, 77, 80, 0, 0)).putShort(1).putShort(0).putInt(0).putLong(facts.size().toLong())
            out.put(facts.toByteArray()).put(data.toByteArray()).put(strings.toByteArray())
            out.put(byteArrayOf(-1, -1, -1)) // Deliberately unread debug segments.
            rows.forEach(out::put)
            out.put(byteArrayOf(84, 72, 67, 67, 69, 78, 68, 49))
            var offset = 24L + facts.size()
            for (size in listOf(data.size(), strings.size(), 1, 1, 1, rows.size * 24)) {
                out.putLong(offset).putLong(size.toLong()); offset += size
            }
            out.putLong(rows.size.toLong()).putInt(0).putInt(7).putLong(0)
            val bytes = out.array()
            val path = directory.resolve("$name.thc")
            Files.write(path, bytes)
            return mapOf("name" to name, "boundary" to boundary, "sha256" to "a".repeat(64),
                "compact" to mapOf("path" to path.toString(), "sha256" to hash(bytes), "format" to CoreCompactFormat.NAME),
                "containsDelimitedControl" to false, "registrationObligations" to false,
                "mainAlias" to false, "packageScalarDeclarations" to false)
        }
    }

    private fun fixture(badB: Boolean = false, recursive: Boolean = false, wrongIdentity: Boolean = false,
                        badProvenance: Boolean = false): Path {
        val a = Model("A").run {
            function("entry") { choice({ literal(7) }, { call("unit:B.entry") }) }
            function("untouched") { write(255) }
            write()
        }
        val b = Model("B").run {
            function("entry") {
                when {
                    badB -> write(255)
                    recursive -> { expr(5); expr(0); write(0); text("unit:A.entry"); u(1); literal(0)
                        u(1); write(byteArrayOf(2, 0, 0, 0)) }
                    else -> { expr(5); expr(1); text("+#"); u(2); variable(); literal(1)
                        u(2); write(byteArrayOf(2, 0, 2, 0, 0, 0)) }
                }
            }
            write(if (wrongIdentity) "Wrong" else "B", badProvenance)
        }
        val c = Model("C").run { function("entry") { literal(99) }; write() }
        Files.delete(directory.resolve("C.thc"))
        val path = directory.resolve("packages.json")
        Files.writeString(path, Json.stringify(mapOf("format" to "thc-core-packages", "schema" to 1,
            "ghc" to "9.14.1", "units" to listOf(mapOf("id" to "unit", "depends" to emptyList<String>(),
                "modules" to listOf(a, b, c))))))
        return path
    }
    private fun request(path: Path, backend: String, verify: Boolean = false) = CoreModules.request(listOf("@$path"),
        "unit:A.entry", backend = backend, sourceNotesEnabled = false, asyncExceptions = false, verifyArtifacts = verify)
    private fun count(entry: Value, key: String) = ((Json.parse(entry.getMember("diagnostics").asString()) as Map<*, *>)[key] as Number).toLong()

    @Test fun selectedBindingAndCrossModuleDemandLeaveColdBodiesFilesAndDebugUnread() {
        val path = fixture()
        for (backend in listOf("ast", "bytecode")) executionContext().use { context ->
            val entry = context.eval("thc", request(path, backend))
            assertEquals(1L, count(entry, "coreCompactModuleOpens"))
            assertEquals(1L, count(entry, "coreCompactDecodedBindings"))
            assertEquals(7L, entry.execute(0).asLong())
            assertEquals(1L, count(entry, "coreCompactModuleOpens"))
            assertEquals(6L, entry.execute(5).asLong())
            assertEquals(2L, count(entry, "coreCompactModuleOpens"))
            assertEquals(2L, count(entry, "coreCompactDecodedBindings"))
            assertEquals(2L, count(entry, "coreCompactDecodedModules"))
            val decoded = count(entry, "coreCompactDataBytesRead")
            assertEquals(10L, entry.execute(9).asLong())
            assertEquals(decoded, count(entry, "coreCompactDataBytesRead"))
            assertEquals(0L, count(entry, "coreCompactDebugBytesRead"))
            assertEquals(0L, count(entry, "coreCompactHashBytesScanned"))
            assertEquals(0L, count(entry, "coreUnitSourceOpens"))
        }
    }

    @Test fun mutuallyReferencingModulesRegisterBeforeFollowingRuntimeDemand() {
        val path = fixture(recursive = true)
        for (backend in listOf("ast", "bytecode")) executionContext().use { context ->
            val entry = context.eval("thc", request(path, backend))
            assertEquals(7L, entry.execute(1).asLong())
            assertEquals(2L, count(entry, "coreCompactDecodedBindings"))
        }
    }

    @Test fun sourceEnabledOrdinaryLoadAndExecutionDoNotReadOptionalDebugTables() {
        val path = fixture()
        for (backend in listOf("ast", "bytecode")) executionContext().use { context ->
            val entry = context.eval("thc", CoreModules.request(listOf("@$path"), "unit:A.entry",
                backend = backend, sourceNotesEnabled = true, asyncExceptions = false))
            assertEquals(0L, count(entry, "coreCompactDebugBytesRead"))
            assertEquals(7L, entry.execute(0).asLong())
            assertEquals(6L, entry.execute(5).asLong())
            assertEquals(0L, count(entry, "coreCompactDebugBytesRead"))
            assertEquals(2L, count(entry, "coreCompactModuleOpens"))
        }
    }

    @Test fun malformedSelectedBodyIdentityAndTruncatedProvenanceRejectOnDemand() {
        for (variant in 0..2) {
            CoreFileMappings.shared.evictIdleBelow(directory)
            val path = fixture(badB = variant == 0, wrongIdentity = variant == 1, badProvenance = variant == 2)
            for (backend in listOf("ast", "bytecode")) executionContext().use { context ->
                val entry = context.eval("thc", request(path, backend))
                assertEquals(7L, entry.execute(0).asLong())
                val failure = assertThrows(RuntimeException::class.java) { entry.execute(1) }
                val expected = listOf("Invalid compact Core expression tag", "identity differs from package directory",
                    "Truncated compact Core record")[variant]
                assertTrue(failure.message.orEmpty().contains(expected), failure.message)
                assertEquals(7L, entry.execute(0).asLong())
            }
        }
    }

    @Test fun explicitVerificationWalksColdBindingsOnlyWhenRequested() {
        val path = fixture()
        for (backend in listOf("ast", "bytecode")) executionContext().use { context ->
            // A.untouched deliberately has an unknown expression tag.
            val failure = assertThrows(RuntimeException::class.java) { context.eval("thc", request(path, backend, true)) }
            assertTrue(failure.message.orEmpty().contains("Invalid compact Core expression tag"), failure.message)
        }
    }

    @Test fun sharedEngineContextsReuseOnlyImmutableMappingsAndClosingOnePreservesOther() {
        val path = fixture()
        Engine.newBuilder().allowExperimentalOptions(true).build().use { engine ->
            val first = Context.newBuilder("thc").engine(engine).build()
            Context.newBuilder("thc").engine(engine).build().use { second ->
                try {
                    val one = first.eval("thc", request(path, "ast"))
                    val two = second.eval("thc", request(path, "ast"))
                    assertEquals(1L, count(one, "coreCompactPhysicalMappingOpens"))
                    assertEquals(1L, count(two, "coreCompactMappingCacheHits"))
                    assertEquals(1L, count(two, "coreCompactDecodedBindings"))
                    first.close()
                    assertEquals(4L, two.execute(3).asLong())
                    assertEquals(2L, count(two, "coreCompactDecodedBindings"))
                } finally { first.close() }
            }
        }
    }
}
