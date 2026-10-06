// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc;

import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.Truffle;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Engine;
import thc.runtime.*;
import static org.junit.jupiter.api.Assertions.*;
import static thc.CoreExecutionTestSupport.*;

/** Real lowerer ownership checks; auxiliary-cache persistence remains a separate acceptance. */
class ReusableProgramTest {
    private Map<String,Object> intrinsicCaseModule(Map<String,Object> metadata) {
        var data = map("kind", "data", "evaluated", true, "primReps", list("BoxedRep (Just Lifted)"));
        var word = map("kind", "long", "evaluated", true, "primReps", list("IntRep"));
        var closure = map("kind", "closure", "evaluated", true, "primReps", list("BoxedRep (Just Lifted)"));
        var selected = list("case", variable("x"), "input", list(
            list("lit", list("int", "0"), list(), list("con", "Zero", 0, map("rep", data))),
            list("default", null, list(), list("con", "One", 0, map("rep", data)))),
            with(metadata, "binder", wordParameter("input")));
        var body = list("case", selected, "selected", list(
            list("data", "Zero", list(), literal(47)), list("data", "One", list(), literal(-25))),
            map("rep", word, "binder", map("id", "selected", "rep", data)));
        var entry = binding("read", list("lam", list(wordParameter("x")), body,
            map("rep", closure, "resultRep", word)), true);
        entry.put("rep", closure); entry.put("arity", 1);
        var module = module(list(entry));
        var constructors = new ArrayList<Map<String,Object>>();
        for (String name : List.of("Zero", "One")) constructors.add(map("id", name, "name", name,
            "kind", "boxed", "arity", 0, "tag", constructors.size() + 1, "fieldReps", list(),
            "fieldTypes", list(), "fieldLifted", list(), "strictFields", list()));
        module.put("constructors", constructors);
        return module;
    }

    @Test @SuppressWarnings("unchecked")
    void intrinsicCaseResultSurvivesErasedOuterProofWithoutGuestTraining() throws Exception {
        var unknown = map("kind", "unknown", "evaluated", false);
        var data = map("kind", "data", "evaluated", true, "primReps", list("BoxedRep (Just Lifted)"));
        var module = intrinsicCaseModule(map("rep", unknown, "resultRep", data));
        try (var engine = Engine.newBuilder().allowExperimentalOptions(true).option("engine.BackgroundCompilation", "false")
                .option("engine.MultiTier", "false").option("engine.CompilationFailureAction", "Throw").build()) {
            Program.PreparedCode code;
            try (var preparation = Context.newBuilder("thc").engine(engine).build()) {
                preparation.initialize("thc"); preparation.enter();
                try { code = Program.prepareCode(TruffleLanguage.LanguageReference.create(Language.class).get(null), module, List.of("read")); }
                finally { preparation.leave(); }
            }
            var field = Program.PreparedCode.class.getDeclaredField("targets"); field.setAccessible(true);
            for (var target : (List<com.oracle.truffle.runtime.OptimizedCallTarget>)field.get(code)) {
                assertFalse(target.wasExecuted()); assertTrue(target.prepareForAOT()); target.compile(true); assertFalse(target.wasExecuted());
            }
            String previous = System.getProperty("thc.requireCompiledCode"); System.setProperty("thc.requireCompiledCode", "true");
            try {
                for (int load = 0; load < 2; load++) try (var context = Context.newBuilder("thc").engine(engine).build()) {
                    context.initialize("thc"); context.enter();
                    try {
                        for (int instance = 0; instance < 2; instance++) {
                            var program = code.newInstance(TruffleLanguage.LanguageReference.create(Language.class).get(null));
                            var entry = (Closure)program.entryValue("read");
                            assertEquals(47L, Calls.target(entry.target, new Object[]{0L, entry.environment, 0L}));
                            assertEquals(-25L, Calls.target(entry.target, new Object[]{0L, entry.environment, 1L}));
                            assertEquals(0, count(program, "loweredRootCount"));
                            code.requireInstalledCode();
                        }
                    } finally { context.leave(); }
                }
            } finally {
                if (previous == null) System.clearProperty("thc.requireCompiledCode"); else System.setProperty("thc.requireCompiledCode", previous);
            }
        }
        assertEquals("unknown", unknown.get("kind"), "Preparation must not rewrite the outer expression certificate");
    }

    @Test @SuppressWarnings("unchecked")
    void preparedNativeLabelsAndBigNatLiteralsBelongToTheInvokingLoad() throws Exception {
        var address = map("kind", "address", "evaluated", true, "primReps", list("AddrRep"));
        var bytes = map("kind", "object", "evaluated", true, "primReps", list("BoxedRep (Just Unlifted)"));
        var closure = map("kind", "closure", "evaluated", true, "primReps", list("BoxedRep (Just Lifted)"));
        var label = list("lit", "function-addr", "free", map("rep", address));
        var big = list("lit", "bignat", "18446744073709551617", map("rep", bytes));
        var bindings = new ArrayList<Map<String,Object>>();
        var labelBinding = binding("label", label, false); labelBinding.put("rep", address); bindings.add(labelBinding);
        var bigBinding = binding("big", big, false); bigBinding.put("rep", bytes); bindings.add(bigBinding);
        for (String name : List.of("globalLabel", "inlineLabel", "globalBig", "inlineBig", "nullAddress")) {
            boolean byteResult = name.endsWith("Big");
            Object body = switch (name) {
                case "globalLabel" -> list("var", "label", map("rep", address));
                case "inlineLabel" -> label;
                case "globalBig" -> list("var", "big", map("rep", bytes));
                case "inlineBig" -> big;
                default -> list("lit", "null-addr", "0", map("rep", address));
            };
            var binding = binding(name, list("lam", list(wordParameter("unused")), body,
                map("rep", closure, "resultRep", byteResult ? bytes : address)), true);
            binding.put("rep", closure); binding.put("arity", 1); bindings.add(binding);
        }
        try (var engine = Engine.newBuilder().allowExperimentalOptions(true).option("engine.BackgroundCompilation", "false")
                .option("engine.MultiTier", "false").option("engine.CompilationFailureAction", "Throw").build()) {
            Program.PreparedCode code;
            try (var preparation = Context.newBuilder("thc").engine(engine).allowExperimentalOptions(true).allowNativeAccess(true)
                    .option("thc.ByteArrayStorage", "native").build()) {
                preparation.initialize("thc"); preparation.enter();
                try { code = Program.prepareCode(TruffleLanguage.LanguageReference.create(Language.class).get(null), module(bindings),
                    List.of("globalLabel", "inlineLabel", "globalBig", "inlineBig", "nullAddress")); }
                finally { preparation.leave(); }
            }
            var field = Program.PreparedCode.class.getDeclaredField("targets"); field.setAccessible(true);
            for (var target : (List<com.oracle.truffle.runtime.OptimizedCallTarget>)field.get(code)) {
                assertFalse(target.wasExecuted()); assertTrue(target.prepareForAOT()); target.compile(true); assertFalse(target.wasExecuted());
            }
            code.requireInstalledCode();
            String previous = System.getProperty("thc.requireCompiledCode"); System.setProperty("thc.requireCompiledCode", "true");
            ManagedAddress previousLabel = null;
            Object previousBytes = null;
            try {
                for (int load = 0; load < 2; load++) try (var context = Context.newBuilder("thc").engine(engine).allowExperimentalOptions(true).allowNativeAccess(true)
                        .option("thc.ByteArrayStorage", load == 0 ? "heap" : "native").build()) {
                    context.initialize("thc"); context.enter();
                    try {
                        var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                        for (int instance = 0; instance < 2; instance++) {
                            var program = code.newInstance(language);
                            var results = new LinkedHashMap<String,Object>();
                            for (String name : List.of("globalLabel", "inlineLabel", "globalBig", "inlineBig", "nullAddress")) {
                                var function = (Closure)program.entryValue(name);
                                results.put(name, Calls.target(function.target, new Object[]{0L, function.environment, 0L}));
                            }
                            var currentLabel = (ManagedAddress)results.get("globalLabel");
                            currentLabel.finalizerFunction().requireOwner(Language.currentState().cbits());
                            assertTrue(currentLabel.sameLocation((ManagedAddress)results.get("inlineLabel")));
                            assertSame(ManagedAddress.nullAddress(), results.get("nullAddress"));
                            if (load != 0 && instance == 0) {
                                var oldLabel = previousLabel;
                                assertThrows(RuntimeFault.class, () -> oldLabel.finalizerFunction().requireOwner(Language.currentState().cbits()));
                            }
                            previousLabel = currentLabel;
                            var currentBytes = results.get("globalBig");
                            assertNotSame(previousBytes, currentBytes); assertNotSame(currentBytes, results.get("inlineBig"));
                            for (String name : List.of("globalBig", "inlineBig")) {
                                Object value = results.get(name);
                                assertEquals(16, ManagedByteArray.sizeGuest(value)); assertEquals(1L, ManagedByteArray.readIntGuest(value, 0));
                                assertEquals(1L, ManagedByteArray.readIntGuest(value, 1));
                                if (load == 0) assertInstanceOf(byte[].class, value);
                                else assertTrue(assertInstanceOf(ManagedAllocation.class, value).hasNativeStorage());
                            }
                            assertSame(currentBytes, ManagedByteArray.freezeGuest(currentBytes));
                            ManagedByteArray.writeIntGuest(ManagedByteArray.freezeGuest(currentBytes), 0, 7L);
                            assertEquals(1L, ManagedByteArray.readIntGuest(results.get("inlineBig"), 0));
                            var inlineFunction = (Closure) program.entryValue("inlineBig");
                            Object fresh = Calls.target(inlineFunction.target, new Object[]{0L, inlineFunction.environment, 0L});
                            assertNotSame(results.get("inlineBig"), fresh); assertEquals(1L, ManagedByteArray.readIntGuest(fresh, 0));
                            previousBytes = currentBytes;
                            assertEquals(0, count(program, "loweredRootCount")); assertTrue(count(program, "compiledEntries") >= 5);
                            code.requireInstalledCode();
                        }
                    } finally { context.leave(); }
                }
            } finally {
                if (previous == null) System.clearProperty("thc.requireCompiledCode"); else System.setProperty("thc.requireCompiledCode", previous);
            }
        }
    }

