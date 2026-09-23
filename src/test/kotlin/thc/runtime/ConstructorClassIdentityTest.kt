package thc.runtime

import com.oracle.truffle.api.RootCallTarget
import com.oracle.truffle.api.TruffleLanguage
import com.oracle.truffle.api.frame.VirtualFrame
import com.oracle.truffle.api.nodes.RootNode
import org.graalvm.polyglot.Context
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import thc.Language

class ConstructorClassIdentityTest {
    private fun matching(enabled: Boolean, action: () -> Unit) {
        val previous = System.getProperty(CONSTRUCTOR_CLASS_IDENTITY_PROPERTY)
        try {
            if (enabled) System.setProperty(CONSTRUCTOR_CLASS_IDENTITY_PROPERTY, "true")
            else System.clearProperty(CONSTRUCTOR_CLASS_IDENTITY_PROPERTY)
            action()
        } finally {
            if (previous == null) System.clearProperty(CONSTRUCTOR_CLASS_IDENTITY_PROPERTY)
            else System.setProperty(CONSTRUCTOR_CLASS_IDENTITY_PROPERTY, previous)
        }
    }
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
    private fun valid(target: RootCallTarget): Boolean = Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget")
        .getMethod("isValidLastTier").invoke(target) == true
    private fun compile(target: RootCallTarget) {
        Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget")
            .getMethod("compile", Boolean::class.javaPrimitiveType).invoke(target, true)
        assertTrue(valid(target))
    }
    private fun proof(layout: DataLayout): ConstructorClassIdentity =
        layout.javaClass.getDeclaredField("constructorClass").also { it.isAccessible = true }
            .get(layout) as ConstructorClassIdentity

    @Test fun matchingDistinguishesConstructorsAndRejectsForeignValuesInBothStrategies() {
        for (strategy in listOf("field-based", "array-based")) for (enabled in listOf(false, true)) matching(enabled) {
            context(strategy) { language ->
                val first = DataLayout(language, "First", "SameName", arrayOf("IntRep"))
                val second = DataLayout(language, "Second", "SameName", arrayOf("IntRep"))
                val a = first.create(arrayOf(Long.MIN_VALUE))
                val b = second.create(arrayOf(Long.MAX_VALUE))
                assertTrue(first.matches(a)); assertTrue(second.matches(b))
                assertFalse(first.matches(b)); assertFalse(second.matches(a))
                for (foreign in listOf(null, Any(), first, Long.MIN_VALUE, "First")) {
                    assertFalse(first.matches(foreign)); assertFalse(second.matches(foreign))
                }
                assertThrows(RuntimeFault::class.java) { DataValue(first, Any()) }
                if (strategy == "array-based") {
                    assertSame(a.javaClass, b.javaClass)
                    assertFalse(proof(first).isExclusive()); assertFalse(proof(second).isExclusive())
                }
            }
        }
    }

    private class MatchRoot(private val layout: DataLayout) : RootNode(null) {
        override fun execute(frame: VirtualFrame): Any = layout.matches(frame.arguments[0])
        override fun getName(): String = "constructor class match"
    }

    @Test fun compiledMatchDeoptimizesBeforeASecondSharedClassOwnerPublishesValues() = matching(true) {
        context("array-based") { language ->
            val first = DataLayout(language, "First", "First", arrayOf("IntRep"))
            val a = first.create(arrayOf(3_000_000_017L))
            assertTrue(proof(first).isExclusive())
            val target = MatchRoot(first).callTarget
            fun matches(value: Any?) = Calls.target(target, arrayOf(value)) as Boolean
            repeat(30) { assertTrue(matches(a)); assertFalse(matches(null)); assertFalse(matches("foreign")) }
            compile(target)
            assertTrue(matches(a)); assertTrue(valid(target))

            // Registering a constructor with its option off must still invalidate
            // the earlier enabled proof, before even its first value is published.
            lateinit var second: DataLayout
            matching(false) { second = DataLayout(language, "Second", "Second", arrayOf("IntRep")) }
            assertFalse(valid(target), "Compiled class-only matching must depend on exclusive ownership")
            assertFalse(proof(first).isExclusive()); assertFalse(proof(second).isExclusive())
            val b = second.create(arrayOf(Long.MAX_VALUE))
            assertSame(a.javaClass, b.javaClass)
            assertFalse(matches(b)); assertTrue(matches(a))
            compile(target)
            assertFalse(matches(b)); assertTrue(matches(a))

            // Off-mode owners that predate an enabled layout also count.
            val third = DataLayout(language, "Third", "Third", arrayOf("IntRep"))
            val c = third.create(arrayOf(Long.MIN_VALUE))
            assertFalse(proof(third).isExclusive())
            assertTrue(third.matches(c)); assertFalse(third.matches(a)); assertFalse(third.matches(b))
        }
    }

