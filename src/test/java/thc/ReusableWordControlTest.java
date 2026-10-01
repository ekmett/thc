// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc;

import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.TruffleLanguage;
import java.util.*;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Engine;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;
import thc.runtime.*;
import static org.junit.jupiter.api.Assertions.*;
import static thc.CoreExecutionTestSupport.*;

/** Existing word-case / join lowering with reusable code and per-load state. */
class ReusableWordControlTest {
    private Map<String,Object> word() { return map("kind", "long", "evaluated", true, "primReps", list("IntRep")); }
    private Map<String,Object> parameter(String id) { return map("id", id, "name", id, "type", "Int#", "lifted", false, "rep", word()); }
    private List<Object> variable(String id) { return list("var", id); }
    private List<Object> literal(long value) { return list("lit", "int", Long.toString(value)); }
    private List<Object> arithmetic(String op, List<Object> a, List<Object> b) {
        return list("app", list("prim", op), list(a, b), list(false, false), map("rep", word()));
    }
    private List<Object> jump(List<Object> count, List<Object> accumulator) {
        return list("app", variable("go"), list(count, accumulator), list(false, false), map("rep", word()));
    }
    private Map<String,Object> binding(String id, List<Object> expression) {
        return map("id", id, "name", id, "type", "Synthetic", "lifted", true, "expr", expression);
    }
    private Map<String,Object> module() {
        var branch = list("case", variable("i"), "remaining", list(
            list("lit", list("int", "0"), list(), variable("acc")),
            list("default", null, list(), jump(arithmetic("-#", variable("remaining"), literal(1)),
                arithmetic("+#", variable("acc"), variable("remaining"))))),
            map("rep", word(), "binder", parameter("remaining")));
        var loop = binding("go", list("lam", list(parameter("i"), parameter("acc")), branch, map("resultRep", word())));
        loop.put("joinValueArity", 2); loop.put("joinResultRep", word());
        var read = binding("read", list("lam", list(parameter("n"), parameter("seed")),
            list("let", true, list(loop), jump(variable("n"), arithmetic("+#", variable("shared"), variable("seed"))),
                map("rep", word())), map("resultRep", word())));
        read.put("arity", 2);
        return map("schema", 1, "ghc", "9.14.1", "module", "Synthetic.ReusableWordControl", "instrument", true,
            "constructors", list(), "bindings", list(binding("shared", arithmetic("+#", literal(17), literal(25))),
                read, binding("untouched", list("unsupported-never-selected"))));
    }
    @Test @SuppressWarnings("unchecked") void reusableControlRejectsUnprovedOrNonWordCarriers() {
        try (var context = Main.executionContext(false)) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                for (int variant = 0; variant < 4; variant++) {
                    var data = module();
                    var bindings = (List<Map<String,Object>>)data.get("bindings");
                    var reader = (List<Object>)bindings.get(1).get("expr");
                    var region = (List<Object>)reader.get(2);
                    var join = ((List<Map<String,Object>>)region.get(2)).getFirst();
                    var lambda = (List<Object>)join.get("expr");
                    var branch = (List<Object>)lambda.get(2);
                    if (variant == 0) join.remove("joinResultRep");
                    else if (variant == 1) ((List<Map<String,Object>>)lambda.get(1)).getFirst().remove("rep");
                    else if (variant == 2) ((Map<String,Object>)((Map<String,Object>)branch.get(4)).get("binder")).remove("rep");
                    else ((List<List<Object>>)branch.get(3)).getFirst().set(1, list("float", "0.0"));
                    assertThrows(UnsupportedCore.class, () -> Program.prepareCode(language, data, List.of("read")));
                }
            } finally { context.leave(); }
        }
    }

    @Test @SuppressWarnings("unchecked") void firstCompiledJoinBranchesNeedNoGuestTraining() throws Exception {
        try (var engine = Engine.newBuilder().allowExperimentalOptions(true).option("engine.BackgroundCompilation", "false")
                .option("engine.MultiTier", "false").option("engine.CompilationFailureAction", "Throw").build()) {
            Program.PreparedCode code;
            try (var preparation = Context.newBuilder("thc").engine(engine).build()) {
                preparation.initialize("thc"); preparation.enter();
                try { code = Program.prepareCode(TruffleLanguage.LanguageReference.create(Language.class).get(null), module(), List.of("read")); }
                finally { preparation.leave(); }
            }
            var field = Program.PreparedCode.class.getDeclaredField("targets"); field.setAccessible(true);
            var targets = (List<com.oracle.truffle.api.RootCallTarget>)field.get(code);
            var type = Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget");
            for (var target : targets) {
                assertEquals(false, type.getMethod("wasExecuted").invoke(target));
                assertEquals(true, type.getMethod("prepareForAOT").invoke(target));
                type.getMethod("compile", boolean.class).invoke(target, true);
                assertEquals(false, type.getMethod("wasExecuted").invoke(target));
            }
            code.requireInstalledCode();
            String previous = System.getProperty("thc.requireCompiledCode"); System.setProperty("thc.requireCompiledCode", "true");
            try (var context = Context.newBuilder("thc").engine(engine).build()) {
                context.initialize("thc"); context.enter();
                try {
                    var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                    var first = code.newInstance(language); var second = code.newInstance(language);
                    var reader = (Closure)first.entryValue("read"); var other = (Closure)second.entryValue("read");
                    Truffle.getRuntime().getClass().getMethod("bypassedInstalledCode", type).invoke(Truffle.getRuntime(), reader.target);
                    assertEquals(47L, Calls.target(reader.target, new Object[]{0L, reader.environment, 0L, 5L}));
                    code.requireInstalledCode();
                    assertEquals(-25L, Calls.target(other.target, new Object[]{0L, other.environment, 5L, -82L}));
                    code.requireInstalledCode();
                    assertEquals(2L, ((Number)first.diagnostics().get("compiledEntries")).longValue());
                    assertEquals(2L, ((Number)second.diagnostics().get("compiledEntries")).longValue());
                } finally { context.leave(); }
            } finally {
                if (previous == null) System.clearProperty("thc.requireCompiledCode"); else System.setProperty("thc.requireCompiledCode", previous);
            }
        }
    }
}
