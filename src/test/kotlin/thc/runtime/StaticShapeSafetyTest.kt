package thc.runtime

import com.oracle.truffle.api.Truffle
import com.oracle.truffle.api.TruffleLanguage
import com.oracle.truffle.api.staticobject.StaticShape
import org.graalvm.polyglot.Context
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import thc.Language

class StaticShapeSafetyTest {
    private fun configuration(strategy: String, unchecked: Boolean, force: Boolean = false,
                              action: (Language) -> Unit) {
        val previous = System.getProperty(STATIC_SHAPE_UNCHECKED_PROPERTY)
        try {
            if (unchecked) System.setProperty(STATIC_SHAPE_UNCHECKED_PROPERTY, "true")
            else System.clearProperty(STATIC_SHAPE_UNCHECKED_PROPERTY)
            Context.newBuilder("thc").allowExperimentalOptions(true)
                .option("engine.StaticObjectStorageStrategy", strategy)
                .option("engine.ForceStaticObjectSafetyChecks", force.toString())
                .option("engine.BackgroundCompilation", "false")
                .build().use { context ->
                    context.initialize("thc"); context.enter()
                    try { action(TruffleLanguage.LanguageReference.create(Language::class.java).get(null)) }
                    finally { context.leave() }
                }
        } finally {
            if (previous == null) System.clearProperty(STATIC_SHAPE_UNCHECKED_PROPERTY)
            else System.setProperty(STATIC_SHAPE_UNCHECKED_PROPERTY, previous)
        }
    }

    private fun safetyChecks(layout: Any): Boolean {
        val shapeField = layout.javaClass.getDeclaredField("shape").also { it.isAccessible = true }
        val flag = StaticShape::class.java.getDeclaredField("safetyChecks").also { it.isAccessible = true }
        return flag.getBoolean(shapeField.get(layout))
    }