    @Test @SuppressWarnings("unchecked")
    void preparedEnumsAndArithmeticPayloadsUseTheInvokingInstance() throws Exception {
        var data = map("kind", "data", "evaluated", true, "primReps", list("BoxedRep (Just Lifted)"));
        var closure = map("kind", "closure", "evaluated", true, "primReps", list("BoxedRep (Just Lifted)"));
        var empty = map("kind", "unknown", "evaluated", true, "primReps", list(),
            "aggregate", "unboxed-tuple", "components", list());
        var family = map("typeConstructor", "Choice", "constructors", list("Zero", "One"));
        var constructors = new ArrayList<Map<String,Object>>();
        for (int i = 0; i < 2; i++) {
            String name = i == 0 ? "Zero" : "One";
            constructors.add(map("id", name, "name", name, "kind", "boxed", "arity", 0, "tag", i + 1,
                "enumFamily", family, "fieldReps", list(), "fieldTypes", list(), "fieldLifted", list(), "strictFields", list()));
        }
        constructors.add(map("id", "Tuple0", "name", "Tuple0", "kind", "unboxed-tuple", "arity", 0));
        String payload = "ghc-internal:GHC.Internal.Exception.Type.divZeroException";
        var selected = list("app", list("prim", "tagToEnum#"), list(variable("x")), list(false), false, false,
            map("rep", data, "enumFamily", family));
        var raised = list("app", list("prim", "raiseDivZero#"),
            list(list("con", "Tuple0", 0, map("rep", empty))), list(false), false, false, map("rep", data));
        var bindings = new ArrayList<Map<String,Object>>();
        bindings.add(binding(payload, list("con", "Zero", 0, map("rep", data)), true));
        for (var name : List.of("select", "raise")) {
            var entry = binding(name, list("lam", list(wordParameter("x")), name.equals("select") ? selected : raised,
                map("rep", closure, "resultRep", data)), true);
            entry.put("rep", closure); entry.put("arity", 1); bindings.add(entry);
        }
        var module = module(bindings); module.put("constructors", constructors);
        try (var engine = Engine.newBuilder().allowExperimentalOptions(true).option("engine.BackgroundCompilation", "false")
                .option("engine.MultiTier", "false").option("engine.CompilationFailureAction", "Throw").build()) {
            Program.PreparedCode code;
            try (var preparation = Context.newBuilder("thc").engine(engine).build()) {
                preparation.initialize("thc"); preparation.enter();
                try { code = Program.prepareCode(TruffleLanguage.LanguageReference.create(Language.class).get(null), module, List.of("select", "raise")); }
                finally { preparation.leave(); }
            }
            var field = Program.PreparedCode.class.getDeclaredField("targets"); field.setAccessible(true);
            for (var target : (List<com.oracle.truffle.runtime.OptimizedCallTarget>)field.get(code)) {
                assertFalse(target.wasExecuted()); assertTrue(target.prepareForAOT()); target.compile(true); assertFalse(target.wasExecuted());
            }
            code.requireInstalledCode();
            String previous = System.getProperty("thc.requireCompiledCode");
            var owners = new ArrayList<Object>();
            try {
                for (int load = 0; load < 2; load++) try (var context = Context.newBuilder("thc").engine(engine).build()) {
                    context.initialize("thc"); context.enter();
                    try {
                        var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                        var first = code.newInstance(language); var second = code.newInstance(language);
                        long siblingEntries = count(second, "compiledEntries");
                        for (var program : List.of(first, second)) {
                            System.setProperty("thc.requireCompiledCode", "true");
                            var select = (Closure)program.entryValue("select");
                            Object zero = Calls.target(select.target, new Object[]{0L, select.environment, 0L});
                            assertSame(zero, Calls.target(select.target, new Object[]{0L, select.environment, 0L}));
                            assertNotSame(zero, Calls.target(select.target, new Object[]{0L, select.environment, 1L}));
                            for (var owner : owners) assertNotSame(owner, zero);
                            owners.add(zero); assertSame(program.entryValue(payload), zero);
                            assertTrue(count(program, "compiledEntries") >= 3);
                            assertEquals(0, count(program, "loweredRootCount"));
                            // Stock exception deoptimization is allowed. Ownership
                            // must still hold for every later invocation.
                            System.setProperty("thc.requireCompiledCode", "false");
                            var raise = (Closure)program.entryValue("raise");
                            var failure = assertThrows(GuestException.class,
                                () -> Calls.target(raise.target, new Object[]{0L, raise.environment, 0L}));
                            assertSame(zero, failure.getPayload()); assertTrue(failure.getSomeException());
                            if (program == first) assertEquals(siblingEntries, count(second, "compiledEntries"));
                        }
                    } finally { context.leave(); }
                }
            } finally {
                if (previous == null) System.clearProperty("thc.requireCompiledCode"); else System.setProperty("thc.requireCompiledCode", previous);
            }
        }
    }

