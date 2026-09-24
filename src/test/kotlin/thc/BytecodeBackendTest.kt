// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc

import org.graalvm.polyglot.Context
import org.graalvm.polyglot.Engine
import org.graalvm.polyglot.PolyglotException
import org.graalvm.polyglot.Value
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.File

private typealias BytecodeCoreExpression = List<Any?>

/** Semantic parity at the public backend boundary, including cold compiled paths. */
class BytecodeBackendTest {
    private val project = File(System.getProperty("thc.projectRoot"))
    private val modules = listOf("THC.Prim", "THC.Fixtures").map { File(project, "build/core/$it.json").path }
    private data class Example(val entry: String, val input: Long, val expected: Long)
    private fun oracle(): List<Example> = File(project, "build/native/oracle.tsv").readLines()
        .filter { it.isNotBlank() }.map { line ->
            val fields = line.split('\t')
            Example(fields[0], fields[1].toLong(), fields[2].toLong())
        }
    @Suppress("UNCHECKED_CAST")
    private fun diagnostics(function: Value): Map<String, Any?> =
        Json.parse(function.getMember("diagnostics").asString()) as Map<String, Any?>
    private fun count(function: Value, key: String): Long = (diagnostics(function)[key] as Number).toLong()
    private fun load(context: Context, entry: String): Value =
        loadEntry(context, modules, entry, backend = "bytecode").also {
            assertEquals("bytecode", diagnostics(it)["backend"], "Must execute the requested backend")
        }
    private fun compileAndCheck(function: Value, input: Long, expected: Long) {
        repeat(40) { assertEquals(expected, function.execute(input).asLong()) }
        assertTrue(function.invokeMember("compile").asBoolean())
        val before = count(function, "compiledEntries")
        assertEquals(expected, function.execute(input).asLong())
        assertTrue(count(function, "compiledEntries") > before, "Must enter installed guest code")
    }

    private fun variable(id: String): BytecodeCoreExpression = listOf("var", id)
    private fun integer(value: Long): BytecodeCoreExpression = listOf("lit", "int", value.toString())
    private fun apply(function: BytecodeCoreExpression, arguments: List<BytecodeCoreExpression>,
                      lifted: List<Boolean>): BytecodeCoreExpression = listOf("app", function, arguments, lifted)
    private fun primitive(name: String, vararg arguments: BytecodeCoreExpression): BytecodeCoreExpression =
        apply(listOf("prim", name), arguments.toList(), List(arguments.size) { false })
    private fun lambda(id: String, body: BytecodeCoreExpression): BytecodeCoreExpression = listOf("lam", listOf(
        mapOf("id" to id, "name" to id, "type" to "Int#", "lifted" to false, "coercion" to false)), body)
    private fun binding(id: String, expression: BytecodeCoreExpression, arity: Int = 0): Map<String, Any?> =
        mapOf("id" to id, "name" to id, "type" to "Synthetic", "lifted" to true, "arity" to arity, "expr" to expression)
    private fun constructor(id: String, strict: List<Boolean>, lifted: List<Boolean?>): Map<String, Any?> = mapOf(
        "id" to id, "name" to id, "arity" to strict.size, "tag" to 1, "kind" to "boxed",
        "strictFields" to strict, "fieldLifted" to lifted,
        "fieldReps" to lifted.map { listOf(if (it == false) "IntRep" else "BoxedRep (Just Lifted)") })
    private fun module(bindings: List<Map<String, Any?>>, constructors: List<Map<String, Any?>> = emptyList()): Map<String, Any?> =
        mapOf("schema" to 1, "ghc" to "9.14.1", "module" to "Synthetic.BytecodeParity",
            "bindings" to bindings, "constructors" to constructors)
    private fun request(data: Map<String, Any?>, diagnostic: Boolean = false, backend: String = "bytecode"): String =
        Json.stringify(mapOf("entry" to "entry", "modules" to listOf(data), "backend" to backend,
            "instrument" to true, "diagnosticUnsupported" to diagnostic))
    private fun ignore(value: BytecodeCoreExpression): BytecodeCoreExpression =
        listOf("case", value, "ignored", listOf(listOf("default", null, emptyList<String>(), integer(41))))
    private fun coldBranch(value: BytecodeCoreExpression): BytecodeCoreExpression = listOf("case", variable("input"), "choice", listOf(
        listOf("lit", listOf("int", "0"), emptyList<String>(), integer(41)),
        listOf("default", null, emptyList<String>(), value)))
    private fun assertFailure(function: Value, message: String, vararg arguments: Any): PolyglotException =
        assertThrows(PolyglotException::class.java) { function.execute(*arguments) }.also {
            assertTrue(it.message.orEmpty().contains(message), it.message)
        }

