package thc

import com.oracle.truffle.api.RootCallTarget
import com.oracle.truffle.api.Truffle
import com.oracle.truffle.api.TruffleLanguage
import com.oracle.truffle.api.frame.FrameDescriptor
import com.oracle.truffle.api.frame.FrameSlotKind
import com.oracle.truffle.api.frame.VirtualFrame
import com.oracle.truffle.api.nodes.RootNode
import thc.runtime.Calls
import thc.runtime.Closure
import thc.runtime.Dispatch
import thc.runtime.DataLayout
import thc.runtime.RuntimeFault
import thc.runtime.Metrics
import org.graalvm.polyglot.Context
import org.graalvm.polyglot.PolyglotException
import org.graalvm.polyglot.Value
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.File

class RuntimeTest {
    private val root = File(System.getProperty("thc.projectRoot"))
    private val modules = listOf("THC.Prim", "THC.Fixtures").map { File(root, "build/core/$it.json").path }
    data class Example(val entry: String, val input: Long, val expected: Long)
    private fun oracle(): List<Example> = File(root, "build/native/oracle.tsv").readLines().filter { it.isNotBlank() }.map {
        val p = it.split('\t'); Example(p[0], p[1].toLong(), p[2].toLong())
    }
    @Suppress("UNCHECKED_CAST")
    private fun diagnostics(value: Value): Map<String, Any?> = Json.parse(value.getMember("diagnostics").asString()) as Map<String, Any?>
    private fun count(value: Value, key: String): Long = (diagnostics(value)[key] as Number).toLong()

    @Test fun exportedHaskellMatchesNativeGhcBeforeAndAfterGuestCompilation() {
        executionContext().use { context ->
            for ((entry, rows) in oracle().groupBy { it.entry }) {
                val fn = loadEntry(context, modules, entry)
                for (r in rows) assertEquals(r.expected, fn.execute(r.input).asLong(), "$entry(${r.input}) interpreted")
                repeat(40) { val r = rows[it % rows.size]; assertEquals(r.expected, fn.execute(r.input).asLong()) }
                assertTrue(fn.invokeMember("compile").asBoolean(), "Guest compilation: $entry")
                val before = count(fn, "compiledEntries")
                for (r in rows) assertEquals(r.expected, fn.execute(r.input).asLong(), "$entry(${r.input}) compiled")
                assertTrue(count(fn, "compiledEntries") > before, "Must execute installed guest code: $entry")
                println("VERIFIED_GUEST $entry ${Json.stringify(diagnostics(fn))}")
            }
        }
    }

    @Test fun sharingPapAndOverapplicationHaveObservableRuntimeCoverage() {
        executionContext().use { context ->
            val shared = loadEntry(context, modules, "shared")
            assertEquals(120L, shared.execute(7L).asLong())
            // costlyBox's shared x is entered once; a second field demand hits its update.
            assertTrue(count(shared, "thunkEvaluations") > 0)
            assertTrue(count(shared, "thunkHits") > 0)
            val entries = diagnostics(shared)["thunkEvaluationsByLabel"] as Map<*, *>
            assertEquals(1L, (entries["x"] as Number).toLong(), "Shared Core binding x entered exactly once")
            val under = loadEntry(context, modules, "under")
            assertEquals(14L, under.execute(7L).asLong())
            assertTrue(count(under, "papAllocations") > 0)
            val underUpdates = count(under, "thunkEvaluations")
            assertEquals(15L, under.execute(8L).asLong())
            assertEquals(underUpdates, count(under, "thunkEvaluations"),
                "GHC-known WHNF constructors and PAPs need no per-call update thunk")
            val over = loadEntry(context, modules, "over")
            assertEquals(13L, over.execute(0L).asLong())
            assertEquals(42L, over.execute(7L).asLong())
        }
    }