    @Test @SuppressWarnings("unchecked")
    void preparedAtomicModifierKeepsLazyCapturesAndMetricsPerInstance() throws Exception {
        var state = map("kind", "void", "evaluated", true, "primReps", list());
        var word = map("kind", "long", "evaluated", true, "primReps", list("IntRep"));
        var data = map("kind", "data", "evaluated", false, "primReps", list("BoxedRep (Just Lifted)"));
        var closure = map("kind", "closure", "evaluated", true, "primReps", list("BoxedRep (Just Lifted)"));
        var mutvar = map("kind", "object", "evaluated", true, "primReps", list("BoxedRep (Just Unlifted)"));
        var stateBinder = map("id", "s", "name", "s", "lifted", false, "rep", state);
        var oldBinder = map("id", "old", "name", "old", "lifted", true, "rep", data);
        var cellBinder = map("id", "cell", "name", "cell", "lifted", false, "rep", mutvar);
        var old = list("var", "old", map("rep", data));
        var cell = list("var", "cell", map("rep", mutvar));
        var token = list("void", map("rep", state));
        var shared = binding("shared", list("app", list("con", "Box", 1), list(literal(42)), list(false),
            false, false, map("rep", data)), true);
        shared.put("rep", data);
        var modifier = binding("modifier", list("lam", list(oldBinder),
            list("app", list("con", "Pair", 2), list(list("var", "shared", map("rep", data)), old),
                list(true, true), false, false, map("rep", data)), map("rep", closure, "resultRep", data)), true);
        modifier.put("rep", closure); modifier.put("arity", 1);
        var modified = map("kind", "unknown", "evaluated", true, "primReps", list("BoxedRep (Just Lifted)", "BoxedRep (Just Lifted)"),
            "aggregate", "unboxed-tuple", "components", list(state, data, data));
        var read = map("kind", "unknown", "evaluated", true, "primReps", list("BoxedRep (Just Lifted)"),
            "aggregate", "unboxed-tuple", "components", list(state, data));
        var modification = list("app", list("prim", "atomicModifyMutVar2#"),
            list(cell, list("var", "modifier", map("rep", closure)), token), list(false, true, false), false, false, map("rep", modified));
        var modificationBody = list("case", modification, "triple", list(list("data", "Triple", list("s", "old", "result"), old,
            map("binders", list(stateBinder, oldBinder, map("id", "result", "name", "result", "lifted", true, "rep", data))))),
            map("binder", map("id", "triple", "name", "triple", "lifted", false, "rep", modified), "rep", data));
        var readBody = list("case", list("app", list("prim", "readMutVar#"), list(cell, token), list(false, false), false, false, map("rep", read)),
            "readPair", list(list("data", "ReadPair", list("s", "old"),
                list("case", old, "boxed", list(list("data", "Box", list("value"), variable("value"),
                    map("binders", list(wordParameter("value"))))),
                    map("binder", map("id", "boxed", "name", "boxed", "lifted", true, "rep", data), "rep", word)),
                map("binders", list(stateBinder, oldBinder)))),
            map("binder", map("id", "readPair", "name", "readPair", "lifted", false, "rep", read), "rep", word));
        var bindings = list(shared, modifier);
        var all = new ArrayList<Map<String,Object>>(bindings);
        for (String name : List.of("modify", "read")) {
            var result = name.equals("modify") ? data : word;
            var entry = binding(name, list("lam", list(cellBinder), name.equals("modify") ? modificationBody : readBody,
                map("rep", closure, "resultRep", result)), true);
            entry.put("rep", closure); entry.put("arity", 1); all.add(entry);
        }
        var module = module(all);
        module.put("constructors", list(
            map("id", "Box", "name", "Box", "arity", 1, "kind", "boxed", "tag", 1,
                "strictFields", list(true), "fieldLifted", list(false), "fieldReps", list(list("IntRep")), "fieldTypes", list(word)),
            map("id", "Pair", "name", "Pair", "arity", 2, "kind", "boxed", "tag", 1,
                "strictFields", list(false, false), "fieldLifted", list(true, true),
                "fieldReps", list(list("BoxedRep (Just Lifted)"), list("BoxedRep (Just Lifted)")), "fieldTypes", list(data, data)),
            map("id", "Triple", "name", "Triple", "arity", 3, "kind", "unboxed-tuple"),
            map("id", "ReadPair", "name", "ReadPair", "arity", 2, "kind", "unboxed-tuple")));
        try (var engine = Engine.newBuilder().allowExperimentalOptions(true).option("engine.BackgroundCompilation", "false")
                .option("engine.MultiTier", "false").option("engine.CompilationFailureAction", "Throw").build()) {
            Program.PreparedCode code;
            try (var preparation = Context.newBuilder("thc").engine(engine).build()) {
                preparation.initialize("thc"); preparation.enter();
                try { code = Program.prepareCode(TruffleLanguage.LanguageReference.create(Language.class).get(null), module, List.of("modify", "read")); }
                finally { preparation.leave(); }
            }
            var field = Program.PreparedCode.class.getDeclaredField("targets"); field.setAccessible(true);
            for (var target : (List<com.oracle.truffle.runtime.OptimizedCallTarget>)field.get(code)) {
                assertFalse(target.wasExecuted()); assertTrue(target.prepareForAOT()); target.compile(true); assertFalse(target.wasExecuted());
            }
            code.requireInstalledCode();
            String previous = System.getProperty("thc.requireCompiledCode"); System.setProperty("thc.requireCompiledCode", "true");
            try {
                for (int load = 0; load < 2; load++) try (var context = Context.newBuilder("thc").engine(engine).build()) {
                    context.initialize("thc"); context.enter();
                    try {
                        var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                        var first = code.newInstance(language); var second = code.newInstance(language);
                        long siblingEntries = count(second, "compiledEntries");
                        for (var program : List.of(first, second)) {
                            Object original = new DataLayout(language, "old", "old", new String[0]).allocate();
                            var storage = new ManagedMutVar(original);
                            var modifyEntry = (Closure)program.entryValue("modify");
                            assertSame(original, Calls.target(modifyEntry.target, new Object[]{0L, modifyEntry.environment, storage}));
                            assertInstanceOf(Thunk.class, storage.getValue()); assertEquals(0, count(program, "thunkEvaluations"));
                            var readEntry = (Closure)program.entryValue("read");
                            assertEquals(42L, Calls.target(readEntry.target, new Object[]{0L, readEntry.environment, storage}));
                            long evaluated = count(program, "thunkEvaluations"); assertEquals(3, evaluated);
                            assertEquals(42L, Calls.target(readEntry.target, new Object[]{0L, readEntry.environment, storage}));
                            assertEquals(evaluated, count(program, "thunkEvaluations"));
                            assertEquals(0, count(program, "loweredRootCount")); code.requireInstalledCode();
                            if (program == first) assertEquals(siblingEntries, count(second, "compiledEntries"));
                        }
                    } finally { context.leave(); }
                }
            } finally {
                if (previous == null) System.clearProperty("thc.requireCompiledCode"); else System.setProperty("thc.requireCompiledCode", previous);
            }
        }
    }

