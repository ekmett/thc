// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.staticobject.StaticShape;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;
import thc.Language;
import static org.junit.jupiter.api.Assertions.*;

class StaticShapeSafetyTest {
    @FunctionalInterface
    private interface Action {
        void accept(Language language) throws Exception;
    }
    private void configuration(String strategy, boolean unchecked, boolean force, Action action) throws Exception {
        var previous = System.getProperty(Frames.STATIC_SHAPE_UNCHECKED_PROPERTY);
        try {
            if (unchecked)
                System.setProperty(Frames.STATIC_SHAPE_UNCHECKED_PROPERTY, "true");
            else
                System.clearProperty(Frames.STATIC_SHAPE_UNCHECKED_PROPERTY);
            try (var context = Context.newBuilder("thc")
                     .allowExperimentalOptions(true)
                     .option("engine.StaticObjectStorageStrategy", strategy)
                     .option("engine.ForceStaticObjectSafetyChecks", Boolean.toString(force))
                     .option("engine.BackgroundCompilation", "false")
                     .build()) {
                context.initialize("thc");
                context.enter();
                try {
                    action.accept(TruffleLanguage.LanguageReference.create(Language.class).get(null));
                } finally {
                    context.leave();
                }
            }
        } finally {
            if (previous == null)
                System.clearProperty(Frames.STATIC_SHAPE_UNCHECKED_PROPERTY);
            else
                System.setProperty(Frames.STATIC_SHAPE_UNCHECKED_PROPERTY, previous);
        }
    }
    private boolean safetyChecks(Object layout) throws Exception {
        var shapeField = layout.getClass().getDeclaredField("shape");
        shapeField.setAccessible(true);
        var flag = StaticShape.class.getDeclaredField("safetyChecks");
        flag.setAccessible(true);
        return flag.getBoolean(shapeField.get(layout));
    }
    @Test
    void ownedStorageRejectsForgedKeysAndCrossLayoutAccessInBothStrategies() throws Exception {
        for (var strategy : List.of("field-based", "array-based"))
            for (boolean unchecked : new boolean[] {false, true}) {
                configuration(strategy, unchecked, false, language -> {
                    var box = new DataLayout(language, "Box", "Box", new String[] {"IntRep"});
                    var otherBox = new DataLayout(language, "Other", "Other", new String[] {"IntRep"});
                    var value = box.create(new Object[] {Long.MIN_VALUE});
                    var otherValue = otherBox.create(new Object[] {Long.MAX_VALUE});
                    var nil = new DataLayout(language, "Nil", "Nil", new String[0]);
                    assertSame(nil.create(new Object[0]), nil.create(new Object[0]));
                    assertEquals(Long.MIN_VALUE, box.readLong(value, 0));
                    assertEquals(!unchecked, safetyChecks(box));
                    var captures = new CaptureLayout(language, new boolean[] {false, true}, new boolean[] {false, true},
                        new Class<?>[] {DataValue.class, null});
                    var otherCaptures = new CaptureLayout(language, new boolean[] {false, true},
                        new boolean[] {false, true}, new Class<?>[] {DataValue.class, null});
                    var environment = captures.captureValues(new Object[] {value, Long.MIN_VALUE});
                    var otherEnvironment = otherCaptures.captureValues(new Object[] {otherValue, Long.MAX_VALUE});
                    assertSame(value, environment.getObject(0));
                    assertEquals(Long.MIN_VALUE, environment.getLong(1));
                    assertEquals(!unchecked, safetyChecks(captures));
                    var dataConstructors = new ArrayList<Integer>();
                    for (var constructor : DataValue.class.getConstructors())
                        dataConstructors.add(constructor.getParameterCount());
                    var frameConstructors = new ArrayList<Integer>();
                    for (var constructor : CapturedFrame.class.getConstructors())
                        frameConstructors.add(constructor.getParameterCount());
                    assertEquals(List.of(2), dataConstructors);
                    assertEquals(List.of(2), frameConstructors);
                    if (strategy.equals("array-based")) {
                        if (value instanceof LayoutDataValue)
                            assertSame(value.getClass(), otherValue.getClass(),
                                "Layout validation must work even when constructors share their carrier class");
                        else {
                            assertTrue(otherValue instanceof LayoutDataValue);
                            assertNotSame(value.getClass(), otherValue.getClass());
                        }
                        assertSame(environment.getClass(), otherEnvironment.getClass());
                    }
                    for (var fake : Arrays.asList(null, new Object(), box, value, captures, environment)) {
                        assertThrows(RuntimeFault.class, () -> new DataValue(box, fake));
                        assertThrows(RuntimeFault.class, () -> new CapturedFrame(captures, fake));
                        assertThrows(RuntimeFault.class, () -> new DataValue(box, fake) {});
                        assertThrows(RuntimeFault.class, () -> new CapturedFrame(captures, fake) {});
                    }
                    // A caller can build another public factory, but cannot authorize
                    // its foreign shape to claim either of our private layout links.
                    var foreignData = StaticShape.newBuilder(language).build(DataValue.class, DataValueFactory.class);
                    var foreignCapture =
                        StaticShape.newBuilder(language).build(CapturedFrame.class, CapturedFrameFactory.class);
                    assertThrows(RuntimeFault.class, () -> foreignData.getFactory().create(box, new Object()));
                    assertThrows(RuntimeFault.class, () -> foreignCapture.getFactory().create(captures, new Object()));
                    var frameLayout = new FrameLayout();
                    int[] slots = {frameLayout.bind("reference"), frameLayout.bind("primitive")};
                    var frame = Truffle.getRuntime().createVirtualFrame(new Object[0], frameLayout.build());
                    FrameAccess.write(frame, slots[0], value);
                    FrameAccess.writeLong(frame, slots[1], Long.MAX_VALUE);
                    var fromFrame = captures.capture(frame, slots);
                    captures.restore(fromFrame, 0, frame, slots[0]);
                    captures.restore(fromFrame, 1, frame, slots[1]);
                    assertSame(value, FrameAccess.read(frame, slots[0]));
                    assertEquals(Long.MAX_VALUE, frame.getLong(slots[1]));
                    assertThrows(RuntimeFault.class, () -> otherBox.read(value, 0));
                    assertThrows(RuntimeFault.class, () -> otherBox.readLong(value, 0));
                    assertThrows(RuntimeFault.class, () -> otherBox.restore(value, 0, frame, slots[1]));
                    assertThrows(RuntimeFault.class, () -> otherBox.initializeLong(value, 0, 7L));
                    assertThrows(RuntimeFault.class, () -> otherBox.initialize(value, 0, 7L));
                    assertThrows(RuntimeFault.class, () -> otherBox.describe(value));
                    assertEquals(
                        Long.MIN_VALUE, box.readLong(value, 0), "Rejected writes must leave real storage intact");
                    assertThrows(RuntimeFault.class, () -> otherCaptures.read(environment, 0));
                    assertThrows(RuntimeFault.class, () -> otherCaptures.readObject(environment, 0));
                    assertThrows(RuntimeFault.class, () -> otherCaptures.readLong(environment, 1));
                    assertThrows(RuntimeFault.class, () -> otherCaptures.isObject(environment, 0));
                    assertThrows(RuntimeFault.class, () -> otherCaptures.isLong(environment, 1));
                    assertThrows(RuntimeFault.class, () -> otherCaptures.restore(environment, 0, frame, slots[0]));
                    for (int index : new int[] {-1, 2}) {
                        assertThrows(RuntimeFault.class, () -> captures.read(environment, index));
                        assertThrows(RuntimeFault.class, () -> captures.readObject(environment, index));
                        assertThrows(RuntimeFault.class, () -> captures.readLong(environment, index));
                        assertThrows(RuntimeFault.class, () -> captures.isObject(environment, index));
                        assertThrows(RuntimeFault.class, () -> captures.isLong(environment, index));
                        assertThrows(RuntimeFault.class, () -> captures.restore(environment, index, frame, slots[0]));
                    }
                    for (int index : new int[] {-1, 1}) {
                        assertThrows(RuntimeFault.class, () -> box.read(value, index));
                        assertThrows(RuntimeFault.class, () -> box.readLong(value, index));
                        assertThrows(RuntimeFault.class, () -> box.initialize(value, index, 7L));
                    }
                    var typed = new DataLayout(
                        language, "Typed", "Typed", new String[] {"LiftedRep"}, new Class<?>[] {DataValue.class});
                    assertSame(value, typed.read(typed.create(new Object[] {value}), 0));
                    assertThrows(IllegalArgumentException.class, () -> typed.create(new Object[] {new Object()}));
                    assertThrows(
                        IllegalArgumentException.class, () -> captures.captureValues(new Object[] {new Object(), 7L}));
                    FrameAccess.write(frame, slots[0], new Object());
                    assertThrows(IllegalArgumentException.class, () -> captures.capture(frame, slots));
                    assertThrows(RuntimeFault.class, () -> box.create(new Object[] {"not a Long"}));
                    assertThrows(RuntimeFault.class, () -> captures.captureValues(new Object[] {value, "not a Long"}));
                });
            }
    }
    @Test
    void engineCanForceChecksDespiteThePerShapeExperiment() throws Exception {
        for (var strategy : List.of("field-based", "array-based"))
            configuration(strategy, true, true, language -> {
                var box = new DataLayout(language, "Box", "Box", new String[] {"IntRep"});
                var captures = new CaptureLayout(language, new boolean[] {true}, new boolean[] {true});
                assertTrue(safetyChecks(box));
                assertTrue(safetyChecks(captures));
                assertEquals(Long.MAX_VALUE, box.readLong(box.create(new Object[] {Long.MAX_VALUE}), 0));
                assertEquals(Long.MIN_VALUE, captures.captureValues(new Object[] {Long.MIN_VALUE}).getLong(0));
            });
    }
}
