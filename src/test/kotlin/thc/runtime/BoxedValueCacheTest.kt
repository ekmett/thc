@file:Suppress("UNCHECKED_CAST")
package thc.runtime

import com.oracle.truffle.api.RootCallTarget
import com.oracle.truffle.api.TruffleLanguage
import org.graalvm.polyglot.Context
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import thc.Json
import thc.Language
import java.io.File

class BoxedValueCacheTest {
    private data class Builtin(val id: String, val name: String, val rep: String, val minimum: Long, val maximum: Long)
    private val builtins = listOf(
        Builtin(BOXED_INT_CONSTRUCTOR_ID, "I#", "IntRep", -16, 255),
        Builtin(BOXED_CHAR_CONSTRUCTOR_ID, "C#", "WordRep", 0, 255))

    private fun property(name: String, value: String?, action: () -> Unit) {
        val before = System.getProperty(name)
        try {
            if (value == null) System.clearProperty(name) else System.setProperty(name, value)
            action()
        } finally {
            if (before == null) System.clearProperty(name) else System.setProperty(name, before)
        }
    }
    private fun cache(enabled: Boolean, action: () -> Unit) =
        property(BOXED_VALUE_CACHE_PROPERTY, if (enabled) "true" else null, action)

    private fun context(strategy: String, action: (Language) -> Unit) {
        Context.newBuilder("thc").allowExperimentalOptions(true)
            .option("engine.StaticObjectStorageStrategy", strategy)
            .option("engine.BackgroundCompilation", "false")
            .option("engine.MultiTier", "false")
            .option("engine.CompilationFailureAction", "Throw")
            .build().use { context ->
                context.initialize("thc"); context.enter()
                try { action(TruffleLanguage.LanguageReference.create(Language::class.java).get(null)) }
                finally { context.leave() }
            }
    }
    private fun layout(language: Language, builtin: Builtin) =
        DataLayout(language, builtin.id, builtin.name, arrayOf(builtin.rep))
    private fun compile(target: RootCallTarget) {
        val type = Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget")
        type.getMethod("compile", Boolean::class.javaPrimitiveType).invoke(target, true)
        assertEquals(true, type.getMethod("isValidLastTier").invoke(target))
    }
    private fun exported(path: String, id: String): Map<String, Any?> {
        val module = Json.parse(File(System.getProperty("thc.projectRoot"), path).readText()) as Map<String, Any?>
        return (module["constructors"] as List<Map<String, Any?>>).single { it["id"] == id }
    }
    private fun metadata(builtin: Builtin): Map<String, Any?> =
        exported("build/core/CbvAudit.json", builtin.id)

    @Test fun wiredConstructorIdentitiesAndPrimitiveRepresentationsMatchActualPinnedExports() {
        for (builtin in builtins) {
            val constructor = metadata(builtin)
            assertEquals(builtin.name, constructor["name"])
            assertEquals(1, (constructor["arity"] as Number).toInt())
            assertEquals("boxed", constructor["kind"])
            assertEquals(listOf(listOf(builtin.rep)), constructor["fieldReps"])
            assertEquals(listOf(false), constructor["fieldLifted"])
        }
    }

    @Test fun exactGhcRangesReuseImmutableValuesWhileEveryOtherPayloadStaysFullWidth() = cache(true) {
        for (strategy in listOf("field-based", "array-based")) context(strategy) { language ->
            for (builtin in builtins) {
                val layout = layout(language, builtin)
                assertTrue(layout.hasBoxedValueCache)
                val cached = (builtin.minimum..builtin.maximum).map { n -> layout.createLong(n) }
                for ((index, n) in (builtin.minimum..builtin.maximum).withIndex()) {
                    assertSame(cached[index], layout.create(arrayOf(n)), "$strategy ${builtin.name} $n")
                    assertEquals(n, layout.readLong(cached[index], 0))
                    if (index > 0) assertNotSame(cached[index - 1], cached[index])
                }
                // This includes the full Unicode range and machine-width values; C#
                // retains the old raw WordRep semantics even for an invalid codepoint.
                for (n in listOf(builtin.minimum - 1, builtin.maximum + 1, 0x10000L, 0x10ffffL,
                        3_000_000_017L, Long.MIN_VALUE, Long.MAX_VALUE)) {
                    val first = layout.createLong(n)
                    assertNotSame(first, layout.create(arrayOf(n)))
                    assertEquals(n, layout.readLong(first, 0))
                }
                assertThrows(RuntimeFault::class.java) { layout.create(arrayOf(1)) }
                assertThrows(RuntimeFault::class.java) { layout.create(emptyArray()) }
                // Fresh allocation is deliberately separate from cache lookup: callers
                // that initialize a final field must never receive an existing flyweight.
                val fresh = layout.allocate()
                layout.initializeLong(fresh, 0, 7)
                assertNotSame(fresh, layout.createLong(7))
                assertEquals(7L, layout.readLong(fresh, 0))
            }
        }
    }