    @Test @SuppressWarnings("unchecked")
    void preparedSynchronousHandlersAndMaskingNeedNoTraining() throws Exception {
        var state = map("kind", "void", "evaluated", true, "primReps", list());
        var word = map("kind", "long", "evaluated", true, "primReps", list("IntRep"));
        var closure = map("kind", "closure", "evaluated", true, "primReps", list("BoxedRep (Just Lifted)"));
        var data = map("kind", "data", "evaluated", true, "primReps", list("BoxedRep (Just Lifted)"));
        var io = map("kind", "unknown", "evaluated", true, "primReps", list("IntRep"),
            "aggregate", "unboxed-tuple", "components", list(state, word));
        var token = list("void", map("rep", state));
        var stateBinder = map("id", "s", "name", "s", "lifted", false, "rep", state);
        var getMask = list("app", list("prim", "getMaskingState#"), list(token), list(false), false, false, map("rep", io));
        var action = list("lam", list(stateBinder), getMask, map("rep", closure, "resultRep", io));
        var payload = list("con", "Payload", 0, map("rep", data));
        var raising = list("lam", list(stateBinder), list("app", list("prim", "raiseIO#"),
            list(payload, token), list(true, false), false, false, map("rep", io)), map("rep", closure, "resultRep", io));
        var handler = list("lam", list(map("id", "error", "name", "error", "lifted", true, "rep", data), stateBinder),
            getMask, map("rep", closure, "resultRep", io));
        var masked = list("app", list("prim", "maskAsyncExceptions#"), list(action, token), list(true, false), false, false, map("rep", io));
        var caught = list("app", list("prim", "catch#"), list(raising, handler, token), list(true, true, false), false, false, map("rep", io));
        var alive = list("app", list("prim", "keepAlive#"), list(payload, token, action), list(true, false, true), false, false, map("rep", io));
        var bindings = new ArrayList<Map<String,Object>>();
        for (var name : List.of("mask", "catch", "alive")) {
            var body = list("case", name.equals("mask") ? masked : name.equals("catch") ? caught : alive, "pair", list(list("data", "Pair", list("s", "mask"),
                list("var", "mask", map("rep", word)), map("binders", list(stateBinder, wordParameter("mask"))))),
                map("binder", map("id", "pair", "name", "pair", "lifted", false, "rep", io), "rep", word));
            var binding = binding(name, list("lam", list(wordParameter("unused")), body, map("rep", closure, "resultRep", word)), true);
            binding.put("arity", 1); binding.put("rep", closure); bindings.add(binding);
        }
        var module = module(bindings);
        module.put("constructors", list(map("id", "Pair", "name", "Pair", "arity", 2, "kind", "unboxed-tuple"),
            map("id", "Payload", "name", "Payload", "arity", 0, "kind", "boxed", "tag", 1,
                "strictFields", list(), "fieldLifted", list(), "fieldReps", list())));
        try (var engine = Engine.newBuilder().allowExperimentalOptions(true).option("engine.BackgroundCompilation", "false")
                .option("engine.MultiTier", "false").option("engine.CompilationFailureAction", "Throw").build()) {
            Program.PreparedCode code;
            try (var preparation = Context.newBuilder("thc").engine(engine).build()) {
                preparation.initialize("thc"); preparation.enter();
                try { code = Program.prepareCode(TruffleLanguage.LanguageReference.create(Language.class).get(null), module, List.of("mask", "catch", "alive")); }
                finally { preparation.leave(); }
            }
            var field = Program.PreparedCode.class.getDeclaredField("targets"); field.setAccessible(true);
            for (var target : (List<com.oracle.truffle.runtime.OptimizedCallTarget>)field.get(code)) {
                assertFalse(target.wasExecuted()); assertTrue(target.prepareForAOT()); target.compile(true); assertFalse(target.wasExecuted());
            }
            code.requireInstalledCode();
            String previous = System.getProperty("thc.requireCompiledCode"); System.setProperty("thc.requireCompiledCode", "true");
            try {
                for (int load = 0; load < 2; load++) try (var context = Context.newBuilder("thc").engine(engine).build()) {
                    context.initialize("thc"); context.enter();
                    try {
                        var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                        var program = code.newInstance(language); var sibling = code.newInstance(language);
                        long siblingEntries = count(sibling, "compiledEntries");
                        for (var name : List.of("mask", "catch", "alive")) {
                            var function = (Closure)program.entryValue(name);
                            assertEquals(name.equals("alive") ? 0L : 2L, Calls.target(function.target, new Object[]{0L, function.environment, 0L}), "GHC masking tag");
                            assertEquals(MaskingState.UNMASKED, Language.currentState().getMaskingState().get());
                        }
                        assertTrue(count(program, "compiledEntries") >= 5); assertEquals(siblingEntries, count(sibling, "compiledEntries"));
                        assertEquals(0, count(program, "loweredRootCount")); code.requireInstalledCode();
                        var handoff = language.getHandoffState().get();
                        assertNull(handoff.getPending()); assertEquals(0, handoff.getArguments().getDepth());
                        assertEquals(0, handoff.getResults().getDepth());
                    } finally { context.leave(); }
                }
            } finally {
                if (previous == null) System.clearProperty("thc.requireCompiledCode"); else System.setProperty("thc.requireCompiledCode", previous);
            }
        }
    }

    private Map<String,Object> ordinaryClosureModule() {
        var longRep = map("kind", "long", "evaluated", true, "primReps", list("IntRep"));
        var closureRep = map("kind", "closure", "evaluated", true, "primReps", list("BoxedRep (Just Lifted)"));
        var helper = binding("add", list("lam", list(wordParameter("a"), wordParameter("b")),
            plus(variable("a"), variable("b")), map("rep", closureRep, "resultRep", longRep)), true);
        helper.put("rep", closureRep); helper.put("arity", 2);
        var captured = binding("f", list("lam", list(wordParameter("y")),
            list("app", variable("add"), list(variable("x"), variable("y")), list(false, false), map("rep", longRep)),
            map("rep", closureRep, "resultRep", longRep)), true);
        captured.put("rep", closureRep); captured.put("arity", 1);
        var local = binding("z", plus(variable("shared"), literal(1)), false);
        local.put("rep", longRep);
        var reader = binding("read", list("lam", list(wordParameter("x")),
            list("let", false, list(captured, local),
                list("app", variable("f"), list(variable("z")), list(false), map("rep", longRep))),
            map("rep", closureRep, "resultRep", longRep)), true);
        reader.put("arity", 1); reader.put("rep", closureRep);
        return module(list(binding("shared", plus(literal(17), literal(25)), true), helper, reader,
            binding("untouched", list("unsupported-never-selected"), true)));
    }