    @Test fun allExportedFixturesMatchNativeGhcBeforeAndAfterCompilation() {
        executionContext().use { context ->
            val rowsByEntry = oracle().groupBy { it.entry }
            assertTrue(rowsByEntry.isNotEmpty(), "Native oracle must not be empty")
            for ((entry, rows) in rowsByEntry) {
                val function = load(context, entry)
                for (row in rows) assertEquals(row.expected, function.execute(row.input).asLong(), "$entry(${row.input}) interpreted")
                repeat(40) { val row = rows[it % rows.size]; assertEquals(row.expected, function.execute(row.input).asLong()) }
                assertTrue(function.invokeMember("compile").asBoolean(), "Compile bytecode: $entry")
                val before = count(function, "compiledEntries")
                for (row in rows) assertEquals(row.expected, function.execute(row.input).asLong(), "$entry(${row.input}) compiled")
                assertTrue(count(function, "compiledEntries") > before, "Installed bytecode guest: $entry")
                assertEquals(0L, count(function, "unsupportedTraps"))
            }
        }
    }

    @Test fun compiledBaseCasesCanGrowIntoLongSelfMutualAndCapturedLoops() {
        executionContext().use { context ->
            val cases = listOf("sumLoop" to 5_000_050_000L, "mutualTail" to 100_000L,
                "selfMutualTail" to 100_000L, "recursiveCaf" to 100_000L, "capturedChangingEnv" to 100_017L)
            for ((entry, expected) in cases) {
                val function = load(context, entry)
                val base = if (entry == "capturedChangingEnv") 17L else 0L
                compileAndCheck(function, 0L, base)
                assertEquals(expected, function.execute(100_000L).asLong(), entry)
                assertTrue(function.invokeMember("compile").asBoolean())
                assertEquals(expected, function.execute(100_000L).asLong(), "$entry recompiled")
                assertEquals(base, function.execute(0L).asLong(), "$entry fresh host call")
                if (entry == "capturedChangingEnv") assertEquals(24L, function.execute(7L).asLong())
                assertEquals(0L, count(function, "blackholes"))
            }
        }
    }

    @Test fun sharingPartialAndOverapplicationPreserveTheirSemantics() {
        executionContext().use { context ->
            val shared = load(context, "shared")
            assertEquals(120L, shared.execute(7L).asLong())
            assertEquals(1L, (diagnostics(shared)["thunkEvaluationsByLabel"] as Map<*, *>)["x"])
            // Caller-side forcing can forward the evaluated x to both uses;
            // the single recorded entry above establishes sharing either way.
            val partial = load(context, "under")
            assertEquals(14L, partial.execute(7L).asLong())
            assertTrue(count(partial, "papAllocations") > 0)
            compileAndCheck(partial, 8L, 15L)
            val over = load(context, "over")
            compileAndCheck(over, 0L, 13L)
            assertEquals(42L, over.execute(7L).asLong(), "A different returned function still accepts the surplus argument")
            val captured = load(context, "nestedCaptureThunk")
            compileAndCheck(captured, 7L, 72L)
            for (input in listOf(3_000_000_000L, -3_000_000_000L, 0L, 7L)) {
                assertEquals(input * input + 23L, captured.execute(input).asLong(), "Captures preserve 64-bit values and lexical ownership")
            }
        }
    }

