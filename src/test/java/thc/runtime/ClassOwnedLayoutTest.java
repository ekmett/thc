// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.RootNode;
import com.oracle.truffle.api.staticobject.StaticShape;
import java.lang.reflect.Modifier;
import java.util.*;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;
import thc.Language;
import static org.junit.jupiter.api.Assertions.*;
import static thc.runtime.RepresentationTestSupport.*;

class ClassOwnedLayoutTest {
    private void options(boolean owned, CheckedRunnable action) throws Exception { options(owned, false, action); }
    private void options(boolean owned, boolean unchecked, CheckedRunnable action) throws Exception {
        var settings = Map.of(ClassOwnedLayoutsKt.CLASS_OWNED_LAYOUTS_PROPERTY, Boolean.toString(owned), FramesKt.STATIC_SHAPE_UNCHECKED_PROPERTY, Boolean.toString(unchecked));
        var previous = new HashMap<String, String>(); settings.forEach((key, value) -> previous.put(key, System.getProperty(key)));
        try { settings.forEach(System::setProperty); action.run(); }
        finally { previous.forEach((key, value) -> { if (value == null) System.clearProperty(key); else System.setProperty(key, value); }); }
    }
    private void context(String strategy, CheckedConsumer<Language> action) throws Exception {
        try (var context = Context.newBuilder("thc").allowExperimentalOptions(true).option("engine.StaticObjectStorageStrategy", strategy)
                .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false").option("engine.CompilationFailureAction", "Throw").build()) {
            context.initialize("thc"); context.enter();
            try { action.accept(TruffleLanguage.LanguageReference.create(Language.class).get(null)); } finally { context.leave(); }
        }
    }
    private void compile(RootCallTarget target) throws Exception {
        var optimized = Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget"); optimized.getMethod("compile", boolean.class).invoke(target, true);
        assertEquals(true, optimized.getMethod("isValidLastTier").invoke(target));
    }
    private static final class ReadRoot extends RootNode {
        private final DataLayout layout;
        ReadRoot(DataLayout layout) { super(null); this.layout = layout; }
        @Override public Object execute(VirtualFrame frame) { return layout.matches(frame.getArguments()[0]) ? layout.readLong((DataValue) frame.getArguments()[0], 0) : -17L; }
    }
    @Test void defaultUsesClassOwnershipAndExplicitFalseKeepsLayoutCarriers() throws Exception {
        var previous = System.getProperty(ClassOwnedLayoutsKt.CLASS_OWNED_LAYOUTS_PROPERTY);
        try {
            System.clearProperty(ClassOwnedLayoutsKt.CLASS_OWNED_LAYOUTS_PROPERTY);
            context("field-based", language -> {
                var owned = new DataLayout(language, "DefaultOwner", "DefaultOwner", new String[]{"IntRep"}); var first = owned.create(new Object[]{Long.MIN_VALUE});
                assertFalse(first instanceof LayoutDataValue); assertSame(owned, first.getLayout());
                options(false, () -> {
                    var fallback = new DataLayout(language, "ExplicitFallback", "ExplicitFallback", new String[]{"IntRep"}); var second = fallback.create(new Object[]{Long.MAX_VALUE});
                    assertTrue(second instanceof LayoutDataValue); assertSame(fallback, second.getLayout()); assertFalse(owned.matches(second)); assertFalse(fallback.matches(first));
                    assertEquals(Long.MIN_VALUE, owned.readLong(first, 0)); assertEquals(Long.MAX_VALUE, fallback.readLong(second, 0));
                });
            });
        } finally { if (previous == null) System.clearProperty(ClassOwnedLayoutsKt.CLASS_OWNED_LAYOUTS_PROPERTY); else System.setProperty(ClassOwnedLayoutsKt.CLASS_OWNED_LAYOUTS_PROPERTY, previous); }
    }
    @Test void fieldlessOwnersAndArrayFallbackKeepOldCompiledValuesValid() throws Exception {
        options(true, () -> {
            assertTrue(Arrays.stream(DataValue.class.getDeclaredFields()).noneMatch(f -> !Modifier.isStatic(f.getModifiers())), "The pointer-free base must not retain a per-instance layout or ownership token");
            assertEquals(list(DataLayout.class), Arrays.stream(LayoutDataValue.class.getDeclaredFields()).filter(f -> !Modifier.isStatic(f.getModifiers())).map(java.lang.reflect.Field::getType).toList());
            for (var strategy : list("field-based", "array-based")) context(strategy, language -> {
                var first = new DataLayout(language, "First", "First", new String[]{"IntRep"}); var a = first.create(new Object[]{Long.MIN_VALUE});
                assertFalse(a instanceof LayoutDataValue); assertSame(first, a.getLayout()); var target = new ReadRoot(first).getCallTarget();
                for (int i = 0; i < 30; i++) assertEquals(Long.MIN_VALUE, Calls.target(target, new Object[]{a})); compile(target);
                var second = new DataLayout(language, "Second", "Second", new String[]{"IntRep"}); var b = second.create(new Object[]{Long.MAX_VALUE});
                var third = new DataLayout(language, "Third", "Third", new String[]{"IntRep"}); var c = third.create(new Object[]{3_000_000_017L});
                if (strategy.equals("array-based")) {
                    assertTrue(b instanceof LayoutDataValue); assertTrue(c instanceof LayoutDataValue); assertNotSame(a.getClass(), b.getClass());
                    assertSame(b.getClass(), c.getClass(), "Shared fallback classes still discriminate by each value's layout");
                } else { assertFalse(b instanceof LayoutDataValue); assertFalse(c instanceof LayoutDataValue); }
                assertEquals(Long.MIN_VALUE, Calls.target(target, new Object[]{a})); assertEquals(-17L, Calls.target(target, new Object[]{b})); assertEquals(-17L, Calls.target(target, new Object[]{c}));
                assertSame(first, a.getLayout()); assertSame(second, b.getLayout()); assertSame(third, c.getLayout()); assertEquals("First[" + Long.MIN_VALUE + "]", a.toString());
                assertThrows(RuntimeFault.class, () -> second.readLong(a, 0)); assertThrows(RuntimeFault.class, () -> first.initializeLong(b, 0, 0L));
                for (var foreign : list(null, new Object(), 1L, first)) assertFalse(first.matches(foreign)); assertEquals(Long.MAX_VALUE, second.readLong(b, 0));
            });
        });
    }
    private record Retained(DataLayout first, DataValue value) {}
    @Test void offModeAndClosedContextsCannotReassignAnExistingOwner() throws Exception {
        var saved = new Retained[1];
        options(true, () -> {
            context("array-based", language -> {
                var first = new DataLayout(language, "Survivor", "Survivor", new String[]{"IntRep"}); var a = first.create(new Object[]{Long.MIN_VALUE}); saved[0] = new Retained(first, a);
                options(false, () -> {
                    var off = new DataLayout(language, "Off", "Off", new String[]{"IntRep"}); var b = off.create(new Object[]{12L});
                    assertTrue(b instanceof LayoutDataValue); assertFalse(first.matches(b)); assertFalse(off.matches(a)); assertSame(first, a.getLayout());
                });
            });
            // Either cross-context carrier policy must preserve permanent ownership.
            context("array-based", language -> {
                var first = saved[0].first(); var a = saved[0].value(); var other = new DataLayout(language, "Later", "Later", new String[]{"IntRep"}); var b = other.create(new Object[]{Long.MAX_VALUE});
                assertSame(first, a.getLayout()); assertEquals(Long.MIN_VALUE, first.readLong(a, 0)); assertFalse(first.matches(b)); assertFalse(other.matches(a)); assertSame(other, b.getLayout());
            });
        });
    }
    private static final class ReservationCarrier {}
    @Test void classReservationIsPermanentAndDescriptorPublicationCannotChangeItsOwner() throws Exception {
        var token = new Object(); var competitor = new Object();
        assertTrue(ClassOwnedLayouts.reserve(ReservationCarrier.class, token)); assertFalse(ClassOwnedLayouts.reserve(ReservationCarrier.class, competitor));
        assertThrows(RuntimeFault.class, () -> ClassOwnedLayouts.resolve(ReservationCarrier.class));
        options(false, () -> context("field-based", language -> {
            var owner = new DataLayout(language, "Owner", "Owner", new String[0]); var other = new DataLayout(language, "Other", "Other", new String[0]);
            ClassOwnedLayouts.publish(ReservationCarrier.class, token, owner); assertSame(owner, ClassOwnedLayouts.resolve(ReservationCarrier.class));
            assertThrows(RuntimeFault.class, () -> ClassOwnedLayouts.publish(ReservationCarrier.class, competitor, other));
            assertThrows(RuntimeFault.class, () -> ClassOwnedLayouts.publish(ReservationCarrier.class, token, other));
            assertFalse(ClassOwnedLayouts.reserve(ReservationCarrier.class, competitor)); assertSame(owner, ClassOwnedLayouts.resolve(ReservationCarrier.class));
        }));
    }
    private static final class AnswerRoot extends RootNode {
        int evaluations;
        AnswerRoot() { super(null); }
        @Override public Object execute(VirtualFrame frame) { evaluations++; return Long.MIN_VALUE; }
    }
    private static final class ForceRoot extends RootNode {
        @Child private Force force = new Force(new Metrics(false));
        ForceRoot() { super(null); }
        @Override public Object execute(VirtualFrame frame) { return force.execute(frame, frame.getArguments()[0]); }
    }
    @Test void ownershipChecksPreservePreciseFieldsLazyFieldsAndAllocationAuthentication() throws Exception {
        for (var strategy : list("field-based", "array-based")) for (boolean unchecked : new boolean[]{false, true}) options(true, unchecked, () -> context(strategy, language -> {
            var scalar = new DataLayout(language, "Scalar", "Scalar", new String[]{"IntRep"}); var a = scalar.create(new Object[]{Long.MAX_VALUE});
            var typed = new DataLayout(language, "Typed", "Typed", new String[]{"LiftedRep"}, new Class<?>[]{DataValue.class});
            assertSame(a, typed.read(typed.create(new Object[]{a}), 0)); assertNull(typed.read(typed.create(new Object[]{null}), 0)); assertThrows(IllegalArgumentException.class, () -> typed.create(new Object[]{new Object()}));
            var lazy = new DataLayout(language, "Lazy", "Lazy", new String[]{"LiftedRep"}); var answer = new AnswerRoot(); var thunk = new Thunk(answer.getCallTarget(), null); var cell = lazy.create(new Object[]{thunk});
            assertEquals(0, answer.evaluations); assertSame(thunk, lazy.read(cell, 0)); cell.toString(); assertEquals(0, answer.evaluations);
            var force = new ForceRoot().getCallTarget(); for (int i = 0; i < 2; i++) assertEquals(Long.MIN_VALUE, Calls.target(force, new Object[]{lazy.read(cell, 0)}));
            assertEquals(1, answer.evaluations); assertNull(thunk.getTarget());
            for (var fake : list(null, new Object(), scalar, a)) {
                assertThrows(RuntimeFault.class, () -> new DataValue(scalar, fake)); assertThrows(RuntimeFault.class, () -> new LayoutDataValue(scalar, fake));
                assertThrows(RuntimeFault.class, () -> new DataValue(scalar, fake) {});
            }
            var foreign = StaticShape.newBuilder(language).build(DataValue.class, DataValueFactory.class);
            assertThrows(RuntimeFault.class, () -> foreign.getFactory().create(scalar, new Object()));
            for (int index : new int[]{-1, 1}) assertThrows(RuntimeFault.class, () -> scalar.read(a, index)); assertEquals(Long.MAX_VALUE, scalar.readLong(a, 0));
        }));
    }
}