    @Test @SuppressWarnings("unchecked")
    void nestedTargetsMustBeInstalledAndEnterCompiledWithoutTraining() throws Exception {
        try (var engine = Engine.newBuilder().allowExperimentalOptions(true).option("engine.BackgroundCompilation", "false")
                .option("engine.MultiTier", "false").option("engine.CompilationFailureAction", "Throw").build()) {
            Program.PreparedCode code;
            try (var context = Context.newBuilder("thc").engine(engine).build()) {
                context.initialize("thc"); context.enter();
                try {
                    code = Program.prepareCode(TruffleLanguage.LanguageReference.create(Language.class).get(null),
                        ordinaryClosureModule(), List.of("read"));
                } finally { context.leave(); }
            }
            var field = Program.PreparedCode.class.getDeclaredField("targets"); field.setAccessible(true);
            var targets = (List<com.oracle.truffle.api.RootCallTarget>) field.get(code);
            var nested = targets.stream().filter(target -> target.getRootNode().getName().equals("lambda y")).findFirst().orElseThrow();
            var type = Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget");
            for (var target : targets) {
                assertEquals(false, type.getMethod("wasExecuted").invoke(target));
                assertEquals(true, type.getMethod("prepareForAOT").invoke(target));
                if (target != nested) type.getMethod("compile", boolean.class).invoke(target, true);
                assertEquals(false, type.getMethod("wasExecuted").invoke(target));
            }
            assertTrue(assertThrows(IllegalStateException.class, code::requireInstalledCode).getMessage().contains("lambda y"));
            type.getMethod("compile", boolean.class).invoke(nested, true);
            code.requireInstalledCode();
            String previous = System.getProperty("thc.requireCompiledCode");
            System.setProperty("thc.requireCompiledCode", "true");
            try (var context = Context.newBuilder("thc").engine(engine).build()) {
                context.initialize("thc"); context.enter();
                try {
                    var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                    var program = code.newInstance(language);
                    var reader = (Closure) program.entryValue("read");
                    var runtime = Truffle.getRuntime();
                    runtime.getClass().getMethod("bypassedInstalledCode", type).invoke(runtime, reader.target);
                    assertEquals(true, type.getMethod("isValidLastTier").invoke(reader.target), "nested-call reader before first entry");
                    assertEquals(47L, Calls.target(reader.target, new Object[]{0L, reader.environment, 4L}));
                    assertEquals(1L, count(program, "thunkEvaluations"));
                    assertEquals(0L, count(program, "loweredRootCount"));
                    code.requireInstalledCode();
                } finally { context.leave(); }
            } finally {
                if (previous == null) System.clearProperty("thc.requireCompiledCode");
                else System.setProperty("thc.requireCompiledCode", previous);
            }
        }
    }

