// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.TruffleLanguage;
import java.util.*;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;
import thc.runtime.*;
import static org.junit.jupiter.api.Assertions.*;
import static thc.CoreFormatTestSupport.*;

/** Nested closures have separate lexical environments and executable targets, reused across calls. */
class CoreLoweringRootsTest {
    private Map<String, Object> integer() { return map("kind", "long", "primReps", List.of("IntRep"), "evaluated", true); }
    private Map<String, Object> binder(String id) { return map("id", id, "name", id, "lifted", false, "rep", integer()); }
    private List<Object> variable(String id) { return list("var", id, map("rep", integer())); }
    @Test void nestedClosureRootsAreNecessaryAndAreNotRecreatedPerInvocationOrAsyncCheckpoint() {
        var integer = integer();
        var closure = map("kind", "closure", "primReps", List.of("BoxedRep (Just Lifted)"), "evaluated", true);
        var add = list("app", list("prim", "+#"), list(variable("outer"), variable("inner")), list(false, false), false, false, map("rep", integer));
        var inner = list("lam", list(binder("inner")), add, map("rep", closure, "resultRep", integer));
        var outer = list("lam", list(binder("outer")), inner, map("rep", closure, "resultRep", closure));
        var binding = map("id", "entry", "name", "entry", "arity", 1, "lifted", true, "rep", closure, "expr", outer);
        var module = map("schema", 1, "ghc", "9.14.1", "module", "Nested", "constructors", List.of(), "bindings", list(binding));
        for (var backend : List.of("ast", "bytecode")) for (boolean async : new boolean[]{false, true})
            try (var context = Context.newBuilder("thc").allowExperimentalOptions(true).option("engine.Compilation", "false").build()) {
                context.initialize("thc"); context.enter();
                try {
                    var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                    ExecutableProgram program = backend.equals("bytecode") ? new BytecodeProgram(language, module, async) : new Program(language, module, async, false);
                    var roots = program.diagnostics().get("loweredRootCount");
                    if (backend.equals("bytecode")) assertEquals(3L, ((Number) roots).longValue(),
                        "One legacy module initializer and two lexical function roots; async cuts are not new roots");
                    var entry = program.entryValue("entry"); var host = program.hostEntryTarget(1);
                    RootCallTarget nestedTarget = null;
                    for (long[] pair : new long[][]{{40, 2}, {Long.MAX_VALUE, 1}, {-4, 9}}) {
                        var nested = (Closure) Calls.target(host, new Object[]{entry, new Object[]{pair[0]}});
                        if (nestedTarget == null) nestedTarget = nested.target; else assertSame(nestedTarget, nested.target);
                        assertEquals(pair[0] + pair[1], Calls.target(host, new Object[]{nested, new Object[]{pair[1]}}));
                        assertEquals(roots, program.diagnostics().get("loweredRootCount"), "Closure instances reuse lowered targets");
                    }
                } finally { context.leave(); }
            }
    }
}