    @Test fun unusedBottomRemainsLazyAndDemandedBottomBlackholes() {
        executionContext().use { context ->
            for (entry in listOf("lazyArgument", "lazyField")) {
                val fn = loadEntry(context, modules, entry)
                assertEquals(123L, fn.execute(123L).asLong())
                assertEquals(0L, count(fn, "blackholes"))
            }
            val bottom = loadEntry(context, modules, "blackhole")
            val error = assertThrows(PolyglotException::class.java) { bottom.execute(0L) }
            assertTrue(error.message.orEmpty().contains("Blackhole"), error.message)
            assertEquals(1L, count(bottom, "blackholes"))
            val repeated = assertThrows(PolyglotException::class.java) { bottom.execute(0L) }
            assertTrue(repeated.message.orEmpty().contains("Blackhole"))
            assertEquals(1L, count(bottom, "blackholes"), "Failed thunk rethrows its memoized failure")
        }
    }

    @Test fun cadenzaTailLoopsAndProductiveCafUseBoundedHostStack() {
        executionContext().use { context ->
            val sum = loadEntry(context, modules, "sumLoop")
            assertEquals(5_000_050_000L, sum.execute(100_000L).asLong())
            assertTrue(count(sum, "tailBounces") >= 100_000L)
            val cyclic = loadEntry(context, modules, "recursiveCaf")
            assertEquals(100_000L, cyclic.execute(100_000L).asLong())
            val list = loadEntry(context, modules, "caseList")
            assertEquals(50_005_000L, list.execute(10_000L).asLong())
        }
    }

    @Test fun boundedDispatchCacheSurvivesMoreThanThreeTargets() {
        executionContext().use { context ->
            val fn = loadEntry(context, modules, "cacheSaturation")
            val rows = oracle().filter { it.entry == "cacheSaturation" }
            assertTrue(rows.isNotEmpty())
            repeat(4) { for (r in rows) assertEquals(r.expected, fn.execute(r.input).asLong()) }
            assertTrue(count(fn, "indirectCalls") > 0, "Fixture must reach megamorphic fallback")
            fn.invokeMember("compile")
            for (r in rows) assertEquals(r.expected, fn.execute(r.input).asLong())
            val mutual = loadEntry(context, modules, "mutualTail")
            assertEquals(100_000L, mutual.execute(100_000L).asLong())
            val mixed = loadEntry(context, modules, "selfMutualTail")
            assertEquals(100_000L, mixed.execute(100_000L).asLong())
        }
    }

    @Test fun selfTailCallsRefreshChangingPrimitiveCapturesAndReplayOriginalCaf() {
        executionContext().use { context ->
            val fn = loadEntry(context, modules, "capturedChangingEnv")
            assertEquals(10_017L, fn.execute(10_000L).asLong())
            assertTrue(fn.invokeMember("compile").asBoolean())
            val before = count(fn, "tailBounces")
            assertEquals(100_017L, fn.execute(100_000L).asLong())
            assertTrue(count(fn, "tailBounces") - before >= 100_000L)
            // The same lambda body entered 100k different offset environments.
            // A fresh host call must still begin at the original captured offset.
            val warmedUpdates = count(fn, "thunkEvaluations")
            assertEquals(24L, fn.execute(7L).asLong())
            assertEquals(17L, fn.execute(0L).asLong())
            assertEquals(10_017L, fn.execute(10_000L).asLong())
            assertEquals(warmedUpdates, count(fn, "thunkEvaluations"),
                "Safe constructor arguments need no update thunk on warmed continuation steps")
            assertEquals(0L, count(fn, "blackholes"))
        }
    }

