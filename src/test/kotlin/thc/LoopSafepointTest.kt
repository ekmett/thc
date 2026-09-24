package thc

import com.oracle.truffle.api.TruffleLanguage
import com.oracle.truffle.api.ThreadLocalAction
import org.graalvm.polyglot.Context
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import thc.runtime.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Scalar self-tail loops have no guest allocation or explicit polling operation. */
class LoopSafepointTest {
    private fun variable(name: String): List<Any?> = listOf("var", name)
    private fun integer(value: Long): List<Any?> = listOf("lit", "int", value.toString())
    private fun primitive(name: String, vararg args: List<Any?>): List<Any?> =
        listOf("app", listOf("prim", name), args.toList(), List(args.size) { false })
    private fun module(): Map<String, Any?> {
        val remaining = variable("remaining")
        val total = variable("total")
        val next = primitive("-#", remaining, integer(1))
        val nextTotal = primitive("+#", total, remaining)
        val body = listOf("case", primitive("<=#", remaining, integer(0)), "condition", listOf(
            listOf("lit", listOf("int", "1"), emptyList<String>(), total),
            listOf("default", null, emptyList<String>(), listOf("app", variable("loop"),
                listOf(next, nextTotal), listOf(false, false)))))
        val entry = mapOf("id" to "loop", "name" to "loop", "type" to "Synthetic", "lifted" to true,
            "arity" to 2, "expr" to listOf("lam", listOf(
                mapOf("id" to "remaining", "name" to "remaining", "type" to "Int#", "lifted" to false, "coercion" to false),
                mapOf("id" to "total", "name" to "total", "type" to "Int#", "lifted" to false, "coercion" to false)), body))
        return mapOf("schema" to 1, "ghc" to "9.14.1", "module" to "Synthetic.LoopSafepoint",
            "instrument" to true, "bindings" to listOf(entry), "constructors" to emptyList<Any>())
    }

    private fun <T> entered(context: Context, action: () -> T): T {
        context.enter()
        try { return action() } finally { context.leave() }
    }

    private fun run(backend: String, compiled: Boolean) {
        val context = if (compiled) executionContext() else Context.newBuilder("thc")
            .allowExperimentalOptions(true).option("engine.Compilation", "false").build()
        try {
            context.initialize("thc")
            val (language, state) = entered(context) {
                TruffleLanguage.LanguageReference.create(Language::class.java).get(null) to
                    TruffleLanguage.ContextReference.create(Language::class.java).get(null)
            }
            if (compiled) Executors.newFixedThreadPool(2).use { pool ->
                val together = CountDownLatch(2)
                val depart = CountDownLatch(1)
                val entrants = List(2) { pool.submit {
                    entered(context) { together.countDown(); assertTrue(depart.await(5, TimeUnit.SECONDS)) }
                } }
                assertTrue(together.await(5, TimeUnit.SECONDS))
                depart.countDown()
                entrants.forEach { it.get(5, TimeUnit.SECONDS) }
                assertFalse(language.singleThreadedAssumption.isValid)
            }
            val program: ExecutableProgram = entered(context) {
                if (backend == "ast") Program(language, module()) else BytecodeProgram(language, module())
            }
            fun invoke(n: Long): Any? = Calls.target(program.hostEntryTarget(2),
                arrayOf(program.entryValue("loop"), arrayOf<Any?>(n, 0L)))
            entered(context) {
                repeat(8) { assertEquals(36L, invoke(8L)) }
                if (compiled) {
                    val entry = EntryValue(program, "loop", 2)
                    assertTrue(entry.invokeMember("compile", emptyArray()) as Boolean)
                }
            }
            val before = (program.diagnostics().getValue("selfTailReentries") as Number).toLong()
            val pool = Executors.newSingleThreadExecutor()
            try {
                val runnerThread = AtomicReference<Thread>()
                val stopped = pool.submit<Any?> { entered(context) {
                    runnerThread.set(Thread.currentThread())
                    try { invoke(Long.MAX_VALUE) } catch (failure: Throwable) { failure }
                } }
                if (backend == "ast") {
                    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                    while ((program.diagnostics().getValue("selfTailReentries") as Number).toLong() - before < 1_000L &&
                        !stopped.isDone && System.nanoTime() < deadline) Thread.sleep(1)
                    assertTrue((program.diagnostics().getValue("selfTailReentries") as Number).toLong() - before >= 1_000L,
                        "$backend/$compiled did not enter its scalar loop")
                } else Thread.sleep(50)
                if (stopped.isDone) fail<Any>("$backend/$compiled returned before asynchronous delivery: ${stopped.get()}")
                state.env.submitThreadLocal(arrayOf(runnerThread.get()), object : ThreadLocalAction(true, false) {
                    override fun perform(access: Access) { throw AsyncThunkUnwind("stop scalar loop") }
                })
                assertTrue(stopped.get(5, TimeUnit.SECONDS) is AsyncThunkUnwind,
                    "$backend/$compiled did not deliver the action at a loop safepoint")
            } finally {
                context.close(true)
                pool.shutdownNow()
                assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS), "$backend/$compiled did not stop after cancellation")
            }
            if (compiled) assertTrue((program.diagnostics().getValue("compiledEntries") as Number).toLong() > 0,
                "$backend must have executed installed guest code")
            else assertEquals(0L, program.diagnostics().getValue("compiledEntries"))
        } finally { context.close(true) }
    }

    @Test fun interpretedAstScalarLoopDelivers() = run("ast", false)
    @Test fun compiledAstScalarLoopDelivers() = run("ast", true)
    @Test fun interpretedBytecodeScalarLoopDelivers() = run("bytecode", false)
    @Test fun compiledBytecodeScalarLoopDelivers() = run("bytecode", true)
}