    @Test fun wideArgumentsCapturesAndConstructorFieldsPreserveEveryOperand() {
        fun parameters(ids: List<String>): List<Map<String, Any?>> = ids.map {
            mapOf("id" to it, "name" to it, "type" to "Int#", "lifted" to false, "coercion" to false)
        }
        fun sum(ids: List<String>): BytecodeCoreExpression = ids.map(::variable)
            .reduce { left, right -> primitive("+#", left, right) }
        val ids = (0 until 10).map { "value$it" }
        val operands = (0 until 10).map { primitive("+#", variable("input"), integer(it.toLong())) }
        val wideFunction = binding("wide", listOf("lam", parameters(ids), sum(ids)), 10)
        val call = apply(variable("wide"), operands, List(10) { false })
        val arguments = module(listOf(wideFunction, binding("entry", lambda("input", call), 1)))

        val capturedBindings = ids.mapIndexed { index, id -> binding(id, operands[index]) + ("lifted" to false) }
        val closure = binding("closed", lambda("extra", primitive("+#", sum(ids), variable("extra"))), 1)
        val capturedBody = listOf("let", false, capturedBindings, listOf("let", false, listOf(closure),
            apply(variable("closed"), listOf(variable("input")), listOf(false))))
        val captures = module(listOf(binding("entry", lambda("input", capturedBody), 1)))

        val wide = constructor("Wide", List(10) { false }, List(10) { false })
        val constructed = apply(listOf("con", "Wide", 10), operands, List(10) { false })
        val readFields = listOf("case", constructed, "wideValue", listOf(listOf("data", "Wide", ids, sum(ids))))
        val fields = module(listOf(binding("entry", lambda("input", readFields), 1)), listOf(wide))
        executionContext().use { context ->
            for ((data, coefficient) in listOf(arguments to 10L, captures to 11L, fields to 10L)) {
                val function = context.eval("thc", request(data))
                compileAndCheck(function, 7L, coefficient * 7L + 45L)
                for (input in listOf(3_000_000_000L, -3_000_000_000L, Long.MIN_VALUE, Long.MAX_VALUE, 0L, 7L)) {
                    assertEquals(coefficient * input + 45L, function.execute(input).asLong(),
                        "All ten operands survive primitive storage, capture and call boundaries")
                }
            }
        }
    }

    @Test fun aSelfTailCallThroughAPapRestoresBothItsPrefixAndNewArguments() {
        val parameters = listOf("remaining", "total").map {
            mapOf("id" to it, "name" to it, "type" to "Int#", "lifted" to false, "coercion" to false)
        }
        val partial = apply(variable("worker"), listOf(primitive("-#", variable("remaining"), integer(1))), listOf(false))
        val continueWithPap = listOf("case", partial, "next", listOf(listOf("default", null, emptyList<String>(),
            apply(variable("next"), listOf(primitive("+#", variable("total"), variable("remaining"))), listOf(false)))))
        val body = listOf("case", primitive("<=#", variable("remaining"), integer(0)), "finished", listOf(
            listOf("lit", listOf("int", "1"), emptyList<String>(), variable("total")),
            listOf("default", null, emptyList<String>(), continueWithPap)))
        val worker = binding("worker", listOf("lam", parameters, body), 2)
        val entry = binding("entry", lambda("input", apply(variable("worker"),
            listOf(variable("input"), integer(0)), listOf(false, false))), 1)
        executionContext().use { context ->
            val function = context.eval("thc", request(module(listOf(worker, entry))))
            compileAndCheck(function, 0L, 0L)
            assertEquals(5_000_050_000L, function.execute(100_000L).asLong())
            assertTrue(count(function, "papAllocations") > 0, "Exercise a supplied prefix at the self-call site")
            assertTrue(function.invokeMember("compile").asBoolean())
            val before = count(function, "compiledEntries")
            for (input in listOf(100_000L, 7L, 0L, 10_000L)) {
                assertEquals(input * (input + 1) / 2, function.execute(input).asLong())
            }
            assertTrue(count(function, "compiledEntries") > before)
        }
    }

    @Test fun unusedBottomStaysLazyAndFailedThunksAreNotReentered() {
        executionContext().use { context ->
            for (entry in listOf("lazyArgument", "lazyField")) {
                val function = load(context, entry)
                compileAndCheck(function, 123L, 123L)
                assertEquals(0L, count(function, "blackholes"))
            }
            val bottom = load(context, "blackhole")
            assertFailure(bottom, "Blackhole", 0L)
            val evaluated = count(bottom, "thunkEvaluations")
            assertEquals(1L, count(bottom, "blackholes"))
            assertFailure(bottom, "Blackhole", 0L)
            assertEquals(evaluated, count(bottom, "thunkEvaluations"), "Failed update is memoized")
            assertEquals(1L, count(bottom, "blackholes"))
        }
    }