    @Test fun recursiveCapturedCellsAndEscapedThunksKeepTheirOwnLexicalValues() {
        executionContext().use { context ->
            val mutual = loadEntry(context, modules, "localMutualClosures")
            assertEquals(20_040L, mutual.execute(10_000L).asLong())
            assertTrue(mutual.invokeMember("compile").asBoolean())
            // Distinct invocations allocate distinct recursive knots and base captures.
            for (input in listOf(10_001L, -5L, 0L, 10_000L)) {
                assertEquals(2L * input + 40L, mutual.execute(input).asLong())
            }
            val escaped = loadEntry(context, modules, "nestedCaptureThunk")
            repeat(20) { escaped.execute(it.toLong()).asLong() }
            assertTrue(escaped.invokeMember("compile").asBoolean())
            fun labelCount(label: String): Long =
                ((diagnostics(escaped)["thunkEvaluationsByLabel"] as Map<*, *>)[label] as? Number)?.toLong() ?: 0L
            val outerBefore = labelCount("outer")
            val middleBefore = labelCount("middle")
            // escapingFactory has returned before outer is forced; middle crosses
            // another closure boundary with a separate lexical capture.
            // Inputs outside signed32 expose accidental reuse of Cadenza's Int storage.
            val inputs = listOf(3_000_000_000L, -3_000_000_000L, 7L, 0L, 13L, -5L, 7L)
            for (input in inputs) {
                assertEquals(input * input + 23L, escaped.execute(input).asLong())
            }
            assertEquals(inputs.size.toLong(), labelCount("outer") - outerBefore)
            assertEquals(inputs.size.toLong(), labelCount("middle") - middleBefore)
            assertEquals(0L, count(escaped, "blackholes"))
        }
    }

    /** Raw roots isolate packet/PAP semantics; these are not Haskell-oracle rows. */
    private class ApplicationDriver(argumentCount: Int, metrics: Metrics) : RootNode(null) {
        @field:com.oracle.truffle.api.nodes.Node.Child
        private var dispatch = Dispatch.create(argumentCount, false, metrics)
        @Suppress("UNCHECKED_CAST")
        override fun execute(frame: VirtualFrame): Any? = dispatch.execute(
            frame, frame.arguments[0] as Closure, frame.arguments[1] as Array<Any?>)
        fun apply(function: Closure, vararg arguments: Any?): Any? =
            Calls.target(callTarget, arrayOf(function, arguments))
    }

    private fun decimalTarget(): RootCallTarget = object : RootNode(null) {
        override fun execute(frame: VirtualFrame): Any {
            var result = 0L
            for (index in 1 until frame.arguments.size) result = result * 10L + (frame.arguments[index] as Long)
            return result
        }
    }.callTarget

    @Test fun papPrefixesSurviveRepeatedUnderapplicationAndMegamorphicOverapplication() {
        val metrics = Metrics(true)
        val one = ApplicationDriver(1, metrics)
        val two = ApplicationDriver(2, metrics)
        val original = Closure(null, emptyArray(), 4, decimalTarget())
        val first = one.apply(original, 1L) as Closure
        val second = one.apply(first, 2L) as Closure
        assertEquals(1_234L, two.apply(second, 3L, 4L))
        assertEquals(1_289L, two.apply(second, 8L, 9L), "PAP prefixes remain reusable")
        assertTrue(metrics.papAllocations >= 2L)

        fun overapplied(consumed: Int, suppliedCount: Int): Closure {
            val resultTarget = decimalTarget()
            val target = object : RootNode(null) {
                override fun execute(frame: VirtualFrame): Any {
                    var prefix = 0L
                    for (index in 1 until frame.arguments.size) prefix = prefix * 10L + (frame.arguments[index] as Long)
                    return Closure(null, arrayOf(prefix), suppliedCount - consumed, resultTarget)
                }
            }.callTarget
            return Closure(null, emptyArray(), consumed, target)
        }
        // Both the first function and its returned function carry existing prefixes.
        val initialPap = one.apply(overapplied(2, 4), 1L) as Closure
        assertEquals(1_234L, ApplicationDriver(3, metrics).apply(initialPap, 2L, 3L, 4L))

        // Saturate the independent overapplication cache, then revisit every shape.
        val six = ApplicationDriver(6, metrics)
        val functions = (1..5).map { overapplied(it, 6) }
        for (function in functions + functions.reversed()) {
            assertEquals(123_456L, six.apply(function, 1L, 2L, 3L, 4L, 5L, 6L))
        }
        assertTrue(metrics.indirectCalls > 0L, "Overapplication must reach its generic fallback")
    }

