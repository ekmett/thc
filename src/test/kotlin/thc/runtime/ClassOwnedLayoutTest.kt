package thc.runtime

import com.oracle.truffle.api.RootCallTarget
import com.oracle.truffle.api.TruffleLanguage
import com.oracle.truffle.api.frame.VirtualFrame
import com.oracle.truffle.api.nodes.RootNode
import com.oracle.truffle.api.staticobject.StaticShape
import org.graalvm.polyglot.Context
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import thc.Language
import java.lang.reflect.Modifier

class ClassOwnedLayoutTest {
    private fun options(owned: Boolean, unchecked: Boolean = false, action: () -> Unit) {
        val settings = mapOf(CLASS_OWNED_LAYOUTS_PROPERTY to owned.toString(),
            STATIC_SHAPE_UNCHECKED_PROPERTY to unchecked.toString())
        val previous = settings.mapValues { System.getProperty(it.key) }
        try {
            settings.forEach { (key, value) -> System.setProperty(key, value) }
            action()
        } finally {
            previous.forEach { (key, value) ->
                if (value == null) System.clearProperty(key) else System.setProperty(key, value)
            }
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

    private fun compile(target: RootCallTarget) {
        val optimized = Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget")
        optimized.getMethod("compile", Boolean::class.javaPrimitiveType).invoke(target, true)
        assertEquals(true, optimized.getMethod("isValidLastTier").invoke(target))
    }

    private class ReadRoot(private val layout: DataLayout) : RootNode(null) {
        override fun execute(frame: VirtualFrame): Any =
            if (layout.matches(frame.arguments[0])) layout.readLong(frame.arguments[0] as DataValue, 0) else -17L
    }

    @Test fun defaultUsesClassOwnershipAndExplicitFalseKeepsLayoutCarriers() {
        val previous = System.getProperty(CLASS_OWNED_LAYOUTS_PROPERTY)
        try {
            System.clearProperty(CLASS_OWNED_LAYOUTS_PROPERTY)
            context("field-based") { language ->
                val owned = DataLayout(language, "DefaultOwner", "DefaultOwner", arrayOf("IntRep"))
                val first = owned.create(arrayOf(Long.MIN_VALUE))
                assertFalse(first is LayoutDataValue)
                assertSame(owned, first.layout)
                options(false) {
                    val fallback = DataLayout(language, "ExplicitFallback", "ExplicitFallback", arrayOf("IntRep"))
                    val second = fallback.create(arrayOf(Long.MAX_VALUE))
                    assertTrue(second is LayoutDataValue)
                    assertSame(fallback, second.layout)
                    assertFalse(owned.matches(second)); assertFalse(fallback.matches(first))
                    assertEquals(Long.MIN_VALUE, owned.readLong(first, 0))
                    assertEquals(Long.MAX_VALUE, fallback.readLong(second, 0))
                }
            }
        } finally {
            if (previous == null) System.clearProperty(CLASS_OWNED_LAYOUTS_PROPERTY)
            else System.setProperty(CLASS_OWNED_LAYOUTS_PROPERTY, previous)
        }
    }

    @Test fun fieldlessOwnersAndArrayFallbackKeepOldCompiledValuesValid() = options(true) {
        assertTrue(DataValue::class.java.declaredFields.none { !Modifier.isStatic(it.modifiers) },
            "The pointer-free base must not retain a per-instance layout or ownership token")
        assertEquals(listOf(DataLayout::class.java), LayoutDataValue::class.java.declaredFields
            .filter { !Modifier.isStatic(it.modifiers) }.map { it.type })
        for (strategy in listOf("field-based", "array-based")) context(strategy) { language ->
            val first = DataLayout(language, "First", "First", arrayOf("IntRep"))
            val a = first.create(arrayOf(Long.MIN_VALUE))
            assertFalse(a is LayoutDataValue)
            assertSame(first, a.layout)
            val target = ReadRoot(first).callTarget
            repeat(30) { assertEquals(Long.MIN_VALUE, Calls.target(target, arrayOf(a))) }
            compile(target)
            val second = DataLayout(language, "Second", "Second", arrayOf("IntRep"))
            val b = second.create(arrayOf(Long.MAX_VALUE))
            val third = DataLayout(language, "Third", "Third", arrayOf("IntRep"))
            val c = third.create(arrayOf(3_000_000_017L))
            if (strategy == "array-based") {
                assertTrue(b is LayoutDataValue); assertTrue(c is LayoutDataValue)
                assertNotSame(a.javaClass, b.javaClass)
                assertSame(b.javaClass, c.javaClass,
                    "Shared fallback classes still discriminate by each value's layout")
            } else {
                assertFalse(b is LayoutDataValue); assertFalse(c is LayoutDataValue)
            }
            assertEquals(Long.MIN_VALUE, Calls.target(target, arrayOf(a)))
            assertEquals(-17L, Calls.target(target, arrayOf(b)))
            assertEquals(-17L, Calls.target(target, arrayOf(c)))
            assertSame(first, a.layout); assertSame(second, b.layout); assertSame(third, c.layout)
            assertEquals("First[${Long.MIN_VALUE}]", a.toString())
            assertThrows(RuntimeFault::class.java) { second.readLong(a, 0) }
            assertThrows(RuntimeFault::class.java) { first.initializeLong(b, 0, 0L) }
            for (foreign in listOf(null, Any(), 1L, first)) assertFalse(first.matches(foreign))
            assertEquals(Long.MAX_VALUE, second.readLong(b, 0))
        }
    }

    @Test fun offModeAndClosedContextsCannotReassignAnExistingOwner() {
        lateinit var first: DataLayout
        lateinit var a: DataValue
        options(true) {
            context("array-based") { language ->
                first = DataLayout(language, "Survivor", "Survivor", arrayOf("IntRep"))
                a = first.create(arrayOf(Long.MIN_VALUE))
                options(false) {
                    val off = DataLayout(language, "Off", "Off", arrayOf("IntRep"))
                    val b = off.create(arrayOf(12L))
                    assertTrue(b is LayoutDataValue)
                    assertFalse(first.matches(b)); assertFalse(off.matches(a))
                    assertSame(first, a.layout)
                }
            }
            // Language instances may get different generated classes across contexts;
            // either policy must preserve the old class's permanent owner.
            context("array-based") { language ->
                val other = DataLayout(language, "Later", "Later", arrayOf("IntRep"))
                val b = other.create(arrayOf(Long.MAX_VALUE))
                assertSame(first, a.layout)
                assertEquals(Long.MIN_VALUE, first.readLong(a, 0))
                assertFalse(first.matches(b)); assertFalse(other.matches(a))
                assertSame(other, b.layout)
            }
        }
    }

    private class ReservationCarrier
    @Test fun classReservationIsPermanentAndDescriptorPublicationCannotChangeItsOwner() {
        val token = Any()
        val competitor = Any()
        assertTrue(ClassOwnedLayouts.reserve(ReservationCarrier::class.java, token))
        assertFalse(ClassOwnedLayouts.reserve(ReservationCarrier::class.java, competitor))
        assertThrows(RuntimeFault::class.java) { ClassOwnedLayouts.resolve(ReservationCarrier::class.java) }
        options(false) {
            context("field-based") { language ->
                val owner = DataLayout(language, "Owner", "Owner", emptyArray())
                val other = DataLayout(language, "Other", "Other", emptyArray())
                ClassOwnedLayouts.publish(ReservationCarrier::class.java, token, owner)
                assertSame(owner, ClassOwnedLayouts.resolve(ReservationCarrier::class.java))
                assertThrows(RuntimeFault::class.java) {
                    ClassOwnedLayouts.publish(ReservationCarrier::class.java, competitor, other)
                }
                assertThrows(RuntimeFault::class.java) {
                    ClassOwnedLayouts.publish(ReservationCarrier::class.java, token, other)
                }
                assertFalse(ClassOwnedLayouts.reserve(ReservationCarrier::class.java, competitor))
                assertSame(owner, ClassOwnedLayouts.resolve(ReservationCarrier::class.java))
            }
        }
    }

    private class AnswerRoot : RootNode(null) {
        var evaluations = 0
        override fun execute(frame: VirtualFrame): Any { evaluations++; return Long.MIN_VALUE }
    }
    private class ForceRoot : RootNode(null) {
        @Child private var force = Force(Metrics(false))
        override fun execute(frame: VirtualFrame): Any? = force.execute(frame, frame.arguments[0])
    }

    @Test fun ownershipChecksPreservePreciseFieldsLazyFieldsAndAllocationAuthentication() {
        for (strategy in listOf("field-based", "array-based")) for (unchecked in listOf(false, true)) options(true, unchecked) {
            context(strategy) { language ->
                val scalar = DataLayout(language, "Scalar", "Scalar", arrayOf("IntRep"))
                val a = scalar.create(arrayOf(Long.MAX_VALUE))
                val typed = DataLayout(language, "Typed", "Typed", arrayOf("LiftedRep"), arrayOf(DataValue::class.java))
                assertSame(a, typed.read(typed.create(arrayOf(a)), 0))
                assertNull(typed.read(typed.create(arrayOf(null)), 0))
                assertThrows(IllegalArgumentException::class.java) { typed.create(arrayOf(Any())) }
                val lazy = DataLayout(language, "Lazy", "Lazy", arrayOf("LiftedRep"))
                val answer = AnswerRoot()
                val thunk = Thunk(answer.callTarget, null)
                val cell = lazy.create(arrayOf(thunk))
                assertEquals(0, answer.evaluations)
                assertSame(thunk, lazy.read(cell, 0))
                cell.toString()
                assertEquals(0, answer.evaluations)
                val force = ForceRoot().callTarget
                repeat(2) { assertEquals(Long.MIN_VALUE, Calls.target(force, arrayOf(lazy.read(cell, 0)))) }
                assertEquals(1, answer.evaluations)
                assertNull(thunk.target)
                for (fake in listOf(null, Any(), scalar, a)) {
                    assertThrows(RuntimeFault::class.java) { DataValue(scalar, fake) }
                    assertThrows(RuntimeFault::class.java) { LayoutDataValue(scalar, fake) }
                    assertThrows(RuntimeFault::class.java) { object : DataValue(scalar, fake) {} }
                }
                val foreign = StaticShape.newBuilder(language).build(DataValue::class.java, DataValueFactory::class.java)
                assertThrows(RuntimeFault::class.java) { foreign.factory.create(scalar, Any()) }
                for (index in listOf(-1, 1)) assertThrows(RuntimeFault::class.java) { scalar.read(a, index) }
                assertEquals(Long.MAX_VALUE, scalar.readLong(a, 0))
            }
        }
    }
}
