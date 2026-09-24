@file:Suppress("UNCHECKED_CAST")
package thc.runtime

import com.oracle.truffle.api.RootCallTarget
import com.oracle.truffle.api.TruffleLanguage
import com.oracle.truffle.api.nodes.DirectCallNode
import com.oracle.truffle.api.nodes.NodeUtil
import org.graalvm.polyglot.PolyglotException
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import thc.*
import java.io.File
import java.math.BigInteger
import java.security.MessageDigest

/** Native defined bits, an independent unbounded bit model, and exact installed entries. */
class BitPrimopsTest {
    private val root = File(System.getProperty("thc.projectRoot"))
    private fun manifest() = Json.parse(File(root, "build/bit-primops/manifest.json").readText()) as Map<String, Any?>
    private fun verifyHashes(manifest: Map<String, Any?>) {
        for (kind in listOf("inputHashes", "artifactHashes")) for ((path, expected) in manifest[kind] as Map<String, String>) {
            val actual = MessageDigest.getInstance("SHA-256").digest(File(root, path).readBytes())
                .joinToString("") { "%02x".format(it.toInt() and 255) }
            assertEquals(expected, actual, "Stale bit primitive fixture: $path; rerun prepare-tests.sh")
        }
    }
    private fun mathematical(operation: String, width: Int, carrier: Long): Long {
        val input = BigInteger.valueOf(carrier).mod(BigInteger.ONE.shiftLeft(width))
        val bits = List(width) { input.testBit(it) }
        return when (operation) {
            "popCnt" -> bits.count { it }.toLong()
            "clz" -> bits.asReversed().takeWhile { !it }.size.toLong()
            "ctz" -> bits.takeWhile { !it }.size.toLong()
            "bitReverse", "byteSwap" -> {
                var result = BigInteger.ZERO
                for (bit in bits.indices) if (bits[bit]) {
                    val destination = if (operation == "bitReverse") width - 1 - bit
                        else width - 8 - 8 * (bit / 8) + bit % 8
                    result = result + BigInteger.ONE.shiftLeft(destination)
                }
                result.toLong()
            }
            else -> error(operation)
        }
    }
    private fun valid(target: RootCallTarget, label: String) = assertEquals(true,
        Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget").getMethod("isValidLastTier").invoke(target), label)

    @Test fun realCoreMatchesNativeAndBitModelForEveryInstalledEntry() {
        val manifest = manifest()
        verifyHashes(manifest)
        val entries = manifest["entries"] as List<Map<String, Any?>>
        assertEquals(21, entries.size)
        val rows = File(root, "build/bit-primops/oracle.tsv").readLines().map { it.split('\t') }.groupBy { it[0] }
        assertEquals(entries.map { it["name"] }.toSet(), rows.keys)
        assertEquals(11923, rows.values.sumOf { it.size })
        assertEquals((manifest["nativeRows"] as Number).toInt(), rows.values.sumOf { it.size })
        val stages = manifest["stages"] as Map<String, List<String>>
        assertEquals(setOf("pre", "post"), stages.keys)
        val composite = manifest["compositeEntry"] as String
        assertEquals(entries.indices.toList(), entries.map { (it["index"] as Number).toInt() })
        val expectations = entries.map { entry ->
            val name = entry["name"] as String
            val width = (entry["width"] as Number).toInt()
            val operation = entry["operation"] as String
            assertEquals(rows.getValue(name).size, rows.getValue(name).map { it[1].toLong() }.toSet().size,
                "Unique native inputs for $name")
            rows.getValue(name).associate { row ->
                val input = row[1].toLong(); val expected = row[2].toLong()
                assertEquals(mathematical(operation, width, input), expected, "Native $name($input)")
                input to expected
            }
        }
        // The union retains every native input and exercises the other operations
        // there too. Native rows take precedence; the independent model fills gaps.
        val cases = expectations.flatMap { it.keys }.toSortedSet().map { input ->
            arrayOf(input, *entries.mapIndexed { index, entry ->
                expectations[index][input] ?: mathematical(entry["operation"] as String,
                    (entry["width"] as Number).toInt(), input)
            }.toTypedArray())
        }
        assertEquals(1953, cases.size)
        for ((stage, paths) in stages) {
            val merged = CoreModules.merge(paths.map { Json.parse(File(root, it).readText()) as Map<String, Any?> })
            for (backend in listOf("ast", "bytecode")) primopTestContext().use { context ->
                context.initialize("thc"); context.enter()
                try {
                    val label = "$stage/$backend"
                    val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                    val module = CoreModules.reachable(merged, composite) + ("instrument" to true)
                    assertEquals(1, (module["bindings"] as List<*>).size, "$label one composite guest root")
                    val program: ExecutableProgram = if (backend == "ast") Program(language, module) else BytecodeProgram(language, module)
                    val host = program.hostEntryTarget(entries.size + 1)
                    val function = context.asValue(EntryValue(program, composite, entries.size + 1))
                    fun check(row: Array<Long>, failures: Long = 0) = assertEquals(failures,
                        function.execute(*row).asLong(), "$label(${row[0]}): one failure bit per operation")
                    fun compiled() = (program.diagnostics().getValue("compiledEntries") as Number).toLong()
                    cases.forEach { check(it) }
                    val wrongExpectations = entries.indices.map { index ->
                        cases.first().clone().also { it[index + 1] = it[index + 1] xor 1L }
                    }
                    wrongExpectations.forEachIndexed { index, wrong -> check(wrong, 1L shl index) }
                    assertEquals(0L, compiled(), "$label waits for the explicit compilation request")
                    assertTrue(function.invokeMember("compile").asBoolean(), "$label installation")
                    val original = program.entryTarget(composite)
                    val active = NodeUtil.findAllNodeInstances(host.rootNode, DirectCallNode::class.java)
                        .filter { it.callTarget === original }.map { it.currentCallTarget as RootCallTarget }
                        .ifEmpty { listOf(original) }
                    for (row in cases.asReversed()) {
                        val before = compiled()
                        check(row)
                        assertEquals(1L, compiled() - before, "$label(${row[0]}) must enter installed guest code")
                    }
                    // Every result must reach its own check in installed code.
                    for (index in entries.indices) {
                        val before = compiled()
                        check(wrongExpectations[index], 1L shl index)
                        assertEquals(1L, compiled() - before, "$label check $index remains compiled")
                    }
                    valid(host, "$label host remains installed")
                    active.forEach { valid(it, "$label active target remains installed") }
                    for (counter in listOf("unsupportedTraps", "blackholes", "thunkEvaluations", "papAllocations"))
                        assertEquals(0L, (program.diagnostics().getValue(counter) as Number).toLong(), "$label/$counter")
                } finally { context.leave() }
            }
        }
    }

    @Test fun malformedArityIsRejectedAtLoadEvenWithDiagnosticExecutionEnabled() {
        val entries = manifest()["entries"] as List<Map<String, Any?>>
        for (backend in listOf("ast", "bytecode")) for (diagnostic in listOf(false, true)) executionContext().use { context ->
            for (entry in entries) for (supplied in listOf(0, 2)) {
                val primitive = entry["primitive"] as String
                val body = listOf("app", listOf("prim", primitive),
                    List(supplied) { listOf("lit", "word", "1") }, List(supplied) { false })
                val module = mapOf("schema" to 1, "ghc" to "9.14.1", "module" to "Malformed.BitPrimitive",
                    "constructors" to emptyList<Any?>(), "bindings" to listOf(mapOf("id" to "entry", "name" to "entry",
                        "lifted" to true, "arity" to 0, "expr" to body)))
                val error = assertThrows(PolyglotException::class.java) {
                    context.eval("thc", Json.stringify(mapOf("entry" to "entry", "backend" to backend,
                        "diagnosticUnsupported" to diagnostic, "modules" to listOf(module))))
                }
                assertTrue(error.message.orEmpty().contains("Primitive arity mismatch: $primitive"), error.message)
            }
        }
    }
}