    @Test fun strictConstructorWorkersForceOnlyAtFullSaturationIncludingColdCompiledPaths() {
        val strict = constructor("Strict", listOf(true), listOf(true))
        val lazy = constructor("Lazy", listOf(false), listOf(true))
        executionContext().use { context ->
            for (firstClass in listOf(false, true)) for (id in listOf("Strict", "Lazy")) {
                val worker = listOf("con", id, 1)
                val constructed = apply(if (firstClass) variable("worker") else worker,
                    listOf(variable("bottom")), listOf(true))
                val data = module(listOf(binding("bottom", variable("bottom")), binding("worker", worker, 1),
                    binding("entry", lambda("input", coldBranch(ignore(constructed))), 1)), listOf(strict, lazy))
                val function = context.eval("thc", request(data))
                compileAndCheck(function, 0L, 41L)
                if (id == "Lazy") {
                    assertEquals(41L, function.execute(1L).asLong())
                    assertEquals(0L, count(function, "blackholes"))
                } else {
                    assertFailure(function, "Blackhole", 1L)
                    val evaluated = count(function, "thunkEvaluations")
                    assertFailure(function, "Blackhole", 1L)
                    assertEquals(evaluated, count(function, "thunkEvaluations"))
                    assertEquals(1L, count(function, "blackholes"))
                }
            }
            val pair = constructor("Pair", listOf(true, false), listOf(true, true))
            val partial = apply(listOf("con", "Pair", 2), listOf(variable("bottom")), listOf(true))
            val saturated = ignore(apply(variable("pap"), listOf(variable("bottom")), listOf(true)))
            val body = listOf("case", partial, "pap", listOf(listOf("default", null, emptyList<String>(), coldBranch(saturated))))
            val function = context.eval("thc", request(module(listOf(binding("bottom", variable("bottom")),
                binding("entry", lambda("input", body), 1)), listOf(pair))))
            compileAndCheck(function, 0L, 41L)
            assertTrue(count(function, "papAllocations") > 0)
            assertEquals(0L, count(function, "blackholes"), "A strict argument is lazy while the constructor is partial")
            assertFailure(function, "Blackhole", 1L)
        }
    }

    @Test fun unsupportedCodeRejectsAtLoadOrTrapsOnlyWhenTheDiagnosticBranchIsEntered() {
        val gaps = listOf(variable("Missing.libraryBody") to "Unresolved external binding Missing.libraryBody",
            primitive("futurePrim#", variable("input")) to "Unsupported primitive futurePrim#")
        executionContext().use { context ->
            for ((gap, message) in gaps) {
                val data = module(listOf(binding("entry", lambda("input", coldBranch(gap)), 1)))
                val rejected = assertThrows(PolyglotException::class.java) { context.eval("thc", request(data)) }
                assertTrue(rejected.message.orEmpty().contains(message), rejected.message)
                val function = context.eval("thc", request(data, diagnostic = true))
                compileAndCheck(function, 0L, 41L)
                assertEquals("diagnostic-traps", diagnostics(function)["unsupportedPolicy"])
                assertEquals(0L, count(function, "unsupportedTraps"))
                assertTrue((diagnostics(function)["deferredUnsupported"] as List<*>).isNotEmpty())
                assertFailure(function, "Diagnostic unsupported path reached: $message", 1L)
                assertEquals(1L, count(function, "unsupportedTraps"))
            }
            val malformed = apply(listOf("prim", "+#"), listOf(variable("input")), emptyList())
            val bad = module(listOf(binding("entry", lambda("input", coldBranch(malformed)), 1)))
            val error = assertThrows(PolyglotException::class.java) { context.eval("thc", request(bad, diagnostic = true)) }
            assertTrue(error.message.orEmpty().contains("representation flag count mismatch"), error.message)
            val uncertain = constructor("Uncertain", listOf(true), listOf(null))
            val data = module(listOf(binding("entry", ignore(apply(listOf("con", "Uncertain", 1),
                listOf(integer(0)), listOf(true))))), listOf(uncertain))
            val levity = assertThrows(PolyglotException::class.java) { context.eval("thc", request(data)) }
            assertTrue(levity.message.orEmpty().contains("Unknown strict constructor field levity"), levity.message)
        }
    }

