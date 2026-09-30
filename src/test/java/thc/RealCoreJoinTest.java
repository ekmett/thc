// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc;

import java.nio.file.*;
import java.util.*;
import org.graalvm.polyglot.Value;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static thc.CoreExecutionTestSupport.*;

/** GHC's actual join annotations, including erased type arguments and result lambdas. */
class RealCoreJoinTest {
    @TempDir Path temporary;
    private final Path root = Path.of(System.getProperty("thc.projectRoot"));
    private Map<String, Object> exported() throws Exception { return CoreCbdFixtures.pairedDiagnostic(root.resolve("build/source-core/RepresentationAudit.cbd")); }
    private long count(Value function, String key) { return ((Number) object(Json.parse(function.getMember("diagnostics").asString())).get(key)).longValue(); }
    private List<Object> variable(String id) { return list("var", id, map()); }
    private List<Object> integer(long value) { return list("lit", "int", Long.toString(value), map()); }
    private List<Object> apply(List<Object> fn, List<List<Object>> args, List<Boolean> lifted) { return list("app", fn, args, lifted, false, false, map()); }
    private String id(List<Map<String, Object>> definitions, String name) {
        var matches = definitions.stream().filter(item -> name.equals(item.get("name"))).toList();
        assertEquals(1, matches.size()); return (String) matches.getFirst().get("id");
    }
    private void checkLoop(Value function, String backend, long n) { assertEquals(n <= 0 ? 0L : n * (n + 1) / 2, function.execute(n).asLong(), backend + " joinLoop(" + n + ")"); }
    @Test void exportedRecursiveJoinIsALocalLoopBeforeAndAfterCompilation() throws Exception {
        var module = exported();
        for (String backend : list("ast", "bytecode")) try (var context = Main.executionContext(false)) {
            var function = context.eval("thc", CoreModules.request(list(root.resolve("build/source-core/RepresentationAudit.cbd").toString()), "main:RepresentationAudit.joinLoop", true, false, backend));
            for (int i = 0; i < 8; i++) checkLoop(function, backend, i);
            checkLoop(function, backend, 100_000);
            assertTrue(function.invokeMember("compile").asBoolean()); long before = count(function, "compiledEntries");
            for (long n : new long[]{-1, 0, 1, 100_000}) checkLoop(function, backend, n);
            assertTrue(count(function, "compiledEntries") > before);
            assertTrue(count(function, "localJoinTransfers") >= 100_000L, "Must execute the exported local join");
            assertEquals(0L, count(function, "tailBounces"), "Local joins do not use function tail transfers");
            assertEquals(0L, count(function, "trampolineIterations"));
        }
    }
    private List<Object> invocation(String name, String id, List<Object> value) {
        var args = new ArrayList<>(list(variable("input"), value)); var lifted = new ArrayList<>(list(false, true));
        if (name.equals("functionJoin")) {
            args.add(apply(list("prim", "+#", map()), list(variable("input"), integer(5)), list(false, false))); lifted.add(false);
        }
        return apply(variable(id), args, lifted);
    }
    private void checkJoin(Value function, String backend, String name, long n) {
        assertEquals(name.equals("polyJoin") ? n + 7 : n + n + 5, function.execute(n).asLong(), backend + " " + name + "(" + n + ")");
    }
    @Test void erasedTypeJoinAndFunctionReturningJoinPreserveTheirValueArity() throws Exception {
        var module = exported(); var bindings = objects(module.get("bindings")); var constructors = objects(module.get("constructors"));
        String end = id(constructors, "End"), box = id(constructors, "Box");
        for (String name : list("polyJoin", "functionJoin")) {
            String id = id(bindings, name);
            List<Object> empty = list("con", end, 0, map());
            var nonempty = apply(list("con", box, 2, map()), list(variable("input"), empty), list(false, true));
            var body = list("case", variable("input"), "choice", list(
                list("lit", list("int", "0"), list(), invocation(name, id, empty), map("binders", list())),
                list("default", null, list(), invocation(name, id, nonempty), map("binders", list()))), map());
            var lambda = list("lam", list(map("id", "input", "name", "input", "type", "Int#", "lifted", false, "coercion", false, "rep", map("kind", "long", "primReps", list("IntRep"), "evaluated", true))), body, map());
            var driver = map("schema", 1, "ghc", "9.14.1", "module", "Synthetic.RealJoinDriver", "unit", "main", "boundary", "main", "constructors", list(),
                "bindings", list(map("id", "driver", "name", "driver", "type", "Int# -> Int#", "lifted", true, "arity", 1, "expr", lambda)));
            var artifact = CoreCbdFixtures.write(temporary.resolve(name + ".cbd"), driver);
            for (String backend : list("ast", "bytecode")) try (var context = Main.executionContext(false)) {
                var function = context.eval("thc", CoreModules.request(list(root.resolve("build/source-core/RepresentationAudit.cbd").toString(), artifact.toString()), "driver", true, false, backend));
                for (int i = 0; i < 8; i++) checkJoin(function, backend, name, i);
                assertTrue(function.invokeMember("compile").asBoolean()); long before = count(function, "compiledEntries");
                for (long n : new long[]{0L, 1L, -1L, 3_000_000_000L, Long.MAX_VALUE}) checkJoin(function, backend, name, n);
                assertTrue(count(function, "compiledEntries") > before); assertEquals(0L, count(function, "unsupportedTraps"));
            }
        }
    }
}
