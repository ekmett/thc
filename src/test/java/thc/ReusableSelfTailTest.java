// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.TruffleLanguage;
import java.util.*;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Engine;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;
import org.junit.jupiter.api.Test;
import thc.runtime.*;
import static org.junit.jupiter.api.Assertions.*;
import static thc.CoreExecutionTestSupport.*;

/** Global self recursion, not a local join or a warmed root. */
class ReusableSelfTailTest {
    private Map<String,Object> module() {
        var word = map("kind", "long", "evaluated", true, "primReps", list("IntRep"));
        var parameter = map("id", "n", "name", "n", "type", "Int#", "lifted", false, "rep", word);
        var step = list("app", list("var", "f"), list(
            list("app", list("prim", "-#"), list(list("var", "n"), list("lit", "int", "1")),
                list(false, false), map("rep", word))), list(false), map("rep", word));
        var body = list("case", list("var", "n"), "remaining", list(
            list("lit", list("int", "0"), list(), list("lit", "int", "0")),
            list("default", null, list(), step)), map("rep", word, "binder",
                map("id", "remaining", "name", "remaining", "type", "Int#", "lifted", false, "rep", word)));
        var function = map("id", "f", "name", "f", "type", "Int# -> Int#", "lifted", true, "arity", 1,
            "expr", list("lam", list(parameter), body, map("resultRep", word)));
        return map("schema", 1, "ghc", "9.14.1", "module", "Synthetic.ReusableSelfTail", "instrument", true,
            "constructors", list(), "bindings", list(function));
    }

    @Test @SuppressWarnings("unchecked") void firstCompiledRecursiveEntryNeedsNoGuestTraining() throws Exception {
        try (var engine = Engine.newBuilder().allowExperimentalOptions(true).option("engine.BackgroundCompilation", "false")
                .option("engine.MultiTier", "false").option("engine.CompilationFailureAction", "Throw").build()) {
            Program.PreparedCode code;
            try (var preparation = Context.newBuilder("thc").engine(engine).build()) {
                preparation.initialize("thc"); preparation.enter();
                try { code = Program.prepareCode(TruffleLanguage.LanguageReference.create(Language.class).get(null), module(), List.of("f")); }
                finally { preparation.leave(); }
            }
            var field = Program.PreparedCode.class.getDeclaredField("targets"); field.setAccessible(true);
            var targets = (List<RootCallTarget>)field.get(code);
            var type = Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget");
            assertEquals(1, targets.size());
            for (var target : targets) {
                assertEquals(false, type.getMethod("wasExecuted").invoke(target));
                assertEquals(true, type.getMethod("prepareForAOT").invoke(target));
                type.getMethod("compile", boolean.class).invoke(target, true);
                assertEquals(false, type.getMethod("wasExecuted").invoke(target));
            }
            code.requireInstalledCode();
            String previous = System.getProperty("thc.requireCompiledCode"); System.setProperty("thc.requireCompiledCode", "true");
            try {
                for (int i = 0; i < 2; i++) try (var context = Context.newBuilder("thc").engine(engine).build()) {
                    context.initialize("thc"); context.enter();
                    try {
                        var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                        var first = code.newInstance(language); var second = code.newInstance(language);
                        var function = (Closure)first.entryValue("f"); var other = (Closure)second.entryValue("f");
                        assertSame(function.target, other.target);
                        Truffle.getRuntime().getClass().getMethod("bypassedInstalledCode", type).invoke(Truffle.getRuntime(), function.target);
                        assertEquals(0L, Calls.target(function.target, new Object[]{0L, function.environment, 2000L}));
                        code.requireInstalledCode();
                        assertEquals(2000L, first.diagnostics().get("selfTailReentries"));
                        assertEquals(0L, second.diagnostics().get("selfTailReentries"));
                        assertEquals(0L, Calls.target(other.target, new Object[]{0L, other.environment, 7L}));
                        code.requireInstalledCode();
                        assertEquals(7L, second.diagnostics().get("selfTailReentries"));
                        assertEquals(2000L, first.diagnostics().get("selfTailReentries"));
                        assertEquals(1L, first.diagnostics().get("compiledEntries"));
                        assertEquals(1L, second.diagnostics().get("compiledEntries"));
                    } finally { context.leave(); }
                }
            } finally {
                if (previous == null) System.clearProperty("thc.requireCompiledCode"); else System.setProperty("thc.requireCompiledCode", previous);
            }
        }
    }
}