    @Test fun constructorLayoutsPreservePrimitiveWidthObjectIdentityAndVoidSlots() {
        executionContext().use { context ->
            context.initialize("thc")
            context.enter()
            try {
                val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                val mixed = DataLayout(language, "Synthetic.Mixed", "Mixed",
                    arrayOf("VoidRep", "IntRep", "LiftedRep"))
                val marker = Any()
                val builder = FrameDescriptor.newBuilder()
                val numberSlot = builder.addSlot(FrameSlotKind.Illegal, "number", null)
                val objectSlot = builder.addSlot(FrameSlotKind.Illegal, "object", null)
                val frame = Truffle.getRuntime().createVirtualFrame(emptyArray(), builder.build())
                for (number in listOf(3_000_000_000L, -3_000_000_000L, Long.MIN_VALUE, Long.MAX_VALUE)) {
                    val value = mixed.create(arrayOf(Unit, number, marker))
                    assertSame(Unit, mixed.read(value, 0), "Zero-width Core slots retain their logical index")
                    assertEquals(number, mixed.readLong(value, 1))
                    assertSame(marker, mixed.read(value, 2), "Lifted fields retain lazy payload identity")
                    mixed.restore(value, 1, frame, numberSlot)
                    mixed.restore(value, 2, frame, objectSlot)
                    assertTrue(frame.isLong(numberSlot), "Case restoration must preserve primitive Long slots")
                    assertEquals(number, frame.getLong(numberSlot))
                    assertSame(marker, frame.getObject(objectSlot))
                }
                val reference = DataLayout(language, "Synthetic.Reference", "Reference", arrayOf("LiftedRep"))
                val objectValue = reference.create(arrayOf(marker))
                assertSame(marker, reference.read(objectValue, 0))
                assertThrows(RuntimeFault::class.java) { reference.readLong(objectValue, 0) }
                assertThrows(RuntimeFault::class.java) { mixed.create(arrayOf(Unit, 3, marker)) }
            } finally { context.leave() }
        }
    }

    @Test fun constructorLayoutsRejectCrossConstructorReadsAndShareNullaryValues() {
        executionContext().use { context ->
            context.initialize("thc")
            context.enter()
            try {
                val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                // Equal field counts and names cannot make two constructor identities equal.
                val first = DataLayout(language, "Synthetic.First", "Choice", arrayOf("IntRep"))
                val second = DataLayout(language, "Synthetic.Second", "Choice", arrayOf("IntRep"))
                val value = first.create(arrayOf(3_000_000_000L))
                assertNotSame(first, second)
                assertThrows(RuntimeFault::class.java) { second.read(value, 0) }
                assertThrows(RuntimeFault::class.java) { second.readLong(value, 0) }
                val nil = DataLayout(language, "Synthetic.Nil", "Nil", emptyArray())
                val end = DataLayout(language, "Synthetic.End", "End", emptyArray())
                assertSame(nil.create(emptyArray()), nil.create(emptyArray()), "Nullary constructors are per-layout singletons")
                assertNotSame(nil.create(emptyArray()), end.create(emptyArray()))
            } finally { context.leave() }
        }
    }

