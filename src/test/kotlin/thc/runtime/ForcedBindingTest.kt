// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime

import com.oracle.truffle.api.CompilerDirectives
import com.oracle.truffle.api.RootCallTarget
import com.oracle.truffle.api.TruffleLanguage
import com.oracle.truffle.api.frame.FrameDescriptor
import com.oracle.truffle.api.frame.FrameSlotKind
import com.oracle.truffle.api.frame.VirtualFrame
import com.oracle.truffle.api.nodes.RootNode
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import thc.Language
import thc.executionContext

private typealias ForcedCore = List<Any?>

class ForcedBindingTest {
    private fun descriptor(): FrameDescriptor = FrameDescriptor.newBuilder().also {
        it.addSlot(FrameSlotKind.Object, "binding", null)
        it.addSlot(FrameSlotKind.Object, "alias", null)
    }.build()

    private class BindingDriver(descriptor: FrameDescriptor, metrics: Metrics) : RootNode(null, descriptor) {
        @Child private var first = Evaluate(LocalRead(0), metrics)
        @Child private var second = Evaluate(LocalRead(0), metrics)
        @Child private var alias = Evaluate(LocalRead(1), metrics)
        var bindingAfter: Any? = null
        var aliasAfter: Any? = null
        var compiledEntries = 0
        override fun execute(frame: VirtualFrame): Any {
            if (CompilerDirectives.inCompiledCode()) compiledEntries++
            FrameAccess.write(frame, 0, frame.arguments[0])
            FrameAccess.write(frame, 1, frame.arguments[0])
            try {
                val a = first.executeLong(frame)
                val b = second.executeLong(frame)
                val c = alias.executeLong(frame)
                return a + b + c
            } finally {
                bindingAfter = FrameAccess.read(frame, 0)
                aliasAfter = FrameAccess.read(frame, 1)
            }
        }
    }

    private fun compile(target: RootCallTarget) {
        val type = Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget")
        type.getMethod("compile", Boolean::class.javaPrimitiveType).invoke(target, true)
        assertTrue(valid(target))
    }
    private fun valid(target: RootCallTarget): Boolean = Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget")
        .getMethod("isValidLastTier").invoke(target) == true
    private fun entered(action: (Language) -> Unit) {
        executionContext().use { context ->
            context.initialize("thc")
            context.enter()
            try { action(TruffleLanguage.LanguageReference.create(Language::class.java).get(null)) }
            finally { context.leave() }
        }
    }

    @Test fun astForcingReplacesOnlyTheDemandedLocalAndRetainsSharedThunkAliases() = entered { _ ->
        val metrics = Metrics(true)
        val driver = BindingDriver(descriptor(), metrics)
        val answer = 3_000_000_017L
        var evaluations = 0
        val producer = object : RootNode(null) {
            override fun execute(frame: VirtualFrame): Any { evaluations++; return answer }
        }.callTarget
        fun check(thunk: Thunk) {
            val entered = metrics.thunkEvaluations
            val hits = metrics.thunkHits
            assertEquals(answer * 3, Calls.target(driver.callTarget, arrayOf(thunk)))
            assertEquals(answer, driver.bindingAfter, "The demanded binding must contain its answer")
            assertEquals(answer, driver.aliasAfter, "The later alias demand also updates its own link")
            assertEquals(entered + 1, metrics.thunkEvaluations)
            assertEquals(hits + 1, metrics.thunkHits, "Only the separate alias still needs the shared thunk update")
            assertEquals(2, thunk.state)
            assertEquals(answer, thunk.value)
        }
        repeat(40) { check(Thunk(producer, null)) }
        compile(driver.callTarget)
        val before = driver.compiledEntries
        repeat(8) {
            check(Thunk(producer, null))
            assertTrue(valid(driver.callTarget), "Fresh activation writeback must keep its warmed compiled path")
        }
        assertEquals(before + 8, driver.compiledEntries)
        assertEquals(48, evaluations)
        val hits = metrics.thunkHits
        assertEquals(Long.MIN_VALUE * 3, Calls.target(driver.callTarget, arrayOf(Long.MIN_VALUE)))
        assertEquals(Long.MIN_VALUE, driver.bindingAfter)
        assertEquals(hits, metrics.thunkHits, "An already evaluated value needs no thunk update")
    }

    @Test fun failedForcingLeavesLocalLinksPointingAtTheMemoizedFailure() = entered { _ ->
        val metrics = Metrics(true)
        val driver = BindingDriver(descriptor(), metrics)
        var evaluations = 0
        val failure = RuntimeFault("forced binding test failure")
        val producer = object : RootNode(null) {
            override fun execute(frame: VirtualFrame): Any { evaluations++; throw failure }
        }.callTarget
        val thunk = Thunk(producer, null)
        repeat(2) {
            assertSame(failure, assertThrows(RuntimeFault::class.java) { Calls.target(driver.callTarget, arrayOf(thunk)) })
            assertSame(thunk, driver.bindingAfter, "No successful answer exists to replace this link")
            assertSame(thunk, driver.aliasAfter)
            assertEquals(3, thunk.state)
        }
        assertEquals(1, evaluations)
        assertEquals(1L, metrics.thunkEvaluations)
    }

