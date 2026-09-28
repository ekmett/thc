// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.RootNode;
import java.util.*;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;
import thc.Language;
import static org.junit.jupiter.api.Assertions.*;
import static thc.runtime.RepresentationTestSupport.*;

class ConstructorClassIdentityTest {
    private void matching(boolean enabled, CheckedRunnable action) throws Exception {
        var previous = System.getProperty(ConstructorClassIdentityKt.CONSTRUCTOR_CLASS_IDENTITY_PROPERTY);
        try {
            if (enabled) System.setProperty(ConstructorClassIdentityKt.CONSTRUCTOR_CLASS_IDENTITY_PROPERTY, "true"); else System.clearProperty(ConstructorClassIdentityKt.CONSTRUCTOR_CLASS_IDENTITY_PROPERTY);
            action.run();
        } finally {
            if (previous == null) System.clearProperty(ConstructorClassIdentityKt.CONSTRUCTOR_CLASS_IDENTITY_PROPERTY); else System.setProperty(ConstructorClassIdentityKt.CONSTRUCTOR_CLASS_IDENTITY_PROPERTY, previous);
        }
    }
    private void context(String strategy, CheckedConsumer<Language> action) throws Exception {
        try (var context = Context.newBuilder("thc").allowExperimentalOptions(true).option("engine.StaticObjectStorageStrategy", strategy)
                .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false").option("engine.CompilationFailureAction", "Throw").build()) {
            context.initialize("thc"); context.enter();
            try { action.accept(TruffleLanguage.LanguageReference.create(Language.class).get(null)); } finally { context.leave(); }
        }
    }
    private boolean valid(RootCallTarget target) throws Exception { return Boolean.TRUE.equals(Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget").getMethod("isValidLastTier").invoke(target)); }
    private void compile(RootCallTarget target) throws Exception { Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget").getMethod("compile", boolean.class).invoke(target, true); assertTrue(valid(target)); }
    private ConstructorClassIdentity proof(DataLayout layout) throws Exception { var field = layout.getClass().getDeclaredField("constructorClass"); field.setAccessible(true); return (ConstructorClassIdentity) field.get(layout); }
    @Test void matchingDistinguishesConstructorsAndRejectsForeignValuesInBothStrategies() throws Exception {
        for (var strategy : list("field-based", "array-based")) for (boolean enabled : new boolean[]{false, true}) matching(enabled, () -> context(strategy, language -> {
            var first = new DataLayout(language, "First", "SameName", new String[]{"IntRep"}); var second = new DataLayout(language, "Second", "SameName", new String[]{"IntRep"});
            var a = first.create(new Object[]{Long.MIN_VALUE}); var b = second.create(new Object[]{Long.MAX_VALUE});
            assertTrue(first.matches(a)); assertTrue(second.matches(b)); assertFalse(first.matches(b)); assertFalse(second.matches(a));
            for (var foreign : list(null, new Object(), first, Long.MIN_VALUE, "First")) { assertFalse(first.matches(foreign)); assertFalse(second.matches(foreign)); }
            assertThrows(RuntimeFault.class, () -> new DataValue(first, new Object()));
            if (strategy.equals("array-based")) {
                if (a instanceof LayoutDataValue) { assertSame(a.getClass(), b.getClass()); assertFalse(proof(first).isExclusive()); assertFalse(proof(second).isExclusive()); }
                else { assertTrue(b instanceof LayoutDataValue); assertNotSame(a.getClass(), b.getClass()); assertTrue(proof(first).isExclusive()); assertTrue(proof(second).isExclusive()); }
            }
        }));
    }
    private static final class MatchRoot extends RootNode {
        private final DataLayout layout;
        MatchRoot(DataLayout layout) { super(null); this.layout = layout; }
        @Override public Object execute(VirtualFrame frame) { return layout.matches(frame.getArguments()[0]); }
        @Override public String getName() { return "constructor class match"; }
    }
    private boolean matches(RootCallTarget target, Object value) { return (Boolean) Calls.target(target, new Object[]{value}); }
    @Test void compiledMatchTracksPermanentOrInvalidatableClassOwnership() throws Exception {
        matching(true, () -> context("array-based", language -> {
            var first = new DataLayout(language, "First", "First", new String[]{"IntRep"}); var a = first.create(new Object[]{3_000_000_017L}); assertTrue(proof(first).isExclusive());
            var target = new MatchRoot(first).getCallTarget();
            for (int i = 0; i < 30; i++) { assertTrue(matches(target, a)); assertFalse(matches(target, null)); assertFalse(matches(target, "foreign")); }
            compile(target); assertTrue(matches(target, a)); assertTrue(valid(target));
            // An off-mode registration still participates before publishing a value.
            var secondSlot = new DataLayout[1]; matching(false, () -> secondSlot[0] = new DataLayout(language, "Second", "Second", new String[]{"IntRep"}));
            var second = secondSlot[0]; var b = second.create(new Object[]{Long.MAX_VALUE});
            if (a instanceof LayoutDataValue) {
                assertFalse(valid(target), "Compiled shared-class matching must depend on exclusive ownership"); assertFalse(proof(first).isExclusive()); assertFalse(proof(second).isExclusive()); assertSame(a.getClass(), b.getClass());
            } else {
                assertTrue(valid(target), "A permanent owner keeps its class when a second layout falls back"); assertTrue(proof(first).isExclusive()); assertTrue(proof(second).isExclusive()); assertTrue(b instanceof LayoutDataValue); assertNotSame(a.getClass(), b.getClass());
            }
            assertFalse(matches(target, b)); assertTrue(matches(target, a)); compile(target); assertFalse(matches(target, b)); assertTrue(matches(target, a));
            // Off-mode owners that predate an enabled layout also count.
            var third = new DataLayout(language, "Third", "Third", new String[]{"IntRep"}); var c = third.create(new Object[]{Long.MIN_VALUE});
            assertFalse(proof(third).isExclusive()); assertTrue(third.matches(c)); assertFalse(third.matches(a)); assertFalse(third.matches(b));
        }));
    }
    private static final class AcrossContexts {}
    private static final class UniformA {}
    private static final class UniformB {}
    private record Retained(DataLayout first, DataValue value, ConstructorClassIdentity proof) {}
    @Test void registryOwnershipSpansContextsAndUnexpectedFactoryClassesInvalidateBothOwners() throws Exception {
        matching(true, () -> {
            var saved = new Retained[1];
            context("array-based", language -> {
                var first = new DataLayout(language, "First", "SameName", new String[]{"IntRep"}); var a = first.create(new Object[]{Long.MIN_VALUE}); var shared = new ConstructorClassIdentity(AcrossContexts.class);
                saved[0] = new Retained(first, a, shared); assertTrue(shared.isExclusive());
            });
            context("array-based", language -> {
                var first = saved[0].first(); var a = saved[0].value(); var second = new DataLayout(language, "Second", "SameName", new String[]{"IntRep"}); var b = second.create(new Object[]{Long.MAX_VALUE});
                assertTrue(first.matches(a)); assertTrue(second.matches(b)); assertFalse(first.matches(b)); assertFalse(second.matches(a));
                // Explicit shared-class registration is independent of engine carrier choices.
                var secondShared = new ConstructorClassIdentity(AcrossContexts.class); assertFalse(saved[0].proof().isExclusive()); assertFalse(secondShared.isExclusive());
            });
            var uniform = new ConstructorClassIdentity(UniformA.class); var alternativeOwner = new ConstructorClassIdentity(UniformB.class);
            assertTrue(uniform.isExclusive()); assertTrue(alternativeOwner.isExclusive()); uniform.observe(UniformB.class);
            assertFalse(uniform.isExclusive(), "An alternate carrier invalidates the per-layout iff proof"); assertFalse(alternativeOwner.isExclusive(), "Register the alternate carrier before it can escape");
        });
    }
    private List<Object> variable(String id) { return list("var", id); }
    private List<Object> literal(long value) { return list("lit", "int", Long.toString(value)); }
    private Map<String, Object> binding(String id, List<Object> body) { return map("id", id, "name", id, "lifted", true, "expr", body); }
    private Object call(ExecutableProgram program, String name, Object... values) { return Calls.target(program.hostEntryTarget(values.length), new Object[]{program.entryValue(name), values}); }
    @Test void astAndBytecodeCaseLoweringUseTheSameMatchingSemantics() throws Exception {
        matching(true, () -> {
            var argument = map("id", "x", "name", "x", "lifted", true, "coercion", false);
            List<Object> make = list("lam", list(with(argument, "lifted", false)), list("app", list("con", "Box", 1), list(variable("x")), list(false), true, true));
            List<Object> select = list("lam", list(argument), list("case", variable("x"), "scrutinee", list(list("data", "Box", list("payload"), variable("payload")), list("data", "End", list(), literal(-1)))));
            var module = map("constructors", list(map("id", "Box", "name", "Box", "arity", 1, "kind", "boxed", "fieldReps", list(list("IntRep")), "fieldLifted", list(false), "strictFields", list(false)),
                map("id", "End", "name", "End", "arity", 0, "kind", "boxed", "fieldReps", list(), "fieldLifted", list(), "strictFields", list())),
                "bindings", list(binding("make", make), binding("end", list("con", "End", 0)), binding("select", select)));
            for (var strategy : list("field-based", "array-based")) context(strategy, language -> {
                for (var backend : list("ast", "bytecode")) {
                    ExecutableProgram program = backend.equals("ast") ? new Program(language, module) : new BytecodeProgram(language, module); var end = call(program, "end");
                    for (int i = 0; i < 30; i++) assertEquals((long) i, call(program, "select", call(program, "make", (long) i)));
                    compile(program.entryTarget("select"));
                    for (long value : new long[]{Long.MIN_VALUE, Long.MAX_VALUE, 3_000_000_017L}) assertEquals(value, call(program, "select", call(program, "make", value)), backend);
                    assertEquals(-1L, call(program, "select", end), backend + " cold constructor arm");
                }
            });
        });
    }
}