    @Test fun constructorCasesDistinguishEqualArityAlternativesBeforeAndAfterCompilation() {
        fun variable(id: String): List<Any?> = listOf("var", id)
        fun integer(number: Long): List<Any?> = listOf("lit", "int", number.toString())
        fun primitive(name: String, first: List<Any?>, second: List<Any?>): List<Any?> =
            listOf("app", listOf("prim", name), listOf(first, second), listOf(false, false))
        fun constructor(id: String): Map<String, Any?> = mapOf(
            "id" to id, "name" to id.substringAfterLast('.'), "arity" to 1,
            "tag" to if (id.endsWith("LeftChoice")) 1 else 2, "kind" to "boxed",
            "strictFields" to listOf(false), "fieldLifted" to listOf(false),
            "fieldReps" to listOf(listOf("IntRep")))
        fun binder(id: String) = mapOf("id" to id, "name" to id, "type" to "Int#", "lifted" to false)
        fun constructed(id: String): List<Any?> =
            listOf("app", listOf("con", id, 1), listOf(variable("n")), listOf(false), true)
        val left = "Synthetic.LeftChoice"
        val right = "Synthetic.RightChoice"
        val chooseBody = listOf("case", primitive("<=#", variable("n"), integer(0)), "test", listOf(
            listOf("default", null, emptyList<String>(), constructed(right)),
            listOf("lit", listOf("int", "1"), emptyList<String>(), constructed(left))))
        val choose = mapOf("id" to "choose", "name" to "choose", "arity" to 1, "lifted" to true,
            "expr" to listOf("lam", listOf(binder("n")), chooseBody))
        val entryBody = listOf("case",
            listOf("app", variable("choose"), listOf(variable("input")), listOf(false)), "chosen", listOf(
                listOf("data", left, listOf("leftField"), primitive("+#", variable("leftField"), integer(101))),
                listOf("data", right, listOf("rightField"), primitive("-#", variable("rightField"), integer(202)))))
        val entry = mapOf("id" to "entry", "name" to "entry", "arity" to 1, "lifted" to true,
            "expr" to listOf("lam", listOf(binder("input")), entryBody))
        val request = Json.stringify(mapOf("entry" to "entry", "modules" to listOf(mapOf(
            "schema" to 1, "ghc" to "9.14.1", "module" to "Synthetic",
            "constructors" to listOf(constructor(left), constructor(right)), "bindings" to listOf(choose, entry)))))
        executionContext().use { context ->
            val function = context.eval("thc", request)
            val inputs = listOf(-3_000_000_000L, 3_000_000_000L, 0L, -1L, 1L)
            fun check() {
                for (input in inputs) assertEquals(if (input <= 0) input + 101 else input - 202,
                    function.execute(input).asLong())
            }
            check()
            repeat(8) { check() }
            assertTrue(function.invokeMember("compile").asBoolean())
            check()
        }
    }

    @Test fun repeatedHostEntryKeepsGuestAndInteropCompilable() {
        executionContext().use { context ->
            val fn = loadEntry(context, modules, "under", instrument = false)
            repeat(40) { fn.execute(100L + (it and 15)).asLong() }
            assertTrue(fn.invokeMember("compile").asBoolean())
            repeat(20_000) {
                val input = 100L + (it and 15)
                assertEquals(input + 7L, fn.execute(input).asLong())
            }
        }
    }

    @Test fun recursiveAliasStaysLazyUntilDemanded() {
        val binder = mapOf("id" to "x", "name" to "x", "lifted" to true, "arity" to 0,
            "expr" to listOf("var", "x"))
        fun request(body: List<Any?>): String = Json.stringify(mapOf("entry" to "entry", "modules" to listOf(mapOf(
            "schema" to 1, "ghc" to "9.14.1", "module" to "Synthetic", "constructors" to emptyList<Any>(),
            "bindings" to listOf(mapOf("id" to "entry", "name" to "entry", "arity" to 0, "lifted" to true,
                "expr" to listOf("let", true, listOf(binder), body)))))))
        executionContext().use { context ->
            val unused = context.eval("thc", request(listOf("lit", "int", "42")))
            assertEquals(42L, unused.execute().asLong())
            val used = context.eval("thc", request(listOf("var", "x")))
            val error = assertThrows(PolyglotException::class.java) { used.execute() }
            assertTrue(error.message.orEmpty().contains("Blackhole"), error.message)
        }
    }

    @Test fun hostKernelAbiRejectsNonIntegralArgumentsAndWrongArity() {
        executionContext().use { context ->
            val fn = loadEntry(context, modules, "sumLoop")
            assertEquals(6L, fn.execute(3).asLong())
            assertThrows(PolyglotException::class.java) { fn.execute(1.5) }
            assertThrows(PolyglotException::class.java) { fn.execute() }
        }
    }

    @Test fun transportRejectsAmbiguityAndRoundTripsControlCharacters() {
        assertThrows(IllegalArgumentException::class.java) { Json.parse("{\"x\":1,\"x\":2}") }
        assertThrows(IllegalArgumentException::class.java) { Json.parse("[01]") }
        val value = mapOf("text" to "\"hello\"\n\\\t\u0000λ", "values" to listOf(1L, true, null))
        assertEquals(value, Json.parse(Json.stringify(value)))
    }
}