    @ParameterizedTest @ValueSource(strings = {"fresh", "call", "thunk"})
    void reusableForceTailBounceUpdatesItsCafWithInvocationOwnedMetrics(String boundary) throws Exception {
        try (var context = Context.newBuilder("thc").allowExperimentalOptions(true)
                .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false")
                .option("engine.CompilationFailureAction", "Throw").build()) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var code = Program.prepareCode(language, module(list(
                    binding("shared", plus(literal(17), literal(25)), true),
                    binding("read", lambda(plus(variable("shared"), variable("x"))), true))), List.of("read"));
                var targets = Program.PreparedCode.class.getDeclaredField("targets"); targets.setAccessible(true);
                for (var value : (Iterable<?>) targets.get(code)) {
                    var target = (com.oracle.truffle.runtime.OptimizedCallTarget) value;
                    assertFalse(target.wasExecuted()); assertTrue(target.prepareForAOT()); target.compile(true);
                    assertFalse(target.wasExecuted());
                }
                code.requireInstalledCode();
                var first = code.newInstance(language);
                var sibling = code.newInstance(language);
                var caf = (Thunk) first.entryValue("shared");
                var actualBody = caf.getTarget();
                var environment = caf.getEnvironment();
                var resumed = new AtomicInteger();
                // Force an actual tail transfer, including resumption through a
                // parked child. Its destination remains the real lowered CAF.
                if (boundary.equals("fresh")) {
                    caf.setTarget(new com.oracle.truffle.api.nodes.RootNode(null) {
                        @Override public Object execute(com.oracle.truffle.api.frame.VirtualFrame frame) {
                            throw new TailCall(actualBody, new Object[]{0L, frame.getArguments()[1]});
                        }
                    }.getCallTarget());
                } else {
                    var leaf = new SavedGuestContinuation() {
                        public Object getIdentity() { return this; }
                        public Object getSourceRoot() { return actualBody.getRootNode(); }
                        public Object getYielded() { return Unit.INSTANCE; }
                        public Object continueWith(Object input) {
                            resumed.incrementAndGet();
                            throw new TailCall(actualBody, new Object[]{0L, environment});
                        }
                    };
                    Object marker;
                    if (boundary.equals("call")) marker = new CallSegmentSuspended(new CallSegment(leaf));
                    else {
                        var child = new Thunk(actualBody, environment);
                        child.setValue(leaf); child.setState(5);
                        marker = new ThunkSuspended(child);
                    }
                    var parent = new SavedGuestContinuation() {
                        public Object getIdentity() { return this; }
                        public Object getSourceRoot() { return actualBody.getRootNode(); }
                        public Object getYielded() { return marker; }
                        public Object continueWith(Object input) { return ((ChildResume) input).getValue(); }
                    };
                    caf.setValue(parent); caf.setState(5);
                }
                String previous = System.setProperty("thc.requireCompiledCode", "true");
                try {
                    assertEquals(47L, call(first, first.entryValue("read"), 5L));
                    assertEquals(2, caf.getState()); assertNull(caf.getEnvironment());
                    assertEquals(boundary.equals("fresh") ? 1L : 0L, count(first, "thunkEvaluations"));
                    assertEquals(1L, count(first, "trampolineIterations"));
                    assertEquals(0L, count(sibling, "thunkEvaluations"));
                    assertEquals(0L, count(sibling, "trampolineIterations"));
                    assertEquals(49L, call(first, first.entryValue("read"), 7L));
                    assertEquals(boundary.equals("fresh") ? 0 : 1, resumed.get(), "Repeated CAF reads must not replay a saved child");
                    assertEquals(boundary.equals("fresh") ? 1L : 0L, count(first, "thunkEvaluations"));
                    code.requireInstalledCode();
                } finally {
                    if (previous == null) System.clearProperty("thc.requireCompiledCode");
                    else System.setProperty("thc.requireCompiledCode", previous);
                }
            } finally { context.leave(); }
        }
    }

    @Test void unusedConstructorMetadataRemainsUnpreparedUntilSelected() {
        var data = module(list(binding("read", lambda(plus(variable("x"), literal(1))), true),
            binding("constructed", list("con", "Unused", 0), true)));
        data.put("constructors", list(map("id", "Unused", "name", "Unused", "arity", 0, "tag", 1,
            "kind", "boxed", "strictFields", list(), "fieldLifted", list(), "fieldReps", list())));
        try (var context = Main.executionContext(false)) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var code = Program.prepareCode(language, data, List.of("read"));
                var instance = code.newInstance(language);
                assertEquals(8L, call(instance, instance.entryValue("read"), 7L));
                assertThrows(UnsupportedCore.class, () -> instance.entryValue("constructed"));
                var selected = Program.prepareCode(language, data, List.of("constructed"));
                assertNotSame(selected.newInstance(language).entryValue("constructed"), selected.newInstance(language).entryValue("constructed"));
            } finally { context.leave(); }
        }
    }

    private Map<String, Object> binding(String id, List<Object> body, boolean lifted) {
        return map("id", id, "name", id, "type", "Synthetic", "lifted", lifted, "expr", body);
    }
    private List<Object> variable(String id) { return list("var", id); }
    private List<Object> literal(long n) { return list("lit", "int", Long.toString(n)); }
    private List<Object> plus(List<Object> a, List<Object> b) {
        return list("app", list("prim", "+#"), list(a, b), list(false, false));
    }
    private List<Object> lambda(List<Object> body) {
        return list("lam", list(wordParameter("x")), body);
    }
    private Map<String, Object> wordParameter(String id) {
        return map("id", id, "name", id, "type", "Int#", "lifted", false, "coercion", false,
            "rep", map("kind", "long", "evaluated", true, "primReps", list("IntRep")));
    }
    private Object call(ExecutableProgram program, Object value, Object... arguments) {
        return Calls.target(program.hostEntryTarget(arguments.length), new Object[]{value, arguments});
    }
    private long count(ExecutableProgram program, String name) { return ((Number) program.diagnostics().get(name)).longValue(); }
    private Map<String, Object> module(List<Map<String, Object>> bindings) {
        return map("schema", 1, "ghc", "9.14.1", "module", "Synthetic.Reusable", "instrument", true,
            "constructors", list(), "bindings", bindings);
    }

    @Test void untouchedRealRootsPrepareOutsideContextAndRetainTheirFirstCompiledEntry() throws Exception {
        var untouched = new AtomicInteger();
        var lazy = new CoreBindingBody(new CoreBindingBody.Header(2, Map.of(0, "var", 1, "unused"), false), null,
            () -> { untouched.incrementAndGet(); throw new AssertionError("untouched definition was decoded"); });
        var data = module(list(binding("shared", plus(literal(17), literal(25)), true),
            binding("read", lambda(plus(variable("shared"), variable("x"))), true),
            binding("bottom", variable("bottom"), true), binding("unused", lazy, true)));
        try (var engine = Engine.newBuilder().allowExperimentalOptions(true)
                .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false")
                .option("engine.CompilationFailureAction", "Throw").build()) {
            Program.PreparedCode code;
            Program preparation;
            com.oracle.truffle.api.RootCallTarget[] targets;
            try (var context = Context.newBuilder("thc").engine(engine).build()) {
                context.initialize("thc"); context.enter();
                try {
                    var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                    code = Program.prepareCode(language, data, List.of("read", "bottom"));
                    preparation = code.newInstance(language);
                    targets = new com.oracle.truffle.api.RootCallTarget[]{
                        ((Closure) preparation.entryValue("read")).target,
                        ((Thunk) preparation.entryValue("shared")).getTarget(),
                        ((Thunk) preparation.entryValue("bottom")).getTarget()};
                } finally { context.leave(); }
            }
            // No entered context, guest execution or profile-training call; the preparation context is gone.
            var type = Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget");
            for (var target : targets) {
                assertEquals(false, type.getMethod("wasExecuted").invoke(target));
                assertEquals(true, type.getMethod("prepareForAOT").invoke(target), target.getRootNode().getName());
                type.getMethod("compile", boolean.class).invoke(target, true);
                assertEquals(true, type.getMethod("isValidLastTier").invoke(target));
                assertEquals(false, type.getMethod("wasExecuted").invoke(target), "preparation/compilation must not execute a guest body");
            }
            assertEquals(0L, count(preparation, "thunkEvaluations"));
            assertEquals(0L, count(preparation, "compiledEntries"));
            assertEquals(0, untouched.get());
            RuntimeFault previousFailure = null;
            for (int owner = 0; owner < 2; owner++) {
                try (var context = Context.newBuilder("thc").engine(engine).build()) {
                    context.initialize("thc"); context.enter();
                    try {
                        var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                        var program = code.newInstance(language);
                        var reader = (Closure) program.entryValue("read");
                        assertSame(targets[0], reader.target);
                        assertEquals(0, ((Thunk) program.entryValue("shared")).getState());
                        var runtime = Truffle.getRuntime();
                        runtime.getClass().getMethod("bypassedInstalledCode", type).invoke(runtime, targets[0]);
                        assertEquals(true, type.getMethod("isValidLastTier").invoke(targets[0]), "original reader immediately before its first call");
                        assertEquals(47L + owner, Calls.target(reader.target, new Object[]{0L, reader.environment, 5L + owner}));
                        assertEquals(2L, count(program, "compiledEntries"), "first reader and shared CAF must both enter compiled code");
                        assertEquals(true, type.getMethod("isValidLastTier").invoke(targets[0]), "original reader after its first call");
                        assertEquals(true, type.getMethod("isValidLastTier").invoke(targets[1]), "original CAF after its first call");
                        assertEquals(1L, count(program, "thunkEvaluations"));
                        var sibling = code.newInstance(language);
                        var siblingReader = (Closure) sibling.entryValue("read");
                        assertSame(reader.target, siblingReader.target);
                        assertEquals(0, ((Thunk) sibling.entryValue("shared")).getState());
                        assertEquals(53L, Calls.target(siblingReader.target, new Object[]{0L, siblingReader.environment, 11L}));
                        assertEquals(2L, count(sibling, "compiledEntries"));
                        assertEquals(1L, count(sibling, "thunkEvaluations"));
                        assertEquals(2L, count(program, "compiledEntries"));
                        assertEquals(true, type.getMethod("isValidLastTier").invoke(targets[0]));
                        var bottom = program.entryValue("bottom");
                        var failure = assertThrows(RuntimeFault.class, () -> call(program, bottom));
                        assertSame(failure, assertThrows(RuntimeFault.class, () -> call(program, bottom)));
                        if (previousFailure != null) assertNotSame(previousFailure, failure);
                        previousFailure = failure;
                        assertEquals(2L, count(program, "thunkEvaluations"));
                        assertEquals(0L, count(program, "loweredRootCount"));
                        assertEquals(0, untouched.get());
                    } finally { context.leave(); }
                }
            }
            assertEquals(0L, count(preparation, "thunkEvaluations"));
        }
    }

    @Test void ordinaryRootsDoNotClaimReusableAotPreparation() throws Exception {
        try (var context = Main.executionContext(false)) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var ordinary = new Program(language, module(list(binding("read", lambda(variable("x")), true))));
                var target = ((Closure) ordinary.entryValue("read")).target;
                var type = Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget");
                assertEquals(false, type.getMethod("prepareForAOT").invoke(target));
                assertEquals(0L, count(ordinary, "compiledEntries"));
            } finally { context.leave(); }
        }
    }

    @Test void strictInitializersAreInertUntilInstanceCreationAndMalformedLevityStillFails() {
        try (var context = Main.executionContext(false)) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var arithmetic = module(list(binding("strict", plus(literal(17), literal(25)), false)));
                var force = module(list(binding("caf", plus(literal(17), literal(25)), true),
                    binding("strict", variable("caf"), false)));
                var unknown = wordParameter("x"); unknown.remove("rep"); unknown.put("lifted", null);
                var missingProof = module(list(binding("read", list("lam", list(unknown), plus(variable("x"), literal(1))), true)));
                assertAll(
                    () -> assertEquals(42L, Program.prepareCode(language, arithmetic, List.of("strict")).newInstance(language).entryValue("strict")),
                    () -> {
                        var code = Program.prepareCode(language, force, List.of("strict"));
                        var first = code.newInstance(language); var second = code.newInstance(language);
                        assertEquals(42L, first.entryValue("strict")); assertEquals(42L, second.entryValue("strict"));
                        assertNotSame(first.entryValue("caf"), second.entryValue("caf"));
                        assertEquals(2, ((Thunk)first.entryValue("caf")).getState());
                        assertEquals(1L, count(first, "thunkEvaluations"));
                        first.entryValue("strict"); assertEquals(1L, count(first, "thunkEvaluations"));
                    },
                    () -> assertThrows(UnsupportedCore.class, () -> Program.prepareCode(language, missingProof, List.of("read"))));
                var literalCode = Program.prepareCode(language, module(list(binding("strict", literal(42), false))), List.of("strict"));
                assertEquals(42L, literalCode.newInstance(language).entryValue("strict"));
            } finally { context.leave(); }
        }
    }

    @Test void admittedInputsRejectNonLongAndThunkBeforeEffects() {
        try (var context = Main.executionContext(false)) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var program = Program.prepareCode(language, module(list(binding("shared", plus(literal(17), literal(25)), true),
                    binding("read", lambda(plus(variable("shared"), variable("x"))), true))), List.of("read")).newInstance(language);
                var effects = new AtomicInteger();
                var external = new Thunk(new GuestRoot(language, com.oracle.truffle.api.frame.FrameDescriptor.newBuilder().build()) {
                    @Override public long bloom(com.oracle.truffle.api.frame.VirtualFrame frame) { return 0; }
                    @Override public Object execute(com.oracle.truffle.api.frame.VirtualFrame frame) { effects.incrementAndGet(); return 9L; }
                }.getCallTarget(), null);
                var reader = (Closure) program.entryValue("read");
                for (Object invalid : List.of("9", Integer.valueOf(9), external)) {
                    assertThrows(RuntimeFault.class, () -> Calls.target(reader.target, new Object[]{0L, reader.environment, invalid}));
                    assertEquals(0, effects.get());
                    assertEquals(0, external.getState());
                    assertEquals(0, ((Thunk) program.entryValue("shared")).getState());
                    assertEquals(0L, count(program, "thunkEvaluations"));
                }
            } finally { context.leave(); }
        }
    }

    @Test void preparedCodeRejectsForeignLanguageBeforeInstanceCreation() {
        Program.PreparedCode code;
        Language preparedLanguage;
        var data = module(list(binding("read", lambda(variable("x")), true)));
        try (var first = Main.executionContext(false)) {
            first.initialize("thc"); first.enter();
            try {
                preparedLanguage = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                code = Program.prepareCode(preparedLanguage, data, List.of("read"));
            } finally { first.leave(); }
        }
        try (var second = Main.executionContext(false)) {
            second.initialize("thc"); second.enter();
            try {
                var currentLanguage = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                assertNotSame(preparedLanguage, currentLanguage);
                assertAll(
                    () -> assertThrows(UnsupportedCore.class, () -> code.newInstance(currentLanguage)),
                    () -> assertThrows(UnsupportedCore.class, () -> code.newInstance(preparedLanguage)),
                    () -> assertThrows(UnsupportedCore.class, () -> Program.prepareCode(preparedLanguage, data, List.of("read"))));
            } finally { second.leave(); }
        }
    }

    @Test void reusableRootsOutlivePreparationContextButInstancesDoNotCrossContexts() {
        var data = module(list(binding("shared", plus(literal(17), literal(25)), true),
            binding("read", lambda(plus(variable("shared"), variable("x"))), true),
            binding("bottom", variable("bottom"), true)));
        try (var engine = Engine.newBuilder().allowExperimentalOptions(true).option("engine.Compilation", "false").build()) {
            Program.PreparedCode code;
            Program first;
            Closure firstReader;
            RuntimeFault firstFailure;
            try (var context = Context.newBuilder("thc").engine(engine).build()) {
                context.initialize("thc"); context.enter();
                try {
                    var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                    code = Program.prepareCode(language, data, List.of("read", "bottom"));
                    first = code.newInstance(language);
                    firstReader = (Closure) first.entryValue("read");
                    assertEquals(47L, call(first, firstReader, 5L));
                    firstFailure = assertThrows(RuntimeFault.class, () -> call(first, first.entryValue("bottom")));
                } finally { context.leave(); }
            }
            // The preparation context is closed. Only code may be reused; its values remain owned.
            try (var context = Context.newBuilder("thc").engine(engine).build()) {
                context.initialize("thc"); context.enter();
                try {
                    var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                    var second = code.newInstance(language);
                    var secondReader = (Closure) second.entryValue("read");
                    assertSame(firstReader.target, secondReader.target);
                    assertEquals(0, ((Thunk) second.entryValue("shared")).getState());
                    assertEquals(51L, call(second, secondReader, 9L));
                    assertEquals(1L, count(second, "thunkEvaluations"));
                    long secondEntries = count(second, "compiledEntries");
                    assertThrows(RuntimeFault.class, () -> Calls.target(secondReader.target,
                        new Object[]{0L, firstReader.environment, 1L}), "a closed context's instance cannot enter shared code elsewhere");
                    assertEquals(secondEntries, count(second, "compiledEntries"));
                    var failure = assertThrows(RuntimeFault.class, () -> call(second, second.entryValue("bottom")));
                    assertNotSame(firstFailure, failure);
                    assertSame(failure, assertThrows(RuntimeFault.class, () -> call(second, second.entryValue("bottom"))));
                    assertEquals(2L, count(second, "thunkEvaluations"));
                    assertEquals(2L, count(first, "thunkEvaluations"));
                    assertEquals(0L, count(second, "loweredRootCount"));
                } finally { context.leave(); }
            }
        }
    }

    @Test void preparationRetainsDeclarationsWithoutOpeningLibraries() {
        // Deliberately unusable code: unused immutable declarations must not
        // parse a native library or require native access during preparation.
        var declaration = new PackageScalarLink("unused-component", "unused-target", "", "", new byte[0], List.of());
        var data = module(list(binding("read", lambda(variable("x")), true)));
        data.put("packageScalarLinks", List.of(declaration));
        try (var context = Main.executionContext(false)) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var code = Program.prepareCode(language, data, List.of("read"));
                assertEquals(List.of(declaration), code.getPackageScalarLinks());
                assertTrue(code.getForeignLinks().isEmpty());
                assertTrue(code.getManagedRegistrations().isEmpty());
                var first = code.newInstance(language);
                var second = code.newInstance(language);
                assertEquals(47L, call(first, first.entryValue("read"), 47L));
                assertEquals(-25L, call(second, second.entryValue("read"), -25L));
                assertEquals(0L, count(first, "loweredRootCount"));
            } finally { context.leave(); }
        }
    }

    @Test void preparedForeignExceptionBridgeRequiresItsBindings() {
        try (var context = Main.executionContext(false)) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var foreign = module(list(binding("read", lambda(variable("x")), true)));
                foreign.put("selectedForeignExceptionBridge", map("unit", "u", "box", "b", "project", "p"));
                assertThrows(UnsupportedCore.class, () -> Program.prepareCode(language, foreign, List.of("read")));
            } finally { context.leave(); }
        }
    }

    @Test void installedSharedRootUsesFreshOwnersCafAndMetricsOnItsFirstCall() throws Exception {
        try (var context = Main.executionContext(false)) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var code = Program.prepareCode(language, module(list(
                    binding("shared", plus(literal(17), literal(25)), true),
                    binding("read", lambda(plus(variable("shared"), variable("x"))), true))), List.of("read"));
                var first = code.newInstance(language);
                var second = code.newInstance(language);
                var reader = (Closure) first.entryValue("read");
                var secondReader = (Closure) second.entryValue("read");
                var secondCaf = (Thunk) second.entryValue("shared");
                assertSame(reader.target, secondReader.target);
                // One declared call establishes the ordinary JIT profiles using the FIRST owner only.
                // The second owner and its CAF have never executed when shared code is installed.
                assertEquals(47L, Calls.target(reader.target, new Object[]{0L, reader.environment, 5L}));
                assertEquals(0, secondCaf.getState());
                var target = reader.target;
                var type = Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget");
                type.getMethod("compile", boolean.class).invoke(target, true);
                type.getMethod("waitForCompilation").invoke(target);
                assertEquals(true, type.getMethod("isValidLastTier").invoke(target));
                var runtime = Truffle.getRuntime();
                runtime.getClass().getMethod("bypassedInstalledCode", type).invoke(runtime, target);
                long firstEntries = count(first, "compiledEntries");
                long secondEntries = count(second, "compiledEntries");
                assertEquals(51L, Calls.target(target, new Object[]{0L, secondReader.environment, 9L}));
                assertSame(target, secondReader.target);
                assertEquals(true, type.getMethod("isValidLastTier").invoke(target), "the first second-owner call must retain installed code");
                assertTrue(count(second, "compiledEntries") > secondEntries);
                assertEquals(firstEntries, count(first, "compiledEntries"));
                assertEquals(2, secondCaf.getState());
                assertEquals(1L, count(first, "thunkEvaluations"));
                assertEquals(1L, count(second, "thunkEvaluations"));
            } finally { context.leave(); }
        }
    }

    @Test void realLoweredPapRetainsItsOwnerAcrossOtherInstanceCalls() {
        var x = wordParameter("x");
        var y = wordParameter("y");
        try (var context = Main.executionContext(false)) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var code = Program.prepareCode(language, module(list(
                    binding("shared", plus(literal(17), literal(25)), true),
                    binding("read", list("lam", list(x, y), plus(variable("shared"), plus(variable("x"), variable("y")))), true))), List.of("read"));
                var first = code.newInstance(language); var second = code.newInstance(language);
                var firstPap = (Closure) call(first, first.entryValue("read"), 3L);
                var secondPap = (Closure) call(second, second.entryValue("read"), 11L);
                assertSame(first, firstPap.environment.getProgram()); assertSame(second, secondPap.environment.getProgram());
                assertSame(firstPap.target, secondPap.target);
                assertEquals(55L, call(second, secondPap, 2L));
                assertEquals(0, ((Thunk) first.entryValue("shared")).getState());
                assertEquals(50L, call(first, firstPap, 5L));
                assertEquals(1L, count(first, "thunkEvaluations")); assertEquals(1L, count(second, "thunkEvaluations"));
            } finally { context.leave(); }
        }
    }

    @Test void wrongCodeAndMissingOwnerAreRejectedBeforeCafEffects() {
        try (var context = Main.executionContext(false)) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var data = module(list(binding("shared", plus(literal(17), literal(25)), true),
                    binding("read", lambda(plus(variable("shared"), variable("x"))), true)));
                var first = Program.prepareCode(language, data, List.of("read")).newInstance(language);
                var other = Program.prepareCode(language, data, List.of("read")).newInstance(language);
                var reader = (Closure) first.entryValue("read");
                var layout = reader.environment.getLayout();
                for (var environment : List.of(layout.captureValues(new Object[0]), layout.captureValues(new Object[0], other))) {
                    assertThrows(RuntimeFault.class, () -> Calls.target(reader.target, new Object[]{0L, environment, 5L}));
                    assertEquals(0, ((Thunk) first.entryValue("shared")).getState());
                    assertEquals(0, ((Thunk) other.entryValue("shared")).getState());
                    assertEquals(0L, count(first, "thunkEvaluations")); assertEquals(0L, count(other, "thunkEvaluations"));
                }
            } finally { context.leave(); }
        }
    }

    @ParameterizedTest @ValueSource(ints = {1, 4})
    void realLoweredRootsShareCodeButNotCafUpdatesFailuresOrMetrics(int jobs) {
        var untouched = new AtomicInteger();
        var lazy = new CoreBindingBody(new CoreBindingBody.Header(2, Map.of(0, "var", 1, "unused"), false), null,
            () -> { untouched.incrementAndGet(); throw new AssertionError("untouched definition was decoded"); });
        var module = map("schema", 1, "ghc", "9.14.1", "module", "Synthetic.Reusable",
            "instrument", true, "constructors", list(), "bindings", list(
                binding("shared", plus(literal(17), literal(25)), true),
                binding("read", lambda(plus(variable("shared"), variable("x"))), true),
                binding("readAgain", lambda(plus(variable("shared"), variable("x"))), true),
                binding("bottom", variable("bottom"), true), binding("unused", lazy, true)));
        try (var context = Main.executionContext(false)) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var code = Program.prepareCode(language, module, List.of("read", "readAgain", "bottom"), jobs);
                var first = code.newInstance(language);
                var second = code.newInstance(language);
                assertEquals(0, untouched.get());
                assertEquals(0L, count(first, "thunkEvaluations"));
                assertEquals(0L, count(second, "thunkEvaluations"));
                var firstReader = (Closure) first.entryValue("read");
                var secondReader = (Closure) second.entryValue("read");
                assertSame(firstReader.target, secondReader.target, "the actual lowered root must be reused");
                assertNotSame(firstReader, secondReader);
                assertSame(first, firstReader.environment.getProgram());
                assertSame(second, secondReader.environment.getProgram());
                var firstCaf = (Thunk) first.entryValue("shared");
                var secondCaf = (Thunk) second.entryValue("shared");
                assertNotSame(firstCaf, secondCaf);
                assertSame(firstCaf.getTarget(), secondCaf.getTarget());
                assertEquals(0, firstCaf.getState()); assertEquals(0, secondCaf.getState());
                assertEquals(47L, call(first, firstReader, 5L));
                assertEquals(2, firstCaf.getState()); assertEquals(0, secondCaf.getState());
                assertEquals(1L, count(first, "thunkEvaluations")); assertEquals(0L, count(second, "thunkEvaluations"));
                assertEquals(49L, call(first, first.entryValue("readAgain"), 7L));
                assertEquals(1L, count(first, "thunkEvaluations"));
                assertEquals(51L, call(second, secondReader, 9L));
                assertEquals(1L, count(second, "thunkEvaluations"));
                var firstBottom = (Thunk) first.entryValue("bottom");
                var secondBottom = (Thunk) second.entryValue("bottom");
                assertEquals(0, firstBottom.getState()); assertEquals(0, secondBottom.getState());
                var firstFailure = assertThrows(RuntimeFault.class, () -> call(first, firstBottom));
                assertSame(firstFailure, assertThrows(RuntimeFault.class, () -> call(first, firstBottom)));
                assertEquals(0, secondBottom.getState());
                var secondFailure = assertThrows(RuntimeFault.class, () -> call(second, secondBottom));
                assertSame(secondFailure, assertThrows(RuntimeFault.class, () -> call(second, secondBottom)));
                assertNotSame(firstFailure, secondFailure);
                assertEquals(2L, count(first, "thunkEvaluations")); assertEquals(2L, count(second, "thunkEvaluations"));
                assertEquals(0L, count(first, "loweredRootCount")); assertEquals(0L, count(second, "loweredRootCount"));
                assertEquals(0, untouched.get());
            } finally { context.leave(); }
        }
    }
}