    @Test fun ownedStorageRejectsForgedKeysAndCrossLayoutAccessInBothStrategies() {
        for (strategy in listOf("field-based", "array-based")) for (unchecked in listOf(false, true)) {
            configuration(strategy, unchecked) { language ->
                val box = DataLayout(language, "Box", "Box", arrayOf("IntRep"))
                val otherBox = DataLayout(language, "Other", "Other", arrayOf("IntRep"))
                val value = box.create(arrayOf(Long.MIN_VALUE))
                val otherValue = otherBox.create(arrayOf(Long.MAX_VALUE))
                val nil = DataLayout(language, "Nil", "Nil", emptyArray())
                assertSame(nil.create(emptyArray()), nil.create(emptyArray()))
                assertEquals(Long.MIN_VALUE, box.readLong(value, 0))
                assertEquals(!unchecked, safetyChecks(box))

                val captures = CaptureLayout(language, booleanArrayOf(false, true), booleanArrayOf(false, true),
                    arrayOf(DataValue::class.java, null))
                val otherCaptures = CaptureLayout(language, booleanArrayOf(false, true), booleanArrayOf(false, true),
                    arrayOf(DataValue::class.java, null))
                val environment = captures.captureValues(arrayOf(value, Long.MIN_VALUE))
                val otherEnvironment = otherCaptures.captureValues(arrayOf(otherValue, Long.MAX_VALUE))
                assertSame(value, environment.getObject(0))
                assertEquals(Long.MIN_VALUE, environment.getLong(1))
                assertEquals(!unchecked, safetyChecks(captures))
                assertEquals(listOf(2), DataValue::class.java.constructors.map { it.parameterCount })
                assertEquals(listOf(2), CapturedFrame::class.java.constructors.map { it.parameterCount })
                if (strategy == "array-based") {
                    if (value is LayoutDataValue) assertSame(value.javaClass, otherValue.javaClass,
                        "Layout validation must work even when constructors share their carrier class")
                    else {
                        assertTrue(otherValue is LayoutDataValue)
                        assertNotSame(value.javaClass, otherValue.javaClass)
                    }
                    assertSame(environment.javaClass, otherEnvironment.javaClass)
                }

                for (fake in listOf(null, Any(), box, value, captures, environment)) {
                    assertThrows(RuntimeFault::class.java) { DataValue(box, fake) }
                    assertThrows(RuntimeFault::class.java) { CapturedFrame(captures, fake) }
                    assertThrows(RuntimeFault::class.java) { object : DataValue(box, fake) {} }
                    assertThrows(RuntimeFault::class.java) { object : CapturedFrame(captures, fake) {} }
                }
                // A caller can build another public factory, but cannot authorize
                // its foreign shape to claim either of our private layout links.
                val foreignData = StaticShape.newBuilder(language).build(DataValue::class.java, DataValueFactory::class.java)
                val foreignCapture = StaticShape.newBuilder(language).build(CapturedFrame::class.java, CapturedFrameFactory::class.java)
                assertThrows(RuntimeFault::class.java) { foreignData.factory.create(box, Any()) }
                assertThrows(RuntimeFault::class.java) { foreignCapture.factory.create(captures, Any()) }

                val frameLayout = FrameLayout()
                val slots = intArrayOf(frameLayout.bind("reference"), frameLayout.bind("primitive"))
                val frame = Truffle.getRuntime().createVirtualFrame(emptyArray(), frameLayout.build())
                FrameAccess.write(frame, slots[0], value)
                FrameAccess.writeLong(frame, slots[1], Long.MAX_VALUE)
                val fromFrame = captures.capture(frame, slots)
                captures.restore(fromFrame, 0, frame, slots[0])
                captures.restore(fromFrame, 1, frame, slots[1])
                assertSame(value, FrameAccess.read(frame, slots[0]))
                assertEquals(Long.MAX_VALUE, frame.getLong(slots[1]))
                assertThrows(RuntimeFault::class.java) { otherBox.read(value, 0) }
                assertThrows(RuntimeFault::class.java) { otherBox.readLong(value, 0) }
                assertThrows(RuntimeFault::class.java) { otherBox.restore(value, 0, frame, slots[1]) }
                assertThrows(RuntimeFault::class.java) { otherBox.initializeLong(value, 0, 7L) }
                assertThrows(RuntimeFault::class.java) { otherBox.initialize(value, 0, 7L) }
                assertThrows(RuntimeFault::class.java) { otherBox.describe(value) }
                assertEquals(Long.MIN_VALUE, box.readLong(value, 0), "Rejected writes must leave real storage intact")
                assertThrows(RuntimeFault::class.java) { otherCaptures.read(environment, 0) }
                assertThrows(RuntimeFault::class.java) { otherCaptures.readObject(environment, 0) }
                assertThrows(RuntimeFault::class.java) { otherCaptures.readLong(environment, 1) }
                assertThrows(RuntimeFault::class.java) { otherCaptures.isObject(environment, 0) }
                assertThrows(RuntimeFault::class.java) { otherCaptures.isLong(environment, 1) }
                assertThrows(RuntimeFault::class.java) { otherCaptures.restore(environment, 0, frame, slots[0]) }
                for (index in listOf(-1, 2)) {
                    assertThrows(RuntimeFault::class.java) { captures.read(environment, index) }
                    assertThrows(RuntimeFault::class.java) { captures.readObject(environment, index) }
                    assertThrows(RuntimeFault::class.java) { captures.readLong(environment, index) }
                    assertThrows(RuntimeFault::class.java) { captures.isObject(environment, index) }
                    assertThrows(RuntimeFault::class.java) { captures.isLong(environment, index) }
                    assertThrows(RuntimeFault::class.java) { captures.restore(environment, index, frame, slots[0]) }
                }
                for (index in listOf(-1, 1)) {
                    assertThrows(RuntimeFault::class.java) { box.read(value, index) }
                    assertThrows(RuntimeFault::class.java) { box.readLong(value, index) }
                    assertThrows(RuntimeFault::class.java) { box.initialize(value, index, 7L) }
                }

                val typed = DataLayout(language, "Typed", "Typed", arrayOf("LiftedRep"), arrayOf(DataValue::class.java))
                assertSame(value, typed.read(typed.create(arrayOf(value)), 0))
                assertThrows(IllegalArgumentException::class.java) { typed.create(arrayOf(Any())) }
                assertThrows(IllegalArgumentException::class.java) { captures.captureValues(arrayOf(Any(), 7L)) }
                FrameAccess.write(frame, slots[0], Any())
                assertThrows(IllegalArgumentException::class.java) { captures.capture(frame, slots) }
                assertThrows(RuntimeFault::class.java) { box.create(arrayOf("not a Long")) }
                assertThrows(RuntimeFault::class.java) { captures.captureValues(arrayOf(value, "not a Long")) }
            }
        }
    }

    @Test fun engineCanForceChecksDespiteThePerShapeExperiment() {
        for (strategy in listOf("field-based", "array-based")) configuration(strategy, unchecked = true, force = true) { language ->
            val box = DataLayout(language, "Box", "Box", arrayOf("IntRep"))
            val captures = CaptureLayout(language, booleanArrayOf(true), booleanArrayOf(true))
            assertTrue(safetyChecks(box))
            assertTrue(safetyChecks(captures))
            assertEquals(Long.MAX_VALUE, box.readLong(box.create(arrayOf(Long.MAX_VALUE)), 0))
            assertEquals(Long.MIN_VALUE, captures.captureValues(arrayOf(Long.MIN_VALUE)).getLong(0))
        }
    }
}
