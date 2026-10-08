// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc;

import java.nio.file.Path;
import java.util.*;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Value;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static thc.CoreExecutionTestSupport.*;

/** Public CBD controls use synthetic models, not claims about original GHC exports. */
class AggregateFrontierTest {
    @TempDir Path temporary;
    @AfterEach void releaseMappings() { CoreFileMappings.shared.evictIdleBelow(temporary); }
    private final Map<String, Object> integer = map("kind", "long", "evaluated", true, "primReps", list("IntRep"));
    private final Map<String, Object> closure = map("kind", "closure", "evaluated", true, "primReps", list("BoxedRep (Just Lifted)"));
    private Map<String, Object> binder(String name, Map<String, Object> rep) {
        return map("id", name, "name", name, "lifted", rep == closure, "coercion", false, "rep", rep);
    }
    private List<Object> literal(long value) { return list("lit", "int", Long.toString(value), map("rep", integer)); }
    private Map<String, Object> function(String name, Map<String, Object> input, Object body) {
        var binding = map("id", "main:Frontier." + name, "name", name, "lifted", true, "rep", closure, "arity", 1);
        binding.put("expr", list("lam", list(binder("x", input)), body, map("rep", closure, "resultRep", integer)));
        return binding;
    }
    private Path model(List<Map<String, Object>> bindings, List<Map<String, Object>> constructors) throws Exception {
        return CoreCbdFixtures.write(temporary.resolve("Frontier.cbd"), map("schema", 1, "ghc", "9.14.1", "unit", "main", "module", "Frontier",
            "boundary", "optimized-Core-after-Tidy-before-CorePrep", "constructors", constructors, "bindings", bindings));
    }
    private Context context() { return Main.withContextProfile(Context.newBuilder("thc"), ContextProfile.SYNCHRONOUS_TEST).build(); }
    private long compiledEntries(Value function) { return ((Number) object(Json.parse(function.getMember("diagnostics").asString())).get("compiledEntries")).longValue(); }
    @Test void diagnosticModeKeepsLegacyColdAggregatePathsLazyAndTrapsWhenReached() throws Exception {
        var argument = list("var", "x", map("rep", integer));
        var coldCall = list("app", list("var", "main:Frontier.cold", map("rep", closure)), list(argument), list(false), false, false, map("rep", integer));
        var choice = list("case", argument, "selected", list(list("lit", list("int", "0"), list(), literal(-7), map("binders", list())),
            list("default", null, list(), coldCall, map("binders", list()))), map("rep", integer, "binder", binder("selected", integer)));
        // No aggregate proof: this constructor must fail when the separate cold definition is demanded.
        var malformed = list("app", list("con", "main:Frontier.Sum", 1, map()), list(argument), list(false), false, false, map("rep", integer));
        var artifact = model(list(function("entry", integer, choice), function("cold", integer, malformed)),
            list(map("id", "main:Frontier.Sum", "name", "Sum", "kind", "unboxed-sum", "arity", 1, "tag", 1, "sumArity", 2,
                "strictFields", list(false), "fieldLifted", list(false), "fieldReps", list(list("IntRep")), "fieldTypes", list(integer))));
        for (String backend : list("ast", "bytecode")) {
            try (var context = context()) {
                var function = context.eval("thc", CoreModules.request(list(artifact.toString()), "main:Frontier.entry", true, false, backend));
                assertEquals(-7L, function.execute(0L).asLong(), backend + ": strict public loading leaves the cold definition lazy");
                var error = assertThrows(PolyglotException.class, () -> function.execute(1L));
                assertTrue(Objects.toString(error.getMessage(), "").contains("Unsupported constructor representation unboxed-sum"), backend + ": " + error.getMessage());
            }
            try (var context = context()) {
                var function = context.eval("thc", CoreModules.request(list(artifact.toString()), "main:Frontier.entry", true, true, backend));
                assertEquals(-7L, function.execute(0L).asLong());
                assertTrue(function.invokeMember("compile").asBoolean(), backend);
                long before = compiledEntries(function);
                assertEquals(-7L, function.execute(0L).asLong());
                assertTrue(compiledEntries(function) > before, backend + ": first installed call precedes the cold trap");
                var error = assertThrows(PolyglotException.class, () -> function.execute(1L));
                assertTrue(Objects.toString(error.getMessage(), "").contains("Diagnostic unsupported path reached: Unsupported constructor representation unboxed-sum"), backend + ": " + error.getMessage());
            }
        }
    }
    @Test void diagnosticModeStillValidatesHostShapesIncludingUnusedFormals() throws Exception {
        var empty = map("kind", "unknown", "evaluated", true, "primReps", list(), "aggregate", "unboxed-tuple", "components", list());
        var artifact = model(list(function("entry", empty, literal(41))), list());
        for (String backend : list("ast", "bytecode")) try (var context = context()) {
            var function = context.eval("thc", CoreModules.request(list(artifact.toString()), "main:Frontier.entry", true, true, backend));
            assertEquals(41L, function.execute((Object) new Object[0]).asLong(), backend);
            var error = assertThrows(PolyglotException.class, () -> function.execute(0L));
            assertTrue(Objects.toString(error.getMessage(), "").contains("Host ABI requires an array of"), backend + ": " + error.getMessage());
        }
    }
}