    private class AcrossContexts
    private class UniformA
    private class UniformB

    @Test fun registryOwnershipSpansContextsAndUnexpectedFactoryClassesInvalidateBothOwners() = matching(true) {
        lateinit var first: DataLayout
        lateinit var a: DataValue
        lateinit var sharedClassProof: ConstructorClassIdentity
        context("array-based") { language ->
            first = DataLayout(language, "First", "SameName", arrayOf("IntRep"))
            a = first.create(arrayOf(Long.MIN_VALUE))
            sharedClassProof = ConstructorClassIdentity(AcrossContexts::class.java)
            assertTrue(sharedClassProof.isExclusive())
        }
        context("array-based") { language ->
            val second = DataLayout(language, "Second", "SameName", arrayOf("IntRep"))
            val b = second.create(arrayOf(Long.MAX_VALUE))
            assertTrue(first.matches(a)); assertTrue(second.matches(b))
            assertFalse(first.matches(b)); assertFalse(second.matches(a))
            // THC currently uses exclusive language instances, so the engine may
            // choose distinct carriers across contexts. The registry must also
            // handle a shared Java class without relying on that engine policy.
            val secondShared = ConstructorClassIdentity(AcrossContexts::class.java)
            assertFalse(sharedClassProof.isExclusive()); assertFalse(secondShared.isExclusive())
        }
        val uniform = ConstructorClassIdentity(UniformA::class.java)
        val alternativeOwner = ConstructorClassIdentity(UniformB::class.java)
        assertTrue(uniform.isExclusive()); assertTrue(alternativeOwner.isExclusive())
        uniform.observe(UniformB::class.java)
        assertFalse(uniform.isExclusive(), "An alternate carrier invalidates the per-layout iff proof")
        assertFalse(alternativeOwner.isExclusive(), "Register the alternate carrier before it can escape")
    }

    @Test fun astAndBytecodeCaseLoweringUseTheSameMatchingSemantics() = matching(true) {
        val argument = mapOf("id" to "x", "name" to "x", "lifted" to true, "coercion" to false)
        fun variable(id: String) = listOf("var", id)
        fun literal(value: Long) = listOf("lit", "int", value.toString())
        fun binding(id: String, body: List<Any?>) = mapOf("id" to id, "name" to id, "lifted" to true, "expr" to body)
        val make = listOf("lam", listOf(argument + ("lifted" to false)),
            listOf("app", listOf("con", "Box", 1), listOf(variable("x")), listOf(false), true, true))
        val select = listOf("lam", listOf(argument), listOf("case", variable("x"), "scrutinee", listOf(
            listOf("data", "Box", listOf("payload"), variable("payload")),
            listOf("data", "End", emptyList<String>(), literal(-1)))))
        val module = mapOf("constructors" to listOf(
            mapOf("id" to "Box", "name" to "Box", "arity" to 1, "kind" to "boxed", "fieldReps" to listOf(listOf("IntRep")),
                "fieldLifted" to listOf(false), "strictFields" to listOf(false)),
            mapOf("id" to "End", "name" to "End", "arity" to 0, "kind" to "boxed", "fieldReps" to emptyList<Any>(),
                "fieldLifted" to emptyList<Boolean>(), "strictFields" to emptyList<Boolean>())),
            "bindings" to listOf(binding("make", make), binding("end", listOf("con", "End", 0)), binding("select", select)))
        for (strategy in listOf("field-based", "array-based")) context(strategy) { language ->
            for (backend in listOf("ast", "bytecode")) {
                val program = if (backend == "ast") Program(language, module) else BytecodeProgram(language, module)
                fun call(name: String, vararg values: Any?): Any? =
                    Calls.target(program.hostEntryTarget(values.size), arrayOf(program.entryValue(name), arrayOf(*values)))
                val end = call("end")
                repeat(30) { assertEquals(it.toLong(), call("select", call("make", it.toLong()))) }
                compile(program.entryTarget("select"))
                for (value in listOf(Long.MIN_VALUE, Long.MAX_VALUE, 3_000_000_017L))
                    assertEquals(value, call("select", call("make", value)), backend)
                assertEquals(-1L, call("select", end), "$backend cold constructor arm")
            }
        }
    }
}
