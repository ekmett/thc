// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

@file:Suppress("UNCHECKED_CAST")
package thc

import org.graalvm.polyglot.PolyglotException
import org.graalvm.polyglot.Value
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.File
import java.security.MessageDigest

/** GHC's narrow signed values have canonical sign-extended Long carriers. */
class SignedNarrowPrimopsTest {
    private val root = File(System.getProperty("thc.projectRoot"))
    private fun manifest() = Json.parse(File(root, "build/signed-narrow-primops/manifest.json").readText()) as Map<String, Any?>
    private fun count(function: Value, key: String): Long =
        ((Json.parse(function.getMember("diagnostics").asString()) as Map<String, Any?>)[key] as Number).toLong()
    private fun entries() = manifest()["entries"] as List<Map<String, Any?>>

    private fun mathematical(name: String, width: Int, left: Long, right: Long): Long {
        return ScalarPrimopModel.scalar(name.substringBefore("Int"), width, false, left, right)
    }
    private fun verifyHashes(manifest: Map<String, Any?>) {
        for (kind in listOf("inputHashes", "artifactHashes")) for ((path, expected) in manifest[kind] as Map<String, String>) {
            val actual = MessageDigest.getInstance("SHA-256").digest(File(root, path).readBytes())
                .joinToString("") { "%02x".format(it.toInt() and 255) }
            assertEquals(expected, actual, "Stale signed narrow fixture: $path; rerun prepare-tests.sh")
        }
    }

    @Test fun realCoreAgreesWithNativeAndSignedModelBeforeAndAfterCompilation() {
        val manifest = manifest()
        verifyHashes(manifest)
        val entries = manifest["entries"] as List<Map<String, Any?>>
        assertEquals(36, entries.size)
        assertEquals("signedNarrowDispatch", manifest["compositeEntry"])
        assertEquals((0 until entries.size).toList(), entries.map { (it["selector"] as Number).toInt() })
        val modules = (manifest["modules"] as List<String>).map { Json.parse(File(root, it).readText()) }
        val rows = File(root, "build/signed-narrow-primops/oracle.tsv").readLines()
            .map { it.split('\t') }.groupBy { it[0] }
        assertEquals(entries.map { it["name"] }.toSet(), rows.keys)
        val cases = entries.associate { entry ->
            val name = entry["name"] as String
            val width = (entry["width"] as Number).toInt()
            val arity = (entry["arity"] as Number).toInt()
            val operationCases = rows.getValue(name).map { listOf(it[1].toLong(), it[2].toLong(), it[3].toLong()) }
            for ((left, right, native) in operationCases) {
                if (arity == 1) assertEquals(0L, right, "Unary native oracle row for $name")
                assertEquals(mathematical(name, width, left, right), native, "Native $name($left, $right)")
            }
            name to operationCases
        }
        assertEquals((manifest["nativeRows"] as Number).toLong(), cases.values.sumOf { it.size.toLong() })
        for (backend in listOf("ast", "bytecode")) primopTestContext().use { context ->
            val function = context.eval("thc", Json.stringify(mapOf("modules" to modules,
                "entry" to manifest["compositeEntry"], "backend" to backend, "instrument" to true)))
            fun check(entry: Map<String, Any?>, row: List<Long>) {
                val name = entry["name"] as String
                val selector = (entry["selector"] as Number).toInt()
                assertEquals(row[2], function.execute(selector, row[0], row[1]).asLong(),
                    "$backend $name(${row[0]}, ${row[1]})")
            }
            for (entry in entries) for (row in cases.getValue(entry["name"] as String)) check(entry, row)
            assertEquals(0L, count(function, "compiledEntries"), "$backend must remain interpreted before explicit compile")
            assertTrue(function.invokeMember("compile").asBoolean(), "$backend composite installation")
            val before = count(function, "compiledEntries")
            for (entry in entries.asReversed()) for (row in cases.getValue(entry["name"] as String).asReversed())
                check(entry, row)
            assertEquals(cases.values.sumOf { it.size.toLong() }, count(function, "compiledEntries") - before,
                "$backend every native row must enter the installed composite guest root")
            assertEquals(0L, count(function, "unsupportedTraps"))
            assertEquals(0L, count(function, "blackholes"))
        }
    }

    private fun rawModule(primitive: String, width: Int, supplied: Int): Map<String, Any?> {
        val parameters = List(supplied) { mapOf("id" to "x$it", "name" to "x$it", "lifted" to false,
            "type" to "Int$width#", "coercion" to false) }
        val body = listOf("app", listOf("prim", primitive), List(supplied) { listOf("var", "x$it") }, List(supplied) { false })
        return mapOf("schema" to 1, "ghc" to "9.14.1", "module" to "SignedNarrowCarrierControl",
            "constructors" to emptyList<Any?>(), "bindings" to listOf(mapOf("id" to "entry", "name" to "entry",
                "lifted" to true, "arity" to supplied, "expr" to listOf("lam", parameters, body))))
    }

    @Test fun directResultsAreCanonicalWithoutAnIntNToIntConversionMaskingTheResult() {
        for (entry in entries()) {
            val name = entry["name"] as String
            val width = (entry["width"] as Number).toInt()
            val arity = (entry["arity"] as Number).toInt()
            val minimum = -(1L shl (width - 1)); val maximum = -minimum - 1
            val values = listOf(minimum, minimum + 1, -3L, -1L, 0L, 1L, 3L, maximum - 1, maximum)
            val pairs = if (arity == 1) values.map { it to 0L } else
                values.flatMap { x -> values.map { y -> x to y } }.filter { (x, y) ->
                    !name.startsWith("quot") && !name.startsWith("rem") || y != 0L && (x != minimum || y != -1L)
                }
            for (backend in listOf("ast", "bytecode")) executionContext().use { context ->
                val function = context.eval("thc", Json.stringify(mapOf("modules" to listOf(rawModule(entry["primitive"] as String, width, arity)),
                    "entry" to "entry", "backend" to backend, "instrument" to true)))
                repeat(2) { pass ->
                    if (pass == 1) assertTrue(function.invokeMember("compile").asBoolean())
                    val before = count(function, "compiledEntries")
                    for ((left, right) in pairs) {
                        val args = if (arity == 1) arrayOf(left) else arrayOf(left, right)
                        assertEquals(mathematical(name, width, left, right), function.execute(*args).asLong(),
                            "$backend raw $name($left, $right), pass $pass")
                    }
                    if (pass == 1) assertEquals(pairs.size.toLong(), count(function, "compiledEntries") - before)
                }
            }
        }
    }

    @Test fun everyAddedPrimitiveRejectsWrongAritiesEvenInDiagnosticMode() {
        for (backend in listOf("ast", "bytecode")) for (diagnostic in listOf(false, true)) executionContext().use { context ->
            for (entry in entries()) {
                val name = entry["primitive"] as String
                val arity = (entry["arity"] as Number).toInt()
                val width = (entry["width"] as Number).toInt()
                for (supplied in listOf(arity - 1, arity + 1)) {
                    val error = assertThrows(PolyglotException::class.java) {
                        context.eval("thc", Json.stringify(mapOf("entry" to "entry", "backend" to backend,
                            "diagnosticUnsupported" to diagnostic, "modules" to listOf(rawModule(name, width, supplied)))))
                    }
                    assertTrue(error.message.orEmpty().contains("Primitive arity mismatch: $name"), error.message)
                }
            }
        }
    }
}