    @Test fun managedLiteralAddressesPreserveUnsignedBytesNulsAndBoundsAfterCompilation() {
        val shifted = primitive("plusAddr#", listOf("lit", "string-bytes", "ff4100"), variable("input"))
        val body = primitive("indexCharOffAddr#", shifted, integer(0))
        executionContext().use { context ->
            val function = context.eval("thc", request(module(listOf(binding("entry", lambda("input", body), 1)))))
            val expected = listOf(255L, 65L, 0L, 0L)
            for ((offset, value) in expected.withIndex()) assertEquals(value, function.execute(offset).asLong())
            compileAndCheck(function, 0L, 255L)
            for ((offset, value) in expected.withIndex()) assertEquals(value, function.execute(offset).asLong())
            assertFailure(function, "outside its backing storage", 4L)
            val fakeRead = primitive("indexCharOffAddr#", variable("input"), integer(0))
            val fake = context.eval("thc", request(module(listOf(binding("entry", lambda("input", fakeRead), 1)))))
            assertFailure(fake, "Expected a managed literal Addr#", 0L)
        }
    }

    @Test fun coldRaiseKeepsItsBottomPayloadLazyAndMemoizesTheGuestFailure() {
        val raised = apply(listOf("prim", "raise#"), listOf(variable("payload")), listOf(true))
        val data = module(listOf(binding("payload", variable("payload")), binding("failure", raised),
            binding("entry", lambda("input", coldBranch(variable("failure"))), 1)))
        executionContext().use { context ->
            val function = context.eval("thc", request(data))
            compileAndCheck(function, 0L, 41L)
            assertEquals(0L, count(function, "thunkEvaluations"))
            val first = assertFailure(function, "Haskell exception raised by raise#", 1L)
            assertTrue(first.isGuestException)
            assertFalse(first.isHostException)
            assertEquals(0L, count(function, "blackholes"), "raise# must not enter its bottom payload")
            val evaluated = count(function, "thunkEvaluations")
            assertEquals(1L, evaluated, "Only the failing CAF is entered")
            assertTrue(assertFailure(function, "Haskell exception raised by raise#", 1L).isGuestException)
            assertEquals(evaluated, count(function, "thunkEvaluations"))
            assertEquals(0L, count(function, "blackholes"))
        }
    }

    @Test fun backendSelectionAndMutableProgramStateAreIsolatedWithinASharedEngine() {
        Engine.newBuilder().allowExperimentalOptions(true).option("engine.BackgroundCompilation", "false").build().use { engine ->
            Context.newBuilder("thc").engine(engine).build().use { first ->
                Context.newBuilder("thc").engine(engine).build().use { second ->
                    val ast = loadEntry(first, modules, "shared", backend = "ast")
                    val bytecode = load(first, "shared")
                    val otherContext = load(second, "shared")
                    assertEquals("ast", diagnostics(ast)["backend"])
                    assertFalse(ast.hasMember("bytecode"))
                    assertTrue(bytecode.hasMember("bytecode"))
                    val instructions = bytecode.getMember("bytecode").asString()
                    assertFalse(instructions.isBlank())
                    assertTrue(instructions.contains("c.EnterRoot"), "Dump must contain generated Core instructions")
                    assertTrue(instructions.contains("load.argument"), "Dump must include the bytecode argument loads")
                    assertEquals(120L, ast.execute(7L).asLong())
                    assertTrue(count(ast, "thunkEvaluations") > 0)
                    assertEquals(0L, count(bytecode, "thunkEvaluations"))
                    assertEquals(0L, count(otherContext, "thunkEvaluations"))
                    assertEquals(222L, bytecode.execute(10L).asLong())
                    assertEquals(0L, count(otherContext, "thunkEvaluations"))
                    assertEquals(30L, otherContext.execute(2L).asLong())
                    assertEquals(120L, ast.execute(7L).asLong())
                    val bad = assertThrows(PolyglotException::class.java) {
                        first.eval("thc", CoreModules.request(modules, "under", backend = "unknown-backend"))
                    }
                    assertTrue(bad.message.orEmpty().contains("Unknown THC backend: unknown-backend"), bad.message)
                    assertEquals(14L, load(first, "under").execute(7L).asLong())
                }
            }
        }
    }
}