    @Test fun defaultOffAndNonBuiltinConstructorsKeepOrdinaryAllocation() = cache(false) {
        for (strategy in listOf("field-based", "array-based")) context(strategy) { language ->
            for (builtin in builtins) {
                val off = layout(language, builtin)
                assertFalse(off.hasBoxedValueCache)
                assertNotSame(off.createLong(7), off.createLong(7))
                cache(true) {
                    // Options are fixed at layout construction, never polled in hot code.
                    assertFalse(off.hasBoxedValueCache)
                    assertNotSame(off.createLong(7), off.createLong(7))
                    val lookalikes = listOf(
                        DataLayout(language, "user:${builtin.id}", builtin.name, arrayOf(builtin.rep)),
                        DataLayout(language, builtin.id, "Other", arrayOf(builtin.rep)),
                        DataLayout(language, builtin.id, builtin.name, arrayOf("Int64Rep")),
                        DataLayout(language, builtin.id, builtin.name, arrayOf("LiftedRep")))
                    for (other in lookalikes) {
                        assertFalse(other.hasBoxedValueCache)
                        assertNotSame(other.create(arrayOf(7L)), other.create(arrayOf(7L)))
                    }
                }
            }
        }
    }

    @Test fun cachesRemainOwnedByTheirLayoutAcrossSharedArrayCarriersAndContexts() = cache(true) {
        property(CONSTRUCTOR_CLASS_IDENTITY_PROPERTY, "true") {
            for (strategy in listOf("field-based", "array-based")) {
                lateinit var first: DataLayout
                lateinit var value: DataValue
                context(strategy) { language ->
                    first = layout(language, builtins[0]); value = first.createLong(7)
                    val second = layout(language, builtins[0])
                    val other = second.createLong(7)
                    assertNotSame(value, other)
                    assertTrue(first.matches(value)); assertFalse(first.matches(other))
                    assertTrue(second.matches(other)); assertFalse(second.matches(value))
                    assertThrows(RuntimeFault::class.java) { second.readLong(value, 0) }
                    if (strategy == "array-based") assertSame(value.javaClass, other.javaClass)
                }
                context(strategy) { language ->
                    val another = layout(language, builtins[0])
                    val other = another.createLong(7)
                    assertNotSame(value, other)
                    assertFalse(first.matches(other)); assertFalse(another.matches(value))
                    assertEquals(7L, first.readLong(value, 0))
                    assertEquals(7L, another.readLong(other, 0))
                }
            }
        }
    }

    private fun module(builtin: Builtin): Map<String, Any?> {
        val proof = mapOf("kind" to "long", "primReps" to listOf(builtin.rep), "evaluated" to true)
        val parameter = mapOf("id" to "x", "name" to "x", "lifted" to false, "coercion" to false, "rep" to proof)
        // The primitive operation ensures the field expression still executes before
        // lookup, and exercises the primitive AST constructor path as well as BC create.
        val operand = listOf("app", listOf("prim", "+#"),
            listOf(listOf("var", "x"), listOf("lit", "int", "1")), listOf(false, false), true, true)
        val construct = listOf("app", listOf("con", builtin.id, 1), listOf(operand), listOf(false), true, true)
        return mapOf("instrument" to true, "constructors" to listOf(metadata(builtin)), "bindings" to listOf(
            mapOf("id" to "make", "name" to "make", "lifted" to true,
                "expr" to listOf("lam", listOf(parameter), construct))))
    }

    @Test fun bothBackendsSwitchBetweenCachedAndFullWidthConstructorsAfterCompilation() {
        for (enabled in listOf(false, true)) cache(enabled) {
            for (strategy in listOf("field-based", "array-based")) context(strategy) { language ->
                for (backend in listOf("ast", "bytecode")) for (builtin in builtins) {
                    val program = if (backend == "ast") Program(language, module(builtin)) else BytecodeProgram(language, module(builtin))
                    fun call(n: Long): DataValue = Calls.target(program.hostEntryTarget(1),
                        arrayOf(program.entryValue("make"), arrayOf<Any?>(n))) as DataValue
                    val anchor = call(6)
                    repeat(30) { assertEquals((it + 1).toLong(), call(it.toLong()).let { it.layout.readLong(it, 0) }) }
                    compile(program.entryTarget("make"))
                    fun check(n: Long) {
                        val value = call(n)
                        assertEquals(n + 1, value.layout.readLong(value, 0), "$enabled $strategy $backend ${builtin.name}")
                    }
                    for (n in listOf(builtin.minimum - 2, builtin.minimum - 1, builtin.maximum - 1, builtin.maximum,
                            0x10ffffL - 1, 3_000_000_017L, Long.MIN_VALUE, Long.MAX_VALUE)) check(n)
                    compile(program.entryTarget("make"))
                    check(Long.MAX_VALUE - 1)
                    if (enabled) assertSame(anchor, call(6)) else assertNotSame(anchor, call(6))
                    assertEquals(7L, anchor.layout.readLong(anchor, 0), "Cold large writes must not mutate cached values")
                }
            }
        }
    }
}
