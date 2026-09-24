@file:Suppress("UNCHECKED_CAST")
package thc

import org.graalvm.polyglot.PolyglotException
import org.graalvm.polyglot.Value
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.File
import java.math.BigInteger
import java.security.MessageDigest

/** Actual GHC primops, unsigned mathematical results and installed guest code. */
class IntegerPrimopsTest {
    private val root = File(System.getProperty("thc.projectRoot"))
    private fun manifest(): Map<String, Any?> =
        Json.parse(File(root, "build/integer-primops/manifest.json").readText()) as Map<String, Any?>
    private fun count(function: Value, key: String): Long =
        ((Json.parse(function.getMember("diagnostics").asString()) as Map<String, Any?>)[key] as Number).toLong()

    private fun verifyHashes(manifest: Map<String, Any?>) {
        for (kind in listOf("inputHashes", "artifactHashes")) {
            for ((path, expected) in manifest[kind] as Map<String, String>) {
                val actual = MessageDigest.getInstance("SHA-256").digest(File(root, path).readBytes())
                    .joinToString("") { "%02x".format(it.toInt() and 255) }
                assertEquals(expected, actual, "Stale integer primop fixture: $path; rerun prepare-tests.sh")
            }
        }
    }

    private fun mathematical(name: String, width: Int, left: Long, right: Long): Long {
        val modulus = BigInteger.ONE.shiftLeft(width)
        val mask = modulus - BigInteger.ONE
        val x = BigInteger.valueOf(left).mod(modulus)
        val y = BigInteger.valueOf(right).mod(modulus)
        fun bit(condition: Boolean) = if (condition) BigInteger.ONE else BigInteger.ZERO
        val value = when (name.substringBefore("Word")) {
            "quot" -> x / y
            "rem" -> x % y
            "eq" -> bit(x == y)
            "ne" -> bit(x != y)
            "gt" -> bit(x > y)
            "ge" -> bit(x >= y)
            "and" -> x.and(y)
            "or" -> x.or(y)
            "xor" -> x.xor(y)
            "not" -> x.xor(mask)
            "uncheckedShiftL" -> x.shiftLeft(right.toInt()).and(mask)
            "uncheckedShiftRL" -> x.shiftRight(right.toInt())
            else -> error("Unknown integer primop $name")
        }
        return value.toLong()
    }

    @Test fun realCoreAgreesWithNativeAndUnsignedModelBeforeAndAfterCompilation() {
        val manifest = manifest()
        verifyHashes(manifest)
        val entries = manifest["entries"] as List<Map<String, Any?>>
        assertEquals(40, entries.size)
        val composite = manifest["composite"] as Map<String, Any?>
        assertEquals("composite", composite["name"])
        assertEquals(3, (composite["arity"] as Number).toInt())
        assertEquals(0, (composite["selectorArgument"] as Number).toInt())
        assertEquals(entries.map { it["name"] }, composite["selectorOrder"])
        assertEquals((0 until entries.size).toList(), entries.map { (it["selector"] as Number).toInt() })
        val modules = (manifest["modules"] as List<String>).map { Json.parse(File(root, it).readText()) }
        val rows = File(root, "build/integer-primops/oracle.tsv").readLines()
            .map { it.split('\t') }.groupBy { it[0] }
        assertEquals(entries.map { it["name"] }.toSet(), rows.keys)
        val casesByName = entries.associate { entry ->
            val name = entry["name"] as String
            name to rows.getValue(name).map { listOf(it[1].toLong(), it[2].toLong(), it[3].toLong()) }
        }
        for (entry in entries) {
            val name = entry["name"] as String
            val width = (entry["width"] as Number).toInt()
            for ((left, right, native) in casesByName.getValue(name))
                assertEquals(mathematical(name, width, left, right), native, "Native $name($left, $right)")
        }
        for (backend in listOf("ast", "bytecode")) primopTestContext().use { context ->
            val function = context.eval("thc", Json.stringify(mapOf("modules" to modules,
                "entry" to composite["name"], "backend" to backend, "instrument" to true)))
            fun check(entry: Map<String, Any?>, row: List<Long>) {
                val name = entry["name"] as String
                val selector = (entry["selector"] as Number).toLong()
                assertEquals(row[2], function.execute(selector, row[0], row[1]).asLong(),
                    "$backend $name(${row[0]}, ${row[1]})")
            }
            // Warm every selector arm and every native row before the one explicit
            // compilation. Each row is then repeated through installed guest code.
            for (entry in entries) {
                val cases = casesByName.getValue(entry["name"] as String)
                cases.forEach { check(entry, it) }
            }
            assertEquals(0L, count(function, "compiledEntries"), "$backend warm phase")
            assertTrue(function.invokeMember("compile").asBoolean(), "$backend composite installation")
            for (entry in entries) {
                val name = entry["name"] as String
                val cases = casesByName.getValue(name)
                val before = count(function, "compiledEntries")
                cases.asReversed().forEach { check(entry, it) }
                // One composite guest root entry per call proves that every
                // oracle row reached installed code after compilation.
                assertEquals(cases.size.toLong(), count(function, "compiledEntries") - before,
                    "$backend $name every native row must enter compiled code")
                assertEquals(0L, count(function, "unsupportedTraps"), "$backend $name")
            }
        }
    }

    @Test fun everyAddedPrimitiveRejectsWrongAritiesEvenInDiagnosticMode() {
        val entries = manifest()["entries"] as List<Map<String, Any?>>
        for (backend in listOf("ast", "bytecode")) for (diagnostic in listOf(false, true)) {
            executionContext().use { context ->
                for (entry in entries) {
                    val name = entry["primitive"] as String
                    val arity = (entry["arity"] as Number).toInt()
                    for (supplied in listOf(arity - 1, arity + 1)) {
                        val body = listOf("app", listOf("prim", name),
                            List(supplied) { listOf("lit", "word", "1") }, List(supplied) { false })
                        val module = mapOf("schema" to 1, "ghc" to "9.14.1", "module" to "Malformed.IntegerPrimop",
                            "constructors" to emptyList<Any?>(), "bindings" to listOf(mapOf("id" to "entry",
                                "name" to "entry", "lifted" to true, "arity" to 0, "expr" to body)))
                        val error = assertThrows(PolyglotException::class.java) {
                            context.eval("thc", Json.stringify(mapOf("entry" to "entry", "backend" to backend,
                                "diagnosticUnsupported" to diagnostic, "modules" to listOf(module))))
                        }
                        assertTrue(error.message.orEmpty().contains("Primitive arity mismatch: $name"), error.message)
                    }
                }
            }
        }
    }
}