    @Test fun recursiveCellIdentitySurvivesStrictRhsForcingBeforeGroupPublication() = entered { language ->
        val answerLayout = DataLayout(language, "Answer", "Answer", emptyArray())
        val answer = answerLayout.create(emptyArray())
        val strictLayout = DataLayout(language, "Strict", "Strict", arrayOf("LiftedRep"))
        val producer = object : RootNode(null) {
            override fun execute(frame: VirtualFrame): Any = answer
        }.callTarget
        val thunk = Thunk(producer, null)
        val firstCell = RecCell().also { it.initialized = true; it.value = thunk }
        val secondCell = RecCell()
        val metrics = Metrics(true)
        val root = object : RootNode(null, descriptor()) {
            @Child private var strictOperand = Evaluate(LocalRead(0), metrics)
            override fun execute(frame: VirtualFrame): Any {
                FrameAccess.write(frame, 0, firstCell)
                FrameAccess.write(frame, 1, secondCell)
                // This is the strict RHS phase: captures still own recursive cells.
                secondCell.value = strictLayout.create(arrayOf(strictOperand.execute(frame)))
                secondCell.initialized = true
                assertSame(firstCell, FrameAccess.read(frame, 0), "The frame must retain its unpublished cell")
                assertSame(answer, firstCell.value, "The mutable cell link can retain the successful answer")
                // Publication must still find both original cells after that force.
                for (slot in 0..1) {
                    val cell = FrameAccess.read(frame, slot) as RecCell
                    FrameAccess.write(frame, slot, cell.value)
                }
                assertSame(answer, FrameAccess.read(frame, 0))
                return FrameAccess.read(frame, 1)!!
            }
        }
        val strict = Calls.target(root.callTarget, emptyArray()) as DataValue
        assertSame(answer, strictLayout.read(strict, 0))
        assertSame(answer, thunk.value)
        assertEquals(1L, metrics.thunkEvaluations)
    }

    private fun variable(id: String): ForcedCore = listOf("var", id)
    private fun integer(value: Long): ForcedCore = listOf("lit", "int", value.toString())
    private fun primitive(name: String, vararg operands: ForcedCore): ForcedCore =
        listOf("app", listOf("prim", name), operands.toList(), List(operands.size) { false })
    private fun lambda(id: String, body: ForcedCore): ForcedCore = listOf("lam", listOf(mapOf(
        "id" to id, "name" to id, "type" to "Int#", "lifted" to false, "coercion" to false)), body)
    private fun binding(id: String, expression: ForcedCore, arity: Int = 0): Map<String, Any?> = mapOf(
        "id" to id, "name" to id, "type" to "Synthetic", "lifted" to true, "arity" to arity, "expr" to expression)
    private fun demand(value: ForcedCore, name: String, body: ForcedCore): ForcedCore =
        listOf("case", value, name, listOf(listOf("default", null, emptyList<String>(), body)))
    private fun let(id: String, value: ForcedCore, body: ForcedCore): ForcedCore =
        listOf("let", false, listOf(binding(id, value)), body)
    private fun module(body: ForcedCore): Map<String, Any?> = mapOf(
        "schema" to 1, "ghc" to "9.14.1", "module" to "Synthetic.ForcedBinding", "instrument" to true,
        "constructors" to emptyList<Any>(), "bindings" to listOf(binding("entry", lambda("input", body), 1)))
    private fun count(program: ExecutableProgram, key: String): Long = (program.diagnostics().getValue(key) as Number).toLong()
    private fun invoke(program: ExecutableProgram, input: Long): Any? =
        Calls.target(program.hostEntryTarget(1), arrayOf(program.entryValue("entry"), arrayOf<Any?>(input)))
    private fun bothBackends(body: ForcedCore, coefficient: Long) = entered { language ->
        for (backend in listOf("ast", "bytecode")) {
            val program: ExecutableProgram = if (backend == "ast") Program(language, module(body)) else BytecodeProgram(language, module(body))
            fun check(input: Long) {
                val evaluations = count(program, "thunkEvaluations")
                val hits = count(program, "thunkHits")
                assertEquals(input * coefficient, invoke(program, input), backend)
                assertEquals(evaluations + 1, count(program, "thunkEvaluations"), "$backend evaluates its shared thunk once")
                assertEquals(hits + 1, count(program, "thunkHits"), "$backend only enters the thunk through the distinct alias/capture")
            }
            repeat(40) { check(3_000_000_017L) }
            compile(program.entryTarget("entry"))
            val compiled = count(program, "compiledEntries")
            for (input in listOf(3_000_000_017L, Long.MIN_VALUE, Long.MAX_VALUE, 0L, -3_000_000_017L)) check(input)
            assertTrue(count(program, "compiledEntries") > compiled)
            assertEquals(0L, count(program, "blackholes"))
        }
    }

    @Test fun repeatedLocalDemandsBypassThunkHeadersButSeparateAliasesKeepSharing() {
        val sum = primitive("+#", primitive("+#", variable("first"), variable("second")),
            primitive("+#", variable("third"), variable("fourth")))
        val reads = demand(variable("shared"), "first", demand(variable("shared"), "second",
            demand(variable("alias"), "third", demand(variable("alias"), "fourth", sum))))
        val delayed = demand(variable("input"), "delayed", variable("delayed"))
        bothBackends(let("shared", delayed, let("alias", variable("shared"), reads)), 4)
    }

    @Test fun preexistingCapturesKeepTheirFinalThunkReferenceWhileTheirRestoredLocalUpdates() {
        val captured = lambda("unused", demand(variable("shared"), "first", demand(variable("shared"), "second",
            primitive("+#", variable("first"), variable("second")))))
        val call = listOf("app", variable("captured"), listOf(integer(0)), listOf(false))
        val reads = demand(variable("shared"), "outer", primitive("+#", variable("outer"), call))
        val delayed = demand(variable("input"), "delayed", variable("delayed"))
        bothBackends(let("shared", delayed, let("captured", captured, reads)), 3)
        // Recursive closures keep the captured cell itself, even after publication
        // gives the caller a direct thunk link. Forcing may update that cell's value.
        val recursive = listOf("let", true, listOf(binding("shared", delayed), binding("captured", captured)), reads)
        bothBackends(recursive, 3)
    }
}
